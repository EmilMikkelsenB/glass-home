package se.glass.home;

import android.content.Context;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Minimal Home Assistant REST client. Config lives on the TV at
 * /sdcard/Android/data/se.glass.home/files/ha.json:
 * {"url": "http://homeassistant.local:8123", "token": "<long-lived token>",
 *  "entities": ["light.living_room", ...], "chips": ["sensor.outdoor_temperature", ...]}
 */
final class HomeAssistant {
    final String url, token;
    final List<String> entities = new ArrayList<>();   // tiles in the Home panel
    final List<String> chips = new ArrayList<>();      // small read-outs on the home screen
    final Map<String, String> names = new HashMap<>(); // optional display names, overriding HA's friendly_name

    private HomeAssistant(String url, String token) {
        this.url = url.replaceAll("/+$", "");
        this.token = token;
    }

    /** Returns null when there is no (valid) config, which hides all Home Assistant UI. */
    static HomeAssistant load(Context c) {
        File f = new File(c.getExternalFilesDir(null), "ha.json");
        if (!f.exists()) return null;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            HomeAssistant ha = new HomeAssistant(o.getString("url"), o.getString("token"));
            addAll(o.optJSONArray("entities"), ha.entities);
            addAll(o.optJSONArray("chips"), ha.chips);
            JSONObject names = o.optJSONObject("names");
            for (Iterator<String> it = names != null ? names.keys() : null; it != null && it.hasNext(); ) {
                String k = it.next();
                ha.names.put(k, names.getString(k));
            }
            return ha;
        } catch (IOException | JSONException e) {
            Log.w("GlassHome", "bad ha.json", e);
            return null;
        }
    }

    private static void addAll(JSONArray a, List<String> out) throws JSONException {
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.getString(i));
    }

    JSONObject state(String entityId) throws IOException, JSONException {
        return new JSONObject(http("GET", "/api/states/" + entityId, null));
    }

    void call(String domain, String service, String entityId) throws IOException, JSONException {
        http("POST", "/api/services/" + domain + "/" + service, new JSONObject().put("entity_id", entityId).toString());
    }

    private String http(String method, String path, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(4000);
            c.setReadTimeout(6000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Content-Type", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code >= 300) throw new IOException("HTTP " + code + " for " + path);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                return buf.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
