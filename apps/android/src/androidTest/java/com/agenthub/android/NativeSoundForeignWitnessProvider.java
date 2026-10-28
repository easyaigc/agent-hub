package com.agenthub.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

/** Runner-APK-only named probe: fixed public tone, no input URI, grant, write or model operation. */
public final class NativeSoundForeignWitnessProvider extends ContentProvider {
    @Override public boolean onCreate() { return getContext() != null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String runner = getContext().getPackageName();
        if (!runner.endsWith(".test") || !runner.startsWith("com.easyai.agenthub.android.") ||
            !uri.toString().equals("content://" + runner + ".sound-witness/probe") ||
            projection != null || selection != null || args != null || order != null ||
            Binder.getCallingUid() == Process.myUid() ||
            getContext().getPackageManager().checkSignatures(Binder.getCallingUid(), Process.myUid()) != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("Only the signed native target may call the fixed probe");
        }
        int caller = Binder.getCallingUid();
        Uri sound = Uri.parse("content://" + runner.substring(0, runner.length() - 5) +
            ".chat-notification-sounds/v1/3579f427ab553b0fa3e71b1433a376b98acab91cae2d5a1247d9fa9ba4fab926.wav");
        int denied = 0;
        try (ParcelFileDescriptor ignored = getContext().getContentResolver().openFileDescriptor(sound, "r")) {
            // Success is deliberately reported as a test failure by the requesting target.
        } catch (SecurityException expected) {
            denied = 1;
        } catch (Exception unavailable) {
            throw new IllegalStateException("Fixed sound probe must reach the provider access boundary", unavailable);
        }
        MatrixCursor result = new MatrixCursor(new String[] {"witnessUid", "callerUid", "denied"});
        result.addRow(new Object[] {Process.myUid(), caller, denied});
        return result;
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/native-sound-witness"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
