package com.GameMacro.mini;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 摇杆校准弹窗里的示意图：左右两个摇杆环，主色弧缓慢转动，
 * 对应官方文案「同时旋转左右摇杆数圈」。动画跟随 View 的 attach/detach，
 * 弹窗关闭后自动停止，不会留在后台空转。
 */
public class StickCalibView extends View {

    private static final int TRACK = 0xFFE6E5F0;
    private static final int PRIMARY = 0xFF4A5BD8;
    private static final int CAP_BG = 0xFFE0E4FF;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    private ValueAnimator spin;
    private float deg;

    public StickCalibView(Context c) {
        super(c);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setColor(TRACK);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(PRIMARY);
        capPaint.setStyle(Paint.Style.FILL);
        capPaint.setColor(CAP_BG);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(PRIMARY);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        spin = ValueAnimator.ofFloat(0f, 360f);
        spin.setDuration(2600L);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(a -> {
            deg = (Float) a.getAnimatedValue();
            invalidate();
        });
        spin.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (spin != null) {
            spin.cancel();
            spin = null;
        }
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float d = getResources().getDisplayMetrics().density;
        float stroke = d * 9f;
        trackPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeWidth(stroke);
        float r = Math.min(getHeight() * 0.34f, getWidth() * 0.17f);
        float cy = getHeight() / 2f;
        drawStick(c, getWidth() * 0.30f, cy, r, stroke, deg);
        drawStick(c, getWidth() * 0.70f, cy, r, stroke, -deg);
    }

    private void drawStick(Canvas c, float cx, float cy, float r, float stroke, float angle) {
        float rad = r - stroke / 2f;
        box.set(cx - rad, cy - rad, cx + rad, cy + rad);
        c.drawCircle(cx, cy, rad, trackPaint);
        c.drawArc(box, -90f + angle, 240f, false, arcPaint);
        c.drawCircle(cx, cy, r * 0.52f, capPaint);
        double a = Math.toRadians(-90f + angle + 240f);
        float ox = (float) (Math.cos(a) * rad);
        float oy = (float) (Math.sin(a) * rad);
        c.drawCircle(cx + ox, cy + oy, stroke * 0.55f, dotPaint);
    }
}
