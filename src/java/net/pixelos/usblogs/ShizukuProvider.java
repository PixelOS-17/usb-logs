package net.pixelos.usblogs;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import moe.shizuku.api.BinderContainer;

/**
 * The Shizuku server calls this provider ("PACKAGE.shizuku", method "sendBinder") whenever
 * this app comes to the foreground, handing over its binder.
 */
public final class ShizukuProvider extends ContentProvider {
    private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Bundle call(String method, String arg, Bundle extras) {
        if (!"sendBinder".equals(method) || extras == null) {
            return null;
        }
        extras.setClassLoader(BinderContainer.class.getClassLoader());
        BinderContainer container = extras.getParcelable(EXTRA_BINDER);
        if (container == null || container.binder == null) {
            return null;
        }
        Shizuku.onBinderReceived(container.binder, getContext().getPackageName());
        return new Bundle();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
