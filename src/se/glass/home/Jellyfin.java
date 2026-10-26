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
 * Minimal Jellyfin client for the "continue watching" row. Config on the TV:
 * /sdcard/Android/data/se.glass.home/files/jellyfin.json
 * {"url": "http://192.168.1.90:8096", "apiKey": "<api key>", "userId": "<user id>"}
 */
final class Jellyfin {
    static final String APP = "org.jellyfin.androidtv";
    static final String STARTUP = "org.jellyfin.androidtv.ui.startup.StartupActivity";

    /** One card: something half-watched (resume) or the next episode of a series in progress (next up). */
    static final class Item {
        String id, type, name, series;
        int season, episode, year;
        float progress;          // 0..1, 0 when not started
        long remainingMin;
        boolean nextUp;
        final List<String> images = new ArrayList<>();   // image paths to try, best first
    }

    final String url, key, userId;

    private Jellyfin(String url, String key, String userId) {
        this.url = url.replaceAll("/+$", "");
        this.key = key;
        this.userId = userId;
    }

    /** Returns null when there is no (valid) config, which hides the row. */
    static Jellyfin load(Context c) {
        File f = new File(c.getExternalFilesDir(null), "jellyfin.json");
        if (!f.exists()) return null;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            return new Jellyfin(o.getString("url"), o.getString("apiKey"), o.getString("userId"));
        } catch (IOException | JSONException e) {
            Log.w("GlassHome", "bad jellyfin.json", e);
            return null;
        }
    }

    /** Resume items first, then next-up episodes for series that aren't already in the list. */
    List<Item> continueWatching() throws IOException, JSONException {
        String fields = "&Fields=PrimaryImageAspectRatio&EnableImageTypes=Primary,Backdrop,Thumb&ImageTypeLimit=1";
        List<Item> out = new ArrayList<>();
        Set<String> series = new HashSet<>();
        JSONArray resume = get("/Users/" + userId + "/Items/Resume?Limit=12&MediaTypes=Video" + fields).getJSONArray("Items");
        for (int i = 0; i < resume.length(); i++) {
            JSONObject o = resume.getJSONObject(i);
            series.add(o.optString("SeriesId"));
            out.add(parse(o, false));
        }
        JSONArray next = get("/Shows/NextUp?userId=" + userId + "&Limit=12" + fields).getJSONArray("Items");
        for (int i = 0; i < next.length() && out.size() < 16; i++) {
            JSONObject o = next.getJSONObject(i);
            if (series.add(o.optString("SeriesId"))) out.add(parse(o, true));
        }
        return out;
    }

    private static Item parse(JSONObject o, boolean nextUp) {
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
        JSONObject tags = o.optJSONObject("ImageTags");
        boolean primary = tags != null && tags.has("Primary"), thumb = tags != null && tags.has("Thumb");
        JSONArray backdrops = o.optJSONArray("BackdropImageTags");
        if (it.type.equals("Episode")) {
            if (primary) it.images.add("/Items/" + it.id + "/Images/Primary");          // the episode still (16:9)
            if (o.has("ParentThumbItemId")) it.images.add("/Items/" + o.optString("ParentThumbItemId") + "/Images/Thumb");
            if (o.has("ParentBackdropItemId")) it.images.add("/Items/" + o.optString("ParentBackdropItemId") + "/Images/Backdrop");
        } else {
            if (thumb) it.images.add("/Items/" + it.id + "/Images/Thumb");
            if (backdrops != null && backdrops.length() > 0) it.images.add("/Items/" + it.id + "/Images/Backdrop");
            if (primary) it.images.add("/Items/" + it.id + "/Images/Primary");
        }
        return it;
    }

    /** First image of the item that downloads and decodes, scaled by the server to `width`. */
    Bitmap image(Item it, int width) {
        for (String path : it.images) {
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

    private JSONObject get(String path) throws IOException, JSONException {
        HttpURLConnection c = open(path);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            return new JSONObject(buf.toString("UTF-8"));
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
}
