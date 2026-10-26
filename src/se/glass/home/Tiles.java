package se.glass.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Map;
import java.util.Set;

/** Home Assistant tiles: glass card with name + state, amber when on, a brightness bar for lights. */
final class Tiles {
    static final int ON_TINT = 0xFFFFB340;   // iOS Home amber
    static final Set<String> READ_ONLY = Set.of("sensor", "binary_sensor", "weather", "sun", "person", "device_tracker", "zone");
    private static final Map<String, String> WEATHER = Map.ofEntries(
            Map.entry("sunny", "Soligt"), Map.entry("clear-night", "Klart"), Map.entry("partlycloudy", "Halvklart"),
            Map.entry("cloudy", "Molnigt"), Map.entry("rainy", "Regn"), Map.entry("pouring", "Skyfall"),
            Map.entry("snowy", "Snö"), Map.entry("snowy-rainy", "Snöblandat regn"), Map.entry("fog", "Dimma"),
            Map.entry("windy", "Blåsigt"), Map.entry("lightning", "Åska"), Map.entry("lightning-rainy", "Åska"),
            Map.entry("hail", "Hagel"));

    private Tiles() {}

    /** The tile's face; wrap in a CardView for focus motion. Tag = entity id. */
    static final class Face extends FrameLayout {
        final String id;
        final TextView name, value;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float brightness = -1;   // 0..1 for lights that are on, -1 = no bar
        boolean on;

        Face(Context c, String id, int w, int h) {
            super(c);
            this.id = id;
            setWillNotDraw(false);
            setBackground(new Glass(null, 0, true, 0.22f));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.BOTTOM);
            col.setPadding(Ui.px(26), Ui.px(18), Ui.px(26), Ui.px(26));
            name = Ui.text(c, id, 26, false, 0.85f);
            name.setSingleLine();
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            value = Ui.text(c, "…", h > Ui.px(170) ? 44 : 36, true, 1f);
            value.setSingleLine();
            col.addView(name);
            col.addView(value);
            addView(col, new LayoutParams(w, h));
            bar.setColor(0xD9FFFFFF);
        }

        boolean isAction() {
            return id.startsWith("scene.") || id.startsWith("script.") || id.startsWith("button.");
        }

        void bind(HomeAssistant ha, JSONObject s) {
            if (s == null) {
                value.setText(Ui.tr("Når inte hemmet", "Can't reach home"));
                return;
            }
            name.setText(ha.display(id, s));
            String state = s.optString("state");
            JSONObject attrs = s.optJSONObject("attributes");
            value.setText(isAction() ? Ui.tr("Starta", "Start") : pretty(state, attrs));   // a scene's state is a timestamp
            boolean nowOn = !isAction() && (state.equals("on") || state.equals("open") || state.equals("playing") || state.equals("unlocked"));
            if (nowOn != on) setBackground(new Glass(null, nowOn ? ON_TINT : 0, true, 0.22f));
            on = nowOn;
            float b = id.startsWith("light.") && nowOn && attrs != null && attrs.has("brightness") ? attrs.optInt("brightness") / 255f : -1;
            if (b != brightness) {
                brightness = b;
                invalidate();
            }
        }

        /** Short "Startad ✓" flash for scenes/scripts. */
        void flash() {
            value.setText(Ui.tr("Startad ✓", "Started ✓"));
            setBackground(new Glass(null, ON_TINT, true, 0.22f));
            postDelayed(() -> setBackground(new Glass(null, 0, true, 0.22f)), 1500);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (brightness < 0) return;
            float inset = getHeight() * 0.1f, t = Math.max(3, getHeight() * 0.035f), y = getHeight() - inset * 0.7f;
            RectF track = new RectF(inset * 1.4f, y - t, getWidth() - inset * 1.4f, y);
            bar.setAlpha(70);
            c.drawRoundRect(track, t / 2, t / 2, bar);
            bar.setAlpha(230);
            c.drawRoundRect(new RectF(track.left, track.top, track.left + track.width() * brightness, track.bottom), t / 2, t / 2, bar);
        }
    }

    static String pretty(String state, JSONObject attrs) {
        switch (state) {
            case "on": return Ui.tr("På", "On");
            case "off": return Ui.tr("Av", "Off");
            case "playing": return Ui.tr("Spelar", "Playing");
            case "paused": return Ui.tr("Pausad", "Paused");
            case "unavailable": return Ui.tr("Ej tillgänglig", "Unavailable");
            default: break;
        }
        if (state.isEmpty()) return "";
        String unit = attrs != null ? attrs.optString("unit_of_measurement", "") : "";
        return unit.isEmpty() ? state.substring(0, 1).toUpperCase() + state.substring(1) : state + " " + unit;
    }

    /** Chip text: weather gets "9.6 °C  Halvklart", others "Name  value". */
    static String chip(HomeAssistant ha, String id, JSONObject s) {
        if (s == null) return "";
        JSONObject a = s.optJSONObject("attributes");
        if (id.startsWith("weather.") && a != null) {
            return a.optString("temperature") + " " + a.optString("temperature_unit", "°C") + "  "
                    + WEATHER.getOrDefault(s.optString("state"), s.optString("state"));
        }
        return ha.display(id, s) + "  " + pretty(s.optString("state"), a);
    }

    /** Service for pressing a tile: toggle, run scene/script, press button; null = nothing to do. */
    static String[] serviceFor(String id, JSONObject state) {
        String domain = id.substring(0, id.indexOf('.'));
        if (READ_ONLY.contains(domain)) return null;
        if (domain.equals("button")) return new String[]{domain, "press"};
        if (domain.equals("scene") || domain.equals("script")) return new String[]{domain, "turn_on"};
        if (domain.equals("lock")) return new String[]{domain, state != null && "locked".equals(state.optString("state")) ? "unlock" : "lock"};
        if (domain.equals("media_player")) return new String[]{domain, "media_play_pause"};
        return new String[]{domain, "toggle"};
    }
}
