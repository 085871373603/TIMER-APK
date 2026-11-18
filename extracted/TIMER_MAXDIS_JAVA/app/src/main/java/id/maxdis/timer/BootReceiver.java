package id.maxdis.timer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Memulihkan timer setelah perangkat selesai boot atau aplikasi diperbarui (alarm sistem hilang pada kedua kasus). */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (!TimerState.isRunning(context) && !TimerState.isFinished(context)) return;
        try {
            TimerService.start(context, TimerService.ACTION_RESTORE);
        } catch (RuntimeException e) {
            Log.e("MAXDIS", "Service tidak dapat dipulihkan dari BootReceiver", e);
            TimerService.recoverWithoutService(context);
        }
    }
}
