package com.ppt.k90monitor;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only telemetry bridge for the official REDMAGIC app.
 *
 * This class does NOT connect to the cooler and does NOT send BLE commands.
 * The official REDMAGIC app owns the BLE/authenticated session. We only parse
 * the already-decoded values that cn.nubia.externdevice writes to logcat.
 *
 * Requires one-time ADB grant:
 * adb shell pm grant com.ppt.k90monitor android.permission.READ_LOGS
 */
public final class RedMagicLogReader {
    private static final String READ_LOGS = "android.permission.READ_LOGS";

    private static final Pattern TEMP_1 = Pattern.compile("onTemperature\\s+values=\\[(-?\\d+)\\]");
    private static final Pattern TEMP_2 = Pattern.compile("onTemp changed\\s+\\[(-?\\d+)\\]");
    private static final Pattern TEMP_3 = Pattern.compile("showTemperature=(-?\\d+)");
    private static final Pattern RPM_1 = Pattern.compile("onFanSpeed\\s+value=(\\d+)");
    private static final Pattern RPM_2 = Pattern.compile("fan speed:\\s*([0-9a-fA-F]{4})");
    private static final Pattern POWER_1 = Pattern.compile("onFanPower\\s+value=(\\d+(?:\\.\\d+)?)");
    private static final Pattern POWER_2 = Pattern.compile("fan power:\\s*([0-9a-fA-F]{2})");

    public static final class State {
        public float clampTempC = Float.NaN;
        public int fanRpm = -1;
        public float powerW = Float.NaN;
        public long tempAt;
        public long rpmAt;
        public long powerAt;
        public long lastDataAt;
        public boolean running;
        public String message = "未启动";
        public String lastLine = "--";

        State copy() {
            State s = new State();
            s.clampTempC = clampTempC;
            s.fanRpm = fanRpm;
            s.powerW = powerW;
            s.tempAt = tempAt;
            s.rpmAt = rpmAt;
            s.powerAt = powerAt;
            s.lastDataAt = lastDataAt;
            s.running = running;
            s.message = message;
            s.lastLine = lastLine;
            return s;
        }
    }

    private static RedMagicLogReader instance;

    public static synchronized RedMagicLogReader get(Context context) {
        if (instance == null) instance = new RedMagicLogReader(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Object lock = new Object();
    private final State state = new State();
    private volatile boolean stopRequested;
    private Process process;
    private Thread thread;

    private RedMagicLogReader(Context context) {
        this.context = context;
    }

    public boolean hasReadLogsPermission() {
        return context.checkSelfPermission(READ_LOGS) == PackageManager.PERMISSION_GRANTED;
    }

    public State getState() {
        synchronized (lock) { return state.copy(); }
    }

    public synchronized boolean start() {
        if (thread != null && thread.isAlive()) return true;
        if (!hasReadLogsPermission()) {
            synchronized (lock) {
                state.running = false;
                state.message = "需要 ADB READ_LOGS 授权";
            }
            return false;
        }

        stopRequested = false;
        thread = new Thread(this::readLoop, "RedMagicLogReader");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    public synchronized void stop() {
        stopRequested = true;
        try { if (process != null) process.destroy(); } catch (Throwable ignored) { }
        process = null;
        try { if (thread != null) thread.interrupt(); } catch (Throwable ignored) { }
        thread = null;
        synchronized (lock) {
            state.running = false;
            state.message = "已停止";
        }
    }

    private void readLoop() {
        synchronized (lock) {
            state.running = true;
            state.message = "等待红魔 App 数据…";
        }
        try {
            // -T 1 starts near the current tail, then keeps streaming new lines.
            ProcessBuilder pb = new ProcessBuilder(
                    "logcat", "-v", "brief", "-T", "1", "neoDevice:V", "*:S");
            pb.redirectErrorStream(true);
            process = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!stopRequested && (line = br.readLine()) != null) {
                    parseLine(line);
                }
            }
        } catch (Throwable e) {
            synchronized (lock) {
                state.message = "日志读取失败: " + e.getClass().getSimpleName();
            }
        } finally {
            synchronized (lock) { state.running = false; }
            try { if (process != null) process.destroy(); } catch (Throwable ignored) { }
            process = null;
        }
    }

    private void parseLine(String line) {
        if (line == null || line.isEmpty()) return;
        long now = System.currentTimeMillis();
        boolean hit = false;

        Float temp = firstFloat(line, TEMP_1, TEMP_2, TEMP_3);
        if (temp != null && temp >= -30f && temp <= 100f) {
            synchronized (lock) {
                state.clampTempC = temp;
                state.tempAt = now;
            }
            hit = true;
        }

        Integer rpm = firstInt(line, RPM_1);
        if (rpm == null) {
            String raw = firstGroup(line, RPM_2);
            if (raw != null) {
                try { rpm = Integer.parseInt(raw, 16); } catch (Throwable ignored) { }
            }
        }
        if (rpm != null && rpm >= 0 && rpm <= 20000) {
            synchronized (lock) {
                state.fanRpm = rpm;
                state.rpmAt = now;
            }
            hit = true;
        }

        Float power = firstFloat(line, POWER_1);
        if (power == null) {
            String raw = firstGroup(line, POWER_2);
            if (raw != null) {
                try { power = (float) Integer.parseInt(raw, 16); } catch (Throwable ignored) { }
            }
        }
        if (power != null && power >= 0f && power <= 100f) {
            synchronized (lock) {
                state.powerW = power;
                state.powerAt = now;
            }
            hit = true;
        }

        if (hit) {
            synchronized (lock) {
                state.lastDataAt = now;
                state.lastLine = line;
                state.message = "正在读取红魔 App";
            }
        }
    }

    public String compactStatus() {
        State s = getState();
        if (!hasReadLogsPermission()) return "未授权 READ_LOGS";
        long age = s.lastDataAt == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - s.lastDataAt;
        if (!s.running) return s.message;
        if (age > 10000) return "等待红魔 App 数据";
        return "红魔 App 数据读取中";
    }

    private static Float firstFloat(String line, Pattern... patterns) {
        for (Pattern p : patterns) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                try { return Float.parseFloat(m.group(1)); } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    private static Integer firstInt(String line, Pattern... patterns) {
        for (Pattern p : patterns) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    private static String firstGroup(String line, Pattern p) {
        Matcher m = p.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    public static String adbGrantCommand() {
        return "adb shell pm grant com.ppt.k90monitor android.permission.READ_LOGS";
    }

    public static String formatPower(float w) {
        if (Float.isNaN(w)) return "--";
        if (Math.abs(w - Math.round(w)) < 0.05f) return String.format(Locale.US, "%d W", Math.round(w));
        return String.format(Locale.US, "%.1f W", w);
    }
}
