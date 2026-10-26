package se.glass.home;

import android.animation.TimeAnimator;
import android.util.Property;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.util.List;

/**
 * Hand-rolled motion (no AndroidX): damped springs that keep their velocity when retargeted, so interrupted animations
 * never jump, plus iOS curves. Everything animates RenderThread-friendly properties (scale, translation, rotation, alpha).
 */
final class Motion {
    /** iOS "ease out" used for fades and panel moves. */
    static final PathInterpolator EASE = new PathInterpolator(0.32f, 0.72f, 0f, 1f);

    /** Last D-pad direction, for tilt and parallax: -1 left/up, +1 right/down, 0 none. */
    static int dirX, dirY;

    private Motion() {}

    static void noteKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: dirX = -1; dirY = 0; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dirX = 1; dirY = 0; break;
            case KeyEvent.KEYCODE_DPAD_UP: dirX = 0; dirY = -1; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dirX = 0; dirY = 1; break;
            default: break;
        }
    }

    /** Response presets: stiffness, damping ratio. */
    static final float[] SNAPPY = {420f, 0.78f};   // focus scale: quick with a little overshoot
    static final float[] SMOOTH = {180f, 0.95f};   // scrolling and layout moves: no bounce
    static final float[] WOBBLY = {260f, 0.45f};   // tilt kick, settles with a wobble

    /** Animate `prop` of `v` to `target`. Re-targeting a running spring keeps its velocity. */
    static Spring spring(View v, Property<View, Float> prop, float target, float[] preset) {
        int key = 0x7f000000 | (prop.getName().hashCode() & 0xffff);
        Object tag = v.getTag(key);
        Spring s = tag instanceof Spring ? (Spring) tag : null;
        if (s == null) {
            s = new Spring(v, prop);
            v.setTag(key, s);
        }
        s.to(target, preset);
        return s;
    }

    /** A spring kick: start from the current value with extra velocity, settle back at `rest`. */
    static void kick(View v, Property<View, Float> prop, float rest, float velocity, float[] preset) {
        spring(v, prop, rest, preset).addVelocity(velocity);
    }

    /** Fade/slide views in one after another (panel tiles, rows). */
    static void stagger(List<View> views, int stepMs, float fromDy) {
        for (int i = 0; i < views.size(); i++) {
            View v = views.get(i);
            v.setAlpha(0f);
            v.setTranslationY(fromDy);
            v.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(i * stepMs, 400)).setDuration(380)
                    .setInterpolator(EASE).withLayer().start();
        }
    }

    static final class Spring implements TimeAnimator.TimeListener {
        private final View view;
        private final Property<View, Float> prop;
        private final TimeAnimator clock = new TimeAnimator();
        private float target, velocity, stiffness, damping;
        private Runnable onRest;

        Spring(View view, Property<View, Float> prop) {
            this.view = view;
            this.prop = prop;
            clock.setTimeListener(this);
        }

        Spring to(float t, float[] preset) {
            target = t;
            stiffness = preset[0];
            damping = 2f * preset[1] * (float) Math.sqrt(preset[0]);   // ratio → coefficient (mass 1)
            if (!clock.isStarted()) clock.start();
            return this;
        }

        Spring addVelocity(float v) {
            velocity += v;
            if (!clock.isStarted()) clock.start();
            return this;
        }

        Spring onRest(Runnable r) {
            onRest = r;
            return this;
        }

        void snap(float value) {
            clock.cancel();
            velocity = 0;
            target = value;
            prop.set(view, value);
        }

        @Override
        public void onTimeUpdate(TimeAnimator a, long total, long deltaMs) {
            float x = prop.get(view);
            float dt = Math.min(deltaMs, 48) / 1000f;
            for (float step = 0.004f; dt > 0; dt -= step) {   // semi-implicit Euler in 4 ms sub-steps: stable at any frame rate
                float h = Math.min(step, dt);
                velocity += (-stiffness * (x - target) - damping * velocity) * h;
                x += velocity * h;
            }
            float scale = Math.max(1f, Math.abs(target));
            if (Math.abs(velocity) < 0.002f * scale && Math.abs(x - target) < 0.0005f * scale) {
                x = target;
                velocity = 0;
                clock.cancel();
                prop.set(view, x);
                if (onRest != null) {
                    Runnable r = onRest;
                    onRest = null;
                    r.run();
                }
                return;
            }
            prop.set(view, x);
        }
    }
}
