package id.maxdis.timer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure calculation layer matching the original TIMER MAXDIS HTML formulas. */
public final class TimerLogic {
    private TimerLogic() {}

    public static final String[] CODES = {"L01", "L02", "L03", "L04", "L05", "L06"};
    public static final String[] NAMES = {"LORONG 1", "LORONG 2", "LORONG 3", "LORONG 4", "LORONG 5", "LORONG 6"};

    /** 12 Qty = 1 menit, artinya 1 Qty = 5 detik (rumus asli: (Qty / 12) x 60 detik). */
    public static final long SECONDS_PER_QTY = 5L;

    /**
     * Batas atas Qty per lorong. 100.000 Qty = 500.000 detik (~5,8 hari); cukup untuk pemakaian nyata
     * dan mencegah overflow pada perhitungan milidetik (qty x 5 x 1000).
     */
    public static final long MAX_QTY = 100_000L;

    /**
     * Durasi timer satu lorong dalam detik.
     *
     * <p>Memakai aritmetika bilangan bulat. Rumus asli ceil((Qty / 12.0) x 60.0) setara dengan Qty x 5,
     * tetapi versi floating point menghasilkan selisih +1 detik pada ~2,8% nilai Qty
     * (contoh: Qty 25 menjadi 126 detik, Qty 100 menjadi 501 detik).
     */
    public static long durationSeconds(long qty) {
        if (qty <= 0) throw new IllegalArgumentException("Qty harus lebih dari 0");
        if (qty > MAX_QTY) throw new IllegalArgumentException("Qty maksimal " + MAX_QTY);
        return qty * SECONDS_PER_QTY;
    }

    /**
     * Mengubah teks isian Qty menjadi angka yang valid (1..MAX_QTY).
     *
     * @throws IllegalArgumentException dengan pesan siap tampil bila kosong, bukan angka, nol, atau melebihi batas
     */
    public static long parseQty(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Masukkan Qty lebih dari 0");
        long qty;
        try {
            qty = Long.parseLong(text);
        } catch (NumberFormatException e) {
            // Angka terlalu panjang untuk long juga masuk ke sini: tetap dianggap melebihi batas bila semua digit.
            if (text.matches("\\d+")) throw new IllegalArgumentException("Qty maksimal " + MAX_QTY);
            throw new IllegalArgumentException("Qty harus berupa angka");
        }
        if (qty <= 0) throw new IllegalArgumentException("Masukkan Qty lebih dari 0");
        if (qty > MAX_QTY) throw new IllegalArgumentException("Qty maksimal " + MAX_QTY);
        return qty;
    }

    /** Standar sesi: (totalQty / 60) x 5 x 60 detik = totalQty x 5 detik. */
    public static double targetSeconds(long totalQty) {
        if (totalQty <= 0) return 0;
        return totalQty * (double) SECONDS_PER_QTY;
    }

    public static String formatDuration(double rawSeconds) {
        long total = Math.max(0L, Math.round(Double.isFinite(rawSeconds) ? rawSeconds : 0));
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        if (hours > 0) return two(hours) + ":" + two(minutes) + ":" + two(seconds);
        return two(minutes) + ":" + two(seconds);
    }

    private static String two(long value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    public static String getShift(long timestampMillis) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(timestampMillis);
        int hour = calendar.get(java.util.Calendar.HOUR_OF_DAY);
        if (hour >= 2 && hour < 6) return "SHIFT 3";
        if (hour >= 9 && hour < 16) return "SHIFT 1";
        if (hour >= 21 && hour < 24) return "SHIFT 2";
        return "DI LUAR SHIFT";
    }

    public static Summary summarize(List<Record> source) {
        List<Record> records = new ArrayList<>(source);
        records.sort(Comparator.comparingInt(a -> a.index));
        Summary s = new Summary();
        if (records.isEmpty()) return s;

        for (Record r : records) {
            s.totalQty += r.qty;
            if (r.finish > r.start) s.workSeconds += (r.finish - r.start) / 1000.0;
        }

        for (int i = 1; i < records.size(); i++) {
            long gap = records.get(i).start - records.get(i - 1).finish;
            if (gap > 0) s.gapSeconds += gap / 1000.0;
        }

        long firstStart = records.get(0).start;
        long lastFinish = records.get(records.size() - 1).finish;
        s.actualSeconds = lastFinish >= firstStart ? (lastFinish - firstStart) / 1000.0 : 0;
        s.targetSeconds = targetSeconds(s.totalQty);
        s.startTimestamp = firstStart;
        s.finishTimestamp = lastFinish;

        if (s.targetSeconds > 0) {
            s.deviation = ((s.actualSeconds - s.targetSeconds) / s.targetSeconds) * 100.0;
            if (s.deviation < -10.0) {
                s.type = "fast";
                s.statusText = "DISPLAY TERLALU CEPAT";
            } else if (s.deviation <= 10.0) {
                s.type = "good";
                s.statusText = "BAGUS, DISPLAY TEPAT WAKTU";
            } else {
                s.type = "slow";
                s.statusText = "DISPLAY TERLALU LAMA";
            }
        } else {
            s.type = "good";
            s.statusText = "DATA WAKTU BELUM CUKUP";
        }
        return s;
    }

    public static final class Record {
        public final int index;
        public final long qty;
        public final long start;
        public final long finish;
        public Record(int index, long qty, long start, long finish) {
            this.index = index;
            this.qty = qty;
            this.start = start;
            this.finish = finish;
        }
    }

    public static final class Summary {
        public long totalQty;
        public double targetSeconds;
        public double workSeconds;
        public double gapSeconds;
        public double actualSeconds;
        public double deviation;
        public long startTimestamp;
        public long finishTimestamp;
        public String type = "good";
        public String statusText = "DATA WAKTU BELUM CUKUP";
    }
}
