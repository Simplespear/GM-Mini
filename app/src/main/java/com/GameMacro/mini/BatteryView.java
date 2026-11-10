package com.GameMacro.mini;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 极简电池图形控件。
 * 外框 + 右侧电极 + 按百分比填充的内部电量条，颜色随电量变化。
 * 仅用 framework 的 Canvas 绘制，无外部依赖。
 */
public class BatteryView extends View {

    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final RectF inner = new RectF();
    private final RectF cap = new RectF();

    private int level = -1;          // -1 表示未知
    private boolean charging = false;
    private int accent = 0;          // 0 = 按电量自动配色

    public BatteryView(Context c) { this(c, null); }

    public BatteryView(Context c, AttributeSet a) {
        super(c, a);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(dp(1.5f));
        framePaint.setColor(Color.parseColor("#C3C9DE"));
        fillPaint.setStyle(Paint.Style.FILL);
    }

    /** @param level 0~100，负数表示未知 */
    public void setLevel(int level, boolean charging) {
        this.level = level;
        this.charging = charging;
        framePaint.setColor(charging ? Color.parseColor("#7187F5") : Color.parseColor("#C3C9DE"));
        invalidate();
    }

    /** 统一强调色（低电量仍回落为红色警示）。 */
    public void setAccent(int color) {
        this.accent = color;
        invalidate();
    }

    public void setUnknown() {
        this.level = -1;
        this.charging = false;
        framePaint.setColor(Color.parseColor("#C3C9DE"));
        invalidate();
    }

    private float dp(float v) {
        return getResources().getDisplayMetrics().density * v;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(Math.round(dp(34)), widthSpec),
                resolveSize(Math.round(dp(16)), heightSpec));
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float capW = dp(2.6f);
        float sw = framePaint.getStrokeWidth();
        float radius = dp(4f);

        // 电池主体
        box.set(sw / 2f, sw / 2f, w - capW - sw / 2f, h - sw / 2f);
        c.drawRoundRect(box, radius, radius, framePaint);

        // 右侧电极
        cap.set(w - capW, h * 0.32f, w, h * 0.68f);
        framePaint.setStyle(Paint.Style.FILL);
        c.drawRoundRect(cap, dp(1f), dp(1f), framePaint);
        framePaint.setStyle(Paint.Style.STROKE);

        // 电量填充
        if (level <= 0) return;
        float pad = sw + dp(1.4f);
        float availW = box.width() - pad * 2f;
        float availH = box.height() - pad * 2f;
        if (availW <= 0 || availH <= 0) return;
        float ratio = Math.min(Math.max(level, 0), 100) / 100f;
        inner.set(box.left + pad, box.top + pad, box.left + pad + availW * ratio, box.bottom - pad);
        fillPaint.setColor(accent != 0 && level >= 20 ? accent : levelColor(level));
        c.drawRoundRect(inner, dp(1.4f), dp(1.4f), fillPaint);
    }

    /** 电量配色：>=50 绿，>=20 橙，<20 红 */
    public static int levelColor(int level) {
        if (level >= 50) return Color.parseColor("#22A06B");
        if (level >= 20) return Color.parseColor("#E8A33D");
        return Color.parseColor("#D9534F");
    }
}
