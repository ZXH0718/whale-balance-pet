package com.dsh.whalepet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 前台服务：屏幕角落的鲸鱼娘 + 余额气泡。
 *
 * 交互（还原 dsh-whale-widget 的按压手感）：
 *  - 按下：鲸鱼压扁 scaleY(0.88) / scaleX(1.05)，播放按压音 Ya1
 *  - 按住满 1 秒：进入摸头 —— 用原版 rua.gif 解出的 10 帧动画循环播放 + 台词 + 震动
 *  - 松手：回弹到 1:1，播放松开音 Ya2；未拖动则刷新余额
 *  - 按住满 3 秒：打开设置页
 *  - 拖动：跟手移动，松手吸附左右边缘并记住位置
 */
public class BalanceService extends Service {

    public static final String ACTION_START = "com.dsh.whalepet.action.START";
    public static final String ACTION_STOP = "com.dsh.whalepet.action.STOP";
    public static final String ACTION_REFRESH = "com.dsh.whalepet.action.REFRESH";

    private static final String CHANNEL_ID = "whale_pet";
    private static final int NOTIFICATION_ID = 1001;
    private static final int EDGE_MARGIN_DP = 6;
    private static final int WHALE_DP = 72;

    /** 按住多久进入摸头 */
    private static final int PAT_TRIGGER_MS = 1000;
    /** 按住多久打开设置 */
    private static final int SETTINGS_TRIGGER_MS = 3000;
    /** 每帧时长：原版 rua.gif 每帧 2cs = 20ms */
    private static final int PAT_FRAME_MS = 20;
    /** 原版按压形变：SQUISH = 'scaleY(0.88) scaleX(1.05)' */
    private static final float SQUISH_SCALE_Y = 0.88f;
    private static final float SQUISH_SCALE_X = 1.05f;
    private static final int SQUISH_MS = 90;
    /** 摸头时「冒出来」的放大倍数与上浮距离（dp） */
    private static final float PAT_SCALE = 1.18f;
    private static final int PAT_LIFT_DP = 8;
    private static final int PAT_POP_MS = 160;
    private static final int LINE_MS = 2600;

    private static final int[] PAT_FRAMES = {
            R.drawable.pat_00, R.drawable.pat_01, R.drawable.pat_02, R.drawable.pat_03,
            R.drawable.pat_04, R.drawable.pat_05, R.drawable.pat_06, R.drawable.pat_07,
            R.drawable.pat_08, R.drawable.pat_09
    };

    private static final String[] PAT_LINES = {
            "唔…别摸头啦",
            "呀…！",
            "再摸就要掉毛了",
            "…好舒服的样子",
            "嗨呀，痒",
            "摸头可以，余额不能掉",
            "今天的我也很可爱吧",
            "喂喂，集中精神写代码",
            "被你摸秃了要你负责",
            "…再摸一下也不是不行"
    };

    private WindowManager windowManager;
    private View overlay;
    private View bubbleBox;
    private TextView tvBalance;
    private TextView tvDetail;
    private ImageView whaleView;
    private AnimationDrawable patAnim;
    private WindowManager.LayoutParams params;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable ticker;
    private Runnable patRunnable;
    private Runnable settingsRunnable;
    private Runnable restoreLineRunnable;

    private MediaPlayer pressSound;
    private MediaPlayer releaseSound;

    private int touchSlop;
    private boolean refreshing;
    private boolean patting;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        pressSound = createSound(R.raw.ya1);
        releaseSound = createSound(R.raw.ya2);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startAsForeground();
        if (overlay == null) {
            showOverlay();
        }
        refresh();
        scheduleTicker();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopTicker();
        handler.removeCallbacksAndMessages(null);
        releaseSound(pressSound);
        releaseSound(releaseSound);
        if (overlay != null && windowManager != null) {
            try {
                windowManager.removeView(overlay);
            } catch (Exception ignored) {
                // 视图已被移除
            }
            overlay = null;
        }
        super.onDestroy();
    }

    // ── 悬浮窗 ──────────────────────────────────────────────────────────

    private void showOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = LayoutInflater.from(this).inflate(R.layout.overlay_whale, null);
        bubbleBox = overlay.findViewById(R.id.bubble_box);
        tvBalance = overlay.findViewById(R.id.tv_balance);
        tvDetail = overlay.findViewById(R.id.tv_detail);
        whaleView = overlay.findViewById(R.id.iv_whale);
        if (whaleView != null) {
            // 以顶部为轴压扁，摸头/按压时像是被从上面按住
            whaleView.setPivotY(0f);
        }
        renderBalance();
        applyLook();

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int savedX = Prefs.posX(this);
        int savedY = Prefs.posY(this);
        params.x = savedX < 0 ? metrics.widthPixels - dp(WHALE_DP + 24) : savedX;
        params.y = savedY < 0 ? Math.round(metrics.heightPixels * 0.35f) : savedY;

        overlay.setOnTouchListener(new DragTouchListener());
        windowManager.addView(overlay, params);
    }

    private void applyLook() {
        float scale = Prefs.scale(this);
        if (whaleView != null) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) whaleView.getLayoutParams();
            lp.width = dp(Math.round(WHALE_DP * scale));
            lp.height = dp(Math.round(WHALE_DP * scale));
            whaleView.setLayoutParams(lp);
        }
        if (bubbleBox != null) {
            bubbleBox.setVisibility(Prefs.showBubble(this) ? View.VISIBLE : View.GONE);
        }
        if (tvBalance != null) {
            tvBalance.setTextSize(17f * scale);
        }
        if (tvDetail != null) {
            tvDetail.setTextSize(11f * scale);
        }
    }

    private void safeUpdate() {
        if (overlay == null || windowManager == null) {
            return;
        }
        try {
            windowManager.updateViewLayout(overlay, params);
        } catch (Exception ignored) {
            // 窗口已消失
        }
    }

    private void snapToEdge() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int margin = dp(EDGE_MARGIN_DP);
        int width = overlay.getWidth() > 0 ? overlay.getWidth() : dp(WHALE_DP + 40);
        int height = overlay.getHeight() > 0 ? overlay.getHeight() : dp(WHALE_DP + 40);

        int centerX = params.x + width / 2;
        params.x = centerX < metrics.widthPixels / 2
                ? margin
                : metrics.widthPixels - width - margin;

        if (params.y < 0) {
            params.y = 0;
        }
        int maxY = metrics.heightPixels - height - margin;
        if (params.y > maxY) {
            params.y = maxY;
        }
        safeUpdate();
    }

    private void openSettings() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    // ── 按压 / 摸头 ─────────────────────────────────────────────────────

    private void squish(boolean down) {
        if (whaleView == null) {
            return;
        }
        float sx = down ? SQUISH_SCALE_X : 1f;
        float sy = down ? SQUISH_SCALE_Y : 1f;
        whaleView.animate().scaleX(sx).scaleY(sy).setDuration(SQUISH_MS).start();
    }

    private void ensurePatAnimation() {
        if (patAnim != null) {
            return;
        }
        patAnim = new AnimationDrawable();
        patAnim.setOneShot(false);
        for (int res : PAT_FRAMES) {
            Drawable frame = getResources().getDrawable(res);
            patAnim.addFrame(frame, PAT_FRAME_MS);
        }
    }

    private void startPat() {
        if (whaleView == null || patting) {
            return;
        }
        patting = true;
        ensurePatAnimation();
        whaleView.setImageDrawable(patAnim);
        patAnim.start();
        // 摸头时「冒出来」：放大 + 上浮一点，一眼能看出她正在被摸
        whaleView.animate()
                .scaleX(PAT_SCALE).scaleY(PAT_SCALE)
                .translationY(-dp(PAT_LIFT_DP))
                .setDuration(PAT_POP_MS).start();

        if (Prefs.patVibrate(this)) {
            vibrate(18);
        }
        int count = Prefs.bumpPatCount(this);
        showLine(PAT_LINES[count % PAT_LINES.length]);
    }

    private void stopPat() {
        patting = false;
        if (patAnim != null) {
            patAnim.stop();
        }
        if (whaleView != null) {
            whaleView.setImageResource(R.drawable.whale);
            whaleView.animate()
                    .scaleX(1f).scaleY(1f)
                    .translationY(0f)
                    .setDuration(140).start();
        }
    }

    private void showLine(String line) {
        if (tvBalance == null) {
            return;
        }
        setBubble(line, null);
        if (restoreLineRunnable != null) {
            handler.removeCallbacks(restoreLineRunnable);
        }
        restoreLineRunnable = new Runnable() {
            @Override
            public void run() {
                renderBalance();
            }
        };
        handler.postDelayed(restoreLineRunnable, LINE_MS);
    }

    private void vibrate(int ms) {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(ms);
            }
        } catch (Exception ignored) {
            // 没有震动马达或权限
        }
    }

    // ── 音效（原版按压音 Ya1 / 松开音 Ya2） ──────────────────────────────

    private MediaPlayer createSound(int resId) {
        try {
            return MediaPlayer.create(this, resId);
        } catch (Exception e) {
            return null;
        }
    }

    private void playSound(MediaPlayer player) {
        if (player == null || !Prefs.patSound(this)) {
            return;
        }
        try {
            if (player.isPlaying()) {
                player.seekTo(0);
            } else {
                player.seekTo(0);
                player.start();
            }
        } catch (Exception ignored) {
            // 播放失败静默
        }
    }

    private void releaseSound(MediaPlayer player) {
        if (player == null) {
            return;
        }
        try {
            player.release();
        } catch (Exception ignored) {
            // 忽略
        }
    }

    // ── 余额刷新 ────────────────────────────────────────────────────────

    private String defaultText() {
        String last = Prefs.lastBalance(this);
        if (last != null && !last.isEmpty()) {
            return last;
        }
        return "余额 --";
    }

    private void setBubble(String main, String detail) {
        if (tvBalance == null) {
            return;
        }
        tvBalance.setText(main);
        if (tvDetail != null) {
            if (detail == null || detail.isEmpty()) {
                tvDetail.setVisibility(View.GONE);
            } else {
                tvDetail.setText(detail);
                tvDetail.setVisibility(View.VISIBLE);
            }
        }
        if (bubbleBox != null) {
            bubbleBox.setVisibility(Prefs.showBubble(this) ? View.VISIBLE : View.GONE);
        }
    }

    /** 气泡回到「余额 + 今日已用」的常态显示。 */
    private void renderBalance() {
        setBubble(defaultText(), Ledger.todayText(this, Ledger.lastCurrency(this)));
    }

    private void refresh() {
        if (refreshing) {
            return;
        }
        String key = Prefs.apiKey(this);
        if (key.trim().isEmpty()) {
            setBubble("未填 Key", null);
            return;
        }
        refreshing = true;
        if (tvBalance != null && tvBalance.getText().toString().startsWith("余额")) {
            setBubble("查询中…", null);
        }
        BalanceFetcher.fetch(key, new BalanceFetcher.Callback() {
            @Override
            public void onResult(boolean ok, String text, double total, String currency) {
                refreshing = false;
                if (ok) {
                    Prefs.setLastBalance(BalanceService.this, text);
                    Ledger.observe(BalanceService.this, total, currency);
                }
                if (!patting) {
                    if (ok) {
                        renderBalance();
                    } else {
                        setBubble(text, null);
                    }
                }
                updateNotification(text);
            }
        });
    }

    private void scheduleTicker() {
        stopTicker();
        final int intervalMs = Prefs.intervalSec(this) * 1000;
        ticker = new Runnable() {
            @Override
            public void run() {
                refresh();
                handler.postDelayed(this, intervalMs);
            }
        };
        handler.postDelayed(ticker, intervalMs);
    }

    private void stopTicker() {
        if (ticker != null) {
            handler.removeCallbacks(ticker);
            ticker = null;
        }
    }

    // ── 通知（前台服务保活） ─────────────────────────────────────────────

    private void startAsForeground() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "鲸鱼桌宠", NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("保持悬浮桌宠不被系统回收");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        Notification notification = buildNotification(defaultText());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception ignored) {
            // 通知权限被关时忽略
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("鲸鱼娘余额桌宠")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 拖拽 / 点击 / 摸头 / 长按设置。 */
    private class DragTouchListener implements View.OnTouchListener {

        private float downRawX;
        private float downRawY;
        private int startX;
        private int startY;
        private boolean dragging;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = params.x;
                    startY = params.y;
                    dragging = false;
                    squish(true);
                    playSound(pressSound);
                    handler.postDelayed(getPatRunnable(), PAT_TRIGGER_MS);
                    handler.postDelayed(getSettingsRunnable(), SETTINGS_TRIGGER_MS);
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                        dragging = true;
                        cancelHoldTriggers();
                        stopPat();
                    }
                    if (dragging) {
                        params.x = startX + Math.round(dx);
                        params.y = startY + Math.round(dy);
                        safeUpdate();
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                    cancelHoldTriggers();
                    squish(false);
                    playSound(releaseSound);
                    if (dragging) {
                        snapToEdge();
                    } else if (patting) {
                        stopPat();
                    } else {
                        refresh();
                    }
                    Prefs.setPos(BalanceService.this, params.x, params.y);
                    dragging = false;
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    cancelHoldTriggers();
                    squish(false);
                    if (dragging) {
                        snapToEdge();
                        Prefs.setPos(BalanceService.this, params.x, params.y);
                    }
                    stopPat();
                    dragging = false;
                    return true;

                default:
                    return false;
            }
        }

        private void cancelHoldTriggers() {
            if (patRunnable != null) {
                handler.removeCallbacks(patRunnable);
            }
            if (settingsRunnable != null) {
                handler.removeCallbacks(settingsRunnable);
            }
        }

        private Runnable getPatRunnable() {
            if (patRunnable == null) {
                patRunnable = new Runnable() {
                    @Override
                    public void run() {
                        if (!dragging) {
                            startPat();
                        }
                    }
                };
            }
            return patRunnable;
        }

        private Runnable getSettingsRunnable() {
            if (settingsRunnable == null) {
                settingsRunnable = new Runnable() {
                    @Override
                    public void run() {
                        if (!dragging) {
                            openSettings();
                        }
                    }
                };
            }
            return settingsRunnable;
        }
    }
}
