package se.glass.home;

import android.content.Context;
import android.graphics.Typeface;

/**
 * Design tokens: one place for the type scale, spacing, safe area, text tiers and material constants. All sizes are design
 * pixels on a 1920×1080 canvas (convert with Ui.px).
 */
final class Theme {
    private Theme() {}

    // ---- layout ----------------------------------------------------------------
    /** TV title-safe area (5 %): content never sits closer to the screen edge. */
    static final int SAFE_X = 96, SAFE_Y = 54;
    /** Rows of artwork may bleed a little further than text (tvOS rows run almost edge to edge). */
    static final int ROW_BLEED_X = 48;
    /** 8-pt spacing scale. */
    static final int S1 = 8, S2 = 16, S3 = 24, S4 = 32, S6 = 48, S8 = 64, S12 = 96;

    // ---- type --------------------------------------------------------------------
    enum Style {
        CLOCK(220, 600, -0.04f),
        LARGE_TITLE(56, 700, -0.02f),
        TITLE(40, 600, -0.015f),
        HEADLINE(30, 600, -0.01f),
        BODY(26, 400, 0f),
        CAPTION(22, 500, 0.01f),
        DATE(34, 600, 0f);

        final int size, weight;
        final float tracking;

        Style(int size, int weight, float tracking) {
            this.size = size;
            this.weight = weight;
            this.tracking = tracking;
        }
    }

    /** Text opacity tiers (instead of shadows). */
    static final float PRIMARY = 1f, SECONDARY = 0.62f, TERTIARY = 0.4f;

    // ---- material ----------------------------------------------------------------
    static final float CARD_RADIUS = 0.12f;     // of height
    static final int PANEL_RADIUS = 32;
    static final float FROST_SATURATION = 1.6f;
    static final float GLASS_WHITE = 0.08f;     // white tint over the frosted backdrop
    static final float GLASS_BRAND = 0.14f;     // brand tint for coloured glass
    static final int BLUE = 0xFF0A84FF, AMBER = 0xFFFFB340;

    // ---- fonts -------------------------------------------------------------------
    private static final Typeface[] INTER = new Typeface[4];   // 400, 500, 600, 700

    static Typeface font(Context c, int weight) {
        int i = weight >= 700 ? 3 : weight >= 600 ? 2 : weight >= 500 ? 1 : 0;
        if (INTER[i] == null) {
            try {
                INTER[i] = Typeface.createFromAsset(c.getAssets(), "fonts/inter-" + new int[]{400, 500, 600, 700}[i] + ".ttf");
            } catch (RuntimeException e) {
                INTER[i] = Typeface.create(Typeface.DEFAULT, weight, false);
            }
        }
        return INTER[i];
    }
}
