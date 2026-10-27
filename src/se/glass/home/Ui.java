package se.glass.home;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Locale;

/** Shared UI helpers: 1920-wide design units, text styles from Theme, language. Call {@link #init} in every activity's onCreate. */
final class Ui {
    static int W = 1920, H = 1080;
    static Typeface semibold = Typeface.DEFAULT_BOLD;
    private static Context app;

    private Ui() {}

    static void init(Activity a) {
        Rect r = a.getWindowManager().getCurrentWindowMetrics().getBounds();
        W = r.width();
        H = r.height();
        app = a.getApplicationContext();
        semibold = Theme.font(app, 600);
    }

    /** For non-activity contexts (the screensaver): the display's size. */
    static void init(Context c) {
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        W = Math.max(m.widthPixels, m.heightPixels);
        H = Math.min(m.widthPixels, m.heightPixels);
        app = c.getApplicationContext();
        semibold = Theme.font(app, 600);
    }

    /** Design pixels (on a 1920×1080 screen) → real pixels. */
    static int px(float v) {
        return Math.round(v * W / 1920f);
    }

    /** Swedish when the TV is set to Swedish, English otherwise. */
    static String tr(String sv, String en) {
        return Locale.getDefault().getLanguage().equals("sv") ? sv : en;
    }

    /** "192.168.1.10:8096/" → "http://192.168.1.10:8096": people often type server addresses without the scheme. */
    static String serverUrl(String u) {
        u = u.trim().replaceAll("/+$", "");
        return u.contains("://") ? u : "http://" + u;
    }

    /** Apply a Theme text style with an opacity tier (Theme.PRIMARY/SECONDARY/TERTIARY). */
    static void style(TextView t, Theme.Style s, float alpha) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(s.size));
        t.setTypeface(Theme.font(t.getContext(), s.weight));
        t.setLetterSpacing(s.tracking);
        t.setTextColor(Glass.withAlpha(0xFFFFFF, alpha));
        t.setIncludeFontPadding(false);
        // tiers carry hierarchy; only very large type gets a whisper of shadow to separate it from bright wallpapers
        if (s.size >= 100) t.setShadowLayer(px(14), 0, px(2), 0x2E000000);
        else t.setShadowLayer(px(6), 0, px(1), 0x24000000);
    }

    static TextView text(Context c, CharSequence s, Theme.Style style, float alpha) {
        TextView t = new TextView(c);
        t.setText(s);
        style(t, style, alpha);
        return t;
    }

    /** Legacy size-based API: maps to the nearest Theme style. */
    static void style(TextView t, int designSize, boolean bold, float alpha) {
        Theme.Style s = designSize >= 150 ? Theme.Style.CLOCK : designSize >= 50 ? Theme.Style.LARGE_TITLE
                : designSize >= 36 ? (bold ? Theme.Style.TITLE : Theme.Style.BODY)
                : designSize >= 28 ? (bold ? Theme.Style.HEADLINE : Theme.Style.BODY)
                : designSize >= 24 ? (bold ? Theme.Style.HEADLINE : Theme.Style.BODY) : Theme.Style.CAPTION;
        style(t, s, alpha);
        if (designSize != s.size && designSize < 150) t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(designSize));
        if (!bold && s.weight > 500) t.setTypeface(Theme.font(t.getContext(), 400));
    }

    static TextView text(Context c, CharSequence s, int designSize, boolean bold, float alpha) {
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
