package se.glass.home;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** iOS 26-style TV home screen: centred clock, glass app buttons, a blurred "More" folder and Home Assistant tiles. */
public class MainActivity extends Activity {
    /** Front-row apps, in order: artwork key in assets/wide, package. */
    private static final String[][] FAVOURITES = {
            {"jellyfin", "org.jellyfin.androidtv"},
            {"tv4play", "se.tv4.tv4playtab"},
            {"svtplay", "se.svt.android.svtplay"},
            {"youtube", "com.google.android.youtube.tv"},
            {"netflix", "com.netflix.ninja"},
            {"moonlight", "com.limelight"},
    };
    /** Package -> artwork key in assets; anything else gets a runtime glass card around the app's own banner. */
    private static final Map<String, String> ART = new HashMap<>();
    static {
        for (String[] fav : FAVOURITES) ART.put(fav[1], fav[0]);
        ART.put("com.apple.atve.androidtv.appletv", "appletv");
        ART.put("com.wbd.stream", "max");
        ART.put("com.discovery.dplay", "discovery");
        ART.put("com.crunchyroll.crunchyroid", "crunchyroll");
        ART.put("tv.twitch.android.app", "twitch");
        ART.put("com.viaplay.android", "viaplay");
        ART.put("allente.b2c.tvclient", "allente");
        ART.put("com.spotify.tv.android", "spotify");
        ART.put("com.valvesoftware.steamlink", "steamlink");
        ART.put("com.disney.disneyplus", "disneyplus");
        ART.put("com.amazon.amazonvideo.livingroom", "primevideo");
        ART.put("com.android.tv.settings", "settings");
    }
    private static final Set<String> HIDDEN = Set.of("se.glass.home", "com.spocky.projengmenu");
    private static final Set<String> READ_ONLY = Set.of("sensor", "binary_sensor", "weather", "sun", "person", "device_tracker", "zone");
    private static final int ON_TINT = 0xFFFFB340;   // iOS Home amber for "on"
    /** Extra for deep links (from KeyService): "home" opens the Home Assistant panel, "more" the folder. */
    static final String EXTRA_OPEN = "open";
    private static final Map<String, String> WEATHER = Map.ofEntries(
            Map.entry("sunny", "Soligt"), Map.entry("clear-night", "Klart"), Map.entry("partlycloudy", "Halvklart"),
            Map.entry("cloudy", "Molnigt"), Map.entry("rainy", "Regn"), Map.entry("pouring", "Skyfall"),
            Map.entry("snowy", "Snö"), Map.entry("snowy-rainy", "Snöblandat regn"), Map.entry("fog", "Dimma"),
            Map.entry("windy", "Blåsigt"), Map.entry("lightning", "Åska"), Map.entry("lightning-rainy", "Åska"),
            Map.entry("hail", "Hagel"));

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private int W;
    private Typeface semibold;
    private PackageManager pm;
    private HomeAssistant ha;
    private ImageView wall;
    private FrameLayout home, overlay;
    private LinearLayout chips;
    private View returnFocus;
    private Runnable panelRefresh, chipRefresh;

    private int px(float v) {
        return Math.round(v * W / 1920f);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Rect r = getWindowManager().getCurrentWindowMetrics().getBounds();
        W = r.width();
        pm = getPackageManager();
        semibold = Typeface.create(Typeface.DEFAULT, 600, false);
        ha = HomeAssistant.load(this);

        FrameLayout root = new FrameLayout(this);
        wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wall.setImageBitmap(asset("wallpaper.jpg"));
        root.addView(wall, match());
        home = buildHome();
        root.addView(home, match());
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x33000000);
        overlay.setVisibility(View.GONE);
        root.addView(overlay, match());
        setContentView(root);
        handleOpen(getIntent());
    }

    private void handleOpen(Intent intent) {
        String open = intent != null ? intent.getStringExtra(EXTRA_OPEN) : null;
        if (open == null) return;
        intent.removeExtra(EXTRA_OPEN);
        ui.post(() -> {
            if (open.equals("home") && ha != null) openHomeAssistant();
            else if (open.equals("more")) openMore();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ha != null && !ha.chips.isEmpty()) {
            chipRefresh = () -> {
                for (int i = 0; i < chips.getChildCount(); i++) refreshChip((TextView) chips.getChildAt(i));
                ui.postDelayed(chipRefresh, 60_000);
            };
            ui.post(chipRefresh);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(chipRefresh);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closePanel();   // Home pressed while already home
        handleOpen(intent);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {   // a launcher never goes "back"; only closes panels
            if (e.getAction() == KeyEvent.ACTION_UP) closePanel();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---- home screen -------------------------------------------------------
    private FrameLayout buildHome() {
        FrameLayout f = new FrameLayout(this);
        f.setClipChildren(false);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        style(date, px(40), semibold, 0.85f);
        f.addView(date, top(px(122)));

        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        style(time, px(250), semibold, 0.9f);
        time.setIncludeFontPadding(false);
        f.addView(time, top(px(170)));

        chips = new LinearLayout(this);
        chips.setGravity(Gravity.END);
        if (ha != null) for (String id : ha.chips) chips.addView(chip(id));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cp.topMargin = px(48);
        cp.rightMargin = px(60);
        f.addView(chips, cp);

        int count = (ha != null ? 2 : 1);
        for (String pkg : favourites()) if (installed(pkg)) count++;
        // cards have transparent sides: overlap them like the preview (step = 252/320 of a card), shrink when many
        int cw = Math.min(px(320), Math.round((W - px(120)) / (1 + (count - 1) * 0.7875f))), ch = cw * 9 / 16;
        List<View> cards = new ArrayList<>();
        for (String pkg : favourites()) if (installed(pkg)) cards.add(appCard(pkg, ART.getOrDefault(pkg, "-"), cw, ch));
        if (ha != null) cards.add(card(art("homeassistant", null), tr("Hemmet", "Home"), cw, ch, this::openHomeAssistant));
        cards.add(card(art("more", null), tr("Fler appar", "More apps"), cw, ch, this::openMore));
        int overlap = Math.round(cw * (1 - 0.7875f));

        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        for (int i = 0; i < cards.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cw, -2);
            if (i > 0) lp.leftMargin = -overlap;
            row.addView(cards.get(i), lp);
        }
        f.addView(row, top(px(640)));
        View first = cards.get(0);
        first.post(first::requestFocus);
        return f;
    }

    /** Front-row packages: files/favorites.json (a JSON array of package names) if present, else FAVOURITES. */
    private List<String> favourites() {
        List<String> out = new ArrayList<>();
        File f = new File(getExternalFilesDir(null), "favorites.json");
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception e) {
            for (String[] fav : FAVOURITES) out.add(fav[1]);
        }
        return out;
    }

    /** Swedish when the TV is set to Swedish, English otherwise. */
    private static String tr(String sv, String en) {
        return Locale.getDefault().getLanguage().equals("sv") ? sv : en;
    }

    private View appCard(String pkg, String key, int cw, int ch) {
        return card(art(key, pkg), label(pkg), cw, ch, () -> launch(pkg));
    }

    private View card(Drawable art, CharSequence label, int cw, int ch, Runnable click) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setFocusable(true);
        v.setClipChildren(false);
        v.setClipToPadding(false);
        ImageView img = new ImageView(this);
        img.setImageDrawable(art);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.addView(img, new LinearLayout.LayoutParams(cw, ch));
        TextView t = new TextView(this);
        t.setText(label);
        style(t, px(26), Typeface.DEFAULT, 1f);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setAlpha(0.8f);
        v.addView(t, new LinearLayout.LayoutParams(cw, -2));
        v.setOnClickListener(x -> click.run());
        v.setOnFocusChangeListener((x, focused) -> {
            float s = focused ? 1.16f : 1f;   // big enough to spot from the sofa
            img.animate().scaleX(s).scaleY(s).setDuration(160).start();
            t.animate().alpha(focused ? 1f : 0.8f).setDuration(160).start();
            v.setZ(focused ? 1 : 0);
        });
        return v;
    }

    /** Pre-made glass artwork from assets/wide, or a runtime glass card around the app's own banner/icon. */
    private Drawable art(String key, String pkg) {
        Bitmap bm = asset("wide_" + key + ".png");
        if (bm != null) return new BitmapDrawable(getResources(), bm);
        Drawable own = null;
        if (pkg != null) {
            try {
                own = pm.getApplicationBanner(pkg);
                if (own == null) own = pm.getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return new Glass(own, 0, false, 0.28f);
    }

    // ---- panels (More folder, Home Assistant) ---------------------------------
    private void openMore() {
        Set<String> seen = new HashSet<>(HIDDEN);
        for (String[] fav : FAVOURITES) seen.add(fav[1]);
        List<String> pkgs = new ArrayList<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String p = ri.activityInfo.packageName;
                if (seen.add(p)) pkgs.add(p);
            }
        }
        pkgs.sort((a, b) -> label(a).toString().compareToIgnoreCase(label(b).toString()));
        int cw = px(300), ch = px(169);
        List<View> items = new ArrayList<>();
        for (String p : pkgs) items.add(card(art(ART.getOrDefault(p, "-"), p), label(p), cw, ch, () -> launch(p)));
        openPanel(tr("Fler appar", "More apps"), items, 5);
    }

    private void openHomeAssistant() {
        List<View> tiles = new ArrayList<>();
        for (String id : ha.entities) tiles.add(haTile(id));
        openPanel(tr("Hemmet", "Home"), tiles, 4);
        panelRefresh = () -> {
            for (View t : tiles) refreshTile(t);
            ui.postDelayed(panelRefresh, 15_000);
        };
        ui.post(panelRefresh);
    }

    private void openPanel(String title, List<View> items, int cols) {
        returnFocus = getCurrentFocus();
        overlay.removeAllViews();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        col.setPadding(px(80), px(90), px(80), px(80));
        TextView t = new TextView(this);
        t.setText(title);
        style(t, px(64), semibold, 0.95f);
        col.addView(t);
        GridLayout g = new GridLayout(this);
        g.setColumnCount(cols);
        g.setClipChildren(false);
        for (View v : items) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(px(10), px(10), px(10), px(10));
            if (v.getMinimumWidth() > 0) lp.width = v.getMinimumWidth();   // equal-width tiles
            g.addView(v, lp);
        }
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, -2);
        gp.topMargin = px(40);
        col.addView(g, gp);
        ScrollView sv = new ScrollView(this);
        sv.setClipChildren(false);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        overlay.addView(sv, match());

        wall.setRenderEffect(RenderEffect.createBlurEffect(px(60), px(60), Shader.TileMode.CLAMP));
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        home.animate().alpha(0f).setDuration(200).start();
        overlay.setAlpha(0f);
        overlay.setVisibility(View.VISIBLE);
        overlay.animate().alpha(1f).setDuration(200).start();
        if (!items.isEmpty()) items.get(0).post(items.get(0)::requestFocus);
    }

    private void closePanel() {
        if (overlay.getVisibility() != View.VISIBLE) return;
        ui.removeCallbacks(panelRefresh);
        wall.setRenderEffect(null);
        overlay.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            overlay.setVisibility(View.GONE);
            overlay.removeAllViews();
        }).start();
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        home.animate().alpha(1f).setDuration(180).start();
        if (returnFocus != null) returnFocus.requestFocus();
    }

    // ---- Home Assistant ----------------------------------------------------
    private View haTile(String id) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.BOTTOM);
        v.setPadding(px(30), px(24), px(30), px(30));
        v.setMinimumWidth(px(380));
        v.setMinimumHeight(px(210));
        v.setFocusable(true);
        v.setTag(id);
        v.setBackground(new Glass(null, 0, true, 0.22f));
        TextView name = new TextView(this);
        name.setText(id);
        style(name, px(28), Typeface.DEFAULT, 0.85f);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMaxWidth(px(320));
        TextView value = new TextView(this);
        value.setText("…");
        style(value, px(46), semibold, 1f);
        value.setSingleLine();
        v.addView(name);
        v.addView(value);
        v.setOnFocusChangeListener((x, focused) -> {
            float s = focused ? 1.08f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(160).start();
            v.setZ(focused ? 1 : 0);
        });
        v.setOnClickListener(x -> toggle(v, id));
        return v;
    }

    private void refreshTile(View tile) {
        String id = (String) tile.getTag();
        io.execute(() -> {
            JSONObject s;
            try {
                s = ha.state(id);
            } catch (Exception e) {
                s = null;
            }
            JSONObject st = s;
            runOnUiThread(() -> bindTile((LinearLayout) tile, id, st));
        });
    }

    private void bindTile(LinearLayout tile, String id, JSONObject s) {
        TextView name = (TextView) tile.getChildAt(0), value = (TextView) tile.getChildAt(1);
        if (s == null) {
            value.setText(tr("Når inte hemmet", "Can't reach home"));
            return;
        }
        JSONObject attrs = s.optJSONObject("attributes");
        String friendly = ha.names.getOrDefault(id, attrs != null ? attrs.optString("friendly_name", id) : id);
        name.setText(friendly.isEmpty() ? id : friendly.substring(0, 1).toUpperCase() + friendly.substring(1));
        String state = s.optString("state");
        boolean action = id.startsWith("scene.") || id.startsWith("script.") || id.startsWith("button.");
        value.setText(action ? tr("Starta", "Start") : pretty(state, attrs));   // a scene's state is just a timestamp
        boolean on = !action && (state.equals("on") || state.equals("open") || state.equals("playing") || state.equals("unlocked"));
        tile.setBackground(new Glass(null, on ? ON_TINT : 0, true, 0.22f));
    }

    private void toggle(View tile, String id) {
        String domain = id.substring(0, id.indexOf('.'));
        if (READ_ONLY.contains(domain)) return;
        String service = domain.equals("button") ? "press"
                : domain.equals("scene") || domain.equals("script") ? "turn_on"
                : domain.equals("lock") ? null : "toggle";
        if (service != null && service.equals("turn_on") || "press".equals(service)) {   // feedback for one-shot actions
            ((TextView) ((LinearLayout) tile).getChildAt(1)).setText(tr("Startad ✓", "Started ✓"));
            tile.setBackground(new Glass(null, ON_TINT, true, 0.22f));
            ui.postDelayed(() -> tile.setBackground(new Glass(null, 0, true, 0.22f)), 1500);
        }
        io.execute(() -> {
            try {
                String svc = service;
                if (svc == null) svc = "locked".equals(ha.state(id).optString("state")) ? "unlock" : "lock";
                ha.call(domain, svc, id);
            } catch (Exception ignored) {
            }
            ui.postDelayed(() -> refreshTile(tile), 1600);
        });
    }

    private TextView chip(String id) {
        TextView t = new TextView(this);
        t.setTag(id);
        style(t, px(26), semibold, 0.95f);
        t.setPadding(px(26), px(12), px(26), px(12));
        t.setBackground(new Glass(null, 0, true, 0.5f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = px(14);
        t.setLayoutParams(lp);
        return t;
    }

    private void refreshChip(TextView chip) {
        String id = (String) chip.getTag();
        io.execute(() -> {
            String text;
            try {
                JSONObject s = ha.state(id);
                JSONObject a = s.optJSONObject("attributes");
                if (id.startsWith("weather.") && a != null) {
                    text = a.optString("temperature") + " " + a.optString("temperature_unit", "°C") + "  "
                            + WEATHER.getOrDefault(s.optString("state"), s.optString("state"));
                } else {
                    String name = a != null ? a.optString("friendly_name", "") : "";
                    text = (name.isEmpty() ? "" : name + "  ") + pretty(s.optString("state"), a);
                }
            } catch (Exception e) {
                text = "";
            }
            String t = text;
            runOnUiThread(() -> {
                chip.setText(t);
                chip.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
            });
        });
    }

    private static String pretty(String state, JSONObject attrs) {
        switch (state) {
            case "on": return tr("På", "On");
            case "off": return tr("Av", "Off");
            case "unavailable": return tr("Ej tillgänglig", "Unavailable");
        }
        String unit = attrs != null ? attrs.optString("unit_of_measurement", "") : "";
        return unit.isEmpty() ? state.substring(0, 1).toUpperCase() + state.substring(1) : state + " " + unit;
    }

    // ---- helpers -----------------------------------------------------------
    private void launch(String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private boolean installed(String pkg) {
        try {
            return pm.getApplicationInfo(pkg, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private CharSequence label(String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai);
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private Bitmap asset(String path) {
        try (InputStream in = getAssets().open(path)) {
            Bitmap bm = BitmapFactory.decodeStream(in);
            if (bm == null) android.util.Log.w("GlassHome", "could not decode " + path);
            return bm;
        } catch (IOException e) {
            android.util.Log.w("GlassHome", "missing asset " + path + ": " + e);
            return null;
        }
    }

    private void style(TextView t, int sizePx, Typeface tf, float alpha) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        t.setTypeface(tf);
        t.setTextColor(Glass.withAlpha(0xFFFFFF, alpha));
        t.setShadowLayer(sizePx * 0.04f + 4, 0, 3, 0x59000000);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private static FrameLayout.LayoutParams top(int topMargin) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = topMargin;
        return lp;
    }
}
