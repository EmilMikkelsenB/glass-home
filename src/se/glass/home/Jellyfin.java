package se.glass.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Minimal Jellyfin client for the "Fortsätt titta" / "Nyligen tillagt" rows. Config on the TV:
 * /sdcard/Android/data/se.glass.home/files/jellyfin.json
 * {"url": "http://192.168.1.90:8096", "apiKey": "<api key>", "userId": "<user id>"}
 * The user can be switched on the TV (profile), which overrides userId via Settings.
 */
class Jellyfin {
    static final String APP = "org.jellyfin.androidtv";
    static final String STARTUP = "org.jellyfin.androidtv.ui.startup.StartupActivity";

    /** One card: something half-watched (resume), the next episode of a series (next up), or recently added. */
    static final class Item {
        String id, type, name, series;
        int season, episode, year;
        float progress;          // 0..1, 0 when not started
        long remainingMin, positionTicks;
        boolean nextUp;
        int demoColor;           // demo mode only: colour of the generated artwork
        final List<String> images = new ArrayList<>();     // card image paths to try, best first
        final List<String> backdrops = new ArrayList<>();  // full-screen background ("top shelf")

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("type", type).put("name", name).put("series", series)
                    .put("season", season).put("episode", episode).put("year", year).put("progress", progress)
                    .put("remainingMin", remainingMin).put("positionTicks", positionTicks).put("nextUp", nextUp).put("demoColor", demoColor)
                    .put("images", new JSONArray(images)).put("backdrops", new JSONArray(backdrops));
        }

        static Item fromJson(JSONObject o) {
            Item it = new Item();
            it.id = o.optString("id");
            it.type = o.optString("type");
            it.name = o.optString("name");
            it.series = o.optString("series");
            it.season = o.optInt("season");
            it.episode = o.optInt("episode");
            it.year = o.optInt("year");
            it.progress = (float) o.optDouble("progress");
            it.remainingMin = o.optLong("remainingMin");
            it.positionTicks = o.optLong("positionTicks");
            it.nextUp = o.optBoolean("nextUp");
            it.demoColor = o.optInt("demoColor");
            addAll(o.optJSONArray("images"), it.images);
            addAll(o.optJSONArray("backdrops"), it.backdrops);
            return it;
        }
    }

    static final class User {
        final String id, name;

        User(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    final String url, key, userId;

    Jellyfin(String url, String key, String userId) {
        this.url = Ui.serverUrl(url);
        this.key = key;
        this.userId = userId;
    }

    /** Returns null when there is no (valid) config, which hides the rows. A profile chosen on the TV overrides userId. */
    static Jellyfin load(Context c, Settings s) {
        if (s.demo()) return new Demo.DemoJellyfin();
        File f = new File(c.getExternalFilesDir(null), "jellyfin.json");
        if (!f.exists()) return null;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            String user = s.jellyfinUser() != null ? s.jellyfinUser() : o.getString("userId");
            return new Jellyfin(o.getString("url"), o.getString("apiKey"), user);
        } catch (IOException | JSONException e) {
            Log.w("GlassHome", "bad jellyfin.json", e);
            return null;
        }
    }

    private static final String FIELDS = "&Fields=PrimaryImageAspectRatio&EnableImageTypes=Primary,Backdrop,Thumb&ImageTypeLimit=1";

    /** Resume items first, then next-up episodes for series that aren't already in the list. */
    List<Item> continueWatching() throws IOException, JSONException {
        List<Item> out = new ArrayList<>();
        Set<String> series = new HashSet<>();
        JSONArray resume = get("/Users/" + userId + "/Items/Resume?Limit=12&MediaTypes=Video" + FIELDS).getJSONArray("Items");
        for (int i = 0; i < resume.length(); i++) {
            JSONObject o = resume.getJSONObject(i);
            series.add(o.optString("SeriesId"));
            out.add(parse(o, false));
        }
        JSONArray next = get("/Shows/NextUp?userId=" + userId + "&Limit=12" + FIELDS).getJSONArray("Items");
        for (int i = 0; i < next.length() && out.size() < 16; i++) {
            JSONObject o = next.getJSONObject(i);
            if (series.add(o.optString("SeriesId"))) out.add(parse(o, true));
        }
        return out;
    }

    /** Newest films and series (episodes grouped into their series). */
    /** Films and series whose name matches `term`. */
    List<Item> search(String term) throws IOException, JSONException {
        String q = java.net.URLEncoder.encode(term, "UTF-8");
        JSONArray a = get("/Users/" + userId + "/Items?searchTerm=" + q + "&IncludeItemTypes=Movie,Series&Recursive=true&Limit=24" + FIELDS)
                .getJSONArray("Items");
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(parse(a.getJSONObject(i), false));
        return out;
    }

    /**
     * The Jellyfin TV app's remote-control session on this device (same model, client "Android TV"), or null.
     * Matching the model keeps us from ever starting playback on someone else's TV.
     */
    String tvSession(String model) throws IOException, JSONException {
        JSONArray a = new JSONArray(getRaw("/Sessions?ControllableByUserId=" + userId));
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.getJSONObject(i);
            if (s.optBoolean("SupportsRemoteControl") && s.optString("Client").contains("Android TV")
                    && s.optString("DeviceName").equalsIgnoreCase(model)) return s.optString("Id");
        }
        return null;
    }

    /** Ask the Jellyfin app (session `sessionId`) to play `it` from where it was left. */
    void playNow(String sessionId, Item it) throws IOException {
        post("/Sessions/" + sessionId + "/Playing?playCommand=PlayNow&itemIds=" + it.id + "&startPositionTicks=" + it.positionTicks);
    }

    List<Item> recentlyAdded() throws IOException, JSONException {
        String body = getRaw("/Users/" + userId + "/Items/Latest?Limit=16&IncludeItemTypes=Movie,Series&GroupItems=true" + FIELDS);
        JSONArray a = new JSONArray(body);
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(parse(a.getJSONObject(i), false));
        return out;
    }

    List<User> users() throws IOException, JSONException {
        JSONArray a = new JSONArray(getRaw("/Users"));
        List<User> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject u = a.getJSONObject(i);
            if (!u.optJSONObject("Policy").optBoolean("IsDisabled")) out.add(new User(u.getString("Id"), u.getString("Name")));
        }
        return out;
    }

    static Item parse(JSONObject o, boolean nextUp) {
        Item it = new Item();
        it.id = o.optString("Id");
        it.type = o.optString("Type");
        it.name = o.optString("Name");
        it.series = o.optString("SeriesName");
        it.season = o.optInt("ParentIndexNumber");
        it.episode = o.optInt("IndexNumber");
        it.year = o.optInt("ProductionYear");
        it.nextUp = nextUp;
        JSONObject ud = o.optJSONObject("UserData");
        if (ud != null) it.progress = (float) ud.optDouble("PlayedPercentage", 0) / 100f;
        long run = o.optLong("RunTimeTicks"), pos = ud != null ? ud.optLong("PlaybackPositionTicks") : 0;
        it.remainingMin = Math.max(0, (run - pos) / 600_000_000L);
        it.positionTicks = pos;
        JSONObject tags = o.optJSONObject("ImageTags");
        boolean primary = tags != null && tags.has("Primary"), thumb = tags != null && tags.has("Thumb");
        JSONArray backdrops = o.optJSONArray("BackdropImageTags");
        boolean ownBackdrop = backdrops != null && backdrops.length() > 0;
        if (it.type.equals("Episode")) {
            if (primary) it.images.add("/Items/" + it.id + "/Images/Primary");          // the episode still (16:9)
            if (o.has("ParentThumbItemId")) it.images.add("/Items/" + o.optString("ParentThumbItemId") + "/Images/Thumb");
            if (o.has("ParentBackdropItemId")) {
                String b = "/Items/" + o.optString("ParentBackdropItemId") + "/Images/Backdrop";
                it.images.add(b);
                it.backdrops.add(b);
            }
        } else {
            if (thumb) it.images.add("/Items/" + it.id + "/Images/Thumb");
            if (ownBackdrop) {
                it.images.add("/Items/" + it.id + "/Images/Backdrop");
                it.backdrops.add("/Items/" + it.id + "/Images/Backdrop");
            }
            if (primary) it.images.add("/Items/" + it.id + "/Images/Primary");
        }
        return it;
    }

    /** First of `paths` that downloads and decodes, scaled by the server to `width`. */
    Bitmap image(Item it, List<String> paths, int width) {
        for (String path : paths) {
            try {
                HttpURLConnection c = open(path + "?fillWidth=" + width + "&quality=85");
                try (InputStream in = c.getInputStream()) {
                    Bitmap bm = BitmapFactory.decodeStream(in);
                    if (bm != null) return bm;
                } finally {
                    c.disconnect();
                }
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    /** Opens the item's page in the Jellyfin TV app (works whether or not the app is running). */
    static android.content.Intent openIntent(Item it) {
        return new android.content.Intent().setClassName(APP, STARTUP).putExtra("ItemId", it.id)
                .putExtra("ItemIsUserView", false).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private JSONObject get(String path) throws IOException, JSONException {
        return new JSONObject(getRaw(path));
    }

    private String getRaw(String path) throws IOException {
        HttpURLConnection c = open(path);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            return buf.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private void post(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url + path).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(4000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Authorization", "MediaBrowser Token=\"" + key + "\"");
            c.setDoOutput(true);
            c.getOutputStream().close();
            if (c.getResponseCode() >= 300) throw new IOException("HTTP " + c.getResponseCode() + " for " + path);
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url + path).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Authorization", "MediaBrowser Token=\"" + key + "\"");
        if (c.getResponseCode() >= 300) throw new IOException("HTTP " + c.getResponseCode() + " for " + path);
        return c;
    }

    private static void addAll(JSONArray a, List<String> out) {
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i));
    }
}
