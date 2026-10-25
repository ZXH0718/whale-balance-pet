package com.dsh.whalepet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 前台服务：负责在屏幕角落放一只鲸鱼娘 + 余额气泡。
 * 拖拽可移动，松手自动吸附左右边缘，位置记在本机；点一下立即刷新，长按打开设置。
 */
public class BalanceService extends Service {

    public static final String ACTION_START = "com.dsh.whalepet.action.START";
    public static final String ACTION_STOP = "com.dsh.whalepet.action.STOP";
    public static final String ACTION_REFRESH = "com.dsh.whalepet.action.REFRESH";

    private static final String CHANNEL_ID = "whale_pet";
    private static final int NOTIFICATION_ID = 1001;
    private static final int EDGE_MARGIN_DP = 6;
    private static final int WHALE_DP = 72;

    private WindowManager windowManager;
    private View overlay;
    private TextView bubble;
    private WindowManager.LayoutParams params;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable ticker;
    private int touchSlop;
    private boolean refreshing;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
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
        bubble = overlay.findViewById(R.id.tv_bubble);
        bubble.setText(defaultText());
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
        View whale = overlay.findViewById(R.id.iv_whale);
        if (whale != null) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) whale.getLayoutParams();
            lp.width = dp(Math.round(WHALE_DP * scale));
            lp.height = dp(Math.round(WHALE_DP * scale));
            whale.setLayoutParams(lp);
        }
        if (bubble != null) {
            bubble.setVisibility(Prefs.showBubble(this) ? View.VISIBLE : View.GONE);
            bubble.setTextSize(13f * scale);
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

    // ── 余额刷新 ────────────────────────────────────────────────────────

    private String defaultText() {
        String last = Prefs.lastBalance(this);
        if (last != null && !last.isEmpty()) {
            return last;
        }
        return "余额 --";
    }

    private void setBubbleText(String text) {
        if (bubble == null) {
            return;
        }
        bubble.setText(text);
        bubble.setVisibility(Prefs.showBubble(this) ? View.VISIBLE : View.GONE);
    }

    private void refresh() {
        if (refreshing) {
            return;
        }
        String key = Prefs.apiKey(this);
        if (key.trim().isEmpty()) {
            setBubbleText("未填 Key");
            return;
        }
        refreshing = true;
        if (bubble != null && bubble.getText().toString().startsWith("余额")) {
            setBubbleText("查询中…");
        }
        BalanceFetcher.fetch(key, new BalanceFetcher.Callback() {
            @Override
            public void onResult(boolean ok, String text) {
                refreshing = false;
                setBubbleText(text);
                if (ok) {
                    Prefs.setLastBalance(BalanceService.this, text);
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

    /** 拖拽 / 点击 / 长按。 */
    private class DragTouchListener implements View.OnTouchListener {

        private float downRawX;
        private float downRawY;
        private int startX;
        private int startY;
        private boolean dragging;

        private final Runnable longPress = new Runnable() {
            @Override
            public void run() {
                if (!dragging) {
                    openSettings();
                }
            }
        };

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = params.x;
                    startY = params.y;
                    dragging = false;
                    handler.postDelayed(longPress, 600L);
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                        dragging = true;
                        handler.removeCallbacks(longPress);
                    }
                    if (dragging) {
                        params.x = startX + Math.round(dx);
                        params.y = startY + Math.round(dy);
                        safeUpdate();
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(longPress);
                    if (dragging) {
                        snapToEdge();
                    } else {
                        refresh();
                    }
                    Prefs.setPos(BalanceService.this, params.x, params.y);
                    dragging = false;
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPress);
                    if (dragging) {
                        snapToEdge();
                        Prefs.setPos(BalanceService.this, params.x, params.y);
                    }
                    dragging = false;
                    return true;

                default:
                    return false;
            }
        }
    }
}
