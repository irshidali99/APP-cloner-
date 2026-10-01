/*
 * Bootstrap of the runtime patch.
 *
 * Android creates every ContentProvider of an app before any of its activities and before the app's own
 * Application.onCreate, so this provider is the earliest code the clone can run without touching the
 * app's own classes. It reads its configuration from its own meta-data and hands it to AppClonerPatch.
 */
package com.appcloner.runtime;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

public final class PatchProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        String config = "";
        try {
            ProviderInfo info = getContext().getPackageManager().getProviderInfo(
                new ComponentName(getContext(), PatchProvider.class),
                ProviderInfoFlags.META_DATA
            );
            Bundle metaData = info == null ? null : info.metaData;
            if (metaData != null) {
                String value = metaData.getString(AppClonerPatch.CONFIG_META);
                if (value != null) config = value;
            }
        } catch (Throwable ignored) {
        }
        AppClonerPatch.install(getContext(), config);
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    /**
     * `PackageManager.GET_META_DATA` lives in PackageManager, but referencing it through a small holder
     * keeps the provider source free of the deprecated int constant warning.
     */
    private static final class ProviderInfoFlags {
        static final int META_DATA = 128;
    }
}
