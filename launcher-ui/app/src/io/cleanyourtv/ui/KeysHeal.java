package io.cleanyourtv.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.io.File;

/**
 * The remote's buttons (RemoteKeyService) stop working inside other apps when the TV force-closes this app to free
 * memory (a TCL C6K does): Android then switches the accessibility service off, with no sign why. When it had been on
 * and you didn't switch it off yourself, and the app may write secure settings (granted once over ADB:
 * pm grant io.cleanyourtv.ui android.permission.WRITE_SECURE_SETTINGS), it is switched back on when Home shows.
 */
final class KeysHeal {
    private KeysHeal() {}

    /** From the service (its own process): on = it is running; off = you switched it off in Accessibility. */
    static void mark(Context c, boolean on) {
        File was = new File(c.getFilesDir(), "keys-was-on"), off = new File(c.getFilesDir(), "keys-off-by-user");
        try {
            if (on) {
                //noinspection ResultOfMethodCallIgnored
                was.createNewFile();
                //noinspection ResultOfMethodCallIgnored
                off.delete();
            } else {
                //noinspection ResultOfMethodCallIgnored
                off.createNewFile();
            }
        } catch (java.io.IOException ignored) {
        }
    }

    static void check(Context c) {
        try {
            String me = c.getPackageName() + "/" + RemoteKeyService.class.getName();
            String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list != null && (list.contains(me) || list.contains(c.getPackageName() + "/.RemoteKeyService"))) return;
            if (!new File(c.getFilesDir(), "keys-was-on").exists() || new File(c.getFilesDir(), "keys-off-by-user").exists()) return;
            if (c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return;
            String next = list == null || list.isEmpty() ? me : list + ":" + me;
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            AppLog.w("remote buttons: switched back on");
        } catch (Exception e) {
            AppLog.w("remote buttons: " + e.getMessage());
        }
    }
}
