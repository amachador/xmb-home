package com.oso.xmbhome;

import android.content.Context;
import android.graphics.Typeface;

/** M PLUS 1p (SIL Open Font License, assets/fonts/OFL.txt), trimmed to Latin. */
final class Fonts {
    private static Typeface regular, medium;

    static Typeface regular(Context c) {
        if (regular == null) regular = load(c, "fonts/MPLUS1p-Regular.ttf", Typeface.NORMAL);
        return regular;
    }

    static Typeface medium(Context c) {
        if (medium == null) medium = load(c, "fonts/MPLUS1p-Medium.ttf", Typeface.BOLD);
        return medium;
    }

    private static Typeface load(Context c, String path, int fallbackStyle) {
        try {
            return Typeface.createFromAsset(c.getAssets(), path);
        } catch (RuntimeException e) {
            return Typeface.create(Typeface.SANS_SERIF, fallbackStyle);
        }
    }

    private Fonts() {}
}
