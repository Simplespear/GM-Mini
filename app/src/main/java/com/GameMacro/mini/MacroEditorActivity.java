package com.GameMacro.mini;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.List;

/**
 * 宏配置编辑器 —— 复刻原版 OSeniorMacroActivity 的 5 tab:
 * 连发 / 摇杆 / 映射 / 振动 / 体感;底部 取消 / 应用。
 * 全代码 UI(与主界面一致,零资源依赖)。
 */
public class MacroEditorActivity extends BaseActivity {

    public static final String EXTRA_PROFILE = "profile";
    /** 进入后直接落在哪个 tab：0 连发 / 1 摇杆 / 2 映射 / 3 振动 / 4 体感。 */
    public static final String EXTRA_TAB = "tab";
    /** 气泡里用户起的配置名，作为本页标题。 */
    public static final String EXTRA_NAME = "name";

    private static final int COLOR_TEXT = Color.parseColor("#1B1B21");
    private static final int COLOR_SUB = Color.parseColor("#5C5C70");
    private static final int COLOR_ACCENT = Color.parseColor("#4A5BD8");
    private static final int COLOR_TAB_ON = Color.parseColor("#E0E4FF");

    private BleClient ble;
    private Kr.Reassembler asm;
    private int asmId = -1;

    private TabLayout tabBar;
    private LinearLayout pageBox;      // 当前 tab 内容
    private int curTab = 0;
    private int profile = 1;
    private String cfgName = "";

    private String pendingLog = "";

    // 0x77 多帧重组(宏信息总配置 = 连发+扳机+摇杆+振动+体感)
    private final Kr.Reassembler asm77 = new Kr.Reassembler();
    private final Kr.Reassembler asm86 = new Kr.Reassembler();
    private final Kr.Reassembler asmMap = new Kr.Reassembler();
    private final Kr.Reassembler asmKin = new Kr.Reassembler();
    private boolean got77, got86, gotMap, gotKin;
    private final StringBuilder logBuf = new StringBuilder();
    private static final String TAG = "GMMiniMacro";

    // —— 连发:只开放 A/B/X/Y/L/R/ZL/ZR 八个键,每键 [mode(2关/1单击/0按住), speed(Hz)] ——
    private static final int[] TURBO_CODES = {0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA7, 0xA5, 0xA8};
    private static final String[] TURBO_NAMES = {"A", "B", "X", "Y", "L", "R", "ZL", "ZR"};
    private final List<int[]> burst = new ArrayList<>();
    private final boolean[] turboOpen = new boolean[TURBO_CODES.length];
    private final TextView[] turboHz = new TextView[TURBO_CODES.length];
    private final TextView[][] turboMode = new TextView[TURBO_CODES.length][3];
    private final TextView[] turboVal = new TextView[TURBO_CODES.length];
    private final LinearLayout[] turboPanel = new LinearLayout[TURBO_CODES.length];
    private final Slider[] turboSeek = new Slider[TURBO_CODES.length];
    /** 原版固定 1–20Hz；不再跟随 0x86 回包（部分固件报 5–20）。 */
    private static final int BURST_MIN_HZ = 1;
    private static final int BURST_MAX_HZ = 20;
    // —— 摇杆 ——
    private int rockerLMin = 10, rockerLMax = 100, rockerRMin = 10, rockerRMax = 100;
    private boolean exLx, exLy, exRx, exRy;      // X/Y 轴反向（原版 4 个独立开关）
    private boolean crossL, crossR, swap;         // 十字键交换 / 交换左右摇杆（三者互斥）
    private MaterialSwitch swSwap, swCrossL, swCrossR;
    // —— 摇杆曲线 {x0,y0,x1,y1}，0–100；曲线只写改过的一侧 ——
    private final int[] curveL = {33, 34, 66, 67};
    private final int[] curveR = {33, 34, 66, 67};
    private int curveSide = 0;
    private boolean curveDirtyL = false, curveDirtyR = false;
    private CurveView curveView;
    private MaterialButton curvePresetBtn;
    private static final String[] CURVE_PRESETS = {"默认", "快速", "缓慢"};
    private static final int[][] CURVE_POINTS = {
            {33, 34, 66, 67}, {20, 66, 33, 80}, {66, 20, 80, 33}};
    // —— 映射：{原键, 目标键, 是否被用户改过} ——
    private final List<int[]> mappings = new ArrayList<>();
    private final List<Integer> mapSupport = new ArrayList<>();
    /** 映射页固定顺序：ZL ZR L R - + Y X B A LS RS 上 下 左 右。目标键也用这张表。 */
    private static final int[] MAP_KEYS = {
            0xA5, 0xA8, 0xA4, 0xA7, 0xAA, 0xAB, 0xA3, 0xA2,
            0xA1, 0xA0, 0xA6, 0xA9, 0xD3, 0xD4, 0xD1, 0xD2
    };
    // —— 扳机(0x77 读,展示用) ——
    private int trigLStart, trigLTerm, trigLSw, trigLThr;
    private int trigRStart, trigRTerm, trigRSw, trigRThr;
    // —— 振动 ——
    private int vibL = 0, vibR = 0;
    // —— 体感 ——
    private int kinectSw, kinectMethod, kinectKey = 1, kinectDz, kinectSens, kinectModel = -1;
    private final List<Integer> kinectKeys = new ArrayList<>();

    private TextView tvStatus;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        profile = getIntent() == null ? 1 : getIntent().getIntExtra(EXTRA_PROFILE, 1);
        int initTab = getIntent() == null ? 0 : getIntent().getIntExtra(EXTRA_TAB, 0);
        curTab = (initTab >= 0 && initTab < 5) ? initTab : 0;
        String n = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_NAME);
        cfgName = n == null ? "" : n.trim();
        setContentView(buildUi());

        // 拿到主界面同一个已连接的 BleClient
        ble = Holder.get();
        for (int i = 0; i < TURBO_CODES.length; i++) burst.add(new int[]{2, 10});
        for (int i = 1; i <= 4; i++) kinectKeys.add(i);
        resetMappings();
        showTab(curTab);
        if (ble == null || !ble.isConnected()) {
            Toast.makeText(this, "手柄未连接,请在主界面连接后再进宏配置", Toast.LENGTH_LONG).show();
            status("手柄未连接 —— 无法读取宏信息");
        } else {
            // 注册帧接收:本 Activity 存活期间由 MainActivity.onFrame 喂进来
            Holder.setSink((f, hex) -> onFrame(f));
            queryAll();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (Holder.getSink() == editorSink) Holder.setSink(null);
    }

    /** 本编辑器的 sink 引用(用于退出时判断是否要清掉) */
    private final Holder.FrameSink editorSink = (f, hex) -> onFrame(f);

    // ---------------- 帧接收与解析 ----------------

    private void onFrame(byte[] f) {
        if (f == null || f.length < 2) return;
        int id = f[1] & 0xff;
        if (id == 0x07) return; // 主动上报/心跳,不关心
        final String line = "← [" + Integer.toHexString(id) + "] " + Kr.hex(f);
        logBuf.append(line).append('\n');
        // 写回链:收到该命令的 ACK 后推进下一条
        if (awaitingId == id && f.length >= 3) {
            runOnUiThread(() -> onAck(id));
        }

        // 0x86 多帧：sub=1 连发支持 / sub=2 映射支持 / sub=8 体感触发键
        if (id == Kr.ID_QUERY_MACRO_SUPPORT && f.length >= 7) {
            int sub = f[2] & 0xff;
            int total = f[3] & 0xff;
            int idx = f[4] & 0xff;
            if (total >= 1 && total <= 64 && idx >= 1 && idx <= total) {
                if (sub == 0x01) {
                    if (idx == 1) asm86.reset();
                    if (asm86.feed(f)) {
                        byte[] all = asm86.data();
                        asm86.reset();
                        android.util.Log.i(TAG, "0x86 连发 payload(" + all.length + "): " + Kr.hex(all));
                        got86 = true;
                        runOnUiThread(() -> { if (curTab == 0) showTab(0); });
                    }
                } else if (sub == 0x02) {
                    if (idx == 1) asmMap.reset();
                    if (asmMap.feed(f)) {
                        byte[] all = asmMap.data();
                        asmMap.reset();
                        gotMap = true;
                        runOnUiThread(() -> {
                            mergeMapSupport(all);
                            if (curTab == 2) showTab(2);
                        });
                    }
                } else if (sub == 0x08) {
                    if (idx == 1) asmKin.reset();
                    if (asmKin.feed(f)) {
                        byte[] all = asmKin.data();
                        asmKin.reset();
                        gotKin = true;
                        runOnUiThread(() -> {
                            mergeKinectKeys(all);
                            if (curTab == 4) showTab(4);
                        });
                    }
                }
            }
            return;
        }

        // 0x77 宏信息(多帧): [len][0x77][sub=3][total][idx][payload...][SN]
        if (id == Kr.ID_QUERY_MACRO_INFO && f.length >= 7 && (f[2] & 0xff) == 0x03) {
            int total = f[3] & 0xff;
            int idx = f[4] & 0xff;
            if (total >= 1 && total <= 64 && idx >= 1 && idx <= total) {
                if (asm77.isDone() || idx == 1) {
                    if (idx == 1) asm77.reset();
                }
                boolean done = asm77.feed(f);
                if (done) {
                    byte[] all = asm77.data();
                    asm77.reset();
                    got77 = true;
                    android.util.Log.i(TAG, "0x77 payload(" + all.length + "): " + Kr.hex(all));
                    runOnUiThread(() -> { parseMacroInfo(all); status("宏信息已读取(" + all.length + " 字节)"); });
                }
            }
            return;
        }
        // 0x62 摇杆(单帧): p[2]=lMin p[3]=lMax p[4]=exLx p[5]=exLy p[6]=rMin p[7]=rMax
        if (id == Kr.ID_QUERY_MACRO_ROCKER && f.length >= 8) {
            final int lMin = f[2] & 0xff, lMax = f[3] & 0xff, rMin = f[6] & 0xff, rMax = f[7] & 0xff;
            runOnUiThread(() -> { rockerLMin = lMin; rockerLMax = lMax; rockerRMin = rMin; rockerRMax = rMax; showTab(curTab); });
            return;
        }
        // 0x64 振动(单帧): p[2]=左 p[3]=右
        if (id == Kr.ID_QUERY_VIBRATE_SETTING && f.length >= 4) {
            final int l = f[2] & 0xff, r = f[3] & 0xff;
            runOnUiThread(() -> { vibL = l; vibR = r; showTab(curTab); });
            return;
        }
    }

    /**
     * 0x77 宏信息总配置解析(照抄 QueryMacroHandleReq.handleResponse 实证偏移,b53 蓝本):
     * [0]=连发块长 L(每 3 字节 code/mode/speed,从 [1] 起);
     * trigger 块:基址 L,块长=[L+1];startLeft=[L+2] termLeft=[L+3] swLeft=[L+4] thrLeft=[L+5] cntLeft=[L+6] 点表[L+7..];
     *   右块基址 v6=L+7+cntLeft*2:startRight=[v6+1] term=[v6+2] sw=[v6+3] thr=[v6+4] cnt=[v6+5] 点表[v6+6..];
     * rocker 块:基址 rb=trigger 块末(=L+trigger块长),块长=[rb+2];[rb+3]=三态标志(1=crossLeft 2=crossRight 3=swap);
     *   X起[rb+4] X止[rb+5] X敏[rb+6] X交L[rb+7] Y起[rb+8] Y止[rb+9] Y敏[rb+10] Y交L[rb+11]
     *   X起R[rb+12] X止R[rb+13] X敏R[rb+14] X交R[rb+15] Y起R[rb+16] Y止R[rb+17] Y敏R[rb+18] Y交R[rb+19]
     *   dzL[rb+20] maxL[rb+21] curveApplyL[rb+22] curveKeyL[rb+23] lineCorrL[rb+24] cntL[rb+25] 点表[rb+26..];
     *   右半基址 v6=rb+26+cntL*2:dzR=[v6+1] maxR=[v6+2] curveApplyR=[v6+3] curveKeyR=[v6+4] lineCorrR=[v6+5] cntR=[v6+6] 点表[v6+7..];
     * vibration 块:基址 vb=rocker 块末,块长=[vb+3];vibLeft=[vb+4] vibRight=[vb+5];
     * kinect 块:基址 kb=vb+振动块长;sens=([kb+5]<<8)|[kb+6](大端);Yaxis[+7] sw[+8] mapperSw[+9]
     *   method[+10] key[+11] deathZone[+12] mapperModel[+13]。
     */
    private void parseMacroInfo(byte[] p) {
        try {
            int L = u(p, 0);
            int hairs = L / 3;
            for (int k = 0; k < hairs; k++) {
                int o = 3 * k + 1;
                int code = u(p, o), mode = u(p, o + 1), speed = u(p, o + 2);
                int ki = turboIndex(code);
                if (ki >= 0 && mode >= 0 && mode <= 2) {
                    burst.get(ki)[0] = mode;
                    burst.get(ki)[1] = Math.max(BURST_MIN_HZ, Math.min(BURST_MAX_HZ, Math.max(1, speed)));
                }
            }
            // trigger 块
            // 官方 QueryMacroHandleReq.handleResponse 基准：
            //   i18 = L+6, i22 = i18 + 2*cntL  →  右块 start = b[L+7+2cntL]
            // 1.5 写成 L+7+2cnt 作为"基址"再 +1 读取，整体多偏 1 字节。
            int tb = L;
            int tLen = u(p, tb + 1);
            int cntTL = u(p, tb + 6);
            int v6 = tb + 6 + cntTL * 2;
            trigLSw = u(p, tb + 4);
            trigLStart = u(p, tb + 2);   trigLTerm = u(p, tb + 3);   trigLThr = u(p, tb + 5);
            trigRSw = u(p, v6 + 3);
            trigRStart = u(p, v6 + 1);   trigRTerm = u(p, v6 + 2);   trigRThr = u(p, v6 + 4);
            // rocker 块
            int rb = tb + tLen;
            int rLen = u(p, rb + 2);
            int flag = u(p, rb + 3);
            crossL = flag == 1; crossR = flag == 2; swap = flag == 3;
            rockerLMin = u(p, rb + 4);  rockerLMax = u(p, rb + 5);
            exLx = u(p, rb + 7) == 1;
            exLy = u(p, rb + 11) == 1;
            rockerRMin = u(p, rb + 12); rockerRMax = u(p, rb + 13);
            exRx = u(p, rb + 15) == 1;
            exRy = u(p, rb + 19) == 1;
            int cntRL = u(p, rb + 25);
            readCurve(curveL, p, rb + 26, cntRL);
            int v6r = rb + 26 + cntRL * 2;   // 右块起点（官方 v6=rb+26+cntL*2）
            int cntRR = u(p, v6r + 6);
            readCurve(curveR, p, v6r + 7, cntRR);
            // vibration 块
            int vb = rb + rLen;
            int vLen = u(p, vb + 3);
            vibL = u(p, vb + 4);
            vibR = u(p, vb + 5);
            // kinect 块
            int kb = vb + vLen;
            kinectSens = Math.min(1000, (u(p, kb + 5) << 8) | u(p, kb + 6));
            kinectSw = u(p, kb + 8);
            kinectMethod = u(p, kb + 10);
            kinectKey = u(p, kb + 11);
            kinectDz = u(p, kb + 12);
            kinectModel = u(p, kb + 9) == 1 ? u(p, kb + 13) : -1;
            if (kinectModel > 3) kinectModel = 0;
            if (kinectKey > 0 && !kinectKeys.contains(kinectKey)) kinectKeys.add(kinectKey);
            showTab(curTab);
        } catch (Exception ignored) {}
    }

    /** 读曲线点表：2 个点，每点 x,y；越界或数值不合理就保持默认。 */
    private void readCurve(int[] dst, byte[] p, int off, int cnt) {
        if (cnt < 2) return;
        int x0 = u(p, off), y0 = u(p, off + 1), x1 = u(p, off + 2), y1 = u(p, off + 3);
        if (x0 > 100 || y0 > 100 || x1 > 100 || y1 > 100) return;
        if (x0 > x1 || y0 > y1) return;
        if (x0 == 0 && y0 == 0 && x1 == 0 && y1 == 0) return;
        dst[0] = x0; dst[1] = y0; dst[2] = x1; dst[3] = y1;
    }

    private int turboIndex(int code) {
        for (int i = 0; i < TURBO_CODES.length; i++) if (TURBO_CODES[i] == (code & 0xff)) return i;
        return -1;
    }

    private void resetMappings() {
        mergeMappings();
    }

    /** 0x86/sub=2 回包：payload[0]=能力位(bit0 手柄) + 键码数组。 */
    private void mergeMapSupport(byte[] all) {
        if (all == null || all.length < 6) return;
        if ((all[0] & 0x01) == 0) return;      // 不支持手柄映射就保留默认表
        List<Integer> keys = new ArrayList<>();
        for (int i = 1; i < all.length; i++) {
            int c = all[i] & 0xff;
            if (c != 0 && c != 0xff && !keys.contains(c)) keys.add(c);
        }
        if (keys.size() < 4) return;
        mapSupport.clear();
        mapSupport.addAll(keys);
        mergeMappings();
    }

    /** 按固定顺序重建映射表，保留用户已经改过的目标键。 */
    private void mergeMappings() {
        java.util.Map<Integer, Integer> dirty = new java.util.HashMap<>();
        for (int[] m : mappings) if (m[2] == 1) dirty.put(m[0], m[1]);
        mappings.clear();
        for (int code : MAP_KEYS) {
            Integer t = dirty.get(code);
            mappings.add(new int[]{code, t == null ? code : t, t == null ? 0 : 1});
        }
    }

    /** 0x86/sub=8 回包：体感触发键码数组。 */
    private void mergeKinectKeys(byte[] all) {
        if (all == null || all.length < 1) return;
        List<Integer> keys = new ArrayList<>();
        for (byte b : all) {
            int c = b & 0xff;
            if (c != 0 && c != 0xff && !keys.contains(c)) keys.add(c);
        }
        if (keys.isEmpty() || keys.size() > 8) return;
        kinectKeys.clear();
        kinectKeys.addAll(keys);
        if (!kinectKeys.contains(kinectKey)) kinectKey = kinectKeys.get(0);
    }

    private static int u(byte[] a, int i) {
        return (i >= 0 && i < a.length) ? (a[i] & 0xff) : 0;
    }

    // ---------------- UI ----------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F6F5FB"));

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle(cfgName.isEmpty() ? "配置" + profile : cfgName);
        toolbar.setSubtitle("宏配置");
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setNavigationContentDescription("返回");
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setBackgroundColor(Color.parseColor("#F6F5FB"));
        root.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        // 5 个 tab：Material 3 TabLayout
        tabBar = new TabLayout(this);
        tabBar.setTabMode(TabLayout.MODE_FIXED);
        tabBar.setTabGravity(TabLayout.GRAVITY_FILL);
        tabBar.setTabIndicatorFullWidth(false);
        tabBar.setSelectedTabIndicatorColor(COLOR_ACCENT);
        tabBar.setBackgroundColor(Color.parseColor("#F6F5FB"));
        tabBar.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(dp(10), 0, dp(10), dp(2));
        root.addView(tabBar, tlp);
        String[] names = {"连发", "摇杆", "映射", "振动", "体感"};
        for (String n : names) tabBar.addTab(tabBar.newTab().setText(n));
        tabBar.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { showTab(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        // 状态行
        tvStatus = new TextView(this);
        tvStatus.setTextSize(11);
        tvStatus.setTextColor(COLOR_SUB);
        tvStatus.setGravity(Gravity.CENTER_VERTICAL);
        tvStatus.setPadding(dp(20), 0, dp(20), 0);
        tvStatus.setHeight(dp(18));
        root.addView(tvStatus);

        // 内容区
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        pageBox = new LinearLayout(this);
        pageBox.setOrientation(LinearLayout.VERTICAL);
        pageBox.setPadding(dp(14), dp(6), dp(14), dp(10));
        sv.addView(pageBox);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部 取消/应用
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(14), dp(8), dp(14), dp(14));
        bottom.setBackgroundColor(Color.parseColor("#F6F5FB"));
        MaterialButton cancel = mkBtn("取消", false);
        MaterialButton apply = mkBtn("应用", true);
        apply.setOnClickListener(v -> doApply());
        cancel.setOnClickListener(v -> finish());
        bottom.addView(cancel, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp2.leftMargin = dp(10);
        bottom.addView(apply, lp2);
        root.addView(bottom);
        return root;
    }

    private void showTab(int idx) {
        curTab = idx;
        if (tabBar != null && tabBar.getSelectedTabPosition() != idx) {
            TabLayout.Tab t = tabBar.getTabAt(idx);
            if (t != null) tabBar.selectTab(t);
            return;   // selectTab 会回调本方法，避免重复构建
        }
        pageBox.removeAllViews();
        if (idx == 0) buildBurstPage();
        else if (idx == 1) buildRockerPage();
        else if (idx == 2) buildMappingPage();
        else if (idx == 3) buildVibPage();
        else buildKinectPage();
    }

    private android.graphics.drawable.Drawable mkTabBg(int color) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(16));
        return bg;
    }

    private MaterialButton mkBtn(String text, boolean filled) {
        MaterialButton b = new MaterialButton(this, null,
                filled ? com.google.android.material.R.attr.materialButtonStyle
                       : com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setCornerRadius(dp(23));
        b.setInsetTop(0);
        b.setInsetBottom(0);
        if (!filled) b.setTextColor(COLOR_SUB);
        return b;
    }

    private LinearLayout card() {
        MaterialCardView mcv = new MaterialCardView(this);
        mcv.setCardBackgroundColor(Color.WHITE);
        mcv.setRadius(dp(18));
        mcv.setStrokeColor(Color.parseColor("#E8E6F0"));
        mcv.setStrokeWidth(dp(1));
        mcv.setCardElevation(0);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        mcv.addView(box, new MaterialCardView.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        pageBox.addView(mcv, lp);
        return box;
    }

    private TextView label(LinearLayout parent, String text, int color, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        parent.addView(t);
        return t;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ---------------- 连发页 ----------------

    private void buildBurstPage() {
        label(pageBox, "连发设置", COLOR_TEXT, 16, true);
        label(pageBox, "点右侧频率调 " + BURST_MIN_HZ + "–" + BURST_MAX_HZ + "Hz", COLOR_SUB, 11, false);

        LinearLayout card = card();
        card.setPadding(dp(12), dp(4), dp(12), dp(6));
        for (int i = 0; i < TURBO_CODES.length; i++) {
            if (i > 0) {
                View line = new View(this);
                line.setBackgroundColor(0xFFF1EFF8);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
                llp.leftMargin = dp(2);
                card.addView(line, llp);
            }
            card.addView(buildTurboRow(i));
        }
    }

    /** 一行连发:键名 + [关闭|单击|按住] + 频率/保存胶囊;展开后在下方出 1~20Hz 滑杆。 */
    private View buildTurboRow(final int i) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(2), dp(7), dp(2), dp(7));

        TextView key = new TextView(this);
        key.setText(TURBO_NAMES[i]);
        key.setTextSize(14.5f);
        key.setTextColor(COLOR_TEXT);
        key.setTypeface(null, Typeface.BOLD);
        line.addView(key, new LinearLayout.LayoutParams(dp(36), LinearLayout.LayoutParams.WRAP_CONTENT));

        // 三态分段控件
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable segBg = new GradientDrawable();
        segBg.setColor(0xFFF4F3FA);
        segBg.setCornerRadius(dp(10));
        seg.setBackground(segBg);
        seg.setPadding(dp(2), dp(2), dp(2), dp(2));

        String[] modes = {"关闭", "单击", "按住"};
        for (int m = 0; m < 3; m++) {
            final int mi = m;
            TextView mb = new TextView(this);
            mb.setText(modes[m]);
            mb.setTextSize(11.5f);
            mb.setGravity(Gravity.CENTER);
            mb.setOnClickListener(v -> {
                burst.get(i)[0] = 2 - mi;   // 原版 radio1/2/3 → auto=2/1/0
                refreshBurstRow(i);
            });
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(40), dp(26));
            if (m > 0) mlp.leftMargin = dp(2);
            seg.addView(mb, mlp);
            turboMode[i][m] = mb;
        }
        line.addView(seg, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        View spacer = new View(this);
        line.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView hz = new TextView(this);
        hz.setTextSize(11.5f);
        hz.setTypeface(null, Typeface.BOLD);
        hz.setGravity(Gravity.CENTER);
        hz.setOnClickListener(v -> {
            boolean open = !turboOpen[i];
            for (int k = 0; k < turboOpen.length; k++) turboOpen[k] = false;   // 一次只展开一行
            turboOpen[i] = open;
            for (int k = 0; k < turboOpen.length; k++) refreshBurstRow(k);
        });
        line.addView(hz, new LinearLayout.LayoutParams(dp(56), dp(30)));
        turboHz[i] = hz;

        // 展开区:滑杆 + 当前值
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(36), 0, dp(2), dp(8));

        Slider sb = new Slider(this);
        sb.setValueFrom(BURST_MIN_HZ);
        sb.setValueTo(BURST_MAX_HZ);
        sb.setStepSize(1);
        sb.setValue(Math.max(BURST_MIN_HZ, Math.min(BURST_MAX_HZ, burst.get(i)[1])));
        sb.addOnChangeListener((slider, value, fromUser) -> {
            if (!fromUser) return;
            int v = Math.max(BURST_MIN_HZ, Math.min(BURST_MAX_HZ, (int) value));
            burst.get(i)[1] = v;
            if (turboVal[i] != null) turboVal[i].setText(v + "HZ");
            if (turboHz[i] != null && !turboOpen[i]) turboHz[i].setText(v + "HZ");
        });
        panel.addView(sb, new LinearLayout.LayoutParams(0, dp(48), 1f));

        TextView val = new TextView(this);
        val.setTextSize(11.5f);
        val.setTextColor(COLOR_ACCENT);
        val.setTypeface(null, Typeface.BOLD);
        val.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        panel.addView(val, new LinearLayout.LayoutParams(dp(48), dp(38)));
        turboVal[i] = val;
        turboSeek[i] = sb;
        turboPanel[i] = panel;

        box.addView(line);
        box.addView(panel);
        refreshBurstRow(i);
        return box;
    }

    private void refreshBurstRow(int i) {
        if (i < 0 || i >= TURBO_CODES.length) return;
        int mode = burst.get(i)[0];
        int speed = Math.max(BURST_MIN_HZ, Math.min(BURST_MAX_HZ, burst.get(i)[1]));
        burst.get(i)[1] = speed;

        if (turboMode[i] != null) {
            for (int m = 0; m < 3; m++) {
                TextView tv = turboMode[i][m];
                if (tv == null) continue;
                boolean on = (2 - m) == mode;
                tv.setTextColor(on ? Color.WHITE : COLOR_SUB);
                tv.setBackground(mkRound(on ? COLOR_ACCENT : Color.TRANSPARENT, 8));
            }
        }
        boolean open = turboOpen[i];
        TextView hz = turboHz[i];
        if (hz != null) {
            hz.setText(open ? "保存" : speed + "HZ");
            hz.setTextColor(open ? Color.WHITE : (mode == 2 ? COLOR_SUB : COLOR_ACCENT));
            hz.setBackground(mkRound(open ? COLOR_ACCENT
                    : (mode == 2 ? 0xFFF1F0F7 : COLOR_TAB_ON), 15));
        }
        if (turboVal[i] != null) turboVal[i].setText(speed + "HZ");
        if (turboPanel[i] != null) turboPanel[i].setVisibility(open ? View.VISIBLE : View.GONE);
        if (turboSeek[i] != null && (int) turboSeek[i].getValue() != speed) {
            turboSeek[i].setValue(speed);
        }
    }

    private android.graphics.drawable.Drawable mkRound(int color, int radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radiusDp));
        return bg;
    }


    // ---------------- 摇杆页 ----------------

    private void buildRockerPage() {
        label(pageBox, "摇杆设置", COLOR_TEXT, 16, true);
        label(pageBox, "拖动两端设定左/右摇杆的起点与终点", COLOR_SUB, 11, false);

        rangeCard("左摇杆", true);
        rangeCard("右摇杆", false);

        buildCurveCard();

        LinearLayout card = card();
        addDualSwitchRow(card, "X 轴反向", exLx, exRx, (which, on) -> {
            if (which == 0) exLx = on; else exRx = on;
        });
        divider(card);
        addDualSwitchRow(card, "Y 轴反向", exLy, exRy, (which, on) -> {
            if (which == 0) exLy = on; else exRy = on;
        });
        divider(card);
        swSwap = addSwitchRow(card, "交换左右摇杆", swap, on -> {
            swap = on;
            if (on) { crossL = false; crossR = false; }
            syncExchangeSwitches();
        });
        divider(card);
        MaterialSwitch[] cs = addDualSwitchRow(card, "十字键交换", crossL, crossR, (which, on) -> {
            if (which == 0) crossL = on; else crossR = on;
            if (on) {
                swap = false;
                if (which == 0) crossR = false; else crossL = false;
            }
            syncExchangeSwitches();
        });
        swCrossL = cs[0];
        swCrossR = cs[1];
    }

    /** 一张卡：标题 + 当前范围 + 双滑块。 */
    private void rangeCard(String name, boolean left) {
        LinearLayout card = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(2), 0, dp(2), 0);

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(14);
        title.setTextColor(COLOR_TEXT);
        title.setTypeface(null, Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(this);
        value.setTextSize(13);
        value.setTextColor(COLOR_ACCENT);
        value.setTypeface(null, Typeface.BOLD);
        int lo = left ? rockerLMin : rockerRMin;
        int hi = left ? rockerLMax : rockerRMax;
        value.setText(lo + " – " + hi);
        head.addView(value);
        card.addView(head);

        DualRangeBar bar = new DualRangeBar(this);
        bar.setRange(0, 100, lo, hi);
        bar.setOnRangeChanged((a, b) -> {
            if (left) { rockerLMin = a; rockerLMax = b; }
            else { rockerRMin = a; rockerRMax = b; }
            value.setText(a + " – " + b);
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        blp.topMargin = dp(4);
        card.addView(bar, blp);
    }

    private void syncExchangeSwitches() {
        if (swSwap != null) swSwap.setChecked(swap);
        if (swCrossL != null) swCrossL.setChecked(crossL);
        if (swCrossR != null) swCrossR.setChecked(crossR);
    }

    // ---------------- 摇杆曲线 ----------------

    /** 原版摇杆页的曲线编辑器：左/右摇杆切换 + 预设下拉 + 两个可拖动的控制点。 */
    private void buildCurveCard() {
        LinearLayout card = card();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(2), 0, dp(2), 0);

        TextView title = new TextView(this);
        title.setText("摇杆曲线");
        title.setTextSize(14);
        title.setTextColor(COLOR_TEXT);
        title.setTypeface(null, Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        curvePresetBtn = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        curvePresetBtn.setTextSize(12);
        curvePresetBtn.setAllCaps(false);
        curvePresetBtn.setCornerRadius(dp(9));
        curvePresetBtn.setInsetTop(0);
        curvePresetBtn.setInsetBottom(0);
        curvePresetBtn.setMinWidth(0);
        curvePresetBtn.setMinimumWidth(dp(78));
        curvePresetBtn.setPadding(dp(6), 0, dp(6), 0);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
        head.addView(curvePresetBtn, plp);
        card.addView(head);

        label(card, "拖动两个圆点调整响应曲线，横轴为物理行程、纵轴为输出行程",
                COLOR_SUB, 11, false).setPadding(dp(2), dp(2), 0, dp(4));

        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        slp.topMargin = dp(6);
        card.addView(segment(new String[]{"左摇杆", "右摇杆"}, curveSide, i -> {
            curveSide = i;
            refreshCurveCard();
        }), slp);

        curveView = new CurveView(this);
        curveView.setOnCurveChanged((x0, y0, x1, y1) -> {
            int[] dst = curveSide == 0 ? curveL : curveR;
            dst[0] = x0; dst[1] = y0; dst[2] = x1; dst[3] = y1;
            if (curveSide == 0) curveDirtyL = true; else curveDirtyR = true;
            updateCurvePresetText();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(320));
        clp.topMargin = dp(2);
        card.addView(curveView, clp);

        label(card, "默认 (33,34)(66,67) · 快速 (20,66)(33,80) · 缓慢 (66,20)(80,33)",
                COLOR_SUB, 10, false).setPadding(dp(2), dp(6), 0, dp(2));

        curvePresetBtn.setOnClickListener(this::showCurvePresetMenu);
        refreshCurveCard();
    }

    private void refreshCurveCard() {
        int[] pts = curveSide == 0 ? curveL : curveR;
        if (curveView != null) curveView.setPoints(pts);
        updateCurvePresetText();
    }

    private void updateCurvePresetText() {
        if (curvePresetBtn == null) return;
        int[] pts = curveSide == 0 ? curveL : curveR;
        curvePresetBtn.setText(curvePresetName(pts) + "  ▾");
    }

    private String curvePresetName(int[] pts) {
        for (int i = 0; i < CURVE_POINTS.length; i++) {
            int[] p = CURVE_POINTS[i];
            if (p[0] == pts[0] && p[1] == pts[1] && p[2] == pts[2] && p[3] == pts[3]) {
                return CURVE_PRESETS[i];
            }
        }
        return "自定义";
    }

    private void showCurvePresetMenu(View anchor) {
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, anchor);
        for (int i = 0; i < CURVE_PRESETS.length; i++) {
            pm.getMenu().add(0, i, i, CURVE_PRESETS[i]);
        }
        pm.setOnMenuItemClickListener(item -> {
            int[] src = CURVE_POINTS[item.getItemId() % CURVE_POINTS.length];
            int[] dst = curveSide == 0 ? curveL : curveR;
            System.arraycopy(src, 0, dst, 0, 4);
            if (curveSide == 0) curveDirtyL = true; else curveDirtyR = true;
            refreshCurveCard();
            return true;
        });
        pm.show();
    }

    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(0xFFF1EFF8);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        parent.addView(line, lp);
    }

    private MaterialSwitch addSwitchRow(LinearLayout parent, String title, boolean checked,
                                      java.util.function.Consumer<Boolean> cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(6), dp(2), dp(6));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(COLOR_TEXT);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        MaterialSwitch sw = new MaterialSwitch(this);
        sw.setChecked(checked);
        sw.setOnClickListener(v -> cb.accept(sw.isChecked()));
        row.addView(sw, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(v -> {
            boolean on = !sw.isChecked();
            sw.setChecked(on);
            cb.accept(on);
        });
        parent.addView(row);
        return sw;
    }

    private interface DualSwitch {
        void onChanged(int which, boolean on);
    }

    /** 一行两个开关：标题 + L 开关 + R 开关（原版轴反向 / 十字键交换）。 */
    private MaterialSwitch[] addDualSwitchRow(LinearLayout parent, String title,
                                            boolean l, boolean r, DualSwitch cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(5), dp(2), dp(5));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(COLOR_TEXT);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView ll = new TextView(this);
        ll.setText("L");
        ll.setTextSize(11);
        ll.setTextColor(COLOR_SUB);
        row.addView(ll);

        MaterialSwitch sl = new MaterialSwitch(this);
        sl.setChecked(l);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(4);
        row.addView(sl, slp);
        sl.setOnClickListener(v -> cb.onChanged(0, sl.isChecked()));

        TextView rl = new TextView(this);
        rl.setText("R");
        rl.setTextSize(11);
        rl.setTextColor(COLOR_SUB);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(10);
        row.addView(rl, rlp);

        MaterialSwitch sr = new MaterialSwitch(this);
        sr.setChecked(r);
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        srp.leftMargin = dp(4);
        row.addView(sr, srp);
        sr.setOnClickListener(v -> cb.onChanged(1, sr.isChecked()));

        parent.addView(row);
        return new MaterialSwitch[]{sl, sr};
    }

    // ---------------- 映射页 ----------------

    private void buildMappingPage() {
        label(pageBox, "按键映射", COLOR_TEXT, 16, true);
        label(pageBox, "点一下按键，选择它要变成哪个键", COLOR_SUB, 11, false);

        LinearLayout card = card();
        card.setPadding(dp(9), dp(9), dp(9), dp(9));
        if (mappings.isEmpty()) {
            label(card, "设备未上报可映射按键", COLOR_SUB, 13, false);
            return;
        }
        for (int i = 0; i < mappings.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(8);
            card.addView(row, rlp);

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(48), 1f);
            row.addView(mapCell(i), clp);
            if (i + 1 < mappings.size()) {
                LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(0, dp(48), 1f);
                c2.leftMargin = dp(8);
                row.addView(mapCell(i + 1), c2);
            } else {
                row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }
    }

    private View mapCell(int index) {
        final int[] m = mappings.get(index);
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(6), 0, dp(6), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0xFFE8E6F0);
        cell.setBackground(bg);

        Chip src = keyChip(KeyCodes.name(m[0]), false);
        // Chip 会自己消费触摸（不冒泡到整行），所以直接把点击挂到 Chip 上
        src.setOnClickListener(v -> showTargetPicker(index));
        cell.addView(src, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView arrow = new TextView(this);
        arrow.setText("→");
        arrow.setTextSize(12);
        arrow.setTextColor(0xFFB0AEC0);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(6);
        alp.rightMargin = dp(6);
        cell.addView(arrow, alp);

        Chip dst = keyChip(KeyCodes.name(m[1]), true);
        dst.setOnClickListener(v -> showTargetPicker(index));
        cell.addView(dst, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        cell.setOnClickListener(v -> showTargetPicker(index));
        return cell;
    }

    private Chip keyChip(String name, boolean accent) {
        Chip c = new Chip(this);
        c.setText(name);
        c.setTextSize(12.5f);
        c.setGravity(Gravity.CENTER);
        c.setTypeface(null, Typeface.BOLD);
        c.setSingleLine(true);
        c.setCheckable(false);
        c.setEnsureMinTouchTargetSize(false);
        c.setChipMinHeight(dp(30));
        c.setChipCornerRadius(dp(8));
        c.setChipStrokeWidth(0);
        c.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                accent ? COLOR_TAB_ON : 0xFFF3F2F9));
        c.setTextColor(accent ? COLOR_ACCENT : COLOR_TEXT);
        return c;
    }

    /** 映射目标候选：和源键同一张表、同一顺序。 */
    private java.util.List<Integer> targetKeys() {
        java.util.List<Integer> out = new ArrayList<>();
        for (int c : MAP_KEYS) out.add(c);
        return out;
    }

    /** 目标键选择弹窗：三列按键网格。 */
    private void showTargetPicker(final int index) {
        if (index < 0 || index >= mappings.size()) return;
        final int[] m = mappings.get(index);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(2), dp(4), dp(2), dp(4));

        final java.util.List<Integer> targets = targetKeys();
        LinearLayout row = null;
        final MaterialDialog[] holder = new MaterialDialog[1];
        for (int i = 0; i < targets.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(6);
                grid.addView(row, rlp);
            }
            final int code = targets.get(i);
            Chip chip = keyChip(KeyCodes.name(code), code == m[1]);
            chip.setTextSize(13);
            chip.setChipMinHeight(dp(44));
            chip.setOnClickListener(v -> {
                m[1] = code;
                m[2] = 1;
                if (holder[0] != null) holder[0].dismissAnimated();
                showTab(2);
                Toast.makeText(this, KeyCodes.name(m[0]) + " → " + KeyCodes.name(code),
                        Toast.LENGTH_SHORT).show();
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            clp.leftMargin = dp(3);
            clp.rightMargin = dp(3);
            row.addView(chip, clp);
        }
        // 最后一行补空位
        if (row != null) {
            int pad = (3 - (targets.size() % 3)) % 3;
            for (int i = 0; i < pad; i++) {
                row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }

        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(grid);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(300));
        sc.setLayoutParams(slp);

        holder[0] = new MaterialDialog.Builder(this)
                .title("将 " + KeyCodes.name(m[0]) + " 映射为")
                .content(sc)
                .negative("取消", null)
                .build();
        holder[0].show();
    }

    // ---------------- 振动页 ----------------

    private void buildVibPage() {
        label(pageBox, "振动设置", COLOR_TEXT, 16, true);

        LinearLayout card = card();
        addVibLevelRow(card, "左振动", true);
        divider(card);
        addVibLevelRow(card, "右振动", false);
        label(card, "档位随「应用」写入手柄", COLOR_SUB, 10, false)
                .setPadding(dp(2), dp(8), 0, dp(2));

        MaterialButton test = mkBtn("试振", true);
        test.setOnClickListener(v -> {
            vibrateTest();
            if (ble != null && ble.isConnected()) {
                Toast.makeText(this, "已发送试振", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        tlp.topMargin = dp(10);
        pageBox.addView(test, tlp);
    }

    /** 原版振动页：四颗星 + 减/加，点一下立刻试振。 */
    private void addVibLevelRow(LinearLayout parent, String name, boolean left) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(6), dp(2), dp(6));

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(14);
        title.setTextColor(COLOR_TEXT);
        row.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        MaterialButton minus = vibStepBtn("−");
        row.addView(minus, new LinearLayout.LayoutParams(dp(34), dp(34)));

        final int[] level = {vibLevel(left ? vibL : vibR)};
        final TextView[] stars = new TextView[4];
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView star = new TextView(this);
            star.setText("★");
            star.setTextSize(19);
            star.setGravity(Gravity.CENTER);
            star.setPadding(0, 0, 0, 0);
            star.setOnClickListener(v -> {
                level[0] = idx + 1;
                commitVibLevel(left, level[0]);
                for (int k = 0; k < 4; k++) {
                    stars[k].setTextColor(k < level[0] ? COLOR_ACCENT : 0xFFD9D7E4);
                }
            });
            stars[i] = star;
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(26), dp(36));
            if (i == 0) slp.leftMargin = dp(8);
            row.addView(star, slp);
        }

        MaterialButton plus = vibStepBtn("+");
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(34), dp(34));
        plp.leftMargin = dp(6);
        row.addView(plus, plp);

        final Runnable paint = () -> {
            for (int k = 0; k < 4; k++) {
                stars[k].setTextColor(k < level[0] ? COLOR_ACCENT : 0xFFD9D7E4);
            }
            minus.setEnabled(level[0] > 0);
            plus.setEnabled(level[0] < 4);
            minus.setAlpha(level[0] > 0 ? 1f : 0.38f);
            plus.setAlpha(level[0] < 4 ? 1f : 0.38f);
        };

        minus.setOnClickListener(v -> {
            if (level[0] <= 0) return;
            level[0]--;
            commitVibLevel(left, level[0]);
            paint.run();
        });
        plus.setOnClickListener(v -> {
            if (level[0] >= 4) return;
            level[0]++;
            commitVibLevel(left, level[0]);
            paint.run();
        });
        paint.run();
        parent.addView(row);
    }

    private MaterialButton vibStepBtn(String text) {
        MaterialButton b = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setCornerRadius(dp(17));
        return b;
    }

    private int vibLevel(int v) {
        if (v <= 0) return 0;
        if (v <= 25) return 1;
        if (v <= 50) return 2;
        if (v <= 75) return 3;
        return 4;
    }

    private void commitVibLevel(boolean left, int level) {
        if (left) vibL = level * 25; else vibR = level * 25;
        vibrateTest();
    }

    /** 试振动：0x45 content=[左,右,3]，强度 0–255（百分比 ×2.55）。 */
    private void vibrateTest() {
        if (ble == null || !ble.isConnected()) {
            Toast.makeText(this, "未连接，无法试振", Toast.LENGTH_SHORT).show();
            return;
        }
        ble.write(Kr.vibrationTest(Math.round(vibL * 2.55f), Math.round(vibR * 2.55f)));
    }

    // ---------------- 体感页 ----------------

    private static final String[] KINECT_METHODS = {"持续", "单击", "按住"};
    private static final String[] KINECT_MODES = {"关闭", "十字键", "左摇杆", "右摇杆", "鼠标"};

    private void buildKinectPage() {
        label(pageBox, "体感设置", COLOR_TEXT, 16, true);

        LinearLayout card = card();
        addSwitchRow(card, "体感", kinectSw == 1, on -> kinectSw = on ? 1 : 0);
        divider(card);

        label(card, "体感触发键", COLOR_TEXT, 14, true).setPadding(dp(2), dp(4), 0, 0);
        buildKinectKeyChips(card);

        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        mlp.topMargin = dp(12);
        card.addView(segment(KINECT_METHODS, kinectMethod, idx -> {
            kinectMethod = idx;
            showTab(4);
        }), mlp);

        divider(card);
        seekBlock(card, "体感死区", kinectDz, 100, "%",
                v -> kinectDz = v);
        seekBlock(card, "体感灵敏度", Math.min(kinectSens, 1000), 1000, "",
                v -> kinectSens = v);

        divider(card);
        TextView t = label(card, "体感映射", COLOR_TEXT, 14, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(6), 0, dp(2));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(8), 0, 0);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(8), 0, 0);
        for (int i = 0; i < KINECT_MODES.length; i++) {
            final int mode = i - 1;   // -1=关闭, 0=十字键 …
            Chip chip = modeChip(KINECT_MODES[i], kinectModel == mode);
            chip.setOnClickListener(v -> {
                kinectModel = mode;
                showTab(4);
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(36), 1f);
            clp.leftMargin = dp(3);
            clp.rightMargin = dp(3);
            if (i < 3) row1.addView(chip, clp); else row2.addView(chip, clp);
        }
        int pad = (3 - (KINECT_MODES.length % 3)) % 3;
        for (int i = 0; i < pad; i++) {
            row2.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        card.addView(row1);
        card.addView(row2);
    }

    private void buildKinectKeyChips(LinearLayout parent) {
        LinearLayout row = null;
        for (int i = 0; i < kinectKeys.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp(8);
                parent.addView(row, rlp);
            }
            final int code = kinectKeys.get(i);
            Chip chip = modeChip(kinectKeyName(code), code == kinectKey);
            chip.setOnClickListener(v -> {
                kinectKey = code;
                showTab(4);
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(36), 1f);
            clp.leftMargin = dp(3);
            clp.rightMargin = dp(3);
            row.addView(chip, clp);
        }
    }

    private String kinectKeyName(int code) {
        if (code >= 1 && code <= 4) return "M" + code;
        return KeyCodes.name(code);
    }

    private Chip modeChip(String text, boolean on) {
        Chip chip = new Chip(this);
        chip.setText(text);
        chip.setTextSize(12.5f);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setCheckable(false);
        chip.setEnsureMinTouchTargetSize(false);
        chip.setChipMinHeight(dp(30));
        chip.setChipCornerRadius(dp(10));
        chip.setChipStrokeWidth(0);
        chip.setTextColor(on ? Color.WHITE : COLOR_SUB);
        chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                on ? COLOR_ACCENT : 0xFFF3F2F9));
        return chip;
    }

    /** 分段控件：Material 3 的 MaterialButtonToggleGroup。 */
    private View segment(String[] names, int selected, java.util.function.IntConsumer cb) {
        MaterialButtonToggleGroup group = new MaterialButtonToggleGroup(this);
        group.setSingleSelection(true);
        group.setSelectionRequired(true);
        group.setBackgroundColor(0xFFF4F3FA);
        group.setPadding(dp(2), dp(2), dp(2), dp(2));
        for (int i = 0; i < names.length; i++) {
            MaterialButton b = new MaterialButton(this, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setId(View.generateViewId());
            b.setText(names[i]);
            b.setTextSize(12);
            b.setAllCaps(false);
            b.setCheckable(true);
            b.setCornerRadius(dp(8));
            b.setInsetTop(0);
            b.setInsetBottom(0);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    dp(34), 1f);
            if (i > 0) lp.leftMargin = dp(2);
            group.addView(b, lp);
        }
        group.check(group.getChildAt(Math.max(0, Math.min(names.length - 1, selected))).getId());
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (g.getChildAt(i).getId() == checkedId) {
                    cb.accept(i);
                    break;
                }
            }
        });
        return group;
    }

    /** 标题 + 右侧数值气泡 + 滑杆。 */
    private void seekBlock(LinearLayout parent, String title, int value, int max,
                           String unit, java.util.function.IntConsumer cb) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(2), dp(10), dp(2), 0);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(COLOR_TEXT);
        head.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView v = new TextView(this);
        v.setText(value + unit);
        v.setTextSize(12);
        v.setTypeface(null, Typeface.BOLD);
        v.setTextColor(COLOR_ACCENT);
        v.setGravity(Gravity.CENTER);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(COLOR_TAB_ON);
        pill.setCornerRadius(dp(9));
        v.setBackground(pill);
        v.setPadding(dp(10), dp(4), dp(10), dp(4));
        head.addView(v);
        parent.addView(head);

        Slider sb = new Slider(this);
        sb.setValueFrom(0);
        sb.setValueTo(max);
        sb.setStepSize(1);
        sb.setValue(Math.max(0, Math.min(max, value)));
        sb.addOnChangeListener((slider, val, fromUser) -> {
            int p = (int) val;
            cb.accept(p);
            v.setText(p + unit);
        });
        parent.addView(sb, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
    }

    // ---------------- 读写 ----------------

    private void queryAll() {
        status("查询设备当前配置…");
        got77 = false;
        got86 = false;
        gotMap = false;
        gotKin = false;
        // 照抄原版:0x77 拿连发+扳机+摇杆+振动+体感,pid 必须是当前配置编号,否则读到的不是这一格
        send(Kr.reqMacroHandle(profile), Kr.ID_QUERY_MACRO_INFO, "读取当前配置(0x77)");
        // 0x86 三类支持表: 1 连发键 / 2 映射键 / 8 体感触发键
        // 四条连续写容易把 BLE 栈顶掉，隔 130ms 逐条发
        sendLater(Kr.reqBurstSupport(1), "读取按键支持", 130);
        sendLater(Kr.reqMapperSupport(profile), "读取映射按键", 260);
        sendLater(Kr.reqKinectKeySupport(profile), "读取体感触发键", 390);
        // 手柄刚连上时第一条查询容易丢,1.2s / 2.6s 各补一次
        tvStatus.postDelayed(() -> retryQuery(0), 1200);
        tvStatus.postDelayed(() -> retryQuery(1), 2600);
    }

    private void retryQuery(int round) {
        if (isFinishing() || isDestroyed()) return;
        if (ble == null || !ble.isConnected()) return;
        if (!got77) {
            send(Kr.reqMacroHandle(profile), Kr.ID_QUERY_MACRO_INFO,
                    "宏总配置查询(重试" + (round + 1) + ")");
        }
        long d = 0;
        if (!got86) {
            sendLater(Kr.reqBurstSupport(1), "读取按键支持(重试" + (round + 1) + ")", d);
            d += 130;
        }
        if (!gotMap) {
            sendLater(Kr.reqMapperSupport(profile), "读取映射按键(重试" + (round + 1) + ")", d);
            d += 130;
        }
        if (!gotKin) {
            sendLater(Kr.reqKinectKeySupport(profile), "读取体感触发键(重试" + (round + 1) + ")", d);
        }
    }

    /** 写回链(照抄原版 macroPushMacro):0x36(1,0) 进入宏配置 → 各 set 命令逐条串行(每条等回包或超时)→ 0x34 收尾。 */
    private void doApply() {
        if (ble == null || !ble.isConnected()) {
            Toast.makeText(this, "未连接", Toast.LENGTH_SHORT).show();
            return;
        }
        status("写回配置…");
        java.util.List<Object[]> chain = new ArrayList<>();  // {frame, expectId, what}
        // type0: setMacroPlatform(1,0) —— 进入宏配置模式(必须)
        chain.add(new Object[]{Kr.setMacroPlatform(), Kr.ID_SET_MACRO_PLATFORM, "进入宏配置"});
        // type3: 连发 —— 照抄 setMacroBurst:auto==2(关闭)跳过;0/1(按住/单击)都发
        for (int i = 0; i < TURBO_CODES.length; i++) {
            int[] v = burst.get(i);
            if (v[0] != 2) {
                chain.add(new Object[]{Kr.setBurstRate(TURBO_CODES[i], v[0], v[1]),
                        Kr.ID_SET_MACRO_BURST_RATE, "连发 " + TURBO_NAMES[i]});
            }
        }
        // type4: 按键映射(0x3d) —— 只写用户改过的项，避免覆盖原配置
        for (int[] m : mappings) {
            if (m[2] != 1) continue;
            chain.add(new Object[]{Kr.setKeyMapping(m[0], m[1]),
                    Kr.ID_SET_MACRO_KEY_MAPPING, "映射 " + KeyCodes.name(m[0]) + "→" + KeyCodes.name(m[1])});
        }
        // type4: 摇杆交换(原版也是用 0x3d 交换两个中心键)
        if (swap) {
            chain.add(new Object[]{Kr.setKeyMapping(0xE0, 0xF0), Kr.ID_SET_MACRO_KEY_MAPPING, "交换左右摇杆 1/2"});
            chain.add(new Object[]{Kr.setKeyMapping(0xF0, 0xE0), Kr.ID_SET_MACRO_KEY_MAPPING, "交换左右摇杆 2/2"});
        } else if (crossL) {
            chain.add(new Object[]{Kr.setKeyMapping(0xD0, 0xE0), Kr.ID_SET_MACRO_KEY_MAPPING, "十字键交换左 1/2"});
            chain.add(new Object[]{Kr.setKeyMapping(0xE0, 0xD0), Kr.ID_SET_MACRO_KEY_MAPPING, "十字键交换左 2/2"});
        } else if (crossR) {
            chain.add(new Object[]{Kr.setKeyMapping(0xD0, 0xF0), Kr.ID_SET_MACRO_KEY_MAPPING, "十字键交换右 1/2"});
            chain.add(new Object[]{Kr.setKeyMapping(0xF0, 0xD0), Kr.ID_SET_MACRO_KEY_MAPPING, "十字键交换右 2/2"});
        }
        // type5: 摇杆线性(setMacroRockerLinear) —— 2/3/6/7 是 X/Y 轴反向位
        chain.add(new Object[]{Kr.setRockerLinear(rockerLMin, rockerLMax, exLx, exLy,
                        rockerRMin, rockerRMax, exRx, exRy),
                Kr.ID_SET_ROCKER_LINEAR, "摇杆线性"});
        // type6: 摇杆曲线点(0x3f) —— 左右各自只在用户改过时写回
        if (curveDirtyL) {
            chain.add(new Object[]{Kr.setRockerCurve(13, curveL),
                    Kr.ID_SET_ROCKER_POINT, "左摇杆曲线"});
        }
        if (curveDirtyR) {
            chain.add(new Object[]{Kr.setRockerCurve(14, curveR),
                    Kr.ID_SET_ROCKER_POINT, "右摇杆曲线"});
        }
        // type9: 振动(setVibrationLevel)
        chain.add(new Object[]{Kr.setVibration(vibL, vibR), Kr.ID_SET_VIBRATION_LEVEL, "振动强度"});
        // type0xc: 体感(setKinectConfigReq)
        chain.add(new Object[]{Kr.setKinect(kinectSw, kinectModel >= 0 ? 1 : 0, kinectMethod,
                        kinectKey, kinectDz, kinectSens, Math.max(0, kinectModel)),
                Kr.ID_SET_KINECT_CONFIG, "体感"});
        // type0xd: setMacroProfileEnd —— 退出宏配置模式(必须,否则不生效)
        chain.add(new Object[]{Kr.setProfileEnd(), Kr.ID_SET_MACRO_PROFILE_END, "应用收尾"});
        pushChain(chain, 0);
    }

    /** 串行发送(照抄原版 macroPushMacro 回调链):第 i 条发出后等该 id 的 ACK 回包,收到才发下一条;超时也继续。 */
    private java.util.List<Object[]> chainRef;
    private int chainIdx;
    private int awaitingId = -1;
    private int chainToken = 0;

    private void pushChain(java.util.List<Object[]> chain, int i) {
        chainRef = chain;
        chainIdx = i;
        final int token = ++chainToken;
        if (i >= chain.size()) {
            awaitingId = -1;
            status("已应用 ✓");
            Toast.makeText(this, "配置已写回设备", Toast.LENGTH_SHORT).show();
            return;
        }
        Object[] step = chain.get(i);
        byte[] frame = (byte[]) step[0];
        int expectId = (Integer) step[1];
        String what = (String) step[2];
        status("正在写入 " + (i + 1) + "/" + chain.size());
        awaitingId = expectId;
        ble.write(frame);
        // 超时兜底:900ms 内没收到 ACK 也继续下一条(token 防止旧超时误推进,连发场景多条 0x37 同 id)
        tvStatus.postDelayed(() -> {
            if (token == chainToken && awaitingId == expectId) {
                awaitingId = -1;
                pushChain(chain, i + 1);
            }
        }, 900);
    }

    /** 收到 ACK(回包 id 匹配)后推进下一条 */
    private void onAck(int id) {
        if (awaitingId == id) {
            awaitingId = -1;
            tvStatus.postDelayed(() -> pushChain(chainRef, chainIdx + 1), 80);
        }
    }

    private void sendLater(byte[] frame, String what, long delayMs) {
        tvStatus.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) send(frame, Kr.ID_QUERY_MACRO_SUPPORT, what);
        }, delayMs);
    }

    private void send(byte[] frame, int expectId, String what) {
        if (ble == null || !ble.isConnected()) {
            status("未连接,跳过 " + what);
            return;
        }
        pendingLog += what + " → " + Kr.hex(frame) + "\n";
        ble.write(frame);
    }

    private final Runnable clearStatus = () -> {
        if (tvStatus != null) tvStatus.setText("");
    };

    /** 状态行只短暂显示用户可读的进度，不再常驻原始帧十六进制。 */
    private void status(String s) {
        runOnUiThread(() -> {
            if (tvStatus == null) return;
            tvStatus.setText(s);
            tvStatus.removeCallbacks(clearStatus);
            tvStatus.postDelayed(clearStatus, 2800);
        });
    }
}
