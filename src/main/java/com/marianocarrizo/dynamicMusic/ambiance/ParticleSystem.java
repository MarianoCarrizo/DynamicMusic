package com.marianocarrizo.dynamicMusic.ambiance;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleSupplier;

/**
 * Audio-reactive particle effect rendered on a transparent Canvas.
 * Each EffectType defines its own spawn, update, and draw behavior.
 * Amplitude (0.0–1.0) scales spawn count, velocity, and particle size each frame.
 */
public class ParticleSystem extends AnimationTimer {

    // Particle slot indices
    private static final int X        = 0;
    private static final int Y        = 1;
    private static final int VX       = 2;
    private static final int VY       = 3;
    private static final int LIFE     = 4;
    private static final int MAX_LIFE = 5;
    private static final int SZ       = 6;
    private static final int R        = 7;
    private static final int G        = 8;
    private static final int B        = 9;
    private static final int A        = 10;
    // Effect-specific extra slot (hue for PLASMA, splatter flag for BLOOD_RAIN, phase for AURORA)
    private static final int EXTRA    = 11;
    private static final int SLOTS    = 12;

    // Shockwave ring: [centerX, centerY, currentRadius, maxRadius, alpha]
    private static final int RX      = 0;
    private static final int RY      = 1;
    private static final int RRADIUS = 2;
    private static final int RMAX    = 3;
    private static final int RALPHA  = 4;
    private static final int RING_SLOTS = 5;

    private final Canvas canvas;
    private final GraphicsContext gc;
    private final EffectType effect;
    private final DoubleSupplier amplitudeSupplier;
    private final List<double[]> particles = new ArrayList<>(300);
    private final List<double[]> rings     = new ArrayList<>(10);
    private final Random rng = new Random();
    private long lastNanos = -1;

    // PLASMA: global hue rotates each frame
    private double plasmaHue = 0.0;
    // SHOCKWAVE: detect amplitude spikes
    private double prevAmplitude = 0.0;

    public ParticleSystem(Canvas canvas, EffectType effect, DoubleSupplier amplitudeSupplier) {
        this.canvas            = canvas;
        this.gc                = canvas.getGraphicsContext2D();
        this.effect            = effect;
        this.amplitudeSupplier = amplitudeSupplier;
    }

    @Override
    public void handle(long now) {
        if (lastNanos < 0) { lastNanos = now; return; }
        double dt = Math.min((now - lastNanos) / 1_000_000_000.0, 0.05);
        lastNanos = now;

        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;

        double amplitude = amplitudeSupplier.getAsDouble();

        // Spawn new particles toward amplitude-scaled target count
        int target  = (int)(baseCount() * (1.0 + amplitude * 2.0));
        int toSpawn = Math.min(target - particles.size(), (int)(5 + amplitude * 15));
        for (int i = 0; i < toSpawn; i++) {
            particles.add(spawn(w, h, amplitude));
        }

        // SHOCKWAVE: emit a fast ring on amplitude spike, slow background pulses always
        if (effect == EffectType.SHOCKWAVE) {
            if (amplitude > 0.5 && prevAmplitude <= 0.5) {
                rings.add(makeRing(w, h, Math.max(w, h) * 0.6, 0.9));
            }
            if (rng.nextDouble() < 0.01) {
                rings.add(makeRing(w, h, Math.max(w, h) * 0.4, 0.12 + amplitude * 0.08));
            }
        }
        prevAmplitude = amplitude;

        gc.clearRect(0, 0, w, h);

        // ELECTRIC: draw random lightning lines behind particles
        if (effect == EffectType.ELECTRIC && amplitude > 0.1) {
            int lineCount = (int)(amplitude * 5);
            for (int i = 0; i < lineCount; i++) {
                gc.setStroke(Color.color(0.5, 0.8, 1.0, amplitude * 0.8));
                gc.setLineWidth(1.5);
                gc.strokeLine(
                        rng.nextDouble() * w, rng.nextDouble() * h,
                        rng.nextDouble() * w, rng.nextDouble() * h);
            }
        }

        // Update and draw particles
        Iterator<double[]> it = particles.iterator();
        while (it.hasNext()) {
            double[] p = it.next();
            updateParticle(p, dt, w, h, amplitude);
            if (p[LIFE] <= 0) { it.remove(); continue; }
            drawParticle(p);
        }

        // Draw shockwave rings
        if (effect == EffectType.SHOCKWAVE) {
            Iterator<double[]> ri = rings.iterator();
            while (ri.hasNext()) {
                double[] ring = ri.next();
                ring[RRADIUS] += dt * (120.0 + amplitude * 250.0);
                ring[RALPHA]  *= 0.97;
                if (ring[RRADIUS] > ring[RMAX] || ring[RALPHA] < 0.01) {
                    ri.remove();
                    continue;
                }
                gc.setStroke(Color.color(0.8, 0.6, 1.0, ring[RALPHA]));
                gc.setLineWidth(2.0);
                double r = ring[RRADIUS];
                gc.strokeOval(ring[RX] - r, ring[RY] - r, r * 2, r * 2);
            }
        }

        // Advance per-effect global state
        if (effect == EffectType.PLASMA) {
            plasmaHue = (plasmaHue + dt * 60.0 * (1.0 + amplitude * 2.0)) % 360.0;
        }

        // VOID: dark pulse on high amplitude
        if (effect == EffectType.VOID && amplitude > 0.6) {
            gc.setFill(Color.color(0, 0, 0, amplitude * 0.3));
            gc.fillRect(0, 0, w, h);
        }
    }

    // -------------------------------------------------------------------------
    // Spawn
    // -------------------------------------------------------------------------

    private int baseCount() {
        return switch (effect) {
            case FIRE       -> 150;
            case ELECTRIC   -> 80;
            case AURORA     -> 60;
            case VOID       -> 35;
            case BLOOD_RAIN -> 70;
            case PLASMA     -> 100;
            case MAELSTROM  -> 90;
            case SHOCKWAVE  -> 30;
        };
    }

    private double[] spawn(double w, double h, double amplitude) {
        double[] p = new double[SLOTS];
        switch (effect) {
            case FIRE -> {
                p[X]       = w * 0.5 + (rng.nextDouble() - 0.5) * w;
                p[Y]       = h;
                double vy  = (80.0 + rng.nextDouble() * 130.0) * (1.0 + amplitude * 3.0);
                p[VX]      = (rng.nextDouble() - 0.5) * 60.0 * (1.0 + amplitude * 3.0);
                p[VY]      = -vy;
                p[MAX_LIFE] = 0.5 + rng.nextDouble() * 1.0;
                p[SZ]      = (4.0 + rng.nextDouble() * 12.0) * (1.0 + amplitude * 1.5);
                p[R]       = 0.9 + rng.nextDouble() * 0.1;
                p[G]       = 0.6 + rng.nextDouble() * 0.3;
                p[B]       = 0;
                p[A]       = 0.7 + rng.nextDouble() * 0.3;
            }
            case ELECTRIC -> {
                p[X]  = rng.nextDouble() * w;
                p[Y]  = rng.nextDouble() * h;
                double angle = rng.nextDouble() * Math.PI * 2.0;
                double speed = (60.0 + rng.nextDouble() * 100.0) * (1.0 + amplitude * 3.0);
                p[VX] = Math.cos(angle) * speed;
                p[VY] = Math.sin(angle) * speed;
                p[MAX_LIFE] = 0.08 + rng.nextDouble() * 0.18;
                p[SZ] = (2.0 + rng.nextDouble() * 6.0) * (1.0 + amplitude * 1.5);
                double bright = 0.7 + rng.nextDouble() * 0.3;
                p[R]  = bright * (0.5 + rng.nextDouble() * 0.5);
                p[G]  = bright * (0.8 + rng.nextDouble() * 0.2);
                p[B]  = 1.0;
                p[A]  = 0.8 + rng.nextDouble() * 0.2;
            }
            case AURORA -> {
                p[X]  = rng.nextDouble() * w;
                p[Y]  = h + 10;
                p[VX] = (rng.nextDouble() - 0.5) * 15.0 * (1.0 + amplitude * 2.0);
                p[VY] = -(5.0 + rng.nextDouble() * 20.0) * (1.0 + amplitude * 2.0);
                p[MAX_LIFE] = 4.0 + rng.nextDouble() * 4.0;
                p[SZ] = (8.0 + rng.nextDouble() * 20.0) * (1.0 + amplitude * 1.5);
                int choice = rng.nextInt(3);
                if (choice == 0) {
                    p[R] = 0.05; p[G] = 0.6 + rng.nextDouble() * 0.4; p[B] = 0.2 + rng.nextDouble() * 0.3;
                } else if (choice == 1) {
                    p[R] = 0.0;  p[G] = 0.7 + rng.nextDouble() * 0.3; p[B] = 0.6 + rng.nextDouble() * 0.4;
                } else {
                    p[R] = 0.4 + rng.nextDouble() * 0.3; p[G] = 0.1; p[B] = 0.7 + rng.nextDouble() * 0.3;
                }
                p[A]    = (0.12 + rng.nextDouble() * 0.18) * (1.0 + amplitude * 0.5);
                p[EXTRA] = rng.nextDouble() * Math.PI * 2.0; // per-particle wave phase
            }
            case VOID -> {
                p[X]  = w * 0.15 + rng.nextDouble() * w * 0.7;
                p[Y]  = h;
                p[VX] = (rng.nextDouble() - 0.5) * 10.0;
                // Occasionally a particle falls downward (reverse gravity smoke)
                p[VY] = rng.nextDouble() < 0.15
                        ? (5.0 + rng.nextDouble() * 10.0)
                        : -(3.0 + rng.nextDouble() * 12.0);
                p[MAX_LIFE] = 5.0 + rng.nextDouble() * 6.0;
                p[SZ] = (14.0 + rng.nextDouble() * 25.0) * (1.0 + amplitude * 1.5);
                double grey = 0.3 + rng.nextDouble() * 0.2;
                p[R] = p[G] = p[B] = grey;
                p[A]  = 0.04 + rng.nextDouble() * 0.06;
            }
            case BLOOD_RAIN -> {
                p[X]  = rng.nextDouble() * w;
                p[Y]  = -5;
                p[VX] = (rng.nextDouble() - 0.5) * 15.0;
                double vy2 = (80.0 + rng.nextDouble() * 100.0) * (1.0 + amplitude * 3.0);
                p[VY] = vy2;
                p[MAX_LIFE] = (h + 10.0) / vy2 + 0.3;
                p[SZ] = (2.0 + rng.nextDouble() * 5.0) * (1.0 + amplitude * 1.5);
                p[R]  = 0.7 + rng.nextDouble() * 0.3;
                p[G]  = 0;
                p[B]  = 0;
                p[A]  = 0.6 + rng.nextDouble() * 0.4;
                p[EXTRA] = 0; // 0 = raindrop, 1 = splatter
            }
            case PLASMA -> {
                double cx = w * 0.5, cy = h * 0.5;
                double angle2 = rng.nextDouble() * Math.PI * 2.0;
                double dist   = rng.nextDouble() * 30.0;
                p[X]  = cx + Math.cos(angle2) * dist;
                p[Y]  = cy + Math.sin(angle2) * dist;
                double spd = (50.0 + rng.nextDouble() * 80.0) * (1.0 + amplitude * 2.0);
                double tang = angle2 + Math.PI * 0.5; // tangential → spiral
                p[VX] = Math.cos(tang) * spd + Math.cos(angle2) * 30.0;
                p[VY] = Math.sin(tang) * spd + Math.sin(angle2) * 30.0;
                p[MAX_LIFE] = 1.0 + rng.nextDouble() * 2.0;
                p[SZ] = (2.0 + rng.nextDouble() * 6.0) * (1.0 + amplitude * 1.5);
                p[EXTRA] = (plasmaHue + rng.nextDouble() * 60.0) % 360.0;
                Color c = Color.hsb(p[EXTRA], 1.0, 1.0);
                p[R] = c.getRed(); p[G] = c.getGreen(); p[B] = c.getBlue();
                p[A] = 0.6 + rng.nextDouble() * 0.4;
            }
            case MAELSTROM -> {
                // Spawn on a random screen edge
                int edge = rng.nextInt(4);
                if      (edge == 0) { p[X] = rng.nextDouble() * w; p[Y] = 0; }
                else if (edge == 1) { p[X] = w;                    p[Y] = rng.nextDouble() * h; }
                else if (edge == 2) { p[X] = rng.nextDouble() * w; p[Y] = h; }
                else                { p[X] = 0;                    p[Y] = rng.nextDouble() * h; }
                double dx = w * 0.5 - p[X];
                double dy = h * 0.5 - p[Y];
                double dist2 = Math.max(1.0, Math.sqrt(dx * dx + dy * dy));
                double spd3  = (50.0 + rng.nextDouble() * 80.0) * (1.0 + amplitude * 3.0);
                // 70% inward + 30% tangential for a spiral look
                double tangX = -dy / dist2;
                double tangY =  dx / dist2;
                p[VX] = (dx / dist2) * spd3 * 0.7 + tangX * spd3 * 0.3;
                p[VY] = (dy / dist2) * spd3 * 0.7 + tangY * spd3 * 0.3;
                p[MAX_LIFE] = 1.5 + rng.nextDouble() * 2.0;
                p[SZ] = (3.0 + rng.nextDouble() * 8.0) * (1.0 + amplitude * 1.5);
                p[R]  = 0.2 + rng.nextDouble() * 0.2;
                p[G]  = 0;
                p[B]  = 0.5 + rng.nextDouble() * 0.5;
                p[A]  = 0.5 + rng.nextDouble() * 0.5;
            }
            case SHOCKWAVE -> {
                // Ambient ember particles as secondary layer
                p[X]  = rng.nextDouble() * w;
                p[Y]  = h * 0.5 + (rng.nextDouble() - 0.5) * h * 0.8;
                double ang3 = rng.nextDouble() * Math.PI * 2.0;
                double spd4 = 10.0 + rng.nextDouble() * 30.0;
                p[VX] = Math.cos(ang3) * spd4;
                p[VY] = Math.sin(ang3) * spd4;
                p[MAX_LIFE] = 1.5 + rng.nextDouble() * 2.5;
                p[SZ] = 2.0 + rng.nextDouble() * 5.0;
                p[R]  = 0.6 + rng.nextDouble() * 0.4;
                p[G]  = 0.2 + rng.nextDouble() * 0.3;
                p[B]  = 0.8 + rng.nextDouble() * 0.2;
                p[A]  = 0.3 + rng.nextDouble() * 0.3;
            }
        }
        p[LIFE] = p[MAX_LIFE];
        return p;
    }

    // -------------------------------------------------------------------------
    // Update
    // -------------------------------------------------------------------------

    private void updateParticle(double[] p, double dt, double w, double h, double amplitude) {
        p[LIFE] -= dt;
        p[X]    += p[VX] * dt;
        p[Y]    += p[VY] * dt;

        switch (effect) {
            case FIRE -> {
                // Strong horizontal flicker, scales hard with amplitude
                p[VX] += (rng.nextDouble() - 0.5) * 40.0 * (1.0 + amplitude * 3.0);
                p[VX] *= 0.95;
                p[SZ] *= 0.993;
                // Shift green toward 0 → particle ages from orange to dark red
                double ratio = p[LIFE] / p[MAX_LIFE];
                p[G] = ratio * 0.35;
                if (ratio < 0.3) p[R] = 0.5 + ratio * 1.5; // darker red at end of life
            }
            case ELECTRIC -> {
                p[VX] *= 0.88;
                p[VY] *= 0.88;
            }
            case AURORA -> {
                // Sinusoidal horizontal drift based on position and per-particle phase
                p[EXTRA] += dt * 1.5 * (1.0 + amplitude);
                p[VX]     = Math.sin(p[EXTRA] + p[Y] * 0.008) * 20.0 * (1.0 + amplitude);
                p[SZ]    *= 0.998;
            }
            case VOID -> {
                p[SZ] += dt * 3.0;
                p[VX] += (rng.nextDouble() - 0.5) * 2.0;
                p[VX] *= 0.99;
            }
            case BLOOD_RAIN -> {
                p[VY] += 200.0 * dt; // gravity
                // Convert to splatter droplet when the raindrop reaches the bottom
                if (p[EXTRA] == 0 && p[Y] >= h - 5) {
                    p[EXTRA]    = 1;
                    p[VY]       = -(30.0 + rng.nextDouble() * 60.0);
                    p[VX]       = (rng.nextDouble() - 0.5) * 120.0;
                    p[LIFE]     = 0.15 + rng.nextDouble() * 0.2;
                    p[MAX_LIFE] = p[LIFE];
                    p[SZ]      *= 0.4;
                }
            }
            case PLASMA -> {
                // Hue rotates per-particle faster at high amplitude
                p[EXTRA] = (p[EXTRA] + dt * 120.0 * (1.0 + amplitude * 2.0)) % 360.0;
                Color c  = Color.hsb(p[EXTRA], 1.0, 1.0);
                p[R] = c.getRed(); p[G] = c.getGreen(); p[B] = c.getBlue();
                // Mild friction; amplitude slightly extends velocity
                double friction = 0.985 + amplitude * 0.005;
                p[VX] *= friction;
                p[VY] *= friction;
            }
            case MAELSTROM -> {
                double dx   = w * 0.5 - p[X];
                double dy   = h * 0.5 - p[Y];
                double dist = Math.max(1.0, Math.sqrt(dx * dx + dy * dy));
                if (dist < 8) { p[LIFE] = 0; break; } // absorbed at center
                double accel = (150.0 + amplitude * 400.0) * dt;
                p[VX] += (dx / dist) * accel;
                p[VY] += (dy / dist) * accel;
                p[VX] *= 0.96;
                p[VY] *= 0.96;
                if (dist < 80) {
                    p[B] = Math.min(1.0, p[B] + 0.02); // glow near center
                }
            }
            case SHOCKWAVE -> {
                p[VX] *= 0.99;
                p[VY] *= 0.99;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Draw
    // -------------------------------------------------------------------------

    private void drawParticle(double[] p) {
        double t     = Math.max(0, p[LIFE] / p[MAX_LIFE]);
        double alpha = p[A] * (t < 0.15 ? t / 0.15 : t);
        if (alpha <= 0.01) return;
        gc.setFill(Color.color(
                Math.min(1, Math.max(0, p[R])),
                Math.min(1, Math.max(0, p[G])),
                Math.min(1, Math.max(0, p[B])),
                Math.min(1, alpha)));
        double sz = Math.max(1, p[SZ]);
        gc.fillOval(p[X] - sz * 0.5, p[Y] - sz * 0.5, sz, sz);
    }

    // -------------------------------------------------------------------------
    // Shockwave ring factory
    // -------------------------------------------------------------------------

    private double[] makeRing(double w, double h, double maxRadius, double alpha) {
        double[] ring = new double[RING_SLOTS];
        ring[RX]      = w * 0.5;
        ring[RY]      = h * 0.5;
        ring[RRADIUS] = 10;
        ring[RMAX]    = maxRadius;
        ring[RALPHA]  = alpha;
        return ring;
    }

    // -------------------------------------------------------------------------
    // Stop
    // -------------------------------------------------------------------------

    @Override
    public void stop() {
        super.stop();
        lastNanos = -1;
        particles.clear();
        rings.clear();
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w > 0 && h > 0) gc.clearRect(0, 0, w, h);
    }
}
