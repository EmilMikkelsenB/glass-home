package se.glass.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Rounded 16:9 artwork for Jellyfin rows: centre-cropped image, glass rim and a progress bar. Paths and shaders are built
 * once per size; the bitmap fades in when it arrives.
 */
final class Poster extends Drawable {
    private final float progress;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shade = new Paint(), rim = new Paint(Paint.ANTI_ALIAS_FLAG), bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF r = new RectF();
    private final Rect src = new Rect();
    private Bitmap bitmap;
    private int fade = 255;
    private long fadeStart;

    Poster(float progress) {
        this.progress = progress;
        rim.setStyle(Paint.Style.STROKE);
    }

    void setBitmap(Bitmap bm) {
        bitmap = bm;
        fadeStart = System.currentTimeMillis();
        fade = 0;
        computeSrc();
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect b) {
        r.set(b);
        float h = r.height(), rad = h * 0.12f;
        clip.reset();
        clip.addRoundRect(r, rad, rad, Path.Direction.CW);
        shade.setShader(new LinearGradient(0, r.bottom - h * 0.4f, 0, r.bottom, 0, 0x8C000000, Shader.TileMode.CLAMP));
        rim.setStrokeWidth(Math.max(1.5f, h / 120f));
        rim.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                Glass.withAlpha(Color.WHITE, 0.6f), Glass.withAlpha(Color.WHITE, 0.12f), Shader.TileMode.CLAMP));
        computeSrc();
    }

    private void computeSrc() {
        if (bitmap == null || r.isEmpty()) return;
        float s = Math.max(r.width() / bitmap.getWidth(), r.height() / bitmap.getHeight());
        float sw = r.width() / s, sh = r.height() / s;
        float sx = (bitmap.getWidth() - sw) / 2, sy = (bitmap.getHeight() - sh) / 2;
        src.set(Math.round(sx), Math.round(sy), Math.round(sx + sw), Math.round(sy + sh));
    }

    @Override
    public void draw(Canvas c) {
        float h = r.height(), rad = h * 0.12f;
        c.save();
        c.clipPath(clip);
        paint.setColor(Glass.withAlpha(Color.WHITE, 0.12f));   // placeholder while the image loads
        if (bitmap == null || fade < 255) c.drawRect(r, paint);
        if (bitmap != null) {
            if (fade < 255) {
                fade = (int) Math.min(255, (System.currentTimeMillis() - fadeStart) * 255 / 300);
                invalidateSelf();
            }
            paint.setAlpha(fade);
            c.drawBitmap(bitmap, src, r, paint);
            paint.setAlpha(255);
        }
        if (progress > 0) {
            c.drawRect(r.left, r.bottom - h * 0.4f, r.right, r.bottom, shade);
            float inset = h * 0.08f, y = r.bottom - h * 0.1f, t = Math.max(3, h * 0.028f);
            bar.setColor(Glass.withAlpha(Color.WHITE, 0.3f));
            c.drawRoundRect(r.left + inset, y, r.right - inset, y + t, t / 2, t / 2, bar);
            bar.setColor(Glass.withAlpha(Color.WHITE, 0.95f));
            c.drawRoundRect(r.left + inset, y, r.left + inset + (r.width() - 2 * inset) * Math.min(1, progress), y + t, t / 2, t / 2, bar);
        }
        c.restore();
        c.drawRoundRect(r, rad, rad, rim);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
