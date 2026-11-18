package id.maxdis.timer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Tombol LANJUT pada notifikasi. Langsung memajukan status tanpa menyalakan Service baru. */
public class NextActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        TimerService.advanceAndStop(context);
    }
}
