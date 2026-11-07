package com.ppt.k90monitor;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MetricReader {
    private final Context context;

    private long lastCpuTotal = -1;
    private long lastCpuIdle = -1;
    private long lastCpuIdleStateUs = -1;
    private long lastCpuIdleWallUs = -1;
    private int lastCpuIdleCoreCount = -1;

    private long lastGpuBusy = -1;
    private long lastGpuTotal = -1;

    private static final Pattern FIRST_NUMBER = Pattern.compile("[-+]?[0-9]*\\.?[0-9]+");
    private static final Pattern TOP_IDLE = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)%\\s*(?:idle|idl)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOP_TOTAL = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)%\\s*cpu", Pattern.CASE_INSENSITIVE);

    public MetricReader(Context context) {
        this.context = context.getApplicationContext();
    }

    public Snapshot read() {
        Snapshot s = new Snapshot();
        s.cpuUsage = readCpuUsage();
        s.cpuFreqMHz = readCpuMaxCurrentFreqMHz();
        s.cpuTempC = readThermalTemp(new String[]{"cpu", "cpuss", "cpu-", "cpu_"});
        s.gpuUsage = readGpuUsage();
        s.gpuFreqMHz = readGpuFreqMHz();
        s.gpuTempC = readThermalTemp(new String[]{"gpu", "gpuss", "gpu-", "gpu_"});
        readMemory(s);
        s.batteryTempC = readBatteryTemp();
        return s;
    }

    // CPU usage fallback order:
    // 1) /proc/stat delta
    // 2) per-core cpuidle cumulative time delta
    // 3) Android toybox top summary
    private float readCpuUsage() {
        float v = readCpuUsageFromProcStat();
        if (!Float.isNaN(v)) return v;

        v = readCpuUsageFromCpuIdle();
        if (!Float.isNaN(v)) return v;

        return readCpuUsageFromTop();
    }

    private float readCpuUsageFromProcStat() {
        String line = readFirstLine("/proc/stat");
        if (line == null || !line.startsWith("cpu ")) return Float.NaN;
        try {
            String[] p = line.trim().split("\\s+");
            long user = parseLong(p, 1), nice = parseLong(p, 2), system = parseLong(p, 3);
            long idle = parseLong(p, 4), iowait = parseLong(p, 5), irq = parseLong(p, 6);
            long softirq = parseLong(p, 7), steal = parseLong(p, 8);
            long idleAll = idle + iowait;
            long total = user + nice + system + idle + iowait + irq + softirq + steal;
            float result = Float.NaN;
            if (lastCpuTotal >= 0 && total > lastCpuTotal) {
                long dt = total - lastCpuTotal;
                long di = idleAll - lastCpuIdle;
                if (dt > 0) result = clamp(100f * (dt - di) / (float) dt);
            }
            lastCpuTotal = total;
            lastCpuIdle = idleAll;
            return result;
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    private float readCpuUsageFromCpuIdle() {
        try {
            File root = new File("/sys/devices/system/cpu");
            File[] cpus = root.listFiles((dir, name) -> name.matches("cpu\\d+"));
            if (cpus == null) return Float.NaN;

            long idleUs = 0;
            int sampledCores = 0;
            for (File cpu : cpus) {
                long online = readLong(cpu.getAbsolutePath() + "/online");
                if (online == 0) continue;

                File cpuidle = new File(cpu, "cpuidle");
                File[] states = cpuidle.listFiles((dir, name) -> name.startsWith("state"));
                if (states == null || states.length == 0) continue;

                long coreIdleUs = 0;
                boolean got = false;
                for (File state : states) {
                    long t = readLong(state.getAbsolutePath() + "/time");
                    if (t >= 0) {
                        coreIdleUs += t;
                        got = true;
                    }
                }
                if (got) {
                    idleUs += coreIdleUs;
                    sampledCores++;
                }
            }

            if (sampledCores == 0) return Float.NaN;
            long wallUs = SystemClock.elapsedRealtimeNanos() / 1000L;
            float result = Float.NaN;

            if (lastCpuIdleStateUs >= 0 && lastCpuIdleWallUs >= 0 &&
                    lastCpuIdleCoreCount == sampledCores && wallUs > lastCpuIdleWallUs) {
                long idleDelta = idleUs - lastCpuIdleStateUs;
                long wallDelta = wallUs - lastCpuIdleWallUs;
                long capacity = wallDelta * sampledCores;
                if (idleDelta >= 0 && capacity > 0) {
                    result = clamp(100f * (1f - idleDelta / (float) capacity));
                }
            }

            lastCpuIdleStateUs = idleUs;
            lastCpuIdleWallUs = wallUs;
            lastCpuIdleCoreCount = sampledCores;
            return result;
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    private float readCpuUsageFromTop() {
        String[][] commands = {
                {"top", "-b", "-n", "1", "-m", "1"},
                {"top", "-n", "1", "-m", "1"}
        };
        for (String[] cmd : commands) {
            Process process = null;
            try {
                process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                boolean done = process.waitFor(900, TimeUnit.MILLISECONDS);
                if (!done) {
                    process.destroyForcibly();
                    continue;
                }
                try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String low = line.toLowerCase(Locale.US);
                        if (!low.contains("cpu") || (!low.contains("idle") && !low.contains("idl"))) continue;
                        Matcher idleMatcher = TOP_IDLE.matcher(low);
                        if (!idleMatcher.find()) continue;
                        float idle = Float.parseFloat(idleMatcher.group(1));
                        Matcher totalMatcher = TOP_TOTAL.matcher(low);
                        if (totalMatcher.find()) {
                            float total = Float.parseFloat(totalMatcher.group(1));
                            if (total > 0f) return clamp(100f * (total - idle) / total);
                        }
                        return clamp(100f - idle);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (process != null) {
                    try { process.destroy(); } catch (Throwable ignored) { }
                }
            }
        }
        return Float.NaN;
    }

    private float readCpuMaxCurrentFreqMHz() {
        File root = new File("/sys/devices/system/cpu");
        File[] dirs = root.listFiles((dir, name) -> name.matches("cpu\\d+"));
        if (dirs == null) return Float.NaN;
        long maxKhz = -1;
        for (File cpu : dirs) {
            long v = readLong(cpu.getAbsolutePath() + "/cpufreq/scaling_cur_freq");
            if (v <= 0) v = readLong(cpu.getAbsolutePath() + "/cpufreq/cpuinfo_cur_freq");
            if (v > maxKhz) maxKhz = v;
        }
        return maxKhz > 0 ? maxKhz / 1000f : Float.NaN;
    }

    private float readGpuUsage() {
        // Direct Qualcomm percentage nodes.
        String[] percentPaths = {
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
                "/sys/class/kgsl/kgsl-3d0/gpu_load",
                "/sys/class/kgsl/kgsl-3d0/devfreq/load",
                "/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpu_busy_percentage"
        };
        for (String path : percentPaths) {
            float v = readPercent(path);
            if (!Float.isNaN(v)) return v;
        }

        // Qualcomm gpubusy commonly exposes: busy total.
        float v = readGpuBusyPair("/sys/class/kgsl/kgsl-3d0/gpubusy");
        if (!Float.isNaN(v)) return v;
        v = readGpuBusyPair("/sys/class/kgsl/kgsl-3d0/devfreq/gpubusy");
        if (!Float.isNaN(v)) return v;

        // Discover renamed devfreq GPU nodes instead of assuming a SoC address.
        File root = new File("/sys/class/devfreq");
        File[] entries = root.listFiles();
        if (entries != null) {
            for (File e : entries) {
                if (!isGpuDevfreq(e)) continue;
                String base = e.getAbsolutePath();
                String[] names = {"gpu_load", "load", "busy_percent", "utilization", "gpu_busy_percentage"};
                for (String name : names) {
                    v = readPercent(base + "/" + name);
                    if (!Float.isNaN(v)) return v;
                }
                v = readGpuBusyTimes(base + "/busy_time", base + "/total_time");
                if (!Float.isNaN(v)) return v;
            }
        }
        return Float.NaN;
    }

    private float readGpuFreqMHz() {
        String[] paths = {
                "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
                "/sys/class/kgsl/kgsl-3d0/clock_mhz",
                "/sys/class/kgsl/kgsl-3d0/gpuclk",
                "/sys/class/kgsl/kgsl-3d0/devfreq/target_freq",
                "/sys/class/devfreq/3d00000.qcom,kgsl-3d0/cur_freq"
        };
        for (String path : paths) {
            long raw = readLong(path);
            float mhz = normalizeFrequencyMHz(raw, path);
            if (!Float.isNaN(mhz) && mhz > 0) return mhz;
        }

        File devfreq = new File("/sys/class/devfreq");
        File[] entries = devfreq.listFiles();
        if (entries != null) {
            for (File e : entries) {
                if (!isGpuDevfreq(e)) continue;
                String base = e.getAbsolutePath();
                String[] names = {"cur_freq", "gpuclk", "clock_mhz", "target_freq"};
                for (String name : names) {
                    String path = base + "/" + name;
                    float mhz = normalizeFrequencyMHz(readLong(path), path);
                    if (!Float.isNaN(mhz) && mhz > 0) return mhz;
                }
            }
        }
        return Float.NaN;
    }

    private boolean isGpuDevfreq(File e) {
        try {
            String name = readFirstLine(e.getAbsolutePath() + "/name");
            String text = e.getName() + " " + (name == null ? "" : name) + " " + e.getCanonicalPath();
            String low = text.toLowerCase(Locale.US);
            if (low.contains("busmon") || low.contains("memlat") || low.contains("bwmon") || low.contains("ddr")) {
                return false;
            }
            return low.contains("kgsl") || low.contains("gpu") || low.contains("3d0");
        } catch (Throwable ignored) {
            String low = e.getName().toLowerCase(Locale.US);
            return (low.contains("kgsl") || low.contains("gpu") || low.contains("3d0")) && !low.contains("busmon");
        }
    }

    private float readPercent(String path) {
        String s = readFirstLine(path);
        if (s == null) return Float.NaN;
        Matcher m = FIRST_NUMBER.matcher(s.replace(',', '.'));
        if (!m.find()) return Float.NaN;
        try {
            float v = Float.parseFloat(m.group());
            return (v >= 0f && v <= 100f) ? clamp(v) : Float.NaN;
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    private float readGpuBusyPair(String path) {
        String s = readFirstLine(path);
        if (s == null) return Float.NaN;
        try {
            String[] p = s.trim().split("\\s+");
            if (p.length < 2) return Float.NaN;
            long busy = Long.parseLong(p[0]);
            long total = Long.parseLong(p[1]);
            return calculateGpuBusy(busy, total);
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    private float readGpuBusyTimes(String busyPath, String totalPath) {
        long busy = readLong(busyPath);
        long total = readLong(totalPath);
        if (busy < 0 || total <= 0) return Float.NaN;
        return calculateGpuBusy(busy, total);
    }

    private float calculateGpuBusy(long busy, long total) {
        if (busy < 0 || total <= 0 || busy > total) return Float.NaN;
        float result;
        if (lastGpuBusy >= 0 && lastGpuTotal >= 0 && busy >= lastGpuBusy && total > lastGpuTotal) {
            long db = busy - lastGpuBusy;
            long dt = total - lastGpuTotal;
            result = dt > 0 ? clamp(100f * db / (float) dt) : Float.NaN;
        } else {
            result = clamp(100f * busy / (float) total);
        }
        lastGpuBusy = busy;
        lastGpuTotal = total;
        return result;
    }

    private float normalizeFrequencyMHz(long raw, String path) {
        if (raw == Long.MIN_VALUE || raw <= 0) return Float.NaN;
        String low = path.toLowerCase(Locale.US);
        if (low.contains("clock_mhz")) return raw;
        if (raw >= 10_000_000L) return raw / 1_000_000f; // Hz
        if (raw >= 10_000L) return raw / 1000f;          // kHz
        return raw;                                      // MHz
    }

    private float readThermalTemp(String[] keywords) {
        File root = new File("/sys/class/thermal");
        File[] zones = root.listFiles((dir, name) -> name.startsWith("thermal_zone"));
        if (zones == null) return Float.NaN;

        List<TempCandidate> matches = new ArrayList<>();
        for (File zone : zones) {
            String type = readFirstLine(zone.getAbsolutePath() + "/type");
            if (type == null) continue;
            String t = type.toLowerCase(Locale.US);
            boolean hit = false;
            for (String k : keywords) {
                if (t.contains(k)) { hit = true; break; }
            }
            if (!hit) continue;
            long raw = readLong(zone.getAbsolutePath() + "/temp");
            float c = normalizeTemp(raw);
            if (!Float.isNaN(c) && c > -20 && c < 150) matches.add(new TempCandidate(type, c));
        }
        if (matches.isEmpty()) return Float.NaN;
        TempCandidate hottest = Collections.max(matches, Comparator.comparingDouble(a -> a.temp));
        return hottest.temp;
    }

    private void readMemory(Snapshot s) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            s.memTotalBytes = mi.totalMem;
            s.memAvailBytes = mi.availMem;
            s.memUsedBytes = Math.max(0, mi.totalMem - mi.availMem);
            s.memUsage = mi.totalMem > 0 ? 100f * s.memUsedBytes / (float) mi.totalMem : Float.NaN;
        } catch (Throwable ignored) {
            s.memUsage = Float.NaN;
        }
    }

    private float readBatteryTemp() {
        try {
            Intent i = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return Float.NaN;
            int tenth = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            return tenth == Integer.MIN_VALUE ? Float.NaN : tenth / 10f;
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    private static float normalizeTemp(long raw) {
        if (raw == Long.MIN_VALUE) return Float.NaN;
        long a = Math.abs(raw);
        if (a > 10000) return raw / 1000f;
        if (a > 1000) return raw / 100f;
        if (a > 150) return raw / 10f;
        return raw;
    }

    private static String readFirstLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return br.readLine();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long readLong(String path) {
        String s = readFirstLine(path);
        if (s == null) return Long.MIN_VALUE;
        Matcher m = FIRST_NUMBER.matcher(s);
        if (!m.find()) return Long.MIN_VALUE;
        try { return Long.parseLong(m.group().split("\\.")[0]); }
        catch (Throwable ignored) { return Long.MIN_VALUE; }
    }

    private static long parseLong(String[] p, int i) {
        if (i >= p.length) return 0;
        try { return Long.parseLong(p[i]); } catch (Throwable ignored) { return 0; }
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(100f, v));
    }

    private static class TempCandidate {
        final String name;
        final float temp;
        TempCandidate(String name, float temp) { this.name = name; this.temp = temp; }
    }

    public static class Snapshot {
        public float cpuUsage = Float.NaN;
        public float cpuFreqMHz = Float.NaN;
        public float cpuTempC = Float.NaN;
        public float gpuUsage = Float.NaN;
        public float gpuFreqMHz = Float.NaN;
        public float gpuTempC = Float.NaN;
        public float memUsage = Float.NaN;
        public long memTotalBytes;
        public long memAvailBytes;
        public long memUsedBytes;
        public float batteryTempC = Float.NaN;
    }
}
