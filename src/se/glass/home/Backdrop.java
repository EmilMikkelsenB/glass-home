package se.glass.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * What is behind the glass, pre-frosted. The wallpaper is static, so instead of a live backdrop blur (which Android views can't
 * do and the TV GPU couldn't afford) every glass surface draws the matching region of a frosted copy of the wallpaper, mapped
 * through the wallpaper view's current transform (parallax) and the glass view's own transform (focus scale). The Jellyfin
 * "top shelf" backdrop can fade in on top of it the same way.
 */
final class Backdrop {
    static final Backdrop SHARED = new Backdrop();

    private Bitmap frost, shelf;
    private float shelfAlpha;
    private View wallView;
    private final Matrix wallMatrix = new Matrix(), tmp = new Matrix(), inv = new Matrix();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final List<WeakReference<Drawable>> clients = new ArrayList<>();

    private Backdrop() {}

    /** The view showing the wallpaper (CENTER_CROP, may be scaled/translated for parallax). */
    void setWallView(View v) {
        wallView = v;
    }

    void setFrost(Bitmap frosted) {
        frost = frosted;
        invalidateAll();
    }

    void setShelf(Bitmap frosted, float alpha) {
        shelf = frosted;
        shelfAlpha = alpha;
        invalidateAll();
    }

    void setShelfAlpha(float a) {
        if (a == shelfAlpha) return;
        shelfAlpha = a;
        invalidateAll();
    }

    void register(Drawable d) {
        clients.add(new WeakReference<>(d));
    }

    /** Wallpaper moved (parallax) or changed: every glass surface must resample. */
    void invalidateAll() {
        for (Iterator<WeakReference<Drawable>> it = clients.iterator(); it.hasNext(); ) {
            Drawable d = it.next().get();
            if (d == null) it.remove();
            else d.invalidateSelf();
        }
    }

    boolean ready() {
        return frost != null && wallView != null && wallView.getWidth() > 0;
    }

    /**
     * Draw the frosted backdrop into `canvas`, which belongs to `host` (a view somewhere above the wallpaper). The caller has
     * already clipped to its glass shape.
     */
    void draw(Canvas canvas, View host) {
        if (!ready() || host == null) return;
        drawLayer(canvas, host, frost, 255);
        if (shelf != null && shelfAlpha > 0.01f) drawLayer(canvas, host, shelf, Math.round(shelfAlpha * 255));
    }

    private void drawLayer(Canvas canvas, View host, Bitmap bm, int alpha) {
        // bitmap → wallpaper view (centre-crop) → screen (wallpaper's own transform), then screen → host local
        int vw = wallView.getWidth(), vh = wallView.getHeight();
        float s = Math.max(vw / (float) bm.getWidth(), vh / (float) bm.getHeight());
        wallMatrix.setScale(s, s);
        wallMatrix.postTranslate((vw - bm.getWidth() * s) / 2f, (vh - bm.getHeight() * s) / 2f);
        tmp.reset();
        wallView.transformMatrixToGlobal(tmp);
        wallMatrix.postConcat(tmp);
        tmp.reset();
        host.transformMatrixToGlobal(tmp);
        if (!tmp.invert(inv)) return;
        wallMatrix.postConcat(inv);
        paint.setAlpha(alpha);
        canvas.drawBitmap(bm, wallMatrix, paint);
    }
}
