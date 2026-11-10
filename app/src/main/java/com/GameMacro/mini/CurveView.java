package com.GameMacro.mini;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/**
 * 摇杆响应曲线：10×10 网格 + 两个可拖动控制点。
 *
 * 坐标含义与原版一致：X 轴 = 物理行程，Y 轴 = 输出行程，范围 0–100。
 * 折线由三段组成：原点→低点、低点→高点、高点→(100,100)。
 */
public class CurveView extends View {

    public interface OnCurveChanged {
        void onChanged(int x0, int y0, int x1, int y1);
    }

    private static final int ACCENT = 0xFF4A5BD8;
    private static final int GREEN  = 0xFF21C877;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bluePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint purplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint redPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path path = new Path();

    private float originX, originY, side;
    private final float[] xs = new float[2];
    private final float[] ys = new float[2];

    private int[] points = {33, 34, 66, 67};
    private int dragIndex = -1;
    private float downX, downY;
    private OnCurveChanged listener;

    public CurveView(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(0.8f));
        gridPaint.setColor(0xFFD9D7E4);
        gridPaint.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(4)}, 0));

        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(dp(1f));
        axisPaint.setColor(0xFFC6C3D4);

        bluePaint.setStyle(Paint.Style.STROKE);
        bluePaint.setStrokeWidth(dp(2f));
        bluePaint.setStrokeCap(Paint.Cap.ROUND);
        bluePaint.setColor(0xFF2F6BFF);

        purplePaint.setStyle(Paint.Style.STROKE);
        purplePaint.setStrokeWidth(dp(2f));
        purplePaint.setStrokeCap(Paint.Cap.ROUND);
        purplePaint.setColor(0xFF8A3FFC);

        redPaint.setStyle(Paint.Style.STROKE);
        redPaint.setStrokeWidth(dp(2f));
        redPaint.setStrokeCap(Paint.Cap.ROUND);
        redPaint.setColor(0xFFE5484D);

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(GREEN);

        dotRingPaint.setStyle(Paint.Style.STROKE);
        dotRingPaint.setStrokeWidth(dp(2f));
        dotRingPaint.setColor(Color.WHITE);

        textPaint.setColor(0xFF5C5C70);
        textPaint.setTextSize(sp(10.5f));
        labelPaint.setColor(0xFF1B1B21);
        labelPaint.setTextSize(sp(11f));
    }

    public void setOnCurveChanged(OnCurveChanged l) {
        listener = l;
    }

    /** 传入 {x0,y0,x1,y1}。 */
    public void setPoints(int[] p) {
        if (p == null || p.length < 4) return;
        points = new int[]{clamp(p[0]), clamp(p[1]), clamp(p[2]), clamp(p[3])};
        normalize();
        invalidate();
    }

    public int[] getPoints() {
        return new int[]{points[0], points[1], points[2], points[3]};
    }

    private int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private void normalize() {
        if (points[0] > points[2]) { int t = points[0]; points[0] = points[2]; points[2] = t; }
        if (points[1] > points[3]) { int t = points[1]; points[1] = points[3]; points[3] = t; }
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float padL = dp(34), padT = dp(34), padR = dp(16), padB = dp(30);
        side = Math.min(getWidth() - padL - padR, getHeight() - padT - padB);
        if (side <= 0) return;
        originX = padL;
        originY = padT + side;

        // 网格
        for (int i = 1; i <= 10; i++) {
            float x = originX + side * i / 10f;
            float y = originY - side * i / 10f;
            canvas.drawLine(x, originY, x, originY - side, gridPaint);
            canvas.drawLine(originX, y, originX + side, y, gridPaint);
        }
        // 轴
        canvas.drawLine(originX, originY, originX + side, originY, axisPaint);
        canvas.drawLine(originX, originY, originX, originY - side, axisPaint);

        // 文字
        canvas.drawText("Y:输出行程", dp(2), dp(16), textPaint);
        canvas.drawText("0", originX - dp(12), originY + dp(14), textPaint);
        String xLabel = "X:物理行程";
        canvas.drawText(xLabel, originX + side - textPaint.measureText(xLabel),
                originY + dp(24), textPaint);

        // 折线
        float x0 = originX + side * points[0] / 100f;
        float y0 = originY - side * points[1] / 100f;
        float x1 = originX + side * points[2] / 100f;
        float y1 = originY - side * points[3] / 100f;

        path.reset();
        path.moveTo(originX, originY);
        path.lineTo(x0, y0);
        canvas.drawPath(path, bluePaint);

        path.reset();
        path.moveTo(x0, y0);
        path.lineTo(x1, y1);
        canvas.drawPath(path, purplePaint);

        path.reset();
        path.moveTo(x1, y1);
        path.lineTo(originX + side, originY - side);
        canvas.drawPath(path, redPaint);

        // 点
        canvas.drawCircle(x0, y0, dp(8), dotPaint);
        canvas.drawCircle(x0, y0, dp(8), dotRingPaint);
        canvas.drawCircle(x1, y1, dp(8), dotPaint);
        canvas.drawCircle(x1, y1, dp(8), dotRingPaint);

        // 坐标标签
        canvas.drawText("(" + points[0] + "," + points[1] + ")",
                Math.min(x0 + dp(13), originX + side - dp(52)), y0 - dp(8), labelPaint);
        canvas.drawText("(" + points[2] + "," + points[3] + ")",
                Math.min(x1 + dp(13), originX + side - dp(52)), y1 - dp(8), labelPaint);

        xs[0] = x0; xs[1] = x1;
        ys[0] = y0; ys[1] = y1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float px = e.getX(), py = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragIndex = nearest(px, py);
                if (dragIndex < 0) return super.onTouchEvent(e);
                downX = px;
                downY = py;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragIndex < 0) return false;
                movePoint(px, py);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragIndex >= 0) {
                    movePoint(px, py);
                    dragIndex = -1;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    notifyChanged();
                    return true;
                }
                return false;
        }
        return super.onTouchEvent(e);
    }

    private int nearest(float px, float py) {
        float d0 = (float) Math.hypot(px - xs[0], py - ys[0]);
        float d1 = (float) Math.hypot(px - xs[1], py - ys[1]);
        float limit = dp(40);
        if (d0 > limit && d1 > limit) return -1;
        return d0 <= d1 ? 0 : 1;
    }

    private void movePoint(float px, float py) {
        int x = Math.round((px - originX) / side * 100f);
        int y = Math.round((originY - py) / side * 100f);
        x = clamp(x);
        y = clamp(y);
        if (dragIndex == 0) {
            points[0] = Math.min(x, points[2]);
            points[1] = Math.min(y, points[3]);
        } else {
            points[2] = Math.max(x, points[0]);
            points[3] = Math.max(y, points[1]);
        }
        invalidate();
    }

    private void notifyChanged() {
        if (listener != null) listener.onChanged(points[0], points[1], points[2], points[3]);
    }
}
