package com.GameMacro.mini;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 按键测试 —— 复刻官方 GameMacro「手柄详情 → 按键测试」布局，浅色 Material 风格。
 *
 * 两路上报并存，谁有数据用谁：
 *   ① 私有通道（官方 CMD_TEST_MODE = 0x07），帧 [len][cmd][LX][LY][RX][RY][K0..K3][L2][R2][K4][SN]
 *      进入测试模式后固件会屏蔽标准 HID，B / Home 只上报不触发系统动作。
 *   ② Android 输入事件（KeyEvent / GenericMotionEvent），作为固件不开私有上报时的兜底。
 *      来自手柄的 BACK / MODE 一律消费掉，只点亮图标，不会退出页面或回桌面。
 *
 * 官方把 6 个命令号都映射到 TestModeEventRsp，不同型号走不同的一条，这里全监听。
 */
public class KeyTestActivity extends AppCompatActivity {

    // 官方 KeyEvent 位
    private static final long B_A = 1L, B_B = 2L, B_X = 4L, B_Y = 8L;
    private static final long B_LEFT = 64L, B_RIGHT = 128L, B_UP = 256L, B_DOWN = 512L;
    private static final long B_SELECT = 1024L, B_START = 2048L;
    private static final long B_L1 = 4096L, B_L2 = 8192L, B_L3 = 16384L;
    private static final long B_R1 = 32768L, B_R2 = 65536L, B_R3 = 131072L;
    private static final long B_BACK = 1048576L, B_MENU = 2097152L, B_HOME = 8388608L;

    private static final int[] TEST_CMDS = {0x07, 0x08, 0x0E, 0x13, 0x17, 0x40};

    private PadView pad;
    private BleClient ble;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long bleFrames = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(0xFFF6F5FB);
        w.setNavigationBarColor(0xFFF6F5FB);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        ble = Holder.get();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFF6F5FB);
        pad = new PadView(this);
        root.addView(pad, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        pad.setAlpha(0f);
        pad.animate().alpha(1f).setDuration(200).start();

        pad.onBack = this::finish;

        if (ble == null || !ble.isConnected()) {
            pad.setHint("手柄未连接", "回到设备页连接后才能测试按键");
        } else {
            Holder.setSink(this::onFrame);
            startPrivateReport();
        }
    }

    /**
     * 官方做法：把固件切到 BETA 工作模式，之后才会推 TestModeEventRsp。
     * 四个候选值对应 {经典/普通} × {新/老固件}，逐个试，收到帧就停。
     */
    private static final int[][] MODES = {{194, 196}, {130, 132}, {66, 68}, {2, 4}};
    private int modeTry = -1;
    private int modeOk = -1;

    private void startPrivateReport() {
        nextMode();
    }

    private void nextMode() {
        modeTry++;
        if (modeTry >= MODES.length) return;
        if (ble != null && ble.isConnected()) ble.write(Kr.setKeyBoardMode(MODES[modeTry][0]));
        final int cur = modeTry;
        ui.postDelayed(() -> {
            if (modeTry == cur && bleFrames == 0) nextMode();
        }, 700);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        Holder.setSink(null);
        ui.removeCallbacksAndMessages(null);
        // 切回宏模式（官方退出测试页同样回到 MACRO_SCREEN）
        if (ble != null && ble.isConnected()) {
            int back = modeOk >= 0 ? MODES[modeOk][1] : 196;
            ble.write(Kr.setKeyBoardMode(back));
        }
    }

    // ---------------- 私有帧 ----------------

    private void onFrame(byte[] f, String hex) {
        if (f == null || f.length < 14) return;
        int cmd = f[1] & 0xff;
        boolean ok = false;
        for (int c : TEST_CMDS) if (c == cmd) { ok = true; break; }
        if (!ok) return;
        if (f.length >= 15 && (f[2] & 0xff) == Kr.ID_QUERY_BATTERY) return;

        final long mask = (long) (f[6] & 0xff)
                | ((long) (f[7] & 0xff) << 8)
                | ((long) (f[8] & 0xff) << 16)
                | ((long) (f[9] & 0xff) << 24)
                | ((long) (f[12] & 0xff) << 32);
        final int vLx = f[2] & 0xff, vLy = f[3] & 0xff;
        final int vRx = f[4] & 0xff, vRy = f[5] & 0xff;
        final int vL2 = f[10] & 0xff, vR2 = f[11] & 0xff;

        runOnUiThread(() -> {
            if (bleFrames == 0) modeOk = modeTry;
            bleFrames++;
            pad.setBle(mask, vLx, vLy, vRx, vRy, vL2, vR2);
        });
    }

    // ---------------- 输入事件兜底 ----------------

    private static boolean fromPad(KeyEvent e) {
        int s = e.getSource();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private static long bitForKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: return B_A;
            case KeyEvent.KEYCODE_BUTTON_B: return B_B;
            case KeyEvent.KEYCODE_BUTTON_X: return B_X;
            case KeyEvent.KEYCODE_BUTTON_Y: return B_Y;
            case KeyEvent.KEYCODE_BUTTON_L1: return B_L1;
            case KeyEvent.KEYCODE_BUTTON_R1: return B_R1;
            case KeyEvent.KEYCODE_BUTTON_L2: return B_L2;
            case KeyEvent.KEYCODE_BUTTON_R2: return B_R2;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return B_L3;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return B_R3;
            case KeyEvent.KEYCODE_BUTTON_START: return B_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return B_SELECT;
            case KeyEvent.KEYCODE_BUTTON_MODE: return B_HOME;
            case KeyEvent.KEYCODE_DPAD_UP: return B_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return B_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return B_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return B_RIGHT;
            // 手柄的「B / 返回」与「Home」在 HID 层就是 BACK / HOME
            case KeyEvent.KEYCODE_BACK: return B_B;
            case KeyEvent.KEYCODE_HOME: return B_HOME;
            default: return 0;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        boolean fromGamepad = fromPad(e);
        long bit = bitForKey(e.getKeyCode());

        // 手柄的「B = 返回」和「Home = 回桌面」只点亮，绝不交给系统
        if (fromGamepad && (e.getKeyCode() == KeyEvent.KEYCODE_BACK
                || e.getKeyCode() == KeyEvent.KEYCODE_HOME)) {
            if (bit != 0) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    if (e.getRepeatCount() == 0) {
                        pad.press(bit, true);
                    }
                } else if (e.getAction() == KeyEvent.ACTION_UP) {
                    pad.press(bit, false);
                }
            }
            return true;
        }

        if (fromGamepad && bit != 0) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    pad.press(bit, true);
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                pad.press(bit, false);
            }
            return true;
        }

        // 不是手柄来的返回键，正常退出
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        int s = e.getSource();
        if ((s & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
                && (s & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) {
            return super.onGenericMotionEvent(e);
        }
        if (e.getAction() != MotionEvent.ACTION_MOVE) return super.onGenericMotionEvent(e);

        float ax = e.getAxisValue(MotionEvent.AXIS_X);
        float ay = e.getAxisValue(MotionEvent.AXIS_Y);
        float az = e.getAxisValue(MotionEvent.AXIS_Z);
        float rz = e.getAxisValue(MotionEvent.AXIS_RZ);
        if (az == 0f && rz == 0f) {
            float rx = e.getAxisValue(MotionEvent.AXIS_RX);
            float ry = e.getAxisValue(MotionEvent.AXIS_RY);
            if (rx != 0f || ry != 0f) { az = rx; rz = ry; }
        }
        float lt = e.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        float rt = e.getAxisValue(MotionEvent.AXIS_RTRIGGER);
        float hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y);

        pad.setHid(ax, ay, az, rz, lt, rt, hatX, hatY);
        return true;
    }

    // ---------------- 自绘面板 ----------------

    private static class PadView extends View {

        private static final float DW = 511f, DH = 960f;

        private static final int C_BG      = 0xFFF4F3FA;
        private static final int C_SURFACE = 0xFFFFFFFF;
        private static final int C_LINE    = 0xFFC9C4D8;
        private static final int C_PRIMARY = 0xFF4A5BD8;
        private static final int C_TEXT    = 0xFF1B1B21;
        private static final int C_SUB     = 0xFF6B6B80;

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private final Path path = new Path();

        private float scale = 1f, ox = 0f, oy = 0f;

        private long bleMask;
        private int lx = 128, ly = 128, rx = 128, ry = 128, l2 = 0, r2 = 0;
        private boolean hasBle;

        private long hidMask;
        private float hLx, hLy, hRx, hRy;
        private int hL2, hR2;

        private String hintTitle, hintSub;
        Runnable onBack;

        private static class Chip {
            final float x, y, r;
            final String label;
            final long bit;
            final int arrow;
            final int icon;   // 0=文字 1=房子(Home) 2=相机(截图)

            Chip(float x, float y, float r, String label, long bit, int arrow, int icon) {
                this.x = x; this.y = y; this.r = r;
                this.label = label; this.bit = bit; this.arrow = arrow; this.icon = icon;
            }

            Chip(float x, float y, float r, String label, long bit, int arrow) {
                this(x, y, r, label, bit, arrow, 0);
            }

            Chip(float x, float y, float r, String label, long bit) {
                this(x, y, r, label, bit, 0);
            }
        }

        private final List<Chip> chips = new ArrayList<>();
        private final List<Chip> bars = new ArrayList<>();

        {
            // 左右两列严格镜像，中轴 x = 255.5；左列 133、右列 378。
            // 第 1 行：LT / RT（46..198 / 313..443 的横条，见 onDraw）
            // 第 3 行：− / +
            chips.add(new Chip(133, 280, 19, "−", 1048576));
            chips.add(new Chip(378, 280, 19, "+", 2048));

            // 第 4 行：左摇杆 ↔ ABXY（X 上 / Y 左 / A 右 / B 下），同一水平中心
            chips.add(new Chip(378, 370, 20, "X", 4));
            chips.add(new Chip(335, 420, 20, "Y", 8));
            chips.add(new Chip(421, 420, 20, "A", 1));
            chips.add(new Chip(378, 470, 20, "B", 2));

            // 第 5 行：十字键 ↔ 右摇杆，同一水平中心
            chips.add(new Chip(133, 590, 24, "", 256, 1));
            chips.add(new Chip(183, 640, 24, "", 128, 2));
            chips.add(new Chip(133, 690, 24, "", 512, 3));
            chips.add(new Chip(83, 640, 24, "", 64, 4));

            // 第 6 行：Home 左下 ↔ 截图右下，左右对齐
            chips.add(new Chip(133, 830, 20, "", B_HOME, 0, 1));
            chips.add(new Chip(378, 830, 20, "", B_MENU, 0, 2));

            // 肩键横条：LB 在 LT 正下方，RB 在 RT 正下方
            bars.add(new Chip(133, 186, 0, "LB", 4096));
            bars.add(new Chip(378, 186, 0, "RB", 32768));
        }

        PadView(Context c) {
            super(c);
            tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tp.setTextAlign(Paint.Align.CENTER);
        }

        void setHint(String title, String sub) {
            hintTitle = title;
            hintSub = sub;
            invalidate();
        }

        void setBle(long m, int vLx, int vLy, int vRx, int vRy, int vL2, int vR2) {
            bleMask = m;
            lx = vLx; ly = vLy; rx = vRx; ry = vRy;
            l2 = clamp(vL2); r2 = clamp(vR2);
            hasBle = true;
            hintTitle = null;
            hintSub = null;
            invalidate();
        }

        void press(long bit, boolean down) {
            if (down) hidMask |= bit; else hidMask &= ~bit;
            invalidate();
        }

        void setHid(float ax, float ay, float az, float rz,
                    float lt, float rt, float hatX, float hatY) {
            hLx = ax; hLy = ay; hRx = az; hRy = rz;
            hL2 = Math.round(Math.max(0f, lt) * 255f);
            hR2 = Math.round(Math.max(0f, rt) * 255f);

            long m = 0;
            if (hatY < -0.5f) m |= B_UP;
            if (hatY > 0.5f) m |= B_DOWN;
            if (hatX < -0.5f) m |= B_LEFT;
            if (hatX > 0.5f) m |= B_RIGHT;
            hidMask = (hidMask & ~(B_UP | B_DOWN | B_LEFT | B_RIGHT)) | m;

            hintTitle = null;
            hintSub = null;
            invalidate();
        }

        private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

        private long mask() { return hasBle ? (bleMask | hidMask) : hidMask; }

        private int vLx() { return hasBle ? lx : axis(hLx); }
        private int vLy() { return hasBle ? ly : axis(hLy); }
        private int vRx() { return hasBle ? rx : axis(hRx); }
        private int vRy() { return hasBle ? ry : axis(hRy); }
        private int vL2() { return hasBle ? l2 : hL2; }
        private int vR2() { return hasBle ? r2 : hR2; }

        private static int axis(float v) {
            if (Math.abs(v) < 0.08f) return 128;
            int n = Math.round((v + 1f) * 127.5f);
            return n < 0 ? 0 : (n > 255 ? 255 : n);
        }

        /** 0..255（中位 128）→ ±range，且在两端严格收敛到 range，保证帽沿正好贴住外圈。 */
        private static float stickOffset(int v, float range) {
            float k = (v - 128) / 127f;
            if (k > 1f) k = 1f;
            if (k < -1f) k = -1f;
            return k * range;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            scale = Math.min(w / DW, h / DH);
            ox = (w - DW * scale) / 2f;
            oy = 0f;
        }

        @Override protected void onDraw(Canvas c) {
            c.drawColor(C_BG);
            c.save();
            c.translate(ox, oy);
            c.scale(scale, scale);

            drawTopBar(c);
            drawTrigger(c, 68, 198, 126, 154, vL2(), "LT");
            drawTrigger(c, 313, 443, 126, 154, vR2(), "RT");
            drawLeftStick(c);
            drawRightStick(c);
            drawChips(c);
            drawHint(c);

            c.restore();
        }

        private void drawTopBar(Canvas c) {
            p.setColor(C_SURFACE);
            rf.set(10, 46, 64, 100);
            c.drawRoundRect(rf, 18, 18, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.2f);
            p.setColor(C_LINE);
            c.drawRoundRect(rf, 18, 18, p);

            p.setStrokeWidth(2.2f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(C_TEXT);
            path.reset();
            path.moveTo(43, 61);
            path.lineTo(29, 73);
            path.lineTo(43, 85);
            c.drawPath(path, p);
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setStyle(Paint.Style.FILL);

            drawTitle(c, "按键测试", 255.5f, 74, 20);
        }

        /**
         * 摇杆：LS / RS 不再单独画圆，直接融进摇杆本体——
         * 按下摇杆（L3 / R3）时内部灰圈变主色，松开回灰。
         */
        private void drawLeftStick(Canvas c) {
            float cx = 133, cy = 420, rO = 80, rI = 52;
            boolean pressed = (mask() & B_L3) != 0;

            p.setColor(0xFFE9E8F3);
            c.drawCircle(cx, cy, rO, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.2f);
            p.setColor(0xFFD3D0E4);
            c.drawCircle(cx, cy, rO, p);
            p.setStyle(Paint.Style.FILL);

            // 摇杆行程必须正好等于「外圈半径 − 摇杆帽半径」：
            // 原来留了 4px 余量，推到最大时帽沿和外壳之间总有一道缝，看起来永远顶不到边。
            float range = rO - rI;
            float ix = cx + stickOffset(vLx(), range);
            float iy = cy + stickOffset(vLy(), range);

            p.setColor(pressed ? C_PRIMARY : 0xFFDCDBE8);
            c.drawCircle(ix, iy, rI, p);

            p.setColor(C_SURFACE);
            c.drawCircle(ix, iy, 22, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.3f);
            p.setColor(pressed ? C_PRIMARY : 0xFFC2C9EA);
            c.drawCircle(ix, iy, 22, p);
            p.setStyle(Paint.Style.FILL);
            drawText(c, "L", ix, iy, 16f, pressed ? C_PRIMARY : 0xFF5C6699);
        }

        private void drawRightStick(Canvas c) {
            float cx = 378, cy = 640, rO = 80, rI = 52;
            boolean pressed = (mask() & B_R3) != 0;

            p.setColor(0xFFE9E8F3);
            c.drawCircle(cx, cy, rO, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.2f);
            p.setColor(0xFFD3D0E4);
            c.drawCircle(cx, cy, rO, p);
            p.setStyle(Paint.Style.FILL);

            float range = rO - rI;
            float ix = cx + stickOffset(vRx(), range);
            float iy = cy + stickOffset(vRy(), range);

            p.setColor(pressed ? C_PRIMARY : 0xFFDCDBE8);
            c.drawCircle(ix, iy, rI, p);

            p.setColor(C_SURFACE);
            c.drawCircle(ix, iy, 22, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.3f);
            p.setColor(pressed ? C_PRIMARY : 0xFFC2C9EA);
            c.drawCircle(ix, iy, 22, p);
            p.setStyle(Paint.Style.FILL);
            drawText(c, "R", ix, iy, 16f, pressed ? C_PRIMARY : 0xFF5C6699);
        }

        private void drawChips(Canvas c) {
            long m = mask();

            // 肩键长条
            for (Chip ch : bars) {
                boolean on = (m & ch.bit) != 0;
                float hw = 65, hh = 15;
                rf.set(ch.x - hw, ch.y - hh, ch.x + hw, ch.y + hh);
                p.setColor(on ? C_PRIMARY : C_SURFACE);
                c.drawRoundRect(rf, hh, hh, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.3f);
                p.setColor(on ? C_PRIMARY : C_LINE);
                c.drawRoundRect(rf, hh, hh, p);
                p.setStyle(Paint.Style.FILL);
                drawText(c, ch.label, ch.x, ch.y, 12.5f, on ? 0xFFFFFFFF : 0xFF3A3A50);
            }

            for (Chip ch : chips) {
                boolean on = (m & ch.bit) != 0;

                if (on) {
                    p.setColor(0x264A5BD8);
                    c.drawCircle(ch.x, ch.y, ch.r * 1.30f, p);
                }

                p.setColor(on ? C_PRIMARY : C_SURFACE);
                c.drawCircle(ch.x, ch.y, ch.r, p);

                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.3f);
                p.setColor(on ? C_PRIMARY : C_LINE);
                c.drawCircle(ch.x, ch.y, ch.r, p);
                p.setStyle(Paint.Style.FILL);

                if (ch.arrow != 0) {
                    drawArrow(c, ch, on);
                } else if (ch.icon == 1) {
                    drawHome(c, ch, on);
                } else if (ch.icon == 2) {
                    drawShot(c, ch, on);
                } else {
                    float fs = ch.label.length() == 1 ? ch.r * 0.68f : ch.r * 0.56f;
                    if ("−".equals(ch.label)) fs = ch.r * 0.94f;
                    drawText(c, ch.label, ch.x, ch.y, fs, on ? 0xFFFFFFFF : 0xFF3A3A50);
                }
            }
        }

        /** Home：官方 mapper_home 的房子轮廓（描边、无门）。 */
        private void drawHome(Canvas c, Chip ch, boolean on) {
            float x = ch.x, y = ch.y + 1f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.7f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(on ? 0xFFFFFFFF : 0xFF3A3A50);

            path.reset();
            path.moveTo(x - 7.6f, y - 1.4f);
            path.lineTo(x, y - 7.6f);
            path.lineTo(x + 7.6f, y - 1.4f);
            c.drawPath(path, p);

            path.reset();
            path.moveTo(x - 5.4f, y - 2.6f);
            path.lineTo(x - 5.4f, y + 6.6f);
            path.lineTo(x + 5.4f, y + 6.6f);
            path.lineTo(x + 5.4f, y - 2.6f);
            c.drawPath(path, p);

            p.setStrokeCap(Paint.Cap.BUTT);
            p.setStyle(Paint.Style.FILL);
        }

        /** 截图：相机轮廓（机身 + 取景凸起 + 镜头）。 */
        private void drawShot(Canvas c, Chip ch, boolean on) {
            float x = ch.x, y = ch.y + 0.6f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.6f);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(on ? 0xFFFFFFFF : 0xFF3A3A50);

            rf.set(x - 8f, y - 4.8f, x + 8f, y + 6.2f);
            c.drawRoundRect(rf, 2.4f, 2.4f, p);

            rf.set(x - 3f, y - 7.4f, x + 3f, y - 4.4f);
            c.drawRoundRect(rf, 1.2f, 1.2f, p);

            c.drawCircle(x, y + 0.8f, 3.4f, p);
            p.setStyle(Paint.Style.FILL);
        }

        private void drawArrow(Canvas c, Chip ch, boolean on) {
            float s = ch.r * 0.46f, x = ch.x, y = ch.y;
            path.reset();
            switch (ch.arrow) {
                case 1:
                    path.moveTo(x, y - s);
                    path.lineTo(x - s * 0.95f, y + s * 0.62f);
                    path.lineTo(x + s * 0.95f, y + s * 0.62f);
                    break;
                case 2:
                    path.moveTo(x + s, y);
                    path.lineTo(x - s * 0.62f, y - s * 0.95f);
                    path.lineTo(x - s * 0.62f, y + s * 0.95f);
                    break;
                case 3:
                    path.moveTo(x, y + s);
                    path.lineTo(x - s * 0.95f, y - s * 0.62f);
                    path.lineTo(x + s * 0.95f, y - s * 0.62f);
                    break;
                default:
                    path.moveTo(x - s, y);
                    path.lineTo(x + s * 0.62f, y - s * 0.95f);
                    path.lineTo(x + s * 0.62f, y + s * 0.95f);
                    break;
            }
            path.close();
            p.setColor(on ? 0xFFFFFFFF : 0xFF8089C4);
            c.drawPath(path, p);
        }

        private void drawTrigger(Canvas c, float l, float r, float top, float bot,
                                 int v, String label) {
            float rad = (bot - top) / 2f;
            p.setColor(0xFFDCD9E8);
            rf.set(l, top, r, bot);
            c.drawRoundRect(rf, rad, rad, p);

            float fill = (r - l) * v / 255f;
            if (fill > 0.5f) {
                c.save();
                c.clipRect(l, top, l + fill, bot);
                p.setColor(C_PRIMARY);
                c.drawRoundRect(rf, rad, rad, p);
                c.restore();
            }

            // 标签压在进度条上，填充越过中点后自动转白，保证对比度
            if (label != null) {
                boolean lit = fill > (r - l) * 0.5f;
                drawText(c, label, (l + r) / 2f, (top + bot) / 2f, 12.5f,
                        lit ? 0xFFFFFFFF : 0xFF3A3A50);
            }
        }

        private void drawHint(Canvas c) {
            if (hintTitle == null) return;
            String s = (hintSub == null || hintSub.isEmpty())
                    ? hintTitle : hintTitle + " · " + hintSub;
            drawText(c, s, 255.5f, 930, 12.5f, C_SUB);
        }

        private void drawTitle(Canvas c, String s, float x, float y, float size) {
            tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            drawText(c, s, x, y, size, C_TEXT);
            tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        private void drawText(Canvas c, String s, float x, float y, float size, int color) {
            tp.setColor(color);
            tp.setTextSize(size);
            float base = y - (tp.ascent() + tp.descent()) / 2f;
            c.drawText(s, x, base, tp);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                float x = (e.getX() - ox) / scale;
                float y = (e.getY() - oy) / scale;
                if (x >= 6 && x <= 68 && y >= 42 && y <= 104) {
                    if (onBack != null) onBack.run();
                    return true;
                }
            }
            return true;
        }
    }
}
