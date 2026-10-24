package com.marianocarrizo.dynamicMusic.ambiance;

import com.marianocarrizo.dynamicMusic.model.AuraConfig;
import com.marianocarrizo.dynamicMusic.service.AudioFeatures;
import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;

import java.util.Random;
import java.util.function.Supplier;

final class AuraRenderer {

    interface BackgroundPulse {
        void apply(double scale, double brightness);
    }

    static final double LONG_BLEND_SEC = 1.5;
    static final double SHORT_BLEND_SEC = 0.2;
    private static final double MAX_DT = 0.05;

    private static final double ATTACK_RATE = 35.0;
    private static final double RELEASE_RATE = 7.0;
    private static final double BEAT_ATTACK_RATE = 50.0;
    private static final double MIN_BEAT_STRENGTH = 0.4;
    private static final double DEPTH_MIN = 0.5;
    private static final double DEPTH_SPAN = 3.0;
    private static final double REST_SHARE = 0.55;
    private static final double AVG_RATE = 0.8;
    private static final double BEAT_GAIN_MIN = 0.4;
    private static final double BEAT_GAIN_SPAN = 1.2;

    private static final double GLOW_HEIGHT = 0.34;
    private static final double SIDE_GLOW_WIDTH = 0.12;
    private static final double SIDE_GLOW_SHARE = 0.35;
    private static final double EDGE_LINE_HEIGHT = 0.012;
    private static final double VIGNETTE_MAX_ALPHA = 0.92;

    private static final int MAX_EMBERS = 60;
    private static final int MIN_EMBERS = 15;
    private static final double EMBER_RISE = 0.075;
    private static final double EMBER_SIDE_BAND = 0.34;
    private static final double EMBER_BASE_RADIUS = 3.0;
    private static final double EMBER_RADIUS_VAR = 3.5;
    private static final double EMBER_SPRITE_SCALE = 4.0;
    private static final double EMBER_SWAY_PX = 18.0;
    private static final int SPRITE_SIZE = 48;
    private static final int SPRITE_STEPS = 6;

    private static final double ZOOM_MAX = 0.022;
    private static final double BRIGHTNESS_MAX = 0.06;

    private Supplier<AudioFeatures> audio = () -> AudioFeatures.SILENT;
    private final BackgroundPulse pulse;
    private final Random rnd = new Random();
    private final AnimationTimer timer;

    private Canvas canvas;
    private GraphicsContext gc;

    private double[] displayed;
    private double[] from;
    private double[] target;
    private double blendT = 1.0;
    private double blendSec = LONG_BLEND_SEC;

    private double bass, mid, high, energy;
    private double kick, tonal, kickHitVis, snareHitVis;
    private double bassAvg, midAvg, highAvg, energyAvg, tonalAvg;

    private boolean debug = false;
    private final AuraDebugRecorder recorder = new AuraDebugRecorder();
    private AudioFeatures lastFeatures = AudioFeatures.SILENT;
    private double fps = 0;
    private double beatLamp = 0;
    private double beat;
    private double beatVis;
    private long lastBeatCount = -1;

    private long lastNs;
    private double time;
    private boolean running;
    private boolean cleared;

    private double lastScale = 1.0;
    private double lastBrightness = 0.0;

    private final boolean[] eAlive = new boolean[MAX_EMBERS];
    private final double[] eBaseX = new double[MAX_EMBERS];
    private final double[] eY = new double[MAX_EMBERS];
    private final double[] eStartY = new double[MAX_EMBERS];
    private final double[] eTravel = new double[MAX_EMBERS];
    private final double[] eRadius = new double[MAX_EMBERS];
    private final double[] eSpeed = new double[MAX_EMBERS];
    private final double[] eSwayFreq = new double[MAX_EMBERS];
    private final double[] eSwayPhase = new double[MAX_EMBERS];
    private final double[] eFlickFreq = new double[MAX_EMBERS];
    private final double[] eFlickPhase = new double[MAX_EMBERS];
    private final double[] eMix = new double[MAX_EMBERS];

    private long vignetteKey = -1;
    private Paint vignettePaint;
    private long glowKey = -1;
    private Paint glowPaint;
    private Paint sidePaint;
    private Paint linePaint;
    private long spriteKey = -1;
    private final Image[] sprites = new Image[SPRITE_STEPS];

    AuraRenderer(BackgroundPulse pulse) {
        this.pulse = pulse;
        for (int i = 0; i < MAX_EMBERS; i++) spawnEmber(i, true);
        timer = new AnimationTimer() {
            @Override public void handle(long now) { frame(now); }
        };
    }

    void attach(Canvas newCanvas) {
        stop();
        canvas = newCanvas;
        gc = canvas.getGraphicsContext2D();
    }

    void setAudioSource(Supplier<AudioFeatures> source) {
        audio = source != null ? source : () -> AudioFeatures.SILENT;
    }

    void setTarget(AuraConfig aura, boolean longTransition, boolean calm) {
        double[] next = AuraLook.resolve(aura, calm);
        if (displayed == null) {
            displayed = next.clone();
            from = next.clone();
            target = next;
            blendT = 1.0;
            return;
        }
        from = displayed.clone();
        target = next;
        blendT = 0.0;
        blendSec = longTransition ? LONG_BLEND_SEC : SHORT_BLEND_SEC;
    }

    void setDebug(boolean on) {
        if (on == debug) return;
        debug = on;
        if (on) recorder.open();
        else recorder.close();
    }

    boolean isDebug() {
        return debug;
    }

    void start() {
        if (running || canvas == null) return;
        running = true;
        lastNs = 0;
        lastBeatCount = -1;
        cleared = false;
        timer.start();
    }

    void stop() {
        if (running) timer.stop();
        running = false;
        if (gc != null && canvas != null) gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        cleared = true;
        applyPulse(1.0, 0.0, true);
    }

    private void frame(long now) {
        if (lastNs == 0) { lastNs = now; return; }
        double dt = Math.min(MAX_DT, (now - lastNs) / 1e9);
        lastNs = now;
        time += dt;
        if (displayed == null || canvas == null) return;

        advanceBlend(dt);
        double[] v = displayed;

        double reactivity = v[AuraLook.REACTIVITY];
        double motion = v[AuraLook.MOTION];
        double depth = (DEPTH_MIN + DEPTH_SPAN * reactivity) * motion;
        updateAudio(dt, v);
        double midG = swing(mid, midAvg, depth);
        double highG = swing(high, highAvg, depth);
        double energyG = swing(energy, energyAvg, depth);
        double tonalG = swing(tonal, tonalAvg, depth);
        double percGain = (BEAT_GAIN_MIN + BEAT_GAIN_SPAN * reactivity) * v[AuraLook.PERC];
        double kickG = Math.min(1, Math.max(beatVis, kickHitVis) * percGain);
        double snareG = Math.min(1, snareHitVis * percGain);
        double kickLvlG = Math.min(1, kick * percGain);

        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;

        boolean anyLayer = v[AuraLook.W_VIGNETTE] > 0.003 || v[AuraLook.W_GLOW] > 0.003
                || v[AuraLook.W_EMBERS] > 0.003 || hasLiveEmbers();
        if (anyLayer || debug) {
            gc.clearRect(0, 0, w, h);
            cleared = false;
            double iMul = 0.2 + 1.2 * v[AuraLook.INTENSITY];
            drawVignette(v, w, h, iMul, tonalG, kickG);
            drawGlow(v, w, h, iMul, tonalG, energyG, snareG, kickG);
            drawEmbers(v, dt, w, h, tonalG, energyG, highG, kickG);
        } else if (!cleared) {
            gc.clearRect(0, 0, w, h);
            cleared = true;
        }

        double wp = v[AuraLook.W_PULSE];
        double drive = Math.min(1, 0.9 * kickG + 0.25 * kickLvlG + 0.12 * tonalG);
        double scale = 1.0 + wp * ZOOM_MAX * drive;
        double brightness = wp * BRIGHTNESS_MAX * Math.min(1, kickG + 0.1 * kickLvlG);
        applyPulse(scale, brightness, false);

        if (debug) {
            if (dt > 0) fps += (1.0 / dt - fps) * 0.05;
            drawHud(v, w, h, tonalG, kickLvlG, snareG, kickG, midG, highG, energyG, scale);
            AudioFeatures f = lastFeatures;
            recorder.write(f.mediaTime(), f.rawBass(), f.bass(), f.mid(), f.high(), f.energy(),
                    f.beatCount(), f.beatStrength(),
                    bass, mid, high, energy, bassAvg, energyAvg,
                    kickLvlG, midG, highG, energyG, kickG,
                    v[AuraLook.REACTIVITY], v[AuraLook.INTENSITY], scale,
                    f.kick(), f.snare(), f.tonal(), f.kickHit(), f.snareHit(),
                    kickLvlG, snareG, tonalG, kickG);
        }
    }

    private void drawHud(double[] v, double w, double h, double tonalG, double kickLvlG, double snareG,
                         double kickG, double midG, double highG, double energyG, double scale) {
        double bassG = kickLvlG, beatG = kickG;
        final double x = 16, y = 16, bw = 260, rowH = 22;
        final double boxW = bw + 120, boxH = 14 * rowH + 20;
        gc.save();
        gc.setGlobalAlpha(1.0);
        gc.clearRect(x - 8, y - 8, boxW, boxH);
        gc.setFill(Color.color(0, 0, 0, 0.72));
        gc.fillRect(x - 8, y - 8, boxW, boxH);
        gc.setFont(Font.font("Monospaced", 12));

        AudioFeatures f = lastFeatures;
        double[][] rows = {
                {f.rawBass(), bassG}, {f.bass(), bassG}, {f.mid(), midG}, {f.high(), highG}, {f.energy(), energyG},
                {f.kick(), kickLvlG}, {f.snare(), snareG}, {f.tonal(), tonalG},
                {f.kickHit(), kickG}, {f.snareHit(), snareG}
        };
        String[] names = {"bass raw", "bass", "mid", "high", "energy",
                "kick", "snare", "tonal", "kickHit", "snareHit"};
        for (int i = 0; i < rows.length; i++) {
            double ry = y + i * rowH;
            gc.setFill(Color.color(1, 1, 1, 0.85));
            gc.fillText(names[i], x, ry + 12);
            double bx = x + 70;
            gc.setFill(Color.color(1, 1, 1, 0.12));
            gc.fillRect(bx, ry, bw - 70, 6);
            gc.fillRect(bx, ry + 8, bw - 70, 6);
            gc.setFill(Color.web("#4FC3FF"));
            gc.fillRect(bx, ry, (bw - 70) * clamp01(rows[i][0]), 6);
            gc.setFill(Color.web("#2fbf71"));
            gc.fillRect(bx, ry + 8, (bw - 70) * clamp01(rows[i][1]), 6);
        }

        if (f.beatCount() != (long) beatLampCount) { beatLamp = 1.0; beatLampCount = f.beatCount(); }
        beatLamp *= 0.85;
        double ly = y + rows.length * rowH;
        gc.setFill(Color.color(1, 0.35, 0.2, 0.25 + 0.75 * beatLamp));
        gc.fillOval(x, ly, 14, 14);
        gc.setFill(Color.color(1, 1, 1, 0.85));
        gc.fillText(String.format(java.util.Locale.ROOT, "beats %d  beat_g %.2f", f.beatCount(), beatG),
                x + 22, ly + 12);
        gc.fillText(String.format(java.util.Locale.ROOT, "react %.2f  int %.2f  zoom %.3f",
                v[AuraLook.REACTIVITY], v[AuraLook.INTENSITY], scale), x, ly + rowH + 12);
        gc.fillText(String.format(java.util.Locale.ROOT, "media %.2fs  fps %.0f  %s",
                f.mediaTime(), fps, recorder.isOpen() ? "REC audio-debug.csv" : "not recording"),
                x, ly + 2 * rowH + 12);
        gc.setFill(Color.color(1, 1, 1, 0.5));
        gc.fillText("blue = analyzer   green = aura input   F3 = hide", x, ly + 3 * rowH + 12);
        gc.restore();
    }

    private double beatLampCount = -1;

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(1, v);
    }

    private void advanceBlend(double dt) {
        if (blendT >= 1.0) return;
        blendT = Math.min(1.0, blendT + dt / blendSec);
        double e = blendT * blendT * (3 - 2 * blendT);
        for (int i = 0; i < displayed.length; i++) {
            displayed[i] = from[i] + (target[i] - from[i]) * e;
        }
        if (blendT >= 1.0) System.arraycopy(target, 0, displayed, 0, displayed.length);
    }

    private void updateAudio(double dt, double[] v) {
        AudioFeatures f = audio.get();
        if (f == null) f = AudioFeatures.SILENT;
        lastFeatures = f;

        bass = follow(bass, f.bass(), dt);
        kick = follow(kick, f.kick(), dt);
        tonal = follow(tonal, f.tonal(), dt);
        kickHitVis = followHit(kickHitVis, f.kickHit(), dt);
        snareHitVis = followHit(snareHitVis, f.snareHit(), dt);
        mid = follow(mid, f.mid(), dt);
        high = follow(high, f.high(), dt);
        energy = follow(energy, f.energy(), dt);
        double k = 1 - Math.exp(-AVG_RATE * dt);
        bassAvg += (bass - bassAvg) * k;
        midAvg += (mid - midAvg) * k;
        highAvg += (high - highAvg) * k;
        energyAvg += (energy - energyAvg) * k;
        tonalAvg += (tonal - tonalAvg) * k;

        if (lastBeatCount < 0) {
            lastBeatCount = f.beatCount();
        } else if (f.beatCount() != lastBeatCount) {
            lastBeatCount = f.beatCount();
            beat = Math.max(beat, Math.min(1.0, Math.max(MIN_BEAT_STRENGTH, f.beatStrength())));
        }
        beat *= Math.exp(-dt / Math.max(0.03, v[AuraLook.BEAT_DECAY]));
        if (beat > beatVis) beatVis += (beat - beatVis) * (1 - Math.exp(-BEAT_ATTACK_RATE * dt));
        else beatVis = beat;
    }

    private static double swing(double value, double avg, double depth) {
        return Math.max(0, Math.min(1, REST_SHARE * avg + (value - avg) * depth));
    }

    private static double followHit(double current, double goal, double dt) {
        return goal > current ? current + (goal - current) * (1 - Math.exp(-BEAT_ATTACK_RATE * dt)) : goal;
    }

    private static double follow(double current, double goal, double dt) {
        double rate = goal > current ? ATTACK_RATE : RELEASE_RATE;
        return current + (goal - current) * (1 - Math.exp(-rate * dt));
    }

    private void applyPulse(double scale, double brightness, boolean force) {
        if (!force && Math.abs(scale - lastScale) < 1e-5 && Math.abs(brightness - lastBrightness) < 1e-4) return;
        lastScale = scale;
        lastBrightness = brightness;
        if (pulse != null) pulse.apply(scale, brightness);
    }

    private void drawVignette(double[] v, double w, double h, double iMul, double tonalG, double kickG) {
        double weight = v[AuraLook.W_VIGNETTE];
        if (weight < 0.003) return;
        ensureVignettePaint(v);
        double alpha = (v[AuraLook.V_BASE] + v[AuraLook.V_BREATH] * (0.85 * tonalG + 0.3 * kickG)) * iMul;
        alpha = Math.min(VIGNETTE_MAX_ALPHA, alpha) * weight;
        gc.save();
        gc.translate(w / 2, h / 2);
        gc.scale(w / 2, h / 2);
        gc.setGlobalAlpha(alpha);
        gc.setFill(vignettePaint);
        gc.fillRect(-1.5, -1.5, 3, 3);
        gc.restore();
    }

    private void drawGlow(double[] v, double w, double h, double iMul,
                          double tonalG, double energyG, double snareG, double kickG) {
        double weight = v[AuraLook.W_GLOW];
        if (weight < 0.003) return;
        ensureGlowPaints(v);

        double hit = snareG + 0.35 * kickG;
        double level = v[AuraLook.G_BASE] + v[AuraLook.G_ENERGY] * (0.75 * tonalG + 0.25 * energyG);
        double alpha = Math.min(0.95, level * iMul + hit * v[AuraLook.BEAT_FLASH]) * weight;
        double heightScale = 0.85 + 0.3 * tonalG;

        gc.save();
        gc.translate(0, h);
        gc.scale(w, h * GLOW_HEIGHT * heightScale);
        gc.setGlobalAlpha(alpha);
        gc.setFill(glowPaint);
        gc.fillRect(0, -1, 1, 1);
        gc.restore();

        double sideW = w * SIDE_GLOW_WIDTH;
        gc.save();
        gc.setGlobalAlpha(alpha * SIDE_GLOW_SHARE);
        gc.setFill(sidePaint);
        gc.save();
        gc.scale(sideW, h);
        gc.fillRect(0, 0, 1, 1);
        gc.restore();
        gc.translate(w, 0);
        gc.scale(-sideW, h);
        gc.fillRect(0, 0, 1, 1);
        gc.restore();

        double line = Math.min(1.0, (snareG + 0.3 * kickG) * v[AuraLook.EDGE_LINE]) * weight;
        if (line > 0.01) {
            gc.save();
            gc.translate(0, h);
            gc.scale(w, h * EDGE_LINE_HEIGHT);
            gc.setGlobalAlpha(line);
            gc.setFill(linePaint);
            gc.fillRect(0, -1, 1, 1);
            gc.restore();
        }
    }

    private void drawEmbers(double[] v, double dt, double w, double h,
                            double tonalG, double energyG, double highG, double kickG) {
        double weight = v[AuraLook.W_EMBERS];
        int active = weight < 0.02 ? 0
                : (int) Math.round((MIN_EMBERS + (MAX_EMBERS - MIN_EMBERS) * v[AuraLook.INTENSITY]) * Math.min(1, weight));
        ensureSprites(v);

        double px = h / 1080.0;
        double flickerDepth = v[AuraLook.E_FLICKER] * (0.30 + 0.70 * highG);
        double speedFactor = EMBER_RISE * v[AuraLook.E_SPEED]
                * (0.45 + 0.9 * (0.65 * tonalG + 0.35 * energyG)) * (1 + 0.5 * kickG);
        double alphaBase = v[AuraLook.E_ALPHA] * (0.45 + 0.75 * v[AuraLook.INTENSITY]) * Math.min(1, weight)
                * (1 + 0.5 * kickG);
        double swayAmp = EMBER_SWAY_PX * px * v[AuraLook.E_SWAY];
        double secondaryMax = v[AuraLook.E_SECONDARY];

        for (int i = 0; i < MAX_EMBERS; i++) {
            if (!eAlive[i]) {
                if (i < active && rnd.nextDouble() < dt * 3) spawnEmber(i, false, secondaryMax);
                continue;
            }
            eY[i] -= speedFactor * eSpeed[i] * dt;
            double p = (eStartY[i] - eY[i]) / eTravel[i];
            if (p >= 1.0) {
                if (i < active) spawnEmber(i, false, secondaryMax);
                else eAlive[i] = false;
                continue;
            }
            double fadeIn = smooth(p / 0.12);
            double fadeOut = 1 - smooth((p - 0.55) / 0.45);
            double flicker = 1 - flickerDepth * (0.5 + 0.5 * Math.sin(time * eFlickFreq[i] + eFlickPhase[i]));
            double a = alphaBase * fadeIn * fadeOut * flicker;
            if (a < 0.01) continue;

            double x = eBaseX[i] * w + Math.sin(eSwayPhase[i] + time * eSwayFreq[i]) * swayAmp * (0.4 + 0.6 * p);
            double d = eRadius[i] * px * v[AuraLook.E_SIZE] * EMBER_SPRITE_SCALE;
            int step = (int) Math.round(eMix[i] * (SPRITE_STEPS - 1));
            gc.setGlobalAlpha(Math.min(1.0, a));
            gc.drawImage(sprites[step], x - d / 2, eY[i] * h - d / 2, d, d);
        }
        gc.setGlobalAlpha(1.0);
    }

    private boolean hasLiveEmbers() {
        for (boolean alive : eAlive) if (alive) return true;
        return false;
    }

    private void spawnEmber(int i, boolean initial) {
        spawnEmber(i, initial, 0.5);
    }

    private void spawnEmber(int i, boolean initial, double secondaryMax) {
        double offset = Math.pow(rnd.nextDouble(), 1.5) * EMBER_SIDE_BAND;
        eBaseX[i] = rnd.nextBoolean() ? offset : 1.0 - offset;
        eStartY[i] = 1.02 + rnd.nextDouble() * 0.05;
        eTravel[i] = 0.35 + 0.45 * rnd.nextDouble();
        eY[i] = initial ? eStartY[i] - eTravel[i] * rnd.nextDouble() : eStartY[i];
        eRadius[i] = EMBER_BASE_RADIUS + EMBER_RADIUS_VAR * rnd.nextDouble();
        eSpeed[i] = 0.7 + 0.6 * rnd.nextDouble();
        eSwayFreq[i] = 0.5 + 0.7 * rnd.nextDouble();
        eSwayPhase[i] = rnd.nextDouble() * Math.PI * 2;
        eFlickFreq[i] = 7 + 7 * rnd.nextDouble();
        eFlickPhase[i] = rnd.nextDouble() * Math.PI * 2;
        eMix[i] = rnd.nextDouble() * secondaryMax;
        eAlive[i] = true;
    }

    private static double smooth(double t) {
        double c = Math.max(0, Math.min(1, t));
        return c * c * (3 - 2 * c);
    }

    private void ensureVignettePaint(double[] v) {
        long key = colorKey(v) << 5 | (long) Math.round(v[AuraLook.V_RIM_DARKEN] * 31);
        if (key == vignetteKey) return;
        vignetteKey = key;
        double k = 1 - v[AuraLook.V_RIM_DARKEN];
        Color rim = Color.color(clamp(v[AuraLook.SR] * k), clamp(v[AuraLook.SG] * k), clamp(v[AuraLook.SB] * k));
        Color primary = Color.color(clamp(v[AuraLook.PR]), clamp(v[AuraLook.PG]), clamp(v[AuraLook.PB]));
        Color inner = rim.interpolate(primary, 0.45);
        vignettePaint = new RadialGradient(0, 0, 0, 0, 1.0, false, CycleMethod.NO_CYCLE,
                new Stop(0.00, withAlpha(rim, 0.0)),
                new Stop(0.50, withAlpha(rim, 0.0)),
                new Stop(0.72, withAlpha(inner, 0.30)),
                new Stop(0.88, withAlpha(rim.interpolate(inner, 0.3), 0.62)),
                new Stop(1.00, withAlpha(rim, 1.0)));
    }

    private void ensureGlowPaints(double[] v) {
        long key = colorKey(v);
        if (key == glowKey) return;
        glowKey = key;
        Color p = Color.color(clamp(v[AuraLook.PR]), clamp(v[AuraLook.PG]), clamp(v[AuraLook.PB]));
        glowPaint = new LinearGradient(0, 0, 0, -1, false, CycleMethod.NO_CYCLE,
                new Stop(0.00, withAlpha(p, 0.95)),
                new Stop(0.25, withAlpha(p, 0.50)),
                new Stop(0.55, withAlpha(p, 0.17)),
                new Stop(1.00, withAlpha(p, 0.0)));
        sidePaint = new LinearGradient(0, 0, 1, 0, false, CycleMethod.NO_CYCLE,
                new Stop(0.00, withAlpha(p, 0.80)),
                new Stop(0.35, withAlpha(p, 0.30)),
                new Stop(1.00, withAlpha(p, 0.0)));
        Color hot = p.interpolate(Color.WHITE, 0.55);
        linePaint = new LinearGradient(0, 0, 0, -1, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, withAlpha(hot, 1.0)),
                new Stop(1.0, withAlpha(hot, 0.0)));
    }

    private void ensureSprites(double[] v) {
        long key = colorKey(v);
        if (key == spriteKey) return;
        spriteKey = key;
        for (int s = 0; s < SPRITE_STEPS; s++) {
            double m = s / (double) (SPRITE_STEPS - 1);
            double r = v[AuraLook.PR] + (v[AuraLook.SR] - v[AuraLook.PR]) * m;
            double g = v[AuraLook.PG] + (v[AuraLook.SG] - v[AuraLook.PG]) * m;
            double b = v[AuraLook.PB] + (v[AuraLook.SB] - v[AuraLook.PB]) * m;
            sprites[s] = renderSprite(clamp(r), clamp(g), clamp(b));
        }
    }

    private static Image renderSprite(double r, double g, double b) {
        WritableImage img = new WritableImage(SPRITE_SIZE, SPRITE_SIZE);
        PixelWriter pw = img.getPixelWriter();
        double c = (SPRITE_SIZE - 1) / 2.0;
        for (int y = 0; y < SPRITE_SIZE; y++) {
            for (int x = 0; x < SPRITE_SIZE; x++) {
                double d = Math.hypot(x - c, y - c) / c;
                if (d >= 1.0) { pw.setArgb(x, y, 0); continue; }
                double alpha = Math.pow(1 - d, 2.2);
                double hot = 0.6 * Math.pow(Math.max(0, 1 - d * 2.5), 2);
                int ia = (int) Math.round(alpha * 255);
                int ir = (int) Math.round((r + (1 - r) * hot) * 255);
                int ig = (int) Math.round((g + (1 - g) * hot) * 255);
                int ib = (int) Math.round((b + (1 - b) * hot) * 255);
                pw.setArgb(x, y, (ia << 24) | (ir << 16) | (ig << 8) | ib);
            }
        }
        return img;
    }

    private static long colorKey(double[] v) {
        long key = 0;
        for (int i = AuraLook.PR; i <= AuraLook.SB; i++) {
            key = (key << 4) | Math.round(clamp(v[i]) * 15);
        }
        return key;
    }

    private static Color withAlpha(Color c, double a) {
        return Color.color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    private static double clamp(double x) {
        return x < 0 ? 0 : Math.min(1.0, x);
    }
}
