package com.GameMacro.mini;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * T50 产品说明书 —— 把官方 PDF 打进 assets 后本地阅读，不再跳浏览器。
 *
 * 实现要点：
 *   · PdfRenderer 只能在单线程里访问，打开与所有页渲染都排队到同一个 worker；
 *   · 页面是 A4 竖版，按屏宽等比算高，纵向连续滚动更接近翻纸质说明书；
 *   · 只渲染滚动到可视区附近的页（前后各留 1 页），避免一次解码 7 页大图；
 *   · assets 里的 PDF 先复制到 cache（文件名带 lastUpdateTime，App 升级自动换新），
 *     PdfRenderer 需要真实文件描述符，不能直接读 asset 流。
 */
public class ManualActivity extends AppCompatActivity {

    private static final String ASSET = "manual/t50.pdf";

    private static final int C_BG = 0xFFF6F5FB;
    private static final int C_SURFACE = 0xFFFFFFFF;
    private static final int C_ON_SURFACE = 0xFF1B1B21;
    private static final int C_ON_VARIANT = 0xFF5C5C70;
    private static final int C_PRIMARY = 0xFF4A5BD8;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<ImageView> pageViews = new ArrayList<>();

    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private boolean[] requested;
    private int[] pageW;
    private int[] pageH;
    private int pageCount;
    private int targetW;

    private ScrollView scroll;
    private LinearLayout column;
    private TextView pageLabel;
    private TextView loadingText;
    private View loadingBox;

    private volatile boolean closed;
    private volatile boolean ready;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(C_BG);
        w.setNavigationBarColor(C_BG);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        int screenW = getResources().getDisplayMetrics().widthPixels;
        targetW = Math.min(screenW - dp(32), dp(1080));
        if (targetW < dp(200)) targetW = Math.max(dp(200), screenW - dp(32));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(C_BG);

        root.addView(buildTopBar(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56), Gravity.TOP));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        slp.topMargin = dp(56);
        root.addView(scroll, slp);

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(16), dp(16), dp(16), dp(28));
        scroll.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(buildLoading(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if (ready) updateVisible();
        });

        setContentView(root);
        openAsync();
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(C_SURFACE);
        bar.setElevation(dp(3));
        bar.setPadding(dp(6), 0, dp(18), 0);

        FrameLayout back = new FrameLayout(this);
        back.setClickable(true);
        back.setBackground(ripple());
        back.setOnClickListener(v -> finish());
        back.setContentDescription("返回");
        back.addView(new BackArrow(this), new FrameLayout.LayoutParams(
                dp(22), dp(22), Gravity.CENTER));
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView title = new TextView(this);
        title.setText("T50 产品说明书");
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        title.setTextColor(C_ON_SURFACE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(6);
        bar.addView(title, tlp);

        pageLabel = new TextView(this);
        pageLabel.setText("");
        pageLabel.setTextSize(12);
        pageLabel.setTextColor(C_ON_VARIANT);
        bar.addView(pageLabel);
        return bar;
    }

    private View buildLoading() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminateTintList(ColorStateList.valueOf(C_PRIMARY));
        box.addView(pb, new LinearLayout.LayoutParams(dp(32), dp(32)));

        loadingText = new TextView(this);
        loadingText.setText("正在加载说明书…");
        loadingText.setTextSize(12.5f);
        loadingText.setTextColor(C_ON_VARIANT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        box.addView(loadingText, lp);
        loadingBox = box;
        return box;
    }

    private void openAsync() {
        worker.execute(() -> {
            try {
                File file = ensurePdf();
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                renderer = new PdfRenderer(pfd);
                final int n = renderer.getPageCount();
                if (n <= 0) throw new IllegalStateException("pdf has no page");

                final int[] ws = new int[n];
                final int[] hs = new int[n];
                for (int i = 0; i < n; i++) {
                    PdfRenderer.Page p = renderer.openPage(i);
                    ws[i] = p.getWidth();
                    hs[i] = p.getHeight();
                    p.close();
                }
                ui.post(() -> {
                    if (closed) return;
                    pageCount = n;
                    pageW = ws;
                    pageH = hs;
                    requested = new boolean[n];
                    buildPages();
                    ready = true;
                    loadingBox.setVisibility(View.GONE);
                    pageLabel.setText("1 / " + pageCount);
                    scroll.post(this::updateVisible);
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    if (closed) return;
                    loadingText.setText("说明书加载失败\n" + shortMsg(t));
                });
            }
        });
    }

    private void buildPages() {
        for (int i = 0; i < pageCount; i++) {
            ImageView iv = new ImageView(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(C_SURFACE);
            bg.setCornerRadius(dp(14));
            iv.setBackground(bg);
            iv.setClipToOutline(true);
            iv.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setElevation(dp(1.5f));
            iv.setContentDescription("说明书第 " + (i + 1) + " 页");

            int h = Math.max(1, Math.round(targetW * (float) pageH[i] / pageW[i]));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(targetW, h);
            if (i > 0) lp.topMargin = dp(14);
            column.addView(iv, lp);
            pageViews.add(iv);
        }
    }

    /** 可视区前后各一页进入渲染队列；页码取与视口重叠最多的那一页。 */
    private void updateVisible() {
        if (!ready || pageViews.isEmpty()) return;
        int top = scroll.getScrollY();
        int bottom = top + scroll.getHeight();
        int first = -1, last = -1;
        int best = 0, bestOverlap = -1;

        for (int i = 0; i < pageViews.size(); i++) {
            View v = pageViews.get(i);
            int overlap = Math.min(bottom, v.getBottom()) - Math.max(top, v.getTop());
            if (overlap > 0) {
                if (first < 0) first = i;
                last = i;
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    best = i;
                }
            }
        }
        if (first < 0) return;

        for (int i = Math.max(0, first - 1); i <= Math.min(pageCount - 1, last + 1); i++) {
            requestPage(i);
        }
        pageLabel.setText((best + 1) + " / " + pageCount);
    }

    private void requestPage(final int index) {
        if (index < 0 || index >= pageCount || requested[index]) return;
        requested[index] = true;

        final int w = targetW;
        final int h = Math.max(1, Math.round(w * (float) pageH[index] / pageW[index]));
        worker.execute(() -> {
            Bitmap bm = null;
            try {
                PdfRenderer.Page p = renderer.openPage(index);
                bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bm.eraseColor(Color.WHITE);
                p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                p.close();
            } catch (Throwable t) {
                bm = null;
            }
            final Bitmap out = bm;
            ui.post(() -> {
                if (closed || out == null) return;
                ImageView iv = pageViews.get(index);
                iv.setImageBitmap(out);
                iv.setAlpha(0f);
                iv.animate().alpha(1f).setDuration(160).start();
            });
        });
    }

    /** assets 里的 PDF 复制到 cache；文件名带升级时间戳，App 更新后自动换新。 */
    private File ensurePdf() throws Exception {
        long stamp = 0L;
        try {
            stamp = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
        } catch (Exception ignored) {
        }
        File dst = new File(getCacheDir(), "t50_manual_" + stamp + ".pdf");
        if (dst.exists() && dst.length() > 1024) return dst;

        File tmp = new File(getCacheDir(), "t50_manual_" + stamp + ".tmp");
        try (InputStream in = getAssets().open(ASSET);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (dst.exists() && !dst.delete()) {
            // 旧文件删不掉就直接用临时文件，不影响阅读
            return tmp;
        }
        return tmp.renameTo(dst) ? dst : tmp;
    }

    private String shortMsg(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
        return m.length() > 60 ? m.substring(0, 60) : m;
    }

    private RippleDrawable ripple() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(dp(10));
        return new RippleDrawable(ColorStateList.valueOf(0x1F4A5BD8), bg, null);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        closed = true;
        ui.removeCallbacksAndMessages(null);
        try {
            worker.execute(() -> {
                try {
                    if (renderer != null) renderer.close();
                } catch (Throwable ignored) {
                }
                try {
                    if (pfd != null) pfd.close();
                } catch (Throwable ignored) {
                }
            });
            worker.shutdown();
        } catch (Throwable ignored) {
        }
        for (ImageView iv : pageViews) iv.setImageDrawable(null);
        super.onDestroy();
    }

    /** 返回箭头：一条折线，线宽随密度走，避免依赖字体里的 ‹ 字形。 */
    private static class BackArrow extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BackArrow(Context c) {
            super(c);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(c, 2f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(C_ON_SURFACE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            Path path = new Path();
            path.moveTo(w * 0.64f, h * 0.14f);
            path.lineTo(w * 0.30f, h * 0.50f);
            path.lineTo(w * 0.64f, h * 0.86f);
            canvas.drawPath(path, paint);
        }
    }
}
