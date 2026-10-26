package se.glass.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Demo mode: believable fake apps, films and a fake home, for screenshots, friends and the emulator. No network. */
final class Demo {
    /** Front-row apps in demo mode: artwork key, label. */
    static final String[][] APPS = {
            {"jellyfin", "Jellyfin"}, {"svtplay", "SVT Play"}, {"youtube", "YouTube"}, {"netflix", "Netflix"},
            {"disneyplus", "Disney+"}, {"spotify", "Spotify"},
    };

    private Demo() {}

    static final class DemoJellyfin extends Jellyfin {
        DemoJellyfin() {
            super("http://demo", "", "demo");
        }

        @Override
        List<Item> continueWatching() {
            List<Item> out = new ArrayList<>();
            out.add(item("Aurora", "Episode", "Norrsken", 2, 4, 0.62f, 18, false, 0xFF3A7BD5));
            out.add(item("Skärgården", "Episode", "Dimma", 1, 7, 0.3f, 31, false, 0xFF00A6A6));
            out.add(item("Midsommar Express", "Movie", null, 0, 0, 0.44f, 52, false, 0xFFE07A5F));
            out.add(item("Fjällvandring", "Episode", "Toppen", 3, 1, 0f, 0, true, 0xFF6D597A));
            out.add(item("Köket", "Episode", "Kanelbullar", 1, 2, 0f, 0, true, 0xFFB56576));
            return out;
        }

        @Override
        List<Item> recentlyAdded() {
            List<Item> out = new ArrayList<>();
            int[] colors = {0xFF264653, 0xFF2A9D8F, 0xFFE9C46A, 0xFFF4A261, 0xFFE76F51, 0xFF8E7DBE};
            String[] names = {"Polstjärnan", "Vågbrytaren", "Sommarljus", "Kustvägen", "Glöd", "Stilla vatten"};
            for (int i = 0; i < names.length; i++) out.add(item(names[i], "Movie", null, 0, 0, 0f, 0, false, colors[i]));
            return out;
        }

        @Override
        List<User> users() {
            List<User> u = new ArrayList<>();
            u.add(new User("demo", "Demo"));
            u.add(new User("demo2", "Gäst"));
            return u;
        }

        @Override
        Bitmap image(Item it, List<String> paths, int width) {
            int h = width * 9 / 16;
            Bitmap bm = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            int dark = (it.demoColor & 0xFEFEFE) >> 1 | 0xFF000000;
            p.setShader(new LinearGradient(0, 0, width, h, it.demoColor, dark, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, width, h, p);
            p.setShader(null);
            p.setColor(0x33FFFFFF);
            c.drawCircle(width * 0.78f, h * 0.3f, h * 0.45f, p);
            p.setColor(0xF0FFFFFF);
            p.setTypeface(Typeface.create(Typeface.DEFAULT, 700, false));
            p.setTextSize(h * 0.17f);
            c.drawText(it.series != null && !it.series.isEmpty() ? it.series : it.name, width * 0.07f, h * 0.88f, p);
            return bm;
        }

        private static Item item(String title, String type, String episode, int s, int e, float progress, long left,
                                 boolean nextUp, int color) {
            Item it = new Item();
            it.id = "demo-" + title;
            it.type = type;
            it.series = type.equals("Episode") ? title : "";
            it.name = type.equals("Episode") ? episode : title;
            it.season = s;
            it.episode = e;
            it.year = 2025;
            it.progress = progress;
            it.remainingMin = left;
            it.nextUp = nextUp;
            it.demoColor = color;
            it.images.add("demo");
            it.backdrops.add("demo");
            return it;
        }
    }

    static final class DemoHome extends HomeAssistant {
        private final Map<String, JSONObject> states = new HashMap<>();
        private HaLive.Listener listener;

        DemoHome() {
            super("http://demo", "");
            String[][] seed = {
                    {"scene.movie", "Filmkväll", "scening", "Vardagsrum"}, {"scene.cosy", "Mys", "scening", "Vardagsrum"},
                    {"scene.bright", "Ljust", "scening", ""}, {"scene.night", "Godnatt", "scening", ""},
                    {"light.living_ceiling", "Vardagsrum tak", "on", "Vardagsrum"},
                    {"light.living_lamp", "Golvlampa", "off", "Vardagsrum"},
                    {"light.kitchen", "Kök tak", "on", "Kök"}, {"light.hall", "Hallen", "off", "Hall"},
                    {"weather.home", "Väder", "partlycloudy", ""}, {"media_player.living", "Vardagsrum", "playing", ""},
            };
            for (String[] s : seed) {
                if (!s[0].startsWith("weather") && !s[0].startsWith("media")) entities.add(s[0]);
                try {
                    JSONObject a = new JSONObject().put("friendly_name", s[1]);
                    if (s[0].startsWith("light.")) a.put("brightness", s[2].equals("on") ? 180 : 0);
                    if (s[0].startsWith("weather.")) a.put("temperature", 9.5).put("temperature_unit", "°C");
                    if (s[0].startsWith("media_player.")) a.put("media_title", "Nattsudd").put("media_artist", "Demo Band");
                    states.put(s[0], new JSONObject().put("entity_id", s[0]).put("state", s[2]).put("attributes", a)
                            .put("demo_area", s[3]));
                } catch (JSONException ignored) {
                }
            }
            chips.add("weather.home");
            media = "media_player.living";
        }

        @Override
        HaLive live(HaLive.Listener l) {
            listener = l;
            l.onConnection(true);
            Map<String, String> areas = new HashMap<>();
            for (JSONObject st : states.values()) {
                l.onState(st.optString("entity_id"), st);
                if (!st.optString("demo_area").isEmpty()) areas.put(st.optString("entity_id"), st.optString("demo_area"));
            }
            l.onAreas(areas);
            return null;
        }

        @Override
        JSONObject state(String id) {
            return states.get(id);
        }

        @Override
        JSONArray states() {
            return new JSONArray(states.values());
        }

        @Override
        void call(String domain, String service, JSONObject data) {
            String id = data.optString("entity_id");
            JSONObject st = states.get(id);
            if (st == null || listener == null) return;
            try {
                JSONObject a = st.getJSONObject("attributes");
                if (domain.equals("light")) {
                    boolean on = st.optString("state").equals("on");
                    int b = a.optInt("brightness");
                    if (service.equals("toggle")) on = !on;
                    if (data.has("brightness_step_pct")) {
                        b = Math.max(0, Math.min(255, (on ? b : 0) + data.getInt("brightness_step_pct") * 255 / 100));
                        on = b > 0;
                    }
                    if (on && b == 0) b = 180;
                    st.put("state", on ? "on" : "off");
                    a.put("brightness", on ? b : 0);
                } else if (domain.equals("media_player")) {
                    st.put("state", st.optString("state").equals("playing") ? "paused" : "playing");
                }
                listener.onState(id, st);
            } catch (JSONException ignored) {
            }
        }
    }
}
