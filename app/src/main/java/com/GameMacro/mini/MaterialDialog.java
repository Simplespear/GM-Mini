package com.GameMacro.mini;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

/**
 * 居中的 Material 3 弹窗。
 *
 * 统一约束（本项目所有弹窗都走这里，不再使用锚定气泡）：
 *   · 永远位于屏幕正中，宽度 = min(屏宽 - 48dp, 400dp)；
 *   · 28dp 圆角、白色 surface、8dp elevation、无描边；
 *   · 背景：Android 12+ 走系统窗口级高斯模糊（FLAG_BLUR_BEHIND + setBackgroundBlurRadius），
 *     低版本退化为 42% 黑色遮罩；
 *   · 入场：0.92 → 1.0 缩放 + 淡入，180ms Decelerate；出场反向 120ms。
 */
public class MaterialDialog {

    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_ON_SURFACE = 0xFF1B1B21;
    private static final int C_ON_VARIANT = 0xFF5C5C70;
    private static final int C_PRIMARY = 0xFF4A5BD8;
    private static final int C_RED = 0xFFB3261E;

    private final Dialog dialog;
    private final LinearLayout card;
    private final View animView;   // 缩放/淡入动画的作用对象（含右上角 × 的整体）
    private final TextView tvMessage;
    private boolean dismissing;

    private MaterialDialog(Builder b) {
        final Context ctx = b.ctx;
        dialog = new Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(b.cancelable);
        dialog.setCancelable(b.cancelable);

        final boolean dark = b.dark;
        final int onSurface = dark ? 0xFFF2F2F7 : C_ON_SURFACE;
        final int onVariant = dark ? 0xBFFFFFFF : C_ON_VARIANT;
        final int btnColor  = dark ? 0xFFFFFFFF : C_PRIMARY;

        final MaterialCardView cardView = new MaterialCardView(ctx);
        cardView.setCardBackgroundColor(dark ? 0xFF191919 : C_CARD);
        cardView.setRadius(dp(ctx, 28));
        cardView.setCardElevation(dp(ctx, 8));
        cardView.setStrokeWidth(0);
        cardView.setUseCompatPadding(false);

        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(ctx, 24), dp(ctx, 22), dp(ctx, 24), dp(ctx, 14));
        cardView.addView(card, new MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (b.title != null && !b.title.isEmpty()) {
            TextView t = new TextView(ctx);
            t.setText(b.title);
            t.setTextSize(19);
            t.setTextColor(onSurface);
            t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            t.setIncludeFontPadding(false);
            card.addView(t);
        }

        tvMessage = new TextView(ctx);
        if (b.message != null && !b.message.isEmpty()) {
            tvMessage.setText(b.message);
            tvMessage.setTextSize(13.5f);
            tvMessage.setTextColor(onVariant);
            tvMessage.setLineSpacing(dp(ctx, 3), 1f);
            tvMessage.setPadding(0, b.title == null ? 0 : dp(ctx, 10), 0, 0);
            card.addView(tvMessage);
        } else {
            tvMessage.setVisibility(View.GONE);
        }

        if (b.content != null) {
            // 保留调用方给出的高度（设备列表需要固定高度滚动），只强制宽度撑满
            ViewGroup.LayoutParams clp = b.content.getLayoutParams();
            if (clp == null) clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            else clp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            if (clp instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) clp).topMargin = dp(ctx, 6);
            }
            card.addView(b.content, clp);
        }

        if (b.positive != null || b.negative != null) {
            LinearLayout actions = new LinearLayout(ctx);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.topMargin = dp(ctx, 14);
            actions.setLayoutParams(alp);

            if (b.negative != null) {
                TextView n = textBtn(ctx, b.negative, btnColor, false);
                n.setOnClickListener(v -> {
                    dismissAnimated();
                    if (b.onNegative != null) b.onNegative.run();
                });
                actions.addView(n);
            }
            if (b.positive != null) {
                TextView p = textBtn(ctx, b.positive, b.danger ? C_RED : btnColor, true);
                p.setOnClickListener(v -> {
                    if (b.dismissOnPositive) dismissAnimated();
                    if (b.onPositive != null) b.onPositive.run();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = dp(ctx, 6);
                actions.addView(p, lp);
            }
            card.addView(actions);
        }

        // 右上角 ×：叠在卡片之上，用一层 FrameLayout 包住卡片，
        // 让关闭按钮和卡片一起参与入场/出场动画。
        View body = cardView;
        if (b.onClose != null) {
            FrameLayout wrap = new FrameLayout(ctx);
            wrap.setClipChildren(false);
            wrap.setClipToPadding(false);
            wrap.setElevation(dp(ctx, 8));
            wrap.addView(cardView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            View close = closeView(ctx, onSurface);
            close.setOnClickListener(v -> {
                dismissAnimated();
                b.onClose.run();
            });
            FrameLayout.LayoutParams xlp = new FrameLayout.LayoutParams(
                    dp(ctx, 40), dp(ctx, 40), Gravity.TOP | Gravity.END);
            xlp.topMargin = dp(ctx, 6);
            xlp.rightMargin = dp(ctx, 6);
            wrap.addView(close, xlp);
            body = wrap;
        }
        animView = body;

        FrameLayout root = new FrameLayout(ctx);
        root.setPadding(dp(ctx, 24), dp(ctx, 24), dp(ctx, 24), dp(ctx, 24));
        root.setClipChildren(false);
        root.setClipToPadding(false);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                b.widthPx(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(body, clp);
        root.setOnClickListener(v -> { if (b.cancelable) dismissAnimated(); });
        body.setClickable(true);
        card.setClickable(true);

        dialog.setContentView(root);

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setWindowAnimations(0);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(b.dark ? 0.68f : 0.42f);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (Build.VERSION.SDK_INT >= 31) {
                // 系统窗口级模糊：只模糊弹窗背后的内容，弹窗本身清晰。
                try {
                    w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                    w.setBackgroundBlurRadius(dp(ctx, 24));
                } catch (Throwable ignored) {
                    // 少数 ROM 不支持，保留遮罩
                }
            }
        }
    }

    /** 动态改正文（校准进度等）。 */
    public void setMessage(String s) {
        if (tvMessage.getVisibility() != View.VISIBLE) tvMessage.setVisibility(View.VISIBLE);
        tvMessage.setText(s);
    }

    public void show() {
        dialog.show();
        animView.setAlpha(0f);
        animView.setScaleX(0.92f);
        animView.setScaleY(0.92f);
        animView.setTranslationY(dp(card.getContext(), 10));
        animView.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(200)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .start();
    }

    public void dismiss() {
        if (dialog.isShowing()) dialog.dismiss();
    }

    public void dismissAnimated() {
        if (!dialog.isShowing() || dismissing) return;
        dismissing = true;   // × 与系统返回键可能同时触发，避免重复跑退场动画
        animView.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f)
                .setDuration(120)
                .withEndAction(() -> { if (dialog.isShowing()) dialog.dismiss(); })
                .start();
    }

    public boolean isShowing() { return dialog.isShowing(); }

    /** 交给调用方处理系统返回键 / 屏蔽手柄确认键。 */
    public void setOnKeyListener(DialogInterface.OnKeyListener l) {
        dialog.setOnKeyListener(l);
    }

    // ---------------- 快捷方法 ----------------

    public static MaterialDialog alert(Context c, String title, String msg) {
        return new Builder(c).title(title).message(msg).positive("知道了", null).build();
    }

    /** 右上角关闭 ×：自绘两条圆头斜线，配主色 ripple，颜色跟随卡片文字色。 */
    private static View closeView(Context ctx, final int color) {
        View v = new View(ctx) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            {
                p.setColor(color);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeWidth(dp(ctx, 1.8f));
            }
            @Override protected void onDraw(Canvas c) {
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                float s = dp(ctx, 6.5f);
                c.drawLine(cx - s, cy - s, cx + s, cy + s, p);
                c.drawLine(cx + s, cy - s, cx - s, cy + s, p);
            }
        };
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(ctx, 20));
        v.setBackground(new RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.argb(28, 74, 91, 216)),
                null, mask));
        v.setClickable(true);
        v.setContentDescription("关闭");
        return v;
    }

    private static MaterialButton textBtn(Context ctx, String text, int color, boolean bold) {
        MaterialButton b = new MaterialButton(ctx);
        b.setText(text);
        b.setTextSize(14);
        b.setTextColor(color);
        b.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        b.setAllCaps(false);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10));
        b.setCornerRadius(dp(ctx, 20));
        boolean onDark = (b.getTextColors().getDefaultColor() == 0xFFFFFFFF);
        b.setRippleColor(android.content.res.ColorStateList.valueOf(
                onDark ? Color.argb(48, 255, 255, 255) : Color.argb(28, 74, 91, 216)));
        return b;
    }

    static int dp(Context c, float v) {
        return Math.round(c.getResources().getDisplayMetrics().density * v);
    }

    // ---------------- Builder ----------------

    public static class Builder {
        private final Context ctx;
        private String title, message, positive, negative;
        private View content;
        private Runnable onPositive, onNegative, onClose;
        private boolean danger = false, cancelable = true, dark = false;
        private boolean dismissOnPositive = true;
        private int maxWidthDp = 400;

        public Builder(Context c) { ctx = c; }

        public Builder title(String s) { title = s; return this; }
        public Builder message(String s) { message = s; return this; }
        public Builder content(View v) { content = v; return this; }
        public Builder positive(String s, Runnable r) { positive = s; onPositive = r; return this; }
        /** 点正按钮后弹窗不关闭（设备列表的「重新扫描」用，结果就地刷新）。 */
        public Builder positiveSticky(String s, Runnable r) {
            positive = s; onPositive = r; dismissOnPositive = false; return this;
        }
        public Builder negative(String s, Runnable r) { negative = s; onNegative = r; return this; }
        /** 右上角 × 关闭按钮，替代底部文字「返回」。 */
        public Builder close(Runnable r) { onClose = r; return this; }
        public Builder danger(boolean b) { danger = b; return this; }
        public Builder cancelable(boolean b) { cancelable = b; return this; }
        public Builder maxWidthDp(int v) { maxWidthDp = v; return this; }
        /** 深色卡片仅供特殊场景备用；全 App 默认白色 surface。 */
        public Builder dark(boolean b) { dark = b; return this; }

        public MaterialDialog build() { return new MaterialDialog(this); }

        public MaterialDialog show() {
            MaterialDialog d = build();
            d.show();
            return d;
        }

        private int widthPx() {
            int screen = ctx.getResources().getDisplayMetrics().widthPixels;
            int max = dp(ctx, maxWidthDp);
            return Math.min(screen - dp(ctx, 48), max);
        }
    }
}
