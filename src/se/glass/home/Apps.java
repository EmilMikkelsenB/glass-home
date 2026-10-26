package se.glass.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.LruCache;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Installed apps: scanning, labels, glass artwork, launching. */
final class Apps {
    /** Front row before the user has chosen: artwork key, package. */
    private static final String[][] DEFAULT_FAVOURITES = {
            {"jellyfin", "org.jellyfin.androidtv"},
            {"tv4play", "se.tv4.tv4playtab"},
            {"svtplay", "se.svt.android.svtplay"},
            {"youtube", "com.google.android.youtube.tv"},
            {"netflix", "com.netflix.ninja"},
            {"moonlight", "com.limelight"},
    };
    /** Package -> artwork key in assets (wide_<key>.png); anything else gets a runtime glass card around its own banner. */
    private static final Map<String, String> ART = new HashMap<>();
    static {
        for (String[] fav : DEFAULT_FAVOURITES) ART.put(fav[1], fav[0]);
        ART.put("com.apple.atve.androidtv.appletv", "appletv");
        ART.put("com.wbd.stream", "max");
        ART.put("com.discovery.dplay", "discovery");
        ART.put("com.crunchyroll.crunchyroid", "crunchyroll");
        ART.put("tv.twitch.android.app", "twitch");
        ART.put("com.viaplay.android", "viaplay");
        ART.put("allente.b2c.tvclient", "allente");
        ART.put("com.spotify.tv.android", "spotify");
        ART.put("com.valvesoftware.steamlink", "steamlink");
        ART.put("com.disney.disneyplus", "disneyplus");
        ART.put("com.amazon.amazonvideo.livingroom", "primevideo");
        ART.put("com.android.tv.settings", "settings");
    }
    /** Never listed: ourselves and other launchers. */
    static final Set<String> NEVER = Set.of("se.glass.home", "com.spocky.projengmenu", "com.google.android.apps.tv.launcherx");

    private static final LruCache<String, Bitmap> ASSETS = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    private Apps() {}

    static final class App {
        final String pkg;
        final CharSequence label;

        App(String pkg, CharSequence label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    /** Every launchable app (TV launcher entries first, then phone-style ones), sorted by name. */
    static List<App> scan(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> seen = new LinkedHashSet<>(NEVER);
        List<App> out = new ArrayList<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String p = ri.activityInfo.packageName;
                if (seen.add(p)) out.add(new App(p, label(c, p)));
            }
        }
        out.sort((a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
        return out;
    }

    static List<String> defaultFavourites(Context c) {
        List<String> out = new ArrayList<>();
        for (String[] fav : DEFAULT_FAVOURITES) if (installed(c, fav[1])) out.add(fav[1]);
        return out;
    }

    static boolean installed(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationInfo(pkg, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static CharSequence label(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static void launch(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Glass artwork for an app: the generated wide PNG if we have one, else a glass card around its own banner/icon. */
    static Drawable art(Context c, String pkg) {
        String key = ART.get(pkg);
        Bitmap bm = key != null ? asset(c, "wide_" + key + ".png") : null;
        if (bm != null) return new BitmapDrawable(c.getResources(), bm);
        Drawable own = null;
        try {
            own = c.getPackageManager().getApplicationBanner(pkg);
            if (own == null) own = c.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return new Glass(own, 0, false, 0.28f);
    }

    /** Artwork by key for our own cards (more, homeassistant, settings…). */
    static Drawable artKey(Context c, String key) {
        Bitmap bm = asset(c, "wide_" + key + ".png");
        return bm != null ? new BitmapDrawable(c.getResources(), bm) : new Glass(null, 0, false, 0.28f);
    }

    static Bitmap asset(Context c, String path) {
        Bitmap cached = ASSETS.get(path);
        if (cached != null) return cached;
        try (InputStream in = c.getAssets().open(path)) {
            Bitmap bm = BitmapFactory.decodeStream(in);
            if (bm != null) ASSETS.put(path, bm);
            return bm;
        } catch (IOException e) {
            Log.w("GlassHome", "missing asset " + path);
            return null;
        }
    }
}
