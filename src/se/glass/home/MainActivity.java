package se.glass.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Glass Home: iOS 26-style TV home screen. Collapsed it shows the clock and favourites; press down for Jellyfin rows and
 * Home Assistant quick tiles. Panels ("Fler appar", "Hemmet", settings) zoom out of the card that opened them.
 */
public class MainActivity extends Activity implements HaLive.Listener {
    /** Extra for deep links (from KeyService): "home" opens the Home Assistant panel, "more" the folder, "settings". */
    static final String EXTRA_OPEN = "open";
    static final String REPO = "EmilMikkelsenB/glass-home";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService images = Executors.newFixedThreadPool(3);
    private Settings settings;
    private long builtVersion = -1;
    private boolean resumed;
    /**
     * Opens the setup guide once the home screen has stayed on top for a moment. Home clears the launcher task (closing the
     * guide), so this reopens it; the delay + "still resumed" check means rapid Home presses never stack two guides.
     */
    private final Runnable setupCheck = () -> {
        if (resumed && !settings.setupDone() && !SetupActivity.running) startActivity(new Intent(this, SetupActivity.class));
    };

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(setupCheck);
    }

    private HomeAssistant ha;
    private HaLive live;
    private final Map<String, JSONObject> haStates = new HashMap<>();
    private Map<String, String> haAreas = new HashMap<>();
    private final Map<String, List<Tiles.Face>> faces = new HashMap<>();
    private final Map<String, TextView> chipViews = new HashMap<>();
    private volatile Set<String> haInterest = new HashSet<>();
    private Jellyfin jf;
    private Jellyseerr seerr;
    private Updater updater;

    private FrameLayout root, overlay;
    private ImageView wall, frost, shelfA, shelfB;
    private boolean shelfOnA;
    private HomeView home;
    private RowView favRow, resumeRow, latestRow, wishRow, quickRow;
    private Pill updatePill, appPill, playingPill;
    private Updater.Release pendingUpdate;
    private String newApp;
    private long updateCheckedAt, mediaFetchedAt;
    private String wallKey;
    private View returnFocus;
    private Runnable onPanelClose, shelfTask;
    private final List<Tiles.Face> panelFaces = new ArrayList<>();

    private CardView moving;          // favourites move mode
    private ValueAnimator wiggle;
    private Tiles.Face dimming;       // light brightness mode
    private PhoneSetup phone;

    // ---- lifecycle -----------------------------------------------------------
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        settings = new Settings(this);

        root = new FrameLayout(this);
        wall = new ImageView(this) {   // the glass samples this view's transform: resample when it moves
            @Override public void setTranslationX(float x) { super.setTranslationX(x); Backdrop.SHARED.invalidateAll(); }
            @Override public void setTranslationY(float y) { super.setTranslationY(y); Backdrop.SHARED.invalidateAll(); }
        };
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wall.setScaleX(1.06f);
        wall.setScaleY(1.06f);
        Backdrop.SHARED.setWallView(wall);
        Motion.onFrame = Backdrop.SHARED::invalidateAll;
        shelfA = image(1f);
        shelfB = image(1f);
        for (ImageView s : new ImageView[]{shelfA, shelfB}) {   // backdrops are bright: dim them so text stays readable
            s.setColorFilter(0x4D000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
        }
        frost = image(1.06f);
        frost.setAlpha(0f);   // only shown behind panels
        root.addView(wall, Ui.match());
        root.addView(shelfA, Ui.match());
        root.addView(shelfB, Ui.match());
        root.addView(frost, Ui.match());
        home = new HomeView(this);
        root.addView(home, Ui.match());
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x33000000);
        overlay.setVisibility(View.GONE);
        overlay.setClipChildren(false);
        root.addView(overlay, Ui.match());
        setContentView(root);

        loadWallpaper(false);
        reload();
        handleOpen(getIntent());
        ui.postDelayed(wallTick, 60_000);
    }

    private ImageView image(float scale) {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setScaleX(scale);
        v.setScaleY(scale);
        if (scale == 1f) v.setAlpha(0f);
        return v;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Home clears the launcher task, which would close the wizard: reopen it until setup is finished
        resumed = true;
        ui.removeCallbacks(setupCheck);
        if (!settings.setupDone()) ui.postDelayed(setupCheck, 700);
        if (settings.version() != builtVersion) {
            reload();
            loadWallpaper(true);
        } else {
            refreshMedia();
        }
        checkUpdate();
        checkNewApps();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (live != null) live.close();
        if (phone != null) phone.stop();
        ui.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (moving != null) endMove();
        dimming = null;
        closePanel();
        if (home.expanded) home.setExpanded(false);   // Home pressed while home: back to the calm collapsed view
        if (favRow != null) favRow.focusLast();
        handleOpen(intent);
    }

    private void handleOpen(Intent intent) {
        String open = intent != null ? intent.getStringExtra(EXTRA_OPEN) : null;
        if (open == null) return;
        intent.removeExtra(EXTRA_OPEN);
        ui.post(() -> {
            if (open.equals("home") && ha != null) openHomePanel(null);
            else if (open.equals("more")) openMore(null);
            else if (open.equals("settings")) openSettings(null);
            else if (open.equals("search")) openSearch(null);
            else if (open.equals("quick")) openQuick(null);
        });
    }

    /** (Re)load sources and rebuild the home screen, e.g. after the wizard or a settings change. */
    private void reload() {
        builtVersion = settings.version();
        if (live != null) live.close();
        live = null;
        ha = HomeAssistant.load(this, settings);
        jf = Jellyfin.load(this, settings);
        seerr = Jellyseerr.load(this, settings);
        updater = Updater.load(this, REPO);
        haStates.clear();
        Set<String> interest = new HashSet<>();
        if (ha != null) {
            interest.addAll(ha.entities);
            interest.addAll(ha.chips);
            if (ha.media != null) interest.add(ha.media);
        }
        haInterest = interest;
        buildHome();
        if (ha != null) {
            live = ha.live(this);
            if (!settings.demo()) seedStates();
        }
        mediaFetchedAt = 0;
        refreshMedia();
    }

    // ---- keys ----------------------------------------------------------------
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        Motion.noteKey(e);
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (dimming != null) return dimKey(e);
        if (moving != null) return moveKey(e);
        if (down && e.getRepeatCount() >= 4 && overlay.getVisibility() != View.VISIBLE
                && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            super.dispatchKeyEvent(e);   // held: two cards per repeat (the scroll spring keeps it smooth)
        }
        if (code == KeyEvent.KEYCODE_BACK) {   // a launcher never goes "back"; it only closes and collapses
            if (!down) back();
            return true;
        }
        View f = getCurrentFocus();
        if (down && code == KeyEvent.KEYCODE_DPAD_DOWN && overlay.getVisibility() != View.VISIBLE && f != null
                && (f.getParent() == home.chips || f.getParent() == home.pills)) {
            return favRow.focusLast();   // top pills → back to the apps
        }
        if (down && overlay.getVisibility() != View.VISIBLE
                && (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP)) {
            int r = home.rowOf(getCurrentFocus());
            if (r >= 0) {
                int dir = code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
                int n = home.neighbour(r, dir);
                if (n >= 0) {
                    if (!home.expanded) home.setExpanded(true);
                    home.rows.get(n).focusLast();
                    return true;
                }
                if (dir > 0) return true;
                if (!home.expanded && topPill != null) return topPill.requestFocus();
                if (home.expanded) {
                    home.setExpanded(false);
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void back() {
        if (overlay.getVisibility() == View.VISIBLE) {
            closePanel();
        } else if (home.expanded) {
            home.setExpanded(false);
            favRow.focusLast();
        }
    }

    // ---- home screen ---------------------------------------------------------
    private void buildHome() {
        home.clearRows();
        faces.clear();
        chipViews.clear();
        home.chips.removeAllViews();
        if (ha != null) {
            for (String id : ha.chips) {
                TextView chip = Ui.text(this, "", Theme.Style.CAPTION, Theme.PRIMARY);
                chip.setPadding(Ui.px(Theme.S3), Ui.px(12), Ui.px(Theme.S3), Ui.px(12));
                chip.setBackground(new Glass(null, 0, true, 0.5f));
                chip.setVisibility(View.GONE);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.leftMargin = Ui.px(14);
                home.chips.addView(chip, lp);
                chipViews.put(id, chip);
            }
        }

        Pill search = new Pill(this, Ui.tr("Sök", "Search"), 0, 22);
        topPill = jf != null || seerr != null ? search : null;
        search.setOnClickListener(this::openSearch);
        Pill gear = new Pill(this, "\u2699", 0, 22);
        gear.setOnClickListener(this::openQuick);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.leftMargin = Ui.px(14);
        if (jf != null || seerr != null) home.chips.addView(search, 0, sp);
        home.chips.addView(gear, new LinearLayout.LayoutParams(sp));
        if (topPill == null) topPill = gear;
        // the weather chip between them isn't focusable, so the default search jumps back down to the apps
        search.setId(View.generateViewId());
        gear.setId(View.generateViewId());
        search.setNextFocusRightId(gear.getId());
        gear.setNextFocusLeftId(search.getId());
        gear.setNextFocusRightId(gear.getId());

        home.pills.removeAllViews();
        updatePill = pill(0xFF0A84FF, v -> openUpdate(v));
        appPill = pill(0, v -> addNewApp());
        appPill.setOnLongClickListener(v -> {
            dismissNewApp();
            return true;
        });
        playingPill = pill(0, v -> mediaPlayPause());
        sleepPill = pill(0, v -> {
            SleepReceiver.set(this, 0);
            showSleep();
        });
        showSleep();
        if (pendingUpdate != null) showUpdate(pendingUpdate);

        // favourites + Hemmet + Fler appar
        favRow = new RowView(this, null, Theme.ROW_BLEED_X, true);
        List<CardView> cards = new ArrayList<>();
        int n = (ha != null ? 2 : 1) + (settings.demo() ? Demo.APPS.length : settings.favourites().size());
        int gap = Ui.px(28);
        int cw = Math.max(Ui.px(200), Math.min(Ui.px(300), (Ui.W - 2 * Ui.px(Theme.ROW_BLEED_X) - (n - 1) * gap) / n));
        int ch = cw * 10 / 16;
        if (settings.demo()) {
            for (String[] a : Demo.APPS) cards.add(appCard(Apps.iconKey(this, a[0]), a[1], cw, ch, v -> toast(a[1], Ui.tr("Demoläge", "Demo mode"))));
        } else {
            for (String pkg : settings.favourites()) {
                if (!Apps.installed(this, pkg)) continue;
                CardView c = appCard(Apps.icon(this, pkg), Apps.label(this, pkg), cw, ch, v -> Apps.launch(this, pkg));
                c.setTag(pkg);
                c.setOnLongClickListener(v -> {
                    favouriteMenu((CardView) v, pkg);
                    return true;
                });
                cards.add(c);
            }
        }
        if (ha != null) cards.add(appCard(Apps.iconKey(this, "homeassistant"), Ui.tr("Hemmet", "Home"), cw, ch, this::openHomePanel));
        CardView more = appCard(Apps.iconKey(this, "more"), Ui.tr("Fler appar", "More apps"), cw, ch, this::openMore);
        more.setOnLongClickListener(v -> {
            openSettings(v);
            return true;
        });
        cards.add(more);
        favRow.setItems(cards, cw, gap);
        favRow.listener = this::onCardFocused;
        home.addRow(favRow);

        resumeRow = latestRow = quickRow = null;
        if (jf != null) {
            resumeRow = mediaRow(Ui.tr("Fortsätt titta", "Continue watching"), Cache.loadItems(this, "resume"));
            latestRow = mediaRow(Ui.tr("Nyligen tillagt", "Recently added"), Cache.loadItems(this, "latest"));
        }
        wishRow = null;
        if (seerr != null) {
            wishRow = new RowView(this, Ui.tr("Önska", "Request"), Theme.SAFE_X, false);
            wishRow.listener = this::onCardFocused;
            wishRow.setVisibility(View.GONE);
            home.addRow(wishRow);
        }
        if (ha != null && !ha.entities.isEmpty()) {
            quickRow = new RowView(this, Ui.tr("Hemmet", "Home"), Theme.SAFE_X, false);
            List<CardView> tiles = new ArrayList<>();
            for (String id : ha.entities) tiles.add(tileCard(id, Ui.px(300), Ui.px(160), false));
            quickRow.setItems(tiles, Ui.px(300), Ui.px(Theme.S4));
            quickRow.listener = this::onCardFocused;
            home.addRow(quickRow);
        }
        home.expanded = false;
        home.focusedRow = 0;
        home.post(() -> {
            home.apply(false);
            favRow.focusLast();
        });
    }

    private Pill pill(int tint, View.OnClickListener click) {
        Pill p = new Pill(this, "", tint, 22);
        p.setVisibility(View.GONE);
        p.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = Ui.px(16);
        home.pills.addView(p, lp);
        return p;
    }

    private CardView appCard(AppIcon icon, CharSequence label, int w, int h, View.OnClickListener click) {
        CardView c = new CardView(this, icon, w, h, label, null, 1.13f, true);
        c.setOnClickListener(click);
        return c;
    }

    private RowView mediaRow(String title, List<Jellyfin.Item> cached) {
        RowView row = new RowView(this, title, Theme.SAFE_X, false);
        row.listener = this::onCardFocused;
        home.addRow(row);
        bindItems(row, cached);
        return row;
    }

    private void bindItems(RowView row, List<Jellyfin.Item> items) {
        if (row == null) return;
        boolean hadFocus = row.hasFocus();
        int mw = Ui.px(352), mh = mw * 9 / 16;
        List<CardView> cards = new ArrayList<>();
        for (Jellyfin.Item it : items) cards.add(mediaCard(it, mw, mh));
        row.setItems(cards, mw, Ui.px(Theme.S4));
        row.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        if (hadFocus && !items.isEmpty()) row.focusLast();
        else if (hadFocus) favRow.focusLast();
    }

    private CardView mediaCard(Jellyfin.Item it, int mw, int mh) {
        Poster poster = new Poster(it.progress);
        ImageView img = new ImageView(this);
        img.setImageDrawable(poster);
        boolean episode = it.type.equals("Episode");
        String where = episode ? "S" + it.season + " E" + it.episode + " · " + it.name : (it.year > 0 ? String.valueOf(it.year) : "");
        String when = it.nextUp ? Ui.tr("Nästa: ", "Next: ")
                : it.progress > 0 ? Ui.tr(it.remainingMin + " min kvar · ", it.remainingMin + " min left · ") : "";
        CardView c = new CardView(this, img, mw, mh, episode ? it.series : it.name, when + where, 1.1f, false);
        c.setTag(it);
        c.setOnClickListener(v -> {
            if (settings.demo()) toast(c.title.getText().toString(), Ui.tr("Demoläge", "Demo mode"));
            else playDirect(it);
        });
        c.setOnLongClickListener(v -> {
            mediaMenu(c);
            return true;
        });
        Jellyfin source = jf;
        images.execute(() -> {
            Bitmap bm = source.image(it, it.images, 480);
            if (bm != null) runOnUiThread(() -> poster.setBitmap(bm));
        });
        return c;
    }

    private CardView tileCard(String id, int w, int h, boolean inPanel) {
        Tiles.Face face = new Tiles.Face(this, id, w, h);
        face.bind(ha, haStates.get(id));
        faces.computeIfAbsent(id, k -> new ArrayList<>()).add(face);
        if (inPanel) panelFaces.add(face);
        CardView c = new CardView(this, face, w, h, null, null, 1.08f, false);
        c.ringRadius = 0.2f;   // matches the tile's glass corners
        c.setOnClickListener(v -> pressTile(face));
        if (id.startsWith("light.")) {
            c.setOnLongClickListener(v -> {
                startDim(face);
                return true;
            });
        }
        return c;
    }

    /** Focus moved to a card: layout state, parallax and the "top shelf" backdrop. */
    private void onCardFocused(RowView row, CardView card) {
        home.onRowFocused(row.rowIndex);
        int[] loc = new int[2];
        card.getLocationOnScreen(loc);
        float cx = loc[0] + card.getWidth() / 2f;
        Motion.spring(wall, View.TRANSLATION_X, (0.5f - cx / Ui.W) * Ui.px(24), Motion.LAYOUT);
        Motion.spring(wall, View.TRANSLATION_Y, home.expanded ? -row.rowIndex * Ui.px(10) : 0f, Motion.LAYOUT);
        if ((row == resumeRow || row == latestRow) && card.getTag() instanceof Jellyfin.Item) showShelf((Jellyfin.Item) card.getTag(), card);
        else hideShelf();
    }

    private void showShelf(Jellyfin.Item it, CardView card) {
        ui.removeCallbacks(shelfTask);
        Jellyfin source = jf;
        shelfTask = () -> images.execute(() -> {
            Bitmap bm = source.image(it, it.backdrops.isEmpty() ? it.images : it.backdrops, 1280);
            if (bm == null) return;
            runOnUiThread(() -> {
                if (!card.hasFocus()) return;
                ImageView next = shelfOnA ? shelfB : shelfA, prev = shelfOnA ? shelfA : shelfB;
                shelfOnA = !shelfOnA;
                next.setImageBitmap(bm);
                shelfGlass(bm);
                next.animate().alpha(1f).setDuration(600).setInterpolator(Motion.EASE).start();
                prev.animate().alpha(0f).setDuration(600).setInterpolator(Motion.EASE).start();
            });
        });
        ui.postDelayed(shelfTask, 250);   // only for cards you linger on, not every card you pass
    }

    private android.animation.ValueAnimator shelfFade;
    private float shelfAlpha;

    /** Fade what the glass samples between the wallpaper and the frosted film image (`bm` null = back to the wallpaper). */
    private void shelfGlass(Bitmap bm) {
        if (shelfFade != null) shelfFade.cancel();
        Backdrop b = Backdrop.SHARED;
        if (bm != null) b.setShelf(Blur.frost(bm, 320, 10), 0f);
        shelfFade = android.animation.ValueAnimator.ofFloat(bm != null ? 0f : shelfAlpha, bm != null ? 1f : 0f);
        shelfFade.setDuration(600);
        shelfFade.setInterpolator(Motion.EASE);
        shelfFade.addUpdateListener(a -> {
            shelfAlpha = (float) a.getAnimatedValue();
            b.setShelfAlpha(shelfAlpha * 0.85f);
        });
        shelfFade.start();
    }

    private void hideShelf() {
        ui.removeCallbacks(shelfTask);
        if (shelfAlpha > 0) shelfGlass(null);
        shelfA.animate().alpha(0f).setDuration(500).start();
        shelfB.animate().alpha(0f).setDuration(500).start();
    }

    // ---- wallpaper -----------------------------------------------------------
    private final Runnable wallTick = new Runnable() {
        @Override
        public void run() {
            if (!Wallpapers.currentKey(settings).equals(wallKey)) loadWallpaper(true);
            ui.postDelayed(this, 60_000);
        }
    };

    private void loadWallpaper(boolean animate) {
        wallKey = Wallpapers.currentKey(settings);
        images.execute(() -> {
            Bitmap bm = Wallpapers.current(this, settings);
            if (bm == null) return;
            Bitmap fr = Blur.frost(bm, 480, 14);
            runOnUiThread(() -> {
                Drawable old = wall.getDrawable();
                if (animate && old != null) {
                    TransitionDrawable t = new TransitionDrawable(new Drawable[]{old, new BitmapDrawable(getResources(), bm)});
                    t.setCrossFadeEnabled(true);
                    wall.setImageDrawable(t);
                    t.startTransition(1500);
                } else {
                    wall.setImageBitmap(bm);
                }
                frost.setImageBitmap(fr);
                Backdrop.SHARED.setFrost(fr);
            });
        });
    }

    // ---- Jellyfin ------------------------------------------------------------
    private void refreshMedia() {
        if (System.currentTimeMillis() - mediaFetchedAt < 30_000) return;
        mediaFetchedAt = System.currentTimeMillis();
        refreshWishes();
        if (jf == null) return;
        Jellyfin source = jf;
        io.execute(() -> {
            try {
                List<Jellyfin.Item> resume = source.continueWatching(), latest = source.recentlyAdded();
                if (!settings.demo()) {
                    Cache.saveItems(this, "resume", resume);
                    Cache.saveItems(this, "latest", latest);
                }
                runOnUiThread(() -> {
                    if (source != jf) return;
                    bindItems(resumeRow, resume);
                    bindItems(latestRow, latest);
                });
            } catch (Exception e) {
                Log.w("GlassHome", "jellyfin", e);   // keep showing the cached rows
            }
        });
    }

    /**
     * Resume in the Jellyfin app: open the item there (the app must be in front to start playback), then tell its
     * remote-control session to play from the saved position. Series and failures just stay on the item page.
     */
    private void playDirect(Jellyfin.Item it) {
        startActivity(Jellyfin.openIntent(it));
        if (!it.type.equals("Episode") && !it.type.equals("Movie")) return;
        Jellyfin source = jf;
        io.execute(() -> {
            try {
                for (int i = 0; i < 16; i++) {   // the app needs a few seconds to register its session
                    String session = source.tvSession(android.os.Build.MODEL);
                    if (session != null) {
                        source.playNow(session, it);
                        return;
                    }
                    Thread.sleep(500);
                }
                Log.i("GlassHome", "play directly: no Jellyfin session for " + android.os.Build.MODEL);
            } catch (Exception e) {
                Log.w("GlassHome", "play directly", e);
            }
        });
    }

    // ---- Jellyseerr: Önska -----------------------------------------------------
    private void refreshWishes() {
        if (seerr == null) return;
        Jellyseerr source = seerr;
        io.execute(() -> {
            try {
                List<Jellyseerr.Title> trending = source.trending();
                runOnUiThread(() -> {
                    if (source != seerr || wishRow == null) return;
                    List<CardView> cards = new ArrayList<>();
                    int mw = Ui.px(352), mh = mw * 9 / 16;
                    for (Jellyseerr.Title t : trending) if (t.status != Jellyseerr.AVAILABLE) cards.add(wishCard(t, mw, mh));
                    boolean hadFocus = wishRow.hasFocus();
                    wishRow.setItems(cards, mw, Ui.px(Theme.S4));
                    wishRow.setVisibility(cards.isEmpty() ? View.GONE : View.VISIBLE);
                    if (hadFocus && !cards.isEmpty()) wishRow.focusLast();
                });
            } catch (Exception e) {
                Log.w("GlassHome", "jellyseerr", e);
            }
        });
    }

    private CardView wishCard(Jellyseerr.Title t, int mw, int mh) {
        Poster poster = new Poster(0f);
        ImageView img = new ImageView(this);
        img.setImageDrawable(poster);
        CardView c = new CardView(this, img, mw, mh, t.name, t.label(), 1.1f, false);
        c.setOnClickListener(v -> {
            if (t.requestable()) confirmRequest(c, t);
            else toast(t.name, t.label());
        });
        Jellyseerr source = seerr;
        images.execute(() -> {
            Bitmap bm = source.image(t, 480);
            if (bm != null) runOnUiThread(() -> poster.setBitmap(bm));
        });
        return c;
    }

    private void confirmRequest(CardView origin, Jellyseerr.Title t) {
        List<View> sections = new ArrayList<>();
        sections.add(Ui.text(this, (t.type.equals("tv") ? Ui.tr("Serie", "Series") : Ui.tr("Film", "Film"))
                + (t.year.isEmpty() ? "" : " · " + t.year) + Ui.tr(" · laddas ner till Jellyfin", " · downloads to Jellyfin"),
                Theme.Style.BODY, Theme.SECONDARY));
        LinearLayout buttons = new LinearLayout(this);
        Pill go = action(Ui.tr("Begär", "Request"), v -> {
            Jellyseerr source = seerr;
            io.execute(() -> {
                try {
                    source.request(t);
                    t.status = Jellyseerr.PENDING;
                    runOnUiThread(() -> {
                        closePanel();
                        toast(t.name, Ui.tr("Begärd \u2713", "Requested \u2713"));
                        if (origin.subtitle != null) origin.subtitle.setText(t.label());
                    });
                } catch (Exception e) {
                    Log.w("GlassHome", "request", e);
                    runOnUiThread(() -> toast(t.name, Ui.tr("Kunde inte begära", "Couldn't request")));
                }
            });
        });
        go.setTint(Theme.BLUE);
        buttons.addView(go);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.px(Theme.S3);
        buttons.addView(action(Ui.tr("Avbryt", "Cancel"), v -> closePanel()), lp);
        sections.add(buttons);
        openPanel(Ui.tr("Begär ", "Request ") + t.name + "?", sections, origin, null);
    }

    // ---- search ------------------------------------------------------------------
    private void openSearch(View origin) {
        android.widget.EditText field = new android.widget.EditText(this);
        Ui.style(field, Theme.Style.TITLE, Theme.PRIMARY);
        field.setHint(Ui.tr("Sök filmer och serier", "Search films and series"));
        field.setHintTextColor(Glass.withAlpha(0xFFFFFF, Theme.TERTIARY));
        field.setSingleLine();
        field.setMinWidth(Ui.px(1100));
        field.setPadding(Ui.px(Theme.S4), Ui.px(18), Ui.px(Theme.S4), Ui.px(18));
        field.setBackground(new Glass(null, 0, true, 0.5f));
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setClipChildren(false);
        Runnable[] pending = new Runnable[1];
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence q, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence q, int a, int b, int c) {}

            @Override
            public void afterTextChanged(android.text.Editable q) {
                ui.removeCallbacks(pending[0]);
                String term = q.toString().trim();
                pending[0] = () -> search(term, results);
                if (term.length() >= 2) ui.postDelayed(pending[0], 300);
            }
        });
        List<View> sections = new ArrayList<>();
        sections.add(field);
        sections.add(results);
        openPanel(Ui.tr("Sök", "Search"), sections, origin, null);
    }

    private void search(String term, LinearLayout results) {
        Jellyfin source = jf;
        Jellyseerr wishes = seerr;
        io.execute(() -> {
            List<Jellyfin.Item> found = new ArrayList<>();
            List<Jellyseerr.Title> requestable = new ArrayList<>();
            try {
                if (source != null) found = source.search(term);
                if (wishes != null) for (Jellyseerr.Title t : wishes.search(term)) if (t.status != Jellyseerr.AVAILABLE) requestable.add(t);
            } catch (Exception e) {
                Log.w("GlassHome", "search", e);
            }
            List<Jellyfin.Item> have = found;
            runOnUiThread(() -> {
                if (!results.isAttachedToWindow()) return;
                results.removeAllViews();
                int mw = Ui.px(352), mh = mw * 9 / 16;
                if (have.isEmpty() && requestable.isEmpty()) {
                    results.addView(Ui.text(this, Ui.tr("Inga träffar", "No results"), Theme.Style.BODY, Theme.SECONDARY));
                    return;
                }
                if (!have.isEmpty()) {
                    List<View> cards = new ArrayList<>();
                    for (Jellyfin.Item it : have) cards.add(mediaCard(it, mw, mh));
                    results.addView(grid(cards, 4));
                }
                if (!requestable.isEmpty()) {
                    TextView h = Ui.text(this, Ui.tr("Kan begäras", "Can be requested"), Theme.Style.TITLE, Theme.SECONDARY);
                    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
                    hp.topMargin = Ui.px(Theme.S6);
                    hp.leftMargin = Ui.px(12);
                    results.addView(h, hp);
                    List<View> cards = new ArrayList<>();
                    for (Jellyseerr.Title t : requestable) cards.add(wishCard(t, mw, mh));
                    results.addView(grid(cards, 4));
                }
            });
        });
    }

    // ---- quick settings ------------------------------------------------------------
    private Pill sleepPill, topPill;   // topPill: where Up from the app row goes

    private void showSleep() {
        long at = settings.sleepAt();
        boolean on = at > System.currentTimeMillis();
        sleepPill.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) sleepPill.setText(Ui.tr("Stängs av ", "Turns off at ")
                + android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(at)) + Ui.tr(" · avbryt", " · cancel"));
    }

    private void openQuick(View origin) {
        List<View> items = new ArrayList<>();
        if (ha != null && ha.tv != null) {
            for (int min : new int[]{30, 60, 90}) {
                items.add(action(Ui.tr("Stäng av om " + min + " min", "Turn off in " + min + " min"), v -> {
                    SleepReceiver.set(this, min);
                    closePanel();
                    showSleep();
                }));
            }
            if (settings.sleepAt() > System.currentTimeMillis()) items.add(action(Ui.tr("Ingen sov-timer", "No sleep timer"), v -> {
                SleepReceiver.set(this, 0);
                closePanel();
                showSleep();
            }));
        }
        String[][] shortcuts = {
                {android.provider.Settings.ACTION_BLUETOOTH_SETTINGS, Ui.tr("Bluetooth och hörlurar", "Bluetooth & headphones")},
                {android.provider.Settings.ACTION_DISPLAY_SETTINGS, Ui.tr("Bild", "Picture")},
                {android.provider.Settings.ACTION_SOUND_SETTINGS, Ui.tr("Ljud", "Sound")},
                {android.provider.Settings.ACTION_WIFI_SETTINGS, Ui.tr("Nätverk", "Network")},
                {android.provider.Settings.ACTION_SETTINGS, Ui.tr("Alla inställningar", "All settings")},
        };
        for (String[] sc : shortcuts) {
            Intent i = new Intent(sc[0]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(getPackageManager()) == null) continue;   // not every TV has every settings page
            items.add(action(sc[1], v -> {
                closePanel();
                startActivity(i);
            }));
        }
        items.add(action(Ui.tr("Startskärmen\u2026", "Home screen\u2026"), this::openSettings));
        List<View> sections = new ArrayList<>();
        sections.add(grid(items, 2));
        openPanel(Ui.tr("Snabbinställningar", "Quick settings"), sections, origin, null);
    }

    // ---- Home Assistant --------------------------------------------------------
    /** One REST pass so tiles and chips have values even before (or without) the WebSocket. */
    private void seedStates() {
        HomeAssistant source = ha;
        Set<String> ids = new HashSet<>(haInterest);
        io.execute(() -> {
            for (String id : ids) {
                try {
                    JSONObject st = source.state(id);
                    if (source == ha) onState(id, st);
                } catch (Exception ignored) {
                }
            }
        });
    }

    @Override
    public void onState(String id, JSONObject st) {
        if (!haInterest.contains(id)) return;   // HA sends every entity; we only care about ours
        runOnUiThread(() -> {
            haStates.put(id, st);
            List<Tiles.Face> fs = faces.get(id);
            if (fs != null) for (Tiles.Face f : fs) f.bind(ha, st);
            TextView chip = chipViews.get(id);
            if (chip != null) {
                String t = Tiles.chip(ha, id, st);
                chip.setText(t);
                chip.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
            }
            if (ha != null && id.equals(ha.media)) showPlaying(st);
        });
    }

    @Override
    public void onAreas(Map<String, String> entityArea) {
        runOnUiThread(() -> haAreas = entityArea);
    }

    @Override
    public void onNotify(JSONObject data) {
        runOnUiThread(() -> toast(data.optString("title", Ui.tr("Hemmet", "Home")), data.optString("message")));
    }

    @Override
    public void onConnection(boolean up) {
        if (up) return;
        if (!settings.demo()) ui.post(this::seedStates);   // keep values fresh-ish while reconnecting
    }

    private void pressTile(Tiles.Face face) {
        String[] svc = Tiles.serviceFor(face.id, haStates.get(face.id));
        if (svc == null || ha == null) return;
        if (face.isAction()) face.flash();
        HomeAssistant source = ha;
        io.execute(() -> {
            try {
                source.call(svc[0], svc[1], face.id);
            } catch (Exception e) {
                Log.w("GlassHome", "ha call", e);
            }
        });
    }

    private void startDim(Tiles.Face face) {
        dimming = face;
        toast(face.name.getText().toString(), Ui.tr("◀ ▶ ändrar ljusstyrkan · OK när du är klar", "◀ ▶ changes brightness · OK when done"));
    }

    private boolean dimKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() != KeyEvent.ACTION_DOWN) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_BACK) dimming = null;
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            String id = dimming.id;
            int step = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 10 : -10;
            HomeAssistant source = ha;
            io.execute(() -> {
                try {
                    source.call("light", "turn_on", new JSONObject().put("entity_id", id).put("brightness_step_pct", step));
                    if (live == null && !settings.demo()) onState(id, source.state(id));
                } catch (Exception ex) {
                    Log.w("GlassHome", "dim", ex);
                }
            });
        }
        return true;
    }

    private void showPlaying(JSONObject st) {
        String state = st.optString("state");
        JSONObject a = st.optJSONObject("attributes");
        boolean show = (state.equals("playing") || state.equals("paused")) && a != null && a.has("media_title");
        playingPill.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        String artist = a.optString("media_artist");
        playingPill.setText((state.equals("playing") ? "▶  " : "❚❚  ") + a.optString("media_title") + (artist.isEmpty() ? "" : " · " + artist));
    }

    private void mediaPlayPause() {
        if (ha == null || ha.media == null) return;
        HomeAssistant source = ha;
        io.execute(() -> {
            try {
                source.call("media_player", "media_play_pause", source.media);
            } catch (Exception e) {
                Log.w("GlassHome", "media", e);
            }
        });
    }

    // ---- toasts --------------------------------------------------------------
    void toast(String title, String message) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(Ui.px(30), Ui.px(22), Ui.px(30), Ui.px(24));
        t.setBackground(new Glass(null, 0xCC1C1C1E, true, 0.3f));
        t.addView(Ui.text(this, title, 28, true, 1f));
        if (message != null && !message.isEmpty()) t.addView(Ui.text(this, message, 24, false, 0.85f));
        home.toasts.removeAllViews();
        home.toasts.addView(t, new FrameLayout.LayoutParams(-1, -2));
        t.setTranslationX(Ui.px(640));
        Motion.spring(t, View.TRANSLATION_X, 0f, Motion.SNAPPY);
        t.postDelayed(() -> Motion.spring(t, View.TRANSLATION_X, Ui.px(640), Motion.SMOOTH)
                .onRest(() -> home.toasts.removeView(t)), 8000);
    }

    // ---- panels ----------------------------------------------------------------
    private GridLayout grid(List<? extends View> items, int cols) {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(cols);
        g.setClipChildren(false);
        for (View v : items) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(Ui.px(12), Ui.px(12), Ui.px(12), Ui.px(12));
            g.addView(v, lp);
        }
        return g;
    }

    /** Show `sections` in a glass panel that zooms out of `origin` (or the centre). */
    private void openPanel(String title, List<View> sections, View origin, Runnable onClose) {
        if (overlay.getVisibility() == View.VISIBLE) closePanelNow();
        returnFocus = origin != null ? origin : getCurrentFocus();
        onPanelClose = onClose;
        overlay.removeAllViews();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        col.setPadding(Ui.px(Theme.SAFE_X), Ui.px(Theme.SAFE_Y + Theme.S4), Ui.px(Theme.SAFE_X), Ui.px(Theme.S12));
        // one column, centred as a whole, with title / headers / grids sharing its left edge
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        body.addView(Ui.text(this, title, Theme.Style.LARGE_TITLE, Theme.PRIMARY), new LinearLayout.LayoutParams(-2, -2));
        col.addView(body, new LinearLayout.LayoutParams(-2, -2));
        List<View> staggered = new ArrayList<>();
        for (View s : sections) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = Ui.px(s instanceof GridLayout ? Theme.S2 : Theme.S6);
            if (s instanceof TextView && !(s instanceof Pill)) lp.leftMargin = Ui.px(12);   // headers sit on the tiles' edge
            body.addView(s, lp);
            if (s instanceof GridLayout) for (int i = 0; i < ((GridLayout) s).getChildCount(); i++) staggered.add(((GridLayout) s).getChildAt(i));
            else staggered.add(s);
        }
        ScrollView sv = new ScrollView(this);
        sv.setClipChildren(false);
        sv.setVerticalScrollBarEnabled(false);
        sv.setSmoothScrollingEnabled(true);
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        overlay.addView(sv, Ui.match());

        // zoom out of the origin card; the frosted wallpaper fades in (pre-blurred: cheap on the TV GPU)
        int[] loc = new int[2];
        if (origin != null) origin.getLocationOnScreen(loc);
        sv.setPivotX(origin != null ? loc[0] + origin.getWidth() / 2f : Ui.W / 2f);
        sv.setPivotY(origin != null ? loc[1] + origin.getHeight() / 2f : Ui.H / 2f);
        sv.setScaleX(0.94f);
        sv.setScaleY(0.94f);
        Motion.spring(sv, View.SCALE_X, 1f, Motion.PANEL);
        Motion.spring(sv, View.SCALE_Y, 1f, Motion.PANEL);
        overlay.setAlpha(0f);
        overlay.setVisibility(View.VISIBLE);
        overlay.animate().alpha(1f).setDuration(300).setInterpolator(Motion.EASE).start();
        frost.animate().alpha(1f).setDuration(380).setInterpolator(Motion.EASE).start();
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        home.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).setDuration(320).setInterpolator(Motion.EASE).withLayer().start();
        Motion.stagger(staggered, 18, Ui.px(28));
        sv.post(() -> {
            View first = col.focusSearch(View.FOCUS_DOWN);
            for (View s : staggered) {
                if (s.isFocusable()) {
                    first = s;
                    break;
                }
                if (s instanceof ViewGroup) {
                    ArrayList<View> fs = new ArrayList<>();
                    s.addFocusables(fs, View.FOCUS_DOWN);
                    if (!fs.isEmpty()) {
                        first = fs.get(0);
                        break;
                    }
                }
            }
            if (first != null) first.requestFocus();
        });
    }

    private void closePanel() {
        if (overlay.getVisibility() != View.VISIBLE) return;
        runPanelClose();
        View content = overlay.getChildAt(0);
        if (content != null) content.animate().scaleX(0.92f).scaleY(0.92f).setDuration(240).setInterpolator(Motion.EASE).withLayer().start();
        overlay.animate().alpha(0f).setDuration(240).setInterpolator(Motion.EASE).withEndAction(() -> {
            overlay.setVisibility(View.GONE);
            overlay.removeAllViews();
        }).start();
        frost.animate().alpha(0f).setDuration(300).start();
        home.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        home.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(Motion.EASE).withLayer().start();
        if (returnFocus != null && returnFocus.isAttachedToWindow()) returnFocus.requestFocus();
        else favRow.focusLast();
    }

    /** Swap panels without animating home in and out. */
    private void closePanelNow() {
        runPanelClose();
        overlay.removeAllViews();
    }

    private void runPanelClose() {
        for (Tiles.Face f : panelFaces) {
            List<Tiles.Face> fs = faces.get(f.id);
            if (fs != null) fs.remove(f);
        }
        panelFaces.clear();
        if (onPanelClose != null) {
            Runnable r = onPanelClose;
            onPanelClose = null;
            r.run();
        }
    }

    private void openMore(View origin) {
        Set<String> skip = new HashSet<>(settings.favourites());
        Set<String> hidden = settings.hidden();
        skip.addAll(hidden);
        int cw = Ui.px(288), ch = cw * 10 / 16;
        List<View> cards = new ArrayList<>();
        for (Apps.App a : Apps.scan(this)) {
            if (skip.contains(a.pkg)) continue;
            CardView c = appCard(Apps.icon(this, a.pkg), a.label, cw, ch, v -> Apps.launch(this, a.pkg));
            c.setOnLongClickListener(v -> {
                moreMenu(v, a.pkg);
                return true;
            });
            cards.add(c);
        }
        cards.add(appCard(Apps.iconKey(this, "settings"), Ui.tr("Startskärmen", "Home screen"), cw, ch, this::openSettings));
        List<View> sections = new ArrayList<>();
        sections.add(grid(cards, 5));
        openPanel(Ui.tr("Fler appar", "More apps"), sections, origin, null);
    }

    private void openHomePanel(View origin) {
        if (ha == null) return;
        Map<String, List<View>> byArea = new LinkedHashMap<>();
        String other = Ui.tr("Övrigt", "Other");
        for (String id : ha.entities) {
            String area = haAreas.getOrDefault(id, id.startsWith("scene.") ? Ui.tr("Scener", "Scenes") : other);
            byArea.computeIfAbsent(area, k -> new ArrayList<>()).add(tileCard(id, Ui.px(380), Ui.px(200), true));
        }
        List<View> sections = new ArrayList<>();
        for (Map.Entry<String, List<View>> e : byArea.entrySet()) {
            if (byArea.size() > 1) sections.add(Ui.text(this, e.getKey(), Theme.Style.TITLE, Theme.SECONDARY));
            sections.add(grid(e.getValue(), 4));
        }
        openPanel(Ui.tr("Hemmet", "Home"), sections, origin, null);
    }

    void openSettings(View origin) {
        List<View> items = new ArrayList<>();
        items.add(action(Ui.tr("Kör installationsguiden", "Run the setup guide"), v -> setup(null)));
        items.add(action(Ui.tr("Välj favoriter", "Choose favourites"), v -> setup("favourites")));
        items.add(action(Ui.tr("Fjärrkontrollens knappar", "Remote buttons"), v -> setup("buttons")));
        items.add(action(Ui.tr("Bakgrund", "Wallpaper"), v -> setup("wallpaper")));
        if (jf != null && !settings.demo()) items.add(action(Ui.tr("Byt profil (Jellyfin)", "Switch profile (Jellyfin)"), v -> openProfiles(v)));
        int hidden = settings.hidden().size();
        if (hidden > 0) items.add(action(Ui.tr("Visa dolda appar (" + hidden + ")", "Show hidden apps (" + hidden + ")"), v -> {
            settings.setHidden(new HashSet<>());
            closePanel();
        }));
        items.add(action(Ui.tr("Ställ in från mobilen", "Set up from your phone"), v -> openPhoneSetup(v)));
        items.add(action(settings.demo() ? Ui.tr("Demoläge: på", "Demo mode: on") : Ui.tr("Demoläge: av", "Demo mode: off"), v -> {
            settings.setDemo(!settings.demo());
            closePanel();
            reload();
        }));
        List<View> sections = new ArrayList<>();
        sections.add(grid(items, 2));
        sections.add(Ui.text(this, "Glass Home " + version(), 22, false, 0.6f));
        openPanel(Ui.tr("Startskärmen", "Home screen"), sections, origin, null);
    }

    private Pill action(String text, View.OnClickListener click) {
        Pill p = new Pill(this, text, 0, 30);
        p.setMinWidth(Ui.px(560));
        p.setOnClickListener(click);
        return p;
    }

    private void setup(String page) {
        closePanel();
        Intent i = new Intent(this, SetupActivity.class);
        if (page != null) i.putExtra(SetupActivity.EXTRA_PAGE, page);
        startActivity(i);
    }

    private void openProfiles(View origin) {
        Jellyfin source = jf;
        io.execute(() -> {
            try {
                List<Jellyfin.User> users = source.users();
                runOnUiThread(() -> {
                    List<View> items = new ArrayList<>();
                    for (Jellyfin.User u : users) {
                        boolean current = u.id.equals(source.userId);
                        Pill p = action((current ? "✓  " : "") + u.name, v -> {
                            settings.setJellyfinUser(u.id);
                            closePanel();
                            reload();
                        });
                        items.add(p);
                    }
                    List<View> sections = new ArrayList<>();
                    sections.add(grid(items, 2));
                    openPanel(Ui.tr("Vem tittar?", "Who's watching?"), sections, origin, null);
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("Jellyfin", Ui.tr("Når inte servern", "Can't reach the server")));
            }
        });
    }

    private void openPhoneSetup(View origin) {
        if (phone != null) phone.stop();
        phone = new PhoneSetup(this, () -> runOnUiThread(() -> {
            toast(Ui.tr("Sparat", "Saved"), Ui.tr("Inställningarna från mobilen är sparade", "Settings from your phone were saved"));
            closePanel();
            reload();
        }));
        String url = phone.start();
        List<View> sections = new ArrayList<>();
        if (url == null) {
            sections.add(Ui.text(this, Ui.tr("Kunde inte starta (inget nätverk?)", "Couldn't start (no network?)"), 30, false, 0.9f));
        } else {
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(Qr.bitmap(url, Ui.px(420)));
            qr.setBackground(new Glass(null, 0xFFFFFFFF, true, 0.08f));
            qr.setPadding(Ui.px(24), Ui.px(24), Ui.px(24), Ui.px(24));
            sections.add(qr);
            sections.add(Ui.text(this, Ui.tr("Skanna med mobilens kamera, eller öppna:", "Scan with your phone's camera, or open:"), 28, false, 0.85f));
            sections.add(Ui.text(this, url, 34, true, 1f));
            Pill done = action(Ui.tr("Klar", "Done"), v -> closePanel());
            sections.add(done);
        }
        openPanel(Ui.tr("Ställ in från mobilen", "Set up from your phone"), sections, origin, () -> {
            if (phone != null) phone.stop();
            phone = null;
        });
    }

    // ---- long-press menus and move mode --------------------------------------
    private void favouriteMenu(CardView card, String pkg) {
        List<View> items = new ArrayList<>();
        items.add(action(Ui.tr("Flytta", "Move"), v -> startMove(card)));
        items.add(action(Ui.tr("Ta bort från favoriter", "Remove from favourites"), v -> {
            List<String> f = new ArrayList<>(settings.favourites());
            f.remove(pkg);
            settings.setFavourites(f);
            closePanel();
            rebuild();
        }));
        items.add(action(Ui.tr("Dölj appen", "Hide app"), v -> hide(pkg)));
        items.add(action(Ui.tr("Startskärmen…", "Home screen…"), v -> openSettings(v)));
        List<View> sections = new ArrayList<>();
        sections.add(grid(items, 1));
        openPanel(Apps.label(this, pkg).toString(), sections, card, null);
    }

    private void moreMenu(View card, String pkg) {
        List<View> items = new ArrayList<>();
        items.add(action(Ui.tr("Lägg till i favoriter", "Add to favourites"), v -> {
            List<String> f = new ArrayList<>(settings.favourites());
            f.add(pkg);
            settings.setFavourites(f);
            closePanel();
            rebuild();
        }));
        items.add(action(Ui.tr("Dölj appen", "Hide app"), v -> hide(pkg)));
        List<View> sections = new ArrayList<>();
        sections.add(grid(items, 1));
        openPanel(Apps.label(this, pkg).toString(), sections, card, null);
    }

    private void mediaMenu(CardView card) {
        List<View> items = new ArrayList<>();
        Jellyfin.Item it = (Jellyfin.Item) card.getTag();
        items.add(action(Ui.tr("Öppna i Jellyfin", "Open in Jellyfin"), v -> {
            closePanel();
            if (!settings.demo()) startActivity(Jellyfin.openIntent(it));
        }));
        if (!settings.demo()) items.add(action(Ui.tr("Byt profil", "Switch profile"), v -> openProfiles(v)));
        List<View> sections = new ArrayList<>();
        sections.add(grid(items, 1));
        openPanel(card.title.getText().toString(), sections, card, null);
    }

    private void hide(String pkg) {
        Set<String> h = settings.hidden();
        h.add(pkg);
        settings.setHidden(h);
        List<String> f = new ArrayList<>(settings.favourites());
        if (f.remove(pkg)) settings.setFavourites(f);
        closePanel();
        rebuild();
    }

    /** Rebuild the home screen after a local change, without reconnecting to HA/Jellyfin. */
    private void rebuild() {
        builtVersion = settings.version();
        buildHome();
        for (Map.Entry<String, JSONObject> e : new HashMap<>(haStates).entrySet()) onState(e.getKey(), e.getValue());
        bindItems(resumeRow, Cache.loadItems(this, "resume"));
        bindItems(latestRow, Cache.loadItems(this, "latest"));
        mediaFetchedAt = 0;
        refreshMedia();
    }

    private void startMove(CardView card) {
        closePanel();
        moving = card;
        card.requestFocus();
        wiggle = ValueAnimator.ofFloat(-1.6f, 1.6f);
        wiggle.setDuration(130);
        wiggle.setRepeatMode(ValueAnimator.REVERSE);
        wiggle.setRepeatCount(ValueAnimator.INFINITE);
        wiggle.addUpdateListener(a -> card.art.setRotation((float) a.getAnimatedValue()));
        wiggle.start();
        toast(Ui.tr("Flytta", "Move"), Ui.tr("◀ ▶ flyttar · OK när du är klar", "◀ ▶ moves · OK when done"));
    }

    private boolean moveKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            int favCount = 0;
            for (int i = 0; i < favRow.count(); i++) if (favRow.card(i).getTag() instanceof String) favCount++;
            int from = moving.index, to = from + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            if (to >= 0 && to < favCount) {
                favRow.moveItem(from, to);
                moving.requestFocus();
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_BACK)) {
            endMove();
        }
        return true;
    }

    private void endMove() {
        if (wiggle != null) wiggle.cancel();
        moving.art.animate().rotation(0f).setDuration(150).start();
        List<String> order = new ArrayList<>();
        for (int i = 0; i < favRow.count(); i++) if (favRow.card(i).getTag() instanceof String) order.add((String) favRow.card(i).getTag());
        settings.setFavourites(order);
        builtVersion = settings.version();
        moving = null;
    }

    // ---- new apps --------------------------------------------------------------
    private void checkNewApps() {
        if (!settings.setupDone() || settings.demo()) return;
        Set<String> now = new HashSet<>();
        for (Apps.App a : Apps.scan(this)) now.add(a.pkg);
        Set<String> known = settings.knownApps();
        if (known.isEmpty()) {
            settings.setKnownApps(now);
            return;
        }
        for (String p : now) {
            if (!known.contains(p) && !settings.favourites().contains(p)) {
                newApp = p;
                appPill.setText(Ui.tr("Ny app: ", "New app: ") + Apps.label(this, p) + Ui.tr(" · lägg till som favorit?", " · add to favourites?"));
                appPill.setVisibility(View.VISIBLE);
                return;
            }
        }
        settings.setKnownApps(now);
    }

    private void addNewApp() {
        if (newApp == null) return;
        List<String> f = new ArrayList<>(settings.favourites());
        f.add(newApp);
        settings.setFavourites(f);
        dismissNewApp();
        rebuild();
    }

    private void dismissNewApp() {
        Set<String> known = settings.knownApps();
        if (newApp != null) known.add(newApp);
        settings.setKnownApps(known);
        newApp = null;
        appPill.setVisibility(View.GONE);
        checkNewApps();
    }

    // ---- self-update -----------------------------------------------------------
    private void checkUpdate() {
        if (updater == null || System.currentTimeMillis() - updateCheckedAt < 6 * 3600_000L) return;
        updateCheckedAt = System.currentTimeMillis();
        io.execute(() -> {
            try {
                Updater.Release rel = updater.latest();
                if (rel != null && rel.versionCode > versionCode()) runOnUiThread(() -> showUpdate(rel));
            } catch (Exception e) {
                Log.w("GlassHome", "update check", e);
            }
        });
    }

    private void showUpdate(Updater.Release rel) {
        pendingUpdate = rel;
        updatePill.setText(Ui.tr("Ny version " + rel.name + " · tryck här", "New version " + rel.name + " · press here"));
        updatePill.setVisibility(View.VISIBLE);
    }

    private void openUpdate(View origin) {
        Updater.Release rel = pendingUpdate;
        if (rel == null) return;
        List<View> sections = new ArrayList<>();
        TextView notes = Ui.text(this, rel.notes.isEmpty() ? Ui.tr("Förbättringar och rättningar.", "Improvements and fixes.") : rel.notes, 26, false, 0.9f);
        notes.setMaxWidth(Ui.px(1200));
        sections.add(notes);
        Pill install = action(Ui.tr("Installera", "Install"), v -> {
            ((Pill) v).setText(Ui.tr("Laddar ner…", "Downloading…"));
            io.execute(() -> {
                try {
                    Updater.install(this, updater.download(rel, getExternalCacheDir()));
                } catch (Exception e) {
                    Log.w("GlassHome", "update install", e);
                    runOnUiThread(() -> ((Pill) v).setText(Ui.tr("Misslyckades · försök igen", "Failed · try again")));
                }
            });
        });
        install.setTint(0xFF0A84FF);
        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(install);
        Pill later = action(Ui.tr("Senare", "Later"), v -> closePanel());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.px(24);
        buttons.addView(later, lp);
        sections.add(buttons);
        openPanel(Ui.tr("Ny version ", "New version ") + rel.name, sections, origin, null);
    }

    private long versionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
