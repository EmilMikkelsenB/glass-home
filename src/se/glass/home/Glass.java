package se.glass.home;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Glass card drawn at runtime, matching make_assets.py: translucent fill, light rim, optional tint and centred content. */
final class Glass extends Drawable {
    private final Drawable content;
    private final int tint;        // 0 = clear white glass
    private final boolean fill;    // true: shape fills the bounds; false: 1.5:1 shape centred like the wide PNGs
    private final float radius;    // corner radius as a fraction of shape height
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    Glass(Drawable content, int tint, boolean fill, float radius) {
        this.content = content;
        this.tint = tint;
        this.fill = fill;
        this.radius = radius;
    }

    private RectF shape() {
        Rect b = getBounds();
        if (fill) {
            float i = b.height() * 0.04f;
            return new RectF(b.left + i, b.top + i, b.right - i, b.bottom - i);
        }
        float h = b.height() * 0.78f, w = Math.min(h * 1.5f, b.width() * 0.95f);
        float cx = b.exactCenterX(), cy = b.exactCenterY() - b.height() * 0.011f;
        return new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
    }

    @Override
    public void draw(Canvas c) {
        RectF r = shape();
        float rad = r.height() * radius;
        int top = tint != 0 ? withAlpha(tint, 0.62f) : withAlpha(Color.WHITE, 0.20f);
        int bottom = tint != 0 ? withAlpha(tint, 0.42f) : withAlpha(Color.WHITE, 0.07f);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, r.top, 0, r.bottom, top, bottom, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, paint);

        // rim: bright at top-left, fading towards bottom-right (Liquid Glass specular edge)
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, r.height() / 120f));
        paint.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                withAlpha(Color.WHITE, 0.85f), withAlpha(Color.WHITE, 0.15f), Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, paint);
        paint.setShader(null);

        if (content != null) {
            int iw = content.getIntrinsicWidth(), ih = content.getIntrinsicHeight();
            float maxW = r.width() * 0.6f, maxH = r.height() * 0.5f;
            float s = iw > 0 && ih > 0 ? Math.min(maxW / iw, maxH / ih) : 1;
            int w = iw > 0 ? Math.round(iw * s) : (int) maxH, h = ih > 0 ? Math.round(ih * s) : (int) maxH;
            content.setBounds(Math.round(r.centerX() - w / 2f), Math.round(r.centerY() - h / 2f),
                    Math.round(r.centerX() + w / 2f), Math.round(r.centerY() + h / 2f));
            content.draw(c);
        }
    }

    static int withAlpha(int color, float a) {
        return (color & 0xFFFFFF) | (Math.round(a * 255) << 24);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
