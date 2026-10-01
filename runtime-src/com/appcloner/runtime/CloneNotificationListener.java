/*
 * "Notification filter" and "quiet time" for a clone.
 *
 * A NotificationListenerService only ever *sees* notifications - it cannot create them - and this one
 * ignores everything that does not belong to the clone it lives in: it only cancels the clone's own
 * notifications. Two rules are supported:
 *
 *  - quiet time: during the configured window (for example 22:00-07:00, across midnight as well) the
 *    clone's notifications are cancelled while they arrive,
 *  - filter words: a notification whose title or text contains one of the words is cancelled too.
 *
 * The user has to allow notification access for the clone once (Android does not allow it silently); the
 * clone's details screen in App Cloner offers a button that opens that settings page.
 */
package com.appcloner.runtime;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public final class CloneNotificationListener extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        try {
            if (notification == null) return;
            String packageName = notification.getPackageName();
            if (packageName == null || !packageName.equals(getPackageName())) return;
            if (!AppClonerPatch.shouldBlockNotification(notification)) return;
            cancelNotification(notification.getKey());
        } catch (Throwable ignored) {
            // A clone must never crash because of the patch.
        }
    }

    @Override
    public void onListenerConnected() {
        // Notifications that arrived before the listener was connected are cleaned up as well.
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return;
            for (StatusBarNotification notification : active) {
                if (notification == null) continue;
                String packageName = notification.getPackageName();
                if (packageName == null || !packageName.equals(getPackageName())) continue;
                if (!AppClonerPatch.shouldBlockNotification(notification)) continue;
                cancelNotification(notification.getKey());
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification notification) {
    }

    /** Text of a notification, used by the filter words. */
    static String textOf(StatusBarNotification notification) {
        try {
            Notification base = notification.getNotification();
            if (base == null || base.extras == null) return "";
            Bundle extras = base.extras;
            StringBuilder builder = new StringBuilder();
            append(builder, extras.getCharSequence(Notification.EXTRA_TITLE));
            append(builder, extras.getCharSequence(Notification.EXTRA_TEXT));
            append(builder, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            append(builder, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
            append(builder, extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
            return builder.toString();
        } catch (Throwable error) {
            return "";
        }
    }

    private static void append(StringBuilder builder, CharSequence value) {
        if (value == null) return;
        if (builder.length() > 0) builder.append(' ');
        builder.append(value);
    }
}
