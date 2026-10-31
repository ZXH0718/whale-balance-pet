package com.dsh.whalepet;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 前台服务：鲸鱼娘悬浮桌宠。
 *
 * 结构上分开成两个悬浮窗：
 *  - 鲸鱼窗口：尺寸恒等于鲸鱼，拖拽/贴边只动这个窗口
 *  - 气泡窗口：独立窗口，按鲸鱼位置摆到头顶
 * 这样气泡出现或消失都不会改变鲸鱼窗口的尺寸，鲸鱼不会因为重排而漂移。
 *
 * 交互：点鲸鱼或气泡刷新余额；按住 1 秒摸头；按住 3 秒弹出小设置面板；拖拽跟手并吸附左右边缘。
 */
public class BalanceService extends Service {

    public static final String ACTION_START = "com.dsh.whalepet.action.START";
    public static final String ACTION_STOP = "com.dsh.whalepet.action.STOP";
    public static final String ACTION_REFRESH = "com.dsh.whalepet.action.REFRESH";

    private static final String CHANNEL_ID = "whale_pet";
    private static final int NOTIFICATION_ID = 1001;

    /** 鲸鱼边长（dp），另存一份给气泡按比例换算 */
    private static final int WHALE_DP = 96;
    /** 气泡宽 = 鲸鱼宽 × 这个倍数 */
    private static final float BUBBLE_WIDTH_FACTOR = 1.6f;
    /** 气泡高宽比（原版画布 700/1026 = 0.682，压扁到 0.52） */
    private static final float BUBBLE_HEIGHT_FACTOR = 0.52f;
    /** 原版泡泡画布宽度，字号比例 u = 气泡宽 / 1026 */
    private static final float FRAME_W = 1026f;
    private static final float FONT_LABEL_U = 66f;
    private static final float FONT_AMOUNT_U = 128f;
    private static final float FONT_HINT_U = 56f;
    private static final float FONT_LINE_U = 76f;
    private static final float HINT_MARGIN_U = 9f;
    private static final float HINT_MIN_H_U = 64f;
    private static final long ROLL_MS = 700L;

    private static final float SQUISH_SCALE_Y = 0.88f;
    private static final float SQUISH_SCALE_X = 1.05f;
    private static final int SQUISH_MS = 90;
    private static final float PAT_SCALE = 1.18f;
    private static final int PAT_LIFT_DP = 8;
    private static final int PAT_POP_MS = 160;
    private static final int PAT_FRAME_MS = 20;
    private static final int PAT_TRIGGER_MS = 1000;
    private static final int SETTINGS_TRIGGER_MS = 3000;
    private static final int LINE_MS = 2600;
    /** 每摸这么多次喊一句「哦鲸鲸…」 */
    private static final int OOJING_EVERY = 10;
    /** 摸到这么多次触发「一个亿」彩蛋 */
    private static final int EASTER_EGG_AT = 100;
    /** 彩蛋持续时间：1 分钟 */
    private static final long EASTER_EGG_MS = 60_000L;
    private static final long BUBBLE_MS = 3000L;
    private static final long BUBBLE_HIDE_MS = 160L;
    /** 气泡底部压在鲸鱼头顶的重叠量 */
    private static final int BUBBLE_OVERLAP_DP = 6;
    private static final int EDGE_MARGIN_DP = 2;

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

    private View petView;
    private WindowManager.LayoutParams petParams;
    private ImageView whaleView;

    private View bubbleView;
    private WindowManager.LayoutParams bubbleParams;
    private View bubbleTextBox;
    private TextView tvLabel;
    private TextView tvBalance;
    private TextView tvDetail;
    private boolean bubbleAttached;
    private boolean bubbleVisible;
    private AnimationDrawable patAnim;

    private View panel;
    private WindowManager.LayoutParams panelParams;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable ticker;
    private Runnable patRunnable;
    private Runnable settingsRunnable;
    private Runnable restoreLineRunnable;
    private Runnable hideBubbleRunnable;

    private MediaPlayer pressSound;
    private MediaPlayer releaseSound;

    private int touchSlop;
    private boolean refreshing;
    private boolean patting;
    private boolean holdOpenedSettings;

    private double shownTotal = Double.NaN;
    private String shownCurrency = "CNY";

    /** 彩蛋结束时间戳；> now 表示「一个亿」显示中 */
    private long easterEggUntil;
    private Runnable easterEggEndRunnable;

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
        if (petView == null) {
            showPet();
        }
        refresh();
        showBubbleAnimated();
        scheduleTicker();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        hidePanel();
        if (easterEggEndRunnable != null) {
            handler.removeCallbacks(easterEggEndRunnable);
            easterEggEndRunnable = null;
        }
        stopTicker();
        handler.removeCallbacksAndMessages(null);
        releaseSound(pressSound);
        releaseSound(releaseSound);
        detachBubble();
        if (petView != null && windowManager != null) {
            try {
                windowManager.removeView(petView);
            } catch (Exception ignored) {
                // 已经不在
            }
            petView = null;
        }
        super.onDestroy();
    }

    // ── 鲸鱼窗口 ────────────────────────────────────────────────────────

    private void showPet() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        petView = LayoutInflater.from(this).inflate(R.layout.overlay_pet, null);
        whaleView = petView.findViewById(R.id.iv_whale);
        whaleView.setPivotY(0f);
        applyPetLook();

        petParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        petParams.gravity = Gravity.TOP | Gravity.START;

        DisplayMetrics metrics = screenMetrics();
        int whale = whalePx();
        int savedX = Prefs.posX(this);
        int savedY = Prefs.posY(this);
        petParams.x = savedX < 0 ? metrics.widthPixels - whale - dp(EDGE_MARGIN_DP) : savedX;
        petParams.y = savedY < 0 ? Math.round(metrics.heightPixels * 0.35f) : savedY;
        clampPet();

        petView.setOnTouchListener(new PetTouchListener());
        try {
            windowManager.addView(petView, petParams);
        } catch (Exception e) {
            petView = null;
            stopSelf();
            return;
        }
        renderBalance();
    }

    private void applyPetLook() {
        int whale = whalePx();
        if (whaleView != null) {
            ViewGroup.LayoutParams lp = whaleView.getLayoutParams();
            if (lp == null) {
                lp = new ViewGroup.LayoutParams(whale, whale);
            } else {
                lp.width = whale;
                lp.height = whale;
            }
            whaleView.setLayoutParams(lp);
        }
        if (petView != null) {
            petView.requestLayout();
        }
        if (petParams != null) {
            safeUpdatePet();
        }
        positionBubble();
    }

    private void safeUpdatePet() {
        if (petView == null || windowManager == null) {
            return;
        }
        try {
            windowManager.updateViewLayout(petView, petParams);
        } catch (Exception ignored) {
            // 窗口已消失
        }
    }

    /** 保证鲸鱼窗口完整留在屏幕内。 */
    private void clampPet() {
        DisplayMetrics metrics = screenMetrics();
        int whale = whalePx();
        if (petParams.x < 0) {
            petParams.x = 0;
        }
        if (petParams.x > metrics.widthPixels - whale) {
            petParams.x = metrics.widthPixels - whale;
        }
        if (petParams.y < 0) {
            petParams.y = 0;
        }
        if (petParams.y > metrics.heightPixels - whale) {
            petParams.y = metrics.heightPixels - whale;
        }
    }

    private void snapToEdge() {
        DisplayMetrics metrics = screenMetrics();
        int whale = whalePx();
        int margin = dp(EDGE_MARGIN_DP);
        if (Prefs.snapEdge(this)) {
            int center = petParams.x + whale / 2;
            petParams.x = center < metrics.widthPixels / 2
                    ? margin
                    : metrics.widthPixels - whale - margin;
        }
        clampPet();
        safeUpdatePet();
        positionBubble();
        Prefs.setPos(this, petParams.x, petParams.y);
    }

    // ── 气泡窗口 ────────────────────────────────────────────────────────

    private void ensureBubble() {
        if (bubbleView != null || windowManager == null) {
            return;
        }
        bubbleView = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null);
        bubbleTextBox = bubbleView.findViewById(R.id.bubble_text);
        tvLabel = bubbleView.findViewById(R.id.tv_label);
        tvBalance = bubbleView.findViewById(R.id.tv_balance);
        tvDetail = bubbleView.findViewById(R.id.tv_detail);

        bubbleParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;

        bubbleView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 点气泡：刷新余额 + 重播弹出动画
                refresh();
                showBubbleAnimated();
            }
        });
        applyBubbleLook();
    }

    private void applyBubbleLook() {
        if (bubbleView == null) {
            return;
        }
        int bubbleWidth = bubbleWidthPx();
        int bubbleHeight = bubbleHeightPx();
        float u = bubbleWidth / FRAME_W;

        // 显式告诉 BubbleBox 自己的尺寸（悬浮窗是 WRAP_CONTENT，背景矢量图的固有尺寸不能当尺寸用）
        if (bubbleView instanceof BubbleBox) {
            ((BubbleBox) bubbleView).setBubbleSize(bubbleWidth, bubbleHeight);
        }
        bubbleView.setPivotX(bubbleWidth / 2f);
        bubbleView.setPivotY(bubbleHeight * 0.7f);

        if (tvLabel != null) {
            tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, FONT_LABEL_U * u);
            tvLabel.setLetterSpacing(0.06f);
            tvLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        if (tvBalance != null) {
            tvBalance.setTextSize(TypedValue.COMPLEX_UNIT_PX, FONT_AMOUNT_U * u);
            tvBalance.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        }
        if (tvDetail != null) {
            tvDetail.setTextSize(TypedValue.COMPLEX_UNIT_PX, FONT_HINT_U * u);
            tvDetail.setLetterSpacing(0.02f);
            LinearLayout.LayoutParams hintParams =
                    (LinearLayout.LayoutParams) tvDetail.getLayoutParams();
            if (hintParams != null) {
                hintParams.topMargin = Math.round(HINT_MARGIN_U * u);
                tvDetail.setLayoutParams(hintParams);
            }
            tvDetail.setMinHeight(Math.round(HINT_MIN_H_U * u));
        }
        positionBubble();
    }

    /** 把气泡摆到鲸鱼头顶（独立窗口，鲸鱼不会因此移动）。 */
    private void positionBubble() {
        if (bubbleParams == null || petParams == null) {
            return;
        }
        DisplayMetrics metrics = screenMetrics();
        int whale = whalePx();
        int bubbleWidth = bubbleWidthPx();
        int bubbleHeight = bubbleHeightPx();

        int x = petParams.x + (whale - bubbleWidth) / 2;
        int y = petParams.y - bubbleHeight + dp(BUBBLE_OVERLAP_DP);

        if (x < 0) {
            x = 0;
        }
        if (x > metrics.widthPixels - bubbleWidth) {
            x = Math.max(0, metrics.widthPixels - bubbleWidth);
        }
        if (y < 0) {
            y = 0;
        }
        bubbleParams.x = x;
        bubbleParams.y = y;
        if (bubbleAttached) {
            safeUpdateBubble();
        }
    }

    private void safeUpdateBubble() {
        if (bubbleView == null || windowManager == null || !bubbleAttached) {
            return;
        }
        try {
            windowManager.updateViewLayout(bubbleView, bubbleParams);
        } catch (Exception ignored) {
            // 窗口已消失
        }
    }

    private void showBubbleAnimated() {
        if (!Prefs.showBubble(this)) {
            return;
        }
        ensureBubble();
        if (bubbleView == null || windowManager == null) {
            return;
        }
        positionBubble();
        if (!bubbleAttached) {
            bubbleView.setAlpha(0f);
            bubbleView.setScaleX(0.86f);
            bubbleView.setScaleY(0.86f);
            try {
                windowManager.addView(bubbleView, bubbleParams);
                bubbleAttached = true;
            } catch (Exception e) {
                bubbleAttached = false;
                return;
            }
        }
        bubbleVisible = true;
        popBubble();
        scheduleHideBubble();
    }

    private void hideBubbleAnimated() {
        if (!bubbleAttached || bubbleView == null) {
            return;
        }
        bubbleVisible = false;
        bubbleView.animate()
                .alpha(0f)
                .scaleX(0.94f)
                .scaleY(0.94f)
                .setDuration(BUBBLE_HIDE_MS)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (!bubbleVisible) {
                            detachBubble();
                        }
                    }
                })
                .start();
    }

    private void detachBubble() {
        if (!bubbleAttached || bubbleView == null || windowManager == null) {
            bubbleAttached = false;
            return;
        }
        try {
            windowManager.removeView(bubbleView);
        } catch (Exception ignored) {
            // 已经不在
        }
        bubbleAttached = false;
        bubbleVisible = false;
        bubbleView.setAlpha(1f);
        bubbleView.setScaleX(1f);
        bubbleView.setScaleY(1f);
    }

    private void scheduleHideBubble() {
        if (Prefs.bubblePinned(this)) {
            return;
        }
        if (hideBubbleRunnable != null) {
            handler.removeCallbacks(hideBubbleRunnable);
        }
        hideBubbleRunnable = new Runnable() {
            @Override
            public void run() {
                hideBubbleRunnable = null;
                hideBubbleAnimated();
            }
        };
        handler.postDelayed(hideBubbleRunnable, BUBBLE_MS);
    }

    private void cancelHideBubble() {
        if (hideBubbleRunnable != null) {
            handler.removeCallbacks(hideBubbleRunnable);
            hideBubbleRunnable = null;
        }
    }

    private void popBubble() {
        if (bubbleView == null) {
            return;
        }
        bubbleView.setAlpha(0f);
        bubbleView.setScaleX(0.86f);
        bubbleView.setScaleY(0.86f);
        bubbleView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220L)
                .setInterpolator(new OvershootInterpolator(1.6f))
                .start();
        if (bubbleTextBox != null) {
            bubbleTextBox.setAlpha(0f);
            bubbleTextBox.animate().alpha(1f).setStartDelay(120L).setDuration(160L).start();
        }
    }

    // ── 气泡内容 ────────────────────────────────────────────────────────

    private void setLines(String label, String amount, String hint, boolean lineMode) {
        if (tvBalance == null) {
            return;
        }
        float u = bubbleWidthPx() / FRAME_W;
        if (tvLabel != null) {
            if (label == null || label.isEmpty()) {
                tvLabel.setVisibility(View.GONE);
            } else {
                tvLabel.setText(label);
                tvLabel.setVisibility(View.VISIBLE);
            }
        }
        tvBalance.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                (lineMode ? FONT_LINE_U : FONT_AMOUNT_U) * u);
        tvBalance.setMaxLines(lineMode ? 2 : 1);
        tvBalance.setText(amount == null ? "" : amount);
        if (tvDetail != null) {
            if (hint == null || hint.isEmpty()) {
                tvDetail.setVisibility(View.GONE);
            } else {
                tvDetail.setText(hint);
                tvDetail.setVisibility(View.VISIBLE);
            }
        }
    }

    private void renderBalance() {
        String amount;
        String hint;
        if (easterEggActive()) {
            amount = getString(R.string.easter_egg_amount);
            hint = getString(R.string.easter_egg_hint);
        } else {
            amount = Double.isNaN(shownTotal)
                    ? getString(R.string.bubble_loading)
                    : Ledger.amountText(shownTotal, shownCurrency);
            hint = Ledger.usageHint(this, shownCurrency);
        }
        setLines(getString(R.string.bubble_label), amount, hint, false);
    }

    private void rollAmount(double from, double to, String currency) {
        if (tvBalance == null || Double.isNaN(to) || easterEggActive()) {
            return;
        }
        if (Double.isNaN(from) || Math.abs(to - from) < 0.005) {
            tvBalance.setText(Ledger.amountText(to, currency));
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(ROLL_MS);
        animator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            float eased = 1f - (float) Math.pow(1f - t, 3f);
            tvBalance.setText(Ledger.amountText(from + (to - from) * eased, currency));
        });
        animator.start();
    }

    private void showLine(String line) {
        if (tvBalance == null) {
            return;
        }
        setLines(null, line, "摸摸头 · 第 " + Prefs.patCount(this) + " 次", true);
        showBubbleAnimated();
        if (restoreLineRunnable != null) {
            handler.removeCallbacks(restoreLineRunnable);
        }
        restoreLineRunnable = new Runnable() {
            @Override
            public void run() {
                renderBalance();
                if (bubbleVisible) {
                    scheduleHideBubble();
                }
            }
        };
        handler.postDelayed(restoreLineRunnable, LINE_MS);
    }

    // ── 摸头 / 音效 ─────────────────────────────────────────────────────

    private void squish(boolean down) {
        if (whaleView == null) {
            return;
        }
        whaleView.animate()
                .scaleX(down ? SQUISH_SCALE_X : 1f)
                .scaleY(down ? SQUISH_SCALE_Y : 1f)
                .setDuration(SQUISH_MS)
                .start();
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
        whaleView.animate()
                .scaleX(PAT_SCALE)
                .scaleY(PAT_SCALE)
                .translationY(-dp(PAT_LIFT_DP))
                .setDuration(PAT_POP_MS)
                .start();
        if (Prefs.patVibrate(this)) {
            vibrate(18);
        }
        // bumpPatCount 返回的是本次之前的次数，+1 得到累计次数（从 1 开始）
        int count = Prefs.bumpPatCount(this) + 1;
        if (count % EASTER_EGG_AT == 0) {
            showLine("哦鲸鲸…！一…一个亿？！");
            triggerEasterEgg();
        } else if (count % OOJING_EVERY == 0) {
            showLine("哦鲸鲸…");
        } else {
            showLine(PAT_LINES[(count - 1) % PAT_LINES.length]);
        }
    }

    private void stopPat() {
        patting = false;
        if (patAnim != null) {
            patAnim.stop();
        }
        if (whaleView != null) {
            whaleView.setImageResource(R.drawable.whale);
            whaleView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(140)
                    .start();
        }
    }

    /** 摸满 100 次：余额变「一个亿」，1 分钟后恢复。 */
    private void triggerEasterEgg() {
        easterEggUntil = System.currentTimeMillis() + EASTER_EGG_MS;
        vibrate(80);
        if (easterEggEndRunnable != null) {
            handler.removeCallbacks(easterEggEndRunnable);
        }
        easterEggEndRunnable = new Runnable() {
            @Override
            public void run() {
                easterEggEndRunnable = null;
                easterEggUntil = 0L;
                renderBalance();
                if (bubbleVisible) {
                    scheduleHideBubble();
                }
            }
        };
        handler.postDelayed(easterEggEndRunnable, EASTER_EGG_MS);
    }

    private boolean easterEggActive() {
        return System.currentTimeMillis() < easterEggUntil;
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
            player.seekTo(0);
            player.start();
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

    // ── 小设置面板 ──────────────────────────────────────────────────────

    private void showPanel() {
        if (windowManager == null || !Settings.canDrawOverlays(this)) {
            return;
        }
        if (panel != null) {
            hidePanel();
            return;
        }
        panel = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null);
        bindPanel(panel);
        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics metrics = screenMetrics();
        panelParams.x = dp(16);
        panelParams.y = Math.round(metrics.heightPixels * 0.22f);
        try {
            windowManager.addView(panel, panelParams);
        } catch (Exception e) {
            panel = null;
            return;
        }
        final View added = panel;
        added.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (panel == null || panel != added) {
                    return;
                }
                int width = v.getWidth();
                int height = v.getHeight();
                if (width <= 0 || height <= 0) {
                    return;
                }
                DisplayMetrics metrics = screenMetrics();
                int targetX = Math.max(dp(8), (metrics.widthPixels - width) / 2);
                int maxY = metrics.heightPixels - height - dp(8);
                int targetY = Math.min(panelParams.y, Math.max(dp(8), maxY));
                if (targetX != panelParams.x || targetY != panelParams.y) {
                    panelParams.x = targetX;
                    panelParams.y = targetY;
                    updatePanelPosition();
                }
            }
        });
    }

    private void updatePanelPosition() {
        if (panel == null || windowManager == null) {
            return;
        }
        try {
            windowManager.updateViewLayout(panel, panelParams);
        } catch (Exception ignored) {
            // 面板已移除
        }
    }

    private void hidePanel() {
        if (panel == null) {
            return;
        }
        if (windowManager != null) {
            try {
                windowManager.removeView(panel);
            } catch (Exception ignored) {
                // 已经不在
            }
        }
        panel = null;
    }

    private void bindPanel(View root) {
        Switch pinned = root.findViewById(R.id.pn_pinned);
        Switch snap = root.findViewById(R.id.pn_snap);
        Switch sound = root.findViewById(R.id.pn_sound);
        Switch vibrate = root.findViewById(R.id.pn_vibrate);

        pinned.setChecked(Prefs.bubblePinned(this));
        snap.setChecked(Prefs.snapEdge(this));
        sound.setChecked(Prefs.patSound(this));
        vibrate.setChecked(Prefs.patVibrate(this));

        pinned.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setBubblePinned(BalanceService.this, isChecked);
            if (isChecked) {
                showBubbleAnimated();
            } else {
                scheduleHideBubble();
            }
        });
        snap.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setSnapEdge(BalanceService.this, isChecked);
            if (isChecked) {
                snapToEdge();
            }
        });
        sound.setOnCheckedChangeListener((buttonView, isChecked) ->
                Prefs.setPatSound(BalanceService.this, isChecked));
        vibrate.setOnCheckedChangeListener((buttonView, isChecked) ->
                Prefs.setPatVibrate(BalanceService.this, isChecked));

        root.findViewById(R.id.pn_refresh).setOnClickListener(v -> {
            refresh();
            showBubbleAnimated();
        });
        root.findViewById(R.id.pn_full).setOnClickListener(v -> {
            hidePanel();
            openSettings();
        });
        root.findViewById(R.id.pn_close).setOnClickListener(v -> hidePanel());
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
        return "--";
    }

    private void refresh() {
        if (refreshing) {
            return;
        }
        String key = Prefs.apiKey(this);
        if (key.trim().isEmpty()) {
            ensureBubble();
            setLines(getString(R.string.bubble_label), "--", "未填 API Key", false);
            return;
        }
        refreshing = true;
        if (Double.isNaN(shownTotal)) {
            ensureBubble();
            setLines(getString(R.string.bubble_label),
                    getString(R.string.bubble_loading),
                    getString(R.string.bubble_loading_hint), false);
        }
        BalanceFetcher.fetch(key, new BalanceFetcher.Callback() {
            @Override
            public void onResult(boolean ok, String text, double total, String currency) {
                refreshing = false;
                if (ok) {
                    Prefs.setLastBalance(BalanceService.this, text);
                    Ledger.observe(BalanceService.this, total, currency);
                    double from = shownTotal;
                    if (currency != null && !currency.isEmpty()) {
                        shownCurrency = currency;
                    }
                    shownTotal = total;
                    if (!patting) {
                        ensureBubble();
                        renderBalance();
                        if (!easterEggActive()) {
                            rollAmount(from, total, shownCurrency);
                        }
                        if (bubbleVisible) {
                            scheduleHideBubble();
                        }
                    }
                } else if (!patting && bubbleVisible) {
                    String hint = text == null ? getString(R.string.bubble_error_hint) : text;
                    if (hint.length() > 14) {
                        hint = hint.substring(0, 14);
                    }
                    setLines(getString(R.string.bubble_label),
                            Double.isNaN(shownTotal) ? "--" : Ledger.amountText(shownTotal, shownCurrency),
                            hint, false);
                    scheduleHideBubble();
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

    // ── 通知 ────────────────────────────────────────────────────────────

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

    // ── 尺寸与手势 ──────────────────────────────────────────────────────

    private int whalePx() {
        return dp(Math.round(WHALE_DP * Prefs.scale(this)));
    }

    private int bubbleWidthPx() {
        return Math.round(whalePx() * BUBBLE_WIDTH_FACTOR);
    }

    private int bubbleHeightPx() {
        return Math.round(bubbleWidthPx() * BUBBLE_HEIGHT_FACTOR);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private DisplayMetrics screenMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        if (windowManager != null) {
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
        } else {
            metrics.setTo(getResources().getDisplayMetrics());
        }
        return metrics;
    }

    private int overlayWindowType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /** 鲸鱼上的：拖拽 / 点击 / 摸头 / 长按设置。 */
    private class PetTouchListener implements View.OnTouchListener {

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
                    startX = petParams.x;
                    startY = petParams.y;
                    dragging = false;
                    holdOpenedSettings = false;
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
                        petParams.x = startX + Math.round(dx);
                        petParams.y = startY + Math.round(dy);
                        safeUpdatePet();
                        positionBubble();
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
                    } else if (holdOpenedSettings) {
                        holdOpenedSettings = false;
                    } else {
                        // 点鲸鱼：只做按下压缩 + 松手回弹，不刷新余额、不碰气泡
                    }
                    Prefs.setPos(BalanceService.this, petParams.x, petParams.y);
                    dragging = false;
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    cancelHoldTriggers();
                    squish(false);
                    holdOpenedSettings = false;
                    if (dragging) {
                        snapToEdge();
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
                            holdOpenedSettings = true;
                            showPanel();
                        }
                    }
                };
            }
            return settingsRunnable;
        }
    }
}
