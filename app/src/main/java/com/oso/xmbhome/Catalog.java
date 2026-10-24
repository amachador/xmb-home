package com.oso.xmbhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;
import android.text.TextUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Builds the cross bar: Settings, Inputs, Music, Video, Apps. */
final class Catalog {
    static final String TV_APP = "com.mediatek.wwtv.tvcenter";
    static final String TV_SETTINGS = "com.android.tv.settings";
    static final int VIDEO = 3;

    static final class Category {
        final String name;
        final int icon;
        final List<Item> items = new ArrayList<>();
        int selected;

        Category(String name, int icon) { this.name = name; this.icon = icon; }
    }

    static final class Item {
        String title;
        int icon;            // XMB vector, or 0 for an app
        Drawable banner;     // app banner or icon
        boolean isBanner;    // 16:9 banner rather than a square icon
        String pkg;          // apps only
        Intent intent;
        String fallbackPkg;  // Live TV: open the TV app if the tuner URI isn't handled
        boolean overlay;     // opens as a panel over Home: no fade-out, Home stays visible behind it
    }

    static List<Category> build(Context c) {
        List<Category> cats = new ArrayList<>();
        cats.add(settings(c));
        cats.add(inputs(c));
        Category music = new Category("Music", R.drawable.xmb_music);
        Category video = new Category("Video", R.drawable.xmb_video);
        Category apps = new Category("Apps", R.drawable.xmb_apps);
        sortApps(c, music, video, apps);
        Intent all = tvSettings(c, new Intent(Settings.ACTION_APPLICATION_SETTINGS), ".AllAppsActivity");
        if (resolves(c, all)) apps.items.add(setting("All Apps", R.drawable.xmb_folder, all));
        cats.add(music);
        cats.add(video);
        cats.add(apps);
        return cats;
    }

    private static Category settings(Context c) {
        Category s = new Category("Settings", R.drawable.xmb_settings);
        addIfResolves(c, s, "Display Settings", R.drawable.xmb_display, Settings.ACTION_DISPLAY_SETTINGS, null, true);
        addIfResolves(c, s, "Sound Settings", R.drawable.xmb_sound, Settings.ACTION_SOUND_SETTINGS, ".SoundActivity", true);
        addIfResolves(c, s, "Network Settings", R.drawable.xmb_network, Settings.ACTION_WIFI_SETTINGS, null, false);
        addIfResolves(c, s, "All Settings", R.drawable.xmb_system, Settings.ACTION_SETTINGS, ".MainSettings", false);
        return s;
    }

    private static void addIfResolves(Context c, Category cat, String title, int icon, String action, String preferredClass, boolean overlay) {
        Intent i = tvSettings(c, new Intent(action), preferredClass);
        if (!resolves(c, i)) return;
        Item it = setting(title, icon, i);
        it.overlay = overlay;
        cat.items.add(it);
    }

    /**
     * Point the action at one TV Settings screen: on this TV some actions have two handlers in
     * TV Settings itself (Sound, Apps) and would otherwise open a chooser.
     */
    private static Intent tvSettings(Context c, Intent i, String preferredClass) {
        List<ResolveInfo> found = c.getPackageManager().queryIntentActivities(new Intent(i).setPackage(TV_SETTINGS), 0);
        if (found.isEmpty()) return i;
        ActivityInfo pick = found.get(0).activityInfo;
        for (ResolveInfo ri : found) {
            if (preferredClass != null && ri.activityInfo.name.endsWith(preferredClass)) pick = ri.activityInfo;
        }
        return new Intent(i).setComponent(new ComponentName(pick.packageName, pick.name));
    }

    private static Item setting(String title, int icon, Intent i) {
        Item it = new Item();
        it.title = title;
        it.icon = icon;
        it.intent = i;
        return it;
    }

    private static boolean resolves(Context c, Intent i) {
        return i.resolveActivity(c.getPackageManager()) != null;
    }

    /** Live TV first, then HDMI ports by number, with the names given in TV settings. */
    private static Category inputs(Context c) {
        Category cat = new Category("Inputs", R.drawable.xmb_inputs);
        TvInputManager tim = c.getSystemService(TvInputManager.class);
        String tunerId = null;
        List<String[]> hdmi = new ArrayList<>();   // {sortKey, title, inputId}
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isHidden(c)) continue;
                if (info.getType() == TvInputInfo.TYPE_TUNER) {
                    if (tunerId == null) tunerId = info.getId();
                    continue;
                }
                if (info.getType() != TvInputInfo.TYPE_HDMI || info.getParentId() != null) continue;
                String def = String.valueOf(info.loadLabel(c));
                CharSequence custom = info.loadCustomLabel(c);
                String title = !TextUtils.isEmpty(custom) ? custom.toString() : def;
                hdmi.add(new String[] {def, title, info.getId()});
            }
        }
        Item live = new Item();
        live.title = "Live TV";
        live.icon = R.drawable.xmb_livetv;
        // The MediaTek TV app ignores channels-for-input URIs but switches to the tuner when
        // handed the tuner's id as a passthrough URI (verified on the 55PUD7908).
        live.intent = tunerId != null
                ? new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(tunerId))
                : c.getPackageManager().getLeanbackLaunchIntentForPackage(TV_APP);
        live.fallbackPkg = TV_APP;
        if (live.intent != null) cat.items.add(live);
        hdmi.sort((a, b) -> a[0].compareTo(b[0]));
        for (String[] h : hdmi) {
            Item it = new Item();
            it.title = h[1];
            it.icon = R.drawable.xmb_inputs;
            it.intent = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(h[2]));
            cat.items.add(it);
        }
        return cat;
    }

    private static void sortApps(Context c, Category music, Category video, Category apps) {
        PackageManager pm = c.getPackageManager();
        Set<String> hidden = set(c, R.array.hidden_packages), musicPkgs = set(c, R.array.music_packages),
                appPkgs = set(c, R.array.apps_packages);
        List<String> first = Arrays.asList(c.getResources().getStringArray(R.array.video_first));
        Set<String> seen = new HashSet<>();
        List<Item> rest = new ArrayList<>();
        Item[] firsts = new Item[first.size()];
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(c.getPackageName()) || hidden.contains(ai.packageName)
                    || !seen.add(ai.packageName)) continue;
            Item it = new Item();
            it.pkg = ai.packageName;
            it.title = ri.loadLabel(pm).toString();
            Drawable art = ai.loadBanner(pm);
            if (art == null) art = ai.applicationInfo.loadBanner(pm);
            it.isBanner = art != null;
            if (art == null) art = ri.loadIcon(pm);
            it.banner = shrink(c, art, it.isBanner);
            it.intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(new ComponentName(ai.packageName, ai.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if (musicPkgs.contains(it.pkg)) music.items.add(it);
            else if (appPkgs.contains(it.pkg)) apps.items.add(it);
            else if (first.contains(it.pkg)) firsts[first.indexOf(it.pkg)] = it;
            else rest.add(it);
        }
        Collator collator = Collator.getInstance();
        rest.sort((a, b) -> collator.compare(a.title, b.title));
        for (Item it : firsts) if (it != null) video.items.add(it);
        video.items.addAll(rest);
    }

    /** Banners can be decoded at several times the size shown; keep only a bitmap of the drawn size. */
    private static Drawable shrink(Context c, Drawable d, boolean banner) {
        if (d == null) return null;
        float dp = c.getResources().getDisplayMetrics().density;
        int w = Math.round((banner ? 96 : 68) * dp), h = Math.round((banner ? 54 : 68) * dp);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Rect old = d.copyBounds();
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(b));
        d.setBounds(old);
        return new BitmapDrawable(c.getResources(), b);
    }

    private static Set<String> set(Context c, int res) {
        return new HashSet<>(Arrays.asList(c.getResources().getStringArray(res)));
    }

    private Catalog() {}
}
