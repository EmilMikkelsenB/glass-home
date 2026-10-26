package se.glass.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Paint;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;

/** HH:mm where each changed digit rolls up into place (like the iOS lock screen), updated by the system minute tick. */
final class RollingClock extends LinearLayout {
    private final FrameLayout[] slots = new FrameLayout[5];
    private final String[] shown = new String[5];
    private final int size;
    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            update(true);
        }
    };

    RollingClock(Context c, int designSize) {
        super(c);
        size = designSize;
        setGravity(Gravity.CENTER);
        Paint p = Ui.text(c, "0", designSize, true, 1f).getPaint();
        p.setFontFeatureSettings("tnum");
        int digitW = Math.round(p.measureText("0")), colonW = Math.round(p.measureText(":"));
        for (int i = 0; i < 5; i++) {
            slots[i] = new FrameLayout(c);
            slots[i].setClipChildren(true);
            addView(slots[i], new LayoutParams(i == 2 ? colonW : digitW, -2));
        }
        update(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        getContext().registerReceiver(tick, f);
        update(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getContext().unregisterReceiver(tick);
    }

    private void update(boolean animate) {
        Calendar now = Calendar.getInstance();
        String s = String.format("%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
        for (int i = 0; i < 5; i++) {
            String ch = s.substring(i, i + 1);
            if (ch.equals(shown[i])) continue;
            shown[i] = ch;
            TextView next = Ui.text(getContext(), ch, size, true, 0.92f);
            next.setFontFeatureSettings("tnum");
            next.setIncludeFontPadding(false);
            next.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout slot = slots[i];
            if (animate && slot.getChildCount() > 0) {
                TextView old = (TextView) slot.getChildAt(slot.getChildCount() - 1);
                float h = Math.max(old.getHeight(), 1) * 0.6f;
                old.animate().translationY(-h).alpha(0f).setDuration(420).setInterpolator(Motion.EASE).withLayer()
                        .withEndAction(() -> slot.removeView(old)).start();
                next.setTranslationY(h);
                next.setAlpha(0f);
                next.animate().translationY(0f).alpha(1f).setDuration(520).setStartDelay(60).setInterpolator(Motion.EASE)
                        .withLayer().start();
            } else {
                slot.removeAllViews();
            }
            slot.addView(next, new FrameLayout.LayoutParams(-1, -2));
        }
    }
}
