package se.glass.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.media.AudioManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONObject;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Remaps remote buttons system-wide, using the mapping chosen in the setup wizard (Settings.buttons()):
 * {"KEYCODE_ASSIST": {"press": {"panel": "home"}, "long": {"scene": "scene.filmkvall"}}}
 * Actions: {"app": pkg} {"panel": "home"|"more"|"settings"} {"scene": id} {"service": "light/toggle", "entity": id}
 * {"media": "play_pause"}. While the wizard is learning, the next non-navigation key goes to {@link #learner} instead.
 * Every key press is logged under tag GlassKeys, so `adb logcat -s GlassKeys` shows a button's name.
 */
public class KeyService extends AccessibilityService {
    interface Learner {
        void learned(String keyName);
    }

    /** Set by the setup wizard while it waits for "press the button you want to set up". */
    static volatile Learner learner;
    static volatile boolean running;

    /** Keys that are never remapped or learned: you need them to use the TV. */
    static final Set<Integer> NAVIGATION = Set.of(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER);

    private static final long LONG_PRESS_MS = 600;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private long downAt;

    @Override
    protected void onServiceConnected() {
        running = true;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = false;
        return super.onUnbind(intent);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        String key = KeyEvent.keyCodeToString(e.getKeyCode());
        Log.i("GlassKeys", key + " " + (e.getAction() == KeyEvent.ACTION_DOWN ? "down" : "up") + " scan=" + e.getScanCode());
        if (NAVIGATION.contains(e.getKeyCode())) return false;
        Learner l = learner;
        if (l != null) {
            if (e.getAction() == KeyEvent.ACTION_UP) l.learned(key);
            return true;
        }
        JSONObject map = new Settings(this).buttons().optJSONObject(key);
        if (map == null) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) downAt = SystemClock.uptimeMillis();
            return true;
        }
        boolean longPress = SystemClock.uptimeMillis() - downAt >= LONG_PRESS_MS && map.has("long");
        run(map.optJSONObject(longPress ? "long" : "press"));
        return true;
    }

    private void run(JSONObject a) {
        if (a == null) return;
        if (a.has("app")) {
            Apps.launch(this, a.optString("app"));
        } else if (a.has("panel")) {
            startActivity(new Intent(this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_OPEN, a.optString("panel"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } else if (a.has("media")) {
            AudioManager am = getSystemService(AudioManager.class);
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        } else if (a.has("scene") || a.has("service")) {
            HomeAssistant ha = HomeAssistant.load(this, new Settings(this));
            if (ha == null) return;
            String id = a.has("scene") ? a.optString("scene") : a.optString("entity");
            String[] svc = (a.has("scene") ? "scene/turn_on" : a.optString("service")).split("/", 2);
            io.execute(() -> {
                try {
                    ha.call(svc[0], svc[1], id);
                } catch (Exception ex) {
                    Log.w("GlassKeys", "HA call failed", ex);
                }
            });
        }
    }

    /** Human description of an action, for the wizard. */
    static String describe(android.content.Context c, JSONObject a) {
        if (a == null) return Ui.tr("Standard", "Default");
        if (a.has("app")) return Ui.tr("Öppna ", "Open ") + Apps.label(c, a.optString("app"));
        if (a.has("panel")) {
            switch (a.optString("panel")) {
                case "home": return Ui.tr("Hemmet", "Home panel");
                case "more": return Ui.tr("Fler appar", "More apps");
                case "search": return Ui.tr("Sök", "Search");
                case "quick": return Ui.tr("Snabbinställningar", "Quick settings");
                default: return Ui.tr("Startskärmens inställningar", "Home screen settings");
            }
        }
        if (a.has("media")) return Ui.tr("Spela/pausa", "Play/pause");
        if (a.has("scene")) return Ui.tr("Scen: ", "Scene: ") + a.optString("label", readable(a.optString("scene")));
        if (a.has("service")) return Ui.tr("Tänd/släck: ", "Toggle: ") + a.optString("label", readable(a.optString("entity")));
        return Ui.tr("Standard", "Default");
    }

    /** "scene.movie_night" → "Movie night" (for mappings saved without a label). */
    private static String readable(String entityId) {
        String s = entityId.substring(entityId.indexOf('.') + 1).replace('_', ' ');
        return s.isEmpty() ? entityId : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Friendly name for a key code name (KEYCODE_ASSIST → "Mikrofon/Assistent"). */
    static String keyName(String key) {
        switch (key) {
            case "KEYCODE_ASSIST":
            case "KEYCODE_VOICE_ASSIST":
            case "KEYCODE_SEARCH": return Ui.tr("Mikrofon / Assistent", "Mic / Assistant");
            case "KEYCODE_TV_INPUT": return Ui.tr("Källa (input)", "Input");
            case "KEYCODE_BOOKMARK": return Ui.tr("Stjärnknappen", "Star button");
            case "KEYCODE_SETTINGS": return Ui.tr("Inställningar", "Settings");
            case "KEYCODE_GUIDE": return Ui.tr("Guide", "Guide");
            case "KEYCODE_PROG_RED": return Ui.tr("Röd knapp", "Red button");
            case "KEYCODE_ALL_APPS": return Ui.tr("Alla appar", "All apps");
            default:
                if (key.startsWith("KEYCODE_")) key = key.substring(8);
                return key.charAt(0) + key.substring(1).toLowerCase().replace('_', ' ');
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
}
