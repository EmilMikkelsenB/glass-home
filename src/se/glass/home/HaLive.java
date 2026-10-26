package se.glass.home;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Live Home Assistant over its WebSocket API (a tiny RFC 6455 client: no AndroidX/OkHttp here). Delivers the initial states,
 * every state change, entity → area names (for rooms) and our own "glass_home_notify" events. Reconnects with back-off.
 * Callbacks run on the socket thread.
 */
final class HaLive {
    interface Listener {
        void onState(String entityId, JSONObject state);
        void onAreas(Map<String, String> entityArea);
        void onNotify(JSONObject data);
        void onConnection(boolean up);
    }

    private final String url, token;
    private final Listener listener;
    private volatile boolean running = true;
    private volatile Socket socket;
    private OutputStream out;
    private int nextId = 1;
    private final Map<Integer, String> pending = new HashMap<>();   // request id → what it was
    private JSONArray entityReg, deviceReg, areaReg;

    HaLive(String url, String token, Listener l) {
        this.url = url;
        this.token = token;
        this.listener = l;
        Thread t = new Thread(this::loop, "ha-live");
        t.setDaemon(true);
        t.start();
    }

    void close() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    private void loop() {
        int backoff = 2;
        while (running) {
            try {
                session();
                backoff = 2;
            } catch (Exception e) {
                Log.w("GlassHome", "ha live: " + e);
            }
            listener.onConnection(false);
            if (!running) return;
            try {
                Thread.sleep(backoff * 1000L);
            } catch (InterruptedException ignored) {
            }
            backoff = Math.min(backoff * 2, 60);
        }
    }

    private void session() throws Exception {
        URI u = URI.create(url);
        int port = u.getPort() > 0 ? u.getPort() : 80;
        Socket s = new Socket();
        s.connect(new InetSocketAddress(u.getHost(), port), 5000);
        s.setSoTimeout(70_000);   // HA pings well within this; we send our own pings too
        socket = s;
        out = s.getOutputStream();
        DataInputStream in = new DataInputStream(s.getInputStream());
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String handshake = "GET /api/websocket HTTP/1.1\r\nHost: " + u.getHost() + ":" + port + "\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: " + Base64.getEncoder().encodeToString(nonce)
                + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        out.write(handshake.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String status = readLine(in);
        if (!status.contains(" 101 ")) throw new IOException("handshake: " + status);
        while (!readLine(in).isEmpty()) { /* skip headers */ }

        long lastPing = System.currentTimeMillis();
        while (running) {
            String msg = readFrame(in);
            if (msg == null) continue;   // control frame
            Object parsed = msg.startsWith("[") ? new JSONArray(msg) : new JSONObject(msg);
            JSONArray batch = parsed instanceof JSONArray ? (JSONArray) parsed : new JSONArray().put(parsed);
            for (int i = 0; i < batch.length(); i++) handle(batch.getJSONObject(i));
            if (System.currentTimeMillis() - lastPing > 30_000) {
                send(new JSONObject().put("type", "ping"), "ping");
                lastPing = System.currentTimeMillis();
            }
        }
    }

    private void handle(JSONObject m) throws Exception {
        String type = m.optString("type");
        switch (type) {
            case "auth_required":
                sendRaw(new JSONObject().put("type", "auth").put("access_token", token));
                break;
            case "auth_ok":
                listener.onConnection(true);
                send(new JSONObject().put("type", "get_states"), "states");
                send(new JSONObject().put("type", "subscribe_events").put("event_type", "state_changed"), "sub");
                send(new JSONObject().put("type", "subscribe_events").put("event_type", "glass_home_notify"), "sub");
                send(new JSONObject().put("type", "config/entity_registry/list"), "entities");
                send(new JSONObject().put("type", "config/device_registry/list"), "devices");
                send(new JSONObject().put("type", "config/area_registry/list"), "areas");
                break;
            case "auth_invalid":
                running = false;
                throw new IOException("HA token rejected");
            case "result": {
                String what = pending.remove(m.optInt("id"));
                JSONArray r = m.optJSONArray("result");
                if (r == null || what == null) break;
                if (what.equals("states")) {
                    for (int i = 0; i < r.length(); i++) {
                        JSONObject st = r.getJSONObject(i);
                        listener.onState(st.optString("entity_id"), st);
                    }
                } else {
                    if (what.equals("entities")) entityReg = r;
                    if (what.equals("devices")) deviceReg = r;
                    if (what.equals("areas")) areaReg = r;
                    if (entityReg != null && deviceReg != null && areaReg != null) listener.onAreas(areas());
                }
                break;
            }
            case "event": {
                JSONObject ev = m.getJSONObject("event");
                JSONObject data = ev.optJSONObject("data");
                if ("state_changed".equals(ev.optString("event_type")) && data != null) {
                    JSONObject ns = data.optJSONObject("new_state");
                    if (ns != null) listener.onState(data.optString("entity_id"), ns);
                } else if ("glass_home_notify".equals(ev.optString("event_type"))) {
                    listener.onNotify(data != null ? data : new JSONObject());
                }
                break;
            }
            default:
                break;
        }
    }

    /** entity_id → area name, using the entity's own area or else its device's area. */
    private Map<String, String> areas() {
        Map<String, String> areaName = new HashMap<>(), deviceArea = new HashMap<>(), out = new HashMap<>();
        for (int i = 0; i < areaReg.length(); i++) {
            JSONObject a = areaReg.optJSONObject(i);
            areaName.put(a.optString("area_id"), a.optString("name"));
        }
        for (int i = 0; i < deviceReg.length(); i++) {
            JSONObject d = deviceReg.optJSONObject(i);
            if (!d.isNull("area_id")) deviceArea.put(d.optString("id"), d.optString("area_id"));
        }
        for (int i = 0; i < entityReg.length(); i++) {
            JSONObject e = entityReg.optJSONObject(i);
            String area = !e.isNull("area_id") ? e.optString("area_id") : deviceArea.get(e.optString("device_id"));
            if (area != null && areaName.containsKey(area)) out.put(e.optString("entity_id"), areaName.get(area));
        }
        return out;
    }

    private synchronized void send(JSONObject m, String what) throws IOException, org.json.JSONException {
        int id = nextId++;
        pending.put(id, what);
        sendRaw(m.put("id", id));
    }

    /** One masked text frame (clients must mask). */
    private synchronized void sendRaw(JSONObject m) throws IOException {
        byte[] data = m.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(0x81);
        if (data.length < 126) {
            f.write(0x80 | data.length);
        } else if (data.length < 65536) {
            f.write(0x80 | 126);
            f.write(data.length >> 8);
            f.write(data.length & 0xff);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write((int) ((long) data.length >> (8 * i)) & 0xff);
        }
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        f.write(mask);
        for (int i = 0; i < data.length; i++) f.write(data[i] ^ mask[i & 3]);
        out.write(f.toByteArray());
        out.flush();
    }

    /** Reads one (possibly fragmented) message; answers pings; returns null for control frames. */
    private String readFrame(DataInputStream in) throws IOException {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        while (true) {
            int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte();
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0x0f;
            long len = b1 & 0x7f;
            if (len == 126) len = in.readUnsignedShort();
            else if (len == 127) len = in.readLong();
            byte[] payload = new byte[(int) len];
            in.readFully(payload);
            if (op == 0x8) throw new IOException("closed by server");
            if (op == 0x9) {   // ping → pong
                synchronized (this) {
                    out.write(new byte[]{(byte) 0x8A, (byte) 0x80, 0, 0, 0, 0});
                    out.flush();
                }
                return null;
            }
            if (op == 0xA) return null;
            msg.write(payload);
            if (fin) return msg.toString("UTF-8");
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return sb.toString();
    }
}
