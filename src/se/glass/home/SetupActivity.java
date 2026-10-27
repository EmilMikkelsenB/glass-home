package se.glass.home;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * First-run setup (also reachable from "Startskärmen"): welcome → favourites → remote buttons → wallpaper → connections.
 * Started with EXTRA_PAGE it shows just that page and closes when done.
 */
public class SetupActivity extends Activity {
    static final String EXTRA_PAGE = "page";
    /** True while a wizard is on screen, so the launcher never stacks a second one. */
    static volatile boolean running;
    private static final String[] PAGES = {"welcome", "favourites", "buttons", "wallpaper", "connections"};

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Settings settings;
    private FrameLayout root, stage, dialog;
    private ImageView wall;
    private int page;
    private boolean single;
    private Runnable onDialogClose;
    private PhoneSetup phone;

    // favourites page state
    private final List<String> favs = new ArrayList<>();
    private Set<String> hidden;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        running = true;
        Ui.init(this);
        settings = new Settings(this);
        favs.addAll(settings.favourites());
        hidden = settings.hidden();

        root = new FrameLayout(this);
        wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wall, Ui.match());
        View dim = new View(this);
        dim.setBackgroundColor(0x59000000);
        root.addView(dim, Ui.match());
        stage = new FrameLayout(this);
        stage.setClipChildren(false);
        root.addView(stage, Ui.match());
        dialog = new FrameLayout(this);
        dialog.setBackgroundColor(0xEB0C0C14);   // nearly opaque: the page behind must not show through the options
        dialog.setVisibility(View.GONE);
        root.addView(dialog, Ui.match());
        setContentView(root);
        showWallpaper();

        String start = getIntent().getStringExtra(EXTRA_PAGE);
        single = start != null;
        page = 0;
        for (int i = 0; i < PAGES.length; i++) if (PAGES[i].equals(start)) page = i;
        show(page, 0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        KeyService.learner = null;
        if (phone != null) phone.stop();
    }

    private void showWallpaper() {
        io.execute(() -> {
            Bitmap bm = Wallpapers.current(this, settings);
            Bitmap frosted = bm != null ? Blur.frost(bm, 384, 8) : null;
            runOnUiThread(() -> wall.setImageBitmap(frosted));
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        Motion.noteKey(e);
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (dialog.getVisibility() == View.VISIBLE) closeDialog();
                else if (single || page == 0) finishSetup(single);
                else show(page - 1, -1);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---- page frame ------------------------------------------------------------
    private void show(int p, int direction) {
        KeyService.learner = null;
        page = p;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setPadding(Ui.px(120), Ui.px(80), Ui.px(120), Ui.px(60));
        switch (PAGES[p]) {
            case "welcome": welcome(content); break;
            case "favourites": favourites(content); break;
            case "buttons": buttons(content); break;
            case "wallpaper": wallpaper(content); break;
            default: connections(content); break;
        }
        View old = stage.getChildCount() > 0 ? stage.getChildAt(0) : null;
        stage.addView(content, Ui.match());
        if (old != null) {
            old.animate().translationX(-direction * Ui.px(200)).alpha(0f).setDuration(260).setInterpolator(Motion.EASE).withLayer()
                    .withEndAction(() -> stage.removeView(old)).start();
            content.setTranslationX(direction * Ui.px(240));
            content.setAlpha(0f);
            content.animate().translationX(0f).alpha(1f).setDuration(420).setStartDelay(80).setInterpolator(Motion.EASE).withLayer().start();
        }
        content.post(() -> {
            ArrayList<View> fs = new ArrayList<>();
            content.addFocusables(fs, View.FOCUS_DOWN);
            if (!fs.isEmpty()) fs.get(0).requestFocus();
        });
    }

    private void header(LinearLayout c, String title, String sub) {
        if (!single) c.addView(Ui.text(this, Ui.tr("Steg ", "Step ") + (page + 1) + Ui.tr(" av ", " of ") + PAGES.length, 24, true, 0.6f));
        c.addView(Ui.text(this, title, 64, true, 1f));
        if (sub != null) {
            TextView s = Ui.text(this, sub, 30, false, 0.85f);
            s.setMaxWidth(Ui.px(1400));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = Ui.px(10);
            c.addView(s, lp);
        }
    }

    private void footer(LinearLayout c, String next, Runnable onNext) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Pill go = new Pill(this, next, 0xFF0A84FF, 30);
        go.setOnClickListener(v -> onNext.run());
        bar.addView(go);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Ui.px(36);
        c.addView(bar, lp);
    }

    private void next() {
        if (single || page == PAGES.length - 1) finishSetup(true);
        else show(page + 1, 1);
    }

    private void finishSetup(boolean done) {
        if (done && !single) settings.setSetupDone(true);
        if (done || settings.setupDone()) finish();
    }

    // ---- 1. welcome ------------------------------------------------------------
    private void welcome(LinearLayout c) {
        c.setGravity(Gravity.CENTER);
        TextView hi = Ui.text(this, Ui.tr("Välkommen", "Welcome"), 120, true, 1f);
        c.addView(hi, new LinearLayout.LayoutParams(-2, -2));
        TextView s = Ui.text(this, Ui.tr("Vi ställer in din startskärm: favoriter, fjärrkontrollen och bakgrund. Det tar en minut.",
                "Let's set up your home screen: favourites, the remote and the wallpaper. It takes a minute."), 32, false, 0.85f);
        s.setGravity(Gravity.CENTER);
        s.setMaxWidth(Ui.px(1100));
        c.addView(s);
        LinearLayout bar = new LinearLayout(this);
        Pill go = new Pill(this, Ui.tr("Sätt igång", "Get started"), 0xFF0A84FF, 34);
        go.setOnClickListener(v -> next());
        bar.addView(go);
        if (settings.setupDone()) {
            Pill skip = new Pill(this, Ui.tr("Hoppa över", "Skip"), 0, 34);
            skip.setOnClickListener(v -> finishSetup(true));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = Ui.px(24);
            bar.addView(skip, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Ui.px(48);
        c.addView(bar, lp);
        hi.setAlpha(0f);
        hi.setScaleX(0.9f);
        hi.setScaleY(0.9f);
        hi.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(900).setInterpolator(Motion.EASE).start();
    }

    // ---- 2. favourites -----------------------------------------------------------
    private void favourites(LinearLayout c) {
        header(c, Ui.tr("Välj favoriter", "Choose favourites"),
                Ui.tr("Tryck OK på apparna du vill ha på startskärmen. Siffran är ordningen. Resten hamnar i Fler appar. Håll inne OK för att dölja en app helt.",
                        "Press OK on the apps you want on the home screen. The number is the order. The rest go to More apps. Hold OK to hide an app completely."));
        int cw = Ui.px(272), ch = cw * 10 / 16;
        List<View> cards = new ArrayList<>();
        List<TextView> badges = new ArrayList<>();
        List<Apps.App> apps = Apps.scan(this);
        for (Apps.App a : apps) {
            FrameLayout face = new FrameLayout(this);
            face.setClipChildren(false);
            face.addView(Apps.icon(this, a.pkg), new FrameLayout.LayoutParams(cw, ch));
            TextView badge = Ui.text(this, "", 26, true, 1f);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(badgeDot(Theme.BLUE));
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(Ui.px(54), Ui.px(54), Gravity.TOP | Gravity.END);
            bp.topMargin = -Ui.px(14);
            bp.rightMargin = -Ui.px(14);
            face.addView(badge, bp);
            badge.setTag(a.pkg);
            badges.add(badge);
            CardView card = new CardView(this, face, cw, ch, a.label, null, 1.08f, false);
            card.title.setGravity(Gravity.CENTER);   // when picking, every name stays readable
            Ui.style(card.title, Theme.Style.CAPTION, Theme.SECONDARY);
            card.setOnClickListener(v -> {
                if (favs.contains(a.pkg)) favs.remove(a.pkg);
                else {
                    favs.add(a.pkg);
                    hidden.remove(a.pkg);
                }
                updateBadges(badges, cards);
                Motion.kick(badge, View.SCALE_X, 1f, 8f, Motion.FOCUS_IN);
                Motion.kick(badge, View.SCALE_Y, 1f, 8f, Motion.FOCUS_IN);
            });
            card.setOnLongClickListener(v -> {
                if (hidden.contains(a.pkg)) hidden.remove(a.pkg);
                else {
                    hidden.add(a.pkg);
                    favs.remove(a.pkg);
                }
                updateBadges(badges, cards);
                return true;
            });
            card.setTag(a.pkg);
            cards.add(card);
        }
        updateBadges(badges, cards);
        GridLayout g = new GridLayout(this);
        g.setColumnCount(5);
        g.setClipChildren(false);
        for (View v : cards) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(Ui.px(Theme.S2), Ui.px(Theme.S1), Ui.px(Theme.S2), Ui.px(Theme.S2));
            g.addView(v, lp);
        }
        ScrollView sv = new ScrollView(this);   // clips (unlike most of our views): the grid must not draw over the footer
        sv.setVerticalScrollBarEnabled(false);
        sv.setFadingEdgeLength(Ui.px(60));
        sv.setVerticalFadingEdgeEnabled(true);
        g.setPadding(Ui.px(24), Ui.px(58), Ui.px(24), Ui.px(28));   // badges sit above the tiles   // room for the focused card's lift
        sv.addView(g);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 0, 1f);
        sp.topMargin = Ui.px(10);
        c.addView(sv, sp);
        footer(c, single ? Ui.tr("Klar", "Done") : Ui.tr("Fortsätt", "Continue"), () -> {
            settings.setFavourites(new ArrayList<>(favs));
            settings.setHidden(hidden);
            next();
        });
        Motion.stagger(cards.subList(0, Math.min(cards.size(), 15)), 25, Ui.px(30));
    }

    /** iOS-style round badge with a hairline light rim. */
    private static android.graphics.drawable.GradientDrawable badgeDot(int colour) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{AppIcon.lighten(colour, 0.15f), colour});
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setStroke(Math.max(1, Ui.px(1.5f)), 0x59FFFFFF);
        return d;
    }

    private void updateBadges(List<TextView> badges, List<View> cards) {
        for (int i = 0; i < badges.size(); i++) {
            TextView b = badges.get(i);
            String pkg = (String) b.getTag();
            int n = favs.indexOf(pkg);
            boolean hid = hidden.contains(pkg);
            b.setVisibility(n >= 0 || hid ? View.VISIBLE : View.INVISIBLE);
            b.setText(hid ? "–" : String.valueOf(n + 1));
            b.setBackground(badgeDot(hid ? 0xFF8E8E93 : Theme.BLUE));
            cards.get(i).setAlpha(hid ? 0.45f : 1f);
        }
    }

    // ---- 3. remote buttons ---------------------------------------------------------
    private void buttons(LinearLayout c) {
        header(c, Ui.tr("Fjärrkontrollens knappar", "Remote buttons"),
                Ui.tr("Tryck på en knapp du vill ställa in – till exempel mikrofonen, Netflix eller YouTube. Välj sedan vad den ska göra.",
                        "Press a button you want to set up – for example the mic, Netflix or YouTube. Then choose what it should do."));
        TextView listening = Ui.text(this, KeyService.running ? Ui.tr("● Lyssnar efter en knapp…", "● Listening for a button…")
                : Ui.tr("Knapptjänsten är avstängd. Kör installationsfilen igen på datorn, eller slå på “Glass Home buttons” under Inställningar → Tillgänglighet.",
                "The button service is off. Run the installer again on the PC, or turn on “Glass Home buttons” in Settings → Accessibility."), 30, true, 0.95f);
        listening.setMaxWidth(Ui.px(1400));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Ui.px(30);
        c.addView(listening, lp);
        if (KeyService.running) {
            listening.animate().alpha(0.45f).setDuration(900).withEndAction(new Runnable() {
                @Override
                public void run() {
                    if (!listening.isAttachedToWindow()) return;
                    listening.animate().alpha(listening.getAlpha() < 0.6f ? 1f : 0.45f).setDuration(900).withEndAction(this).start();
                }
            }).start();
        }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        JSONObject map = settings.buttons();
        for (Iterator<String> it = map.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONObject m = map.optJSONObject(key);
            String text = KeyService.keyName(key) + "  ·  " + Ui.tr("kort: ", "short: ") + KeyService.describe(this, m.optJSONObject("press"))
                    + (m.has("long") ? "  ·  " + Ui.tr("långt: ", "long: ") + KeyService.describe(this, m.optJSONObject("long")) : "");
            Pill p = new Pill(this, text, 0, 26);
            p.setOnClickListener(v -> configure(key));
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
            pl.topMargin = Ui.px(14);
            list.addView(p, pl);
        }
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2);
        ll.topMargin = Ui.px(24);
        c.addView(list, ll);
        footer(c, single ? Ui.tr("Klar", "Done") : Ui.tr("Fortsätt", "Continue"), this::next);
        KeyService.learner = key -> runOnUiThread(() -> {
            if (dialog.getVisibility() != View.VISIBLE) configure(key);
        });
    }

    /** Two-step chooser: short press, then long press. */
    private void configure(String key) {
        KeyService.learner = null;
        chooseAction(KeyService.keyName(key) + " · " + Ui.tr("kort tryck", "short press"), false, press -> chooseAction(
                KeyService.keyName(key) + " · " + Ui.tr("håll inne", "hold"), true, hold -> {
                    JSONObject map = settings.buttons();
                    try {
                        if (press == null && hold == null) map.remove(key);
                        else {
                            JSONObject m = new JSONObject();
                            if (press != null) m.put("press", press);
                            if (hold != null) m.put("long", hold);
                            map.put(key, m);
                        }
                    } catch (JSONException ignored) {
                    }
                    settings.setButtons(map);
                    closeDialog();   // re-shows the buttons page with the new mapping
                }));
    }

    private void chooseAction(String title, boolean isLong, Consumer<JSONObject> done) {
        List<View> items = new ArrayList<>();
        items.add(option(isLong ? Ui.tr("Ingenting", "Nothing") : Ui.tr("Standard (som vanligt)", "Default (as usual)"), () -> done.accept(null)));
        items.add(option(Ui.tr("Öppna Hemmet", "Open Home panel"), () -> done.accept(obj("panel", "home"))));
        items.add(option(Ui.tr("Öppna Fler appar", "Open More apps"), () -> done.accept(obj("panel", "more"))));
        items.add(option(Ui.tr("Sök", "Search"), () -> done.accept(obj("panel", "search"))));
        items.add(option(Ui.tr("Snabbinställningar", "Quick settings"), () -> done.accept(obj("panel", "quick"))));
        items.add(option(Ui.tr("Spela/pausa", "Play/pause"), () -> done.accept(obj("media", "play_pause"))));
        items.add(option(Ui.tr("Öppna en app…", "Open an app…"), () -> pickApp(done)));
        HomeAssistant ha = HomeAssistant.load(this, settings);
        if (ha != null) {
            items.add(option(Ui.tr("Starta en scen…", "Start a scene…"), () -> pickEntity(ha, "scene.", done)));
            items.add(option(Ui.tr("Tänd/släck en lampa…", "Toggle a light…"), () -> pickEntity(ha, "light.", done)));
        }
        openDialog(title, items, 2);
    }

    private void pickApp(Consumer<JSONObject> done) {
        List<View> items = new ArrayList<>();
        for (Apps.App a : Apps.scan(this)) items.add(option(a.label.toString(), () -> done.accept(obj("app", a.pkg))));
        openDialog(Ui.tr("Vilken app?", "Which app?"), items, 3);
    }

    private void pickEntity(HomeAssistant ha, String prefix, Consumer<JSONObject> done) {
        io.execute(() -> {
            List<String[]> found = new ArrayList<>();
            try {
                JSONArray all = ha.states();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject st = all.getJSONObject(i);
                    String id = st.optString("entity_id");
                    if (id.startsWith(prefix)) found.add(new String[]{id, ha.display(id, st)});
                }
            } catch (Exception ignored) {
            }
            runOnUiThread(() -> {
                List<View> items = new ArrayList<>();
                for (String[] e : found) {
                    items.add(option(e[1], () -> {
                        try {
                            JSONObject a = prefix.equals("scene.") ? obj("scene", e[0])
                                    : new JSONObject().put("service", "light/toggle").put("entity", e[0]);
                            done.accept(a.put("label", e[1]));
                        } catch (JSONException ignored) {
                        }
                    }));
                }
                if (items.isEmpty()) items.add(Ui.text(this, Ui.tr("Hittade inga – når inte Home Assistant?", "None found – can't reach Home Assistant?"), 28, false, 0.85f));
                openDialog(prefix.equals("scene.") ? Ui.tr("Vilken scen?", "Which scene?") : Ui.tr("Vilken lampa?", "Which light?"), items, 3);
            });
        });
    }

    private static JSONObject obj(String k, String v) {
        try {
            return new JSONObject().put(k, v);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private Pill option(String text, Runnable r) {
        Pill p = new Pill(this, text, 0, 28);
        p.setMinWidth(Ui.px(460));
        p.setOnClickListener(v -> r.run());
        return p;
    }

    private void openDialog(String title, List<View> items, int cols) {
        dialog.removeAllViews();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Ui.px(80), Ui.px(90), Ui.px(80), Ui.px(90));
        col.setClipChildren(false);
        col.addView(Ui.text(this, title, 52, true, 1f));
        GridLayout g = new GridLayout(this);
        g.setColumnCount(cols);
        g.setClipChildren(false);
        for (View v : items) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(Ui.px(10), Ui.px(10), Ui.px(10), Ui.px(10));
            g.addView(v, lp);
        }
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, -2);
        gp.topMargin = Ui.px(30);
        col.addView(g, gp);
        ScrollView sv = new ScrollView(this);
        sv.setClipChildren(false);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col);
        dialog.addView(sv, Ui.match());
        if (dialog.getVisibility() != View.VISIBLE) {
            dialog.setAlpha(0f);
            dialog.setVisibility(View.VISIBLE);
            dialog.animate().alpha(1f).setDuration(260).setInterpolator(Motion.EASE).start();
        }
        stage.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        sv.setScaleX(0.94f);
        sv.setScaleY(0.94f);
        sv.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(Motion.EASE).withLayer().start();
        g.post(() -> {
            ArrayList<View> fs = new ArrayList<>();
            g.addFocusables(fs, View.FOCUS_DOWN);
            if (!fs.isEmpty()) fs.get(0).requestFocus();
        });
    }

    private void closeDialog() {
        if (onDialogClose != null) {
            Runnable r = onDialogClose;
            onDialogClose = null;
            r.run();
        }
        dialog.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            dialog.setVisibility(View.GONE);
            dialog.removeAllViews();
        }).start();
        stage.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (PAGES[page].equals("buttons")) show(page, 0);
        else stage.post(() -> {
            ArrayList<View> fs = new ArrayList<>();
            stage.addFocusables(fs, View.FOCUS_DOWN);
            if (!fs.isEmpty()) fs.get(0).requestFocus();
        });
    }

    // ---- 4. wallpaper --------------------------------------------------------------
    private void wallpaper(LinearLayout c) {
        header(c, Ui.tr("Bakgrund", "Wallpaper"), Ui.tr("macOS-bilderna laddas ner till TV:n första gången du väljer dem.",
                "The macOS pictures are downloaded to the TV the first time you choose them."));
        String[][] options = {
                {Settings.WALL_GRADIENT, Ui.tr("Glas (inbyggd)", "Glass (built in)")},
                {Settings.WALL_SIERRA, "macOS Sierra"},
                {Settings.WALL_TAHOE, "macOS Tahoe"},
                {Settings.WALL_DYNAMIC, Ui.tr("Tahoe strand · följer dygnet", "Tahoe beach · follows the day")},
        };
        TextView status = Ui.text(this, "", 28, false, 0.85f);
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        for (String[] o : options) {
            boolean current = settings.wallpaper().equals(o[0]);
            String label = (current ? "✓  " : "") + o[1] + (Wallpapers.ready(this, o[0]) ? "" : Ui.tr("  ↓", "  ↓"));
            Pill p = new Pill(this, label, current ? 0xFF0A84FF : 0, 28);
            p.setOnClickListener(v -> chooseWallpaper(o[0], status));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Ui.px(18);
            row.addView(p, lp);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
        rl.topMargin = Ui.px(40);
        c.addView(row, rl);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = Ui.px(24);
        c.addView(status, sl);
        footer(c, single ? Ui.tr("Klar", "Done") : Ui.tr("Fortsätt", "Continue"), this::next);
    }

    private void chooseWallpaper(String choice, TextView status) {
        io.execute(() -> {
            try {
                Wallpapers.download(this, choice, msg -> runOnUiThread(() -> status.setText(msg)));
                settings.setWallpaper(choice);
                runOnUiThread(() -> {
                    status.setText(Ui.tr("Klart!", "Done!"));
                    showWallpaper();
                    show(page, 0);
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText(Ui.tr("Nedladdningen misslyckades. Är TV:n ansluten till internet?",
                        "The download failed. Is the TV connected to the internet?")));
            }
        });
    }

    // ---- 5. connections --------------------------------------------------------------
    private void connections(LinearLayout c) {
        header(c, Ui.tr("Anslutningar", "Connections"), Ui.tr("Valfritt: koppla ihop med Home Assistant och Jellyfin. Det går att göra senare under Startskärmen.",
                "Optional: connect Home Assistant and Jellyfin. You can do this later from Home screen settings."));
        boolean demo = settings.demo();
        String[][] status = {
                {"Home Assistant", HomeAssistant.load(this, settings) != null ? "✓" : "–"},
                {"Jellyfin", Jellyfin.load(this, settings) != null ? "✓" : "–"},
                {Ui.tr("Uppdateringar", "Updates"), "✓"},
        };
        for (String[] s : status) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = Ui.px(16);
            c.addView(Ui.text(this, s[1] + "   " + s[0] + (demo && !s[0].startsWith("U") ? Ui.tr(" (demo)", " (demo)") : ""), 34, true, 0.95f), lp);
        }
        LinearLayout bar = new LinearLayout(this);
        Pill phoneBtn = new Pill(this, Ui.tr("Ställ in från mobilen", "Set up from your phone"), 0, 30);
        phoneBtn.setOnClickListener(v -> phoneSetup());
        bar.addView(phoneBtn);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.topMargin = Ui.px(36);
        c.addView(bar, bl);
        footer(c, Ui.tr("Klar", "Done"), this::next);
    }

    private void phoneSetup() {
        if (phone != null) phone.stop();
        phone = new PhoneSetup(this, () -> runOnUiThread(() -> {
            closeDialog();
            show(page, 0);
        }));
        String url = phone.start();
        List<View> items = new ArrayList<>();
        if (url == null) {
            items.add(Ui.text(this, Ui.tr("Kunde inte starta (inget nätverk?)", "Couldn't start (no network?)"), 30, false, 0.9f));
        } else {
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(Qr.bitmap(url, Ui.px(400)));
            qr.setPadding(Ui.px(20), Ui.px(20), Ui.px(20), Ui.px(20));
            qr.setBackground(new Glass(null, 0xFFFFFFFF, true, 0.08f));
            items.add(qr);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(Ui.px(30), 0, 0, 0);
            col.addView(Ui.text(this, Ui.tr("Skanna med mobilens kamera, eller öppna:", "Scan with your phone's camera, or open:"), 28, false, 0.85f));
            col.addView(Ui.text(this, url, 32, true, 1f));
            Pill done = new Pill(this, Ui.tr("Klar", "Done"), 0, 28);
            done.setOnClickListener(v -> closeDialog());
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
            dl.topMargin = Ui.px(24);
            col.addView(done, dl);
            items.add(col);
        }
        onDialogClose = () -> {
            if (phone != null) phone.stop();
            phone = null;
        };
        openDialog(Ui.tr("Ställ in från mobilen", "Set up from your phone"), items, 2);
    }
}
