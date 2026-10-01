/*
 * "Confirm exit" for a clone: the first press of the back button only shows a hint, the second press
 * within the window really goes back.
 *
 * Implemented by wrapping the activity's window callback: the callback sees the back key before the
 * activity itself reacts. Every other call is forwarded unchanged, so an app never notices the wrapper.
 */
package com.appcloner.runtime;

import android.app.Activity;
import android.os.Build;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

final class BackWatcher implements Window.Callback {

    private final Activity activity;
    private final Window.Callback delegate;
    private long lastBackAt;

    private BackWatcher(Activity activity, Window.Callback delegate) {
        this.activity = activity;
        this.delegate = delegate;
    }

    /**
     * Wraps the current callback of [activity]'s window.
     *
     * Only used on Android 7+ : older releases verify the class eagerly, and the callback interface
     * mentions a class (SearchEvent) they do not have. Every clone of an older device simply keeps the
     * normal back behaviour.
     */
    static void install(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 24) return;
        Window window = activity.getWindow();
        if (window == null) return;
        Window.Callback current = window.getCallback();
        if (current == null || current instanceof BackWatcher) return;
        window.setCallback(new BackWatcher(activity, current));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null
            && event.getKeyCode() == KeyEvent.KEYCODE_BACK
            && event.getAction() == KeyEvent.ACTION_UP) {
            long now = AppClonerPatch.now();
            if (now - lastBackAt > AppClonerPatch.confirmExitWindow()) {
                lastBackAt = now;
                AppClonerPatch.toast(activity, "Press back again to exit");
                return true;
            }
        }
        return delegate.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        return delegate.dispatchKeyShortcutEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return delegate.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent event) {
        return delegate.dispatchTrackballEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return delegate.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        return delegate.dispatchPopulateAccessibilityEvent(event);
    }

    @Override
    public View onCreatePanelView(int featureId) {
        return delegate.onCreatePanelView(featureId);
    }

    @Override
    public boolean onCreatePanelMenu(int featureId, Menu menu) {
        return delegate.onCreatePanelMenu(featureId, menu);
    }

    @Override
    public boolean onPreparePanel(int featureId, View view, Menu menu) {
        return delegate.onPreparePanel(featureId, view, menu);
    }

    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        return delegate.onMenuOpened(featureId, menu);
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        return delegate.onMenuItemSelected(featureId, item);
    }

    @Override
    public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
        delegate.onWindowAttributesChanged(attrs);
    }

    @Override
    public void onContentChanged() {
        delegate.onContentChanged();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        delegate.onWindowFocusChanged(hasFocus);
    }

    @Override
    public void onAttachedToWindow() {
        delegate.onAttachedToWindow();
    }

    @Override
    public void onDetachedFromWindow() {
        delegate.onDetachedFromWindow();
    }

    @Override
    public void onPanelClosed(int featureId, Menu menu) {
        delegate.onPanelClosed(featureId, menu);
    }

    @Override
    public boolean onSearchRequested() {
        return delegate.onSearchRequested();
    }

    @Override
    public boolean onSearchRequested(SearchEvent searchEvent) {
        if (Build.VERSION.SDK_INT < 23) return false;
        return delegate.onSearchRequested(searchEvent);
    }

    @Override
    public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
        return delegate.onWindowStartingActionMode(callback);
    }

    @Override
    public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
        if (Build.VERSION.SDK_INT < 23) return null;
        return delegate.onWindowStartingActionMode(callback, type);
    }

    @Override
    public void onActionModeStarted(ActionMode mode) {
        delegate.onActionModeStarted(mode);
    }

    @Override
    public void onActionModeFinished(ActionMode mode) {
        delegate.onActionModeFinished(mode);
    }
}
