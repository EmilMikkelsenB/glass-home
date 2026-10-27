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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Jellyseerr client for the "Önska" row: trending, search and requests. Config on the TV:
 * /sdcard/Android/data/se.glass.home/files/jellyseerr.json  {"url": "http://192.168.1.10:5055", "apiKey": "<api key>"}
 */
class Jellyseerr {
    static final int PENDING = 2, PROCESSING = 3, PARTIAL = 4, AVAILABLE = 5;

    static final class Title {
        int id, status, demoColor;
        String type, name, year, backdrop;   // type: "movie" | "tv"; backdrop: TMDB path

        /** Short status for the card ("Begärd", "Finns"…), or the year. */
        String label() {
            switch (status) {
                case PENDING: return Ui.tr("Begärd", "Requested");
                case PROCESSING: return Ui.tr("Laddas ner", "Downloading");
                case PARTIAL: return Ui.tr("Delvis tillgänglig", "Partly available");
                case AVAILABLE: return Ui.tr("Finns i Jellyfin", "In Jellyfin");
                default: return year;
            }
        }

        boolean requestable() {
            return status < PENDING;
        }
    }

    final String url, key;

    Jellyseerr(String url, String key) {
        this.url = url.replaceAll("/+$", "");
        this.key = key;
    }

    /** Returns null when there is no (valid) config, which hides the row and the search section. */
    static Jellyseerr load(Context c, Settings s) {
        if (s.demo()) return new Demo.DemoSeerr();
        File f = new File(c.getExternalFilesDir(null), "jellyseerr.json");
        if (!f.exists()) return null;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            return new Jellyseerr(o.getString("url"), o.getString("apiKey"));
        } catch (IOException | JSONException e) {
            Log.w("GlassHome", "bad jellyseerr.json", e);
            return null;
        }
    }

    List<Title> trending() throws IOException, JSONException {
        return titles(get("/api/v1/discover/trending?page=1"));
    }

    List<Title> search(String term) throws IOException, JSONException {
        // Jellyseerr wants %20, not URLEncoder's "+"
        return titles(get("/api/v1/search?page=1&query=" + java.net.URLEncoder.encode(term, "UTF-8").replace("+", "%20")));
    }

    /** Request a film, or every season of a series. */
    void request(Title t) throws IOException, JSONException {
        JSONObject body = new JSONObject().put("mediaType", t.type).put("mediaId", t.id);
        if (t.type.equals("tv")) body.put("seasons", "all");
        HttpURLConnection c = open("/api/v1/request");
        try {
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() >= 300) throw new IOException("HTTP " + c.getResponseCode() + " for request");
        } finally {
            c.disconnect();
        }
    }

    /** TMDB backdrop, scaled by TMDB's CDN. */
    Bitmap image(Title t, int width) {
        if (t.backdrop == null || t.backdrop.isEmpty()) return null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://image.tmdb.org/t/p/" + (width > 780 ? "w1280" : "w780") + t.backdrop).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(10000);
            try (InputStream in = c.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static List<Title> titles(JSONObject page) {
        List<Title> out = new ArrayList<>();
        JSONArray a = page.optJSONArray("results");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            String type = o.optString("mediaType");
            if (!type.equals("movie") && !type.equals("tv")) continue;   // people etc.
            Title t = new Title();
            t.id = o.optInt("id");
            t.type = type;
            t.name = o.optString(type.equals("movie") ? "title" : "name");
            String date = o.optString(type.equals("movie") ? "releaseDate" : "firstAirDate");
            t.year = date.length() >= 4 ? date.substring(0, 4) : "";
            t.backdrop = o.isNull("backdropPath") ? null : o.optString("backdropPath");
            JSONObject info = o.optJSONObject("mediaInfo");
            t.status = info != null ? info.optInt("status") : 0;
            out.add(t);
        }
        return out;
    }

    private JSONObject get(String path) throws IOException, JSONException {
        HttpURLConnection c = open(path);
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() >= 300) throw new IOException("HTTP " + c.getResponseCode() + " for " + path);
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
        c.setRequestProperty("X-Api-Key", key);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }
}
