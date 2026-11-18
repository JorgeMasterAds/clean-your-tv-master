package io.cleanyourtv.ui;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.io.File;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 * It leaves two marks for KeysHeal: "on" once it has run, "off by you" when switched off in Accessibility (a force-stop
 * by the TV, which also switches it off, leaves no such mark, so it can be switched back on).
 */
public class RemoteKeyService extends AccessibilityService {
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        KeysHeal.mark(this, true);
    }

    @Override public boolean onUnbind(Intent intent) {
        KeysHeal.mark(this, false);   // switched off in Accessibility (a killed process never gets here)
        return super.onUnbind(intent);
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}
}
