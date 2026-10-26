package se.glass.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;

/**
 * Wallpapers. The gradient ships in the APK. Apple's macOS wallpapers are NOT bundled (public repo): the TV downloads them
 * itself from 512pixels.net when chosen, scales them to the screen and keeps a 1920-wide JPEG.
 */
final class Wallpapers {
    private static final String BASE = "https://media.512pixels.net/downloads/macos-wallpapers-6k/";
    /** key → source file name (Apple originals, 6K). */
    private static final String[][] SOURCES = {
            {"sierra", "10-12-6k.jpg"},
            {"tahoe", "26-Tahoe-Dark-6K.png"},
            {"beach-dawn", "26-Tahoe-Beach-Dawn.png"},
            {"beach-day", "26-Tahoe-Beach-Day.png"},
            {"beach-dusk", "26-Tahoe-Beach-Dusk.png"},
            {"beach-night", "26-Tahoe-Beach-Night.png"},
    };

    interface Progress {
        void on(String message);
    }

    private Wallpapers() {}

    /** The wallpaper for the current choice (and, for dynamic, the time of day). Falls back to the gradient. */
    static Bitmap current(Context c, Settings s) {
        String w = s.wallpaper();
        String key = w.equals(Settings.WALL_DYNAMIC) ? dynamicKey() : w;
        File f = file(c, key);
        if (!key.equals(Settings.WALL_GRADIENT) && f.exists()) {
            Bitmap bm = BitmapFactory.decodeFile(f.getPath());
            if (bm != null) return bm;
        }
        return Apps.asset(c, "wallpaper.jpg");
    }

    /** Identifies what current() would show, to know when the dynamic wallpaper must change. */
    static String currentKey(Settings s) {
        return s.wallpaper().equals(Settings.WALL_DYNAMIC) ? dynamicKey() : s.wallpaper();
    }

    static String dynamicKey() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 5 && h < 9 ? "beach-dawn" : h >= 9 && h < 17 ? "beach-day" : h >= 17 && h < 21 ? "beach-dusk" : "beach-night";
    }

    static boolean ready(Context c, String choice) {
        if (choice.equals(Settings.WALL_GRADIENT)) return true;
        if (choice.equals(Settings.WALL_DYNAMIC)) {
            for (String k : new String[]{"beach-dawn", "beach-day", "beach-dusk", "beach-night"}) if (!file(c, k).exists()) return false;
            return true;
        }
        return file(c, choice).exists();
    }

    /** Downloads what `choice` needs (blocking; call off the UI thread). */
    static void download(Context c, String choice, Progress p) throws IOException {
        String[] keys = choice.equals(Settings.WALL_DYNAMIC)
                ? new String[]{"beach-dawn", "beach-day", "beach-dusk", "beach-night"} : new String[]{choice};
        int n = 0;
        for (String k : keys) {
            n++;
            if (file(c, k).exists()) continue;
            p.on(Ui.tr("Laddar ner bild " + n + " av " + keys.length + "…", "Downloading image " + n + " of " + keys.length + "…"));
            File raw = new File(c.getCacheDir(), k + ".src");
            fetch(BASE + source(k), raw);
            p.on(Ui.tr("Förbereder bilden…", "Preparing the image…"));
            scaleToScreen(raw, file(c, k));
            raw.delete();
        }
    }

    private static String source(String key) {
        for (String[] s : SOURCES) if (s[0].equals(key)) return s[1];
        throw new IllegalArgumentException(key);
    }

    private static File file(Context c, String key) {
        File dir = new File(c.getFilesDir(), "wallpapers");
        dir.mkdirs();
        return new File(dir, key + ".jpg");
    }

    private static void fetch(String url, File to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(60_000);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            byte[] b = new byte[65536];
            for (int r; (r = in.read(b)) > 0; ) out.write(b, 0, r);
        } finally {
            c.disconnect();
        }
    }

    /** Decode subsampled (6K would not fit in memory), centre-crop to 16:9 and save as a 1920×1080 JPEG. */
    private static void scaleToScreen(File src, File dst) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getPath(), o);
        o.inSampleSize = Math.max(1, Integer.highestOneBit(o.outWidth / 1920));
        o.inJustDecodeBounds = false;
        Bitmap big = BitmapFactory.decodeFile(src.getPath(), o);
        if (big == null) throw new IOException("could not decode " + src);
        int w = 1920, h = 1080;
        float scale = Math.max(w / (float) big.getWidth(), h / (float) big.getHeight());
        float cw = w / scale, ch = h / scale;
        Rect from = new Rect(Math.round((big.getWidth() - cw) / 2), Math.round((big.getHeight() - ch) / 2),
                Math.round((big.getWidth() + cw) / 2), Math.round((big.getHeight() + ch) / 2));
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(big, from, new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
        big.recycle();
        try (OutputStream os = new FileOutputStream(dst)) {
            out.compress(Bitmap.CompressFormat.JPEG, 92, os);
        }
    }
}
