package id.maxdis.timer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        // Tidak ada timer aktif (alarm basi setelah reset): tidak perlu menyalakan Service sama sekali.
        if (!TimerState.isRunning(context) && !TimerState.isFinished(context)) return;
        try {
            TimerService.start(context, TimerService.ACTION_FINISH);
        } catch (RuntimeException e) {
            // Android 12+ dapat menolak startForegroundService dari latar belakang
            // (ForegroundServiceStartNotAllowedException). Jangan crash: tandai selesai dan bunyikan notifikasi sistem.
            Log.e("MAXDIS", "Service tidak dapat dijalankan dari AlarmReceiver", e);
            TimerService.recoverWithoutService(context);
        }
    }
}
