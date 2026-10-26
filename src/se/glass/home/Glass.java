package se.glass.home;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Glass card drawn at runtime, matching make_assets.py: translucent fill, light rim, optional tint and centred content.
 * Shaders are built once per size (onBoundsChange), never in draw(), and the outline gives a rounded RenderThread shadow.
 */
final class Glass extends Drawable {
    private final Drawable content;
    private final int tint;        // 0 = clear white glass
    private final boolean fill;    // true: shape fills the bounds; false: 1.5:1 shape centred like the wide PNGs
    private final float radius;    // corner radius as a fraction of shape height
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG), rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RectF shape = new RectF();

    Glass(Drawable content, int tint, boolean fill, float radius) {
        this.content = content;
        this.tint = tint;
        this.fill = fill;
        this.radius = radius;
        rimPaint.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        if (fill) {
            float i = b.height() * 0.04f;
            shape = new RectF(b.left + i, b.top + i, b.right - i, b.bottom - i);
        } else {
            float h = b.height() * 0.78f, w = Math.min(h * 1.5f, b.width() * 0.95f);
            float cx = b.exactCenterX(), cy = b.exactCenterY() - b.height() * 0.011f;
            shape = new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        }
        int top = tint != 0 ? withAlpha(tint, 0.62f) : withAlpha(Color.WHITE, 0.20f);
        int bottom = tint != 0 ? withAlpha(tint, 0.42f) : withAlpha(Color.WHITE, 0.07f);
        fillPaint.setShader(new LinearGradient(0, shape.top, 0, shape.bottom, top, bottom, Shader.TileMode.CLAMP));
        // rim: bright at top-left, fading towards bottom-right (Liquid Glass specular edge)
        rimPaint.setStrokeWidth(Math.max(1.5f, shape.height() / 120f));
        rimPaint.setShader(new LinearGradient(shape.left, shape.top, shape.right, shape.bottom,
                withAlpha(Color.WHITE, 0.85f), withAlpha(Color.WHITE, 0.15f), Shader.TileMode.CLAMP));
        if (content != null) {
            int iw = content.getIntrinsicWidth(), ih = content.getIntrinsicHeight();
            float maxW = shape.width() * 0.6f, maxH = shape.height() * 0.5f;
            float s = iw > 0 && ih > 0 ? Math.min(maxW / iw, maxH / ih) : 1;
            int w = iw > 0 ? Math.round(iw * s) : (int) maxH, h = ih > 0 ? Math.round(ih * s) : (int) maxH;
            content.setBounds(Math.round(shape.centerX() - w / 2f), Math.round(shape.centerY() - h / 2f),
                    Math.round(shape.centerX() + w / 2f), Math.round(shape.centerY() + h / 2f));
        }
    }

    @Override
    public void draw(Canvas c) {
        float rad = shape.height() * radius;
        c.drawRoundRect(shape, rad, rad, fillPaint);
        c.drawRoundRect(shape, rad, rad, rimPaint);
        if (content != null) content.draw(c);
    }

    @Override
    public void getOutline(Outline o) {
        Rect r = new Rect();
        shape.round(r);
        o.setRoundRect(r, shape.height() * radius);
        o.setAlpha(0.5f);
    }

    static int withAlpha(int color, float a) {
        return (color & 0xFFFFFF) | (Math.round(a * 255) << 24);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
