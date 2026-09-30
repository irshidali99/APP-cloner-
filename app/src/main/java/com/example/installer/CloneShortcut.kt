package com.example.installer

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Launcher shortcut for a clone.
 *
 * Needed when a clone hides its launcher icon ("stealth"): the app then has no way to be started by
 * tapping the drawer, so a pinned shortcut keeps it reachable. The shortcut starts the clone's own
 * launcher activity, so nothing of the clone has to be modified for it.
 */
object CloneShortcut {

    /** True when the device's launcher supports pinned shortcuts. */
    fun isSupported(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /**
     * Asks the launcher to pin a shortcut for [clonePackageId].
     *
     * @return `true` when the launcher accepted the request (it may still show its own confirmation).
     */
    fun requestPin(context: Context, clonePackageId: String, label: String, iconPng: ByteArray? = null): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(clonePackageId)
            ?: return false
        launchIntent.action = Intent.ACTION_MAIN
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER)

        val shortcut = ShortcutInfoCompat.Builder(context, "clone-" + clonePackageId.hashCode())
            .setShortLabel(label)
            .setLongLabel(label)
            .setIntent(launchIntent)
            .apply { iconPng?.let { IconCompat.createWithData(it, 0, it.size) } }
            .build()

        return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }
}
