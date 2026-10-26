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

        private final Glass glass = new Glass(null, 0, true, 0.2f);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);

        Face(Context c, String id, int w, int h) {
            super(c);
            this.id = id;
            setWillNotDraw(false);
            setBackground(glass);
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.BOTTOM);
            int pad = Ui.px(h > Ui.px(170) ? Theme.S4 : Theme.S3);
            col.setPadding(pad, pad, pad, pad);
            name = Ui.text(c, id, Theme.Style.HEADLINE, Theme.PRIMARY);
            name.setSingleLine();
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            value = Ui.text(c, "…", Theme.Style.BODY, Theme.SECONDARY);
            value.setSingleLine();
            col.addView(name);
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2);
            vp.topMargin = Ui.px(6);
            col.addView(value, vp);
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
            if (nowOn != on) {
                glass.setGlassTint(nowOn ? ON_TINT : 0);
                value.setTextColor(Glass.withAlpha(0xFFFFFF, nowOn ? 0.85f : Theme.SECONDARY));
                invalidate();
            }
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
            glass.setGlassTint(ON_TINT);
            postDelayed(() -> glass.setGlassTint(on ? ON_TINT : 0), 1500);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            // small status glyph, top-left: a lamp dot (filled when on) or a scene spark
            float g = getHeight() * 0.09f, gx = getHeight() * 0.2f, gy = getHeight() * 0.22f;
            glyph.setStyle(on || isAction() ? Paint.Style.FILL : Paint.Style.STROKE);
            glyph.setStrokeWidth(Math.max(2f, g * 0.22f));
            glyph.setColor(on ? 0xFFFFFFFF : 0xB3FFFFFF);
            if (isAction()) {
                for (int i = 0; i < 4; i++) {   // four-point spark
                    c.save();
                    c.rotate(i * 90, gx, gy);
                    c.drawOval(gx - g * 0.18f, gy - g * 1.1f, gx + g * 0.18f, gy, glyph);
                    c.restore();
                }
            } else {
                c.drawCircle(gx, gy, g * 0.7f, glyph);
            }
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
