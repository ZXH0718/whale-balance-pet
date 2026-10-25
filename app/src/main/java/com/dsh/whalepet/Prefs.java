package com.dsh.whalepet;

import android.content.Context;
import android.content.SharedPreferences;

/** 本机设置存储：API Key 只保存在应用私有 SharedPreferences，不上传任何服务器。 */
public final class Prefs {

    private static final String FILE = "whale_prefs";

    private Prefs() {
    }

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String apiKey(Context context) {
        return get(context).getString("api_key", "");
    }

    public static void setApiKey(Context context, String key) {
        get(context).edit().putString("api_key", key == null ? "" : key.trim()).apply();
    }

    public static int intervalSec(Context context) {
        int v = get(context).getInt("interval_sec", 60);
        return v < 15 ? 15 : Math.min(v, 3600);
    }

    public static void setIntervalSec(Context context, int sec) {
        get(context).edit().putInt("interval_sec", sec).apply();
    }

    public static boolean showBubble(Context context) {
        return get(context).getBoolean("show_bubble", true);
    }

    public static void setShowBubble(Context context, boolean show) {
        get(context).edit().putBoolean("show_bubble", show).apply();
    }

    public static float scale(Context context) {
        float v = get(context).getFloat("scale", 1f);
        return v < 0.5f ? 0.5f : Math.min(v, 2f);
    }

    public static void setScale(Context context, float scale) {
        get(context).edit().putFloat("scale", scale).apply();
    }

    public static int posX(Context context) {
        return get(context).getInt("pos_x", -1);
    }

    public static int posY(Context context) {
        return get(context).getInt("pos_y", -1);
    }

    public static void setPos(Context context, int x, int y) {
        get(context).edit().putInt("pos_x", x).putInt("pos_y", y).apply();
    }

    public static String lastBalance(Context context) {
        return get(context).getString("last_balance", "");
    }

    public static void setLastBalance(Context context, String text) {
        get(context).edit().putString("last_balance", text).apply();
    }
    public static boolean patSound(Context context) {
        return get(context).getBoolean("pat_sound", true);
    }

    public static void setPatSound(Context context, boolean on) {
        get(context).edit().putBoolean("pat_sound", on).apply();
    }

    public static boolean patVibrate(Context context) {
        return get(context).getBoolean("pat_vibrate", true);
    }

    public static void setPatVibrate(Context context, boolean on) {
        get(context).edit().putBoolean("pat_vibrate", on).apply();
    }

    public static int patCount(Context context) {
        return get(context).getInt("pat_count", 0);
    }

    /** 摸头计数 +1，返回本次应该用的台词序号（从 0 开始）。 */
    public static int bumpPatCount(Context context) {
        int current = patCount(context);
        get(context).edit().putInt("pat_count", current + 1).apply();
        return current;
    }
}
