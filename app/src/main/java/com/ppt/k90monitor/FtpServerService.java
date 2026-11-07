package com.ppt.k90monitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Lightweight passive-mode FTP server for LAN file transfer. */
public class FtpServerService extends Service {
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_USER = "user";
    public static final String EXTRA_PASS = "pass";
    public static final String ACTION_STOP = "com.ppt.k90monitor.STOP_FTP";

    private static final String CHANNEL_ID = "k90_ftp";
    private static final int NOTIFICATION_ID = 9010;

    private static volatile boolean running;
    private static volatile int runningPort = -1;
    private static volatile String runningRoot = "--";
    private static final AtomicInteger activeConnections = new AtomicInteger();
    private static final AtomicLong downloadedBytes = new AtomicLong();
    private static final AtomicLong uploadedBytes = new AtomicLong();

    private ServerSocket controlServer;
    private ExecutorService pool;
    private Thread acceptThread;
    private volatile boolean stopping;
    private String username = "k90";
    private String password = "123456";
    private File rootDir;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public static boolean isRunning() { return running; }
    public static int getRunningPort() { return runningPort; }
    public static String getRunningRoot() { return runningRoot; }
    public static int getActiveConnections() { return Math.max(0, activeConnections.get()); }
    public static long getDownloadedBytes() { return Math.max(0L, downloadedBytes.get()); }
    public static long getUploadedBytes() { return Math.max(0L, uploadedBytes.get()); }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        pool = Executors.newCachedThreadPool();
        acquireBackgroundLocks();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int port = intent == null ? 2121 : intent.getIntExtra(EXTRA_PORT, 2121);
        username = sanitizeCredential(intent == null ? null : intent.getStringExtra(EXTRA_USER), "k90");
        password = sanitizeCredential(intent == null ? null : intent.getStringExtra(EXTRA_PASS), "123456");

        if (running) return START_STICKY;

        rootDir = chooseRoot();
        if (!rootDir.exists()) rootDir.mkdirs();
        runningRoot = rootDir.getAbsolutePath();
        runningPort = port;
        stopping = false;
        activeConnections.set(0);
        downloadedBytes.set(0L);
        uploadedBytes.set(0L);

        startForeground(NOTIFICATION_ID, buildNotification(port));
        startServer(port);
        return START_STICKY;
    }

    private void acquireBackgroundLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "K90PerformanceOverlay:FTP");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Throwable ignored) { }

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "K90PerformanceOverlay:FTP-WIFI");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Throwable ignored) { }
    }

    private void releaseBackgroundLocks() {
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable ignored) { }
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) { }
        wifiLock = null;
        wakeLock = null;
    }

    private void startServer(int port) {
        acceptThread = new Thread(() -> {
            try {
                controlServer = new ServerSocket(port);
                controlServer.setReuseAddress(true);
                running = true;
                while (!stopping) {
                    try {
                        Socket socket = controlServer.accept();
                        pool.execute(() -> handleClient(socket));
                    } catch (Throwable e) {
                        if (!stopping) e.printStackTrace();
                    }
                }
            } catch (Throwable e) {
                running = false;
                stopSelf();
            }
        }, "K90-FTP-Accept");
        acceptThread.start();
    }

    private void handleClient(Socket socket) {
        ServerSocket passive = null;
        activeConnections.incrementAndGet();
        try (Socket client = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(client.getOutputStream()), true)) {

            client.setSoTimeout(120_000);
            reply(out, 220, "K90 FTP ready");

            boolean userOk = false;
            boolean loggedIn = false;
            File cwd = rootDir;
            long restartOffset = 0L;
            File renameFrom = null;

            String line;
            while ((line = in.readLine()) != null) {
                if (line.length() > 4096) { reply(out, 500, "Command too long"); continue; }
                String cmd;
                String arg;
                int sp = line.indexOf(' ');
                if (sp < 0) { cmd = line.trim().toUpperCase(Locale.US); arg = ""; }
                else { cmd = line.substring(0, sp).trim().toUpperCase(Locale.US); arg = line.substring(sp + 1).trim(); }

                if ("USER".equals(cmd)) { userOk = username.equals(arg); loggedIn = false; reply(out, 331, "Password required"); continue; }
                if ("PASS".equals(cmd)) { loggedIn = userOk && password.equals(arg); reply(out, loggedIn ? 230 : 530, loggedIn ? "Login successful" : "Login incorrect"); continue; }
                if ("QUIT".equals(cmd)) { reply(out, 221, "Bye"); break; }
                if ("SYST".equals(cmd)) { reply(out, 215, "UNIX Type: L8"); continue; }
                if ("FEAT".equals(cmd)) { out.print("211-Features\r\n UTF8\r\n SIZE\r\n MDTM\r\n PASV\r\n EPSV\r\n211 End\r\n"); out.flush(); continue; }
                if ("OPTS".equals(cmd)) { reply(out, 200, "OK"); continue; }
                if ("NOOP".equals(cmd)) { reply(out, 200, "OK"); continue; }
                if (!loggedIn) { reply(out, 530, "Please login"); continue; }

                switch (cmd) {
                    case "PWD":
                    case "XPWD": reply(out, 257, "\"" + virtualPath(cwd) + "\""); break;
                    case "CWD": { File f = resolve(cwd, arg); if (f != null && f.isDirectory()) { cwd = f; reply(out, 250, "Directory changed"); } else reply(out, 550, "Directory unavailable"); break; }
                    case "CDUP": { File f = resolve(cwd, ".."); if (f != null && f.isDirectory()) cwd = f; reply(out, 250, "Directory changed"); break; }
                    case "TYPE": reply(out, 200, "Type set"); break;
                    case "MODE": reply(out, 200, "Mode set"); break;
                    case "STRU": reply(out, 200, "Structure set"); break;
                    case "PASV": {
                        closeQuietly(passive); passive = new ServerSocket(0); passive.setSoTimeout(30_000);
                        InetAddress addr = client.getLocalAddress(); byte[] ip = addr instanceof Inet4Address ? addr.getAddress() : findLanIpv4().getAddress(); int p = passive.getLocalPort();
                        reply(out, 227, String.format(Locale.US, "Entering Passive Mode (%d,%d,%d,%d,%d,%d)", ip[0]&255, ip[1]&255, ip[2]&255, ip[3]&255, p/256, p%256)); break;
                    }
                    case "EPSV": { closeQuietly(passive); passive = new ServerSocket(0); passive.setSoTimeout(30_000); reply(out, 229, "Entering Extended Passive Mode (|||" + passive.getLocalPort() + "|)"); break; }
                    case "LIST":
                    case "NLST": {
                        if (passive == null) { reply(out, 425, "Use PASV first"); break; }
                        File target = arg.isEmpty() ? cwd : resolve(cwd, stripListOptions(arg));
                        if (target == null || !target.exists()) { reply(out, 550, "Not found"); closeQuietly(passive); passive = null; break; }
                        reply(out, 150, "Opening data connection");
                        try (Socket data = passive.accept(); PrintWriter d = new PrintWriter(new OutputStreamWriter(data.getOutputStream()), true)) { if ("NLST".equals(cmd)) writeNames(d, target); else writeList(d, target); }
                        catch (SocketTimeoutException e) { reply(out, 425, "Data timeout"); }
                        closeQuietly(passive); passive = null; reply(out, 226, "Transfer complete"); break;
                    }
                    case "RETR": {
                        File f = resolve(cwd, arg); if (passive == null) { reply(out, 425, "Use PASV first"); break; }
                        if (f == null || !f.isFile()) { reply(out, 550, "File unavailable"); closeQuietly(passive); passive = null; break; }
                        reply(out, 150, "Opening binary data connection");
                        try (Socket data = passive.accept(); BufferedInputStream fis = new BufferedInputStream(new FileInputStream(f)); BufferedOutputStream dos = new BufferedOutputStream(data.getOutputStream())) { skipFully(fis, restartOffset); copy(fis, dos, downloadedBytes); }
                        catch (Throwable e) { reply(out, 426, "Transfer aborted"); }
                        restartOffset = 0L; closeQuietly(passive); passive = null; reply(out, 226, "Transfer complete"); break;
                    }
                    case "STOR":
                    case "APPE": {
                        File f = resolve(cwd, arg); if (passive == null) { reply(out, 425, "Use PASV first"); break; }
                        if (f == null) { reply(out, 550, "Invalid path"); closeQuietly(passive); passive = null; break; }
                        File parent = f.getParentFile(); if (parent != null && !parent.exists()) parent.mkdirs();
                        reply(out, 150, "Opening binary data connection"); boolean append = "APPE".equals(cmd);
                        try (Socket data = passive.accept(); BufferedInputStream dis = new BufferedInputStream(data.getInputStream()); BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(f, append))) { copy(dis, fos, uploadedBytes); }
                        catch (Throwable e) { reply(out, 426, "Transfer aborted"); }
                        closeQuietly(passive); passive = null; reply(out, 226, "Transfer complete"); break;
                    }
                    case "SIZE": { File f = resolve(cwd, arg); if (f != null && f.isFile()) reply(out, 213, String.valueOf(f.length())); else reply(out, 550, "Not found"); break; }
                    case "MDTM": { File f = resolve(cwd, arg); if (f != null && f.exists()) reply(out, 213, new SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(new Date(f.lastModified()))); else reply(out, 550, "Not found"); break; }
                    case "REST": try { restartOffset = Math.max(0L, Long.parseLong(arg)); reply(out, 350, "Restart position accepted"); } catch (Throwable e) { reply(out, 501, "Bad offset"); } break;
                    case "DELE": { File f = resolve(cwd, arg); reply(out, f != null && f.isFile() && f.delete() ? 250 : 550, f != null && !f.exists() ? "Deleted" : "Delete failed"); break; }
                    case "MKD":
                    case "XMKD": { File f = resolve(cwd, arg); boolean ok = f != null && (f.isDirectory() || f.mkdirs()); reply(out, ok ? 257 : 550, ok ? "\"" + virtualPath(f) + "\" created" : "Create failed"); break; }
                    case "RMD":
                    case "XRMD": { File f = resolve(cwd, arg); boolean ok = f != null && f.isDirectory() && f.delete(); reply(out, ok ? 250 : 550, ok ? "Removed" : "Remove failed"); break; }
                    case "RNFR": { File f = resolve(cwd, arg); if (f != null && f.exists()) { renameFrom = f; reply(out, 350, "Ready for RNTO"); } else { renameFrom = null; reply(out, 550, "Not found"); } break; }
                    case "RNTO": { File dst = resolve(cwd, arg); boolean ok = renameFrom != null && dst != null && renameFrom.renameTo(dst); renameFrom = null; reply(out, ok ? 250 : 550, ok ? "Renamed" : "Rename failed"); break; }
                    default: reply(out, 502, "Command not implemented"); break;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(passive);
            activeConnections.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    private File chooseRoot() {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return Environment.getExternalStorageDirectory();
        File dir = getExternalFilesDir("ftp");
        return dir != null ? dir : new File(getFilesDir(), "ftp");
    }

    private File resolve(File cwd, String path) {
        try {
            File base = path.startsWith("/") ? rootDir : cwd; String p = path.startsWith("/") ? path.substring(1) : path;
            File f = new File(base, p).getCanonicalFile(); File root = rootDir.getCanonicalFile(); String rp = root.getPath(); String fp = f.getPath();
            if (fp.equals(rp) || fp.startsWith(rp + File.separator)) return f;
        } catch (Throwable ignored) { }
        return null;
    }

    private String virtualPath(File file) {
        try { String rp = rootDir.getCanonicalPath(); String fp = file.getCanonicalPath(); if (fp.equals(rp)) return "/"; String rel = fp.substring(rp.length()).replace(File.separatorChar, '/'); return rel.startsWith("/") ? rel : "/" + rel; }
        catch (Throwable e) { return "/"; }
    }

    private void writeNames(PrintWriter out, File target) { if (target.isFile()) { out.print(target.getName() + "\r\n"); out.flush(); return; } File[] files = target.listFiles(); if (files == null) return; for (File f : files) out.print(f.getName() + "\r\n"); out.flush(); }
    private void writeList(PrintWriter out, File target) { if (target.isFile()) { out.print(listLine(target)); out.flush(); return; } File[] files = target.listFiles(); if (files == null) return; for (File f : files) out.print(listLine(f)); out.flush(); }
    private String listLine(File f) { String perms = f.isDirectory() ? "drwxr-xr-x" : "-rw-r--r--"; String time = new SimpleDateFormat("MMM dd HH:mm", Locale.US).format(new Date(f.lastModified())); return String.format(Locale.US, "%s 1 owner group %12d %s %s\r\n", perms, f.isDirectory() ? 0L : f.length(), time, f.getName()); }
    private String stripListOptions(String arg) { String s = arg.trim(); while (s.startsWith("-")) { int sp = s.indexOf(' '); if (sp < 0) return ""; s = s.substring(sp + 1).trim(); } return s; }
    private static void copy(BufferedInputStream in, BufferedOutputStream out, AtomicLong counter) throws Exception { byte[] buf = new byte[64 * 1024]; int n; while ((n = in.read(buf)) >= 0) { if (n > 0) { out.write(buf, 0, n); counter.addAndGet(n); } } out.flush(); }
    private static void skipFully(BufferedInputStream in, long count) throws Exception { long left = count; while (left > 0) { long n = in.skip(left); if (n <= 0) { if (in.read() < 0) break; n = 1; } left -= n; } }
    private static String sanitizeCredential(String v, String fallback) { if (v == null) return fallback; v = v.trim(); return v.isEmpty() ? fallback : v.replace("\r", "").replace("\n", ""); }
    private static void reply(PrintWriter out, int code, String msg) { out.print(code + " " + msg + "\r\n"); out.flush(); }
    private static void closeQuietly(ServerSocket s) { if (s != null) try { s.close(); } catch (Throwable ignored) { } }

    public static String getLanIp() { try { return findLanIpv4().getHostAddress(); } catch (Throwable e) { return "127.0.0.1"; } }
    private static InetAddress findLanIpv4() throws Exception { Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces(); while (interfaces.hasMoreElements()) { NetworkInterface ni = interfaces.nextElement(); if (!ni.isUp() || ni.isLoopback()) continue; Enumeration<InetAddress> addrs = ni.getInetAddresses(); while (addrs.hasMoreElements()) { InetAddress a = addrs.nextElement(); if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a; } } return InetAddress.getByName("127.0.0.1"); }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "FTP 文件传输", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("局域网 FTP 文件传输服务");
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(int port) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 11, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, FtpServerService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 12, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setContentTitle("K90 FTP 已启动")
                .setContentText("ftp://" + getLanIp() + ":" + port + " · 后台运行")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(content)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止 FTP", stopPi).build())
                .build();
    }

    @Override public void onDestroy() {
        stopping = true;
        running = false;
        runningPort = -1;
        activeConnections.set(0);
        try { if (controlServer != null) controlServer.close(); } catch (Throwable ignored) { }
        if (pool != null) pool.shutdownNow();
        releaseBackgroundLocks();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
