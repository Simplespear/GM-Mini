package com.GameMacro.mini;

import android.annotation.SuppressLint;
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
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 极简 BLE 客户端:只做连接 / 通知订阅 / 写 / 心跳 / 请求-响应队列。 */
@SuppressLint("MissingPermission")
public class BleClient {

    public static final UUID SVC = UUID.fromString("0000FF00-0000-1000-8000-00805F9B34FB");
    public static final UUID CH_WRITE = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB");
    public static final UUID CH_NOTIFY = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    public interface Listener {
        void onLog(String s);
        void onState(String s);
        void onFrame(byte[] frame);
        void onDeviceFound(String name, String mac, int rssi);
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler h = new Handler(Looper.getMainLooper());

    /** BluetoothProfile.HID_HOST 是 @hide 常量，这里用数值（4）。 */
    private static final int PROFILE_HID_HOST = 4;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic chW, chN;
    /** 记住本次连接/系统已连的设备，断开时要按设备去断系统链路。 */
    private BluetoothDevice currentDevice;

    private boolean heartbeatOn = false;
    private int pendingId = -1;
    private long pendingAt = 0;
    private int reqToken = 0;

    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();

    private final Runnable hbTask = new Runnable() {
        @Override public void run() {
            if (!heartbeatOn) return;
            write(Kr.heartbeatFrame());
            h.postDelayed(this, 1000);
        }
    };

    public BleClient(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) adapter = bm.getAdapter();
    }

    public boolean adapterReady() {
        return adapter != null && adapter.isEnabled();
    }

    private final Runnable scanTimeoutTask = new Runnable() {
        @Override public void run() {
            if (scanner == null) return;
            stopScan();
            listener.onLog("扫描已自动停止（15 秒）");
            listener.onState("扫描结束");
        }
    };

    public void startScan() {
        if (adapter == null) return;
        // 预填充已配对设备名字：部分手柄的 BLE 广播不带名字，扫描回调只能靠这里缓存的名字识别。
        bonded();
        try {
            if (scanner != null) { scanner.stopScan(scanCb); scanner = null; }
        } catch (Exception ignored) {}
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { listener.onLog("LE 扫描器不可用"); return; }
        ScanSettings s = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(null, s, scanCb);
        } catch (Exception e) {
            listener.onLog("启动扫描异常: " + e);
            return;
        }
        listener.onState("扫描中…");
        h.removeCallbacks(scanTimeoutTask);
        h.postDelayed(scanTimeoutTask, 15000);
    }

    public void stopScan() {
        h.removeCallbacks(scanTimeoutTask);
        try { if (scanner != null) scanner.stopScan(scanCb); } catch (Exception ignored) {}
        scanner = null;
    }

    /**
     * 已知手柄的广播名，内置在包里，断网也能认出来。
     * 名称来自设备广播和配对记录，不依赖任何在线接口。
     */
    public static final String[] KNOWN_BT_NAMES = {
            "GameMacro",            // T50 / 本机手柄
            "Midea",
            "Thunderobot G50Plus",
            "Mobapad-S1-Joy-Ls", "Mobapad-S1-Joy-Rs", "Mobapad-S1-Joy-Lhd", "Mobapad-S1-Joy-Rhd",
            "IINE Gamepad BLE",
            "GC-Pad",
            "EvoFox One S",
            "STK-7040HRG",
            "Mobapad-ML35",
            "CRKD ATOM PLUS", "CRKD-CK24ND",
            "Pocket Controller",
            "Coiorvis-L", "Coiorvis-R",
            "QMacro",
    };

    /** 兜底关键字：官方表之外的同类手柄（含中文名、常见代工名） */
    private static final String[] FUZZY_KEYS = {
            "gamemacro", "pocket controller", "qmacro", "mobapad", "coiorvis",
            "gamepad", "game pad", "controller", "joystick", "macro",
            "手柄", "t50"
    };

    /** 判断某个蓝牙名字/广播是否像我们的手柄（仅用于列表里做标记/排序，不再用于过滤）。 */
    public static boolean looksLikeController(String name, java.util.List<ParcelUuid> uuids) {
        // 1) 自制服务 UUID 0xFF00 广播 —— 最强判据（部分固件会在广播里带）
        if (hasTargetService(uuids)) return true;
        // 2) 名称匹配
        return nameMatches(name);
    }

    private static boolean hasTargetService(java.util.List<ParcelUuid> uuids) {
        if (uuids == null) return false;
        for (ParcelUuid u : uuids) {
            if (u == null || u.getUuid() == null) continue;
            UUID id = u.getUuid();
            if (id.equals(SVC)) return true;
            if (id.getMostSignificantBits() == SVC.getMostSignificantBits()
                    && id.getLeastSignificantBits() == SVC.getLeastSignificantBits()) return true;
        }
        return false;
    }

    /** 名称匹配：官方完整名优先，其次模糊关键字（全部忽略大小写与空格） */
    public static boolean nameMatches(String name) {
        if (name == null) return false;
        String n = name.trim().toLowerCase();
        if (n.isEmpty()) return false;
        String compact = n.replace(" ", "");
        for (String k : KNOWN_BT_NAMES) {
            if (n.equals(k.toLowerCase())) return true;
            if (compact.contains(k.toLowerCase().replace(" ", ""))) return true;
        }
        for (String k : FUZZY_KEYS) if (n.contains(k)) return true;
        return false;
    }

    /**
     * 自动接管用的严格匹配：只认官方内置型号名，不认 controller / gamepad 这类泛词，
     * 避免把系统里已连的 Xbox / PS 手柄也拉进本 App 的 GATT。
     */
    public static boolean autoAttachMatch(String name) {
        if (name == null) return false;
        String n = name.trim().toLowerCase();
        if (n.isEmpty()) return false;
        String compact = n.replace(" ", "");
        for (String k : KNOWN_BT_NAMES) {
            if (n.equals(k.toLowerCase())) return true;
            if (compact.contains(k.toLowerCase().replace(" ", ""))) return true;
        }
        return false;
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            BluetoothDevice d = r.getDevice();
            String addr = d.getAddress();
            ScanRecord rec = r.getScanRecord();

            // 名称三级兜底：系统缓存名 → 广播里的 Local Name → 已配对设备名
            // 1.5 只看第一级，导致大量"广播带名字但系统未缓存"的手柄被丢弃。
            String n = null;
            try { n = d.getName(); } catch (SecurityException ignored) {}
            if (n == null || n.trim().isEmpty()) {
                try { n = (rec != null) ? rec.getDeviceName() : null; } catch (Exception ignored) {}
            }
            if (n == null || n.trim().isEmpty()) n = bondedNames.get(addr);

            java.util.List<ParcelUuid> uuids = rec == null ? null : rec.getServiceUuids();

            // 默认列出全部扫描到的 BLE 设备，不做任何名称/UUID 过滤。
            // 原因：大量双模/经典蓝牙手柄的广播里既没有 FF00 UUID，也没有 Local Name，
            // 任何过滤条件都会把它们静默丢弃（这正是之前"扫不到设备"的根源）。
            // 是否手柄由用户在列表里选择，列表里只做标记与排序。
            if (n == null || n.trim().isEmpty()) n = "未命名设备 " + addr;
            listener.onDeviceFound(n, addr, r.getRssi());
        }
        @Override public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) onScanResult(0, r);
        }
        @Override public void onScanFailed(int code) {
            listener.onLog("扫描失败 code=" + code
                    + (code == 1 ? "（SCAN_FAILED_ALREADY_STARTED）" : "")
                    + (code == 2 ? "（SCAN_FAILED_APPLICATION_REGISTRATION_FAILED）" : "")
                    + (code == 3 ? "（SCAN_FAILED_INTERNAL_ERROR）" : "")
                    + (code == 4 ? "（SCAN_FAILED_FEATURE_UNSUPPORTED）" : ""));
        }
    };

    private volatile java.util.Map<String, String> bondedNames = new java.util.HashMap<>();

    /** 已配对设备(经典 + LE),扫描不到时可直接连。 */
    public List<BluetoothDevice> bonded() {
        List<BluetoothDevice> out = new ArrayList<>();
        if (adapter == null) return out;
        // 整体替换而不是先 clear：扫描回调可能正好在读取，避免读到空表
        java.util.Map<String, String> fresh = new java.util.HashMap<>();
        try {
            java.util.Set<BluetoothDevice> s = adapter.getBondedDevices();
            if (s != null) {
                for (BluetoothDevice d : s) {
                    out.add(d);
                    try {
                        String n = d.getName();
                        if (n != null) fresh.put(d.getAddress(), n);
                    } catch (SecurityException ignored) {}
                }
            }
        } catch (SecurityException ignored) {}
        bondedNames = fresh;
        return out;
    }

    public void connect(BluetoothDevice dev) {
        stopScan();
        hardClose();                       // 先彻底释放上一条连接,避免 status 133/147 抢占
        currentDevice = dev;
        listener.onState("连接中 " + dev.getAddress());
        // 刚 close 完立刻 connectGatt 在部分机型(尤其 Android 12+)会返回 133。
        // 官方 RxAndroidBle 也是先销毁旧连接再延时建连，这里留 250ms 让协议栈释放。
        h.postDelayed(() -> {
            try {
                gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE);
            } catch (Exception e) {
                listener.onLog("连接异常: " + e);
                listener.onState("连接失败");
            }
        }, 250);
    }

    /** 静默关闭当前 GATT(不改变 UI 状态)。 */
    private void hardClose() {
        heartbeatOn = false;
        h.removeCallbacks(hbTask);
        chW = null;
        chN = null;
        try { if (gatt != null) { gatt.disconnect(); gatt.close(); } } catch (Exception ignored) {}
        gatt = null;
    }

    public void connect(String mac) {
        if (adapter == null) return;
        try { connect(adapter.getRemoteDevice(mac)); }
        catch (Exception e) { listener.onLog("地址无效: " + mac); }
    }

    public void close() {
        hardClose();
        listener.onState("已断开");
    }

    public BluetoothDevice getCurrentDevice() { return currentDevice; }

    /**
     * 查找「系统已经连上」的手柄。
     *
     * 手柄在系统蓝牙里通常会被自动连成 HID 输入设备（不开本 App 也能当键盘用），
     * 但本 App 的 GATT 客户端并不会自动挂上去，所以刚打开 App 时看不到电量。
     * 这里主动去问系统：GATT / HID 两个 profile 下已连接的设备里，有没有手柄。
     */
    public BluetoothDevice findSystemConnected() {
        if (adapter == null) return null;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) return null;
        bonded();                       // 补一份「地址 -> 名字」缓存，广播没名字时靠它识别
        int[] profiles = {BluetoothProfile.GATT, PROFILE_HID_HOST};
        for (int p : profiles) {
            List<BluetoothDevice> list;
            try {
                list = bm.getConnectedDevices(p);
            } catch (Throwable t) {
                continue;
            }
            if (list == null) continue;
            for (BluetoothDevice d : list) {
                String n = null;
                try { n = d.getName(); } catch (SecurityException ignored) {}
                if (n == null || n.trim().isEmpty()) n = bondedNames.get(d.getAddress());
                if (nameMatches(n)) return d;
                // 名字没对上时，看设备类别：0x0404=摇杆 0x0408=游戏手柄
                try {
                    android.bluetooth.BluetoothClass cls = d.getBluetoothClass();
                    int dc = cls == null ? -1 : cls.getDeviceClass();
                    if (dc == 0x0404 || dc == 0x0408) return d;
                } catch (Throwable ignored) {}
            }
        }
        // 兜底：个别 ROM 不把 HID/GATT 连接暴露给普通 App，直接问 ACL 是否连着
        for (BluetoothDevice d : bonded()) {
            String n = null;
            try { n = d.getName(); } catch (SecurityException ignored) {}
            if (n == null || n.trim().isEmpty()) n = bondedNames.get(d.getAddress());
            if (!nameMatches(n)) continue;
            try {
                java.lang.reflect.Method m = BluetoothDevice.class.getMethod("isConnected");
                Object r = m.invoke(d);
                if (Boolean.TRUE.equals(r)) return d;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * 真正断开：不只是关掉本 App 的 GATT，还要把系统那条连接断掉，
     * 否则手柄仍然以 HID 键盘/手柄身份连着手机（指示灯不灭、仍能操控）。
     *
     * 手段按可用性依次尝试：
     *   1) 反射 BluetoothDevice.disconnect()  —— 一次断开该设备全部 profile
     *   2) HID_HOST profile proxy 的 disconnect() —— 系统隐藏接口，补刀
     */
    public void disconnectSystem() {
        BluetoothDevice dev = currentDevice;
        if (dev == null) dev = findSystemConnected();   // 没接管过也要能断
        hardClose();
        currentDevice = null;
        if (dev != null) {
            boolean ok = reflectDisconnect(dev);
            listener.onLog("系统蓝牙断开: " + (ok ? "已下发断开指令" : "反射不可用，改走 HID 通道"));
            hidDisconnect(dev);
        } else {
            listener.onLog("没有记录到设备，仅释放本机 GATT");
        }
        listener.onState("已断开");
    }

    /**
     * 「GameMacro」只是 App 与手柄之间的 BLE 协议名，手柄在系统蓝牙里通常还以
     * 「Pocket Controller」的经典 HID 身份存在。两者是同一只柄，解绑时必须一起处理。
     */
    public static boolean sameModelFamily(String a, String b) {
        if (a == null || b == null) return false;
        String x = a.trim().toLowerCase(), y = b.trim().toLowerCase();
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.equals(y)) return true;
        return isMacroFamily(x) && isMacroFamily(y);
    }

    private static boolean isMacroFamily(String n) {
        String c = n.replace(" ", "");
        return c.contains("gamemacro") || c.contains("pocketcontroller")
                || c.contains("qmacro") || c.contains("t50");
    }

    private static String safeName(BluetoothDevice d) {
        try { return d == null ? null : d.getName(); } catch (Throwable t) { return null; }
    }

    private static boolean isBonded(BluetoothDevice d) {
        try { return d != null && d.getBondState() == BluetoothDevice.BOND_BONDED; }
        catch (Throwable t) { return false; }
    }

    private boolean removeBond(BluetoothDevice d) {
        try {
            java.lang.reflect.Method m = BluetoothDevice.class.getMethod("removeBond");
            Object r = m.invoke(d);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 同一个 MAC 的经典/LE 句柄在 BluetoothDevice.equals 里相等，只能按对象身份去重。 */
    private static void addIdentity(List<BluetoothDevice> list, BluetoothDevice d) {
        if (d == null) return;
        for (BluetoothDevice x : list) if (x == d) return;
        list.add(d);
    }

    /**
     * 取消系统配对（官方 BluetoothUtil.removeBond 同款），但要覆盖手柄的全部身份：
     *   1) 当前 GATT 设备（BLE，名字通常是 GameMacro）
     *   2) 同一 MAC 的经典/LE 两种 transport 句柄
     *   3) 系统配对列表里同系列的 Pocket Controller（经典 HID，地址可能不同）
     *
     * 返回实际找到并下发的系统配对项数量（0 表示系统里本来就没有配对）。
     */
    public int unpair(BluetoothDevice d) {
        if (d == null) return 0;
        hardClose();
        currentDevice = null;

        final List<BluetoothDevice> targets = new ArrayList<>();
        final String addr = d.getAddress();
        final String name = safeName(d);
        addIdentity(targets, d);

        // 同一 MAC 的另一个传输句柄：部分 ROM 的 bond 按 transport 分开存储，
        // 只对 BLE 句柄 removeBond 时，经典 HID 的配对会残留。
        if (adapter != null) {
            try { addIdentity(targets, adapter.getRemoteDevice(addr)); } catch (Throwable ignored) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                for (int tr : new int[]{BluetoothDevice.TRANSPORT_BREDR, BluetoothDevice.TRANSPORT_LE}) {
                    try { addIdentity(targets, adapter.getRemoteLeDevice(addr, tr)); } catch (Throwable ignored) {}
                }
            }
        }

        // Pocket Controller / GameMacro 是同一只柄的两个名字，地址也可能不同。
        int bondedHits = 0;
        for (BluetoothDevice b : bonded()) {
            String n = safeName(b);
            boolean same = addr.equalsIgnoreCase(b.getAddress()) || sameModelFamily(name, n);
            if (!same) continue;
            addIdentity(targets, b);
            if (isBonded(b)) bondedHits++;
        }
        if (isBonded(d)) bondedHits++;

        int sent = 0;
        for (BluetoothDevice t : targets) {
            boolean wasBonded = isBonded(t);
            reflectDisconnect(t);              // 先放掉 ACL，removeBond 成功率更高
            if (removeBond(t) && wasBonded) sent++;
        }
        final int found = Math.max(bondedHits, sent);
        listener.onLog("取消配对: 系统配对项 " + bondedHits + "，已下发 " + sent + " 个句柄");
        if (found == 0) return 0;

        // removeBond 是异步的，个别 ROM 第一次只断连不解绑；1.2s 后复查补刀。
        h.postDelayed(() -> {
            int left = 0;
            for (BluetoothDevice t : targets) {
                if (isBonded(t)) { left++; removeBond(t); }
            }
            if (left > 0) listener.onLog("取消配对: 仍有 " + left + " 项未清，已再次下发");
            else listener.onLog("取消配对: 系统配对已清除");
        }, 1200);
        return found;
    }

    /** 反射调用 BluetoothDevice.disconnect()（@hide，普通 App 只能靠反射）。 */
    private boolean reflectDisconnect(BluetoothDevice d) {        try {
            java.lang.reflect.Method m = BluetoothDevice.class.getMethod("disconnect");
            Object r = m.invoke(d);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 通过 HID_HOST profile 断开系统侧的输入设备连接。 */
    private void hidDisconnect(final BluetoothDevice dev) {
        if (adapter == null) return;
        try {
            adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    try {
                        // proxy 的实现类是包内可见的，直接从实现类 getMethod 会 IllegalAccess，
                        // 这里遍历它实现的接口（BluetoothHidHost）拿方法再 invoke。
                        java.lang.reflect.Method m = null;
                        for (Class<?> c : proxy.getClass().getInterfaces()) {
                            try {
                                m = c.getMethod("disconnect", BluetoothDevice.class);
                                if (m != null) break;
                            } catch (Throwable ignored) {}
                        }
                        if (m == null) {
                            try {
                                m = proxy.getClass().getMethod("disconnect", BluetoothDevice.class);
                                m.setAccessible(true);
                            } catch (Throwable ignored) {}
                        }
                        if (m != null) {
                            m.invoke(proxy, dev);
                            listener.onLog("已通过 HID 通道断开系统连接");
                        } else {
                            listener.onLog("HID 断开不可用（找不到 disconnect 方法）");
                        }
                    } catch (Throwable t) {
                        listener.onLog("HID 断开不可用（" + t.getClass().getSimpleName() + "）");
                    } finally {
                        try { adapter.closeProfileProxy(profile, proxy); } catch (Throwable ignored) {}
                    }
                }
                @Override public void onServiceDisconnected(int profile) {}
            }, PROFILE_HID_HOST);
        } catch (Throwable t) {
            listener.onLog("无法打开 HID 通道: " + t.getClass().getSimpleName());
        }
    }

    public boolean isConnected() {
        return gatt != null && chW != null && chN != null;
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onState("已连接,发现服务…");
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                heartbeatOn = false;
                h.removeCallbacks(hbTask);
                chW = null;
                chN = null;
                // 1.5 只改状态不回收 GATT，重复连接会累积句柄并触发 status=133。
                try { g.close(); } catch (Exception ignored) {}
                if (gatt == g) gatt = null;
                listener.onState("断开 status=" + status);
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService svc = g.getService(SVC);
            if (svc == null) {
                listener.onLog("未找到服务 FF00,该设备可能不是目标手柄");
                return;
            }
            chW = svc.getCharacteristic(CH_WRITE);
            chN = svc.getCharacteristic(CH_NOTIFY);
            if (chW == null || chN == null) {
                listener.onLog("缺少 FF01/FF02 特征");
                return;
            }
            g.setCharacteristicNotification(chN, true);
            BluetoothGattDescriptor d = chN.getDescriptor(CCCD);
            if (d != null) {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(d);
            } else {
                listener.onLog("无 CCCD 描述符(部分设备可免)");
                onSubscribed();
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (CCCD.equals(d.getUuid())) onSubscribed();
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v == null) return;
            listener.onFrame(v);
            if (pendingId >= 0 && v.length >= 2 && (v[1] & 0xff) == pendingId) {
                pendingId = -1;
                next();
            }
        }
    };

    private void onSubscribed() {
        listener.onState("通信通知注册成功");
        heartbeatOn = true;
        h.postDelayed(hbTask, 300);
    }

    /** 入队发送,串行等待响应或超时。 */
    public void request(int expectId, byte[] frame, long timeoutMs) {
        final int token = ++reqToken;
        pendingId = expectId;
        pendingAt = System.currentTimeMillis();
        write(frame);
        h.postDelayed(() -> {
            // 1.5 原来比较 "now - pendingAt >= timeoutMs - 5"，只要回调被系统稍微提前调度
            // 就永远不成立，导致 pendingId 卡死、后续请求全部超时。这里改为到点即判超时。
            if (token == reqToken && pendingId == expectId) {
                pendingId = -1;
                listener.onLog("请求超时 id=0x" + Integer.toHexString(expectId));
                next();
            }
        }, timeoutMs);
    }

    public void enqueue(byte[] frame) { queue.add(frame); }
    public boolean queueEmpty() { return queue.isEmpty(); }

    private void next() {
        byte[] f = queue.poll();
        if (f != null) write(f);
    }

    public void write(byte[] data) {
        if (gatt == null || chW == null) { listener.onLog("未连接,丢弃 " + Kr.hex(data)); return; }
        if (data == null || data.length == 0) return;
        // 官方协议固定 20 字节分包（BaseReq 之外的 BleManager.writeMessage）。
        // 1.5 直接整帧写，超过 MTU(默认 20) 会被底层截断或返回失败。
        if (data.length <= 20) { writeChunk(data); return; }
        for (int off = 0; off < data.length; off += 20) {
            final int o = off;
            final byte[] chunk = java.util.Arrays.copyOfRange(data, o, Math.min(o + 20, data.length));
            if (o == 0) writeChunk(chunk);
            else h.postDelayed(() -> writeChunk(chunk), (o / 20) * 40L);
        }
    }

    private void writeChunk(byte[] chunk) {
        if (gatt == null || chW == null) return;
        try {
            int props = chW.getProperties();
            if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                chW.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            else
                chW.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            chW.setValue(chunk);
            gatt.writeCharacteristic(chW);
        } catch (Exception e) {
            listener.onLog("写入失败: " + e);
        }
    }
}
