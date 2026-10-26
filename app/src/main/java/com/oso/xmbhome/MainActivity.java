package com.oso.xmbhome;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * An XMB (PS3 firmware 0.83) home screen: a horizontal row of categories, the selected one's items
 * in a vertical column. Layout is in dp on the TV's 960x540 dp screen, matching the mockups.
 */
public class MainActivity extends Activity implements NowPlaying.Listener {
    // Cross-bar geometry (dp).
    private static final float CX = 282, CY = 150, SPACING = 118, FOCUS_Y = 262, BELOW_Y = 346, STEP = 42;
    private static final float ZOOM = 1.06f;               // faint zoom for wake, open and return
    private static final long WAKE_ITEM_DELAY = 400;       // item column after the categories, wake only
    private static final long WAKE_WINDOW_MS = 8000;       // a start this soon after screen-on is a wake
    // Screensaver: after two minutes without a key, the menu dims and drifts back a little.
    private static final long IDLE_AFTER_MS = 120_000, IDLE_ANIM_MS = 1000, IDLE_WAKE_MS = 350, PANEL_DIM_MS = 500;
    private static final float IDLE_ALPHA = .3f, IDLE_SCALE = .95f, CLOCK_ALPHA = .9f;

    private static final PathInterpolator MOVE = new PathInterpolator(.2f, .8f, .2f, 1f);
    private static final PathInterpolator FADE = new PathInterpolator(.22f, .61f, .36f, 1f);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("d/M HH:mm");

    private float dp;
    private WaveView wave;
    private FrameLayout fg, menu, catLayer, itemLayer;
    private TextView clock, ticker;
    private LinearLayout nowPlayingBox;
    private NowPlaying nowPlaying;

    private final List<Catalog.Category> cats = new ArrayList<>();
    private final List<View> catViews = new ArrayList<>();
    private final List<List<Row>> rows = new ArrayList<>();
    private int cat = -1, shownCat = -1;
    private boolean catalogDirty = true, started, launchedApp, animating, opening, idle, overlayOpen, firstStart = true;
    private final android.os.Handler idleHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable goIdle = () -> dimMenu(IDLE_ANIM_MS);
    private long screenOnAt;
    private String playingPkg, playingText;
    private TextView glowing;

    private static final class Row {
        FrameLayout view;
        ImageView icon;
        TextView title, sub;
        Catalog.Item item;
        boolean focused;
    }

    // ---------------------------------------------------------------- lifecycle

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            screenOnAt = SystemClock.uptimeMillis();
            if (started) playWake();   // the screen came on with Home already in front
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            catalogDirty = true;
            if (started) rebuild();
        }
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
            wave.refreshSky();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        buildViews();
        nowPlaying = new NowPlaying(this, this);
        applyPreview(getIntent());
        registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON), RECEIVER_NOT_EXPORTED);
        IntentFilter pk = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        pk.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pk.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pk.addDataScheme("package");
        registerReceiver(packageReceiver, pk, RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        applyPreview(intent);
    }

    /**
     * Debug builds: show a fixed date and time, for screenshots of other seasons and hours.
     * adb shell am start -n com.oso.xmbhome/.MainActivity --es preview_time 2026-06-27T15:00
     * (an empty value goes back to the real clock).
     */
    private void applyPreview(Intent intent) {
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        String t = intent == null ? null : intent.getStringExtra("preview_time");
        if (t == null) return;
        try {
            Sky.preview = t.isEmpty() ? null : LocalDateTime.parse(t);
        } catch (java.time.format.DateTimeParseException e) {
            return;
        }
        if (clock != null) updateClock();
        if (wave != null) wave.refreshSky();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (catalogDirty) rebuild();
        IntentFilter t = new IntentFilter(Intent.ACTION_TIME_TICK);
        t.addAction(Intent.ACTION_TIME_CHANGED);
        t.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, t, RECEIVER_NOT_EXPORTED);
        updateClock();
        wave.refreshSky();
        wave.start();
        nowPlaying.start();
        boolean woke = firstStart || SystemClock.uptimeMillis() - screenOnAt < WAKE_WINDOW_MS;
        if (woke) {
            playWake();
            launchedApp = false;
        }
        firstStart = false;
        screenOnAt = 0;
        startGlow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Here rather than in onStart: TV Settings opens as a translucent panel, so Home is only
        // paused, never stopped, and would stay faded out after the opening animation.
        if (launchedApp) {
            launchedApp = false;
            playReturn();
        }
        wakeFromIdle(overlayOpen);   // back from a Settings panel: undim like waking the screensaver
        overlayOpen = false;
        armIdle();
    }

    @Override
    protected void onPause() {
        idleHandler.removeCallbacks(goIdle);
        super.onPause();
    }

    @Override
    protected void onStop() {
        started = false;
        unregisterReceiver(timeReceiver);
        wave.stop();
        nowPlaying.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(screenReceiver);
        unregisterReceiver(packageReceiver);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- views

    private int px(float v) { return Math.round(v * dp); }

    private void buildViews() {
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        wave = new WaveView(this);
        root.addView(wave, new FrameLayout.LayoutParams(-1, -1));

        fg = new Flat(this);
        root.addView(fg, new FrameLayout.LayoutParams(-1, -1));
        fg.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            v.setPivotX((r - l) / 2f);
            v.setPivotY((b - t) / 2f);
        });
        // The categories and items: the part the screensaver dims and zooms. The top bar stays
        // outside it, so the clock only dims and Now Playing is left alone.
        menu = new Flat(this);
        fg.addView(menu, new FrameLayout.LayoutParams(-1, -1));
        menu.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            v.setPivotX((r - l) / 2f);
            v.setPivotY((b - t) / 2f);
        });
        catLayer = new Flat(this);
        menu.addView(catLayer, new FrameLayout.LayoutParams(-1, -1));
        itemLayer = new Flat(this);
        menu.addView(itemLayer, new FrameLayout.LayoutParams(-1, -1));

        // Top right: Now Playing ticker, then the clock.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        tlp.topMargin = px(30);
        tlp.rightMargin = px(48);
        fg.addView(top, tlp);

        nowPlayingBox = new LinearLayout(this);
        nowPlayingBox.setGravity(Gravity.CENTER_VERTICAL);
        nowPlayingBox.setAlpha(0f);
        ImageView note = new ImageView(this);
        note.setImageResource(R.drawable.xmb_play);
        nowPlayingBox.addView(note, new LinearLayout.LayoutParams(px(22), px(22)));
        ticker = text(15, false);
        ticker.setLetterSpacing(.03f);
        marquee(ticker);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-2, -2);
        klp.leftMargin = px(8);
        nowPlayingBox.addView(ticker, klp);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.rightMargin = px(18);
        top.addView(nowPlayingBox, nlp);

        clock = text(15, false);
        clock.setLetterSpacing(.03f);
        clock.setAlpha(CLOCK_ALPHA);
        top.addView(clock);

        setContentView(root);
        getWindow().setBackgroundDrawable(null);   // the waves cover every pixel: skip a full-screen fill
        wave.setOnTick(this::breathe);
    }

    private TextView text(float sp, boolean medium) {
        TextView t = new TextView(this);
        t.setTypeface(medium ? Fonts.medium(this) : Fonts.regular(this));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(0xFFFFFFFF);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Android's own marquee: holds, scrolls, repeats, with faded edges; only runs when selected. */
    private void marquee(TextView t) {
        t.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        t.setMarqueeRepeatLimit(-1);
        t.setHorizontalFadingEdgeEnabled(true);
        t.setFadingEdgeLength(px(14));
        t.setSelected(true);
    }

    private void rebuild() {
        catalogDirty = false;
        String prevName = cat >= 0 && cat < cats.size() ? cats.get(cat).name : null;
        List<Integer> prevSel = new ArrayList<>();
        for (Catalog.Category c : cats) prevSel.add(c.selected);
        cats.clear();
        for (Catalog.Category c : Catalog.build(this)) if (!c.items.isEmpty()) cats.add(c);
        for (int i = 0; i < cats.size() && i < prevSel.size(); i++) {
            cats.get(i).selected = Math.min(prevSel.get(i), cats.get(i).items.size() - 1);
        }
        cat = -1;
        for (int i = 0; i < cats.size(); i++) {
            String n = cats.get(i).name;
            if (n.equals(prevName) || (prevName == null && n.equals("Video"))) cat = i;
        }
        if (cat < 0) cat = Math.min(Catalog.VIDEO, cats.size() - 1);

        catLayer.removeAllViews();
        itemLayer.removeAllViews();
        catViews.clear();
        rows.clear();
        for (Catalog.Category c : cats) {
            catViews.add(categoryView(c));
            List<Row> list = new ArrayList<>();
            for (Catalog.Item it : c.items) list.add(rowView(it));
            rows.add(list);
        }
        shownCat = -1;
        layout(false);
    }

    private View categoryView(Catalog.Category c) {
        FrameLayout v = new Flat(this);
        ImageView icon = new ImageView(this);
        icon.setImageResource(c.icon);
        icon.setTag("icon");
        v.addView(icon, new FrameLayout.LayoutParams(px(96), px(96), Gravity.CENTER_HORIZONTAL));
        TextView label = text(17, false);
        label.setText(c.name);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setTag("label");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2);
        lp.topMargin = px(86);
        v.addView(label, lp);
        catLayer.addView(v, new FrameLayout.LayoutParams(px(120), px(130)));
        return v;
    }

    private Row rowView(Catalog.Item it) {
        Row r = new Row();
        r.item = it;
        r.view = new Flat(this);
        r.icon = new ImageView(this);
        FrameLayout.LayoutParams ilp;
        if (it.icon != 0) {
            r.icon.setImageResource(it.icon);
            ilp = new FrameLayout.LayoutParams(px(100), px(100), Gravity.CENTER_VERTICAL);
            ilp.leftMargin = px(-2);
        } else {
            r.icon.setImageDrawable(it.banner);
            r.icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int w = it.isBanner ? 96 : 68, h = it.isBanner ? 54 : 68;
            ilp = new FrameLayout.LayoutParams(px(w), px(h), Gravity.CENTER_VERTICAL);
            ilp.leftMargin = px((96 - w) / 2f);
            float radius = (it.isBanner ? 8 : 15) * dp;
            r.icon.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            r.icon.setClipToOutline(true);
        }
        r.view.addView(r.icon, ilp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        r.title = text(16, false);
        r.title.setText(it.title);
        // Room for the breathing glow without clipping.
        r.title.setPadding(px(6), px(6), px(6), px(6));
        texts.addView(r.title);
        r.sub = text(13, false);
        r.sub.setAlpha(.7f);
        r.sub.setMaxWidth(px(300));
        r.sub.setPadding(px(6), 0, px(6), 0);
        marquee(r.sub);
        r.sub.setVisibility(View.GONE);
        // The title keeps 6 dp of padding so its glow isn't clipped; pull the subtitle up into it,
        // leaving a 1 dp gap under the name.
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = px(-5);
        texts.addView(r.sub, slp);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(px(560), -2, Gravity.CENTER_VERTICAL);
        tlp.leftMargin = px(96 + 16 - 6);
        r.view.addView(texts, tlp);

        itemLayer.addView(r.view, new FrameLayout.LayoutParams(px(700), px(72)));
        return r;
    }

    // ---------------------------------------------------------------- layout and focus

    /** Places categories and items; animate = slide like the XMB, otherwise jump. */
    private void layout(boolean animate) {
        boolean newColumn = cat != shownCat;
        for (int ci = 0; ci < cats.size(); ci++) {
            int d = ci - cat;
            boolean on = d == 0;
            View v = catViews.get(ci);
            float x = px(CX + d * SPACING - 60), y = px(CY - 48);
            View icon = v.findViewWithTag("icon"), label = v.findViewWithTag("label");
            if (animate) {
                v.animate().translationX(x).translationY(y).alpha(on ? 1f : .55f)
                        .setDuration(220).setInterpolator(MOVE).start();
                icon.animate().scaleX(on ? 1f : .72f).scaleY(on ? 1f : .72f).setDuration(220).setInterpolator(MOVE).start();
                label.animate().alpha(on ? 1f : 0f).setDuration(220).start();
            } else {
                v.setTranslationX(x); v.setTranslationY(y); v.setAlpha(on ? 1f : .55f);
                icon.setScaleX(on ? 1f : .72f); icon.setScaleY(on ? 1f : .72f);
                label.setAlpha(on ? 1f : 0f);
            }

            Catalog.Category c = cats.get(ci);
            List<Row> list = rows.get(ci);
            for (int j = 0; j < list.size(); j++) {
                Row r = list.get(j);
                int k = j - c.selected;
                boolean focused = on && k == 0;
                float cy, a;
                if (k == 0) { cy = FOCUS_Y; a = 1f; }
                else if (k > 0) { cy = BELOW_Y + (k - 1) * STEP; a = .55f; }
                else { cy = CY - 78 + (k + 1) * STEP; a = .45f; }
                if (!on || cy < -40 || cy > 580) a = 0f;
                float tx = px(CX - 48 + (on ? 0 : d * SPACING)), ty = px(cy - 36);
                float scale = r.item.icon != 0 ? (focused ? 1f : .54f) : (focused ? 1f : .58f);
                setFocused(r, focused);
                boolean slide = animate && on && !newColumn;
                if (slide) {
                    r.view.animate().translationX(tx).translationY(ty).alpha(a)
                            .setDuration(220).setInterpolator(MOVE).start();
                    r.icon.animate().scaleX(scale).scaleY(scale).setDuration(220).setInterpolator(MOVE).start();
                } else if (animate && !on && r.view.getAlpha() > 0f) {
                    // The column being left travels with its category icon while it fades out.
                    r.view.animate().cancel();
                    r.icon.animate().cancel();
                    r.view.animate().translationX(tx).alpha(0f).setDuration(220).setInterpolator(MOVE).withEndAction(() -> {
                        r.view.setTranslationY(ty);
                        r.icon.setScaleX(scale);
                        r.icon.setScaleY(scale);
                    }).start();
                } else if (animate && on) {
                    // The column arriving comes in with its category icon from where it rested.
                    r.view.animate().cancel();
                    r.icon.animate().cancel();
                    r.view.setTranslationY(ty);
                    r.icon.setScaleX(scale);
                    r.icon.setScaleY(scale);
                    r.view.animate().translationX(tx).alpha(a).setDuration(220).setInterpolator(MOVE).start();
                } else {
                    r.view.animate().cancel();
                    r.icon.animate().cancel();
                    r.view.setTranslationX(tx);
                    r.view.setTranslationY(ty);
                    r.icon.setScaleX(scale);
                    r.icon.setScaleY(scale);
                    if (animate && on) {
                        r.view.setAlpha(0f);
                        r.view.animate().alpha(a).setDuration(220).setInterpolator(FADE).start();
                    } else {
                        r.view.setAlpha(a);
                    }
                }
            }
        }
        shownCat = cat;
        startGlow();
    }

    /** Only touches what changed: a text size or visibility change re-measures the row. */
    private void setFocused(Row r, boolean focused) {
        boolean was = r.focused;
        r.focused = focused;
        if (focused != was) {
            r.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, focused ? 18 : 16);
            if (!focused) r.title.setShadowLayer(0, 0, 0, 0);
        }
        boolean playing = focused && r.item.pkg != null && r.item.pkg.equals(playingPkg) && playingText != null;
        if (playing && !playingText.contentEquals(r.sub.getText())) r.sub.setText(playingText);
        int vis = playing ? View.VISIBLE : View.GONE;
        if (r.sub.getVisibility() != vis) r.sub.setVisibility(vis);
    }

    /** The focused title breathes: a soft white glow, 2.6 s in and out, ticked by the waves at 30 fps. */
    private void startGlow() {
        TextView target = null;
        if (cat >= 0 && cat < rows.size()) {
            for (Row r : rows.get(cat)) if (r.focused) target = r.title;
        }
        if (target != glowing && glowing != null) glowing.setShadowLayer(0, 0, 0, 0);
        glowing = target;
    }

    private void breathe(double seconds) {
        if (glowing == null) return;
        double p = (seconds % 2.6) / 1.3, f = p <= 1 ? p : 2 - p;
        f = f * f * (3 - 2 * f);   // ease in and out
        // Only the brightness breathes: a changing blur radius would re-blur the text on every
        // frame, which costs more than a whole frame's budget on this TV.
        int alpha = (int) Math.round((.15 + .8 * f) * 255);
        glowing.setShadowLayer(9 * dp, 0, 0, (alpha << 24) | 0xFFFFFF);
    }

    // ---------------------------------------------------------------- transitions

    /** Wake: background fades in, then the icons fade in while zooming out, then the items. */
    private void playWake() {
        animating = true;
        wave.animate().cancel();
        fg.animate().cancel();
        itemLayer.animate().cancel();
        wave.setAlpha(0f);
        fg.setAlpha(0f);
        fg.setScaleX(ZOOM);
        fg.setScaleY(ZOOM);
        itemLayer.setAlpha(0f);
        wave.animate().alpha(1f).setDuration(900).setStartDelay(0).setInterpolator(FADE).start();
        fg.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(700).setStartDelay(700).setInterpolator(FADE).start();
        itemLayer.animate().alpha(1f).setDuration(300).setStartDelay(700 + WAKE_ITEM_DELAY).setInterpolator(FADE)
                .withEndAction(() -> animating = false).start();
    }

    /** Back from an app: the menu settles from slightly larger, the reverse of opening. */
    private void playReturn() {
        animating = true;
        wave.setAlpha(0f);
        fg.setAlpha(0f);
        fg.setScaleX(ZOOM);
        fg.setScaleY(ZOOM);
        itemLayer.setAlpha(1f);
        wave.animate().alpha(1f).setDuration(450).setStartDelay(50).setInterpolator(FADE).start();
        fg.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(450).setStartDelay(150).setInterpolator(FADE)
                .withEndAction(() -> animating = false).start();
    }

    private void skipAnimations() {
        for (View v : new View[] {wave, fg, itemLayer}) {
            v.animate().cancel();
            v.setAlpha(1f);
        }
        fg.setScaleX(1f);
        fg.setScaleY(1f);
        animating = false;
    }

    // ---------------------------------------------------------------- screensaver

    private void armIdle() {
        idleHandler.removeCallbacks(goIdle);
        idleHandler.postDelayed(goIdle, IDLE_AFTER_MS);
    }

    /** The screensaver look: the menu dims and drifts back, the clock only dims. */
    private void dimMenu(long ms) {
        if (opening) return;
        idle = true;
        menu.animate().alpha(IDLE_ALPHA).scaleX(IDLE_SCALE).scaleY(IDLE_SCALE)
                .setDuration(ms).setInterpolator(FADE).start();
        clock.animate().alpha(CLOCK_ALPHA * IDLE_ALPHA).setDuration(ms).setInterpolator(FADE).start();
    }

    private void wakeFromIdle(boolean animate) {
        idle = false;
        menu.animate().cancel();
        clock.animate().cancel();
        if (animate) {
            menu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(IDLE_WAKE_MS).setInterpolator(FADE).start();
            clock.animate().alpha(CLOCK_ALPHA).setDuration(IDLE_WAKE_MS).setInterpolator(FADE).start();
        } else {
            menu.setAlpha(1f);
            menu.setScaleX(1f);
            menu.setScaleY(1f);
            clock.setAlpha(CLOCK_ALPHA);
        }
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int k = ev.getKeyCode();
        boolean nav = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A
                || k == KeyEvent.KEYCODE_BACK;
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            boolean wasIdle = idle;
            armIdle();
            if (wasIdle) {
                wakeFromIdle(true);
                // The first press only wakes the menu: it never moves or opens anything unseen.
                if (nav) return true;
            }
        }
        if (!nav) return super.dispatchKeyEvent(ev);
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (opening) return true;   // the app is about to start
        if (animating) { skipAnimations(); return true; }
        if (cats.isEmpty()) return true;
        Catalog.Category c = cats.get(cat);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (cat > 0) { cat--; layout(true); } break;   // ends don't wrap
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (cat < cats.size() - 1) { cat++; layout(true); } break;
            case KeyEvent.KEYCODE_DPAD_UP: if (c.selected > 0) { c.selected--; layout(true); } break;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (c.selected < c.items.size() - 1) { c.selected++; layout(true); } break;
            case KeyEvent.KEYCODE_BACK: break;   // a home screen never closes
            default: if (ev.getRepeatCount() == 0) open(c.items.get(c.selected)); break;
        }
        return true;
    }

    /**
     * Opening is the wake in reverse: the items go first, the icons zoom toward the viewer while
     * fading, then the background fades to black, and only then the app starts.
     */
    private void open(Catalog.Item it) {
        if (it.overlay) {
            // Display and Sound open as a panel over Home: no fade-out; Home dims and drifts back
            // behind the panel exactly like the screensaver, and comes back when it closes.
            overlayOpen = true;
            dimMenu(PANEL_DIM_MS);
            try {
                startActivity(new Intent(it.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e) {
                overlayOpen = false;
                wakeFromIdle(true);
                Toast.makeText(this, "Can't open " + it.title, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        opening = true;
        animating = true;
        itemLayer.animate().alpha(0f).setDuration(200).setStartDelay(0).setInterpolator(FADE).start();
        fg.animate().alpha(0f).scaleX(ZOOM).scaleY(ZOOM).setDuration(380).setStartDelay(100).setInterpolator(FADE).start();
        wave.animate().alpha(0f).setDuration(400).setStartDelay(250).setInterpolator(FADE)
                .withEndAction(() -> launch(it)).start();
    }

    private void launch(Catalog.Item it) {
        Bundle anim = ActivityOptions.makeCustomAnimation(this, R.anim.app_enter, 0).toBundle();
        try {
            startActivity(new Intent(it.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), anim);
        } catch (ActivityNotFoundException | SecurityException e) {
            Intent fb = it.fallbackPkg != null ? getPackageManager().getLeanbackLaunchIntentForPackage(it.fallbackPkg) : null;
            if (fb == null) {
                opening = false;
                skipAnimations();
                Toast.makeText(this, "Can't open " + it.title, Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(fb.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), anim);
        }
        opening = false;
        launchedApp = true;
        if (it.pkg != null) nowPlaying.launched(it.pkg);
    }

    // ---------------------------------------------------------------- clock and now playing

    private void updateClock() {
        clock.setText(Sky.now().format(CLOCK));
    }

    @Override
    public void onNowPlaying(String pkg, String text) {
        playingPkg = pkg;
        playingText = text;
        if (text == null) {
            nowPlayingBox.animate().alpha(0f).setDuration(400).start();
        } else {
            if (!text.contentEquals(ticker.getText())) ticker.setText(text);
            // As wide as a short title ("So What — Miles Davis"); longer text scrolls inside it.
            Paint p = ticker.getPaint();
            int cap = (int) Math.ceil(p.measureText("So What — Miles Davis"));
            int need = (int) Math.ceil(p.measureText(text));
            ticker.getLayoutParams().width = need > cap ? cap : LinearLayout.LayoutParams.WRAP_CONTENT;
            ticker.requestLayout();
            ticker.setSelected(true);
            nowPlayingBox.animate().alpha(.75f).setDuration(400).start();
        }
        if (cat >= 0 && cat < rows.size()) {
            for (Row r : rows.get(cat)) setFocused(r, r.focused);
        }
    }
}
