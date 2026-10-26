package se.glass.home;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Screensaver: slow Ken Burns pan over Jellyfin backdrops (or the wallpaper) with the big rolling clock. */
public class GlassDream extends DreamService {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Bitmap> pictures = new ArrayList<>();
    private ImageView a, b;
    private boolean onA;
    private int next;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        Ui.init((android.content.Context) this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        a = new ImageView(this);
        b = new ImageView(this);
        for (ImageView v : new ImageView[]{a, b}) {
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            root.addView(v, Ui.match());
        }
        View shade = new View(this);
        shade.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x10000000, 0x80000000}));
        root.addView(shade, Ui.match());
        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        Ui.style(date, 40, true, 0.85f);
        clock.addView(date);
        clock.addView(new RollingClock(this, 220));
        root.addView(clock, Ui.at(-1, 90));
        setContentView(root);

        Settings s = new Settings(this);
        io.execute(() -> {
            Jellyfin jf = Jellyfin.load(this, s);
            if (jf != null) {
                try {
                    for (Jellyfin.Item it : jf.recentlyAdded()) {
                        if (pictures.size() >= 8) break;
                        Bitmap bm = jf.image(it, it.backdrops.isEmpty() ? it.images : it.backdrops, 1600);
                        if (bm != null) synchronized (pictures) { pictures.add(bm); }
                        if (pictures.size() == 1) ui.post(this::showNext);
                    }
                } catch (Exception ignored) {
                }
            }
            if (pictures.isEmpty()) {
                Bitmap w = Wallpapers.current(this, s);
                if (w != null) synchronized (pictures) { pictures.add(w); }
                ui.post(this::showNext);
            }
        });
    }

    /** Cross-fade to the next picture and drift it slowly (scale + pan) for 12 s. */
    private void showNext() {
        Bitmap bm;
        synchronized (pictures) {
            if (pictures.isEmpty()) return;
            bm = pictures.get(next++ % pictures.size());
        }
        ImageView in = onA ? b : a, out = onA ? a : b;
        onA = !onA;
        in.setImageBitmap(bm);
        boolean left = next % 2 == 0;
        in.setScaleX(1.12f);
        in.setScaleY(1.12f);
        in.setTranslationX(left ? Ui.px(40) : -Ui.px(40));
        in.animate().alpha(1f).setDuration(1800).start();
        in.animate().scaleX(1.02f).scaleY(1.02f).translationX(left ? -Ui.px(40) : Ui.px(40)).setDuration(14_000)
                .setInterpolator(new android.view.animation.LinearInterpolator()).withLayer().start();
        out.animate().alpha(0f).setDuration(1800).start();
        ui.postDelayed(this::showNext, 12_000);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        ui.removeCallbacksAndMessages(null);
    }
}
