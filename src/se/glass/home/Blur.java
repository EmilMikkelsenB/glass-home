package se.glass.home;

import android.graphics.Bitmap;

/**
 * Pre-computed frosted blur for panel backgrounds: blurring once on a small copy and cross-fading it is far cheaper on the
 * Chromecast GPU than animating a live RenderEffect.
 */
final class Blur {
    private Blur() {}

    /** Downscale to `width`, then 3 box-blur passes (≈ gaussian) of `radius` px. Returns a new bitmap. */
    static Bitmap frost(Bitmap src, int width, int radius) {
        int h = Math.max(1, Math.round(width * (float) src.getHeight() / src.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(src, width, h, true).copy(Bitmap.Config.ARGB_8888, true);
        int[] px = new int[width * h], tmp = new int[width * h];
        small.getPixels(px, 0, width, 0, 0, width, h);
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, width, h, radius, true);
            box(tmp, px, width, h, radius, false);
        }
        small.setPixels(px, 0, width, 0, 0, width, h);
        return small;
    }

    /** One running-sum box blur along rows (horizontal) or columns, edge pixels clamped. */
    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w, div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = horizontal ? line * w : line, stride = horizontal ? 1 : w;
            int ra = 0, rr = 0, rg = 0, rb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[base + clamp(i, len) * stride];
                ra += c >>> 24; rr += (c >> 16) & 0xff; rg += (c >> 8) & 0xff; rb += c & 0xff;
            }
            for (int i = 0; i < len; i++) {
                out[base + i * stride] = ((ra / div) << 24) | ((rr / div) << 16) | ((rg / div) << 8) | (rb / div);
                int add = in[base + clamp(i + r + 1, len) * stride], sub = in[base + clamp(i - r, len) * stride];
                ra += (add >>> 24) - (sub >>> 24);
                rr += ((add >> 16) & 0xff) - ((sub >> 16) & 0xff);
                rg += ((add >> 8) & 0xff) - ((sub >> 8) & 0xff);
                rb += (add & 0xff) - (sub & 0xff);
            }
        }
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : i >= len ? len - 1 : i;
    }
}
