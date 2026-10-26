package se.glass.home;

import android.graphics.Path;
import android.graphics.RectF;

/**
 * iOS-style "continuous" rounded rectangles: each corner is a superellipse quarter that starts curving earlier than a circular
 * arc, so the edge flows into the corner without the visible kink of a plain round-rect. Convex, so it also works as an Outline.
 */
final class Squircle {
    private static final float N = 4.2f;         // superellipse exponent (2 = circle; higher = longer, flatter shoulder)
    private static final float SPREAD = 1.45f;   // how far the curve reaches along each edge, relative to the radius
    private static final int STEPS = 14;

    private Squircle() {}

    static Path path(RectF r, float radius) {
        Path p = new Path();
        add(p, r, radius);
        return p;
    }

    static void add(Path p, RectF r, float radius) {
        float k = Math.min(radius * SPREAD, Math.min(r.width(), r.height()) / 2f);
        if (k <= 0) {
            p.addRect(r, Path.Direction.CW);
            return;
        }
        // corner centres (where the superellipse quarter is anchored), clockwise from top-right
        float[][] corners = {{r.right - k, r.top + k, 0}, {r.right - k, r.bottom - k, 1}, {r.left + k, r.bottom - k, 2}, {r.left + k, r.top + k, 3}};
        boolean first = true;
        for (float[] c : corners) {
            int q = (int) c[2];
            for (int i = 0; i <= STEPS; i++) {
                double t = (q * 90 + 270 + i * 90.0 / STEPS) % 360 * Math.PI / 180;   // q0: 270→360 (top→right)
                double cos = Math.cos(t), sin = Math.sin(t);
                float x = c[0] + (float) (k * Math.signum(cos) * Math.pow(Math.abs(cos), 2 / N));
                float y = c[1] + (float) (k * Math.signum(sin) * Math.pow(Math.abs(sin), 2 / N));
                if (first) {
                    p.moveTo(x, y);
                    first = false;
                } else {
                    p.lineTo(x, y);
                }
            }
        }
        p.close();
    }
}
