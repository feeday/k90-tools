package com.ppt.k90monitor;

import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class GpuDiagnostics {
    private static final int MAX_SCAN_ITEMS = 500;
    private static final int MAX_VALUE_CHARS = 180;

    private static final String[] INTERESTING_NAMES = {
            "gpu_busy_percentage", "gpu_load", "load", "gpubusy",
            "cur_freq", "target_freq", "gpuclk", "clock_mhz",
            "busy_time", "total_time", "utilization", "busy_percent",
            "available_frequencies", "min_freq", "max_freq", "name", "governor"
    };

    private GpuDiagnostics() { }

    public static String run() {
        StringBuilder out = new StringBuilder(8192);
        out.append("K90 Performance Overlay - GPU Diagnostics V3\n");
        out.append("================================================\n");
        out.append("MODEL: ").append(Build.MODEL).append('\n');
        out.append("DEVICE: ").append(Build.DEVICE).append('\n');
        out.append("PRODUCT: ").append(Build.PRODUCT).append('\n');
        out.append("SDK: ").append(Build.VERSION.SDK_INT).append('\n');
        if (Build.VERSION.SDK_INT >= 31) {
            out.append("SOC_MODEL: ").append(Build.SOC_MODEL).append('\n');
            out.append("SOC_MANUFACTURER: ").append(Build.SOC_MANUFACTURER).append('\n');
        }
        out.append("\n[getprop]\n");
        appendCommand(out, new String[]{"getprop", "ro.soc.model"});
        appendCommand(out, new String[]{"getprop", "ro.hardware"});
        appendCommand(out, new String[]{"getprop", "ro.board.platform"});

        out.append("\n[Known Qualcomm / KGSL paths]\n");
        String[] known = {
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
                "/sys/class/kgsl/kgsl-3d0/gpu_load",
                "/sys/class/kgsl/kgsl-3d0/devfreq/load",
                "/sys/class/kgsl/kgsl-3d0/gpubusy",
                "/sys/class/kgsl/kgsl-3d0/devfreq/gpubusy",
                "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
                "/sys/class/kgsl/kgsl-3d0/clock_mhz",
                "/sys/class/kgsl/kgsl-3d0/gpuclk",
                "/sys/class/kgsl/kgsl-3d0/devfreq/target_freq",
                "/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies"
        };
        for (String path : known) appendPath(out, new File(path));

        out.append("\n[/sys/class/kgsl tree]\n");
        scanTree(out, new File("/sys/class/kgsl"), 0, 3, new int[]{0});

        out.append("\n[/sys/class/devfreq GPU candidates]\n");
        File devfreq = new File("/sys/class/devfreq");
        File[] entries = safeList(devfreq);
        if (entries == null) {
            appendDirState(out, devfreq);
        } else {
            Arrays.sort(entries, Comparator.comparing(File::getName));
            for (File entry : entries) {
                String nameValue = safeRead(entry.getAbsolutePath() + "/name");
                String probe = (entry.getName() + " " + (nameValue == null ? "" : nameValue)).toLowerCase(Locale.US);
                if (probe.contains("gpu") || probe.contains("kgsl") || probe.contains("3d0") || probe.contains("adreno")) {
                    out.append("\nDEVFREQ: ").append(entry.getAbsolutePath());
                    if (nameValue != null) out.append(" name=").append(shorten(nameValue));
                    out.append('\n');
                    appendInterestingChildren(out, entry);
                }
            }
        }

        out.append("\n[/sys/devices GPU/KGSL candidates, limited scan]\n");
        scanGpuCandidates(out, new File("/sys/devices"), 0, 4, new int[]{0});

        out.append("\n[Command probes]\n");
        appendCommand(out, new String[]{"sh", "-c", "ls -ld /sys/class/kgsl /sys/class/kgsl/kgsl-3d0 /sys/class/kgsl/kgsl-3d0/devfreq 2>&1"});
        appendCommand(out, new String[]{"sh", "-c", "ls -l /sys/class/kgsl/kgsl-3d0 2>&1 | head -80"});
        appendCommand(out, new String[]{"sh", "-c", "ls -l /sys/class/kgsl/kgsl-3d0/devfreq 2>&1 | head -80"});

        out.append("\n================================================\n");
        out.append("说明：EXISTS=true 但 READ=false/ERROR=Permission denied 表示 HyperOS/SELinux 禁止普通 APK 读取。\n");
        return out.toString();
    }

    private static void appendInterestingChildren(StringBuilder out, File dir) {
        for (String name : INTERESTING_NAMES) {
            File f = new File(dir, name);
            if (f.exists()) appendPath(out, f);
        }
        File childDevfreq = new File(dir, "devfreq");
        if (childDevfreq.exists()) {
            for (String name : INTERESTING_NAMES) {
                File f = new File(childDevfreq, name);
                if (f.exists()) appendPath(out, f);
            }
        }
    }

    private static void scanTree(StringBuilder out, File file, int depth, int maxDepth, int[] count) {
        if (count[0] >= MAX_SCAN_ITEMS) return;
        count[0]++;
        appendPath(out, file);
        if (!file.isDirectory() || depth >= maxDepth) return;
        File[] children = safeList(file);
        if (children == null) return;
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            scanTree(out, child, depth + 1, maxDepth, count);
            if (count[0] >= MAX_SCAN_ITEMS) {
                out.append("... scan limit reached ...\n");
                break;
            }
        }
    }

    private static void scanGpuCandidates(StringBuilder out, File dir, int depth, int maxDepth, int[] count) {
        if (count[0] >= MAX_SCAN_ITEMS || depth > maxDepth) return;
        File[] children = safeList(dir);
        if (children == null) return;
        for (File child : children) {
            if (count[0] >= MAX_SCAN_ITEMS) return;
            String low = child.getName().toLowerCase(Locale.US);
            boolean interesting = low.contains("kgsl") || low.contains("gpu") || low.contains("adreno") || low.contains("3d0");
            if (interesting) {
                count[0]++;
                appendPath(out, child);
                if (child.isDirectory()) appendInterestingChildren(out, child);
            }
            if (child.isDirectory() && depth < maxDepth) {
                scanGpuCandidates(out, child, depth + 1, maxDepth, count);
            }
        }
    }

    private static void appendDirState(StringBuilder out, File f) {
        out.append(f.getAbsolutePath())
                .append(" EXISTS=").append(f.exists())
                .append(" DIR=").append(f.isDirectory())
                .append(" READ=").append(f.canRead())
                .append('\n');
    }

    private static void appendPath(StringBuilder out, File f) {
        out.append(f.getAbsolutePath())
                .append(" EXISTS=").append(f.exists())
                .append(" DIR=").append(f.isDirectory())
                .append(" READ=").append(f.canRead());
        if (f.exists() && f.isFile()) {
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line = br.readLine();
                out.append(" VALUE=").append(line == null ? "<empty>" : shorten(line));
            } catch (Throwable e) {
                out.append(" ERROR=").append(e.getClass().getSimpleName());
                if (e.getMessage() != null) out.append(":").append(shorten(e.getMessage()));
            }
        }
        out.append('\n');
    }

    private static File[] safeList(File dir) {
        try { return dir.listFiles(); }
        catch (Throwable ignored) { return null; }
    }

    private static String safeRead(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return br.readLine();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void appendCommand(StringBuilder out, String[] command) {
        out.append("$ ").append(String.join(" ", command)).append('\n');
        Process p = null;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!p.waitFor(1200, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                out.append("<timeout>\n");
                return;
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                int lines = 0;
                while ((line = br.readLine()) != null && lines++ < 100) {
                    out.append(shorten(line)).append('\n');
                }
            }
        } catch (Throwable e) {
            out.append("ERROR=").append(e.getClass().getSimpleName());
            if (e.getMessage() != null) out.append(":").append(shorten(e.getMessage()));
            out.append('\n');
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) { }
            }
        }
    }

    private static String shorten(String s) {
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() <= MAX_VALUE_CHARS ? s : s.substring(0, MAX_VALUE_CHARS) + "...";
    }
}
