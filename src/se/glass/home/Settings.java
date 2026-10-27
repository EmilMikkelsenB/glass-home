package se.glass.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything the user chooses on the TV (setup wizard, long-press menu): favourites, hidden apps, remote buttons, wallpaper,
 * Jellyfin profile. Stored in SharedPreferences. The old PC-pushed favorites.json / buttons.json are imported once.
 */
final class Settings {
    static final String WALL_GRADIENT = "gradient", WALL_SIERRA = "sierra", WALL_TAHOE = "tahoe", WALL_DYNAMIC = "dynamic";

    private final SharedPreferences p;
    private final Context ctx;

    Settings(Context c) {
        ctx = c.getApplicationContext();
        p = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE);
        if (!p.getBoolean("imported", false)) importLegacy();
    }

    /** Bumped on every change, so the home screen knows to rebuild after the wizard or a long-press edit. */
    long version() { return p.getLong("version", 0); }

    boolean setupDone() { return p.getBoolean("setupDone", false); }
    void setSetupDone(boolean b) { edit().putBoolean("setupDone", b).apply(); }

    boolean demo() { return p.getBoolean("demo", false) || new File(ctx.getExternalFilesDir(null), "demo").exists(); }
    void setDemo(boolean b) { edit().putBoolean("demo", b).apply(); }

    List<String> favourites() { return list("favourites", Apps.defaultFavourites(ctx)); }
    void setFavourites(List<String> pkgs) { edit().putString("favourites", new JSONArray(pkgs).toString()).apply(); }

    Set<String> hidden() { return new LinkedHashSet<>(list("hidden", new ArrayList<>())); }
    void setHidden(Set<String> pkgs) { edit().putString("hidden", new JSONArray(pkgs).toString()).apply(); }

    /** Known installed packages, to spot apps installed after setup ("Ny app"). */
    Set<String> knownApps() { return new LinkedHashSet<>(list("knownApps", new ArrayList<>())); }
    void setKnownApps(Set<String> pkgs) { p.edit().putString("knownApps", new JSONArray(pkgs).toString()).apply(); }

    String wallpaper() { return p.getString("wallpaper", WALL_GRADIENT); }
    void setWallpaper(String w) { edit().putString("wallpaper", w).apply(); }

    /** Jellyfin user id override (profile switch); null = the one in jellyfin.json. */
    String jellyfinUser() { return p.getString("jellyfinUser", null); }

    /** Sleep timer: wall-clock millis when the TV turns off, 0 = off (no version bump: nothing to rebuild). */
    long sleepAt() { return p.getLong("sleepAt", 0); }
    void setSleepAt(long at) { p.edit().putLong("sleepAt", at).apply(); }
    void setJellyfinUser(String id) { edit().putString("jellyfinUser", id).apply(); }

    /** {"KEYCODE_X": {"press": action, "long": action}} — see KeyService for actions. */
    JSONObject buttons() {
        try {
            return new JSONObject(p.getString("buttons", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    void setButtons(JSONObject b) { edit().putString("buttons", b.toString()).apply(); }

    private SharedPreferences.Editor edit() {
        return p.edit().putLong("version", version() + 1);
    }

    private List<String> list(String key, List<String> fallback) {
        String s = p.getString(key, null);
        if (s == null) return fallback;
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (JSONException e) {
            return fallback;
        }
        return out;
    }

    private void importLegacy() {
        SharedPreferences.Editor e = p.edit().putBoolean("imported", true);
        File dir = ctx.getExternalFilesDir(null);
        try {
            File fav = new File(dir, "favorites.json");
            if (fav.exists()) e.putString("favourites", new JSONArray(read(fav)).toString());
            File btn = new File(dir, "buttons.json");
            if (btn.exists()) e.putString("buttons", new JSONObject(read(btn)).toString());
        } catch (Exception ex) {
            Log.w("GlassHome", "legacy import", ex);
        }
        e.apply();
    }

    private static String read(File f) throws java.io.IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
