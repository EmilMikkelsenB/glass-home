package se.glass.home;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Locale;

/** Shared UI helpers: 1920-wide design units, text style, language. Call {@link #init} in every activity's onCreate. */
final class Ui {
    static int W = 1920, H = 1080;
    static Typeface semibold = Typeface.DEFAULT_BOLD;

    private Ui() {}

    static void init(Activity a) {
        Rect r = a.getWindowManager().getCurrentWindowMetrics().getBounds();
        W = r.width();
        H = r.height();
        semibold = Typeface.create(Typeface.DEFAULT, 600, false);
    }

    /** For non-activity contexts (the screensaver): the display's size. */
    static void init(android.content.Context c) {
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        W = Math.max(m.widthPixels, m.heightPixels);
        H = Math.min(m.widthPixels, m.heightPixels);
        semibold = Typeface.create(Typeface.DEFAULT, 600, false);
    }

    /** Design pixels (on a 1920×1080 screen) → real pixels. */
    static int px(float v) {
        return Math.round(v * W / 1920f);
    }

    /** Swedish when the TV is set to Swedish, English otherwise. */
    static String tr(String sv, String en) {
        return Locale.getDefault().getLanguage().equals("sv") ? sv : en;
    }

    static void style(TextView t, int designSize, boolean bold, float alpha) {
        int size = px(designSize);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        t.setTypeface(bold ? semibold : Typeface.DEFAULT);
        t.setTextColor(Glass.withAlpha(0xFFFFFF, alpha));
        t.setShadowLayer(size * 0.04f + 4, 0, 3, 0x59000000);
    }

    static TextView text(android.content.Context c, CharSequence s, int designSize, boolean bold, float alpha) {
        TextView t = new TextView(c);
        t.setText(s);
        style(t, designSize, bold, alpha);
        return t;
    }

    static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    /** Wrap-content params at (design) top/left; left < 0 centres horizontally. */
    static FrameLayout.LayoutParams at(int left, int top) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | (left < 0 ? Gravity.CENTER_HORIZONTAL : Gravity.START));
        lp.topMargin = px(top);
        if (left >= 0) lp.leftMargin = px(left);
        return lp;
    }
}
