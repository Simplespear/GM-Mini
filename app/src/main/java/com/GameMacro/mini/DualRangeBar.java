package com.GameMacro.mini;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 双滑块范围条 —— 对应原版 BothSeekBarView（摇杆起点 / 终点）。
 * 一条浅灰轨道、两枚白色描边滑块、中间主色区间，底部刻度和 0/20/…/100 数字。
 */
public class DualRangeBar extends View {

    private static final int C_TRACK = 0xFFE4E2F0;
    private static final int C_ACTIVE = 0xFF4A5BD8;
    private static final int C_THUMB = 0xFFFFFFFF;
    private static final int C_TEXT = 0xFF8C8C9E;
    private static final int C_TICK = 0xFFD6D4E2;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final float density;
    private int min = 0, max = 100;
    private int low = 0, high = 100;
    private int activeThumb = -1;
    private OnRangeChanged listener;

    public interface OnRangeChanged {
        void onChanged(int low, int high);
    }

    public DualRangeBar(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        trackPaint.setColor(C_TRACK);
        activePaint.setColor(C_ACTIVE);
        tickPaint.setColor(C_TICK);
        textPaint.setColor(C_TEXT);
        textPaint.setTextSize(dp(9.5f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        thumbPaint.setColor(C_THUMB);
        thumbStroke.setColor(C_ACTIVE);
        thumbStroke.setStyle(Paint.Style.STROKE);
        thumbStroke.setStrokeWidth(dp(2));
        shadowPaint.setColor(0x1F1B1B21);
        setFocusable(true);
    }

    private float dp(float v) {
        return v * density;
    }

    public void setRange(int min, int max, int low, int high) {
        this.min = min;
        this.max = Math.max(min + 1, max);
        this.low = clamp(low);
        this.high = Math.max(this.low, clamp(high));
        invalidate();
    }

    public void setOnRangeChanged(OnRangeChanged l) {
        listener = l;
    }

    private int clamp(int v) {
        return Math.max(min, Math.min(max, v));
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = resolveSize(Math.round(dp(300)), widthMeasureSpec);
        int h = resolveSize(Math.round(dp(56)), heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    private float left() {
        return getPaddingLeft() + dp(14);
    }

    private float right() {
        return getWidth() - getPaddingRight() - dp(14);
    }

    private float trackY() {
        return dp(15);
    }

    private float xOf(int value) {
        float t = (value - min) / (float) (max - min);
        return left() + (right() - left()) * t;
    }

    private int valueOf(float x) {
        float t = (x - left()) / Math.max(1f, right() - left());
        t = Math.max(0f, Math.min(1f, t));
        return Math.round(min + t * (max - min));
    }

    @Override protected void onDraw(Canvas c) {
        float y = trackY();
        float l = left(), r = right();
        float radius = dp(2);

        rect.set(l, y - radius, r, y + radius);
        c.drawRoundRect(rect, radius, radius, trackPaint);

        float lx = xOf(low), hx = xOf(high);
        rect.set(lx, y - radius, hx, y + radius);
        c.drawRoundRect(rect, radius, radius, activePaint);

        // 刻度：每 10 一格，每 20 标数字
        int span = max - min;
        for (int i = 0; i <= span; i += 10) {
            int v = min + i;
            float x = xOf(v);
            c.drawLine(x, y + dp(6), x, y + dp(9), tickPaint);
            if (v % 20 == 0 || v == max) {
                c.drawText(String.valueOf(v), x, y + dp(23), textPaint);
            }
        }

        drawThumb(c, lx, y);
        drawThumb(c, hx, y);
    }

    private void drawThumb(Canvas c, float x, float y) {
        float r = dp(9);
        c.drawCircle(x, y + dp(1.5f), r, shadowPaint);
        c.drawCircle(x, y, r, thumbPaint);
        c.drawCircle(x, y, r - dp(1), thumbStroke);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                activeThumb = Math.abs(e.getX() - xOf(low)) <= Math.abs(e.getX() - xOf(high)) ? 0 : 1;
                updateFromTouch(e.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                updateFromTouch(e.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                activeThumb = -1;
                performClick();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void updateFromTouch(float x) {
        int v = valueOf(x);
        if (activeThumb == 0) {
            low = Math.min(v, high - 1);
        } else if (activeThumb == 1) {
            high = Math.max(v, low + 1);
        }
        invalidate();
        if (listener != null) listener.onChanged(low, high);
    }

    @Override public boolean performClick() {
        return super.performClick();
    }
}
