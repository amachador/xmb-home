package com.oso.xmbhome;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * The PS3 XMB's background rule: a colour per half month (anchored on the 15th and 24th, blending
 * over the three days before each anchor) and a brightness that follows the hour, black at night.
 */
final class Sky {
    private static final int[] PALETTE = {
            0xF0E68C, 0xA52A2A, 0x6B8E23, 0x1F7F1F, 0xFFC0CB, 0xFF69B4, 0xC0C0C0, 0x228B22,
            0xD8BFD8, 0x800080, 0xADD8E6, 0x208080, 0x0000FF, 0x00008B, 0x800080, 0xEE82EE,
            0x800000, 0xF0E68C, 0xB5703A, 0xA52A2A, 0xC81E28, 0xE60000, 0xE0B0FF, 0xC0C0C0};
    private static final float[] LUMINANCE = {
            0, 0, 0, 0, 0, 0, .13f, .27f, .4f, .53f, .67f, .8f, .93f, .8f, .67f, .53f, .4f, .27f, .13f, 0, 0, 0, 0, 0};
    private static final double BLEND_MINUTES = 3 * 24 * 60;

    /** Debug builds only: a fixed date and time to show (for screenshots), or null for the clock. */
    static LocalDateTime preview;

    static LocalDateTime now() {
        return preview != null ? preview : LocalDateTime.now();
    }

    /** Month colour as 0xRRGGBB. */
    static int colour(LocalDateTime now) {
        LocalDateTime prev = null, next = null;
        int prevColour = 0, nextColour = 0;
        for (int y = now.getYear() - 1; y <= now.getYear() + 1; y++) {
            for (int m = 1; m <= 12; m++) {
                for (int half = 0; half < 2; half++) {
                    LocalDateTime a = LocalDate.of(y, m, half == 0 ? 15 : 24).atStartOfDay();
                    int c = PALETTE[(m - 1) * 2 + half];
                    if (!a.isAfter(now)) { prev = a; prevColour = c; }
                    else if (next == null) { next = a; nextColour = c; }
                }
            }
        }
        double untilNext = ChronoUnit.MINUTES.between(now, next);
        if (prev == null || untilNext > BLEND_MINUTES) return prevColour;
        return mix(prevColour, nextColour, (float) (1 - untilNext / BLEND_MINUTES));
    }

    /** 0 at night, 0.93 at noon, interpolated through the hour. */
    static float brightness(LocalDateTime now) {
        float h = now.getHour() + now.getMinute() / 60f;
        int a = (int) h % 24, b = (a + 1) % 24;
        return LUMINANCE[a] + (LUMINANCE[b] - LUMINANCE[a]) * (h - (int) h);
    }

    static int mix(int from, int to, float t) {
        int r = Math.round(((from >> 16) & 255) + (((to >> 16) & 255) - ((from >> 16) & 255)) * t);
        int g = Math.round(((from >> 8) & 255) + (((to >> 8) & 255) - ((from >> 8) & 255)) * t);
        int b = Math.round((from & 255) + ((to & 255) - (from & 255)) * t);
        return (r << 16) | (g << 8) | b;
    }

    static int scale(int rgb, float k) {
        return mix(0, rgb, k);
    }

    static int lift(int rgb, float t) {
        return mix(rgb, 0xFFFFFF, t);
    }

    private Sky() {}
}
