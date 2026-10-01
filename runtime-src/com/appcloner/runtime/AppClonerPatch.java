/*
 * Runtime patch that App Cloner injects into a clone as an extra classesN.dex together with a
 * ContentProvider entry in the clone's manifest.
 *
 * The provider is created by the platform before any activity, service or receiver of the clone, so the
 * patch can install itself before the first screen appears. Only framework classes are used: the patch is
 * compiled once by CI and shipped inside App Cloner as assets/runtime/patch.dex, so it must never
 * reference a class of the cloned app.
 *
 * Everything is configured through the provider's meta-data (com.appcloner.runtime.CONFIG):
 *   mode=passcode;lock=<sha256>;pattern=<sha256>;shots=1;wipe=1;screenoff=1;dark=1;confirm=1;shake=1;fab=1;lang=ur
 *
 * Platform APIs that only exist on newer Android versions are reached through [ApiGates], so an older
 * device never has to load a class it does not have.
 */
package com.appcloner.runtime;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.security.MessageDigest;
import java.util.WeakHashMap;

public final class AppClonerPatch implements Application.ActivityLifecycleCallbacks {

    /** Meta-data key the clone reads its own configuration from. */
    public static final String CONFIG_META = "com.appcloner.runtime.CONFIG";

    /** Salt for the one time reset code shown in App Cloner (never stored inside the clone). */
    private static final String RESET_SALT = "appcloner-reset:";

    private static final float SHAKE_THRESHOLD = 13.0f;
    private static final long CONFIRM_EXIT_WINDOW_MILLIS = 2000L;

    private static boolean installed;
    private static boolean screenOffReceiverRegistered;

    private static String lockMode = "none";
    private static String lockHash = "";
    private static String patternHash = "";
    private static String appLanguage = "";
    private static boolean blockScreenshots;
    private static boolean incognitoWipe;
    private static boolean exitOnScreenOff;
    private static boolean confirmExit;
    private static boolean shakeToExit;
    private static boolean floatingBackButton;

    /** Process wide unlock flag: the clone asks for the passcode once per run. */
    private static volatile boolean unlocked;

    private static int startedActivities;
    private static Context lastContext;
    private static SensorManager sensorManager;
    private static final WeakHashMap<Activity, Boolean> prepared = new WeakHashMap<Activity, Boolean>();

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
            lastContext = app;
            if (app instanceof Application) {
                ((Application) app).registerActivityLifecycleCallbacks(new AppClonerPatch());
            }
            if (exitOnScreenOff) registerScreenOffReceiver(app);
            // Both of these apply to the whole clone, not to a single activity.
            ApiGates.applyDarkMode(app);
            ApiGates.applyLanguage(app, appLanguage);
        } catch (Throwable error) {
            // A clone must never crash because of the patch: every feature is best effort.
            installed = true;
        }
    }

    private static void parseConfig(String config) {
        lockMode = "none";
        lockHash = "";
        patternHash = "";
        appLanguage = "";
        blockScreenshots = false;
        incognitoWipe = false;
        exitOnScreenOff = false;
        confirmExit = false;
        shakeToExit = false;
        floatingBackButton = false;
        ApiGates.setDarkWanted(false);
        if (config == null) return;
        String[] parts = config.split(";");
        for (String part : parts) {
            int separator = part.indexOf('=');
            String key = separator < 0 ? part.trim() : part.substring(0, separator).trim();
            String value = separator < 0 ? "" : part.substring(separator + 1).trim();
            if ("lock".equals(key)) lockHash = value;
            else if ("pattern".equals(key)) patternHash = value;
            else if ("mode".equals(key)) lockMode = value.isEmpty() ? "none" : value;
            else if ("shots".equals(key)) blockScreenshots = "1".equals(value);
            else if ("wipe".equals(key)) incognitoWipe = "1".equals(value);
            else if ("screenoff".equals(key)) exitOnScreenOff = "1".equals(value);
            else if ("dark".equals(key)) ApiGates.setDarkWanted("1".equals(value));
            else if ("confirm".equals(key)) confirmExit = "1".equals(value);
            else if ("shake".equals(key)) shakeToExit = "1".equals(value);
            else if ("fab".equals(key)) floatingBackButton = "1".equals(value);
            else if ("lang".equals(key)) appLanguage = value;
        }
    }

    /** True when the clone asks for a passcode, a pattern or the calculator disguise. */
    public static boolean hasLock() {
        if ("none".equals(lockMode)) return false;
        if ("pattern".equals(lockMode)) return patternHash != null && patternHash.length() > 0;
        return lockHash != null && lockHash.length() > 0;
    }

    public static boolean isPatternLock() {
        return "pattern".equals(lockMode);
    }

    public static boolean isCalculatorLock() {
        return "calc".equals(lockMode);
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

    /** True when [value] is the pattern of this clone ("0,1,4,7" for the dots that were drawn). */
    public static boolean verifyPattern(String value) {
        if (!hasLock()) return true;
        return patternHash.equals(sha256(value == null ? "" : value));
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

    static void toast(Context context, String message) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    static long confirmExitWindow() {
        return CONFIRM_EXIT_WINDOW_MILLIS;
    }

    static long now() {
        return SystemClock.elapsedRealtime();
    }

    // ------------------------------------------------------------------ activity lifecycle

    @Override
    public void onActivityCreated(Activity activity, Bundle state) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
        startedActivities++;
        if (shakeToExit) startShakeWatch();
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
        prepareActivity(activity);
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
        if (startedActivities == 0) stopShakeWatch();
        if (startedActivities > 0) return;
        if (incognitoWipe) wipeInBackground(activity);
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle state) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }

    // ------------------------------------------------------------------ per activity extras

    /** Adds the floating back button and the confirm-exit watcher once per activity. */
    private static void prepareActivity(final Activity activity) {
        if (activity == null || activity instanceof LockActivity) return;
        if (prepared.containsKey(activity)) return;
        prepared.put(activity, Boolean.TRUE);

        if (confirmExit) {
            try {
                BackWatcher.install(activity);
            } catch (Throwable ignored) {
            }
        }
        if (floatingBackButton) {
            try {
                final ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                decor.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            addFloatingBackButton(activity, decor);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    /** A small translucent button in the corner, drawn inside the clone's own window. */
    private static void addFloatingBackButton(final Activity activity, ViewGroup decor) {
        if (decor == null || decor.findViewWithTag("appcloner-back") != null) return;
        TextView button = new TextView(activity);
        button.setTag("appcloner-back");
        button.setText("\u2039");
        button.setTextSize(26f);
        button.setTextColor(0xFFFFFFFF);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(activity, 44), dp(activity, 44));
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.setMargins(0, 0, dp(activity, 12), dp(activity, 24));
        button.setLayoutParams(params);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    activity.onBackPressed();
                } catch (Throwable ignored) {
                }
            }
        });
        decor.addView(button);
    }

    private static int dp(Context context, int value) {
        try {
            return (int) (value * context.getResources().getDisplayMetrics().density);
        } catch (Throwable error) {
            return value;
        }
    }

    // ------------------------------------------------------------------ shake to exit

    private static void startShakeWatch() {
        if (sensorManager != null) return;
        try {
            if (lastContext == null) return;
            sensorManager = (SensorManager) lastContext.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager == null) return;
            Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer == null) {
                sensorManager = null;
                return;
            }
            sensorManager.registerListener(shakeListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Throwable error) {
            sensorManager = null;
        }
    }

    private static void stopShakeWatch() {
        try {
            if (sensorManager != null) sensorManager.unregisterListener(shakeListener);
        } catch (Throwable ignored) {
        }
        sensorManager = null;
    }

    private static final SensorEventListener shakeListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event == null || event.values == null || event.values.length < 3) return;
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            double force = Math.sqrt((double) (x * x + y * y + z * z));
            if (force < SHAKE_THRESHOLD) return;
            stopShakeWatch();
            killClone();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    /** Ends the clone process: used by "shake to exit" and "end with the screen". */
    private static void killClone() {
        try {
            Process.killProcess(Process.myPid());
        } catch (Throwable ignored) {
        }
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
                killClone();
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
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
