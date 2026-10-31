package com.dsh.whalepet;

import android.Manifest;
import android.net.Uri;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** 设置页：填 API Key、刷新间隔、气泡开关，启动 / 停止桌宠。 */
public class MainActivity extends Activity {

    private static final int REQ_EXPORT_LEDGER = 1001;
    private static final int REQ_IMPORT_LEDGER = 1002;

    private TextView status;
    private EditText keyInput;
    private EditText intervalInput;
    private Switch bubbleSwitch;
    private Switch soundSwitch;
    private Switch pinnedSwitch;
    private Switch snapSwitch;
    private Switch vibrateSwitch;
    private TextView ledgerSummary;
    private TextView ledgerDetail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.tv_status);
        keyInput = findViewById(R.id.et_key);
        intervalInput = findViewById(R.id.et_interval);
        bubbleSwitch = findViewById(R.id.sw_bubble);
        soundSwitch = findViewById(R.id.sw_sound);
        pinnedSwitch = findViewById(R.id.sw_pinned);
        snapSwitch = findViewById(R.id.sw_snap);
        vibrateSwitch = findViewById(R.id.sw_vibrate);
        ledgerSummary = findViewById(R.id.tv_ledger_summary);
        ledgerDetail = findViewById(R.id.tv_ledger_detail);

        keyInput.setText(Prefs.apiKey(this));
        intervalInput.setText(String.valueOf(Prefs.intervalSec(this)));
        bubbleSwitch.setChecked(Prefs.showBubble(this));
        soundSwitch.setChecked(Prefs.patSound(this));
        pinnedSwitch.setChecked(Prefs.bubblePinned(this));
        snapSwitch.setChecked(Prefs.snapEdge(this));
        vibrateSwitch.setChecked(Prefs.patVibrate(this));

        Button overlayButton = findViewById(R.id.btn_overlay);
        overlayButton.setOnClickListener(v -> requestOverlayPermission());

        Button startButton = findViewById(R.id.btn_start);
        startButton.setOnClickListener(v -> {
            save();
            startPet();
        });

        Button stopButton = findViewById(R.id.btn_stop);
        stopButton.setOnClickListener(v -> stopPet());

        Button checkButton = findViewById(R.id.btn_check);
        checkButton.setOnClickListener(v -> checkOnce());

        Button exportButton = findViewById(R.id.btn_export_ledger);
        exportButton.setOnClickListener(v -> startExportLedger());

        Button importButton = findViewById(R.id.btn_import_ledger);
        importButton.setOnClickListener(v -> startImportLedger());

        Button clearButton = findViewById(R.id.btn_clear_ledger);
        clearButton.setOnClickListener(v -> {
            Ledger.clear(this);
            refreshLedger();
            Toast.makeText(this, "记账已清空", Toast.LENGTH_SHORT).show();
        });

        // Android 13+ 通知权限：不授予也能跑，只是前台服务通知不可见
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        refreshLedger();
    }

    private void updateStatus() {
        boolean overlay = canOverlay();
        String last = Prefs.lastBalance(this);
        String text = (overlay ? "悬浮窗权限：已授予" : "悬浮窗权限：未授予（必须先授权才能显示桌宠）")
                + "\n上次余额：" + (last == null || last.isEmpty() ? "—" : last)
                + "\n累计摸头：" + Prefs.patCount(this) + " 次";
        status.setText(text);
    }

    private void startExportLedger() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE,
                "whale-ledger-" + new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date()) + ".json");
        try {
            startActivityForResult(intent, REQ_EXPORT_LEDGER);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_LONG).show();
        }
    }

    private void startImportLedger() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_IMPORT_LEDGER);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT_LEDGER) {
            doExportLedger(uri);
        } else if (requestCode == REQ_IMPORT_LEDGER) {
            doImportLedger(uri);
        }
    }

    private void doExportLedger(Uri uri) {
        OutputStream out = null;
        try {
            out = getContentResolver().openOutputStream(uri);
            if (out == null) {
                throw new IllegalStateException("无法写入该位置");
            }
            out.write(Ledger.exportJson(this).getBytes(StandardCharsets.UTF_8));
            out.flush();
            Toast.makeText(this, "账单已导出", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        }
    }

    private void doImportLedger(Uri uri) {
        InputStream in = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) {
                throw new IllegalStateException("无法读取该文件");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            int added = Ledger.importJson(this,
                    new String(buffer.toByteArray(), StandardCharsets.UTF_8), true);
            if (added < 0) {
                Toast.makeText(this, "不是有效的账单文件", Toast.LENGTH_LONG).show();
                return;
            }
            refreshLedger();
            Toast.makeText(this, "已导入 " + added + " 条（重复的自动跳过）", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        }
    }

    private void refreshLedger() {
        if (ledgerSummary == null || ledgerDetail == null) {
            return;
        }
        String currency = Ledger.lastCurrency(this);
        ledgerSummary.setText(Ledger.summaryText(this, currency));
        ledgerDetail.setText(Ledger.detailText(this, 12));
    }

    private boolean canOverlay() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "当前系统版本无需授权", Toast.LENGTH_SHORT).show();
            return;
        }
        if (canOverlay()) {
            Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void save() {
        Prefs.setApiKey(this, keyInput.getText().toString());
        Prefs.setShowBubble(this, bubbleSwitch.isChecked());
        Prefs.setPatSound(this, soundSwitch.isChecked());
        Prefs.setBubblePinned(this, pinnedSwitch.isChecked());
        Prefs.setSnapEdge(this, snapSwitch.isChecked());
        Prefs.setPatVibrate(this, vibrateSwitch.isChecked());
        int interval = 60;
        try {
            interval = Integer.parseInt(intervalInput.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            // 用默认值
        }
        Prefs.setIntervalSec(this, interval);
    }

    private void startPet() {
        if (!canOverlay()) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }
        if (Prefs.apiKey(this).trim().isEmpty()) {
            Toast.makeText(this, "请先填写 DeepSeek API Key", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(this, BalanceService.class);
        intent.setAction(BalanceService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "桌宠已启动", Toast.LENGTH_SHORT).show();
        updateStatus();
    }

    private void stopPet() {
        Intent intent = new Intent(this, BalanceService.class);
        intent.setAction(BalanceService.ACTION_STOP);
        startService(intent);
        Toast.makeText(this, "桌宠已停止", Toast.LENGTH_SHORT).show();
    }

    private void checkOnce() {
        save();
        status.setText("查询中…");
        BalanceFetcher.fetch(Prefs.apiKey(this), (ok, text, total, currency) -> {
            Prefs.setLastBalance(this, text);
            if (ok) {
                Ledger.observe(this, total, currency);
            }
            status.setText((ok ? "当前余额：" : "查询结果：") + text);
            refreshLedger();
        });
    }
}
