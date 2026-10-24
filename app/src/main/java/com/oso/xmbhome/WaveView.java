package com.oso.xmbhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.Choreographer;
import android.view.View;

import java.time.LocalDateTime;

/**
 * The background: the PS3 month colour at the hour's brightness, plus the PSP's waves: two smooth
 * translucent sheets, each a single long trough filled down to the bottom, drifting slowly in
 * opposite directions so they overlap into three tones and now and then cross. Redraws at 30 fps
 * only while started; the colour is recomputed once a minute.
 *
 * Drawn at half resolution into its own GPU layer and scaled up 2x: the soft shapes don't need
 * full resolution, and frames where only the menu changes (the breathing glow runs at 60 fps)
 * then reuse the layer instead of repainting two full-screen gradients.
 */
final class WaveView extends View implements Choreographer.FrameCallback {
    private static final long FRAME_NS = 33_333_333L;
    private static final double K = Math.PI * 2 / 1630;   // one trough is ~1.7 screen widths (dp)

    private final float dp;
    private final Paint bg = new Paint(), fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Sheet[] sheets = {
            new Sheet(330, 40, .17, .6, 12, .10f, .16f, .45f, .10f),
            new Sheet(382, 36, -.11, 2.9, -16, .12f, .24f, .60f, .08f)};
    private int colour = -1;
    private float brightness = -1;
    private boolean running;
    private long lastFrameNs;
    private double seconds;
    private java.util.function.DoubleConsumer onTick;

    /** Called on every 30 fps tick with the running time, so other slow animations can share it. */
    void setOnTick(java.util.function.DoubleConsumer tick) { onTick = tick; }

    private static final class Sheet {
        final float base, amp, tilt, topAlpha, bottomAlpha, liftToWhite, rimAlpha;
        final double speed, phase;   // radians per second (sign = direction), starting phase
        LinearGradient shader;

        Sheet(float base, float amp, double speed, double phase, float tilt,
              float topAlpha, float bottomAlpha, float lift, float rimAlpha) {
            this.base = base; this.amp = amp; this.speed = speed; this.phase = phase; this.tilt = tilt;
            this.topAlpha = topAlpha; this.bottomAlpha = bottomAlpha; this.liftToWhite = lift; this.rimAlpha = rimAlpha;
        }

        /** Edge height in dp at x dp across a screen wDp wide. */
        float y(float x, float wDp, double t) {
            return (float) (base + tilt * (x / wDp - .5f) * 2 + amp * Math.sin(x * K + t * speed + phase));
        }
    }

    WaveView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density / 2;   // half resolution
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1 * dp);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setPivotX(0);
        setPivotY(0);
        setScaleX(2);
        setScaleY(2);
        refreshSky();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec) / 2, MeasureSpec.getSize(hSpec) / 2);
    }

    /** Called on start and every minute (TIME_TICK). */
    void refreshSky() {
        LocalDateTime now = LocalDateTime.now();
        int c = Sky.colour(now);
        float b = Sky.brightness(now);
        if (c == colour && b == brightness && getWidth() > 0 && bg.getShader() != null) return;
        colour = c;
        brightness = b;
        buildShaders();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        buildShaders();
    }

    private void buildShaders() {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        int lit = Sky.scale(colour, brightness);
        // CSS 170deg: top-left-ish to bottom-right-ish, darker at the top so the clock stays readable.
        bg.setShader(new LinearGradient(w * 0.54f, 0, w * 0.44f, h,
                new int[] {0xFF000000 | Sky.scale(lit, .45f), 0xFF000000 | Sky.scale(lit, .8f), 0xFF000000 | lit},
                new float[] {0, .6f, 1}, Shader.TileMode.CLAMP));
        for (Sheet s : sheets) {
            int rgb = Sky.lift(colour, s.liftToWhite);
            float top = (s.base - s.amp - Math.abs(s.tilt)) * dp;
            s.shader = new LinearGradient(0, top, 0, h, alpha(rgb, s.topAlpha), alpha(rgb, s.bottomAlpha), Shader.TileMode.CLAMP);
        }
    }

    private static int alpha(int rgb, float a) {
        return (Math.round(a * 255) << 24) | rgb;
    }

    void start() {
        if (running) return;
        running = true;
        lastFrameNs = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) return;
        if (lastFrameNs == 0 || frameTimeNanos - lastFrameNs >= FRAME_NS) {
            if (lastFrameNs != 0) seconds += (frameTimeNanos - lastFrameNs) / 1e9;
            lastFrameNs = frameTimeNanos;
            invalidate();
            if (onTick != null) onTick.accept(seconds);
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawRect(0, 0, w, h, bg);
        float wDp = w / dp;
        for (Sheet s : sheets) {
            path.rewind();
            path.moveTo(0, h);
            for (float x = 0; x <= wDp + 12; x += 12) path.lineTo(x * dp, s.y(x, wDp, seconds) * dp);
            path.lineTo(w, h);
            path.close();
            fill.setShader(s.shader);
            canvas.drawPath(path, fill);
            path.rewind();
            for (float x = 0; x <= wDp + 12; x += 12) {
                float y = s.y(x, wDp, seconds) * dp;
                if (x == 0) path.moveTo(0, y); else path.lineTo(x * dp, y);
            }
            rim.setColor(alpha(0xFFFFFF, s.rimAlpha));
            canvas.drawPath(path, rim);
        }
    }
}
