package se.glass.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Remaps remote buttons system-wide. Config on the TV: /sdcard/Android/data/se.glass.home/files/buttons.json
 * {"KEYCODE_ASSIST": {"press": {"panel": "home"}, "long": {"scene": "scene.filmkvall"}}}
 * Actions: {"app": pkg} {"panel": "home"|"more"} {"scene": id} {"service": "light/toggle", "entity": id}
 * Every key press is logged under tag GlassKeys, so `adb logcat -s GlassKeys` shows a button's name.
 */
public class KeyService extends AccessibilityService {
    private static final long LONG_PRESS_MS = 600;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private JSONObject buttons = new JSONObject();
    private long loadedAt = -1;
    private long downAt;

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        String key = KeyEvent.keyCodeToString(e.getKeyCode());
        Log.i("GlassKeys", key + " " + (e.getAction() == KeyEvent.ACTION_DOWN ? "down" : "up") + " scan=" + e.getScanCode());
        JSONObject map = mapping().optJSONObject(key);
        if (map == null) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) downAt = SystemClock.uptimeMillis();
            return true;
        }
        boolean longPress = SystemClock.uptimeMillis() - downAt >= LONG_PRESS_MS && map.has("long");
        run(map.optJSONObject(longPress ? "long" : "press"));
        return true;
    }

    private JSONObject mapping() {
        File f = new File(getExternalFilesDir(null), "buttons.json");
        if (f.lastModified() != loadedAt) {
            loadedAt = f.lastModified();
            try {
                buttons = f.exists() ? new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)) : new JSONObject();
            } catch (Exception ex) {
                Log.w("GlassKeys", "bad buttons.json", ex);
                buttons = new JSONObject();
            }
        }
        return buttons;
    }

    private void run(JSONObject a) {
        if (a == null) return;
        if (a.has("app")) {
            PackageManager pm = getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(a.optString("app"));
            if (i == null) i = pm.getLaunchIntentForPackage(a.optString("app"));
            if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (a.has("panel")) {
            startActivity(new Intent(this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_OPEN, a.optString("panel"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } else if (a.has("scene") || a.has("service")) {
            HomeAssistant ha = HomeAssistant.load(this);
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

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
}
