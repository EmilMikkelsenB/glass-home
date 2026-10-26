package se.glass.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.FloatProperty;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * A tvOS-style layered app icon (16:10): a plate (brand-coloured glass over the frosted backdrop, or the app's own banner),
 * a logo layer floating above it, a soft top light and a lit rim. When focused, the layers drift at different depths
 * (parallax) and the rim light moves with them, so the icon feels like a physical object instead of a sticker.
 */
final class AppIcon extends View {
    /** Parallax amount, -1 … 1 per axis (spring-animated by CardView). */
    static final FloatProperty<View> PARALLAX_X = new FloatProperty<View>("parallaxX") {
        @Override public void setValue(View v, float f) { ((AppIcon) v).setParallax(f, ((AppIcon) v).py); }
        @Override public Float get(View v) { return ((AppIcon) v).px; }
    };
    static final FloatProperty<View> PARALLAX_Y = new FloatProperty<View>("parallaxY") {
        @Override public void setValue(View v, float f) { ((AppIcon) v).setParallax(((AppIcon) v).px, f); }
        @Override public Float get(View v) { return ((AppIcon) v).py; }
    };

    private final int brand;          // plate colour; 0 = clear glass plate
    private final Bitmap logo;        // centred logo on a 16:10 transparent canvas (may be null)
    private final Drawable banner;    // the app's own banner when we have no logo (full-bleed plate)
    private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG), light = new Paint(Paint.ANTI_ALIAS_FLAG),
            rim = new Paint(Paint.ANTI_ALIAS_FLAG), bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF shape = new RectF(), logoRect = new RectF();
    private Path path = new Path(), rimPath = new Path();
    private float px, py;
    private boolean focused;
    private final Drawable invalidator = new Drawable() {   // lets Backdrop invalidate us like any glass surface
        @Override public void draw(Canvas c) {}
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public void invalidateSelf() { invalidate(); }
    };

    AppIcon(Context c, int brand, Bitmap logo, Drawable banner) {
        super(c);
        this.brand = brand;
        this.logo = logo;
        this.banner = banner;
        rim.setStyle(Paint.Style.STROKE);
        Backdrop.SHARED.register(invalidator);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                if (!path.isEmpty()) o.setPath(path);
                o.setAlpha(0.55f);
            }
        });
    }

    void setFocused(boolean f) {
        if (f == focused) return;
        focused = f;
        rebuild();
        invalidate();
    }

    void setParallax(float x, float y) {
        px = x;
        py = y;
        rebuildRim();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        shape.set(0, 0, w, h);
        path = Squircle.path(shape, h * Theme.CARD_RADIUS);
        if (banner != null) banner.setBounds(0, 0, w, h);
        rebuild();
        invalidateOutline();
    }

    private void rebuild() {
        float h = shape.height();
        if (h == 0) return;
        if (brand != 0) {   // brand plate: rich at the bottom, lighter at the top, still a touch see-through
            plate.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{Glass.withAlpha(lighten(brand, 0.12f), 0.9f), Glass.withAlpha(brand, 0.94f), Glass.withAlpha(Glass.darken(brand, 0.78f), 0.97f)},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        } else {            // clear plate (our own tiles): frosted glass
            plate.setShader(new LinearGradient(0, 0, 0, h, Glass.withAlpha(Color.WHITE, focused ? 0.2f : 0.13f),
                    Glass.withAlpha(Color.WHITE, focused ? 0.1f : 0.05f), Shader.TileMode.CLAMP));
        }
        light.setShader(new LinearGradient(0, 0, 0, h * 0.5f, Glass.withAlpha(Color.WHITE, focused ? 0.2f : 0.12f), 0,
                Shader.TileMode.CLAMP));
        rebuildRim();
    }

    private void rebuildRim() {
        float h = shape.height(), w = shape.width();
        if (h == 0) return;
        float sw = Math.max(1.2f, h / 110f);
        rim.setStrokeWidth(sw);
        RectF r = new RectF(shape);
        r.inset(sw / 2, sw / 2);
        rimPath = Squircle.path(r, r.height() * Theme.CARD_RADIUS);
        float dx = -px * w * 0.3f, dy = -py * h * 0.3f;   // the light slides against the layer movement
        rim.setShader(new LinearGradient(dx, dy, w + dx, h + dy,
                new int[]{Glass.withAlpha(Color.WHITE, focused ? 0.8f : 0.45f), Glass.withAlpha(Color.WHITE, 0.08f),
                        Glass.withAlpha(Color.WHITE, focused ? 0.32f : 0.14f)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.save();
        c.clipPath(path);
        if (banner != null) {           // the app's own banner as the plate, drifting a little (deepest layer)
            c.save();
            c.translate(px * w * 0.012f, py * h * 0.012f);
            c.scale(1.03f, 1.03f, w / 2, h / 2);
            banner.draw(c);
            c.restore();
        } else {
            Backdrop.SHARED.draw(c, this);
            c.drawRect(shape, plate);
        }
        if (logo != null) {             // the logo floats above: it moves the most
            float lx = px * w * 0.035f, ly = py * h * 0.035f;
            logoRect.set(lx, ly, w + lx, h + ly);
            c.drawBitmap(logo, null, logoRect, bmp);
        }
        c.drawRect(0, 0, w, h * 0.5f, light);
        c.drawPath(rimPath, rim);
        c.restore();
    }

    static int lighten(int color, float f) {
        return Color.rgb(Math.round(Color.red(color) + (255 - Color.red(color)) * f),
                Math.round(Color.green(color) + (255 - Color.green(color)) * f),
                Math.round(Color.blue(color) + (255 - Color.blue(color)) * f));
    }
}
