package se.glass.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A focusable card: artwork (an AppIcon, a Poster, a tile…) with optional captions below. On focus it lifts on a spring, its
 * shadow deepens, an AppIcon's layers drift into parallax and posters get a soft white rim. App tiles show their name only
 * while focused (tvOS); media cards keep two lines of caption.
 */
class CardView extends LinearLayout {
    final FrameLayout art;
    final View content;
    final TextView title, subtitle;
    private final float focusScale;
    private final boolean labelOnFocus;
    private final Rim rim;
    int index;                        // position in its row (set by RowView)
    float ringRadius = Theme.CARD_RADIUS;

    CardView(Context c, View content, int w, int h, CharSequence titleText, CharSequence sub, float focusScale, boolean labelOnFocus) {
        super(c);
        this.content = content;
        this.focusScale = focusScale;
        this.labelOnFocus = labelOnFocus;
        setOrientation(VERTICAL);
        setGravity(labelOnFocus ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        setFocusable(true);
        setClipChildren(false);
        setClipToPadding(false);

        art = new FrameLayout(c);
        art.setClipChildren(false);
        art.addView(content, new FrameLayout.LayoutParams(w, h));
        rim = content instanceof AppIcon ? null : new Rim();
        if (rim != null) art.setForeground(rim);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                RectF r = new RectF(0, 0, v.getWidth(), v.getHeight());
                if (r.isEmpty()) return;
                o.setPath(Squircle.path(r, r.height() * ringRadius));
                o.setAlpha(0.85f);
            }
        });
        addView(art, new LayoutParams(w, h));

        title = caption(titleText, labelOnFocus ? Theme.Style.CAPTION : Theme.Style.HEADLINE, Theme.PRIMARY);
        if (title != null) {
            LayoutParams tp = new LayoutParams(labelOnFocus ? w + Ui.px(80) : w, -2);   // focus labels may overhang
            tp.topMargin = Ui.px(labelOnFocus ? 18 : 14);
            if (labelOnFocus) {
                title.setGravity(Gravity.CENTER);
                title.setAlpha(0f);
                tp.leftMargin = tp.rightMargin = -Ui.px(40);
            }
            addView(title, tp);
        }
        subtitle = caption(sub, Theme.Style.CAPTION, Theme.SECONDARY);
        if (subtitle != null) {
            LayoutParams sp = new LayoutParams(w, -2);
            sp.topMargin = Ui.px(4);
            addView(subtitle, sp);
        }
    }

    private TextView caption(CharSequence s, Theme.Style style, float alpha) {
        if (s == null) return null;
        TextView t = Ui.text(getContext(), s, style, alpha);
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
        float[] spring = focused ? Motion.FOCUS_IN : Motion.FOCUS_OUT;
        art.setLayerType(LAYER_TYPE_HARDWARE, null);   // cheap transforms while it moves
        Motion.spring(art, SCALE_X, focused ? focusScale : 1f, spring);
        Motion.spring(art, SCALE_Y, focused ? focusScale : 1f, spring)
                .onRest(() -> art.setLayerType(LAYER_TYPE_NONE, null));
        Motion.spring(art, TRANSLATION_Z, focused ? Ui.px(40) : 0f, spring);
        if (content instanceof AppIcon) {
            AppIcon icon = (AppIcon) content;
            icon.setFocused(focused);
            if (focused) {   // layers trail the move, then settle centred
                icon.setParallax(-Motion.dirX, -Motion.dirY);
                Motion.spring(icon, AppIcon.PARALLAX_X, 0f, Motion.LAYOUT);
                Motion.spring(icon, AppIcon.PARALLAX_Y, 0f, Motion.LAYOUT);
            }
        }
        if (rim != null) {
            android.animation.ObjectAnimator.ofInt(rim, "alpha", rim.getAlpha(), focused ? 255 : 0).setDuration(focused ? 180 : 260).start();
        }
        if (title != null && labelOnFocus) {
            title.animate().cancel();
            title.animate().alpha(focused ? 1f : 0f).translationY(focused ? 0f : -Ui.px(6))
                    .setStartDelay(focused ? 120 : 0).setDuration(focused ? 220 : 140).setInterpolator(Motion.EASE).start();
        }
        setZ(focused ? 1 : 0);
    }

    /** Soft white focus rim on a squircle, with a faint outer glow. */
    final class Rim extends android.graphics.drawable.Drawable {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Path path = new Path();
        private int alpha;

        Rim() {
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(0xFFFFFFFF);
            glow.setStyle(Paint.Style.STROKE);
            glow.setColor(0xFFFFFFFF);
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect b) {
            float w = Math.max(2f, b.height() / 70f);
            stroke.setStrokeWidth(w);
            glow.setStrokeWidth(w * 4);
            glow.setMaskFilter(new android.graphics.BlurMaskFilter(w * 3, android.graphics.BlurMaskFilter.Blur.NORMAL));
            RectF r = new RectF(b);
            r.inset(w / 2, w / 2);
            path = Squircle.path(r, r.height() * ringRadius);
        }

        @Override
        public void draw(Canvas c) {
            if (alpha == 0) return;
            glow.setAlpha(alpha * 40 / 255);
            c.drawPath(path, glow);
            stroke.setAlpha(alpha * 200 / 255);
            c.drawPath(path, stroke);
        }

        @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }
        @Override public int getAlpha() { return alpha; }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
