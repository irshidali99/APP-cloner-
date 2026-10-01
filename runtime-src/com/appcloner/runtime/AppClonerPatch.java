/*
 * Runtime patch that App Cloner injects into a clone as an extra classesN.dex together with a
 * ContentProvider entry in the clone's manifest.
 *
 * The provider is created by the platform before any activity and before the app's own Application
 * object runs, so the patch can install itself first. Only framework classes are used: the patch is
 * compiled once by CI and shipped inside App Cloner as assets/runtime/patch.dex, which means it must
 * never reference a class of the cloned app.
 *
 * Everything is configured through the provider's meta-data (com.appcloner.runtime.CONFIG):
 *   lock=<sha256 of the passcode>;shots=1;wipe=1;screenoff=1
 */
package com.appcloner.runtime;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.view.WindowManager;

import java.io.File;
import java.security.MessageDigest;

public final class AppClonerPatch implements Application.ActivityLifecycleCallbacks {

    /** Meta-data key the clone reads its own configuration from. */
    public static final String CONFIG_META = "com.appcloner.runtime.CONFIG";

    /** Salt for the one time reset code shown in App Cloner (never stored inside the clone). */
    private static final String RESET_SALT = "appcloner-reset:";

    private static boolean installed;
    private static boolean screenOffReceiverRegistered;

    private static String lockHash = "";
    private static boolean blockScreenshots;
    private static boolean incognitoWipe;
    private static boolean exitOnScreenOff;

    /** Process wide unlock flag: the clone asks for the passcode once per run. */
    private static volatile boolean unlocked;

    private static int startedActivities;

    private AppClonerPatch() {
    }

    /** Called once by [PatchProvider] right after the process starts. */
    public static void install(Context context, String config) {
        if (context == null) return;
        try {
            parseConfig(config);
            if (installed) return;
            installed = true;
            Context app = context.getApplicationContext();
            if (app == null) app = context;
            if (app instanceof Application) {
                ((Application) app).registerActivityLifecycleCallbacks(new AppClonerPatch());
            }
            if (exitOnScreenOff) registerScreenOffReceiver(app);
        } catch (Throwable error) {
            // A clone must never crash because of the patch: every feature is best effort.
            installed = true;
        }
    }

    private static void parseConfig(String config) {
        lockHash = "";
        blockScreenshots = false;
        incognitoWipe = false;
        exitOnScreenOff = false;
        if (config == null) return;
        String[] parts = config.split(";");
        for (String part : parts) {
            int separator = part.indexOf('=');
            String key = separator < 0 ? part.trim() : part.substring(0, separator).trim();
            String value = separator < 0 ? "" : part.substring(separator + 1).trim();
            if ("lock".equals(key)) lockHash = value;
            else if ("shots".equals(key)) blockScreenshots = "1".equals(value);
            else if ("wipe".equals(key)) incognitoWipe = "1".equals(value);
            else if ("screenoff".equals(key)) exitOnScreenOff = "1".equals(value);
        }
    }

    public static boolean hasLock() {
        return lockHash != null && lockHash.length() > 0;
    }

    public static boolean isUnlocked() {
        return unlocked;
    }

    public static void markUnlocked() {
        unlocked = true;
    }

    /** True when [value] is the passcode the clone was configured with. */
    public static boolean verifyPasscode(String value) {
        if (!hasLock()) return true;
        return lockHash.equals(sha256(value == null ? "" : value));
    }

    /** True when [value] is the reset code App Cloner shows for this clone. */
    public static boolean verifyResetCode(Context context, String value) {
        if (context == null || value == null) return false;
        String expected = resetCodeOf(context.getPackageName());
        return expected.equals(value.trim().toUpperCase());
    }

    /** The reset code depends on the clone's package name only, so both sides agree without storing it. */
    public static String resetCodeOf(String packageName) {
        String digest = sha256(RESET_SALT + (packageName == null ? "" : packageName));
        return digest.substring(0, 8).toUpperCase();
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                String hex = Integer.toHexString(current & 0xff);
                if (hex.length() == 1) builder.append('0');
                builder.append(hex);
            }
            return builder.toString();
        } catch (Throwable error) {
            return "";
        }
    }

    // ------------------------------------------------------------------ activity lifecycle

    @Override
    public void onActivityCreated(Activity activity, Bundle state) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
        startedActivities++;
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (activity == null) return;
        try {
            if (blockScreenshots) {
                activity.getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE
                );
            }
        } catch (Throwable ignored) {
        }
        if (!hasLock() || unlocked) return;
        if (activity instanceof LockActivity) return;
        try {
            Intent intent = new Intent();
            intent.setClassName(activity, LockActivity.class.getName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            activity.startActivity(intent);
        } catch (Throwable ignored) {
            // If the lock screen cannot be opened the app stays usable - never lock the user out.
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
        if (startedActivities > 0) startedActivities--;
        if (startedActivities > 0) return;
        if (incognitoWipe) wipeInBackground(activity);
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle state) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }

    // ------------------------------------------------------------------ incognito

    /** Removes everything the app stored while it was used, so nothing stays on the device. */
    private static void wipeInBackground(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext() == null
            ? context
            : context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    deleteTree(app.getCacheDir());
                    deleteTree(app.getFilesDir());
                    deleteTree(new File(app.getApplicationInfo().dataDir));
                } catch (Throwable ignored) {
                }
            }
        }, "appcloner-incognito").start();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child);
            }
        }
        file.delete();
    }

    // ------------------------------------------------------------------ screen off exit

    private static void registerScreenOffReceiver(Context context) {
        if (screenOffReceiverRegistered) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (intent == null) return;
                if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) return;
                try {
                    Process.killProcess(Process.myPid());
                } catch (Throwable ignored) {
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // Android 13+ wants an explicit export flag for dynamically registered receivers.
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            screenOffReceiverRegistered = true;
        } catch (Throwable ignored) {
            try {
                context.registerReceiver(receiver, filter);
                screenOffReceiverRegistered = true;
            } catch (Throwable ignoredToo) {
            }
        }
    }
}
