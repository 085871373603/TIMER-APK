package id.maxdis.timer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Menguji mesin status sesi (TimerState) tanpa perangkat, memakai Context dan SharedPreferences palsu. */
public class TimerStateTest {

    private static final long T0 = 1_800_000_000_000L;

    private FakeContext ctx;
    private long now;

    @Before public void setUp() {
        ctx = new FakeContext();
        now = T0;
        TimerState.setClockForTest(new TimerState.Clock() {
            @Override public long now() { return now; }
        });
    }

    @After public void tearDown() {
        TimerState.setClockForTest(null);
    }

    @Test public void freshStateIsIdle() {
        assertFalse(TimerState.isActive(ctx));
        assertFalse(TimerState.isRunning(ctx));
        assertFalse(TimerState.isFinished(ctx));
        assertFalse(TimerState.isSessionCompleted(ctx));
        assertEquals(0, TimerState.index(ctx));
        assertTrue(TimerState.getRecords(ctx).isEmpty());
    }

    @Test public void startStoresDeadlineFromQty() {
        assertTrue(TimerState.start(ctx, 0, 12));
        assertTrue(TimerState.isRunning(ctx));
        assertTrue(TimerState.isActive(ctx));
        assertEquals(12L, TimerState.currentQty(ctx));
        assertEquals(T0, TimerState.currentStart(ctx));
        assertEquals(T0 + 60_000L, TimerState.endEpoch(ctx));
        assertEquals(T0, TimerState.sessionStart(ctx));
    }

    @Test public void qty25LastsExactly125Seconds() {
        TimerState.start(ctx, 0, 25);
        assertEquals(T0 + 125_000L, TimerState.endEpoch(ctx));
    }

    @Test public void startIsRejectedWhileRunningOrWaitingForNext() {
        assertTrue(TimerState.start(ctx, 0, 12));
        long end = TimerState.endEpoch(ctx);
        now += 5_000L;
        assertFalse("klik ganda tidak boleh memulai ulang", TimerState.start(ctx, 0, 99));
        assertEquals(end, TimerState.endEpoch(ctx));
        assertEquals(12L, TimerState.currentQty(ctx));

        now = end;
        TimerState.markFinished(ctx);
        assertFalse(TimerState.start(ctx, 0, 5));
    }

    @Test public void startRejectsWrongLorongIndex() {
        assertFalse(TimerState.start(ctx, 3, 12));
        assertFalse(TimerState.start(ctx, -1, 12));
        assertFalse(TimerState.start(ctx, 6, 12));
        assertFalse(TimerState.isRunning(ctx));
    }

    @Test public void startRejectsInvalidQtyWithoutChangingState() {
        assertThrows(IllegalArgumentException.class, () -> TimerState.start(ctx, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> TimerState.start(ctx, 0, -3));
        assertThrows(IllegalArgumentException.class, () -> TimerState.start(ctx, 0, TimerLogic.MAX_QTY + 1));
        assertFalse(TimerState.isRunning(ctx));
        assertFalse(TimerState.isActive(ctx));
    }

    @Test public void markFinishedRecordsLorongAndIsNoOpWhenNotRunning() {
        TimerState.markFinished(ctx); // belum berjalan
        assertTrue(TimerState.getRecords(ctx).isEmpty());

        TimerState.start(ctx, 0, 12);
        now = T0 + 60_000L;
        TimerState.markFinished(ctx);
        assertTrue(TimerState.isFinished(ctx));
        assertFalse(TimerState.isRunning(ctx));
        assertTrue(TimerState.done(ctx, 0));
        assertEquals(12L, TimerState.quantity(ctx, 0));
        List<TimerLogic.Record> r = TimerState.getRecords(ctx);
        assertEquals(1, r.size());
        assertEquals(T0, r.get(0).start);
        assertEquals(T0 + 60_000L, r.get(0).finish);

        long before = r.get(0).finish;
        now += 99_000L;
        TimerState.markFinished(ctx); // panggilan kedua tidak boleh menimpa
        assertEquals(before, TimerState.getRecords(ctx).get(0).finish);
    }

    /** Alarm terlambat (Doze) atau pemulihan setelah reboot tidak boleh menggelembungkan Waktu Kerja. */
    @Test public void lateCompletionIsRecordedAtDeadlineNotAtDetectionTime() {
        TimerState.start(ctx, 0, 12);
        now = T0 + 60_000L + 3_600_000L; // terdeteksi 1 jam setelah jatuh tempo
        TimerState.markFinished(ctx);
        TimerLogic.Record r = TimerState.getRecords(ctx).get(0);
        assertEquals(T0 + 60_000L, r.finish);
        assertEquals(60.0, TimerLogic.summarize(TimerState.getRecords(ctx)).workSeconds, 0.0);
    }

    @Test public void finishNeverPrecedesStartWhenClockMovesBackwards() {
        TimerState.start(ctx, 0, 12);
        now = T0 - 500_000L;
        TimerState.markFinished(ctx);
        TimerLogic.Record r = TimerState.getRecords(ctx).get(0);
        assertTrue(r.finish >= r.start);
    }

    @Test public void advanceMovesToNextLorongAndIsIdempotent() {
        TimerState.start(ctx, 0, 12);
        assertFalse("belum selesai: advance tidak boleh melompat", TimerState.advance(ctx));
        assertEquals(0, TimerState.index(ctx));

        now = T0 + 60_000L;
        TimerState.markFinished(ctx);
        assertFalse(TimerState.advance(ctx));
        assertEquals(1, TimerState.index(ctx));
        assertFalse(TimerState.isFinished(ctx));
        assertFalse(TimerState.isRunning(ctx));
        assertTrue(TimerState.isActive(ctx));

        assertFalse(TimerState.advance(ctx)); // tombol LANJUT ditekan dua kali
        assertEquals(1, TimerState.index(ctx));
    }

    @Test public void fullSixLorongSessionCompletesExactlyOnce() {
        long[] qty = {12, 25, 100, 13, 7, 61};
        long totalQty = 0;
        for (int i = 0; i < 6; i++) {
            assertTrue("start lorong " + i, TimerState.start(ctx, i, qty[i]));
            now = TimerState.endEpoch(ctx) + 2_000L; // user menekan LANJUT 2 detik setelah selesai
            TimerState.markFinished(ctx);
            boolean sessionDone = TimerState.advance(ctx);
            assertEquals(i == 5, sessionDone);
            totalQty += qty[i];
            now += 1_000L;
        }
        assertTrue(TimerState.isSessionCompleted(ctx));
        assertFalse(TimerState.isActive(ctx));
        assertFalse(TimerState.isRunning(ctx));
        assertFalse(TimerState.advance(ctx));

        List<TimerLogic.Record> records = TimerState.getRecords(ctx);
        assertEquals(6, records.size());
        TimerLogic.Summary s = TimerLogic.summarize(records);
        assertEquals(totalQty, s.totalQty);
        assertEquals(totalQty * 5.0, s.workSeconds, 0.0001);   // waktu kerja tepat sesuai rumus
        assertEquals(totalQty * 5.0, s.targetSeconds, 0.0001);
        assertEquals(5 * 3.0, s.gapSeconds, 0.0001);            // 2 detik tunggu + 1 detik jeda x 5 celah
        assertEquals(totalQty * 5.0 + 15.0, s.actualSeconds, 0.0001);
        assertEquals("good", s.type);                           // deviasi ~1,4% masih dalam +-10%

        assertFalse("sesi selesai tidak boleh dimulai ulang sebelum direset", TimerState.start(ctx, 5, 12));

        TimerState.resetSession(ctx);
        assertFalse(TimerState.isSessionCompleted(ctx));
        assertEquals(0, TimerState.index(ctx));
        assertTrue(TimerState.getRecords(ctx).isEmpty());
        assertTrue(TimerState.start(ctx, 0, 12));
    }

    @Test public void indexIsClampedToValidRange() {
        ctx.prefs.edit().putInt("index", 99).commit();
        assertEquals(5, TimerState.index(ctx));
        ctx.prefs.edit().putInt("index", -4).commit();
        assertEquals(0, TimerState.index(ctx));
    }

    @Test public void resetCurrentClearsOnlyActiveLorong() {
        TimerState.start(ctx, 0, 12);
        now = T0 + 60_000L;
        TimerState.markFinished(ctx);
        TimerState.advance(ctx);
        TimerState.start(ctx, 1, 24);
        TimerState.resetCurrent(ctx);
        assertFalse(TimerState.isRunning(ctx));
        assertTrue(TimerState.done(ctx, 0));
        assertFalse(TimerState.done(ctx, 1));
        assertEquals(0L, TimerState.endEpoch(ctx));
    }

    // ---------------- test doubles ----------------

    static final class FakeContext extends ContextWrapper {
        final FakePrefs prefs = new FakePrefs();
        FakeContext() { super(null); }
        @Override public Context getApplicationContext() { return this; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
    }

    static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> data = new HashMap<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(data); }
        @Override public String getString(String k, String d) { return data.containsKey(k) ? (String) data.get(k) : d; }
        @Override public Set<String> getStringSet(String k, Set<String> d) {
            @SuppressWarnings("unchecked") Set<String> v = (Set<String>) data.get(k);
            return v != null ? v : d;
        }
        @Override public int getInt(String k, int d) { return data.containsKey(k) ? (Integer) data.get(k) : d; }
        @Override public long getLong(String k, long d) { return data.containsKey(k) ? (Long) data.get(k) : d; }
        @Override public float getFloat(String k, float d) { return data.containsKey(k) ? (Float) data.get(k) : d; }
        @Override public boolean getBoolean(String k, boolean d) { return data.containsKey(k) ? (Boolean) data.get(k) : d; }
        @Override public boolean contains(String k) { return data.containsKey(k); }
        @Override public Editor edit() { return new FakeEditor(); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

        final class FakeEditor implements Editor {
            private final Map<String, Object> pending = new HashMap<>();
            private final Set<String> removals = new HashSet<>();
            private boolean clear;

            @Override public Editor putString(String k, String v) { pending.put(k, v); removals.remove(k); return this; }
            @Override public Editor putStringSet(String k, Set<String> v) { pending.put(k, v); removals.remove(k); return this; }
            @Override public Editor putInt(String k, int v) { pending.put(k, v); removals.remove(k); return this; }
            @Override public Editor putLong(String k, long v) { pending.put(k, v); removals.remove(k); return this; }
            @Override public Editor putFloat(String k, float v) { pending.put(k, v); removals.remove(k); return this; }
            @Override public Editor putBoolean(String k, boolean v) { pending.put(k, v); removals.remove(k); return this; }
            @Override public Editor remove(String k) { removals.add(k); pending.remove(k); return this; }
            @Override public Editor clear() { clear = true; return this; }
            @Override public boolean commit() {
                if (clear) data.clear();
                for (String k : removals) data.remove(k);
                data.putAll(pending);
                return true;
            }
            @Override public void apply() { commit(); }
        }
    }
}
