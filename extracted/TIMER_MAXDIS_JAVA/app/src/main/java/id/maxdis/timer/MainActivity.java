package id.maxdis.timer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_NOTIFICATIONS = 501;
    private static final int REQ_EXACT_ALARM = 502;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final EditText[] qtyInputs = new EditText[6];

    private LinearLayout rowsContainer;
    private LinearLayout stepperContainer;
    private TextView stepLabels;
    private String lastRenderSignature = "";
    private Dialog timerDialog;
    private TextView timerTitleView;
    private TextView timerQtyView;
    private TextView timerDisplayView;
    private TextView timerMessageView;
    private Button timerNextButton;
    private CircleTimerView timerCircle;
    private VideoView timerVideo;
    private long pendingQty = 0;
    private boolean resultDialogShowing = false;
    private Dialog resultDialog;
    private Dialog exactAlarmDialog;
    private long lastEnterAt = 0L;

    private final Runnable uiLoop = new Runnable() {
        @Override public void run() {
            refreshUiFromState();
            uiHandler.postDelayed(this, 400L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.rgb(245, 245, 245));
        TimerService.createChannels(this);
        buildMainUi();
        askNotificationPermission();
        refreshUiFromState();
    }

    @Override protected void onResume() {
        super.onResume();
        if (pendingQty > 0 && hasExactAlarmPermission()) {
            long qty = pendingQty;
            pendingQty = 0;
            startTimerNow(qty);
        }
        // Self-heal: bila proses sempat dimatikan sistem, pastikan Service + alarm sistem kembali aktif.
        if (TimerState.isRunning(this) || TimerState.isFinished(this)) ensureService();
        uiHandler.removeCallbacks(uiLoop);
        uiHandler.post(uiLoop);
        refreshUiFromState();
    }

    @Override protected void onPause() {
        uiHandler.removeCallbacks(uiLoop);
        super.onPause();
    }

    @Override protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        dismissQuietly(timerDialog);
        dismissQuietly(resultDialog);
        dismissQuietly(exactAlarmDialog);
        super.onDestroy();
    }

    private void buildMainUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(Color.rgb(245, 245, 245));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(10), dp(14), dp(24));
        scroll.addView(page);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(6), dp(8), dp(6), dp(12));
        header.setBackground(round(Color.WHITE, 12, 0, 0));
        Button menu = button("MENU", Color.TRANSPARENT, Color.rgb(85, 85, 85));
        menu.setTextSize(13);
        menu.setPadding(dp(7), dp(8), dp(10), dp(8));
        menu.setOnClickListener(v -> showMenuDialog());
        header.addView(menu, new LinearLayout.LayoutParams(-2, dp(42)));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        logoLp.leftMargin = dp(5);
        header.addView(logo, logoLp);
        TextView title = text("TIMER MAXDIS", 19, Color.rgb(31, 41, 55), true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1);
        titleLp.leftMargin = dp(8);
        header.addView(title, titleLp);
        TextView timerTag = text("TIMER", 12, Color.WHITE, true);
        timerTag.setGravity(Gravity.CENTER);
        timerTag.setPadding(dp(15), dp(8), dp(15), dp(8));
        timerTag.setBackground(round(RED, 6, 0, 0));
        header.addView(timerTag, new LinearLayout.LayoutParams(-2, dp(34)));
        page.addView(header, matchWrap());

        stepperContainer = new LinearLayout(this);
        stepperContainer.setOrientation(LinearLayout.HORIZONTAL);
        stepperContainer.setGravity(Gravity.CENTER);
        stepperContainer.setPadding(dp(10), dp(17), dp(10), dp(17));
        stepperContainer.setBackground(round(Color.WHITE, 10, 0, 0));
        LinearLayout.LayoutParams stepLp = matchWrap();
        stepLp.topMargin = dp(10);
        page.addView(stepperContainer, stepLp);
        buildStepper(1);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER);
        info.setPadding(dp(12), dp(13), dp(12), dp(13));
        info.setBackground(round(Color.rgb(255, 249, 196), 8, Color.rgb(250, 209, 20), dp(1)));
        TextView infoTitle = text("Isi Qty lalu tekan ENTER untuk mulai timer.", 14, Color.rgb(51, 51, 51), true);
        infoTitle.setGravity(Gravity.CENTER);
        TextView infoBody = text("⚠ Timer berjalan di layanan Android dan tetap aktif saat layar mati. Jangan paksa berhenti aplikasi.", 12, Color.RED, true);
        infoBody.setGravity(Gravity.CENTER);
        infoBody.setPadding(0, dp(5), 0, 0);
        info.addView(infoTitle, matchWrap());
        info.addView(infoBody, matchWrap());
        LinearLayout.LayoutParams infoLp = matchWrap();
        infoLp.topMargin = dp(12);
        page.addView(info, infoLp);

        LinearLayout listCard = new LinearLayout(this);
        listCard.setOrientation(LinearLayout.VERTICAL);
        listCard.setBackground(round(Color.WHITE, 8, Color.rgb(221, 221, 221), dp(1)));
        LinearLayout listHeader = new LinearLayout(this);
        listHeader.setGravity(Gravity.CENTER_VERTICAL);
        listHeader.setPadding(dp(12), dp(13), dp(4), dp(13));
        listHeader.setBackground(round(RED, 7, 0, 0));
        listHeader.addView(text("DETAIL LORONG", 12, Color.WHITE, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView qtyHead = text("QTY", 11, Color.WHITE, true);
        qtyHead.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams qtyHeadLp = new LinearLayout.LayoutParams(dp(66), -2);
        qtyHeadLp.leftMargin = dp(8);
        qtyHeadLp.rightMargin = dp(6);
        listHeader.addView(qtyHead, qtyHeadLp);
        TextView statusHead = text("STATUS", 10, Color.WHITE, true);
        statusHead.setGravity(Gravity.CENTER);
        statusHead.setSingleLine(true);
        listHeader.addView(statusHead, new LinearLayout.LayoutParams(dp(40), -2));
        listCard.addView(listHeader, matchWrap());
        rowsContainer = new LinearLayout(this);
        rowsContainer.setOrientation(LinearLayout.VERTICAL);
        listCard.addView(rowsContainer, matchWrap());
        LinearLayout.LayoutParams listLp = matchWrap();
        listLp.topMargin = dp(12);
        page.addView(listCard, listLp);

        TextView footer = text("Rumus standar: 12 Qty = 1 menit • Total 6 lorong", 11, Color.GRAY, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(14), 0, 0);
        page.addView(footer, matchWrap());
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(0, bars.top, 0, bars.bottom);
                return insets;
            });
        }
    }

    private void refreshUiFromState() {
        StringBuilder signature = new StringBuilder();
        signature.append(TimerState.index(this)).append('|')
                .append(TimerState.isRunning(this)).append('|')
                .append(TimerState.isFinished(this)).append('|')
                .append(TimerState.isSessionCompleted(this));
        for (int i = 0; i < 6; i++) signature.append('|').append(TimerState.done(this, i));
        if (!signature.toString().equals(lastRenderSignature)) {
            lastRenderSignature = signature.toString();
            renderRows();
            buildStepper(TimerState.isRunning(this) ? 2 : (TimerState.isFinished(this) ? 3 : 1));
        }

        if (TimerState.isRunning(this) || TimerState.isFinished(this)) {
            showOrUpdateTimerDialog();
        } else if (timerDialog != null && timerDialog.isShowing()) {
            timerDialog.dismiss();
        }

        if (TimerState.isSessionCompleted(this) && !resultDialogShowing) showResultDialog();
    }

    private void renderRows() {
        if (rowsContainer == null) return;
        rowsContainer.removeAllViews();
        int activeIndex = TimerState.index(this);
        boolean blocked = TimerState.isRunning(this) || TimerState.isFinished(this);
        for (int i = 0; i < 6; i++) {
            final int index = i;
            boolean done = TimerState.done(this, i);
            boolean active = i == activeIndex && !done;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(10), dp(8));
            row.setBackgroundColor(active ? Color.rgb(227, 242, 253) : Color.WHITE);
            if (i > 0) {
                View divider = new View(this);
                divider.setBackgroundColor(Color.rgb(238, 238, 238));
                rowsContainer.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
            }

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView code = text(TimerLogic.CODES[i], 11, Color.GRAY, false);
            TextView name = text(TimerLogic.NAMES[i], 14, Color.rgb(51, 51, 51), true);
            info.addView(code, matchWrap());
            info.addView(name, matchWrap());
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

            EditText input = new EditText(this);
            input.setSingleLine(true);
            input.setTextSize(15);
            input.setGravity(Gravity.CENTER);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
            input.setHint("0");
            input.setPadding(dp(4), dp(4), dp(4), dp(4));
            input.setBackground(round(Color.WHITE, 6, Color.rgb(204, 204, 204), dp(1)));
            input.setEnabled(active && !blocked);
            if (done) input.setText(String.valueOf(TimerState.quantity(this, i)));
            input.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) {
                    return handleQtyEnter(index, input);
                }
                return false;
            });
            input.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP) {
                    return handleQtyEnter(index, input);
                }
                return false;
            });
            qtyInputs[i] = input;
            LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(dp(66), dp(44));
            inputLp.leftMargin = dp(8);
            inputLp.rightMargin = dp(12);
            row.addView(input, inputLp);

            TextView status = text(done ? "✓" : "•", 20, done ? Color.WHITE : Color.GRAY, true);
            status.setGravity(Gravity.CENTER);
            status.setBackground(round(done ? Color.rgb(76, 175, 80) : Color.rgb(238, 238, 238), 50, 0, 0));
            row.addView(status, new LinearLayout.LayoutParams(dp(28), dp(28)));
            rowsContainer.addView(row, new LinearLayout.LayoutParams(-1, dp(66)));
        }
    }

    private boolean handleQtyEnter(int index, EditText input) {
        if (index != TimerState.index(this) || TimerState.isRunning(this) || TimerState.isFinished(this)
                || TimerState.isSessionCompleted(this)) return true;
        long qty;
        try {
            qty = TimerLogic.parseQty(input.getText() == null ? "" : input.getText().toString());
        } catch (IllegalArgumentException e) {
            input.setError(e.getMessage());
            return true;
        }
        // Beberapa keyboard mengirim aksi IME dan tombol ENTER sekaligus: abaikan pemicu ganda.
        long now = SystemClock.elapsedRealtime();
        if (now - lastEnterAt < 800L) return true;
        lastEnterAt = now;
        hideKeyboard(input);
        maybeRequestExactAlarm(qty);
        return true;
    }

    private void maybeRequestExactAlarm(long qty) {
        if (exactAlarmDialog != null && exactAlarmDialog.isShowing()) return;
        if (Build.VERSION.SDK_INT >= 31 && !hasExactAlarmPermission()) {
            exactAlarmDialog = new AlertDialog.Builder(this)
                    .setTitle("Izinkan alarm akurat")
                    .setMessage("Agar bunyi timer tepat saat layar mati atau mode hemat daya aktif, izinkan TIMER MAXDIS menggunakan Alarms & reminders. Tanpa izin ini Android dapat menunda alarm.")
                    .setPositiveButton("BUKA PENGATURAN", (d, w) -> {
                        pendingQty = qty;
                        try {
                            Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:" + getPackageName()));
                            startActivityForResult(i, REQ_EXACT_ALARM);
                        } catch (Exception ex) {
                            pendingQty = 0;
                            startTimerNow(qty);
                        }
                    })
                    .setNegativeButton("LANJUT TANPA IZIN", (d, w) -> startTimerNow(qty))
                    .setNeutralButton("BATAL", null)
                    .show();
        } else {
            startTimerNow(qty);
        }
    }

    private boolean hasExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < 31) return true;
        android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXACT_ALARM && pendingQty > 0) {
            long qty = pendingQty;
            pendingQty = 0;
            if (hasExactAlarmPermission()) startTimerNow(qty);
            else new AlertDialog.Builder(this)
                    .setTitle("Izin belum diberikan")
                    .setMessage("Timer tetap dapat berjalan, tetapi alarm bisa terlambat saat mode hemat daya.")
                    .setPositiveButton("LANJUT", (d, w) -> startTimerNow(qty))
                    .setNegativeButton("BATAL", null)
                    .show();
        }
    }

    private void startTimerNow(long qty) {
        int index = TimerState.index(this);
        try {
            // false = sudah ada timer berjalan / menunggu LANJUT (klik ganda): jangan mulai ulang.
            if (!TimerState.start(this, index, qty)) return;
            TimerService.scheduleCompletionAlarm(this, TimerState.endEpoch(this));
        } catch (Exception e) {
            Toast.makeText(this, "Gagal memulai timer: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        if (!ensureService()) {
            Toast.makeText(this, "Layanan latar belakang tidak dapat dijalankan. Biarkan aplikasi tetap terbuka.",
                    Toast.LENGTH_LONG).show();
        }
        lastRenderSignature = "";
        refreshUiFromState();
    }

    /** Menyalakan / menyinkronkan Foreground Service. Mengembalikan false bila sistem menolak. */
    private boolean ensureService() {
        try {
            TimerService.start(this, TimerService.ACTION_SYNC);
            return true;
        } catch (RuntimeException e) {
            Log.e("MAXDIS", "Tidak dapat menjalankan TimerService", e);
            return false;
        }
    }

    private static void dismissQuietly(Dialog d) {
        try { if (d != null && d.isShowing()) d.dismiss(); } catch (Exception ignored) {}
    }

    private void showOrUpdateTimerDialog() {
        boolean finished = TimerState.isFinished(this);
        int index = TimerState.index(this);
        long qty = TimerState.currentQty(this);
        long remainingMs = Math.max(0L, TimerState.endEpoch(this) - System.currentTimeMillis());
        long seconds = (remainingMs + 999L) / 1000L;

        if (isFinishing() || isDestroyed()) return;
        if (timerDialog == null || !timerDialog.isShowing()) createTimerDialog();
        if (timerTitleView == null) return;

        timerTitleView.setText(TimerLogic.NAMES[index]);
        timerQtyView.setText("QTY " + qty);
        if (finished) {
            timerDisplayView.setText("00:00");
            if (timerVideo != null) {
                try { timerVideo.pause(); } catch (Exception ignored) {}
            }
            timerMessageView.setText("WAKTU HABIS! Tekan lanjut untuk menyelesaikan lorong.");
            timerNextButton.setVisibility(View.VISIBLE);
            timerCircle.setProgress(1f);
        } else {
            timerDisplayView.setText(TimerLogic.formatDuration(seconds));
            long duration = Math.max(1L, TimerLogic.durationSeconds(qty));
            float progress = Math.min(1f, Math.max(0f, (float) seconds / (float) duration));
            timerCircle.setProgress(progress);
            String message = seconds <= 10 ? "Hampir selesai!" : seconds <= 30 ? "30 detik terakhir." : seconds <= 60 ? "Tinggal sebentar lagi." : "Waktu berjalan.";
            timerMessageView.setText(message);
            timerNextButton.setVisibility(View.GONE);
            if (timerVideo != null && !timerVideo.isPlaying()) {
                try { timerVideo.start(); } catch (Exception ignored) {}
            }
        }
    }

    private void createTimerDialog() {
        timerDialog = new Dialog(this);
        timerDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(round(Color.WHITE, 20, 0, 0));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text("● TIMER AKTIF", 11, Color.rgb(0, 94, 170), true);
        badge.setPadding(dp(11), dp(7), dp(11), dp(7));
        badge.setBackground(round(Color.rgb(227, 242, 253), 30, 0, 0));
        top.addView(badge, new LinearLayout.LayoutParams(0, -2, 1));
        timerQtyView = text("QTY 0", 11, Color.DKGRAY, true);
        timerQtyView.setGravity(Gravity.CENTER);
        timerQtyView.setPadding(dp(11), dp(7), dp(11), dp(7));
        timerQtyView.setBackground(round(Color.rgb(248, 250, 252), 30, 0, 0));
        top.addView(timerQtyView);
        card.addView(top, matchWrap());

        timerTitleView = text("LORONG 1", 25, Color.rgb(31, 41, 55), true);
        timerTitleView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = matchWrap();
        titleLp.topMargin = dp(12);
        card.addView(timerTitleView, titleLp);
        TextView subtitle = text("Sedang mengerjakan...", 13, Color.GRAY, false);
        subtitle.setGravity(Gravity.CENTER);
        card.addView(subtitle, matchWrap());

        timerVideo = new VideoView(this);
        timerVideo.setBackground(round(Color.rgb(244, 246, 248), 12, Color.rgb(229, 231, 235), dp(1)));
        // Tanpa ini VideoView menampilkan dialog bawaan "Can't play this video" bila video gagal dimuat.
        timerVideo.setOnErrorListener((mp, what, extra) -> true);
        timerVideo.setOnPreparedListener(mp -> {
            try { mp.setVolume(0f, 0f); mp.setLooping(true); } catch (Exception ignored) {}
            if (TimerState.isRunning(this)) timerVideo.start();
        });
        LinearLayout.LayoutParams videoLp = new LinearLayout.LayoutParams(-1, dp(112));
        videoLp.topMargin = dp(10);
        card.addView(timerVideo, videoLp);
        try {
            Uri videoUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.display);
            timerVideo.setVideoURI(videoUri);
        } catch (Exception ignored) {}

        FrameLayout ringFrame = new FrameLayout(this);
        timerCircle = new CircleTimerView(this);
        FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(dp(235), dp(235), Gravity.CENTER);
        ringFrame.addView(timerCircle, ringLp);
        timerDisplayView = text("00:00", 51, Color.rgb(34, 34, 34), true);
        timerDisplayView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        timerDisplayView.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams timeLp = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        ringFrame.addView(timerDisplayView, timeLp);
        TextView timerLabel = text("WAKTU TERSISA", 10, Color.GRAY, true);
        timerLabel.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams timerLabelLp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        timerLabelLp.topMargin = dp(58);
        ringFrame.addView(timerLabel, timerLabelLp);
        LinearLayout.LayoutParams frameLp = new LinearLayout.LayoutParams(-1, dp(245));
        frameLp.topMargin = dp(4);
        card.addView(ringFrame, frameLp);

        timerMessageView = text("Waktu berjalan.", 14, Color.DKGRAY, true);
        timerMessageView.setGravity(Gravity.CENTER);
        timerMessageView.setPadding(dp(12), dp(12), dp(12), dp(12));
        timerMessageView.setBackground(round(Color.rgb(248, 250, 252), 12, 0, 0));
        card.addView(timerMessageView, matchWrap());

        timerNextButton = button("LANJUT LORONG BERIKUTNYA", RED, Color.WHITE);
        timerNextButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams nextLp = matchWrap();
        nextLp.topMargin = dp(13);
        card.addView(timerNextButton, nextLp);
        timerNextButton.setOnClickListener(v -> advanceLorong());

        timerDialog.setContentView(card);
        timerDialog.setCancelable(false);
        timerDialog.setCanceledOnTouchOutside(false);
        Window w = timerDialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams p = w.getAttributes();
            p.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            p.height = WindowManager.LayoutParams.WRAP_CONTENT;
            p.dimAmount = 0.72f;
            w.setAttributes(p);
        }
        timerDialog.setOnDismissListener(d -> {
            if (timerVideo != null) {
                try { timerVideo.pause(); } catch (Exception ignored) {}
            }
        });
        timerDialog.show();
        // Dialog.show() may reset dimensions depending on Android version.
        if (timerDialog.getWindow() != null) {
            timerDialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92f), -2);
            timerDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
    }

    private void advanceLorong() {
        if (!TimerState.isFinished(this)) return; // klik ganda / status basi
        if (timerVideo != null) {
            try { timerVideo.stopPlayback(); } catch (Exception ignored) {}
        }
        // Status disimpan secara sinkron; Service (alarm, getar, notifikasi) dihentikan di dalam advanceAndStop.
        TimerService.advanceAndStop(this);
        dismissQuietly(timerDialog);
        timerDialog = null;
        timerTitleView = timerQtyView = timerDisplayView = timerMessageView = null;
        timerNextButton = null;
        timerCircle = null;
        timerVideo = null;
        lastRenderSignature = "";
        refreshUiFromState();
    }

    private void showResultDialog() {
        if (isFinishing() || isDestroyed()) return;
        resultDialogShowing = true;
        List<TimerLogic.Record> records = TimerState.getRecords(this);
        TimerLogic.Summary s = TimerLogic.summarize(records);
        String finish = s.finishTimestamp > 0
                ? new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date(s.finishTimestamp)) : "-";
        String deviation = String.format(Locale.getDefault(), "%+.1f%%", s.deviation);
        String message = "STATUS: " + s.statusText + "\n\n"
                + "Total Qty       : " + s.totalQty + "\n"
                + "Shift           : " + TimerLogic.getShift(s.startTimestamp) + "\n"
                + "Waktu Standar   : " + TimerLogic.formatDuration(s.targetSeconds) + "\n"
                + "Waktu Kerja     : " + TimerLogic.formatDuration(s.workSeconds) + "\n"
                + "Total Gap       : " + TimerLogic.formatDuration(s.gapSeconds) + "\n"
                + "Waktu Aktual    : " + TimerLogic.formatDuration(s.actualSeconds) + "\n"
                + "Deviasi         : " + deviation + "\n"
                + "Waktu Selesai   : " + finish + "\n\n"
                + "Rentang -10% sampai +10% dianggap tepat waktu.";
        resultDialog = new AlertDialog.Builder(this)
                .setTitle("✓ SELURUH LORONG SELESAI")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("OK", (d, w) -> {
                    TimerState.resetSession(this);
                    TimerService.cancelTimerAlarm(this);
                    stopService(new Intent(this, TimerService.class));
                    resultDialogShowing = false;
                    lastRenderSignature = "";
                    refreshUiFromState();
                })
                .show();
    }

    private void buildStepper(int activeStep) {
        if (stepperContainer == null) return;
        stepperContainer.removeAllViews();
        String[] labels = {"Input", "Proses", "Selesai"};
        for (int i = 1; i <= 3; i++) {
            LinearLayout step = new LinearLayout(this);
            step.setOrientation(LinearLayout.VERTICAL);
            step.setGravity(Gravity.CENTER);
            TextView circle = text(i <= activeStep - 1 ? "✓" : String.valueOf(i), 14, Color.WHITE, true);
            circle.setGravity(Gravity.CENTER);
            circle.setBackground(round(i < activeStep ? Color.rgb(76, 175, 80) : (i == activeStep ? Color.rgb(0, 94, 170) : Color.rgb(224, 224, 224)), 50, 0, 0));
            step.addView(circle, new LinearLayout.LayoutParams(dp(34), dp(34)));
            TextView label = text(labels[i - 1], 11, i == activeStep ? Color.rgb(0, 94, 170) : Color.GRAY, i == activeStep);
            label.setPadding(0, dp(4), 0, 0);
            step.addView(label, wrapWrap());
            stepperContainer.addView(step, new LinearLayout.LayoutParams(0, -2, 1));
        }
    }

    private void showMenuDialog() {
        new AlertDialog.Builder(this)
                .setTitle("MENU TIMER MAXDIS")
                .setMessage("Timer pengisian enam lorong.\n\nRumus: Qty ÷ 12 × 60 detik.\n\nSaat timer berjalan, layanan Android menjaga hitung mundur tetap aktif di latar belakang. Geser aplikasi dari Recent Apps tidak menghentikan timer.")
                .setPositiveButton("TUTUP", null)
                .show();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private void hideKeyboard(View view) {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            view.clearFocus();
        } catch (Exception ignored) {}
    }

    private Button button(String label, int background, int foreground) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(foreground);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setBackground(round(background, 10, 0, 0));
        return b;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private GradientDrawable round(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (strokeWidth > 0) d.setStroke(strokeWidth, strokeColor);
        return d;
    }

    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams wrapWrap() { return new LinearLayout.LayoutParams(-2, -2); }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private static final int RED = Color.rgb(229, 27, 35);

    private class CircleTimerView extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private float progress = 1f;
        CircleTimerView(Context context) { super(context); }
        void setProgress(float p) { progress = Math.max(0f, Math.min(1f, p)); invalidate(); }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float stroke = dp(12);
            float pad = stroke / 2f + dp(3);
            float radius = Math.min(getWidth(), getHeight()) / 2f - pad;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setColor(Color.rgb(229, 231, 235));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setColor(RED);
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, -90f, progress * 360f, false, paint);
        }
    }
}
