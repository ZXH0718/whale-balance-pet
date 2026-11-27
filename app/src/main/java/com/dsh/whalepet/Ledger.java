package com.dsh.whalepet;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.Date;
import java.util.List;
import java.util.Set;
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
    /** 金额动画 700ms 内会高频格式化；复用线程本地 DecimalFormat，避免每帧 String.format 分配 Formatter。 */
    private static final ThreadLocal<DecimalFormat> MONEY_FORMAT = ThreadLocal.withInitial(() -> {
        DecimalFormat format = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.CHINA));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format;
    });

    /** 缓存 JSON 解析结果；账本最多 200 条，避免每次刷新/重绘都重新解析整份 JSON。 */
    private static final Object CACHE_LOCK = new Object();
    private static String cachedRaw;
    private static List<Entry> cachedEntries = Collections.emptyList();

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

    /** 返回不可变缓存快照；避免每次统计/渲染都重解析 JSON 并复制最多 200 条记录。 */
    private static List<Entry> snapshot(Context context) {
        synchronized (CACHE_LOCK) {
            String raw = Prefs.get(context).getString(KEY_ENTRIES, "");
            if (raw == null) {
                raw = "";
            }
            if (raw.equals(cachedRaw)) {
                return cachedEntries;
            }
            List<Entry> list = new ArrayList<>();
            if (!raw.isEmpty()) {
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
            }
            cachedRaw = raw;
            cachedEntries = Collections.unmodifiableList(list);
            return cachedEntries;
        }
    }

    /** 需要排序或修改的场景用可变副本。 */
    private static List<Entry> load(Context context) {
        return new ArrayList<>(snapshot(context));
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
        String raw = array.toString();
        Prefs.get(context).edit().putString(KEY_ENTRIES, raw).apply();
        synchronized (CACHE_LOCK) {
            cachedRaw = raw;
            cachedEntries = Collections.unmodifiableList(new ArrayList<>(entries));
            cachedStatsRaw = null;
            cachedStats = null;
        }
    }

    private static void append(Context context, Entry entry) {
        List<Entry> entries = load(context);
        entries.add(0, entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
        save(context, entries);
    }

    private static class Stats {
        double todaySpent;
        double todayTopUp;
        double totalSpent;
        int todayCount;
    }

    /** 一次遍历算出今日/累计统计；使用日界毫秒区间，避免对每条账都创建 SimpleDateFormat。 */
    private static Stats stats(Context context) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long start = calendar.getTimeInMillis();
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        long end = calendar.getTimeInMillis();

        Stats result = new Stats();
        // snapshot() 命中缓存时不解析 JSON、不复制列表；200 条以内单次遍历非常轻。
        for (Entry e : snapshot(context)) {
            if (e.isSpend()) {
                double amount = Math.abs(e.delta);
                result.totalSpent += amount;
                if (e.ts >= start && e.ts < end) {
                    result.todaySpent += amount;
                    result.todayCount++;
                }
            } else if (e.ts >= start && e.ts < end) {
                result.todayTopUp += e.delta;
            }
        }
        return result;
    }

    /** 今天的消费总额（正数）。 */
    public static double todaySpent(Context context) {
        return stats(context).todaySpent;
    }

    /** 今天的充值 / 赠金总额（正数）。 */
    public static double todayTopUp(Context context) {
        return stats(context).todayTopUp;
    }

    /** 累计消费（全部历史）。 */
    public static double totalSpent(Context context) {
        return stats(context).totalSpent;
    }

    public static int todayCount(Context context) {
        return stats(context).todayCount;
    }

    public static List<Entry> recent(Context context, int limit) {
        List<Entry> all = snapshot(context);
        return all.size() <= limit ? all : new ArrayList<>(all.subList(0, limit));
    }

    public static void clear(Context context) {
        Prefs.get(context).edit()
                .remove(KEY_ENTRIES)
                .remove(KEY_LAST_TOTAL)
                .remove(KEY_LAST_CURRENCY)
                .apply();
        synchronized (CACHE_LOCK) {
            cachedRaw = "";
            cachedEntries = Collections.emptyList();
        }
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
        return MONEY_FORMAT.get().format(value);
    }

    /** 原版金额格式：CNY →「¥ 12.34」，其它 →「12.34 USD」，取不到 →「--」。 */
    public static String amountText(double value, String currency) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "--";
        }
        String fixed = money(value);
        if (currency == null || currency.isEmpty() || "CNY".equalsIgnoreCase(currency)) {
            return "\u00a5 " + fixed;
        }
        return fixed + " " + currency;
    }

    /** 泡泡提示行（原版文案：「今日已用 ¥ 0.42」）。 */
    public static String usageHint(Context context, String currency) {
        return "今日已用 " + amountText(todaySpent(context), currency);
    }

    /** 上一次观测到的总余额，没有则为 NaN。 */
    public static double lastTotal(Context context) {
        SharedPreferences prefs = Prefs.get(context);
        if (!prefs.contains(KEY_LAST_TOTAL)) {
            return Double.NaN;
        }
        return Double.longBitsToDouble(prefs.getLong(KEY_LAST_TOTAL, 0L));
    }

    /** 设置页汇总。 */
    public static String summaryText(Context context, String currency) {
        String sym = symbol(currency);
        Stats stats = stats(context);
        StringBuilder sb = new StringBuilder();
        sb.append("今日已用 ").append(sym).append(money(stats.todaySpent))
                .append("（").append(stats.todayCount).append(" 笔）");
        if (stats.todayTopUp > 0) {
            sb.append("\n今日充值 ").append(sym).append(money(stats.todayTopUp));
        }
        sb.append("\n累计已用 ").append(sym).append(money(stats.totalSpent));
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
    // ── 导入 / 导出 ────────────────────────────────────────────────────

    /** 导出成 JSON 文本（便于备份、迁移、自己分析）。 */
    public static String exportJson(Context context) {
        JSONObject root = new JSONObject();
        try {
            root.put("app", "whale-balance-pet");
            root.put("format", 1);
            root.put("exportedAt", System.currentTimeMillis());
            JSONArray array = new JSONArray();
            for (Entry e : load(context)) {
                JSONObject o = new JSONObject();
                o.put("ts", e.ts);
                o.put("delta", e.delta);
                o.put("balance", e.balance);
                o.put("currency", e.currency);
                o.put("kind", e.kind);
                array.put(o);
            }
            root.put("entries", array);
        } catch (Exception e) {
            return "{}";
        }
        return root.toString();
    }

    /**
     * 导入 JSON。merge=true 时与现有账目合并（同一条自动去重），false 时覆盖。
     * @return 新导入的条数；文件格式不对返回 -1。
     */
    public static int importJson(Context context, String json, boolean merge) {
        List<Entry> incoming = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray array = root.optJSONArray("entries");
            if (array == null) {
                return -1;
            }
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                Entry e = new Entry();
                e.ts = o.optLong("ts");
                e.delta = o.optDouble("delta", 0);
                e.balance = o.optDouble("balance", 0);
                e.currency = o.optString("currency", "CNY");
                e.kind = o.optString("kind", KIND_SPEND);
                if (e.ts > 0) {
                    incoming.add(e);
                }
            }
        } catch (Exception e) {
            return -1;
        }

        List<Entry> target = merge ? load(context) : new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Entry e : target) {
            seen.add(fingerprint(e));
        }
        int added = 0;
        for (Entry e : incoming) {
            if (seen.add(fingerprint(e))) {
                target.add(e);
                added++;
            }
        }
        Collections.sort(target, (a, b) -> Long.compare(b.ts, a.ts));
        while (target.size() > MAX_ENTRIES) {
            target.remove(target.size() - 1);
        }
        save(context, target);
        return added;
    }

    private static String fingerprint(Entry e) {
        return e.ts + "|" + Math.round(e.delta * 100) + "|"
                + Math.round(e.balance * 100) + "|" + e.kind;
    }
}
