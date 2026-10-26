package se.glass.home;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * The glass material. Bottom → top: the frosted backdrop that is really behind it (Backdrop), a tint, a soft top light,
 * an inner rim lit from the top-left (the light follows `setLight`), and optional centred content. Continuous (squircle)
 * corners; the outline gives a matching RenderThread shadow. Shaders and paths are built per size, never in draw().
 */
final class Glass extends Drawable {
    private final Drawable content;
    private int tint;              // 0 = clear glass; opaque colour = tinted glass; translucent colour = used as-is
    private final boolean fill;    // true: shape fills the bounds; false: 1.5:1 shape centred like the old wide PNGs
    private final float radius;    // corner radius as a fraction of shape height
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG), lightPaint = new Paint(Paint.ANTI_ALIAS_FLAG),
            rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RectF shape = new RectF();
    private Path path = new Path(), rimPath = new Path();
    private boolean focused;
    private float light;           // -1 … 1: shifts the specular rim (layered parallax)
    private boolean sample = true;

    Glass(Drawable content, int tint, boolean fill, float radius) {
        this.content = content;
        this.tint = tint;
        this.fill = fill;
        this.radius = radius;
        rimPaint.setStyle(Paint.Style.STROKE);
        Backdrop.SHARED.register(this);
    }

    /** Glass without backdrop sampling (for surfaces that already sit on a frosted panel). */
    Glass noSample() {
        sample = false;
        return this;
    }

    void setGlassTint(int t) {
        if (t == tint) return;
        tint = t;
        rebuild();
        invalidateSelf();
    }

    void setFocused(boolean f) {
        if (f == focused) return;
        focused = f;
        rebuild();
        invalidateSelf();
    }

    void setLight(float l) {
        if (Math.abs(l - light) < 0.01f) return;
        light = l;
        rebuildRim();
        invalidateSelf();
    }

    RectF shape() {
        return shape;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        if (fill) {
            float i = Math.min(b.width(), b.height()) * 0.02f;
            shape = new RectF(b.left + i, b.top + i, b.right - i, b.bottom - i);
        } else {
            float h = b.height() * 0.78f, w = Math.min(h * 1.5f, b.width() * 0.95f);
            float cx = b.exactCenterX(), cy = b.exactCenterY() - b.height() * 0.011f;
            shape = new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        }
        path = Squircle.path(shape, shape.height() * radius);
        rebuild();
        if (content != null) {
            int iw = content.getIntrinsicWidth(), ih = content.getIntrinsicHeight();
            float maxW = shape.width() * 0.6f, maxH = shape.height() * 0.5f;
            float s = iw > 0 && ih > 0 ? Math.min(maxW / iw, maxH / ih) : 1;
            int w = iw > 0 ? Math.round(iw * s) : (int) maxH, h = ih > 0 ? Math.round(ih * s) : (int) maxH;
            content.setBounds(Math.round(shape.centerX() - w / 2f), Math.round(shape.centerY() - h / 2f),
                    Math.round(shape.centerX() + w / 2f), Math.round(shape.centerY() + h / 2f));
        }
    }

    private void rebuild() {
        if (shape.isEmpty()) return;
        int a = tint >>> 24;
        int top, bottom;
        if (tint == 0) {           // clear glass: a whisper of white, a bit more when focused
            float base = Theme.GLASS_WHITE + (focused ? 0.08f : 0f);
            top = withAlpha(Color.WHITE, base + 0.04f);
            bottom = withAlpha(Color.WHITE, base * 0.5f);
        } else if (a == 0xFF) {    // tinted glass (amber "on", blue buttons): vivid but still see-through
            float base = focused ? 0.92f : 0.84f;   // nearly opaque: translucent amber over blue turns muddy
            top = withAlpha(AppIcon.lighten(tint, 0.12f), base);
            bottom = withAlpha(darken(tint, 0.86f), base);
        } else {
            top = tint;
            bottom = withAlpha(tint, a / 255f * 0.6f);
        }
        fillPaint.setShader(new LinearGradient(0, shape.top, 0, shape.bottom, top, bottom, Shader.TileMode.CLAMP));
        // soft top light: the upper third catches a little more light
        lightPaint.setShader(new LinearGradient(0, shape.top, 0, shape.top + shape.height() * 0.45f,
                withAlpha(Color.WHITE, focused ? 0.14f : 0.08f), 0, Shader.TileMode.CLAMP));
        rebuildRim();
    }

    private void rebuildRim() {
        if (shape.isEmpty()) return;
        rimPaint.setStrokeWidth(Math.max(1.2f, shape.height() / 140f));
        RectF rim = new RectF(shape);
        rim.inset(rimPaint.getStrokeWidth() / 2f, rimPaint.getStrokeWidth() / 2f);
        rimPath = Squircle.path(rim, rim.height() * radius);
        float dx = shape.width() * 0.25f * light;
        rimPaint.setShader(new LinearGradient(shape.left + dx, shape.top, shape.right + dx, shape.bottom,
                new int[]{withAlpha(Color.WHITE, focused ? 0.75f : 0.5f), withAlpha(Color.WHITE, 0.1f), withAlpha(Color.WHITE, focused ? 0.3f : 0.16f)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        c.save();
        c.clipPath(path);
        Object host = getCallback();
        if (sample && host instanceof View) Backdrop.SHARED.draw(c, (View) host);
        c.drawRect(shape, fillPaint);
        c.drawRect(shape.left, shape.top, shape.right, shape.top + shape.height() * 0.45f, lightPaint);
        c.drawPath(rimPath, rimPaint);
        c.restore();
        if (content != null) content.draw(c);
    }

    @Override
    public void getOutline(Outline o) {
        if (path.isEmpty()) return;
        o.setPath(path);
        o.setAlpha(0.6f);
    }

    static int withAlpha(int color, float a) {
        return (color & 0xFFFFFF) | (Math.round(Math.max(0, Math.min(1, a)) * 255) << 24);
    }

    static int darken(int color, float f) {
        return Color.rgb(Math.round(Color.red(color) * f), Math.round(Color.green(color) * f), Math.round(Color.blue(color) * f));
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
