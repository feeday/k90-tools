package com.ppt.k90monitor;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView status;
    private TextView ftpStatus;
    private EditText ftpPort;
    private EditText ftpUser;
    private EditText ftpPass;
    private RedMagicBridgeReader bridge;
    private SharedPreferences prefs;

    private static final String KEY_FTP_PORT = "ftp_port";
    private static final String KEY_FTP_USER = "ftp_user";
    private static final String KEY_FTP_PASS = "ftp_pass";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bridge = new RedMagicBridgeReader(this);
        prefs = getSharedPreferences(OverlayMonitorService.PREFS, MODE_PRIVATE);
        setContentView(buildUi());
        requestNotificationPermissionIfNeeded();
        updateStatus();
        updateFtpStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        updateStatus();
        updateFtpStatus();
    }

    private View buildUi() {
        int pad = dp(20);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.WHITE);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("K90-Tools");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(20, 20, 20));
        title.setPadding(0, dp(8), 0, dp(8));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView desc = new TextView(this);
        desc.setText("温度模式：单行显示 CPU / GPU / BAT / 背夹温度，无数据项自动隐藏。\n\n全部模式：显示 CPU、GPU 温度、电池温度、RAM、实时网速和红魔扩展数据，并可直接在悬浮窗开关 FTP。\n\n内置 CPU 压力测试，可选 3 / 5 / 15 / 30 / 60 分钟，结束后显示平均 / 最高 / 最低频率、平均占用和最高温度。\n\n新增 Puck Chat AI 工具箱，页面资源直接打包在 APK 内，无需自建网站或 Node.js 服务。");
        desc.setTextSize(15);
        desc.setTextColor(Color.DKGRAY);
        desc.setLineSpacing(0, 1.2f);
        desc.setPadding(0, 0, 0, dp(18));
        root.addView(desc, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(Color.rgb(20, 20, 20));
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        Button overlay = makeButton("1. 授权悬浮窗");
        overlay.setOnClickListener(v -> requestOverlayPermission());
        root.addView(overlay, buttonLp());

        root.addView(sectionTitle("2. 选择显示模式"), new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button tempMode = makeButton("温度模式");
        tempMode.setOnClickListener(v -> setMode(OverlayMonitorService.MODE_TEMP));
        row.addView(tempMode, weightedButton());
        Button fullMode = makeButton("全部模式");
        fullMode.setOnClickListener(v -> setMode(OverlayMonitorService.MODE_FULL));
        row.addView(fullMode, weightedButton());
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));

        Button start = makeButton("3. 开始悬浮监控");
        start.setOnClickListener(v -> startMonitor());
        root.addView(start, buttonLp());

        Button aiTools = makeButton("打开 Puck Chat AI 工具箱");
        aiTools.setOnClickListener(v -> startActivity(new Intent(this, PuckChatActivity.class)));
        root.addView(aiTools, buttonLp());

        Button stop = makeButton("停止悬浮监控");
        stop.setOnClickListener(v -> stopService(new Intent(this, OverlayMonitorService.class)));
        root.addView(stop, buttonLp());

        root.addView(sectionTitle("CPU 压力测试"), new LinearLayout.LayoutParams(-1, -2));
        Button stress = makeButton("进入 CPU 压力测试");
        stress.setOnClickListener(v -> startActivity(new Intent(this, StressTestActivity.class)));
        root.addView(stress, buttonLp());

        TextView stressNote = new TextView(this);
        stressNote.setText("支持 3 / 5 / 15 / 30 / 60 分钟 CPU 满载。测试结束显示平均、最高、最低频率，以及 CPU 平均占用和最高温度。");
        stressNote.setTextSize(13);
        stressNote.setTextColor(Color.GRAY);
        stressNote.setPadding(0, dp(8), 0, dp(10));
        root.addView(stressNote, new LinearLayout.LayoutParams(-1, -2));

        root.addView(sectionTitle("红魔散热器（可选）"), new LinearLayout.LayoutParams(-1, -2));
        Button refresh = makeButton("刷新红魔数据状态");
        refresh.setOnClickListener(v -> updateStatus());
        root.addView(refresh, buttonLp());

        Button copyStart = makeButton("可选：复制 AYA 桥接启动命令");
        copyStart.setOnClickListener(v -> copy(bridge.shellStartCommand(), "桥接启动命令已复制"));
        root.addView(copyStart, buttonLp());

        root.addView(sectionTitle("FTP 文件传输"), new LinearLayout.LayoutParams(-1, -2));
        ftpStatus = new TextView(this);
        ftpStatus.setTextSize(14);
        ftpStatus.setTextColor(Color.DKGRAY);
        ftpStatus.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(ftpStatus, new LinearLayout.LayoutParams(-1, -2));

        ftpPort = makeInput("端口，例如 2121");
        ftpPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        ftpPort.setText(String.valueOf(prefs.getInt(KEY_FTP_PORT, 2121)));
        root.addView(ftpPort, inputLp());

        ftpUser = makeInput("用户名");
        ftpUser.setText(prefs.getString(KEY_FTP_USER, "k90"));
        root.addView(ftpUser, inputLp());

        ftpPass = makeInput("密码");
        ftpPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ftpPass.setText(prefs.getString(KEY_FTP_PASS, "123456"));
        root.addView(ftpPass, inputLp());

        Button storage = makeButton("授权访问手机全部文件");
        storage.setOnClickListener(v -> requestAllFilesAccess());
        root.addView(storage, buttonLp());

        LinearLayout ftpButtons = new LinearLayout(this);
        ftpButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button ftpStart = makeButton("启动 FTP");
        ftpStart.setOnClickListener(v -> startFtp());
        ftpButtons.addView(ftpStart, weightedButton());
        Button ftpStop = makeButton("停止 FTP");
        ftpStop.setOnClickListener(v -> stopFtp());
        ftpButtons.addView(ftpStop, weightedButton());
        root.addView(ftpButtons, new LinearLayout.LayoutParams(-1, -2));

        Button copyFtp = makeButton("复制 FTP 地址");
        copyFtp.setOnClickListener(v -> {
            int port = FtpServerService.isRunning() ? FtpServerService.getRunningPort() : readPort();
            copy("ftp://" + FtpServerService.getLanIp() + ":" + port, "FTP 地址已复制");
        });
        root.addView(copyFtp, buttonLp());

        TextView note = new TextView(this);
        note.setText("全部模式悬浮窗底部可直接点 FTP:开/关；温度模式继续保持纯一行极简显示。长按悬浮窗可关闭监控。");
        note.setTextSize(13);
        note.setTextColor(Color.GRAY);
        note.setPadding(0, dp(16), 0, dp(16));
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));
        return scroll;
    }

    private TextView sectionTitle(String value) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(18);
        t.setTextColor(Color.BLACK);
        t.setPadding(0, dp(22), 0, dp(5));
        return t;
    }

    private EditText makeInput(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(16);
        e.setSingleLine(true);
        return e;
    }

    private LinearLayout.LayoutParams inputLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(54));
        lp.topMargin = dp(6);
        return lp;
    }

    private void setMode(String mode) {
        prefs.edit().putString(OverlayMonitorService.KEY_MODE, mode).apply();
        Toast.makeText(this, OverlayMonitorService.MODE_TEMP.equals(mode) ? "已切换：温度模式" : "已切换：全部模式", Toast.LENGTH_SHORT).show();
        updateStatus();
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(16);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(54));
        lp.topMargin = dp(10);
        return lp;
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(54), 1f);
        lp.setMargins(dp(2), dp(5), dp(2), dp(5));
        return lp;
    }

    private void copy(String value, String toast) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("command", value));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    private void requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "悬浮窗权限已开启", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
    }

    private void requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
            Toast.makeText(this, "已具备文件访问权限", Toast.LENGTH_SHORT).show();
            updateFtpStatus();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Throwable e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    private void startMonitor() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授权悬浮窗权限", Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }
        Intent i = new Intent(this, OverlayMonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        Toast.makeText(this, "K90 悬浮监控已启动", Toast.LENGTH_SHORT).show();
    }

    private int readPort() {
        int port = 2121;
        try { port = Integer.parseInt(ftpPort.getText().toString().trim()); } catch (Throwable ignored) { }
        if (port < 1024 || port > 65535) port = 2121;
        return port;
    }

    private void startFtp() {
        int port = readPort();
        String user = ftpUser.getText().toString().trim();
        String pass = ftpPass.getText().toString();
        if (user.isEmpty()) user = "k90";
        if (pass.isEmpty()) pass = "123456";

        prefs.edit().putInt(KEY_FTP_PORT, port).putString(KEY_FTP_USER, user).putString(KEY_FTP_PASS, pass).apply();
        Intent i = new Intent(this, FtpServerService.class);
        i.putExtra(FtpServerService.EXTRA_PORT, port);
        i.putExtra(FtpServerService.EXTRA_USER, user);
        i.putExtra(FtpServerService.EXTRA_PASS, pass);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        Toast.makeText(this, "FTP 正在启动", Toast.LENGTH_SHORT).show();
        ftpStatus.postDelayed(this::updateFtpStatus, 500);
    }

    private void stopFtp() {
        Intent i = new Intent(this, FtpServerService.class).setAction(FtpServerService.ACTION_STOP);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        Toast.makeText(this, "FTP 已停止", Toast.LENGTH_SHORT).show();
        ftpStatus.postDelayed(this::updateFtpStatus, 300);
    }

    private void updateFtpStatus() {
        if (ftpStatus == null) return;
        boolean allFiles = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
        if (FtpServerService.isRunning()) {
            ftpStatus.setText("状态：运行中 ✓\n地址：ftp://" + FtpServerService.getLanIp() + ":" + FtpServerService.getRunningPort() +
                    "\n共享目录：" + FtpServerService.getRunningRoot() +
                    "\n全部文件权限：" + (allFiles ? "已授权 ✓" : "未授权"));
        } else {
            ftpStatus.setText("状态：未启动\n本机 IP：" + FtpServerService.getLanIp() +
                    "\n全部文件权限：" + (allFiles ? "已授权 ✓" : "未授权（将只共享应用 ftp 文件夹）"));
        }
    }

    private void updateStatus() {
        if (status == null || bridge == null) return;
        RedMagicBridgeReader.State s = bridge.read();
        String rmState = (!s.fileExists || s.stale || s.fanRpm < 0) ? "无实时数据" : "实时数据 ✓";
        String mode = prefs.getString(OverlayMonitorService.KEY_MODE, OverlayMonitorService.MODE_FULL);
        status.setText("悬浮窗权限：" + (Settings.canDrawOverlays(this) ? "已开启 ✓" : "未开启 ✗") +
                "\n显示模式：" + (OverlayMonitorService.MODE_TEMP.equals(mode) ? "温度模式" : "全部模式") +
                "\nCPU 压力测试：已集成 ✓" +
                "\nFTP：" + (FtpServerService.isRunning() ? "运行中 ✓" : "未启动") +
                "\n红魔扩展：" + rmState);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
