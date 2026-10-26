package se.glass.home;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;

import java.util.ArrayList;
import java.util.List;

/**
 * The home screen. Collapsed: big clock + the favourites row only. Press down → expanded: the clock shrinks away, the rows
 * below slide in, and vertical movement is fixed-focus (the focused row sits at one height, the stack glides on a spring).
 */
final class HomeView extends FrameLayout {
    final LinearLayout clock;        // date + rolling time
    final LinearLayout chips;        // top right: weather etc.
    final LinearLayout pills;        // top left: update, new app, now playing
    final FrameLayout toasts;        // top right below the chips: notifications from the house
    final LinearLayout rowsBox;
    final List<RowView> rows = new ArrayList<>();
    private final View scrim;
    boolean expanded;
    int focusedRow;

    HomeView(Context c) {
        super(c);
        setClipChildren(false);
        scrim = new View(c);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0, 0x59000000, 0x99000000}));
        scrim.setAlpha(0f);
        addView(scrim, Ui.match());

        clock = new LinearLayout(c);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setGravity(Gravity.CENTER_HORIZONTAL);
        TextClock date = new TextClock(c);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        Ui.style(date, Theme.Style.DATE, Theme.SECONDARY);
        clock.addView(date, new LinearLayout.LayoutParams(-2, -2));
        RollingClock time = new RollingClock(c, 250);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = Ui.px(4);
        clock.addView(time, tp);
        addView(clock, Ui.at(-1, 118));

        chips = new LinearLayout(c);
        chips.setGravity(Gravity.END);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cp.topMargin = Ui.px(Theme.SAFE_Y);
        cp.rightMargin = Ui.px(Theme.SAFE_X);
        addView(chips, cp);

        pills = new LinearLayout(c);
        pills.setClipChildren(false);
        addView(pills, Ui.at(Theme.SAFE_X, Theme.SAFE_Y));

        toasts = new FrameLayout(c);
        toasts.setClipChildren(false);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(Ui.px(560), -2, Gravity.TOP | Gravity.END);
        np.topMargin = Ui.px(Theme.SAFE_Y + 80);
        np.rightMargin = Ui.px(Theme.SAFE_X);
        addView(toasts, np);

        rowsBox = new LinearLayout(c);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        rowsBox.setClipChildren(false);
        rowsBox.setClipToPadding(false);
        addView(rowsBox, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        rowsBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) apply(false);
        });
    }

    void addRow(RowView r) {
        r.rowIndex = rows.size();
        rows.add(r);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        if (r.rowIndex > 0) lp.topMargin = Ui.px(Theme.S3);
        rowsBox.addView(r, lp);
    }

    void clearRows() {
        rows.clear();
        rowsBox.removeAllViews();
    }

    /** Index of the row containing `v`, or -1. */
    int rowOf(View v) {
        for (View p = v; p != null && p != this; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p instanceof RowView) return ((RowView) p).rowIndex;
        }
        return -1;
    }

    /** Next row in `dir` (+1 down, -1 up) that has cards, or -1. */
    int neighbour(int from, int dir) {
        for (int i = from + dir; i >= 0 && i < rows.size(); i += dir) {
            if (rows.get(i).getVisibility() == VISIBLE && rows.get(i).count() > 0) return i;
        }
        return -1;
    }

    void setExpanded(boolean e) {
        if (expanded == e) return;
        expanded = e;
        if (!e) focusedRow = 0;
        apply(true);
    }

    void onRowFocused(int r) {
        if (r == focusedRow) return;
        focusedRow = r;
        apply(true);
    }

    /** Move everything to where the current state says it belongs. */
    void apply(boolean animate) {
        if (rows.isEmpty()) return;
        RowView focus = rows.get(Math.min(focusedRow, rows.size() - 1));
        float anchor = Ui.px(!expanded ? 640 : focusedRow == 0 ? 330 : 280);
        float rowsY = anchor - focus.getTop() + (focus.title != null ? 0 : 0);
        float clockScale = expanded ? 0.6f : 1f;
        float clockY = expanded ? -Ui.px(84) : 0f;
        float clockAlpha = !expanded || focusedRow == 0 ? 1f : 0f;
        clock.setPivotX(clock.getWidth() / 2f);
        clock.setPivotY(0);
        go(rowsBox, TRANSLATION_Y, rowsY, animate);
        go(clock, SCALE_X, clockScale, animate);
        go(clock, SCALE_Y, clockScale, animate);
        go(clock, TRANSLATION_Y, clockY, animate);
        fade(clock, clockAlpha, animate);
        fade(pills, clockAlpha, animate);   // the row above would run into them
        fade(chips, clockAlpha, animate);
        fade(scrim, expanded ? 1f : 0f, animate);
        for (RowView r : rows) {
            float a = !expanded ? (r.rowIndex == 0 ? 1f : 0f)
                    : r.rowIndex < focusedRow - 1 ? 0f : r.rowIndex == focusedRow - 1 ? 0.3f : 1f;
            fade(r, a, animate, expanded ? Math.max(0, r.rowIndex - focusedRow) * 40 : 0);
            r.setDescendantFocusability(!expanded && r.rowIndex > 0 ? ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    : ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
    }

    private static void go(View v, android.util.Property<View, Float> p, float to, boolean animate) {
        Motion.Spring s = Motion.spring(v, p, to, Motion.LAYOUT);
        if (!animate) s.snap(to);
    }

    private static void fade(View v, float to, boolean animate) {
        fade(v, to, animate, 0);
    }

    private static void fade(View v, float to, boolean animate, int delayMs) {
        v.animate().cancel();
        if (!animate) {
            v.setAlpha(to);
            return;
        }
        v.animate().alpha(to).setStartDelay(to > v.getAlpha() ? delayMs : 0).setDuration(420).setInterpolator(Motion.EASE).start();
    }
}
