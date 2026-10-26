package se.glass.home;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * A horizontal row of cards with an optional title. Scrolling is "fixed focus": the selected card stays at the left anchor and
 * the strip glides underneath on a spring (Apple TV style). Rows that fit on screen can be centred instead.
 */
final class RowView extends FrameLayout {
    interface Listener {
        void onItemFocused(RowView row, CardView card);
    }

    final TextView title;
    final LinearLayout strip;
    private final int padLeft;
    private final boolean centreIfFits;
    private int gap;
    int lastIndex;
    int rowIndex;
    Listener listener;

    RowView(Context c, CharSequence titleText, int padLeftDesign, boolean centreIfFits) {
        super(c);
        this.padLeft = Ui.px(padLeftDesign);
        this.centreIfFits = centreIfFits;
        setClipChildren(false);
        setClipToPadding(false);
        title = titleText != null ? Ui.text(c, titleText, Theme.Style.TITLE, Theme.PRIMARY) : null;
        if (title != null) addView(title);
        strip = new LinearLayout(c);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        strip.setPadding(padLeft, Ui.px(Theme.S3), padLeft, Ui.px(Theme.S6));   // room below for the focused label
        addView(strip);
    }

    /** Replace the cards, `gap` (real px) apart. */
    void setItems(List<CardView> cards, int itemW, int gap) {
        this.gap = gap;
        strip.removeAllViews();
        for (int i = 0; i < cards.size(); i++) {
            CardView card = cards.get(i);
            card.index = i;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(itemW, -2);
            if (i > 0) lp.leftMargin = gap;
            strip.addView(card, lp);
            card.setOnFocusChangeListener((v, focused) -> {
                if (!focused) return;
                lastIndex = ((CardView) v).index;
                scrollToIndex(lastIndex, true);
                if (listener != null) listener.onItemFocused(this, (CardView) v);
            });
        }
        lastIndex = Math.min(lastIndex, Math.max(0, cards.size() - 1));
        post(() -> scrollToIndex(lastIndex, false));
    }

    int count() {
        return strip.getChildCount();
    }

    CardView card(int i) {
        return (CardView) strip.getChildAt(i);
    }

    /** Focus the card we were on last time (or the nearest existing one). */
    boolean focusLast() {
        if (count() == 0) return false;
        return card(Math.min(lastIndex, count() - 1)).requestFocus();
    }

    void scrollToIndex(int i, boolean animate) {
        if (i >= count() || getWidth() == 0) return;
        int content = strip.getMeasuredWidth(), view = getWidth();
        float target;
        if (content <= view && centreIfFits) {
            target = (view - content) / 2f;
        } else {
            target = -(card(i).getLeft() - padLeft);
            target = Math.max(Math.min(0, view - content), Math.min(0, target));
        }
        if (animate) Motion.spring(strip, TRANSLATION_X, target, Motion.SCROLL);
        else Motion.spring(strip, TRANSLATION_X, target, Motion.SCROLL).snap(target);
    }

    /** Move a card from one slot to another with a FLIP animation (move mode for favourites). */
    void moveItem(int from, int to) {
        if (to < 0 || to >= count() || from == to) return;
        int[] before = new int[count()];
        for (int i = 0; i < count(); i++) before[i] = card(i).getLeft();
        View v = strip.getChildAt(from);
        strip.removeViewAt(from);
        strip.addView(v, to);
        for (int i = 0; i < count(); i++) {
            CardView c = card(i);
            c.index = i;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) c.getLayoutParams();
            lp.leftMargin = i > 0 ? gap : 0;
        }
        lastIndex = to;
        strip.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View s, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                strip.removeOnLayoutChangeListener(this);
                for (int i = 0; i < count(); i++) {
                    CardView c = card(i);
                    int oldIndex = c == v ? from : (i < Math.min(from, to) || i > Math.max(from, to)) ? i : (from < to ? i + 1 : i - 1);
                    float dx = before[oldIndex] - c.getLeft();
                    if (dx != 0) {
                        c.setTranslationX(dx);
                        Motion.spring(c, TRANSLATION_X, 0f, Motion.LAYOUT);
                    }
                }
                scrollToIndex(to, true);
            }
        });
        strip.requestLayout();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int th = 0;
        if (title != null) {
            title.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
            th = title.getMeasuredHeight() + Ui.px(Theme.S1);
        }
        strip.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);   // the strip may be wider than the screen
        setMeasuredDimension(w, th + strip.getMeasuredHeight());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int th = 0;
        if (title != null) {
            title.layout(padLeft, 0, padLeft + title.getMeasuredWidth(), title.getMeasuredHeight());
            th = title.getMeasuredHeight() + Ui.px(Theme.S1);
        }
        strip.layout(0, th, strip.getMeasuredWidth(), th + strip.getMeasuredHeight());
        if (changed) scrollToIndex(lastIndex, false);
    }
}
