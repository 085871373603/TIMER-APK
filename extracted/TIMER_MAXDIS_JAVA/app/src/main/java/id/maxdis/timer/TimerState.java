package id.maxdis.timer;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Durable session state. Timer deadlines use wall-clock time so a reboot can restore the remaining time.
 *
 * <p>Semua transisi status (start, selesai, lanjut) ditulis dengan {@code commit()} agar tetap tersimpan
 * meskipun proses dimatikan sistem sesaat setelahnya. Semua pemanggil berjalan di main thread.
 */
public final class TimerState {
    private static final String PREF = "timer_maxdis_native_state";

    /** Sumber waktu; dapat diganti pada unit test. */
    interface Clock { long now(); }

    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override public long now() { return System.currentTimeMillis(); }
    };
    private static Clock clock = SYSTEM_CLOCK;

    private TimerState() {}

    /** Hanya untuk unit test. Berikan null untuk mengembalikan jam sistem. */
    static void setClockForTest(Clock testClock) { clock = testClock == null ? SYSTEM_CLOCK : testClock; }

    private static long now() { return clock.now(); }

    private static SharedPreferences prefs(Context c) {
        Context app = c.getApplicationContext();
        return (app != null ? app : c).getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /**
     * Memulai timer untuk lorong {@code index}.
     *
     * @return false bila ditolak karena timer sedang berjalan / menunggu LANJUT, sesi sudah selesai, atau lorong tidak sesuai urutan
     * @throws IllegalArgumentException bila Qty di luar 1..{@link TimerLogic#MAX_QTY}
     */
    public static boolean start(Context c, int index, long qty) {
        long duration = TimerLogic.durationSeconds(qty); // validasi dahulu sebelum menyentuh state
        SharedPreferences p = prefs(c);
        if (p.getBoolean("running", false) || p.getBoolean("finished", false)) return false;
        if (p.getBoolean("sessionCompleted", false)) return false;
        if (index < 0 || index >= TimerLogic.CODES.length) return false;
        if (index != index(c)) return false;
        long now = now();
        SharedPreferences.Editor e = p.edit();
        if (index == 0 && getRecords(c).isEmpty()) e.putLong("sessionStart", now);
        e.putBoolean("active", true)
                .putBoolean("running", true)
                .putBoolean("finished", false)
                .putBoolean("sessionCompleted", false)
                .putInt("index", index)
                .putLong("currentQty", qty)
                .putLong("currentDuration", duration)
                .putLong("currentStart", now)
                .putLong("endEpoch", now + duration * 1000L);
        return e.commit();
    }

    /**
     * Menandai lorong aktif selesai. Waktu selesai dicatat sebagai batas akhir timer (endEpoch), bukan saat
     * kode ini kebetulan berjalan, sehingga alarm yang terlambat (Doze) atau pemulihan setelah reboot tidak
     * menggelembungkan "Waktu Kerja".
     */
    public static void markFinished(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("running", false)) return;
        int i = p.getInt("index", 0);
        long qty = p.getLong("currentQty", 0);
        long start = p.getLong("currentStart", 0);
        long end = p.getLong("endEpoch", 0);
        long now = now();
        long finish = end > 0 ? Math.min(now, end) : now;
        if (finish < start) finish = start; // jam sistem diubah mundur
        p.edit()
                .putBoolean("running", false)
                .putBoolean("finished", true)
                .putBoolean("active", true)
                .putBoolean("done_" + i, true)
                .putLong("qty_" + i, qty)
                .putLong("record_start_" + i, start)
                .putLong("record_finish_" + i, finish)
                .commit();
    }

    /** Returns true when all six lorongs have been confirmed. Aman dipanggil berulang (idempoten). */
    public static boolean advance(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("finished", false)) return false;
        int next = p.getInt("index", 0) + 1;
        if (next >= TimerLogic.CODES.length) {
            p.edit().putBoolean("finished", false)
                    .putBoolean("running", false)
                    .putBoolean("active", false)
                    .putBoolean("sessionCompleted", true)
                    .commit();
            return true;
        }
        p.edit().putInt("index", next)
                .putBoolean("finished", false)
                .putBoolean("running", false)
                .putBoolean("active", true)
                .commit();
        return false;
    }

    public static void resetCurrent(Context c) {
        SharedPreferences p = prefs(c);
        int i = p.getInt("index", 0);
        SharedPreferences.Editor e = p.edit()
                .putBoolean("running", false)
                .putBoolean("finished", false)
                .putLong("currentQty", 0)
                .putLong("currentDuration", 0)
                .putLong("currentStart", 0)
                .putLong("endEpoch", 0)
                .remove("done_" + i)
                .remove("qty_" + i)
                .remove("record_start_" + i)
                .remove("record_finish_" + i);
        if (i == 0 && getRecords(c).isEmpty()) e.putLong("sessionStart", 0);
        e.commit();
    }

    public static void resetSession(Context c) {
        prefs(c).edit().clear().commit();
    }

    public static boolean isActive(Context c) { return prefs(c).getBoolean("active", false); }
    public static boolean isRunning(Context c) { return prefs(c).getBoolean("running", false); }
    public static boolean isFinished(Context c) { return prefs(c).getBoolean("finished", false); }
    public static boolean isSessionCompleted(Context c) { return prefs(c).getBoolean("sessionCompleted", false); }
    public static int index(Context c) { return Math.max(0, Math.min(TimerLogic.CODES.length - 1, prefs(c).getInt("index", 0))); }
    public static long currentQty(Context c) { return prefs(c).getLong("currentQty", 0); }
    public static long currentStart(Context c) { return prefs(c).getLong("currentStart", 0); }
    public static long endEpoch(Context c) { return prefs(c).getLong("endEpoch", 0); }
    public static long quantity(Context c, int i) { return prefs(c).getLong("qty_" + i, 0); }
    public static boolean done(Context c, int i) { return prefs(c).getBoolean("done_" + i, false); }
    public static long sessionStart(Context c) { return prefs(c).getLong("sessionStart", 0); }

    public static List<TimerLogic.Record> getRecords(Context c) {
        SharedPreferences p = prefs(c);
        List<TimerLogic.Record> records = new ArrayList<>();
        for (int i = 0; i < TimerLogic.CODES.length; i++) {
            if (p.getBoolean("done_" + i, false)) {
                records.add(new TimerLogic.Record(i,
                        p.getLong("qty_" + i, 0),
                        p.getLong("record_start_" + i, 0),
                        p.getLong("record_finish_" + i, 0)));
            }
        }
        return records;
    }
}
