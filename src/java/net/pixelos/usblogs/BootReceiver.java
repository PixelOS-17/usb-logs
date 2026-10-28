package net.pixelos.usblogs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.IOException;

/** Keeps a capture without root recording across reboots. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        try {
            if (AppCapture.current(context) != null) {
                Recorder.start(context);
            }
        } catch (IOException e) {
            Log.e("PixelOSLogs", "Cannot read the running capture", e);
        }
    }
}
