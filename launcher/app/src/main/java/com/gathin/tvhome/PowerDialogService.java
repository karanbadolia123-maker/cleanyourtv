package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Lets the glass home open the system power menu. Ordinary apps cannot power off or restart a TV, but an
 * enabled accessibility service may ask the system to show its own power dialog. It listens to nothing.
 */
public class PowerDialogService extends AccessibilityService {

    private static volatile PowerDialogService instance;

    /** True when the system power dialog was requested; false when this service is not enabled. */
    public static boolean showPowerDialog() {
        PowerDialogService s = instance;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
