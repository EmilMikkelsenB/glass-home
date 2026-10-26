package se.glass.home;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * "Set up from your phone": a tiny LAN-only web server with one glass-styled form for the Home Assistant and Jellyfin
 * connections. The URL contains a random 6-digit PIN; requests without it get 404. It runs only while the panel is open.
 */
final class PhoneSetup {
    private final Context ctx;
    private final Runnable onSaved;
    private final String pin = String.format("%06d", new SecureRandom().nextInt(1_000_000));
    private ServerSocket server;

    PhoneSetup(Context c, Runnable onSaved) {
        this.ctx = c.getApplicationContext();
        this.onSaved = onSaved;
    }

    /** Starts serving; returns the URL to show (null if there's no LAN address). */
    String start() {
        String ip = lanAddress();
        if (ip == null) return null;
        try {
            server = new ServerSocket(0, 8);   // all interfaces; the random port + PIN keep it private
        } catch (Exception e) {
            Log.w("GlassHome", "phone setup", e);
            return null;
        }
        Thread t = new Thread(this::serve, "phone-setup");
        t.setDaemon(true);
        t.start();
        Log.i("GlassHome", "phone setup on port " + server.getLocalPort());
        return "http://" + ip + ":" + server.getLocalPort() + "/" + pin;
    }

    void stop() {
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(10_000);
                handle(s);
            } catch (Exception e) {
                if (!server.isClosed()) Log.w("GlassHome", "phone setup request", e);
            }
        }
    }

    private void handle(Socket s) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String request = in.readLine();
        if (request == null) return;
        int length = 0;
        for (String h; (h = in.readLine()) != null && !h.isEmpty(); ) {
            if (h.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(h.substring(15).trim());
        }
        String[] parts = request.split(" ");
        OutputStream out = s.getOutputStream();
        if (parts.length < 2 || !parts[1].equals("/" + pin)) {
            respond(out, "404 Not Found", "Not found");
            return;
        }
        if (parts[0].equals("POST")) {
            char[] body = new char[Math.min(length, 64_000)];
            int read = 0;
            while (read < body.length) {
                int n = in.read(body, read, body.length - read);
                if (n < 0) break;
                read += n;
            }
            save(parse(new String(body, 0, read)));
            respond(out, "200 OK", page(Ui.tr("Sparat! Titta på TV:n.", "Saved! Look at the TV.")));
            onSaved.run();
        } else {
            respond(out, "200 OK", page(null));
        }
    }

    private static Map<String, String> parse(String body) throws Exception {
        Map<String, String> m = new HashMap<>();
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8").trim());
        }
        return m;
    }

    /** Writes ha.json / jellyfin.json. Empty secret fields keep the saved value. */
    private void save(Map<String, String> f) throws Exception {
        File dir = ctx.getExternalFilesDir(null);
        JSONObject ha = readJson(new File(dir, "ha.json"));
        if (!f.getOrDefault("haUrl", "").isEmpty()) {
            ha.put("url", f.get("haUrl"));
            if (!f.getOrDefault("haToken", "").isEmpty()) ha.put("token", f.get("haToken"));
            ha.put("entities", list(f.get("haEntities")));
            ha.put("chips", list(f.get("haChips")));
            if (!f.getOrDefault("haMedia", "").isEmpty()) ha.put("media", f.get("haMedia"));
            else ha.remove("media");
            Files.write(new File(dir, "ha.json").toPath(), ha.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        JSONObject jf = readJson(new File(dir, "jellyfin.json"));
        if (!f.getOrDefault("jfUrl", "").isEmpty()) {
            jf.put("url", f.get("jfUrl"));
            if (!f.getOrDefault("jfKey", "").isEmpty()) jf.put("apiKey", f.get("jfKey"));
            if (!f.getOrDefault("jfUser", "").isEmpty()) jf.put("userId", f.get("jfUser"));
            Files.write(new File(dir, "jellyfin.json").toPath(), jf.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        new Settings(ctx).setJellyfinUser(null);
    }

    private static JSONArray list(String s) {
        JSONArray a = new JSONArray();
        if (s == null) return a;
        for (String p : s.split("[,\\n]")) if (!p.trim().isEmpty()) a.put(p.trim());
        return a;
    }

    private static JSONObject readJson(File f) {
        try {
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private String page(String message) {
        File dir = ctx.getExternalFilesDir(null);
        JSONObject ha = readJson(new File(dir, "ha.json")), jf = readJson(new File(dir, "jellyfin.json"));
        String saved = Ui.tr("(sparad – lämna tomt för att behålla)", "(saved – leave empty to keep)");
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>")
                .append("<title>Glass Home</title><style>")
                .append("body{margin:0;min-height:100vh;font:16px -apple-system,system-ui,sans-serif;color:#fff;")
                .append("background:linear-gradient(140deg,#1e3a8a,#6b21a8 55%,#0f766e)}")
                .append("main{max-width:560px;margin:auto;padding:24px 16px}h1{font-size:28px;margin:8px 0 20px}")
                .append("section{background:rgba(255,255,255,.14);border:1px solid rgba(255,255,255,.35);border-radius:22px;")
                .append("padding:18px;margin-bottom:16px;backdrop-filter:blur(20px)}h2{font-size:18px;margin:0 0 12px}")
                .append("label{display:block;font-size:13px;opacity:.85;margin:10px 0 4px}")
                .append("input,textarea{width:100%;box-sizing:border-box;padding:12px;border-radius:12px;border:0;font:inherit;")
                .append("background:rgba(255,255,255,.9);color:#111}textarea{min-height:80px}")
                .append("button{width:100%;padding:14px;border:0;border-radius:14px;font:600 17px inherit;background:#0a84ff;color:#fff}")
                .append(".msg{background:rgba(52,199,89,.35);padding:12px;border-radius:12px;margin-bottom:16px}")
                .append("</style></head><body><main><h1>Glass Home</h1>");
        if (message != null) h.append("<div class=msg>").append(esc(message)).append("</div>");
        h.append("<form method=post><section><h2>Home Assistant</h2>")
                .append(field("haUrl", Ui.tr("Adress", "Address"), ha.optString("url", "http://homeassistant.local:8123"), false))
                .append(field("haToken", Ui.tr("Long-lived access token ", "Long-lived access token ") + (ha.has("token") ? saved : ""), "", true))
                .append(area("haEntities", Ui.tr("Rutor (entity id, en per rad)", "Tiles (entity ids, one per line)"), ha.optJSONArray("entities")))
                .append(area("haChips", Ui.tr("Uppe till höger (t.ex. weather.home)", "Top right (e.g. weather.home)"), ha.optJSONArray("chips")))
                .append(field("haMedia", Ui.tr("Spelas nu (media_player, valfritt)", "Now playing (media_player, optional)"), ha.optString("media", ""), false))
                .append("</section><section><h2>Jellyfin</h2>")
                .append(field("jfUrl", Ui.tr("Adress", "Address"), jf.optString("url", "http://192.168.1.10:8096"), false))
                .append(field("jfKey", Ui.tr("API-nyckel ", "API key ") + (jf.has("apiKey") ? saved : ""), "", true))
                .append(field("jfUser", Ui.tr("Användar-id", "User id"), jf.optString("userId", ""), false))
                .append("</section><button>").append(Ui.tr("Spara till TV:n", "Save to the TV")).append("</button></form></main></body></html>");
        return h.toString();
    }

    private static String field(String name, String label, String value, boolean secret) {
        return "<label>" + esc(label) + "</label><input name=" + name + (secret ? " type=password autocomplete=off" : "")
                + " value=\"" + esc(value) + "\">";
    }

    private static String area(String name, String label, JSONArray values) {
        StringBuilder v = new StringBuilder();
        for (int i = 0; values != null && i < values.length(); i++) v.append(values.optString(i)).append('\n');
        return "<label>" + esc(label) + "</label><textarea name=" + name + ">" + esc(v.toString().trim()) + "</textarea>";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void respond(OutputStream out, String status, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private static String lanAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
