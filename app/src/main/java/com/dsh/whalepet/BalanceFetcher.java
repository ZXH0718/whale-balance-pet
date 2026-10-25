package com.dsh.whalepet;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 调 DeepSeek 官方文档化接口 GET /user/balance 查询余额。
 * 只用 GET + Bearer Token，不涉及任何第三方服务。
 */
public final class BalanceFetcher {

    public interface Callback {
        /**
         * @param ok       是否成功
         * @param text     给气泡显示的文本（如 ¥12.34）
         * @param total    数值总余额，失败为 NaN
         * @param currency 币种（CNY / USD ...）
         */
        void onResult(boolean ok, String text, double total, String currency);
    }

    private static final String ENDPOINT = "https://api.deepseek.com/user/balance";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BalanceFetcher() {
    }

    public static void fetch(final String apiKey, final Callback callback) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            callback.onResult(false, "未填 Key", Double.NaN, "");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok;
                String text;
                double total = Double.NaN;
                String currency = "";
                try {
                    Result result = request(apiKey.trim());
                    text = result.text;
                    total = result.total;
                    currency = result.currency;
                    ok = true;
                } catch (Exception e) {
                    ok = false;
                    text = "查询失败 " + shortMessage(e);
                }
                final boolean fok = ok;
                final String ftext = text;
                final double ftotal = total;
                final String fcur = currency;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onResult(fok, ftext, ftotal, fcur);
                    }
                });
            }
        }, "balance-fetch").start();
    }

    private static class Result {
        String text;
        double total;
        String currency;
    }

    private static Result request(String key) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder body = new StringBuilder();
            if (stream != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
                reader.close();
            }
            if (code == 401) {
                throw new IllegalStateException("Key 无效 401");
            }
            if (code == 402) {
                throw new IllegalStateException("余额不足 402");
            }
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }
            return format(body.toString());
        } finally {
            conn.disconnect();
        }
    }

    private static Result format(String json) throws Exception {
        Result result = new Result();
        JSONObject root = new JSONObject(json);
        JSONArray infos = root.optJSONArray("balance_infos");
        if (infos == null || infos.length() == 0) {
            result.text = root.optBoolean("is_available", false) ? "余额 --" : "账户不可用";
            result.total = Double.NaN;
            result.currency = "";
            return result;
        }
        StringBuilder out = new StringBuilder();
        double sum = 0;
        boolean hasNumber = false;
        String primaryCurrency = "";
        for (int i = 0; i < infos.length(); i++) {
            JSONObject info = infos.getJSONObject(i);
            String currency = info.optString("currency", "");
            String total = info.optString("total_balance", "0");
            if (out.length() > 0) {
                out.append(" / ");
            }
            out.append(Ledger.symbol(currency)).append(total);
            try {
                sum += Double.parseDouble(total);
                hasNumber = true;
            } catch (NumberFormatException ignored) {
                // 非数字就不参与记账
            }
            if (primaryCurrency.isEmpty()) {
                primaryCurrency = currency;
            }
        }
        result.text = out.toString();
        result.total = hasNumber ? sum : Double.NaN;
        result.currency = primaryCurrency;
        return result;
    }

    private static String shortMessage(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            message = e.getClass().getSimpleName();
        }
        return message.length() > 40 ? message.substring(0, 40) : message;
    }
}
