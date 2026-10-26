package se.glass.home;

import android.content.Context;
import android.view.Gravity;
import android.widget.TextView;

/** A focusable glass capsule (menus, settings, wizard, top-left notices). Lifts on a spring when focused. */
final class Pill extends TextView {
    private final Glass glass;

    Pill(Context c, CharSequence text, int tint, int designSize) {
        super(c);
        setText(text);
        Ui.style(this, designSize >= 30 ? Theme.Style.HEADLINE : Theme.Style.CAPTION, Theme.PRIMARY);
        if (designSize >= 30) setTypeface(Theme.font(c, 500));
        setGravity(Gravity.CENTER_VERTICAL);
        int ph = Ui.px(designSize >= 30 ? Theme.S4 : Theme.S3), pv = Ui.px(designSize >= 30 ? 18 : 12);
        setPadding(ph, pv, ph, pv);
        setFocusable(true);
        glass = new Glass(null, tint, true, 0.5f);
        setBackground(glass);
        setClipToOutline(false);
    }

    void setTint(int t) {
        glass.setGlassTint(t);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect prev) {
        super.onFocusChanged(focused, direction, prev);
        float[] s = focused ? Motion.FOCUS_IN : Motion.FOCUS_OUT;
        Motion.spring(this, SCALE_X, focused ? 1.06f : 1f, s);
        Motion.spring(this, SCALE_Y, focused ? 1.06f : 1f, s);
        Motion.spring(this, TRANSLATION_Z, focused ? Ui.px(14) : 0f, s);
        glass.setFocused(focused);
    }
}
