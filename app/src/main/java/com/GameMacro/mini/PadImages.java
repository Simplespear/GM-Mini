package com.GameMacro.mini;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 官方手柄型号表与产品图。
 *
 * 图片是 GameMacro 官方设备库（OSS avatar）下载的<strong>原始文件</strong>，
 * 按原尺寸、原编码放进 assets/pads，未做任何再压缩；显示时按控件像素宽度
 * 做 inSampleSize 采样解码，既保证清晰度又不会把 3840px 的大图整张读进内存。
 *
 * 识别顺序（与官方一致）：
 *   1. 读到 0x84 手柄信息里的 project 号 → 官方型号，最准；
 *   2. 蓝牙广播名里的具体型号关键字（T50 / T47 / 朱雀 …）；
 *   3. 都没有 → 视为未知，界面不显示通用图标。
 */
public final class PadImages {

    public static final class Pad {
        public final String name;     // 官方型号名
        public final String btKey;    // 蓝牙名关键字（关于页展示用）
        public final String asset;    // assets/pads 下的原始文件名

        Pad(String name, String btKey, String asset) {
            this.name = name;
            this.btKey = btKey;
            this.asset = asset;
        }
    }

    /**
     * 当前只内置 T50 的产品图，其余型号不再打包图片。
     * 需要恢复某款时，把原图放回 assets/pads 并在此追加即可。
     */
    public static final Pad[] ALL = {
            new Pad("T50", "GameMacro", "t50.png"),
    };

    private PadImages() {
    }

    /** project 号（0x84 上报）→ 官方型号；未收录返回空串。 */
    public static String modelOfProject(String project) {
        if (project == null) return "";
        String p = project.trim().toUpperCase();
        switch (p) {
            case "560615": case "088D67": return "T50";
            case "649505": return "T43";
            case "560157": case "560168": case "560169": return "T47";
            case "912400": case "952400": return "T52";
            case "951305": return "T70";
            case "560610": return "G50Plus";
            case "560671": case "649500": return "G6495";
            case "560277": return "SW570";
            case "560623": return "MG0102";
            case "560630": return "GC1201";
            case "951302": return "GC-Pad";
            case "649003": return "EvoFox One S";
            case "558270": return "STK-7040HRG";
            case "560113": return "Mobapad ML35";
            case "562102": return "CRKD ATOM PLUS";
            case "560692": return "Pocket";
            case "557628": case "557728": return "QMacro";
            case "557632": case "557638": case "557605": return "Coiorvis 朱雀";
            default: return "";
        }
    }

    /** 官方型号名 → 手柄；没有该型号返回 null。 */
    public static Pad byModel(String model) {
        if (model == null || model.trim().isEmpty()) return null;
        String m = model.trim();
        for (Pad p : ALL) {
            if (p.name.equalsIgnoreCase(m)) return p;
        }
        return null;
    }

    /**
     * 蓝牙广播名 → 手柄。只有内置产品图的 T50 会命中；
     * "GameMacro" 这种统一广播名不猜型号，交给 0x84 的 project 号判定。
     */
    public static Pad byBtName(String raw) {
        if (raw == null) return null;
        String n = raw.toLowerCase().replace(" ", "").replace("★", "");
        if (n.contains("t50")) return ALL[0];
        return null;
    }

    /** 综合识别：project 号优先，其次蓝牙名；都不中返回 null（未知型号）。 */
    public static Pad resolve(String btName, String project) {
        Pad p = byModel(modelOfProject(project));
        if (p != null) return p;
        return byBtName(btName);
    }

    /**
     * 界面展示名：project 号命中官方型号就用型号名（即使该型号没有内置图片），
     * 否则保留广播名。名字识别与图片解耦，删图不影响型号显示。
     */
    public static String displayName(String btName, String project) {
        String model = modelOfProject(project);
        if (!model.isEmpty()) return model;
        String n = btName == null ? "" : btName.replace("★", "").trim();
        return n.isEmpty() ? "手柄" : n;
    }

    // ---------------- 原图加载 ----------------

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(12 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    /** 采样档位：按 96px 向上取整，同一档共享缓存。 */
    private static int bucket(int px) {
        int b = 96;
        while (b < px) b += 96;
        return b;
    }

    /**
     * 把原图按目标像素宽度解码后塞进 ImageView（异步 + 缓存 + 淡入）。
     * pad 为 null 时移除图片，由调用方决定容器是否隐藏。
     */
    public static void into(final ImageView iv, final Pad pad, int targetPx) {
        into(iv, pad, targetPx, false);
    }

    /**
     * trim=true 时先裁掉画布四周的透明留白，只留手柄本体——官方原图里手柄只占中间一条，
     * 直接铺大图会显得“图很大、手柄很小”。只裁透明像素，不缩放、不重采样。
     */
    public static void into(final ImageView iv, final Pad pad, int targetPx, final boolean trim) {
        if (iv == null) return;
        if (pad == null) {
            iv.setTag(null);
            iv.setImageDrawable(null);
            return;
        }
        final String key = pad.asset + (trim ? "#t" : "") + "@" + bucket(targetPx);
        iv.setTag(key);

        Bitmap hit = CACHE.get(key);
        if (hit != null) {
            iv.setImageBitmap(hit);
            iv.setAlpha(1f);
            return;
        }
        iv.setImageDrawable(null);
        final Context ctx = iv.getContext().getApplicationContext();
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap raw = decode(ctx, pad.asset, bucket(targetPx));
                if (raw == null) return;
                final Bitmap bm = trim ? trimAlpha(raw) : raw;
                CACHE.put(key, bm);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!key.equals(String.valueOf(iv.getTag()))) return;
                        iv.setAlpha(0f);
                        iv.setImageBitmap(bm);
                        iv.requestLayout();
                        iv.animate().alpha(1f).setDuration(180).start();
                    }
                });
            }
        });
    }

    /** 裁掉四周透明留白；没有透明像素或本来就是满幅时原样返回。 */
    private static Bitmap trimAlpha(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        int minX = w, minY = h, maxX = -1, maxY = -1;
        int[] px = new int[w];
        for (int y = 0; y < h; y++) {
            src.getPixels(px, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if ((px[x] >>> 24) > 12) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) return src;
        minX = Math.max(0, minX - 2);
        minY = Math.max(0, minY - 2);
        maxX = Math.min(w - 1, maxX + 2);
        maxY = Math.min(h - 1, maxY + 2);
        if (minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1) return src;
        try {
            return Bitmap.createBitmap(src, minX, minY, maxX - minX + 1, maxY - minY + 1);
        } catch (Exception | OutOfMemoryError e) {
            return src;
        }
    }

    /**
     * 同步设置（只给小图用）：关于页的 T50 大图如果异步回来，卡片高度会先塌再撑开，
     * 这里直接在主线程解码——500×499 的原图 + 去透明边，耗时在毫秒级。
     */
    public static void setSync(ImageView iv, Pad pad, int targetPx, boolean trim) {
        if (iv == null || pad == null) return;
        String key = pad.asset + (trim ? "#t" : "") + "@" + bucket(targetPx);
        Bitmap bm = CACHE.get(key);
        if (bm == null) {
            Bitmap raw = decode(iv.getContext().getApplicationContext(), pad.asset, bucket(targetPx));
            if (raw == null) return;
            bm = trim ? trimAlpha(raw) : raw;
            CACHE.put(key, bm);
        }
        iv.setTag(key);
        iv.setImageBitmap(bm);
    }

    /** 清空图片（未连接时用）。 */
    public static void clear(ImageView iv) {
        into(iv, null, 0);
    }

    private static Bitmap decode(Context c, String asset, int targetPx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = c.getAssets().open("pads/" + asset);
            try {
                BitmapFactory.decodeStream(in, null, bounds);
            } finally {
                in.close();
            }
            int w = bounds.outWidth;
            int sample = 1;
            while (w > 0 && w / (sample * 2) >= targetPx) sample *= 2;

            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
            in = c.getAssets().open("pads/" + asset);
            try {
                return BitmapFactory.decodeStream(in, null, opt);
            } finally {
                in.close();
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
