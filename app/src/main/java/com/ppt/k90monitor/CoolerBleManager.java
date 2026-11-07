package com.ppt.k90monitor;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;

/** REDMAGIC Cryo Cooler 8 Pro BLE protocol support, mapped from real device logs. */
public final class CoolerBleManager {
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final UUID ADV_SERVICE = UUID.fromString("00004a41-0000-1000-8000-00805f9b34fb");
    public static final UUID SERVICE = UUID.fromString("d52082ad-e805-9f97-9d4e-1c682d9c9ce6");
    public static final UUID CHAR_HALL = UUID.fromString("00001011-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_LIGHT = UUID.fromString("00001013-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_TEMP = UUID.fromString("00001014-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_STATUS = UUID.fromString("00001015-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_DESTROYER = UUID.fromString("00001017-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_AUTO_TEMP = UUID.fromString("00001018-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_FAN_RPM = UUID.fromString("0000101c-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_POWER = UUID.fromString("0000101d-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_PROTECT = UUID.fromString("0000101f-0000-1000-8000-00805f9b34fb");

    private static CoolerBleManager instance;

    public static synchronized CoolerBleManager get(Context context) {
        if (instance == null) instance = new CoolerBleManager(context.getApplicationContext());
        return instance;
    }

    public static final class State {
        public boolean scanning;
        public boolean connected;
        public String deviceName = "--";
        public String address = "--";
        public float clampTempC = Float.NaN;
        public int fanRpm = -1;
        public float powerW = Float.NaN;
        public Boolean coolerOn = null;
        public Boolean autoTemp = null;
        public Boolean destroyer = null;
        public String firmware = "--";
        public String lastPacket = "--";
        public String message = "未连接";

        State copy() {
            State s = new State();
            s.scanning = scanning;
            s.connected = connected;
            s.deviceName = deviceName;
            s.address = address;
            s.clampTempC = clampTempC;
            s.fanRpm = fanRpm;
            s.powerW = powerW;
            s.coolerOn = coolerOn;
            s.autoTemp = autoTemp;
            s.destroyer = destroyer;
            s.firmware = firmware;
            s.lastPacket = lastPacket;
            s.message = message;
            return s;
        }
    }

    private interface GattOp { boolean start(BluetoothGatt g); }

    private final Context context;
    private final Object lock = new Object();
    private final State state = new State();
    private final Queue<GattOp> queue = new ArrayDeque<>();
    private boolean opRunning;
    private long lastRefreshMs;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattService rmService;

    private CoolerBleManager(Context context) {
        this.context = context;
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
    }

    public State getState() {
        synchronized (lock) { return state.copy(); }
    }

    public boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public void startAutoConnect() {
        if (!hasPermissions()) { setMessage("缺少蓝牙权限"); return; }
        if (adapter == null || !adapter.isEnabled()) { setMessage("蓝牙未开启"); return; }
        State s = getState();
        if (s.connected || s.scanning) return;
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { setMessage("BLE扫描不可用"); return; }
            synchronized (lock) {
                state.scanning = true;
                state.message = "正在寻找 RM Magcooler 8pro…";
            }
            scanner.startScan(scanCallback);
        } catch (Throwable e) {
            setMessage("扫描失败: " + e.getClass().getSimpleName());
        }
    }

    public void refreshTelemetry() {
        State s = getState();
        if (!s.connected || rmService == null) return;
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs < 1500) return;
        lastRefreshMs = now;
        synchronized (queue) {
            if (queue.size() > 2 || opRunning) return;
            enqueueReadLocked(CHAR_TEMP);
            enqueueReadLocked(CHAR_FAN_RPM);
            enqueueReadLocked(CHAR_POWER);
            enqueueReadLocked(CHAR_HALL);
        }
        runNext();
    }

    /** Intelligent temperature control. ON/OFF verified: characteristic 0x1018, byte 01/00. */
    public boolean setAutoTemp(boolean on) {
        if (on && Boolean.TRUE.equals(getState().destroyer)) enqueueWrite(CHAR_DESTROYER, new byte[]{0x00});
        enqueueWrite(CHAR_AUTO_TEMP, new byte[]{(byte) (on ? 1 : 0)});
        return true;
    }

    /** Destroyer/overclock mode. ON/OFF verified: characteristic 0x1017, byte 01/00. */
    public boolean setDestroyer(boolean on) {
        if (on && Boolean.TRUE.equals(getState().autoTemp)) enqueueWrite(CHAR_AUTO_TEMP, new byte[]{0x00});
        enqueueWrite(CHAR_DESTROYER, new byte[]{(byte) (on ? 1 : 0)});
        return true;
    }

    /** Only the ON command (0x02) is verified in the supplied logs. No guessed OFF command is sent. */
    public boolean turnCoolerOn() {
        enqueueWrite(CHAR_HALL, new byte[]{0x02});
        return true;
    }

    public void disconnect() {
        try { if (scanner != null && hasPermissions()) scanner.stopScan(scanCallback); } catch (Throwable ignored) { }
        try { if (gatt != null) gatt.disconnect(); } catch (Throwable ignored) { }
        try { if (gatt != null) gatt.close(); } catch (Throwable ignored) { }
        gatt = null;
        rmService = null;
        synchronized (queue) { queue.clear(); opRunning = false; }
        synchronized (lock) {
            state.scanning = false;
            state.connected = false;
            state.message = "未连接";
            state.coolerOn = null;
            state.autoTemp = null;
            state.destroyer = null;
            state.fanRpm = -1;
            state.clampTempC = Float.NaN;
            state.powerW = Float.NaN;
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null) return;
            String name = safeName(result);
            String low = name.toLowerCase(Locale.US);
            boolean nameMatch = low.contains("rm magcooler 8pro") || low.contains("magcooler") ||
                    low.contains("redmagic") || low.contains("red magic");
            boolean serviceMatch = false;
            try {
                ScanRecord r = result.getScanRecord();
                if (r != null && r.getServiceUuids() != null) {
                    for (android.os.ParcelUuid p : r.getServiceUuids()) {
                        if (ADV_SERVICE.equals(p.getUuid())) { serviceMatch = true; break; }
                    }
                }
            } catch (Throwable ignored) { }
            if (!nameMatch && !serviceMatch) return;
            connect(result.getDevice(), name);
        }

        @Override public void onScanFailed(int errorCode) {
            synchronized (lock) { state.scanning = false; state.message = "扫描失败 code=" + errorCode; }
        }
    };

    private void connect(BluetoothDevice device, String name) {
        try { if (scanner != null) scanner.stopScan(scanCallback); } catch (Throwable ignored) { }
        synchronized (lock) {
            state.scanning = false;
            state.deviceName = name;
            try { state.address = device.getAddress(); } catch (Throwable ignored) { }
            state.message = "连接中…";
        }
        try {
            gatt = Build.VERSION.SDK_INT >= 23
                    ? device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                    : device.connectGatt(context, false, callback);
        } catch (Throwable e) { setMessage("连接失败: " + e.getClass().getSimpleName()); }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            synchronized (lock) {
                state.connected = newState == BluetoothProfile.STATE_CONNECTED;
                state.message = state.connected ? "已连接，发现服务…" : "已断开";
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try { g.discoverServices(); } catch (Throwable ignored) { }
            } else {
                rmService = null;
                synchronized (queue) { queue.clear(); opRunning = false; }
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) { setMessage("服务发现失败: " + status); return; }
            rmService = g.getService(SERVICE);
            if (rmService == null) { setMessage("已连接，但未找到 8 Pro 服务"); return; }

            BluetoothGattCharacteristic statusChar = rmService.getCharacteristic(CHAR_STATUS);
            if (statusChar != null) {
                try {
                    g.setCharacteristicNotification(statusChar, true);
                    BluetoothGattDescriptor d = statusChar.getDescriptor(CCCD);
                    if (d != null) enqueueDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } catch (Throwable ignored) { }
            }

            synchronized (queue) {
                enqueueReadLocked(CHAR_HALL);
                enqueueReadLocked(CHAR_TEMP);
                enqueueReadLocked(CHAR_FAN_RPM);
                enqueueReadLocked(CHAR_POWER);
                enqueueReadLocked(CHAR_DESTROYER);
                enqueueReadLocked(CHAR_AUTO_TEMP);
            }
            setMessage("已连接 · REDMAGIC 8 Pro");
            runNext();
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) { finishOp(); }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) onPacket(c, value);
            finishOp();
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (Build.VERSION.SDK_INT < 33 && status == BluetoothGatt.GATT_SUCCESS) onPacket(c, c.getValue());
            if (Build.VERSION.SDK_INT < 33) finishOp();
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Read back state instead of assuming the write succeeded semantically.
                synchronized (queue) { enqueueReadLocked(c.getUuid()); }
            }
            finishOp();
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) { onPacket(c, value); }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) onPacket(c, c.getValue());
        }
    };

    private void onPacket(BluetoothGattCharacteristic c, byte[] value) {
        if (c == null || value == null) return;
        UUID u = c.getUuid();
        String hex = toHex(value);
        synchronized (lock) { state.lastPacket = u + " " + hex; }

        if (CHAR_TEMP.equals(u) && value.length >= 1) {
            setClampTemp(decodeDisplayTemp(value[0] & 0xFF));
        } else if (CHAR_FAN_RPM.equals(u) && value.length >= 2) {
            setFanRpm(u16be(value[0], value[1]));
        } else if (CHAR_POWER.equals(u) && value.length >= 1) {
            synchronized (lock) { state.powerW = value[0] & 0xFF; }
        } else if (CHAR_HALL.equals(u) && value.length >= 1) {
            int v = value[0] & 0xFF;
            synchronized (lock) {
                // 0x02 is verified as switch_on. Other values remain conservative.
                state.coolerOn = v == 0x02 ? Boolean.TRUE : (v == 0x00 ? Boolean.FALSE : null);
            }
        } else if (CHAR_AUTO_TEMP.equals(u) && value.length >= 1) {
            synchronized (lock) { state.autoTemp = value[0] != 0; }
        } else if (CHAR_DESTROYER.equals(u) && value.length >= 1) {
            synchronized (lock) { state.destroyer = value[0] != 0; }
        } else if (CHAR_STATUS.equals(u) && value.length >= 2) {
            decodeStatus(value);
        }
    }

    private void decodeStatus(byte[] v) {
        int event = v[0] & 0xFF;
        if (event == 0x04 && v.length >= 2) {
            setClampTemp(decodeDisplayTemp(v[1] & 0xFF));
        } else if (event == 0x08 && v.length >= 3) {
            setFanRpm(u16be(v[1], v[2]));
        }
    }

    /** Firmware V8.4.7 logs show raw 25 (0x19) displayed by the official app as 19°C. */
    private float decodeDisplayTemp(int raw) { return raw - 6f; }

    private void setClampTemp(float c) {
        if (c < -30f || c > 100f) return;
        synchronized (lock) { state.clampTempC = c; }
    }

    private void setFanRpm(int rpm) {
        if (rpm < 0 || rpm > 20000) return;
        synchronized (lock) { state.fanRpm = rpm; }
    }

    private static int u16be(byte hi, byte lo) { return ((hi & 0xFF) << 8) | (lo & 0xFF); }

    private void enqueueReadLocked(UUID uuid) {
        if (rmService == null) return;
        BluetoothGattCharacteristic c = rmService.getCharacteristic(uuid);
        if (c == null || (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) return;
        queue.offer(g -> {
            try { return g.readCharacteristic(c); } catch (Throwable e) { return false; }
        });
    }

    private void enqueueWrite(UUID uuid, byte[] data) {
        if (!getState().connected || rmService == null) return;
        BluetoothGattCharacteristic c = rmService.getCharacteristic(uuid);
        if (c == null) return;
        synchronized (queue) {
            queue.offer(g -> {
                try {
                    if (Build.VERSION.SDK_INT >= 33) {
                        return g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0;
                    }
                    c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    c.setValue(data);
                    return g.writeCharacteristic(c);
                } catch (Throwable e) { return false; }
            });
        }
        runNext();
    }

    private void enqueueDescriptor(BluetoothGattDescriptor d, byte[] data) {
        synchronized (queue) {
            queue.offer(g -> {
                try {
                    if (Build.VERSION.SDK_INT >= 33) return g.writeDescriptor(d, data) == 0;
                    d.setValue(data);
                    return g.writeDescriptor(d);
                } catch (Throwable e) { return false; }
            });
        }
    }

    private void runNext() {
        BluetoothGatt g = gatt;
        if (g == null) return;
        while (true) {
            GattOp op;
            synchronized (queue) {
                if (opRunning) return;
                op = queue.poll();
                if (op == null) return;
                opRunning = true;
            }
            boolean started = false;
            try { started = op.start(g); } catch (Throwable ignored) { }
            if (started) return;
            synchronized (queue) { opRunning = false; }
        }
    }

    private void finishOp() {
        synchronized (queue) { opRunning = false; }
        runNext();
    }

    private String safeName(ScanResult r) {
        try {
            String n = r.getDevice().getName();
            if (n != null && !n.trim().isEmpty()) return n.trim();
        } catch (Throwable ignored) { }
        try {
            ScanRecord sr = r.getScanRecord();
            if (sr != null && sr.getDeviceName() != null) return sr.getDeviceName();
        } catch (Throwable ignored) { }
        return "Unknown BLE";
    }

    private void setMessage(String s) { synchronized (lock) { state.message = s; } }

    private static String toHex(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", b & 0xFF));
        }
        return sb.toString();
    }
}
