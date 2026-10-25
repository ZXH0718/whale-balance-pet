package com.dsh.whalepet;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 本地记账：靠两次余额观测的差额记账，不碰任何接口之外的东西。
 *
 * 规则（与原版 dsh-whale-widget 的「小鲸鱼记账」一致）：
 *  - 余额下降 = 消费，累加到当天已用
 *  - 余额上升 = 充值 / 赠金，单独记，不会冲掉已有消费
 *  - 只有观测到变化才落账，所以账目颗粒度取决于刷新间隔
 */
public final class Ledger {

    public static final String KIND_SPEND = "spend";
    public static final String KIND_TOPUP = "topup";

    private static final int MAX_ENTRIES = 200;
    private static final String KEY_ENTRIES = "ledger_entries";
    private static final String KEY_LAST_TOTAL = "ledger_last_total";
    private static final String KEY_LAST_CURRENCY = "ledger_last_currency";
    private static final double EPS = 0.005;

    private Ledger() {
    }

    /** 一条账目：时间、变化额（负=消费）、观测到的余额、币种、类型。 */
    public static class Entry {
        public long ts;
        public double delta;
        public double balance;
        public String currency = "CNY";
        public String kind = KIND_SPEND;

        public boolean isSpend() {
            return KIND_SPEND.equals(kind);
        }
    }

    /** 喂一次观测值；有变化就落账。返回本次是否新增了账目。 */
    public static boolean observe(Context context, double total, String currency) {
        if (Double.isNaN(total)) {
            return false;
        }
        SharedPreferences prefs = Prefs.get(context);
        String cur = currency == null || currency.isEmpty() ? "CNY" : currency;
        boolean has = prefs.contains(KEY_LAST_TOTAL);
        double last = Double.longBitsToDouble(prefs.getLong(KEY_LAST_TOTAL, 0L));
        String lastCur = prefs.getString(KEY_LAST_CURRENCY, cur);

        prefs.edit()
                .putLong(KEY_LAST_TOTAL, Double.doubleToRawLongBits(total))
                .putString(KEY_LAST_CURRENCY, cur)
                .apply();

        if (!has || !lastCur.equals(cur)) {
            return false;
        }
        double delta = total - last;
        if (Math.abs(delta) < EPS) {
            return false;
        }
        Entry entry = new Entry();
        entry.ts = System.currentTimeMillis();
        entry.delta = delta;
        entry.balance = total;
        entry.currency = cur;
        entry.kind = delta < 0 ? KIND_SPEND : KIND_TOPUP;
        append(context, entry);
        return true;
    }

    private static List<Entry> load(Context context) {
        List<Entry> list = new ArrayList<>();
        String raw = Prefs.get(context).getString(KEY_ENTRIES, "");
        if (raw == null || raw.isEmpty()) {
            return list;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                Entry e = new Entry();
                e.ts = o.optLong("ts");
                e.delta = o.optDouble("delta", 0);
                e.balance = o.optDouble("balance", 0);
                e.currency = o.optString("currency", "CNY");
                e.kind = o.optString("kind", KIND_SPEND);
                list.add(e);
            }
        } catch (Exception ignored) {
            // 数据坏了就当空账本
        }
        return list;
    }

    private static void save(Context context, List<Entry> entries) {
        JSONArray array = new JSONArray();
        try {
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("ts", e.ts);
                o.put("delta", e.delta);
                o.put("balance", e.balance);
                o.put("currency", e.currency);
                o.put("kind", e.kind);
                array.put(o);
            }
        } catch (Exception ignored) {
            return;
        }
        Prefs.get(context).edit().putString(KEY_ENTRIES, array.toString()).apply();
    }

    private static void append(Context context, Entry entry) {
        List<Entry> entries = load(context);
        entries.add(0, entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
        save(context, entries);
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    }

    private static String dayOf(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date(ts));
    }

    /** 今天的消费总额（正数）。 */
    public static double todaySpent(Context context) {
        String today = today();
        double sum = 0;
        for (Entry e : load(context)) {
            if (e.isSpend() && today.equals(dayOf(e.ts))) {
                sum += Math.abs(e.delta);
            }
        }
        return sum;
    }

    /** 今天的充值 / 赠金总额（正数）。 */
    public static double todayTopUp(Context context) {
        String today = today();
        double sum = 0;
        for (Entry e : load(context)) {
            if (!e.isSpend() && today.equals(dayOf(e.ts))) {
                sum += e.delta;
            }
        }
        return sum;
    }

    /** 累计消费（全部历史）。 */
    public static double totalSpent(Context context) {
        double sum = 0;
        for (Entry e : load(context)) {
            if (e.isSpend()) {
                sum += Math.abs(e.delta);
            }
        }
        return sum;
    }

    public static int todayCount(Context context) {
        String today = today();
        int n = 0;
        for (Entry e : load(context)) {
            if (e.isSpend() && today.equals(dayOf(e.ts))) {
                n++;
            }
        }
        return n;
    }

    public static List<Entry> recent(Context context, int limit) {
        List<Entry> all = load(context);
        return all.size() <= limit ? all : new ArrayList<>(all.subList(0, limit));
    }

    public static void clear(Context context) {
        Prefs.get(context).edit()
                .remove(KEY_ENTRIES)
                .remove(KEY_LAST_TOTAL)
                .remove(KEY_LAST_CURRENCY)
                .apply();
    }

    // ── 格式化 ──────────────────────────────────────────────────────────

    public static String symbol(String currency) {
        if ("CNY".equalsIgnoreCase(currency)) {
            return "\u00a5";
        }
        if ("USD".equalsIgnoreCase(currency)) {
            return "$";
        }
        return currency == null || currency.isEmpty() ? "" : currency + " ";
    }

    public static String money(double value) {
        return String.format(Locale.CHINA, "%.2f", value);
    }

    /** 气泡第二行：「今日 -¥0.42」或「今日 还没花钱」。 */
    public static String todayText(Context context, String currency) {
        String sym = symbol(currency);
        int count = todayCount(context);
        if (count == 0) {
            return "今日 还没花钱";
        }
        return "今日 -" + sym + money(todaySpent(context)) + " · " + count + " 笔";
    }

    /** 设置页汇总。 */
    public static String summaryText(Context context, String currency) {
        String sym = symbol(currency);
        StringBuilder sb = new StringBuilder();
        sb.append("今日已用 ").append(sym).append(money(todaySpent(context)))
                .append("（").append(todayCount(context)).append(" 笔）");
        double topUp = todayTopUp(context);
        if (topUp > 0) {
            sb.append("\n今日充值 ").append(sym).append(money(topUp));
        }
        sb.append("\n累计已用 ").append(sym).append(money(totalSpent(context)));
        return sb.toString();
    }

    /** 明细列表文本，每行一条。 */
    public static String detailText(Context context, int limit) {
        List<Entry> entries = recent(context, limit);
        if (entries.isEmpty()) {
            return "还没有账目。余额发生变化时会自动记一笔。";
        }
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            String sym = symbol(e.currency);
            sb.append(fmt.format(new Date(e.ts)))
                    .append(e.isSpend() ? "  消费 -" : "  充值 +")
                    .append(sym).append(money(Math.abs(e.delta)))
                    .append("   余 ").append(sym).append(money(e.balance))
                    .append("\n");
        }
        return sb.toString().trim();
    }

    public static String lastCurrency(Context context) {
        return Prefs.get(context).getString(KEY_LAST_CURRENCY, "CNY");
    }
}
