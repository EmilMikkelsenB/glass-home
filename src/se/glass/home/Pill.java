package se.glass.home;

import android.content.Context;
import android.view.Gravity;
import android.widget.TextView;

/** A focusable glass text button (menus, settings, wizard, top-left notices). Springs up when focused. */
final class Pill extends TextView {
    private int tint;

    Pill(Context c, CharSequence text, int tint, int designSize) {
        super(c);
        this.tint = tint;
        setText(text);
        Ui.style(this, designSize, true, 0.96f);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(Ui.px(designSize), Ui.px(designSize * 0.45f), Ui.px(designSize), Ui.px(designSize * 0.45f));
        setFocusable(true);
        setBackground(new Glass(null, tint, true, 0.5f));
    }

    void setTint(int t) {
        if (t == tint) return;
        tint = t;
        setBackground(new Glass(null, t, true, 0.5f));
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect prev) {
        super.onFocusChanged(focused, direction, prev);
        Motion.spring(this, SCALE_X, focused ? 1.08f : 1f, Motion.SNAPPY);
        Motion.spring(this, SCALE_Y, focused ? 1.08f : 1f, Motion.SNAPPY);
        Motion.spring(this, TRANSLATION_Z, focused ? Ui.px(12) : 0f, Motion.SMOOTH);
        setBackground(new Glass(null, focused && tint == 0 ? 0x66FFFFFF : tint, true, 0.5f));
    }
}
