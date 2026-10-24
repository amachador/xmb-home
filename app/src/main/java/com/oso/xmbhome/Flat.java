package com.oso.xmbhome;

import android.content.Context;
import android.widget.FrameLayout;

/**
 * A FrameLayout whose children don't overlap visually, so fading it doesn't need an offscreen
 * buffer: dimmed rows and categories would otherwise each get a GPU layer.
 */
final class Flat extends FrameLayout {
    Flat(Context c) {
        super(c);
        setClipChildren(false);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
