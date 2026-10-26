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

/** Rounded 16:9 artwork for the continue-watching row: centre-cropped image, glass rim and a progress bar. */
final class Poster extends Drawable {
    private final float progress;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap bitmap;

    Poster(float progress) {
        this.progress = progress;
    }

    void setBitmap(Bitmap bm) {
        bitmap = bm;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        RectF r = new RectF(getBounds());
        float h = r.height(), rad = h * 0.12f;
        Path clip = new Path();
        clip.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        paint.setStyle(Paint.Style.FILL);
        if (bitmap != null) {
            float s = Math.max(r.width() / bitmap.getWidth(), r.height() / bitmap.getHeight());
            float sw = r.width() / s, sh = r.height() / s;
            float sx = (bitmap.getWidth() - sw) / 2, sy = (bitmap.getHeight() - sh) / 2;
            c.drawBitmap(bitmap, new Rect(Math.round(sx), Math.round(sy), Math.round(sx + sw), Math.round(sy + sh)), r, paint);
        } else {
            paint.setColor(Glass.withAlpha(Color.WHITE, 0.12f));   // placeholder while the image loads
            c.drawRect(r, paint);
        }
        if (progress > 0) {
            paint.setShader(new LinearGradient(0, r.bottom - h * 0.4f, 0, r.bottom, 0, 0x8C000000, Shader.TileMode.CLAMP));
            c.drawRect(r.left, r.bottom - h * 0.4f, r.right, r.bottom, paint);
            paint.setShader(null);
            float inset = h * 0.08f, y = r.bottom - h * 0.1f, t = Math.max(3, h * 0.028f);
            RectF track = new RectF(r.left + inset, y, r.right - inset, y + t);
            paint.setColor(Glass.withAlpha(Color.WHITE, 0.3f));
            c.drawRoundRect(track, t / 2, t / 2, paint);
            paint.setColor(Glass.withAlpha(Color.WHITE, 0.95f));
            c.drawRoundRect(new RectF(track.left, track.top, track.left + track.width() * Math.min(1, progress), track.bottom), t / 2, t / 2, paint);
        }
        c.restore();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, h / 120f));
        paint.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                Glass.withAlpha(Color.WHITE, 0.6f), Glass.withAlpha(Color.WHITE, 0.12f), Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, paint);
        paint.setShader(null);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
