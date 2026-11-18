package id.maxdis.timer;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

public class TimerService extends Service {
    public static final String ACTION_SYNC = "id.maxdis.timer.ACTION_SYNC";
    public static final String ACTION_FINISH = "id.maxdis.timer.ACTION_FINISH";
    public static final String ACTION_NEXT = "id.maxdis.timer.ACTION_NEXT";
    public static final String ACTION_RESTORE = "id.maxdis.timer.ACTION_RESTORE";

    private static final String TAG = "MAXDIS";
    private static final int NOTIFICATION_ID = 4201;
    private static final String CHANNEL_RUNNING = "maxdis_running_v1";
    /** Dipakai saat Service aktif: senyap, karena bunyi alarm diputar sendiri dari notifikasi.mp3. */
    private static final String CHANNEL_ALARM = "maxdis_alarm_v2";
    /** Dipakai hanya bila Service tidak bisa dijalankan (mis. dibatasi sistem): bunyi dan getar dari sistem. */
    private static final String CHANNEL_ALARM_FALLBACK = "maxdis_alarm_fallback_v1";

    /** Alarm yang datang lebih awal dari ini dianggap basi / tidak sesuai dan tidak boleh menyelesaikan timer. */
    private static final long EARLY_TOLERANCE_MS = 1500L;
    private static final int MAX_ALARM_RETRIES = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer alarmPlayer;
    private Bitmap largeIcon;
    private int alarmFailures = 0;
    private long lastNotificationSecond = Long.MIN_VALUE;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (TimerState.isRunning(TimerService.this)) {
                long remaining = TimerState.endEpoch(TimerService.this) - System.currentTimeMillis();
                if (remaining <= 0) {
                    completeTimer();
                    return;
                }
                long seconds = (remaining + 999L) / 1000L;
                if (seconds != lastNotificationSecond) {
                    lastNotificationSecond = seconds;
                    updateNotification(false);
                }
                handler.postDelayed(this, 500L);
            } else if (TimerState.isFinished(TimerService.this)) {
                // Self-heal: bila pemutar mati karena error, coba lagi beberapa kali.
                if (alarmPlayer == null && alarmFailures < MAX_ALARM_RETRIES) startAlarm();
                updateNotification(true);
                handler.postDelayed(this, 3000L);
            }
        }
    };

    public static void start(Context context, String action) {
        Intent intent = new Intent(context, TimerService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel running = new NotificationChannel(
                CHANNEL_RUNNING, "Timer berjalan", NotificationManager.IMPORTANCE_LOW);
        running.setDescription("Pemberitahuan tetap saat TIMER MAXDIS menghitung mundur.");
        running.setShowBadge(false);

        NotificationChannel alarm = new NotificationChannel(
                CHANNEL_ALARM, "Timer selesai", NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription("Pemberitahuan ketika waktu lorong selesai. Bunyi diputar oleh aplikasi.");
        alarm.setSound(null, null);
        alarm.enableVibration(false);

        NotificationChannel fallback = new NotificationChannel(
                CHANNEL_ALARM_FALLBACK, "Timer selesai (cadangan)", NotificationManager.IMPORTANCE_HIGH);
        fallback.setDescription("Dipakai bila layanan timer dibatasi sistem; bunyi dan getar dari sistem.");
        fallback.enableVibration(true);

        nm.createNotificationChannel(running);
        nm.createNotificationChannel(alarm);
        nm.createNotificationChannel(fallback);
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannels(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RESTORE : intent.getAction();
        boolean finished = TimerState.isFinished(this);
        boolean running = TimerState.isRunning(this);

        // startForegroundService() mewajibkan startForeground() dalam 5 detik, APA PUN kondisinya.
        // Melewatkan langkah ini (mis. karena alarm basi) membuat aplikasi crash di Android 8+.
        startInForeground(buildNotification(this, finished, largeIcon()));

        if (!running && !finished) {
            shutDown(startId);
            return START_NOT_STICKY;
        }

        if (ACTION_NEXT.equals(action) && finished) {
            handler.removeCallbacks(ticker);
            stopAlarm();
            cancelTimerAlarm(this);
            TimerState.advance(this);
            shutDown(startId);
            return START_NOT_STICKY;
        }

        if (ACTION_FINISH.equals(action) && running) {
            long remaining = TimerState.endEpoch(this) - System.currentTimeMillis();
            if (remaining <= EARLY_TOLERANCE_MS) {
                completeTimer();
                return START_STICKY;
            }
            // Alarm basi dari lorong sebelumnya: abaikan dan sinkronkan ulang di bawah.
            Log.w(TAG, "Alarm datang " + remaining + " ms sebelum waktunya; diabaikan.");
        }

        if (running) {
            long remaining = TimerState.endEpoch(this) - System.currentTimeMillis();
            if (remaining <= 0) {
                completeTimer();
            } else {
                scheduleCompletionAlarm(this, TimerState.endEpoch(this));
                stopAlarm();
                handler.removeCallbacks(ticker);
                handler.post(ticker);
                updateNotification(false);
            }
        } else { // finished
            handler.removeCallbacks(ticker);
            startAlarm();
            updateNotification(true);
            handler.postDelayed(ticker, 3000L);
        }
        return START_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void shutDown(int startId) {
        handler.removeCallbacksAndMessages(null);
        stopAlarm();
        stopForeground(true);
        stopSelf(startId);
    }

    private void startInForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void completeTimer() {
        if (!TimerState.isRunning(this)) return;
        handler.removeCallbacks(ticker);
        cancelTimerAlarm(this);
        TimerState.markFinished(this);
        alarmFailures = 0;
        startAlarm();
        updateNotification(true);
        handler.postDelayed(ticker, 3000L);
    }

    @SuppressWarnings("deprecation")
    private void startAlarm() {
        try {
            if (alarmPlayer != null && alarmPlayer.isPlaying()) return;
        } catch (IllegalStateException ignored) {
            // pemutar dalam status tidak valid: lanjut buat ulang
        }
        stopAlarm();
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            MediaPlayer player = MediaPlayer.create(this, R.raw.notifikasi, attributes, 0);
            if (player == null) throw new IllegalStateException("notifikasi.mp3 tidak dapat dimuat");
            alarmPlayer = player;
            // Menjaga CPU tetap aktif selama alarm berbunyi walau layar mati.
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            player.setLooping(true);
            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                alarmFailures++;
                stopAlarm();
                return true;
            });
            player.start();
        } catch (Exception e) {
            alarmFailures++;
            Log.e(TAG, "Tidak dapat memutar notifikasi.mp3", e);
            releasePlayer();
        }
        // Getar tidak bergantung pada keberhasilan MP3, agar pengguna tetap diberi tahu.
        try {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = {0, 350, 650};
                if (Build.VERSION.SDK_INT >= 26) {
                    AudioAttributes va = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build();
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), va);
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Getar gagal", e);
        }
    }

    private void releasePlayer() {
        if (alarmPlayer != null) {
            try { if (alarmPlayer.isPlaying()) alarmPlayer.stop(); } catch (Exception ignored) {}
            try { alarmPlayer.release(); } catch (Exception ignored) {}
            alarmPlayer = null;
        }
    }

    private void stopAlarm() {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null) vibrator.cancel();
        } catch (Exception ignored) {}
        releasePlayer();
    }

    private Bitmap largeIcon() {
        if (largeIcon == null) {
            try {
                largeIcon = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
            } catch (Exception ignored) {
                // ikon besar hanya hiasan; notifikasi tetap tampil tanpanya
            }
        }
        return largeIcon;
    }

    private void updateNotification(boolean finished) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(this, finished, largeIcon()));
    }

    @SuppressWarnings("deprecation")
    static Notification buildNotification(Context context, boolean finished, Bitmap largeIcon) {
        int index = TimerState.index(context);
        String title = "TIMER MAXDIS";
        String text;
        String channel = finished ? CHANNEL_ALARM : CHANNEL_RUNNING;
        if (finished) {
            text = TimerLogic.NAMES[index] + " selesai • Tekan LANJUT";
        } else {
            long remain = Math.max(0L, TimerState.endEpoch(context) - System.currentTimeMillis());
            text = TimerLogic.NAMES[index] + " • QTY " + TimerState.currentQty(context)
                    + " • " + TimerLogic.formatDuration((remain + 999L) / 1000L)
                    + " tersisa";
        }

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(context, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, channel)
                : new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.ic_stat_timer)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(finished
                        ? text + ". Alarm akan terus berbunyi sampai tombol LANJUT ditekan."
                        : text + ". Timer tetap berjalan saat layar mati atau aplikasi ditinggalkan."))
                .setContentIntent(content)
                .setCategory(finished ? Notification.CATEGORY_ALARM : Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        if (largeIcon != null) builder.setLargeIcon(largeIcon);

        if (finished) {
            Intent next = new Intent(context, NextActionReceiver.class);
            PendingIntent nextPi = PendingIntent.getBroadcast(context, 11, next,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(R.drawable.ic_stat_timer, "LANJUT LORONG", nextPi);
            builder.setPriority(Notification.PRIORITY_HIGH);
        } else {
            builder.setPriority(Notification.PRIORITY_LOW);
        }
        return builder.build();
    }

    /**
     * Pindah ke lorong berikutnya tanpa menyalakan Service: status disimpan, alarm sistem dibatalkan, lalu
     * Service (bila sedang berjalan) dihentikan sehingga bunyi, getar, dan notifikasi ikut berhenti.
     * Aman dipanggil berulang. Dipakai oleh tombol LANJUT di aplikasi maupun di notifikasi.
     *
     * @return true bila seluruh enam lorong telah selesai
     */
    public static boolean advanceAndStop(Context context) {
        boolean sessionDone = TimerState.advance(context);
        // Jangan hentikan Service bila ternyata timer lorong lain sedang berjalan (klik basi / ganda).
        if (!TimerState.isRunning(context) && !TimerState.isFinished(context)) {
            cancelTimerAlarm(context);
            context.stopService(new Intent(context, TimerService.class));
        }
        return sessionDone;
    }

    /**
     * Jalur cadangan ketika Service tidak boleh dijalankan dari latar belakang (mis. pembatasan Android 12+).
     * Timer dijadwalkan ulang bila belum jatuh tempo, atau ditandai selesai dan diberi notifikasi bunyi sistem.
     */
    public static void recoverWithoutService(Context context) {
        try {
            if (TimerState.isRunning(context)) {
                long end = TimerState.endEpoch(context);
                if (end - System.currentTimeMillis() <= EARLY_TOLERANCE_MS) {
                    TimerState.markFinished(context);
                    postFallbackNotification(context);
                } else {
                    scheduleCompletionAlarm(context, end);
                }
            } else if (TimerState.isFinished(context)) {
                postFallbackNotification(context);
            }
        } catch (Exception e) {
            Log.e(TAG, "recoverWithoutService gagal", e);
        }
    }

    @SuppressWarnings("deprecation")
    private static void postFallbackNotification(Context context) {
        createChannels(context);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_ALARM_FALLBACK)
                : new Notification.Builder(context);
        int index = TimerState.index(context);
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(context, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent next = new Intent(context, NextActionReceiver.class);
        PendingIntent nextPi = PendingIntent.getBroadcast(context, 11, next,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setSmallIcon(R.drawable.ic_stat_timer)
                .setContentTitle("TIMER MAXDIS")
                .setContentText(TimerLogic.NAMES[index] + " selesai • Tekan LANJUT")
                .setContentIntent(content)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .addAction(R.drawable.ic_stat_timer, "LANJUT LORONG", nextPi);
        nm.notify(NOTIFICATION_ID, builder.build());
    }

    public static void scheduleCompletionAlarm(Context context, long endEpochMillis) {
        AlarmManager am = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = alarmPendingIntent(context);
        am.cancel(pi);
        long trigger = Math.max(System.currentTimeMillis(), endEpochMillis);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            }
        } catch (SecurityException ex) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
        }
    }

    public static void cancelTimerAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        if (am != null) am.cancel(alarmPendingIntent(context));
    }

    private static PendingIntent alarmPendingIntent(Context context) {
        Intent i = new Intent(context, AlarmReceiver.class);
        i.setAction("id.maxdis.timer.ALARM_DONE");
        return PendingIntent.getBroadcast(context, 101, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Intentionally keep the foreground service alive when the user swipes the Activity away.
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopAlarm();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
