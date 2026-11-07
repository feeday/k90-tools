package com.ppt.k90monitor;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StressTestActivity extends Activity {
    private static final float CPU_TEMP_LIMIT_C = 105f;
    private static final float BATTERY_TEMP_LIMIT_C = 55f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable sampleTask = this::sampleAndRefresh;

    private MetricReader metricReader;
    private TextView durationLabel;
    private TextView statusText;
    private TextView resultText;

    private int selectedMinutes = 5;
    private volatile boolean running;
    private long startedAtMs;
    private long targetEndMs;
    private ExecutorService cpuPool;

    private final FrequencyStats cpuFreqStats = new FrequencyStats();
    private final ValueStats cpuUsageStats = new ValueStats();
    private final ValueStats cpuTempStats = new ValueStats();
    private final ValueStats batteryTempStats = new ValueStats();

    private static volatile long cpuBlackHole;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        metricReader = new MetricReader(this);
        setContentView(buildUi());
        updateDurationLabel();
        updateIdleStatus();
    }

    @Override protected void onPause() {
        if (running) finishTest("页面离开，测试已停止");
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopCpuWorkers();
        handler.removeCallbacks(sampleTask);
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.WHITE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("CPU 压力测试");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(20, 20, 20));
        title.setPadding(0, dp(4), 0, dp(8));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView desc = new TextView(this);
        desc.setText("多线程持续压满可用 CPU 核心，每秒读取在线核心实时频率。\n测试结束显示平均 / 最高 / 最低频率、平均占用和最高温度。\n\n温度保护：CPU ≥ 105°C 或电池 ≥ 55°C 时自动停止。");
        desc.setTextSize(14);
        desc.setTextColor(Color.DKGRAY);
        desc.setLineSpacing(0, 1.15f);
        desc.setPadding(0, 0, 0, dp(12));
        root.addView(desc, new LinearLayout.LayoutParams(-1, -2));

        root.addView(sectionTitle("1. 选择测试时长"), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout durationRow = new LinearLayout(this);
        durationRow.setOrientation(LinearLayout.HORIZONTAL);
        int[] minutes = {3, 5, 15, 30, 60};
        String[] labels = {"3分", "5分", "15分", "30分", "1小时"};
        for (int i = 0; i < minutes.length; i++) {
            final int value = minutes[i];
            Button b = makeButton(labels[i], 13);
            b.setOnClickListener(v -> {
                if (running) {
                    Toast.makeText(this, "请先停止当前测试", Toast.LENGTH_SHORT).show();
                    return;
                }
                selectedMinutes = value;
                updateDurationLabel();
            });
            durationRow.addView(b, weightedButtonLp());
        }
        root.addView(durationRow, new LinearLayout.LayoutParams(-1, -2));

        durationLabel = new TextView(this);
        durationLabel.setTextSize(15);
        durationLabel.setTextColor(Color.BLACK);
        durationLabel.setPadding(dp(4), dp(7), dp(4), dp(8));
        root.addView(durationLabel, new LinearLayout.LayoutParams(-1, -2));

        root.addView(sectionTitle("2. CPU 满载"), new LinearLayout.LayoutParams(-1, -2));

        Button start = makeButton("开始 CPU 压力测试", 16);
        start.setOnClickListener(v -> startTest());
        root.addView(start, buttonLp());

        Button stop = makeButton("停止测试", 16);
        stop.setOnClickListener(v -> {
            if (running) finishTest("手动停止");
            else Toast.makeText(this, "当前没有正在运行的测试", Toast.LENGTH_SHORT).show();
        });
        root.addView(stop, buttonLp());

        root.addView(sectionTitle("3. 实时状态"), new LinearLayout.LayoutParams(-1, -2));

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setTextColor(Color.rgb(25, 25, 25));
        statusText.setPadding(dp(12), dp(12), dp(12), dp(12));
        statusText.setBackgroundColor(Color.rgb(242, 242, 242));
        root.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        root.addView(sectionTitle("4. 测试结果"), new LinearLayout.LayoutParams(-1, -2));

        resultText = new TextView(this);
        resultText.setText("完成一次测试后，这里会显示统计结果。\n重点：CPU 平均频率 / 最高频率 / 最低频率。");
        resultText.setTextSize(15);
        resultText.setTextColor(Color.rgb(20, 20, 20));
        resultText.setPadding(dp(12), dp(12), dp(12), dp(12));
        resultText.setBackgroundColor(Color.rgb(236, 244, 255));
        root.addView(resultText, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(this);
        note.setText("说明：频率约每秒采样一次。系统限制读取时会回退到现有监控可读频率。压力测试会明显增加功耗和发热。");
        note.setTextSize(13);
        note.setTextColor(Color.GRAY);
        note.setPadding(0, dp(18), 0, dp(10));
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        return scroll;
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setTextColor(Color.BLACK);
        t.setPadding(0, dp(14), 0, dp(5));
        return t;
    }

    private Button makeButton(String text, int size) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(size);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams weightedButtonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        lp.setMargins(dp(1), dp(4), dp(1), dp(4));
        return lp;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(54));
        lp.topMargin = dp(8);
        return lp;
    }

    private void updateDurationLabel() {
        if (durationLabel != null) {
            durationLabel.setText("当前时长：" + (selectedMinutes == 60 ? "1 小时" : selectedMinutes + " 分钟"));
        }
    }

    private void updateIdleStatus() {
        if (statusText != null) {
            statusText.setText("状态：未运行\n请选择 3 / 5 / 15 / 30 / 60 分钟后开始测试。");
        }
    }

    private void startTest() {
        if (running) {
            Toast.makeText(this, "CPU 压力测试正在运行", Toast.LENGTH_SHORT).show();
            return;
        }

        resetStats();
        running = true;
        startedAtMs = SystemClock.elapsedRealtime();
        targetEndMs = startedAtMs + selectedMinutes * 60_000L;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startCpuWorkers();

        resultText.setText("测试进行中……结束后自动生成统计结果。");
        Toast.makeText(this, "CPU 压力测试已开始", Toast.LENGTH_SHORT).show();
        handler.removeCallbacks(sampleTask);
        handler.postDelayed(sampleTask, 700);
    }

    private void startCpuWorkers() {
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        cpuPool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final int worker = i;
            cpuPool.submit(() -> runCpuStress(worker));
        }
    }

    private void runCpuStress(int worker) {
        try { Thread.currentThread().setPriority(Thread.MAX_PRIORITY); } catch (Throwable ignored) { }
        long x = 0x9E3779B97F4A7C15L ^ (worker * 0xBF58476D1CE4E5B9L);
        double d = 1.000001 + worker * 0.00001;
        while (running && !Thread.currentThread().isInterrupted()) {
            for (int i = 0; i < 60_000; i++) {
                x ^= x << 13;
                x ^= x >>> 7;
                x ^= x << 17;
                d = Math.sqrt(Math.abs(d * 1.000000119 + (x & 0xFFFF) + 1.0));
                d += Math.sin(d + (x & 255) * 0.001);
                if (d > 1_000_000.0) d *= 0.000001;
            }
            cpuBlackHole = x ^ Double.doubleToRawLongBits(d);
        }
    }

    private void stopCpuWorkers() {
        ExecutorService pool = cpuPool;
        cpuPool = null;
        if (pool != null) pool.shutdownNow();
    }

    private void sampleAndRefresh() {
        if (!running) return;

        MetricReader.Snapshot snapshot = metricReader.read();
        FrequencySample cpuSample = readCpuFrequencySample();
        if (!cpuSample.valid() && valid(snapshot.cpuFreqMHz)) {
            cpuSample = new FrequencySample(snapshot.cpuFreqMHz, snapshot.cpuFreqMHz, snapshot.cpuFreqMHz, 1);
        }

        if (cpuSample.valid()) cpuFreqStats.add(cpuSample);
        if (valid(snapshot.cpuUsage)) cpuUsageStats.add(snapshot.cpuUsage);
        if (valid(snapshot.cpuTempC)) cpuTempStats.add(snapshot.cpuTempC);
        if (valid(snapshot.batteryTempC)) batteryTempStats.add(snapshot.batteryTempC);

        long now = SystemClock.elapsedRealtime();
        long remainingMs = Math.max(0, targetEndMs - now);

        StringBuilder sb = new StringBuilder();
        sb.append("状态：运行中\n");
        sb.append("剩余：").append(formatDuration(remainingMs)).append(" / 设定 ")
                .append(selectedMinutes == 60 ? "1小时" : selectedMinutes + "分钟").append("\n");
        sb.append("CPU 占用：").append(formatPercent(snapshot.cpuUsage)).append("\n");
        sb.append("当前平均频率：").append(cpuSample.valid() ? formatMHz(cpuSample.averageMHz) : "不可读").append("\n");
        sb.append("CPU 温度：").append(formatTemp(snapshot.cpuTempC))
                .append("   BAT：").append(formatTemp(snapshot.batteryTempC)).append("\n");
        sb.append("频率样本：").append(cpuFreqStats.count).append(" 次");
        statusText.setText(sb.toString());

        boolean cpuTooHot = valid(snapshot.cpuTempC) && snapshot.cpuTempC >= CPU_TEMP_LIMIT_C;
        boolean batteryTooHot = valid(snapshot.batteryTempC) && snapshot.batteryTempC >= BATTERY_TEMP_LIMIT_C;
        if (cpuTooHot || batteryTooHot) {
            finishTest("温度保护自动停止");
            return;
        }

        if (now >= targetEndMs) {
            finishTest("测试完成");
            return;
        }
        handler.postDelayed(sampleTask, 1000);
    }

    private void finishTest(String reason) {
        if (!running) return;
        running = false;
        handler.removeCallbacks(sampleTask);
        stopCpuWorkers();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        long elapsed = Math.max(0, SystemClock.elapsedRealtime() - startedAtMs);

        StringBuilder result = new StringBuilder();
        result.append(reason).append("\n");
        result.append("实际时长：").append(formatDuration(elapsed)).append("\n\n");
        result.append("CPU 频率\n");
        if (cpuFreqStats.count > 0) {
            result.append("平均：").append(formatMHz((float) (cpuFreqStats.sumAverageMHz / cpuFreqStats.count))).append("\n");
            result.append("最高：").append(formatMHz(cpuFreqStats.maxMHz)).append("\n");
            result.append("最低：").append(formatMHz(cpuFreqStats.minMHz)).append("\n");
            result.append("样本：").append(cpuFreqStats.count).append(" 次\n");
        } else {
            result.append("系统未允许读取 CPU 实时频率。\n");
        }
        result.append("\nCPU 平均占用：").append(cpuUsageStats.count > 0 ? formatPercent((float) cpuUsageStats.average()) : "不可读");
        result.append("\nCPU 最高温度：").append(cpuTempStats.count > 0 ? formatTemp(cpuTempStats.max) : "不可读");
        result.append("\nBAT 最高温度：").append(batteryTempStats.count > 0 ? formatTemp(batteryTempStats.max) : "不可读");

        resultText.setText(result.toString());
        statusText.setText("状态：" + reason + "\nCPU 压力负载已停止。");
        Toast.makeText(this, reason, Toast.LENGTH_SHORT).show();
    }

    private void resetStats() {
        cpuFreqStats.reset();
        cpuUsageStats.reset();
        cpuTempStats.reset();
        batteryTempStats.reset();
    }

    private FrequencySample readCpuFrequencySample() {
        File root = new File("/sys/devices/system/cpu");
        File[] cpus = root.listFiles((dir, name) -> name.matches("cpu\\d+"));
        if (cpus == null || cpus.length == 0) return FrequencySample.invalid();

        double sum = 0;
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        int count = 0;
        for (File cpu : cpus) {
            File onlineFile = new File(cpu, "online");
            if (onlineFile.exists() && readLong(onlineFile) == 0) continue;

            long raw = readLong(new File(cpu, "cpufreq/scaling_cur_freq"));
            if (raw <= 0) raw = readLong(new File(cpu, "cpufreq/cpuinfo_cur_freq"));
            float mhz = normalizeCpuFrequencyMHz(raw);
            if (!valid(mhz) || mhz <= 0) continue;

            sum += mhz;
            min = Math.min(min, mhz);
            max = Math.max(max, mhz);
            count++;
        }
        if (count == 0) return FrequencySample.invalid();
        return new FrequencySample((float) (sum / count), min, max, count);
    }

    private static float normalizeCpuFrequencyMHz(long raw) {
        if (raw <= 0) return Float.NaN;
        if (raw >= 10_000_000L) return raw / 1_000_000f;
        if (raw >= 10_000L) return raw / 1000f;
        return raw;
    }

    private static long readLong(File file) {
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String s = br.readLine();
            if (s == null) return Long.MIN_VALUE;
            return Long.parseLong(s.trim().split("\\s+")[0]);
        } catch (Throwable ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static boolean valid(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static String formatMHz(float mhz) {
        if (!valid(mhz)) return "不可读";
        if (mhz >= 1000f) return String.format(Locale.CHINA, "%.0f MHz (%.2f GHz)", mhz, mhz / 1000f);
        return String.format(Locale.CHINA, "%.0f MHz", mhz);
    }

    private static String formatPercent(float value) {
        return valid(value) ? String.format(Locale.CHINA, "%.1f%%", value) : "不可读";
    }

    private static String formatTemp(float value) {
        return valid(value) ? String.format(Locale.CHINA, "%.1f°C", value) : "不可读";
    }

    private static String formatDuration(long ms) {
        long totalSeconds = Math.max(0, ms / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) return String.format(Locale.CHINA, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.CHINA, "%02d:%02d", minutes, seconds);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static class FrequencySample {
        final float averageMHz;
        final float minMHz;
        final float maxMHz;
        final int cores;

        FrequencySample(float averageMHz, float minMHz, float maxMHz, int cores) {
            this.averageMHz = averageMHz;
            this.minMHz = minMHz;
            this.maxMHz = maxMHz;
            this.cores = cores;
        }

        static FrequencySample invalid() {
            return new FrequencySample(Float.NaN, Float.NaN, Float.NaN, 0);
        }

        boolean valid() {
            return cores > 0 && StressTestActivity.valid(averageMHz);
        }
    }

    private static class FrequencyStats {
        double sumAverageMHz;
        float minMHz = Float.POSITIVE_INFINITY;
        float maxMHz = Float.NEGATIVE_INFINITY;
        int count;

        void add(FrequencySample sample) {
            if (!sample.valid()) return;
            sumAverageMHz += sample.averageMHz;
            minMHz = Math.min(minMHz, sample.minMHz);
            maxMHz = Math.max(maxMHz, sample.maxMHz);
            count++;
        }

        void reset() {
            sumAverageMHz = 0;
            minMHz = Float.POSITIVE_INFINITY;
            maxMHz = Float.NEGATIVE_INFINITY;
            count = 0;
        }
    }

    private static class ValueStats {
        double sum;
        float max = Float.NEGATIVE_INFINITY;
        int count;

        void add(float value) {
            if (!StressTestActivity.valid(value)) return;
            sum += value;
            max = Math.max(max, value);
            count++;
        }

        double average() {
            return count > 0 ? sum / count : Double.NaN;
        }

        void reset() {
            sum = 0;
            max = Float.NEGATIVE_INFINITY;
            count = 0;
        }
    }
}
