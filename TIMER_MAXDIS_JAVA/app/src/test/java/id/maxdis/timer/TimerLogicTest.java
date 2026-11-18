package id.maxdis.timer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;

public class TimerLogicTest {

    // ---------- durasi per lorong ----------

    @Test public void originalPerLorongFormulaIsPreserved() {
        assertEquals(5L, TimerLogic.durationSeconds(1));
        assertEquals(60L, TimerLogic.durationSeconds(12));
        assertEquals(65L, TimerLogic.durationSeconds(13));
        assertEquals(300L, TimerLogic.durationSeconds(60));
    }

    /** Regresi: versi floating point lama menghasilkan 126 untuk Qty 25 dan 501 untuk Qty 100. */
    @Test public void durationHasNoFloatingPointDrift() {
        assertEquals(125L, TimerLogic.durationSeconds(25));
        assertEquals(250L, TimerLogic.durationSeconds(50));
        assertEquals(485L, TimerLogic.durationSeconds(97));
        assertEquals(500L, TimerLogic.durationSeconds(100));
    }

    /** Setiap Qty valid harus tepat Qty x 5 detik (setara (Qty/12) x 60 secara matematis). */
    @Test public void durationIsExactForEveryValidQty() {
        for (long q = 1; q <= TimerLogic.MAX_QTY; q++) {
            assertEquals("Qty " + q, q * 5L, TimerLogic.durationSeconds(q));
        }
    }

    @Test public void durationAcceptsBoundaryAndRejectsOutOfRange() {
        assertEquals(TimerLogic.MAX_QTY * 5L, TimerLogic.durationSeconds(TimerLogic.MAX_QTY));
        assertThrows(IllegalArgumentException.class, () -> TimerLogic.durationSeconds(0));
        assertThrows(IllegalArgumentException.class, () -> TimerLogic.durationSeconds(-1));
        assertThrows(IllegalArgumentException.class, () -> TimerLogic.durationSeconds(TimerLogic.MAX_QTY + 1));
        assertThrows(IllegalArgumentException.class, () -> TimerLogic.durationSeconds(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> TimerLogic.durationSeconds(Long.MIN_VALUE));
    }

    @Test public void maxDurationNeverOverflowsMilliseconds() {
        long millis = TimerLogic.durationSeconds(TimerLogic.MAX_QTY) * 1000L;
        assertTrue(millis > 0);
        assertTrue(System.currentTimeMillis() + millis > 0);
    }

    // ---------- parse input Qty ----------

    @Test public void parseQtyAcceptsValidInput() {
        assertEquals(1L, TimerLogic.parseQty("1"));
        assertEquals(12L, TimerLogic.parseQty("12"));
        assertEquals(25L, TimerLogic.parseQty("  25  "));
        assertEquals(TimerLogic.MAX_QTY, TimerLogic.parseQty("100000"));
        assertEquals(7L, TimerLogic.parseQty("007"));
    }

    @Test public void parseQtyRejectsEmptyAndZero() {
        for (String bad : new String[]{null, "", "   ", "0", "000", "-5"}) {
            IllegalArgumentException e = assertThrows("input: " + bad, IllegalArgumentException.class,
                    () -> TimerLogic.parseQty(bad));
            assertEquals("Masukkan Qty lebih dari 0", e.getMessage());
        }
    }

    @Test public void parseQtyRejectsNonNumeric() {
        for (String bad : new String[]{"abc", "12.5", "1 2", "1,5", "--3", "12a"}) {
            IllegalArgumentException e = assertThrows("input: " + bad, IllegalArgumentException.class,
                    () -> TimerLogic.parseQty(bad));
            assertEquals("Qty harus berupa angka", e.getMessage());
        }
    }

    @Test public void parseQtyRejectsAboveMaxIncludingHugeNumbers() {
        String expected = "Qty maksimal " + TimerLogic.MAX_QTY;
        for (String bad : new String[]{"100001", "9223372036854775807", "99999999999999999999", "123456789012345678901234567890"}) {
            IllegalArgumentException e = assertThrows("input: " + bad, IllegalArgumentException.class,
                    () -> TimerLogic.parseQty(bad));
            assertEquals(expected, e.getMessage());
        }
    }

    // ---------- target sesi ----------

    @Test public void originalSessionTargetFormulaIsPreserved() {
        assertEquals(60.0, TimerLogic.targetSeconds(12), 0.0001);
        assertEquals(360.0, TimerLogic.targetSeconds(72), 0.0001);
        assertEquals(0.0, TimerLogic.targetSeconds(0), 0.0001);
        assertEquals(0.0, TimerLogic.targetSeconds(-4), 0.0001);
    }

    @Test public void targetEqualsSumOfPerLorongDurations() {
        long[] qty = {25, 100, 97, 13, 7, 61};
        long sum = 0;
        long total = 0;
        for (long q : qty) { sum += TimerLogic.durationSeconds(q); total += q; }
        assertEquals((double) sum, TimerLogic.targetSeconds(total), 0.0);
    }

    // ---------- format ----------

    @Test public void formatDurationKeepsOriginalDisplayShape() {
        assertEquals("00:00", TimerLogic.formatDuration(0));
        assertEquals("01:05", TimerLogic.formatDuration(65));
        assertEquals("01:01:01", TimerLogic.formatDuration(3661));
    }

    @Test public void formatDurationHandlesEdgeValues() {
        assertEquals("59:59", TimerLogic.formatDuration(3599));
        assertEquals("01:00:00", TimerLogic.formatDuration(3600));
        assertEquals("23:59:59", TimerLogic.formatDuration(86399));
        assertEquals("01:00", TimerLogic.formatDuration(59.5)); // dibulatkan
        assertEquals("00:00", TimerLogic.formatDuration(-10));
        assertEquals("00:00", TimerLogic.formatDuration(Double.NaN));
        assertEquals("00:00", TimerLogic.formatDuration(Double.POSITIVE_INFINITY));
        assertEquals("00:00", TimerLogic.formatDuration(Double.NEGATIVE_INFINITY));
    }

    // ---------- shift (perilaku rumus asli, termasuk celah "DI LUAR SHIFT") ----------

    private static long atHour(int hour) {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.NOVEMBER, 18, hour, 30, 0);
        return c.getTimeInMillis();
    }

    @Test public void shiftBoundaries() {
        int[] hours =    {0, 1, 2, 5, 6, 8, 9, 15, 16, 20, 21, 23};
        String[] shifts = {"DI LUAR SHIFT", "DI LUAR SHIFT", "SHIFT 3", "SHIFT 3", "DI LUAR SHIFT", "DI LUAR SHIFT",
                "SHIFT 1", "SHIFT 1", "DI LUAR SHIFT", "DI LUAR SHIFT", "SHIFT 2", "SHIFT 2"};
        for (int i = 0; i < hours.length; i++) {
            assertEquals("jam " + hours[i], shifts[i], TimerLogic.getShift(atHour(hours[i])));
        }
    }

    // ---------- rekap sesi ----------

    @Test public void summaryIncludesWorkGapActualAndDeviation() {
        TimerLogic.Summary s = TimerLogic.summarize(Arrays.asList(
                new TimerLogic.Record(0, 12, 1_000L, 61_000L),
                new TimerLogic.Record(1, 12, 91_000L, 151_000L)
        ));
        assertEquals(24L, s.totalQty);
        assertEquals(120.0, s.workSeconds, 0.0001);
        assertEquals(30.0, s.gapSeconds, 0.0001);
        assertEquals(150.0, s.actualSeconds, 0.0001);
        assertEquals(120.0, s.targetSeconds, 0.0001);
        assertEquals(25.0, s.deviation, 0.0001);
        assertEquals("slow", s.type);
    }

    @Test public void summaryOfEmptyListIsSafe() {
        TimerLogic.Summary s = TimerLogic.summarize(Collections.<TimerLogic.Record>emptyList());
        assertEquals(0L, s.totalQty);
        assertEquals(0.0, s.actualSeconds, 0.0);
        assertEquals("DATA WAKTU BELUM CUKUP", s.statusText);
    }

    @Test public void summarySortsRecordsByLorongIndex() {
        TimerLogic.Summary s = TimerLogic.summarize(Arrays.asList(
                new TimerLogic.Record(1, 12, 91_000L, 151_000L),
                new TimerLogic.Record(0, 12, 1_000L, 61_000L)
        ));
        assertEquals(1_000L, s.startTimestamp);
        assertEquals(151_000L, s.finishTimestamp);
        assertEquals(30.0, s.gapSeconds, 0.0001);
    }

    @Test public void summaryIgnoresNegativeGapAndKeepsSourceListUntouched() {
        java.util.List<TimerLogic.Record> src = Arrays.asList(
                new TimerLogic.Record(1, 12, 50_000L, 110_000L),
                new TimerLogic.Record(0, 12, 0L, 60_000L)); // lorong 1 mulai sebelum lorong 0 selesai
        TimerLogic.Summary s = TimerLogic.summarize(src);
        assertEquals(0.0, s.gapSeconds, 0.0);
        assertEquals(1, src.get(0).index); // daftar asli tidak diurutkan ulang
    }

    @Test public void deviationThresholdsAreInclusiveAtTenPercent() {
        // target 60 detik (Qty 12)
        assertEquals("good", typeFor(12, 66_000L));  // +10% persis
        assertEquals("good", typeFor(12, 54_000L));  // -10% persis
        assertEquals("slow", typeFor(12, 67_000L));
        assertEquals("fast", typeFor(12, 53_000L));
        assertEquals("good", typeFor(12, 60_000L));
    }

    private static String typeFor(long qty, long actualMillis) {
        return TimerLogic.summarize(Collections.singletonList(
                new TimerLogic.Record(0, qty, 0L, actualMillis))).type;
    }

    @Test public void zeroQtyGivesInsufficientData() {
        TimerLogic.Summary s = TimerLogic.summarize(Collections.singletonList(new TimerLogic.Record(0, 0, 0L, 5_000L)));
        assertEquals("DATA WAKTU BELUM CUKUP", s.statusText);
        assertFalse(Double.isNaN(s.deviation));
    }
}
