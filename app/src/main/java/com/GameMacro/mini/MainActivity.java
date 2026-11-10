package com.GameMacro.mini;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GM Mini —— T50 手柄的极简配置工具。
 *
 * 页面结构：设备（连接 / 手柄详情 / 日志）· 宏配置 · 关于。
 * 所有弹窗统一使用 MaterialDialog，永远居于屏幕正中并带背景模糊。
 */
public class MainActivity extends AppCompatActivity implements BleClient.Listener {

    private static final int REQ_PERM = 1001;

    // Material 3 色板
    private static final int C_PRIMARY      = 0xFF4A5BD8;
    private static final int C_ON_PRIMARY   = 0xFFFFFFFF;
    private static final int C_PRIMARY_CONT = 0xFFE0E4FF;
    private static final int C_ON_CONT      = 0xFF1B2A6B;
    private static final int C_SURFACE      = 0xFFF6F5FB;
    private static final int C_CARD         = 0xFFFFFFFF;
    private static final int C_OUTLINE      = 0xFFE8E6F0;
    private static final int C_ON_SURFACE   = 0xFF1B1B21;
    private static final int C_ON_VARIANT   = 0xFF5C5C70;
    private static final int C_GREEN        = 0xFF146C43;
    private static final int C_RED          = 0xFFB3261E;
    private static final int C_BLUE         = 0xFF3A5BD9;
    private static final int C_TRACK        = 0xFFEEF0FA;

    private BleClient ble;

    private FrameLayout rootView, overlay;
    private FrameLayout pages;
    private ScrollView[] pageArr = new ScrollView[3];
    private NavigationBarView navBar;
    private int currentPage = 0;

    private TextView tvState, tvDevice, tvBattery, tvChargeTag;
    private int lastPct = -1;
    private boolean lastCharging = false;
    private ImageView headPic;
    private FrameLayout headPicBox;
    private PadImages.Pad headPad;
    private TextView tvMacroCap, tvMacroList, tvMacroInfo;
    private LinearLayout deviceBox;
    private LinearLayout detailCard;
    private BatteryView batteryView;
    private Button deviceListBtn;
    private MaterialDialog deviceMenu;
    private LinearLayout deviceMenuBox;
    private int deviceMenuCount = -1;
    private boolean scanning;
    private final List<View> detailRows = new ArrayList<>();

    // 手柄当前模式（0x87 回包 f[3]）：低半区 0/1/2/4，高半区 128/129/130/132
    private int handleMode = -1;
    private MaterialSwitch swMapper;
    private boolean classicOn = true;
    private LinearLayout classicRow, classicBtn;
    private ImageView classicIcon;
    private TextView tvMapperSub;

    // 手柄信息缓存（固件版本弹窗用）
    private boolean infoLoaded = false;
    private String infoProject = "", infoAgreement = "", infoFw = "", infoHw = "", infoModel = "";
    private MaterialDialog fwDialog;

    private final Map<String, String> found = new LinkedHashMap<>();
    private final Map<String, Integer> rssiMap = new LinkedHashMap<>();
    private final Kr.Reassembler asm = new Kr.Reassembler();
    private int asmId = -1;

    private int macroSupport = -1;
    private final List<String> macroProfiles = new ArrayList<>();
    private String rawMacro = "";

    // 宏配置气泡：名字列表 + 各自占用的配置编号（删除后编号回收，新增优先补最小空号）
    private static final String UI_PREFS = "gmmini_ui";
    private static final String KEY_CFG_NAMES = "cfg_names";
    private final List<String> cfgNames = new ArrayList<>();
    private final List<Integer> cfgNos = new ArrayList<>();
    private LinearLayout cfgListBox;

    private boolean connected = false;
    private boolean userDisconnected = false;
    private boolean attaching = false;

    // 校准会话：官方用 DeviceAlignRsp 上报驱动结束，两种校准共用一套状态
    private static final int CALIB_NONE = 0, CALIB_GYRO = 1, CALIB_STICK = 2;
    private int calibMode = CALIB_NONE;
    private MaterialDialog calibDialog;
    private CountDownTimer calibTimer;
    private CalibRingView calibRing;
    private boolean calibDone;

    private final Handler ui = new Handler();

    /** 前台每 3s 问一次系统「手柄是不是已经自己连上了」，解决手柄开机自动回连但界面不动。 */
    private final Runnable attachWatch = new Runnable() {
        @Override public void run() {
            tryAutoAttach();
            ui.postDelayed(this, 3000);
        }
    };

    /** 扫描回调里合并刷新设备弹窗，避免每来一台设备就重建一次列表。 */
    private final Runnable menuRefresh = this::renderDeviceMenu;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        loadConfigs();
        rootView = buildUi();
        setContentView(rootView);
        ble = new BleClient(this, this);
        askPermissions();
        rootView.postDelayed(this::tryAutoAttach, 1200);
        rootView.postDelayed(() -> animatePageIn(pageArr[0]), 80);
    }

    @Override protected void onResume() {
        super.onResume();
        registerAclReceiver();
        if (rootView != null) rootView.postDelayed(this::tryAutoAttach, 600);
        ui.removeCallbacks(attachWatch);
        ui.postDelayed(attachWatch, 3000);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(attachWatch);
        ui.removeCallbacks(menuRefresh);
        try { unregisterReceiver(aclReceiver); } catch (Exception ignored) {}
    }

    /** 监听系统蓝牙 ACL 连接：手柄被系统连上时立刻接管。 */
    private void registerAclReceiver() {
        try {
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(aclReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(aclReceiver, f);
        } catch (Exception e) {
            log("注册蓝牙广播失败: " + e);
        }
    }

    private final android.content.BroadcastReceiver aclReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i == null || !BluetoothDevice.ACTION_ACL_CONNECTED.equals(i.getAction())) return;
            BluetoothDevice d;
            try { d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE); } catch (Throwable t) { return; }
            if (d == null || ble == null || ble.isConnected() || userDisconnected) return;
            String n = null;
            try { n = d.getName(); } catch (SecurityException ignored) {}
            if (!BleClient.autoAttachMatch(n)) return;
            log("系统蓝牙已连上手柄 " + n + "，自动接管…");
            tvDevice.setText(n);
            ble.connect(d);
        }
    };

    /** 自动接管系统已连接的手柄。 */
    private void tryAutoAttach() {
        if (ble == null || ble.isConnected() || attaching || userDisconnected) return;
        BluetoothDevice d = ble.findSystemConnected();
        if (d == null) return;
        String n = null;
        try { n = d.getName(); } catch (SecurityException ignored) {}
        // 只接管官方型号名，避免把系统里的 Xbox / 其他 HID 手柄也拉进来
        if (!BleClient.autoAttachMatch(n)) return;
        scanning = false;
        log("检测到系统已连接手柄" + (n == null || n.isEmpty() ? "" : " " + n) + "，自动接管…");
        if (n != null && !n.isEmpty()) tvDevice.setText(n);
        attaching = true;
        // 连接过程可能超过 4s：用 15s 兜底，正常情况由 onState 的「已连接/断开」提前解除
        if (rootView != null) rootView.postDelayed(() -> attaching = false, 15000);
        ble.connect(d);
    }

    // ================= UI =================

    private FrameLayout buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(C_SURFACE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        pages = new FrameLayout(this);
        pageArr[0] = buildDevicePage();
        pageArr[1] = buildMacroPage();
        pageArr[2] = buildAboutPage();
        pageArr[1].setVisibility(View.GONE);
        pageArr[2].setVisibility(View.GONE);
        for (ScrollView p : pageArr) {
            pages.addView(p, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        col.addView(pages, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        col.addView(buildNavBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new FrameLayout(this);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setDetailEnabled(false, false);
        return root;
    }

    // ---------------- 设备页 ----------------

    private ScrollView buildDevicePage() {
        ScrollView sv = pageScroll();
        LinearLayout col = pageColumn(sv);

        eyebrow(col);
        titleWithClassicToggle(col);

        LinearLayout statusCard = card(col);

        deviceBox = new LinearLayout(this);
        deviceBox.setOrientation(LinearLayout.VERTICAL);
        deviceBox.setVisibility(View.GONE);
        statusCard.addView(deviceBox);

        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        deviceBox.addView(headRow);

        // 左：官方产品原图。未识别型号时整块隐藏——官方不显示通用图标
        headPicBox = new FrameLayout(this);
        GradientDrawable picBg = new GradientDrawable();
        picBg.setColor(0xFFFFFFFF);
        picBg.setCornerRadius(dp(20));
        picBg.setStroke(dp(1), 0xFFEFEDF7);
        headPicBox.setBackground(picBg);
        headPicBox.setClipToOutline(true);
        headPicBox.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        headPicBox.setVisibility(View.GONE);
        headPic = new ImageView(this);
        headPic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        headPic.setPadding(dp(5), dp(5), dp(5), dp(5));
        headPicBox.addView(headPic, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams picLp = new LinearLayout.LayoutParams(dp(96), dp(96));
        picLp.rightMargin = dp(14);
        headRow.addView(headPicBox, picLp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        headRow.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvDevice = new TextView(this);
        tvDevice.setText("手柄");
        tvDevice.setTextSize(20);
        tvDevice.setMaxLines(2);
        tvDevice.setTextColor(C_ON_SURFACE);
        tvDevice.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        info.addView(tvDevice);

        // 一行读完：状态 · 电池图标 · 真实电量 · 充电/低电标记
        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(7);
        info.addView(metaRow, metaLp);

        tvState = new TextView(this);
        tvState.setText("已连接");
        tvState.setTextSize(13);
        tvState.setTextColor(C_PRIMARY);
        tvState.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tvState.setIncludeFontPadding(false);
        metaRow.addView(tvState);

        batteryView = new BatteryView(this);
        batteryView.setAccent(C_PRIMARY);
        LinearLayout.LayoutParams battLp = new LinearLayout.LayoutParams(dp(30), dp(14));
        battLp.leftMargin = dp(12);
        metaRow.addView(batteryView, battLp);

        tvBattery = new TextView(this);
        tvBattery.setText("—");
        tvBattery.setTextSize(13);
        tvBattery.setTextColor(C_ON_SURFACE);
        tvBattery.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tvBattery.setIncludeFontPadding(false);
        LinearLayout.LayoutParams pctLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pctLp.leftMargin = dp(6);
        metaRow.addView(tvBattery, pctLp);

        tvChargeTag = new TextView(this);
        tvChargeTag.setText("充电中");
        tvChargeTag.setTextSize(10);
        tvChargeTag.setTextColor(C_ON_CONT);
        tvChargeTag.setPadding(dp(7), dp(2), dp(7), dp(2));
        tvChargeTag.setIncludeFontPadding(false);
        GradientDrawable tagBg = new GradientDrawable();
        tagBg.setColor(C_PRIMARY_CONT);
        tagBg.setCornerRadius(dp(8));
        tvChargeTag.setBackground(tagBg);
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagLp.leftMargin = dp(8);
        tvChargeTag.setVisibility(View.GONE);
        metaRow.addView(tvChargeTag, tagLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        Button scanBtn = filledBtn("扫描设备", v -> doScan());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        slp.rightMargin = dp(5);
        actions.addView(scanBtn, slp);

        deviceListBtn = tonalBtn("设备列表", v -> showDeviceMenu());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        dlp.leftMargin = dp(5);
        actions.addView(deviceListBtn, dlp);

        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(10);
        statusCard.addView(actions, actionsLp);

        // 手柄详情（常驻；未连接时置灰，避免连接后整块“刷不出来”）
        sectionTitle(col, "手柄详情");
        detailCard = card(col);
        detailRow(detailCard, R.drawable.ic_pad, "按键测试",
                "实时查看按键、摇杆与扳机状态", this::openKeyTest);
        detailRow(detailCard, R.drawable.ic_gyro, "陀螺仪校准",
                "60 秒倒计时 · 保持手柄静止", this::showGyroCalib);
        detailRow(detailCard, R.drawable.ic_stick, "摇杆校准",
                "按提示推满左右摇杆完成校准", this::showStickCalib);
        detailRow(detailCard, R.drawable.ic_firmware, "固件版本",
                "查看型号、协议、固件与硬件版本", this::showFirmware);
        modeSwitchRow(detailCard);
        detailRow(detailCard, R.drawable.ic_reset, "恢复出厂模式",
                "清除宏、键映射、灯效与校准数据", this::showFactoryReset);
        detailRow(detailCard, R.drawable.ic_unpair, "取消配对",
                "解除与手机的蓝牙配对，需重新配对连接", this::showUnpair);

        setDetailEnabled(false, false);
        return sv;
    }

    private void detailRow(LinearLayout parent, int iconRes, String title, String sub, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(10), dp(4), dp(10));
        row.setBackground(rowRipple());
        row.setClickable(true);

        row.addView(iconTile(iconRes), new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(14), 0, dp(6), 0);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14.5f);
        t.setTextColor(C_ON_SURFACE);
        t.setIncludeFontPadding(false);
        text.addView(t);

        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(11);
        s.setTextColor(C_ON_VARIANT);
        s.setPadding(0, dp(4), 0, 0);
        s.setIncludeFontPadding(false);
        text.addView(s);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));

        row.setOnClickListener(v -> {
            if (!connected) { snack("请先连接手柄"); return; }
            action.run();
        });
        parent.addView(row);
        detailRows.add(row);
    }

    /** 统一的行首图标底板：40dp 圆角方块 + 20dp 图标，详情行共用。 */
    private LinearLayout iconTile(int iconRes) {
        LinearLayout ib = new LinearLayout(this);
        ib.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_TRACK);
        g.setCornerRadius(dp(13));
        ib.setBackground(g);
        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        iv.setColorFilter(C_PRIMARY);
        ib.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));
        return ib;
    }

    /** 「切换映射模式」：与其它详情行同构——图标 + 标题 + 当前模式 + 自绘开关。 */
    private void modeSwitchRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(10), dp(4), dp(10));
        row.setBackground(rowRipple());
        row.setClickable(true);

        row.addView(iconTile(R.drawable.ic_mapper), new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(14), 0, dp(6), 0);

        TextView t = new TextView(this);
        t.setText("切换映射模式");
        t.setTextSize(14.5f);
        t.setTextColor(C_ON_SURFACE);
        t.setIncludeFontPadding(false);
        text.addView(t);

        tvMapperSub = new TextView(this);
        tvMapperSub.setText("当前为宏模式，宏配置生效");
        tvMapperSub.setTextSize(11);
        tvMapperSub.setTextColor(C_ON_VARIANT);
        tvMapperSub.setPadding(0, dp(4), 0, 0);
        tvMapperSub.setIncludeFontPadding(false);
        text.addView(tvMapperSub);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        swMapper = new MaterialSwitch(this);
        swMapper.setContentDescription("切换映射模式");
        row.addView(swMapper, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setOnClickListener(v -> {
            if (!connected) { snack("请先连接手柄"); return; }
            swMapper.toggle();
            onMapperSwitch();
        });
        swMapper.setOnClickListener(v -> onMapperSwitch());
        parent.addView(row);
        detailRows.add(row);
    }

    /** 标题行左侧「设备」，右上角是原版「关闭经典蓝牙」的蓝牙图标开关。 */
    private void titleWithClassicToggle(LinearLayout col) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(2), dp(0), dp(10));

        TextView t = new TextView(this);
        t.setText("设备");
        t.setTextSize(26);
        t.setTextColor(C_ON_SURFACE);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        classicBtn = new LinearLayout(this);
        classicBtn.setGravity(Gravity.CENTER);
        classicBtn.setClickable(true);

        classicIcon = new ImageView(this);
        classicIcon.setImageResource(R.drawable.ic_bluetooth);
        classicBtn.addView(classicIcon, new LinearLayout.LayoutParams(dp(19), dp(19)));

        classicBtn.setOnClickListener(v -> onClassicSwitch());
        classicBtn.setOnLongClickListener(v -> {
            snack(classicOn ? "经典蓝牙已开启，点击可关闭" : "经典蓝牙已关闭，点击可开启");
            return true;
        });
        row.addView(classicBtn, new LinearLayout.LayoutParams(dp(36), dp(36)));

        classicRow = row;
        col.addView(row);
        syncClassicIcon();
    }

    /** 蓝牙图标状态：开启为主色淡底 + 主色图标，关闭为浅灰底 + 灰图标。 */
    private void syncClassicIcon() {
        if (classicBtn == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(classicOn ? C_PRIMARY_CONT : C_TRACK);
        bg.setCornerRadius(dp(12));
        if (classicOn) bg.setStroke(dp(1), 0x334A5BD8);
        classicBtn.setBackground(new RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x1F4A5BD8), bg, null));
        if (classicIcon != null) {
            classicIcon.setColorFilter(classicOn ? C_PRIMARY : 0xFFA6A4B8);
        }
        classicBtn.setContentDescription(classicOn ? "经典蓝牙已开启" : "经典蓝牙已关闭");
    }

    /** 映射模式开关：官方 device_switch_mapper / device_switch_normal 两段文案。 */
    private void onMapperSwitch() {
        if (!connected) {
            syncModeUi();
            snack("请先连接手柄");
            return;
        }
        final boolean toMapping = swMapper.isChecked();
        new MaterialDialog.Builder(this)
                .title("切换映射模式")
                .message(toMapping
                        ? "开启映射模式后，会关闭宏模式，是否继续切换？"
                        : "开启宏模式后，会关闭映射模式，是否继续切换？")
                .negative("取消", this::syncModeUi)
                .positive("确定", () -> {
                    int target = modeTarget(toMapping);
                    write(Kr.setKeyBoardMode(target),
                            toMapping ? "切换到映射模式" : "切换到宏模式");
                    handleMode = target;
                    syncModeUi();
                    snack(toMapping ? "正在切换到映射模式…" : "正在切换到宏模式…");
                })
                .build().show();
    }

    /** 经典蓝牙图标：官方 device_switch_ble / device_switch_classic 两段文案。 */
    private void onClassicSwitch() {
        if (!connected) {
            snack("请先连接手柄");
            return;
        }
        final boolean turnOn = !classicOn;
        new MaterialDialog.Builder(this)
                .title("经典蓝牙")
                .message(turnOn
                        ? "打开经典蓝牙后，可在 Switch/Ps/Xbox/PC/Android/IOS/有线连接 上使用？"
                        : "关闭经典蓝牙后，将不可在 Switch/Ps/Xbox/PC/Android/IOS/有线连接 上使用？")
                .negative("取消", null)
                .positive("确定", () -> {
                    int base = handleMode < 0 ? 196 : handleMode;
                    int target = turnOn
                            ? (base >= 128 ? base : base + 128)
                            : (base >= 128 ? base - 128 : base);
                    write(Kr.setKeyBoardMode(target),
                            turnOn ? "打开经典蓝牙" : "关闭经典蓝牙");
                    handleMode = target;
                    classicOn = turnOn;
                    syncModeUi();
                    snack(turnOn ? "正在打开经典蓝牙…" : "正在关闭经典蓝牙…");
                })
                .build().show();
    }

    /** 官方 getSwitchScreen 的取值：高半区用 192/193，低半区用 64/65。 */
    private int modeTarget(boolean mapping) {
        boolean classic = handleMode < 0 || handleMode >= 128;
        if (mapping) return classic ? 192 : 64;
        return classic ? 193 : 65;
    }

    /** 开关状态跟随 0x87 上报；未读到时按官方默认（经典开启、宏模式）显示。 */
    private void syncModeUi() {
        int m = handleMode < 0 ? 196 : handleMode;
        classicOn = m >= 128;
        if (swMapper != null) swMapper.setChecked((m & 0x7f) == 0);
        if (tvMapperSub != null) {
            tvMapperSub.setText((m & 0x7f) == 0
                    ? "当前为映射模式，键值映射生效"
                    : "当前为宏模式，宏配置生效");
        }
        syncClassicIcon();
    }

    /** 未连接时置灰手柄详情与两个开关，但保持可见——连接后立刻可点。 */
    private void setDetailEnabled(boolean on, boolean animate) {
        if (detailCard != null) {
            // 卡片背景在 MaterialCardView 上，透明度要作用到外层卡片
            View cardView = detailCard.getParent() instanceof View
                    ? (View) detailCard.getParent() : detailCard;
            float target = on ? 1f : 0.42f;
            if (animate) cardView.animate().alpha(target).setDuration(200).start();
            else cardView.setAlpha(target);
        }
        if (classicRow != null) classicRow.setAlpha(on ? 1f : 0.42f);
        if (classicBtn != null) classicBtn.setEnabled(on);
        if (swMapper != null) swMapper.setEnabled(on);
        // 连接成功的瞬间，详情行依次浮入（Material 列表入场）
        if (on && animate) {
            int i = 0;
            for (View r : detailRows) {
                r.setAlpha(0f);
                r.setTranslationY(dp(6));
                r.animate().alpha(1f).translationY(0f)
                        .setStartDelay(40L + 32L * (i++))
                        .setDuration(220)
                        .setInterpolator(new DecelerateInterpolator(1.6f)).start();
            }
        }
    }

    // ---------------- 宏配置页 ----------------

    private ScrollView buildMacroPage() {
        ScrollView sv = pageScroll();
        LinearLayout col = pageColumn(sv);

        eyebrow(col);

        // 标题行：宏配置 + 右上角「＋」新增配置
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(2), 0, dp(10));

        TextView t = new TextView(this);
        t.setText("宏配置");
        t.setTextSize(16);
        t.setTextColor(C_ON_SURFACE);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        head.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView add = new TextView(this);
        add.setText("＋");
        add.setTextSize(22);
        add.setGravity(Gravity.CENTER);
        add.setTextColor(C_PRIMARY);
        add.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable addBg = new GradientDrawable();
        addBg.setColor(C_PRIMARY_CONT);
        addBg.setCornerRadius(dp(20));
        add.setBackground(addBg);
        add.setClickable(true);
        add.setOnClickListener(v -> addConfig());
        head.addView(add, new LinearLayout.LayoutParams(dp(40), dp(40)));

        col.addView(head);

        cfgListBox = new LinearLayout(this);
        cfgListBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(cfgListBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        renderConfigs();
        return sv;
    }

    /** 无配置时只留一句引导，不做弹窗；有配置时每条渲染成一个「配置」气泡。 */
    private void renderConfigs() {
        if (cfgListBox == null) return;
        cfgListBox.removeAllViews();

        if (cfgNames.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(44), dp(24), dp(44));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(C_CARD);
            bg.setCornerRadius(dp(24));
            bg.setStroke(dp(1), C_OUTLINE);
            empty.setBackground(bg);

            TextView e = new TextView(this);
            e.setText("还没有配置\n点击右上角 ＋ 添加配置");
            e.setTextSize(15);
            e.setTextColor(C_ON_VARIANT);
            e.setGravity(Gravity.CENTER);
            e.setLineSpacing(dp(6), 1f);
            empty.addView(e);
            cfgListBox.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }

        for (int i = 0; i < cfgNames.size(); i++) {
            cfgListBox.addView(buildConfigCard(i));
        }
    }

    private LinearLayout buildConfigCard(final int index) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), C_OUTLINE);
        box.setBackground(bg);
        box.setElevation(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        box.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(cfgNames.get(index));
        name.setTextSize(16);
        name.setTextColor(C_ON_SURFACE);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView rename = iconAction(R.drawable.ic_edit, C_PRIMARY, "重命名配置");
        rename.setOnClickListener(v -> renameConfig(index));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(34), dp(34));
        rlp.leftMargin = dp(2);
        head.addView(rename, rlp);

        ImageView del = iconAction(R.drawable.ic_delete, 0xFFD93B3B, "删除配置");
        del.setOnClickListener(v -> deleteConfig(index));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(34), dp(34));
        dlp.leftMargin = dp(2);
        head.addView(del, dlp);

        box.addView(head);

        // 气泡内的五个功能入口，对应 MacroEditorActivity 的五个 tab
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(12);

        String[] labels = {"连发", "摇杆", "映射", "振动", "体感"};
        for (int i = 0; i < labels.length; i++) {
            final int tab = i;
            TextView fn = new TextView(this);
            fn.setText(labels[i]);
            fn.setTextSize(12.5f);
            fn.setTextColor(C_ON_CONT);
            fn.setGravity(Gravity.CENTER);
            fn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            GradientDrawable fbg = new GradientDrawable();
            fbg.setColor(C_TRACK);
            fbg.setCornerRadius(dp(14));
            fn.setBackground(fbg);
            fn.setClickable(true);
            fn.setOnClickListener(v -> openMacroTab(index, tab));
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, dp(46), 1f);
            flp.setMargins(dp(3), 0, dp(3), 0);
            grid.addView(fn, flp);
        }
        box.addView(grid, glp);
        return box;
    }

    /** 小图标按钮：34dp 圆形 ripple + 约 18dp 图标，用于气泡右上角的重命名 / 删除。 */
    private ImageView iconAction(int res, int color, String desc) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setColorFilter(color);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setPadding(dp(8), dp(8), dp(8), dp(8));
        v.setContentDescription(desc);
        v.setClickable(true);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        v.setBackground(new RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.argb(30, 74, 91, 216)), null, mask));
        return v;
    }

    /** 「＋」：优先补最小的空号，并按编号插回列表。1/4 存在时新增得到「配置 2」。 */
    private void addConfig() {
        int no = nextFreeNo();
        int pos = 0;
        while (pos < cfgNos.size() && cfgNos.get(pos) < no) pos++;
        cfgNos.add(pos, no);
        cfgNames.add(pos, "配置 " + no);
        saveConfigs();
        renderConfigs();
    }

    /** 当前已用编号之外的第一个空位（从 1 开始）。 */
    private int nextFreeNo() {
        int no = 1;
        while (cfgNos.contains(no)) no++;
        return no;
    }

    private void renameConfig(final int index) {
        if (index < 0 || index >= cfgNames.size()) return;
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(cfgNames.get(index));
        et.setSelection(et.getText().length());
        et.setSingleLine(true);
        et.setTextSize(15);
        et.setPadding(dp(4), dp(10), dp(4), dp(10));

        new MaterialDialog.Builder(this)
                .title("重命名配置")
                .content(et)
                .negative("取消", null)
                .positive("保存", () -> {
                    String n = et.getText().toString().trim().replace('\n', ' ');
                    if (n.isEmpty()) n = "配置 " + (index < cfgNos.size() ? cfgNos.get(index) : index + 1);
                    if (n.length() > 20) n = n.substring(0, 20);
                    cfgNames.set(index, n);
                    saveConfigs();
                    renderConfigs();
                })
                .build().show();
    }

    private void deleteConfig(final int index) {
        if (index < 0 || index >= cfgNames.size()) return;
        new MaterialDialog.Builder(this)
                .title("删除配置")
                .danger(true)
                .message("确定删除「" + cfgNames.get(index) + "」吗？")
                .negative("取消", null)
                .positive("删除", () -> {
                    cfgNames.remove(index);
                    if (index < cfgNos.size()) cfgNos.remove(index);
                    saveConfigs();
                    renderConfigs();
                })
                .build().show();
    }

    private void openMacroTab(int index, int tab) {
        if (!connected) {
            snack("请先连接手柄");
            return;
        }
        Holder.set(ble);
        Intent it = new Intent(this, MacroEditorActivity.class);
        it.putExtra(MacroEditorActivity.EXTRA_PROFILE,
                index < cfgNos.size() ? cfgNos.get(index) : index + 1);
        it.putExtra(MacroEditorActivity.EXTRA_TAB, tab);
        it.putExtra(MacroEditorActivity.EXTRA_NAME, cfgNames.get(index));
        startActivity(it);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void loadConfigs() {
        android.content.SharedPreferences sp = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        cfgNames.clear();
        cfgNos.clear();
        String raw = sp.getString(KEY_CFG_NAMES, "");
        if (raw != null && !raw.isEmpty()) {
            for (String line : raw.split("\n", -1)) {
                if (line.trim().isEmpty()) continue;
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    try {
                        cfgNos.add(Integer.parseInt(line.substring(0, tab).trim()));
                    } catch (NumberFormatException e) {
                        cfgNos.add(cfgNames.size() + 1);
                    }
                    cfgNames.add(line.substring(tab + 1).trim());
                } else {
                    cfgNos.add(cfgNames.size() + 1);
                    cfgNames.add(line.trim());
                }
            }
        }
    }

    private void saveConfigs() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cfgNames.size(); i++) {
            if (sb.length() > 0) sb.append('\n');
            int no = i < cfgNos.size() ? cfgNos.get(i) : i + 1;
            sb.append(no).append('\t').append(cfgNames.get(i).replace('\n', ' '));
        }
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
                .putString(KEY_CFG_NAMES, sb.toString())
                .apply();
    }

    // ---------------- 关于页 ----------------

    private ScrollView buildAboutPage() {
        ScrollView sv = pageScroll();
        LinearLayout col = pageColumn(sv);

        eyebrow(col);
        title(col, "关于");

        LinearLayout verCard = card(col);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        verCard.addView(row);

        LinearLayout iconBox = new LinearLayout(this);
        iconBox.setGravity(Gravity.CENTER);
        GradientDrawable ib = new GradientDrawable();
        ib.setColor(C_PRIMARY_CONT);
        ib.setCornerRadius(dp(18));
        iconBox.setBackground(ib);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_pad);
        icon.setColorFilter(C_PRIMARY);
        iconBox.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        row.addView(iconBox, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout bInfo = new LinearLayout(this);
        bInfo.setOrientation(LinearLayout.VERTICAL);
        bInfo.setPadding(dp(16), 0, 0, 0);
        row.addView(bInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView appName = new TextView(this);
        appName.setText("GMMini");
        appName.setTextSize(15);
        appName.setTextColor(C_ON_VARIANT);
        bInfo.addView(appName);

        TextView ver = new TextView(this);
        ver.setText("版本 " + appVersion());
        ver.setTextSize(22);
        ver.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        ver.setTextColor(C_ON_SURFACE);
        ver.setPadding(0, dp(2), 0, 0);
        bInfo.addView(ver);

        sectionTitle(col, "支持的手柄");
        LinearLayout padCard = card(col);

        // 大产品图：官方原图裁掉四周透明留白后铺满卡片宽度，高度随图片比例自适应
        ImageView hero = new ImageView(this);
        GradientDrawable heroBg = new GradientDrawable();
        heroBg.setColor(0xFFF7F6FC);
        heroBg.setCornerRadius(dp(18));
        hero.setBackground(heroBg);
        hero.setClipToOutline(true);
        hero.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        hero.setScaleType(ImageView.ScaleType.FIT_CENTER);
        hero.setPadding(dp(8), dp(8), dp(8), dp(8));
        hero.setContentDescription("T50 产品图");
        // 高度按屏幕宽度推算（T50 原图去透明边后约 1.55:1），不依赖测量阶段的 adjustViewBounds
        int heroW = Math.max(dp(140), getResources().getDisplayMetrics().widthPixels
                - dp(40) - dp(36) - dp(16));
        int heroH = Math.round(heroW / 1.55f);
        padCard.addView(hero, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heroH));
        PadImages.setSync(hero, PadImages.ALL[0], dp(320), true);

        TextView padName = new TextView(this);
        padName.setText("T50");
        padName.setTextSize(18);
        padName.setTextColor(C_ON_SURFACE);
        padName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        padName.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(12);
        padCard.addView(padName, nameLp);

        View divider = new View(this);
        divider.setBackgroundColor(0xFFF1EFF8);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        divLp.topMargin = dp(12);
        padCard.addView(divider, divLp);

        TextView manual = new TextView(this);
        manual.setText("产品说明书");
        manual.setTextSize(13.5f);
        manual.setTextColor(C_PRIMARY);
        manual.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        manual.setGravity(Gravity.CENTER);
        manual.setPadding(0, dp(13), 0, dp(13));
        manual.setBackground(rowRipple());
        manual.setClickable(true);
        manual.setOnClickListener(v -> openT50Manual());
        padCard.addView(manual, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return sv;
    }

    /** T50 产品说明书：官方 PDF 已内嵌在 assets/manual/t50.pdf，App 内直接阅读。 */
    private void openT50Manual() {
        startActivity(new Intent(this, ManualActivity.class));
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "2.9.3";
        }
    }

    // ---------------- 底部导航 ----------------

    private View buildNavBar() {
        navBar = (NavigationBarView) getLayoutInflater().inflate(R.layout.view_bottom_nav, null);
        navBar.setSelectedItemId(R.id.nav_device);
        navBar.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            selectPage(id == R.id.nav_macro ? 1 : id == R.id.nav_about ? 2 : 0);
            return true;
        });
        return navBar;
    }

    private void selectPage(int index) {
        if (index == currentPage) return;
        final ScrollView out = pageArr[currentPage];
        final ScrollView in = pageArr[index];
        boolean forward = index > currentPage;
        currentPage = index;

        out.animate().alpha(0f).translationY(dp(forward ? -8 : 8))
                .setDuration(110).withEndAction(() -> {
                    out.setVisibility(View.GONE);
                    out.setAlpha(1f);
                    out.setTranslationY(0f);
                }).start();

        in.setVisibility(View.VISIBLE);
        in.setAlpha(0f);
        in.setTranslationY(dp(forward ? 12 : -12));
        in.animate().alpha(1f).translationY(0f)
                .setDuration(220).setInterpolator(new DecelerateInterpolator(1.6f)).start();

        if (navBar != null) {
            int navId = index == 1 ? R.id.nav_macro : index == 2 ? R.id.nav_about : R.id.nav_device;
            if (navBar.getSelectedItemId() != navId) navBar.setSelectedItemId(navId);
        }
    }

    private void animatePageIn(View v) {
        v.setAlpha(0f);
        v.setTranslationY(dp(14));
        v.animate().alpha(1f).translationY(0f)
                .setDuration(260).setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    // ---------------- 通用小部件 ----------------

    private ScrollView pageScroll() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        return sv;
    }

    private LinearLayout pageColumn(ScrollView sv) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(18), dp(20), dp(20));
        sv.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return col;
    }

    private void eyebrow(LinearLayout col) {
        TextView t = addLabel(col, "GMMINI", 11);
        t.setTextColor(C_PRIMARY);
        t.setLetterSpacing(0.18f);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(6), 0, dp(2));
    }

    private void title(LinearLayout col, String text) {
        TextView t = addLabel(col, text, 26);
        t.setTextColor(C_ON_SURFACE);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(2), 0, dp(14));
    }

    private void title(LinearLayout col, String text, String sub) {
        TextView t = addLabel(col, text, 26);
        t.setTextColor(C_ON_SURFACE);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(2), 0, dp(2));
        TextView s = addLabel(col, sub, 13);
        s.setTextColor(C_ON_VARIANT);
        s.setPadding(0, 0, 0, dp(18));
    }

    private LinearLayout card(LinearLayout parent) {
        MaterialCardView mcv = new MaterialCardView(this);
        mcv.setCardBackgroundColor(C_CARD);
        mcv.setRadius(dp(24));
        mcv.setStrokeColor(C_OUTLINE);
        mcv.setStrokeWidth(dp(1));
        mcv.setCardElevation(0);
        mcv.setUseCompatPadding(false);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(14), dp(18), dp(14));
        mcv.addView(box, new MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        parent.addView(mcv, lp);
        return box;
    }

    private void sectionTitle(LinearLayout parent, String title) {
        TextView t = addLabel(parent, title, 15);
        t.setTextColor(C_ON_SURFACE);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(dp(4), dp(14), 0, dp(8));
    }

    private void sectionTitle(LinearLayout parent, String title, String hint) {
        TextView t = addLabel(parent, title, 15);
        t.setTextColor(C_ON_SURFACE);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(dp(4), dp(14), 0, 0);
        TextView h = addLabel(parent, hint, 12);
        h.setTextColor(C_ON_VARIANT);
        h.setPadding(dp(4), 0, 0, dp(8));
    }

    private void addActionRow(LinearLayout parent, View a, View b, View c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View[] views = new View[]{a, b, c};
        for (View v : views) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            lp.setMargins(dp(3), dp(4), dp(3), dp(4));
            row.addView(v, lp);
        }
        parent.addView(row);
    }

    private TextView addInfoText(LinearLayout parent, String text) {
        TextView t = addLabel(parent, text, 12);
        t.setTextColor(C_ON_VARIANT);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    private TextView addLabel(LinearLayout col, String text, float sp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setPadding(0, dp(4), 0, dp(4));
        col.addView(t);
        return t;
    }

    private Button filledBtn(String text, View.OnClickListener l) {
        return pillBtn(text, C_PRIMARY, C_ON_PRIMARY, l);
    }

    private Button tonalBtn(String text, View.OnClickListener l) {
        return pillBtn(text, C_PRIMARY_CONT, C_ON_CONT, l);
    }

    private Button pillBtn(String text, int bgColor, int fgColor, View.OnClickListener l) {
        MaterialButton b = new MaterialButton(this);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(fgColor);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bgColor));
        b.setCornerRadius(dp(26));
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setOnClickListener(l);
        return b;
    }

    /** 工具区小按钮：Tonal 芯片样式 */
    private Button btn(String text, View.OnClickListener l) {
        MaterialButton b = new MaterialButton(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(C_ON_CONT);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(C_TRACK));
        b.setCornerRadius(dp(14));
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setOnClickListener(l);
        return b;
    }

    private android.graphics.drawable.Drawable rowRipple() {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(18));
        return new RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.argb(26, 74, 91, 216)), null, mask);
    }

    /** ScrollView 没有 maxHeight：内容短时自适应，超过上限才滚动。 */
    private static class MaxScrollView extends ScrollView {
        private final int maxHeight;

        MaxScrollView(Context c, int maxHeight) {
            super(c);
            this.maxHeight = maxHeight;
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, heightSpec);
            if (maxHeight > 0 && getMeasuredHeight() > maxHeight) {
                setMeasuredDimension(getMeasuredWidth(), maxHeight);
            }
        }
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ---------------- Snackbar ----------------

    private void snack(String msg) {
        if (rootView == null) return;
        Snackbar sb = Snackbar.make(rootView, msg, Snackbar.LENGTH_SHORT);
        sb.setAnchorView(navBar);
        sb.show();
    }

    // ---------------- 权限 ----------------

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        for (int i = 0; i < r.length; i++)
            if (r[i] != PackageManager.PERMISSION_GRANTED) log("权限被拒绝: " + p[i]);
        ensureBluetoothOn();
        if (rootView != null) rootView.postDelayed(this::tryAutoAttach, 500);
    }

    private void ensureBluetoothOn() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        if (a == null) { log("本机无蓝牙适配器"); return; }
        if (!a.isEnabled()) {
            log("请打开蓝牙");
            try { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); } catch (Exception ignored) {}
        }
    }

    // ---------------- 扫描 / 设备列表 ----------------

    private void doScan() {
        found.clear();
        rssiMap.clear();
        updateDeviceBtn();
        userDisconnected = false;
        scanning = true;
        if (deviceMenu != null && deviceMenu.isShowing()) renderDeviceMenu();
        ble.bonded();
        if (!ble.adapterReady()) {
            scanning = false;
            if (deviceMenu != null && deviceMenu.isShowing()) renderDeviceMenu();
            log("蓝牙未开启");
            ensureBluetoothOn();
            return;
        }
        log("开始扫描（默认列出全部 BLE 设备，15 秒后自动停止）");
        ble.startScan();
        snack("正在扫描附近的蓝牙设备…");
        tvState.postDelayed(() -> {
            if (found.isEmpty())
                log("未发现设备：请确认手柄已开机并进入可发现状态；若手柄已配对，"
                        + "可直接在「设备列表」的已配对分组里选择。");
        }, 15000);
    }

    private void updateDeviceBtn() {
        if (deviceListBtn == null) return;
        deviceListBtn.setText(found.isEmpty() ? "设备列表" : "设备列表 (" + found.size() + ")");
    }

    /** 设备列表：居中弹窗；扫描结果实时刷新，「重新扫描」会真的重扫并把新设备补进列表。 */
    private void showDeviceMenu() {
        if (deviceMenu != null && deviceMenu.isShowing()) return;

        deviceMenuBox = new LinearLayout(this);
        deviceMenuBox.setOrientation(LinearLayout.VERTICAL);

        int maxH = Math.min(dp(340), Math.round(getResources().getDisplayMetrics().heightPixels * 0.44f));
        ScrollView sc = new MaxScrollView(this, maxH);
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(deviceMenuBox);
        sc.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        deviceMenu = new MaterialDialog.Builder(this)
                .title("选择设备")
                .message(menuSummary(0, 0))
                .content(sc)
                .negative("关闭", null)
                .positiveSticky("重新扫描", this::rescanFromMenu)
                .build();
        deviceMenu.show();
        renderDeviceMenu();
    }

    /** 弹窗里的「重新扫描」：清空旧结果重新扫，弹窗不关，结果边扫边进。 */
    private void rescanFromMenu() {
        doScan();
        renderDeviceMenu();
    }

    private String menuSummary(int nearby, int bonded) {
        return "附近 " + nearby + " 台 · 已配对 " + bonded + " 台" + (scanning ? " · 扫描中" : "");
    }

    /** 按当前 found / 已配对列表重建弹窗内容，扫描回调里可安全重复调用。 */
    private void renderDeviceMenu() {
        if (deviceMenuBox == null || deviceMenu == null || !deviceMenu.isShowing()) return;
        if (ble == null) return;

        List<Map.Entry<String, String>> nearby = new ArrayList<>(found.entrySet());
        nearby.sort((a, b) -> {
            boolean ca = BleClient.nameMatches(a.getValue());
            boolean cb = BleClient.nameMatches(b.getValue());
            if (ca != cb) return ca ? -1 : 1;
            return a.getValue().compareToIgnoreCase(b.getValue());
        });

        List<BluetoothDevice> extra = new ArrayList<>();
        for (BluetoothDevice d : ble.bonded()) {
            if (d == null) continue;
            if (!found.containsKey(d.getAddress())) extra.add(d);
        }

        deviceMenuBox.removeAllViews();

        TextView h1 = new TextView(this);
        h1.setText(nearby.isEmpty()
                ? (scanning ? "附近设备（扫描中…）" : "附近设备（暂无，先点「重新扫描」）")
                : "附近设备");
        h1.setTextSize(11);
        h1.setLetterSpacing(0.06f);
        h1.setTextColor(C_PRIMARY);
        h1.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        h1.setPadding(dp(6), dp(6), dp(6), dp(6));
        deviceMenuBox.addView(h1);

        if (nearby.isEmpty()) {
            TextView e = new TextView(this);
            e.setText(scanning ? "正在扫描，手柄出现后会自动列在这里…"
                    : "还没有扫描到设备，点「重新扫描」再试一次");
            e.setTextSize(13);
            e.setTextColor(C_ON_VARIANT);
            e.setPadding(dp(6), dp(4), dp(6), dp(10));
            deviceMenuBox.addView(e);
        }
        for (Map.Entry<String, String> en : nearby) {
            final String mac = en.getKey();
            String name = en.getValue();
            boolean ctrl = BleClient.nameMatches(name);
            Integer rssi = rssiMap.get(mac);
            String sub = ctrl ? "手柄" : "附近设备";
            if (rssi != null) sub += " · 信号 " + rssi + " dBm";
            final String shown = name;
            deviceMenuBox.addView(deviceRow(shown, sub, mac, () -> {
                if (deviceMenu != null) deviceMenu.dismissAnimated();
                pickDevice(shown, mac);
            }));
        }

        if (!extra.isEmpty()) {
            TextView h2 = new TextView(this);
            h2.setText("已配对设备");
            h2.setTextSize(11);
            h2.setLetterSpacing(0.06f);
            h2.setTextColor(C_PRIMARY);
            h2.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            h2.setPadding(dp(6), dp(14), dp(6), dp(6));
            deviceMenuBox.addView(h2);

            for (BluetoothDevice d : extra) {
                String nm = null;
                try { nm = d.getName(); } catch (SecurityException ignored) {}
                if (nm == null || nm.trim().isEmpty()) nm = "未命名设备";
                boolean ctrl = BleClient.nameMatches(nm);
                final String name = nm;
                final String mac = d.getAddress();
                deviceMenuBox.addView(deviceRow(name, ctrl ? "已配对 · 手柄" : "已配对", mac, () -> {
                    if (deviceMenu != null) deviceMenu.dismissAnimated();
                    pickDevice(name, mac);
                }));
            }
        }

        deviceMenu.setMessage(menuSummary(nearby.size(), extra.size()));
        deviceMenuCount = found.size();
    }

    private boolean isCurrent(String mac) {
        BluetoothDevice d = ble == null ? null : ble.getCurrentDevice();
        return d != null && mac != null && mac.equalsIgnoreCase(d.getAddress());
    }

    /** 设备行：识别到型号才显示官方原图，右侧显示当前设备的真实电量。 */
    /** 设备行：只显示蓝牙名称与连接状态，不显示型号、不显示产品图。 */
    private View deviceRow(String name, String sub, String mac, Runnable action) {
        boolean cur = isCurrent(mac);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(11), dp(8), dp(11));
        row.setBackground(rowRipple());
        row.setClickable(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(14);
        t.setTextColor(C_ON_SURFACE);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setIncludeFontPadding(false);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);

        TextView st = new TextView(this);
        st.setText(sub);
        st.setTextSize(11);
        st.setTextColor(C_ON_VARIANT);
        st.setPadding(0, dp(3), 0, 0);
        st.setIncludeFontPadding(false);
        st.setMaxLines(1);
        st.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(st);

        if (cur) {
            // 与头部卡片同一份数据：lastPct / lastCharging 由 0x07 电量帧刷新
            LinearLayout pill = new LinearLayout(this);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setPadding(dp(8), dp(4), dp(9), dp(4));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(C_PRIMARY_CONT);
            bg.setCornerRadius(dp(11));
            pill.setBackground(bg);

            BatteryView bv = new BatteryView(this);
            bv.setAccent(C_PRIMARY);
            if (lastPct >= 0) bv.setLevel(lastPct, lastCharging);
            else bv.setUnknown();
            pill.addView(bv, new LinearLayout.LayoutParams(dp(24), dp(12)));

            TextView b = new TextView(this);
            b.setText(lastPct >= 0 ? lastPct + "%" : "已连接");
            b.setTextSize(12);
            b.setTextColor(lastPct >= 0 ? pctColor(lastPct) : C_PRIMARY);
            b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            b.setIncludeFontPadding(false);
            b.setPadding(dp(5), 0, 0, 0);
            pill.addView(b);

            if (lastCharging) {
                TextView cg = new TextView(this);
                cg.setText("充电");
                cg.setTextSize(9);
                cg.setTextColor(C_PRIMARY);
                cg.setIncludeFontPadding(false);
                cg.setPadding(dp(4), 0, 0, 0);
                pill.addView(cg);
            }

            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.leftMargin = dp(8);
            row.addView(pill, blp);
        }

        row.setOnClickListener(v -> action.run());
        return row;
    }

    private void pickDevice(String name, String mac) {
        userDisconnected = false;
        scanning = false;
        // 广播名只能给出“可能是哪款”，真正型号等 0x84 的 project 号回来再定
        tvDevice.setText(PadImages.displayName(name, null));
        applyPad(PadImages.byBtName(name));
        tvState.setText("正在连接…");
        tvState.setTextColor(C_BLUE);
        showDeviceCard(true, true);
        batteryView.setUnknown();
        tvBattery.setText("—");
        tvChargeTag.setVisibility(View.GONE);
        lastPct = -1;
        log("选择设备 " + name + "  " + mac);
        ble.connect(mac);
    }

    /** 头部卡片的产品图：识别到型号才显示，未知则整块隐藏。 */
    private void applyPad(PadImages.Pad pad) {
        headPad = pad;
        if (pad == null) {
            headPicBox.setVisibility(View.GONE);
            PadImages.clear(headPic);
        } else {
            headPicBox.setVisibility(View.VISIBLE);
            PadImages.into(headPic, pad, Math.round(dp(96)), true);
        }
    }

    private void showDeviceCard(boolean show, boolean animate) {
        if (show) {
            if (deviceBox.getVisibility() != View.VISIBLE) {
                deviceBox.setVisibility(View.VISIBLE);
                if (animate) {
                    deviceBox.setAlpha(0f);
                    deviceBox.setTranslationY(dp(8));
                    deviceBox.animate().alpha(1f).translationY(0f).setDuration(220)
                            .setInterpolator(new DecelerateInterpolator(1.6f)).start();
                }
            }
        } else {
            deviceBox.setVisibility(View.GONE);
        }
    }

    // ---------------- 手柄详情功能 ----------------

    /** 按键测试（官方：手柄详情 → 按键测试）。 */
    private void openKeyTest() {
        if (!connected) { snack("请先连接手柄"); return; }
        Holder.set(ble);
        startActivity(new Intent(this, KeyTestActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    /** 固件版本（官方：手柄详情 → 固件版本）。 */
    private void showFirmware() {
        if (!infoLoaded) qHandleInfo();
        String msg;
        if (!infoLoaded) {
            msg = "正在读取手柄信息…";
        } else {
            msg = "型号　　" + (infoModel.isEmpty() ? "未知" : infoModel)
                    + "\n项目号　" + dash(infoProject)
                    + "\n协议版本 " + dash(infoAgreement)
                    + "\n固件版本 " + dash(infoFw)
                    + "\n硬件版本 " + dash(infoHw);
        }
        fwDialog = new MaterialDialog.Builder(this)
                .title("固件版本")
                .message(msg)
                .positive("知道了", null)
                .build();
        fwDialog.show();
    }

    private static String dash(String s) {
        return (s == null || s.trim().isEmpty()) ? "—" : s;
    }

    /**
     * 陀螺仪校准：官方 GyroscopeCalibrationDialog，点击即弹 60 秒环形倒计时弹窗。
     * 进入发 0x41 让固件采集；固件用 0x43/1 上报 DeviceAlignRsp 后提前收尾，
     * 一直没收到上报才走满 60 秒，按官方「校准超时」处理，不再默认算成功。
     */
    private void showGyroCalib() {
        if (!startCalib(CALIB_GYRO)) return;

        calibRing = new CalibRingView(this);
        LinearLayout content = calibContent();
        content.addView(calibRing, new LinearLayout.LayoutParams(dp(160), dp(160)));
        content.addView(calibText("请保持手柄静止", 15, C_ON_SURFACE, true, dp(16)));
        content.addView(calibText("校准中，请勿移动或触碰手柄", 11.5f, C_ON_VARIANT, false, dp(5)));

        calibDialog = new MaterialDialog.Builder(this)
                .title("陀螺仪校准")
                .content(content)
                .close(this::cancelCalib)
                .cancelable(false)
                .maxWidthDp(320)
                .build();
        calibDialog.setOnKeyListener(calibKeyListener());
        calibDialog.show();
        calibRing.update(60, 0);
        write(Kr.reqGyroCalibStart(), "陀螺仪校准开始");
        startCalibCountdown(60000L);
    }

    /**
     * 摇杆校准：按官方 CalibrateAndTestFragment 流程。点击即发 0x55 [2,0] 开始采集，
     * 弹提示弹窗；同时旋转左右摇杆数圈并按手柄校准键后，固件用 0x57/2 上报
     * DeviceAlignRsp，App 自动收尾并显示结果。右上角 × / 返回键发 0x56 取消，
     * 60 秒没等到上报按「校准超时」处理。官方流程里没有 App 内的步骤按钮。
     */
    private void showStickCalib() {
        if (!startCalib(CALIB_STICK)) return;

        LinearLayout content = calibContent();
        content.addView(new StickCalibView(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
        content.addView(calibText("同时旋转左右摇杆数圈，然后按手柄的 + 按键完成校准。",
                14, C_ON_SURFACE, true, dp(14)));

        calibDialog = new MaterialDialog.Builder(this)
                .title("摇杆校准")
                .content(content)
                .close(this::cancelCalib)
                .cancelable(false)
                .maxWidthDp(320)
                .build();
        calibDialog.setOnKeyListener(calibKeyListener());
        calibDialog.show();
        write(Kr.reqStickCalibStart(2), "摇杆校准开始");
        startCalibCountdown(60000L);
    }

    /** 开始一次校准会话；未连接或已有校准在进行时不重复进入。 */
    private boolean startCalib(int mode) {
        if (calibMode != CALIB_NONE) return false;
        if (ble == null || !ble.isConnected()) {
            snack("请先连接手柄");
            return false;
        }
        calibMode = mode;
        calibDone = false;
        return true;
    }

    /** 两种校准共用的白色内容容器，保证配色和间距一致。 */
    private LinearLayout calibContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(0, 0, 0, dp(8));
        return content;
    }

    private TextView calibText(String s, float size, int color, boolean bold, int topMargin) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        t.setLayoutParams(lp);
        return t;
    }

    /** 超时兜底；陀螺仪同时驱动环形倒计时，摇杆校准只借它计时。 */
    private void startCalibCountdown(long ms) {
        calibTimer = new CountDownTimer(ms, 1000L) {
            @Override public void onTick(long left) {
                if (calibRing == null) return;
                int remain = (int) (left / 1000L);
                calibRing.update(remain, 360 - (int) (360L * remain / 60L));
            }

            @Override public void onFinish() {
                if (calibRing != null) calibRing.update(0, 360);
                finishCalib(false, "校准超时");
            }
        }.start();
    }

    private DialogInterface.OnKeyListener calibKeyListener() {
        return (d, keyCode, ev) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) {
                cancelCalib();
                return true;
            }
            // 官方校准弹窗屏蔽手柄确认键，避免误触把校准中断
            return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == 96 || keyCode == 97;
        };
    }

    /** 上报结果或超时后收尾：发结束帧、关弹窗、提示结果。 */
    private void finishCalib(boolean ok, String msg) {
        if (calibDone) return;
        calibDone = true;
        stopCalibTimer();
        if (calibMode == CALIB_GYRO) {
            write(Kr.reqGyroCalibStop(), "陀螺仪校准结束");
            if (ok) write(Kr.reqGyroCalibComplete(), "陀螺仪校准完成");
        } else if (calibMode == CALIB_STICK) {
            write(Kr.reqStickCalibEnd(2), "摇杆校准结束");
        }
        closeCalibDialog();
        snack(msg);
    }

    /** 右上角 × / 系统返回键：只发结束帧取消，不发完成帧。 */
    private void cancelCalib() {
        if (calibDone || calibMode == CALIB_NONE) return;
        calibDone = true;
        stopCalibTimer();
        if (calibMode == CALIB_GYRO) write(Kr.reqGyroCalibStop(), "取消陀螺仪校准");
        else write(Kr.reqStickCalibEnd(2), "取消摇杆校准");
        closeCalibDialog();
        snack("已取消校准");
    }

    private void stopCalibTimer() {
        if (calibTimer != null) {
            calibTimer.cancel();
            calibTimer = null;
        }
    }

    private void closeCalibDialog() {
        if (calibDialog != null) {
            calibDialog.dismissAnimated();
            calibDialog = null;
        }
        calibMode = CALIB_NONE;
        calibRing = null;
    }

    /** 官方 DeviceAlignRsp 上报：0x43/1 陀螺仪、0x57/2 摇杆。 */
    private void onAlignReport(Kr.AlignReport r) {
        if (calibMode == CALIB_NONE || calibDone || r == null) return;
        if (calibMode == CALIB_GYRO && r.subId != 1) return;
        if (calibMode == CALIB_STICK && r.subId != 2) return;
        if (r.success) {
            finishCalib(true, r.subId == 2 ? "摇杆校准成功" : "陀螺仪校准成功");
            return;
        }
        String reason = r.subId == 2 ? rockerFailText(r.flags) : "手柄未保持静止。";
        finishCalib(false, reason.isEmpty() ? "校准失败" : reason);
    }

    /** 官方 rocker_not_full / rocker_not_center / rocker_not_circle 对应的低三位。 */
    private String rockerFailText(int flags) {
        StringBuilder sb = new StringBuilder();
        if ((flags & 1) != 0) sb.append("摇杆未打满；");
        if ((flags & 2) != 0) sb.append("摇杆未回中；");
        if ((flags & 4) != 0) sb.append("摇杆未转动数圈。");
        return sb.toString();
    }

    /** 恢复出厂模式（官方：手柄详情 → 恢复出厂模式）。 */
    private void showFactoryReset() {
        new MaterialDialog.Builder(this)
                .title("恢复出厂模式")
                .danger(true)
                .message("将清除手柄内的宏、键映射、灯效与校准数据，并恢复默认设置。\n\n"
                        + "此操作不可撤销，确定继续吗？")
                .negative("取消", null)
                .positive("恢复出厂", () -> {
                    write(Kr.reqFactoryReset(), "恢复出厂");
                    snack("已发送恢复出厂指令，手柄可能自动重启");
                })
                .build().show();
    }

    /** 原版「取消配对」：确认后反射 removeBond 直接改系统配对；失败则落到系统蓝牙设备页。 */
    private void showUnpair() {
        BluetoothDevice d = ble == null ? null : ble.getCurrentDevice();
        if (d == null && ble != null) d = ble.findSystemConnected();
        final BluetoothDevice target = d;
        new MaterialDialog.Builder(this)
                .title("取消配对")
                .danger(true)
                .message("是否要取消配对？\n取消后需要重新配对，才能再次连接手柄。")
                .negative("取消", null)
                .positive("确定", () -> {
                    if (target == null) {
                        snack("没有找到要取消配对的设备");
                        openBluetoothSettings(null);
                        return;
                    }
                    // GameMacro（BLE 协议名）与 Pocket Controller（经典 HID 名）是同一只柄，
                    // BleClient 会把同系列、同 MAC 的配对项一起解绑。
                    int cleared = ble != null ? ble.unpair(target) : 0;
                    boolean ok = cleared > 0;
                    if (ok) {
                        userDisconnected = true;
                        connected = false;
                        showDeviceCard(false, false);
                        tvState.setText("未连接");
                        tvState.setTextColor(C_ON_VARIANT);
                        batteryView.setUnknown();
                        tvBattery.setText("—");
                        tvChargeTag.setVisibility(View.GONE);
                        lastPct = -1;
                        lastCharging = false;
                        handleMode = -1;
                        syncModeUi();
                        setDetailEnabled(false, true);
                        snack("已取消配对（GameMacro / Pocket Controller）");
                        log("已取消配对 " + target.getAddress() + "，共清理 " + cleared + " 项");
                        // 系统解绑是异步的：稍后刷新列表，让 Pocket Controller 也从「已配对」里消失
                        ui.postDelayed(() -> {
                            if (ble != null) ble.bonded();
                            if (deviceMenu != null && deviceMenu.isShowing()) renderDeviceMenu();
                        }, 1400);
                    } else {
                        snack("系统未允许直接取消，正在打开蓝牙设置…");
                        openBluetoothSettings(target);
                    }
                })
                .build().show();
    }

    /** 打开系统蓝牙里该设备的详情页（部分 ROM 不认这个 action 时退回蓝牙设置首页）。 */
    private void openBluetoothSettings(BluetoothDevice d) {
        try {
            Intent it = new Intent("android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS");
            if (d != null) it.putExtra("device_address", d.getAddress());
            startActivity(it);
        } catch (Exception e) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (Exception e2) {
                snack("无法打开系统蓝牙设置");
            }
        }
    }

    private void write(byte[] frame, String label) {
        if (ble == null || !ble.isConnected()) { snack("请先连接手柄"); return; }
        ble.write(frame);
        log("→ " + label + " " + Kr.hex(frame));
    }

    // ---------------- 指令 ----------------

    private void qBattery() {
        if (!ble.isConnected()) return;
        byte[] f = Kr.reqBattery();
        ble.write(f);
        log("→ 电量 " + Kr.hex(f));
    }

    private void qHandleInfo() {
        if (!ble.isConnected()) { log("未连接"); return; }
        byte[] f = Kr.reqHandleInfo(0x01);
        ble.write(f);
        log("→ 手柄信息 " + Kr.hex(f));
    }

    private void qHandleMode() {
        if (ble == null || !ble.isConnected()) return;
        byte[] f = Kr.reqHandleMode();
        ble.write(f);
        log("→ 手柄模式 " + Kr.hex(f));
    }

    private void qMacroCap() {
        if (!ble.isConnected()) { snack("请先连接手柄"); return; }
        byte[] f = Kr.reqMacroSupport(1);
        ble.write(f);
        log("→ 宏支持 " + Kr.hex(f));
    }

    private void qMacroKey(int pid) {
        if (!ble.isConnected()) { snack("请先连接手柄"); return; }
        asm.reset();
        asmId = Kr.ID_QUERY_MACRO_SUB;
        byte[] f = Kr.reqMacroSub(pid);
        ble.write(f);
        log("→ 宏列表 pid=" + pid + " " + Kr.hex(f));
    }

    private void qMacroInfo() {
        if (!ble.isConnected()) { snack("请先连接手柄"); return; }
        asm.reset();
        asmId = Kr.ID_QUERY_MACRO_INFO;
        byte[] f = Kr.reqMacroInfo(0x01, 6);
        ble.write(f);
        log("→ 宏信息 " + Kr.hex(f));
    }

    private void openMacroEditor() {
        if (!ble.isConnected()) { snack("请先连接手柄"); return; }
        Holder.set(ble);
        Holder.setSink((f, hex) -> log("← [宏] " + hex));
        startActivity(new Intent(this, MacroEditorActivity.class));
    }

    // ---------------- BleClient.Listener ----------------

    @Override public void onLog(String s) { log(s); }

    /** GATT 回调来自 binder 线程，统一切回主线程再动 View。 */
    @Override public void onState(final String s0) {
        runOnUiThread(() -> handleState(s0 == null ? "" : s0));
    }

    private void handleState(String s) {
        boolean wasConnected = connected;

        if (s.contains("连接中")) {
            showDeviceCard(true, true);
            tvState.setText("正在连接…");
            tvState.setTextColor(C_BLUE);
        } else if (s.contains("通知注册成功")) {
            // 订阅完成：正式进入可操作状态
            connected = true;
            attaching = false;
            showDeviceCard(true, true);
            tvState.setText("已连接");
            tvState.setTextColor(C_GREEN);
            if (!wasConnected) setDetailEnabled(true, true);
        } else if (s.contains("已连接")) {
            // GATT 一建立就点亮详情与宏区，不等通知订阅完成
            connected = true;
            attaching = false;
            showDeviceCard(true, true);
            tvState.setText("已连接 · 同步中");
            tvState.setTextColor(C_GREEN);
            if (!wasConnected) setDetailEnabled(true, true);
        } else if (s.contains("断开") || s.contains("失败")) {
            connected = false;
            attaching = false;
            showDeviceCard(false, false);
            tvState.setText("未连接");
            tvState.setTextColor(C_ON_VARIANT);
            batteryView.setUnknown();
            tvBattery.setText("—");
            tvChargeTag.setVisibility(View.GONE);
            lastPct = -1;
            lastCharging = false;
            handleMode = -1;
            syncModeUi();
            setDetailEnabled(false, true);
        } else if (s.contains("扫描中")) {
            scanning = true;
            updateDeviceBtn();
            if (deviceMenu != null && deviceMenu.isShowing()) renderDeviceMenu();
        } else if (s.contains("扫描结束")) {
            scanning = false;
            updateDeviceBtn();
            if (deviceMenu != null && deviceMenu.isShowing()) renderDeviceMenu();
        }
        log("[state] " + s);

        if (s.contains("通信通知注册成功")) {
            tvState.postDelayed(this::qBattery, 250);
            tvState.postDelayed(this::qHandleInfo, 700);
            tvState.postDelayed(this::qHandleMode, 900);
            tvState.postDelayed(this::qMacroCap, 1200);
            tvState.removeCallbacks(batteryPoll);
            tvState.postDelayed(batteryPoll, 30000);
        } else if (s.contains("断开") || s.contains("失败")) {
            tvState.removeCallbacks(batteryPoll);
        }
    }

    /** 后台静默刷新电量。 */
    private final Runnable batteryPoll = new Runnable() {
        @Override public void run() {
            if (ble != null && ble.isConnected()) {
                qBattery();
                tvState.postDelayed(this, 30000);
            }
        }
    };

    @Override public void onDeviceFound(final String n0, final String mac, final int rssi) {
        runOnUiThread(() -> handleDeviceFound(n0, mac, rssi));
    }

    private void handleDeviceFound(String name, String mac, int rssi) {
        if (name == null || name.trim().isEmpty()) name = "未知设备";
        rssiMap.put(mac, rssi);
        if (found.containsKey(mac)) return;
        found.put(mac, name);
        updateDeviceBtn();
        log("发现设备 " + name + "  " + mac + "  " + rssi + "dBm");
        // 设备列表开着时边扫边补；合并 350ms，避免列表频繁重建
        if (deviceMenu != null && deviceMenu.isShowing() && found.size() != deviceMenuCount) {
            ui.removeCallbacks(menuRefresh);
            ui.postDelayed(menuRefresh, 350);
        }
    }

    @Override public void onFrame(final byte[] f0) {
        runOnUiThread(() -> handleFrame(f0));
    }

    private void handleFrame(byte[] f) {
        log("← " + Kr.hex(f));
        Holder.FrameSink sink = Holder.getSink();
        if (sink != null) sink.onFrame(f, Kr.hex(f));
        if (f.length < 3) return;
        int id = f[1] & 0xff;

        // 主动上报: [len][0x07][subId][data...]
        if (id == 0x07) {
            int sub = f[2] & 0xff;
            if (sub == Kr.ID_QUERY_BATTERY) showBattery(f, 3);
            return;
        }

        // 官方 DeviceAlignRsp 校准结果：0x43/1 陀螺仪、0x57/2 摇杆、0x57/3 扳机
        Kr.AlignReport report = Kr.parseAlignReport(f);
        if (report != null && calibMode != CALIB_NONE) {
            onAlignReport(report);
            return;
        }

        boolean multiCmd = (id == 0x77 || id == Kr.ID_QUERY_MACRO_SUB || id == 0x86);
        if (multiCmd && f.length >= 7) {
            int total = f[3] & 0xff;
            int idx = f[4] & 0xff;
            if (total >= 1 && total <= 64 && idx >= 1 && idx <= total) {
                if (asmId != id) { asm.reset(); asmId = id; }
                boolean done = asm.feed(f);
                log("   多帧 " + idx + "/" + total + (done ? " 完成" : ""));
                if (done) {
                    byte[] all = asm.data();
                    log("   合并 " + all.length + " 字节: " + Kr.hex(all));
                    onPayload(id, all);
                    asm.reset();
                    asmId = -1;
                }
                return;
            }
        }

        switch (id) {
            case Kr.ID_QUERY_BATTERY:
                showBattery(f, 2);
                break;
            case Kr.ID_QUERY_HANDLE_MODE:
                if (f.length >= 4) {
                    handleMode = f[3] & 0xff;
                    log("手柄当前模式 " + handleMode);
                    syncModeUi();
                }
                break;
            case Kr.ID_QUERY_HANDLE_INFO:
                parseHandleInfo(f);
                break;
            default:
                break;
        }
    }

    private void onPayload(int id, byte[] d) {
        switch (id) {
            case 0x86:
                parseMacroSupport(d);
                break;
            case Kr.ID_QUERY_MACRO_SUB:
                parseMacroKeys(d);
                break;
            case 0x77:
                parseMacroInfo(d);
                break;
            default:
                log("   payload(" + Integer.toHexString(id) + ") = " + Kr.hex(d));
                break;
        }
    }

    // ---------------- 解析 ----------------

    private void showBattery(byte[] f, int base) {
        if (f.length < base + 4) {
            log("电量帧过短(" + f.length + "B): " + Kr.hex(f));
            return;
        }
        int showFlag = f[base] & 0xff;
        int charging = f[base + 1] & 0xff;
        int chargeShow = f[base + 2] & 0xff;
        int elec = f[base + 3] & 0xff;
        int low = f.length > base + 4 ? (f[base + 4] & 0xff) : 0;

        int pct = elec;
        if (pct <= 0 || pct > 100) pct = chargeShow;
        if (pct <= 0 || pct > 100) pct = 0;
        lastPct = pct > 0 ? pct : -1;
        lastCharging = charging == 1;

        if (pct > 0) {
            animateBattery(pct, charging == 1);
        } else {
            batteryView.setUnknown();
            tvBattery.setText("—");
            tvBattery.setTextColor(C_ON_VARIANT);
        }

        if (charging == 1) {
            tvChargeTag.setText("充电中");
            tvChargeTag.setTextColor(C_ON_CONT);
            tvChargeTag.setVisibility(View.VISIBLE);
        } else if (low == 1) {
            tvChargeTag.setText("电量低");
            tvChargeTag.setTextColor(C_RED);
            tvChargeTag.setVisibility(View.VISIBLE);
        } else {
            tvChargeTag.setVisibility(View.GONE);
        }

        log("电量 " + (pct > 0 ? pct + "%" : "未知")
                + "  (elec=" + elec + " chargeShow=" + chargeShow
                + " showFlag=" + showFlag + (charging == 1 ? " 充电中" : "")
                + (low == 1 ? " 低电量" : "") + ")");
    }

    /** 电量文字：正常用正文色（图标本身已按 20% 以下转红），低电转红提醒。 */
    private int pctColor(int pct) {
        return pct < 20 ? C_RED : C_ON_SURFACE;
    }

    /** 电量数字平滑过渡，避免每次刷新跳变。 */
    private void animateBattery(final int pct, final boolean charging) {
        lastCharging = charging;
        int cur;
        try {
            cur = Integer.parseInt(tvBattery.getText().toString().replace("%", "").trim());
        } catch (Exception e) {
            cur = pct;
        }
        final int from = cur;
        batteryView.setLevel(pct, charging);
        tvBattery.setTextColor(pctColor(pct));
        if (from == pct) {
            tvBattery.setText(pct + "%");
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(from, pct);
        a.setDuration(420);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(an -> tvBattery.setText(Math.round((float) an.getAnimatedValue()) + "%"));
        a.start();
    }

    private void parseHandleInfo(byte[] f) {
        String hex = Kr.hex(f);
        if (hex.length() < 34) { log("手柄信息帧过短"); return; }
        String project = hex.substring(8, 14);
        String agreement = hex.substring(14, 20);
        String fw = hex.substring(20, 28);
        String hw = hex.substring(28, 34);
        if (f.length > 0x12) project = project + mapTypeByteToChar(f[0x11]);

        infoProject = project;
        infoAgreement = agreement;
        infoFw = fw;
        infoHw = hw;
        infoModel = PadImages.modelOfProject(project);
        infoLoaded = true;

        if (!infoModel.isEmpty()) {
            // project 号命中官方型号：换成官方型号名 + 对应产品原图
            tvDevice.setText(infoModel);
            applyPad(PadImages.byModel(infoModel));
        } else {
            // 未收录的 project：不猜测、不放通用图，保留广播名
            applyPad(PadImages.byBtName(tvDevice.getText().toString()));
        }
        log("手柄信息 project=" + project + " 协议=" + agreement + " 固件=" + fw + " 硬件=" + hw
                + (infoModel.isEmpty() ? "" : "（型号 " + infoModel + "）"));

        if (fwDialog != null && fwDialog.isShowing()) {
            fwDialog.setMessage("型号　　" + (infoModel.isEmpty() ? "未知" : infoModel)
                    + "\n项目号　" + dash(infoProject)
                    + "\n协议版本 " + dash(infoAgreement)
                    + "\n固件版本 " + dash(infoFw)
                    + "\n硬件版本 " + dash(infoHw));
        }
    }

    private String mapTypeByteToChar(byte b) {
        if (b == 0x0A) return "A";
        if (b == 0x0B) return "B";
        return "";
    }

    private void parseMacroSupport(byte[] d) {
        if (d.length < 1) return;
        int support = d[0] & 0xff;
        macroSupport = support;
        StringBuilder sb = new StringBuilder();
        sb.append("宏能力: ").append(support == 1 ? "支持" : "不支持");
        if (support == 1 && d.length > 1) {
            sb.append("   可用按键: ");
            int n = Math.min(d.length - 1, 24);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(", ");
                sb.append(KeyCodes.name(d[1 + i] & 0xff));
            }
            if (d.length - 1 > n) sb.append("…(共").append(d.length - 1).append(")");
        }
        if (tvMacroCap != null) tvMacroCap.setText(sb.toString());
        log(sb.toString());
    }

    private void parseMacroKeys(byte[] d) {
        macroProfiles.clear();
        rawMacro = Kr.hex(d);
        if (d.length <= 7) {
            macroProfiles.add("(该配置下没有宏)");
            refreshMacroList();
            return;
        }
        int i = 0;
        int guard = 0;
        boolean ok = true;
        while (i < d.length) {
            if (guard++ > 64) { macroProfiles.add("…(超过 64 条,已截断)"); break; }
            if (i + 7 > d.length) { ok = false; break; }
            int value = d[i] & 0xff;
            int cycle = d[i + 1] & 0xff;
            long cycleTime = ((long) (d[i + 2] & 0xff) << 24) | ((d[i + 3] & 0xff) << 16)
                    | ((d[i + 4] & 0xff) << 8) | (d[i + 5] & 0xff);
            int childCount = d[i + 6] & 0xff;
            i += 7;
            if (childCount > 32) { ok = false; break; }

            StringBuilder sb = new StringBuilder();
            sb.append("宏 #").append(macroProfiles.size() + 1)
                    .append("  触发键码=").append(value)
                    .append(" 循环=").append(cycle)
                    .append(" 循环时长=").append(cycleTime).append("ms")
                    .append(" 子事件=").append(childCount);
            for (int c = 0; c < childCount; c++) {
                if (i + 6 > d.length) { ok = false; break; }
                int idx = d[i] & 0xff;
                int keep = ((d[i + 1] & 0xff) << 8) | (d[i + 2] & 0xff);
                int interval = ((d[i + 3] & 0xff) << 8) | (d[i + 4] & 0xff);
                int kn = d[i + 5] & 0xff;
                i += 6;
                if (i + kn > d.length) { ok = false; break; }
                StringBuilder keys = new StringBuilder();
                for (int k = 0; k < kn; k++) {
                    if (k > 0) keys.append("+");
                    keys.append(d[i + k] & 0xff);
                }
                i += kn;
                sb.append("\n    #").append(idx).append(" 保持=").append(keep)
                        .append("ms 间隔=").append(interval).append("ms 键=").append(keys);
            }
            if (!ok) break;
            macroProfiles.add(sb.toString());
        }
        if (!ok || macroProfiles.isEmpty()) {
            macroProfiles.clear();
            macroProfiles.add("(结构解析失败,原始 " + d.length + " 字节见下方)");
        }
        refreshMacroList();
    }

    private void parseMacroInfo(byte[] p) {
        try {
            StringBuilder sb = new StringBuilder("宏信息(" + p.length + " 字节)\n");
            int hairLen = u(p, 0);
            int hairs = hairLen / 3;
            sb.append("爆发毛: ").append(hairs == 0 ? "无" : hairs + " 条");
            for (int k = 0; k < hairs; k++) {
                int o = 3 * k + 1;
                sb.append("\n  code=").append(u(p, o))
                        .append(" mode=").append(u(p, o + 1))
                        .append(" speed=").append(u(p, o + 2));
            }

            int tb = hairLen;
            int tLen = u(p, tb + 1);
            sb.append("\n扳机 左: 起=").append(u(p, tb + 2)).append(" 止=").append(u(p, tb + 3))
                    .append(" X ").append(u(p, tb + 4)).append("~").append(u(p, tb + 5))
                    .append(" Y ").append(u(p, tb + 6)).append("~").append(u(p, tb + 7))
                    .append(" 开关=").append(u(p, tb + 8)).append(" 阈值=").append(u(p, tb + 9));
            sb.append("\n扳机 右: 起=").append(u(p, tb + 10)).append(" 止=").append(u(p, tb + 11))
                    .append(" X ").append(u(p, tb + 12)).append("~").append(u(p, tb + 13))
                    .append(" Y ").append(u(p, tb + 14)).append("~").append(u(p, tb + 15))
                    .append(" 开关=").append(u(p, tb + 16)).append(" 阈值=").append(u(p, tb + 17));

            int rb = tb + tLen;
            int rLen = u(p, rb + 2);
            sb.append("\n摇杆 左: X ").append(u(p, rb + 3)).append("~").append(u(p, rb + 4))
                    .append(" (轴 ").append(u(p, rb + 5)).append("~").append(u(p, rb + 6))
                    .append(" 交换=").append(u(p, rb + 7)).append(")")
                    .append(" Y ").append(u(p, rb + 8)).append("~").append(u(p, rb + 9))
                    .append(" (轴 ").append(u(p, rb + 10)).append("~").append(u(p, rb + 11))
                    .append(" 交换=").append(u(p, rb + 12)).append(")");
            sb.append("\n摇杆 右: X ").append(u(p, rb + 13)).append("~").append(u(p, rb + 14))
                    .append(" (轴 ").append(u(p, rb + 15)).append("~").append(u(p, rb + 16))
                    .append(" 交换=").append(u(p, rb + 17)).append(")")
                    .append(" Y ").append(u(p, rb + 18)).append("~").append(u(p, rb + 19))
                    .append(" (轴 ").append(u(p, rb + 20)).append("~").append(u(p, rb + 21))
                    .append(" 交换=").append(u(p, rb + 22)).append(")");

            int vb = rb + rLen;
            int vLen = u(p, vb + 3);
            sb.append("\n振动: 左=").append(u(p, vb + 4)).append("  右=").append(u(p, vb + 5));

            int kb = vb + vLen;
            int sens = (u(p, kb + 5) << 8) | u(p, kb + 6);
            sb.append("\n体感: 灵敏度=").append(sens)
                    .append(" Y轴开关=").append(u(p, kb + 7))
                    .append(" 开关=").append(u(p, kb + 8))
                    .append(" 映射开关=").append(u(p, kb + 9))
                    .append(" 方式=").append(u(p, kb + 10))
                    .append(" 按键=").append(u(p, kb + 11))
                    .append(" 死区=").append(u(p, kb + 12));

            sb.append("\n原始: ").append(Kr.hex(p));
            if (tvMacroInfo != null) tvMacroInfo.setText(sb.toString());
            log("宏信息解析完成");
        } catch (Exception e) {
            if (tvMacroInfo != null) tvMacroInfo.setText("宏信息(" + p.length + " 字节): 解析失败 " + e);
            log("宏信息原始: " + Kr.hex(p));
        }
    }

    private static int u(byte[] a, int i) {
        return (i >= 0 && i < a.length) ? (a[i] & 0xff) : 0;
    }

    private void refreshMacroList() {
        if (tvMacroList == null) return;
        if (macroProfiles.isEmpty()) { tvMacroList.setText("宏列表: —"); return; }
        StringBuilder sb = new StringBuilder("宏列表: 共 ").append(macroProfiles.size()).append(" 条\n");
        for (String s : macroProfiles) sb.append(s).append("\n");
        if (rawMacro != null && !rawMacro.isEmpty())
            sb.append("\n原始数据(").append(rawMacro.length() / 2).append(" 字节):\n").append(rawMacro);
        tvMacroList.setText(sb.toString());
    }

    /** 界面不再展示运行日志，仅写入 logcat 便于排查。 */
    private void log(String s) {
        android.util.Log.d("GMMini", s);
    }
}
