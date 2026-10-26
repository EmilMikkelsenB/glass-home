package se.glass.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A focusable glass card: artwork on top, one or two captions below. On focus the artwork springs up, tilts toward the
 * direction you came from, a light sheen sweeps across it and its (RenderThread) shadow grows — the Apple TV "lift".
 */
class CardView extends LinearLayout {
    final FrameLayout art;
    final TextView title, subtitle;
    private final Sheen sheen;
    private final float focusScale;
    private final boolean wideGlass;   // artwork is a 1.5:1 glass shape centred on a 16:9 canvas (the generated PNGs)
    int index;                        // position in its row (set by RowView)

    /**
     * @param content   the artwork view, sized to w × h
     * @param wideGlass true for generated glass PNGs (shadow follows the centred glass shape), false for full-bleed posters
     */
    CardView(Context c, View content, int w, int h, CharSequence titleText, CharSequence sub, float focusScale, boolean wideGlass) {
        super(c);
        this.focusScale = focusScale;
        this.wideGlass = wideGlass;
        setOrientation(VERTICAL);
        setGravity(wideGlass ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        setFocusable(true);
        setClipChildren(false);
        setClipToPadding(false);

        art = new FrameLayout(c);
        art.setClipChildren(false);
        art.addView(content, new FrameLayout.LayoutParams(w, h));
        sheen = new Sheen(c);
        art.addView(sheen, new FrameLayout.LayoutParams(w, h));
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                int vw = v.getWidth(), vh = v.getHeight();
                if (wideGlass) {   // same geometry as Glass.shape(): 78 % high, 1.5:1, centred
                    int gh = Math.round(vh * 0.78f), gw = Math.min(Math.round(gh * 1.5f), vw);
                    int l = (vw - gw) / 2, t = (vh - gh) / 2 - Math.round(vh * 0.011f);
                    o.setRoundRect(l, t, l + gw, t + gh, gh * 0.28f);
                } else {
                    o.setRoundRect(0, 0, vw, vh, vh * 0.12f);
                }
                o.setAlpha(0.55f);
            }
        });
        art.setCameraDistance(Ui.px(4000) * getResources().getDisplayMetrics().density);
        addView(art, new LayoutParams(w, h));

        title = caption(titleText, wideGlass ? 26 : 26, !wideGlass, wideGlass ? 0.8f : 0.95f, w);
        if (title != null) {
            LayoutParams tp = new LayoutParams(w, -2);
            tp.topMargin = Ui.px(wideGlass ? 2 : 12);
            addView(title, tp);
        }
        subtitle = caption(sub, 22, false, 0.75f, w);
        if (subtitle != null) addView(subtitle, new LayoutParams(w, -2));
        if (title != null && wideGlass) title.setGravity(Gravity.CENTER);
    }

    private TextView caption(CharSequence s, int size, boolean bold, float alpha, int w) {
        if (s == null) return null;
        TextView t = Ui.text(getContext(), s, size, bold, alpha);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect prev) {
        super.onFocusChanged(focused, direction, prev);
        lift(focused);
    }

    void lift(boolean focused) {
        art.setLayerType(LAYER_TYPE_HARDWARE, null);   // cheap transforms while it moves
        Motion.spring(art, SCALE_X, focused ? focusScale : 1f, Motion.SNAPPY);
        Motion.spring(art, SCALE_Y, focused ? focusScale : 1f, Motion.SNAPPY)
                .onRest(() -> art.setLayerType(LAYER_TYPE_NONE, null));
        Motion.spring(art, TRANSLATION_Z, focused ? Ui.px(22) : 0f, Motion.SMOOTH);
        if (focused) {
            // tilt "kick" toward where we came from, settling flat; sheen sweeps the same way
            if (Motion.dirX != 0) Motion.kick(art, ROTATION_Y, 0f, -Motion.dirX * 90f, Motion.WOBBLY);
            if (Motion.dirY != 0) Motion.kick(art, ROTATION_X, 0f, Motion.dirY * 70f, Motion.WOBBLY);
            sheen.sweep(Motion.dirX < 0);
        }
        if (title != null) title.animate().alpha(focused ? 1f : wideGlass ? 0.8f : 0.95f).setDuration(200).start();
        if (!wideGlass) {   // full-bleed cards get a soft white ring (glass PNGs already have their own rim)
            if (ring == null) ring = new Ring(ringRadius);
            art.setForeground(ring);
            android.animation.ObjectAnimator.ofInt(ring, "alpha", ring.getAlpha(), focused ? 255 : 0).setDuration(220).start();
        }
        setZ(focused ? 1 : 0);
    }

    private Ring ring;
    float ringRadius = 0.12f;   // fraction of height; tiles use their glass radius

    /** White focus ring drawn over full-bleed artwork. */
    static final class Ring extends android.graphics.drawable.Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius;
        private int alpha;

        Ring(float radius) {
            this.radius = radius;
            p.setStyle(Paint.Style.STROKE);
            p.setColor(0xFFFFFFFF);
        }

        @Override
        public void draw(Canvas c) {
            if (alpha == 0) return;
            android.graphics.Rect b = getBounds();
            float w = Math.max(3f, b.height() / 55f);
            p.setStrokeWidth(w);
            p.setAlpha(alpha * 220 / 255);
            float r = b.height() * radius;
            c.drawRoundRect(b.left + w / 2, b.top + w / 2, b.right - w / 2, b.bottom - w / 2, r, r, p);
        }

        @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }
        @Override public int getAlpha() { return alpha; }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** A soft diagonal highlight that slides once across the card on focus. */
    static final class Sheen extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float pos = -1f;   // -1 … 2 across the card, outside = invisible

        Sheen(Context c) {
            super(c);
            setWillNotDraw(false);
        }

        void sweep(boolean leftward) {
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(leftward ? 1.6f : -0.6f, leftward ? -0.6f : 1.6f);
            a.setDuration(650);
            a.setInterpolator(Motion.EASE);
            a.addUpdateListener(x -> {
                pos = (float) x.getAnimatedValue();
                invalidate();
            });
            a.start();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            // built once per size, never in onDraw
            paint.setShader(new LinearGradient(0, 0, w * 0.35f, h, new int[]{0, 0x30FFFFFF, 0},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            if (pos <= -0.6f || pos >= 1.6f) return;
            int w = getWidth(), h = getHeight();
            c.save();
            c.clipRect(w * 0.12f, h * 0.1f, w * 0.88f, h * 0.9f);   // stay inside the glass
            c.translate(pos * w - w * 0.2f, 0);
            c.drawRect(0, 0, w * 0.35f, h, paint);
            c.restore();
        }
    }
}
