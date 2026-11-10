package com.GameMacro.mini;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * 陀螺仪校准弹窗里的 60 秒环形倒计时。
 *
 * 按官方 CircleProgressBar 还原：直径 160dp、环宽 12dp、从 12 点方向顺时针走满 360°，
 * 中间是 48sp 的剩余秒数。配色跟随 App 白色体系：剩余环用主色 #4A5BD8，
 * 已走过的弧用浅灰 #E6E5F0 轨道，数字用深色 #1B1B21。
 */
public class CalibRingView extends View {

    private static final int REMAIN_COLOR = 0xFF4A5BD8;   // App 主色
    private static final int GONE_COLOR = 0xFFE6E5F0;     // 浅灰轨道
    private static final int TEXT_COLOR = 0xFF1B1B21;     // onSurface

    private final Paint remainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    private int seconds = 60;
    private float progressDeg = 0f;

    public CalibRingView(Context c) {
        super(c);
        remainPaint.setStyle(Paint.Style.STROKE);
        remainPaint.setStrokeCap(Paint.Cap.BUTT);
        gonePaint.setStyle(Paint.Style.STROKE);
        gonePaint.setStrokeCap(Paint.Cap.BUTT);
        textPaint.setColor(TEXT_COLOR);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /** @param remain 剩余秒数；@param deg 已走过的角度（0..360） */
    public void update(int remain, int deg) {
        seconds = remain;
        progressDeg = deg;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        ensureBox();
    }

    /** show() 之后可能还没 layout 就收到第一次 update，onDraw 里再兜一次底。 */
    private void ensureBox() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float stroke = getResources().getDisplayMetrics().density * 12f;
        remainPaint.setStrokeWidth(stroke);
        gonePaint.setStrokeWidth(stroke);
        textPaint.setTextSize(getResources().getDisplayMetrics().density * 48f);
        float inset = stroke / 2f + dp(2);
        box.set(inset, inset, w - inset, h - inset);
    }

    private float dp(float v) {
        return getResources().getDisplayMetrics().density * v;
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        ensureBox();
        // 已走过：从 12 点开始顺时针，用浅灰轨道
        if (progressDeg > 0f) {
            gonePaint.setColor(GONE_COLOR);
            c.drawArc(box, 270f, progressDeg, false, gonePaint);
        }
        // 剩余：绿色环逐渐缩短
        remainPaint.setColor(REMAIN_COLOR);
        c.drawArc(box, 270f + progressDeg, 360f - progressDeg, false, remainPaint);

        String s = String.valueOf(seconds);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float base = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
        c.drawText(s, getWidth() / 2f, base, textPaint);
    }
}
