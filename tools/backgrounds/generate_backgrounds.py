#!/usr/bin/env python3
"""Procedural default backgrounds for the ambiance window, one per aura preset.

Regenerate (needs python3 + numpy + ffmpeg on PATH), from the repository root:

    python tools/backgrounds/generate_backgrounds.py            # writes src/main/resources/backgrounds/*.png
    python tools/backgrounds/generate_backgrounds.py out_dir    # or into another folder
    python tools/backgrounds/generate_backgrounds.py out_dir fire holy   # only some scenes

Everything is drawn from fixed seeds, so the output is deterministic. All images are 1920x1080
and keep the centre calm: the aura overlay lives at the edges of the screen.
"""
import subprocess
import sys
from pathlib import Path

import numpy as np

W, H = 1920, 1080
PRESETS = ["fire", "shadow", "electric", "holy", "neutral"]
DEFAULT_OUT = Path(__file__).resolve().parents[2] / "src" / "main" / "resources" / "backgrounds"

YY, XX = np.mgrid[0:H, 0:W].astype(np.float64)
Y = YY / (H - 1)          # 0 top .. 1 bottom
X = XX / (W - 1)          # 0 left .. 1 right
ASPECT = W / H


# ---------------------------------------------------------------- helpers

def smoothstep(t):
    t = np.clip(t, 0.0, 1.0)
    return t * t * (3 - 2 * t)


def vgradient(stops):
    """Vertical colour gradient from (position, (r, g, b)) stops; returns H x W x 3."""
    pos = np.array([s[0] for s in stops])
    col = np.array([s[1] for s in stops], dtype=np.float64)
    column = np.stack([np.interp(Y[:, 0], pos, col[:, k]) for k in range(3)], axis=-1)
    return np.repeat(column[:, None, :], W, axis=1)


def vnoise(cells_y, cells_x, rng):
    """Smooth value noise over the whole frame, in [0, 1]."""
    g = rng.random((cells_y + 2, cells_x + 2))
    ys = np.linspace(0, cells_y, H)
    xs = np.linspace(0, cells_x, W)
    iy, ix = ys.astype(int), xs.astype(int)
    fy = smoothstep(ys - iy)[:, None]
    fx = smoothstep(xs - ix)[None, :]
    a = g[iy][:, ix]
    b = g[iy][:, ix + 1]
    c = g[iy + 1][:, ix]
    d = g[iy + 1][:, ix + 1]
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy


def fbm(cells_y, cells_x, seed, octaves=5, gain=0.5):
    rng = np.random.default_rng(seed)
    out = np.zeros((H, W))
    amp, total = 1.0, 0.0
    for o in range(octaves):
        out += amp * vnoise(cells_y * 2 ** o, cells_x * 2 ** o, rng)
        total += amp
        amp *= gain
    return out / total


def ridge(seed, base, amp, octaves=7, roughness=0.55, x_scale=3):
    """Fractal ridge line: y position (in pixels) of the top of a mountain layer, per column."""
    rng = np.random.default_rng(seed)
    xs = np.linspace(0, 1, W)
    h = np.zeros(W)
    a, total = 1.0, 0.0
    for o in range(octaves):
        n = x_scale * 2 ** o
        pts = rng.random(n + 2)
        h += a * np.interp(xs * n, np.arange(n + 2), pts)
        total += a
        a *= roughness
    h = h / total - 0.5
    return base * H + h * amp * 2


def over(img, color, coverage):
    """Composite a flat/array colour over img using a coverage mask (H x W)."""
    cov = coverage[..., None]
    return img * (1 - cov) + np.asarray(color, dtype=np.float64) * cov


def layer_mask(top):
    """Anti-aliased mask of everything below a per-column ridge."""
    return np.clip(YY - top[None, :] + 0.5, 0, 1)


def glow(cx, cy, radius, aspect=ASPECT):
    """Gaussian blob centred at (cx, cy) in 0..1 coordinates."""
    d2 = ((X - cx) * aspect) ** 2 + (Y - cy) ** 2
    return np.exp(-d2 / (radius * radius))


def add_color(img, color, amount):
    return img + np.asarray(color, dtype=np.float64) * amount[..., None]


def calm_center(strength=1.0):
    """1 at the edges, 0 in the middle: used to keep detail away from the centre."""
    d = np.sqrt(((X - 0.5) * 1.1) ** 2 + ((Y - 0.45) * 1.6) ** 2)
    return smoothstep((d - 0.18) / 0.4) * strength + (1 - strength)


def stars(img, seed, count, ymax, brightness=1.0, tint=(255, 255, 255)):
    rng = np.random.default_rng(seed)
    sx = rng.integers(0, W, count)
    sy = (rng.random(count) ** 1.6 * H * ymax).astype(int)
    b = (rng.random(count) ** 2.2 * 0.85 + 0.15) * brightness
    keep = rng.random(count) < calm_center()[sy, sx] * 0.85 + 0.15  # fewer stars in the middle
    for x, y, v in zip(sx[keep], sy[keep], b[keep]):
        fade = 1.0 - y / (H * ymax + 1)
        img[y, x] = np.maximum(img[y, x], np.asarray(tint) * v * fade)
    return img


def mountains(img, layers):
    """layers: (seed, base, amp, color_top, color_bottom, roughness) back to front."""
    for seed, base, amp, c_top, c_bot, rough in layers:
        top = ridge(seed, base, amp, roughness=rough)
        mask = layer_mask(top)
        depth = np.clip((YY - top[None, :]) / (H * 0.18), 0, 1)[..., None]
        color = np.asarray(c_top) * (1 - depth) + np.asarray(c_bot) * depth
        img = img * (1 - mask[..., None]) + color * mask[..., None]
    return img


def write_png(img, path):
    data = np.clip(np.rint(img), 0, 255).astype(np.uint8)
    path.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}",
         "-i", "-", "-frames:v", "1", "-compression_level", "9", str(path)],
        input=data.tobytes(), check=True)


# ---------------------------------------------------------------- scenes

def fire():
    rng = np.random.default_rng(7)
    img = vgradient([(0.0, (14, 8, 32)), (0.35, (56, 22, 70)), (0.58, (150, 46, 70)),
                     (0.72, (238, 112, 52)), (0.86, (252, 176, 92)), (1.0, (255, 210, 130))])
    sun = glow(0.64, 0.74, 0.07)
    img = add_color(img, (255, 214, 150), sun * 0.95)
    img = add_color(img, (255, 120, 50), glow(0.64, 0.76, 0.30) * 0.30)
    img = add_color(img, (200, 60, 30), glow(0.64, 0.80, 0.75) * 0.18)
    # faint dusk clouds, streaked horizontally, catching the sunset on their undersides
    clouds = fbm(3, 7, 31, 5)
    clouds = smoothstep((clouds - 0.52) / 0.2) * smoothstep(1 - np.abs(Y - 0.45) / 0.22) * calm_center(0.7)
    img = over(img, (232, 96, 70), clouds * 0.30)
    img = stars(img, 5, 1500, 0.5, 0.9)
    img = mountains(img, [
        (11, 0.70, 70, (96, 38, 70), (62, 24, 56), 0.55),
        (23, 0.77, 58, (66, 24, 58), (40, 15, 44), 0.55),
        (37, 0.86, 48, (36, 13, 38), (22, 8, 28), 0.6),
        (41, 0.94, 32, (14, 6, 20), (8, 4, 14), 0.6),
    ])
    return img


def pine_profile(seed, base, height, count, width):
    """Treeline: per-column top (px) made of overlapping triangular pines."""
    rng = np.random.default_rng(seed)
    top = np.full(W, base * H)
    xs = np.arange(W)
    for _ in range(count):
        cx = rng.random() * W
        hh = height * (0.45 + rng.random() * 0.75)
        ww = width * (0.6 + rng.random() * 0.8)
        u = np.clip(1 - np.abs(xs - cx) / ww, 0, 1)
        tier = 1 - 0.10 * np.mod(u * 7, 1.0)  # stepped branches along the flank
        spike = base * H - hh * u ** 1.15 * tier
        top = np.minimum(top, spike)
    return top


def shadow():
    img = vgradient([(0.0, (3, 2, 10)), (0.4, (18, 10, 42)), (0.72, (44, 24, 82)), (1.0, (72, 40, 110))])
    mx, my, mr = 0.72, 0.27, 56.0
    d = np.sqrt((XX - mx * W) ** 2 + (YY - my * H) ** 2)
    img = add_color(img, (150, 130, 235), np.exp(-(d / 420) ** 2) * 0.22)
    img = add_color(img, (190, 175, 255), np.exp(-(d / 150) ** 2) * 0.28)
    img = stars(img, 9, 1300, 0.62, 0.65, (210, 205, 255))
    # thin violet cloud wisps lit by the moon
    wisps = fbm(2, 5, 77, 4)
    wisps = smoothstep((wisps - 0.55) / 0.2) * smoothstep(1 - np.abs(Y - 0.3) / 0.22) * calm_center(0.8)
    img = over(img, (96, 76, 170), wisps * 0.12 * (0.5 + 0.5 * np.exp(-(d / 700) ** 2)))
    # moon disc: pale, with soft maria shading and a lit limb
    disc = np.clip(mr + 0.5 - d, 0, 1)
    nx = (XX - mx * W) / mr
    ny = (YY - my * H) / mr
    shade = 0.80 + 0.20 * fbm(6, 10, 4, 4)
    limb = np.clip(1.0 - 0.25 * (nx * nx + ny * ny), 0.7, 1.0)
    moon = np.stack([238 * shade, 236 * shade, 252 * shade], -1) * limb[..., None]
    img = img * (1 - disc[..., None]) + moon * disc[..., None]
    # distant mountains, then two forest layers
    img = mountains(img, [
        (53, 0.72, 80, (46, 28, 86), (30, 18, 62), 0.55),
        (59, 0.80, 60, (28, 16, 56), (18, 10, 40), 0.55),
    ])
    for seed, base, hgt, cnt, wid, c_top, c_bot in [
        (71, 0.88, 120, 150, 50, (11, 6, 24), (8, 4, 18)),
        (73, 0.96, 160, 110, 70, (4, 2, 10), (3, 1, 7)),
    ]:
        top = pine_profile(seed, base, hgt, cnt, wid)
        mask = layer_mask(top)
        depth = np.clip((YY - top[None, :]) / (H * 0.12), 0, 1)[..., None]
        color = np.asarray(c_top) * (1 - depth) + np.asarray(c_bot) * depth
        img = img * (1 - mask[..., None]) + color * mask[..., None]
    # low violet mist between the layers
    mist = fbm(2, 6, 91, 4)
    mist = smoothstep((mist - 0.4) / 0.4) * smoothstep(1 - np.abs(Y - 0.84) / 0.07)
    img = over(img, (70, 44, 120), mist * 0.14)
    return img


def bolt(glow_buf, rng, x0, y0, y1, drift, width=2.2):
    """Adds a jagged lightning bolt (plus one branch) to a float buffer."""
    pts = [(x0, y0)]
    x, y = x0, y0
    while y < y1:
        y += rng.uniform(18, 46)
        x += rng.uniform(-30, 30) + drift
        pts.append((x, y))
    branch_at = len(pts) // 3
    bx, by = pts[branch_at]
    branch = [(bx, by)]
    for _ in range(5):
        by += rng.uniform(16, 34)
        bx += rng.uniform(10, 40) * np.sign(drift if drift else 1)
        branch.append((bx, by))

    def draw(poly, scale):
        for (xa, ya), (xb, yb) in zip(poly, poly[1:]):
            n = int(max(abs(xb - xa), abs(yb - ya))) + 1
            for t in np.linspace(0, 1, n):
                px, py = xa + (xb - xa) * t, ya + (yb - ya) * t
                r = int(width * 6 * scale) + 2
                x_lo, x_hi = max(int(px) - r, 0), min(int(px) + r + 1, W)
                y_lo, y_hi = max(int(py) - r, 0), min(int(py) + r + 1, H)
                if x_lo >= x_hi or y_lo >= y_hi:
                    continue
                gx = XX[y_lo:y_hi, x_lo:x_hi] - px
                gy = YY[y_lo:y_hi, x_lo:x_hi] - py
                glow_buf[y_lo:y_hi, x_lo:x_hi] = np.maximum(
                    glow_buf[y_lo:y_hi, x_lo:x_hi],
                    np.exp(-(gx * gx + gy * gy) / (2 * (width * scale) ** 2)))

    draw(pts, 1.0)
    draw(branch, 0.7)
    return glow_buf


def electric():
    img = vgradient([(0.0, (3, 8, 18)), (0.4, (8, 28, 52)), (0.7, (16, 56, 86)), (1.0, (26, 78, 104))])
    edge = calm_center(0.85)
    # two cloud decks: a dark heavy one and a brighter teal one with lit edges
    deck_a = fbm(3, 5, 101, 6)
    deck_b = fbm(4, 7, 202, 6)
    a = smoothstep((deck_a - 0.45) / 0.25) * smoothstep(1 - Y / 0.62) * edge
    b = smoothstep((deck_b - 0.5) / 0.22) * smoothstep(1 - np.abs(Y - 0.3) / 0.3) * edge
    img = over(img, (4, 12, 26), a * 0.9)
    rim = np.clip(b - smoothstep((deck_b - 0.58) / 0.22) * smoothstep(1 - np.abs(Y - 0.3) / 0.3) * edge, 0, 1)
    img = over(img, (34, 104, 138), b * 0.55)
    img = over(img, (96, 190, 220), rim * 0.40)
    # lightning, hugging the left and right edges
    rng = np.random.default_rng(404)
    bolts = np.zeros((H, W))
    bolt(bolts, rng, 0.10 * W, 0.12 * H, 0.80 * H, 2.0)
    bolt(bolts, rng, 0.90 * W, 0.08 * H, 0.84 * H, -2.5)
    halo = np.zeros((H, W))
    for cx, cy in [(0.10, 0.16), (0.90, 0.14)]:
        halo += glow(cx, cy, 0.20)
    img = add_color(img, (90, 170, 230), halo * 0.20)
    img = add_color(img, (150, 210, 250), np.clip(bolts, 0, 1) * 0.42)
    img = add_color(img, (60, 140, 220), glow_blur(bolts) * 0.35)
    # dark ridgeline with a cold rim light
    top1 = ridge(311, 0.86, 90, roughness=0.6)
    top2 = ridge(317, 0.94, 60, roughness=0.62)
    m1 = layer_mask(top1)
    m2 = layer_mask(top2)
    img = over(img, (10, 22, 38), m1)
    rimmask = np.clip(1 - np.abs(YY - top1[None, :]) / 3.0, 0, 1) * m1.clip(0.3, 1) * 0.35
    img = over(img, (80, 170, 210), rimmask * edge)
    img = over(img, (4, 8, 14), m2)
    return img


def glow_blur(buf):
    """Cheap wide blur (separable box x3) used for bolt halos."""
    out = buf.copy()
    for _ in range(3):
        for axis in (0, 1):
            k = 31
            c = np.cumsum(np.pad(out, [(k // 2 + 1, k // 2) if a == axis else (0, 0) for a in (0, 1)], mode="edge"), axis=axis)
            out = (np.take(c, np.arange(k, c.shape[axis]), axis=axis) - np.take(c, np.arange(0, c.shape[axis] - k), axis=axis)) / k
    return out


def holy():
    img = vgradient([(0.0, (128, 150, 196)), (0.28, (196, 190, 206)), (0.52, (246, 220, 184)),
                     (0.72, (255, 214, 140)), (1.0, (255, 232, 176))])
    sx, sy = 0.30, 0.72
    img = add_color(img, (255, 226, 170), glow(sx, sy, 0.09) * 0.55)
    img = add_color(img, (255, 200, 120), glow(sx, sy, 0.45) * 0.28)
    # gentle rays fanning out from just below the horizon
    ang = np.arctan2(YY - sy * H, (XX - sx * W))
    rng = np.random.default_rng(55)
    n = 36
    ray = np.zeros((H, W))
    for _ in range(n):
        a0 = rng.uniform(-np.pi, 0)
        wd = rng.uniform(0.015, 0.05)
        ray += rng.uniform(0.4, 1.0) * np.exp(-((ang - a0) / wd) ** 2)
    dist = np.sqrt(((XX - sx * W) ** 2) + ((YY - sy * H) * 1.0) ** 2) / W
    ray *= np.exp(-dist * 1.4) * smoothstep((sy - Y) / 0.06) * calm_center(0.85)
    img = add_color(img, (255, 240, 200), np.clip(ray, 0, 1) * 0.07)
    # high thin clouds, tinted gold
    cl = fbm(2, 6, 88, 5)
    cl = smoothstep((cl - 0.52) / 0.2) * smoothstep(1 - np.abs(Y - 0.34) / 0.2) * calm_center(0.75)
    img = over(img, (255, 240, 214), cl * 0.30)
    # pale distant hills, lighter and warmer toward the viewer
    img = mountains(img, [
        (61, 0.78, 38, (236, 214, 188), (246, 226, 192), 0.5),
        (67, 0.84, 34, (226, 198, 170), (240, 214, 180), 0.5),
        (71, 0.91, 30, (214, 184, 156), (230, 200, 168), 0.5),
        (79, 0.97, 22, (198, 166, 142), (214, 184, 152), 0.5),
    ])
    return img


def neutral():
    img = vgradient([(0.0, (66, 78, 94)), (0.4, (104, 118, 134)), (0.7, (148, 160, 172)), (1.0, (170, 180, 190))])
    img = add_color(img, (190, 200, 212), glow(0.5, 0.62, 0.55) * 0.20)
    # pale disc barely visible through the haze
    img = add_color(img, (210, 218, 226), glow(0.66, 0.30, 0.05) * 0.18)
    specs = [(0.58, 50, (122, 136, 154)), (0.66, 56, (104, 119, 138)),
             (0.75, 60, (88, 103, 124)), (0.85, 62, (72, 88, 108)), (0.95, 48, (56, 70, 88))]
    fog_color = np.array([176, 188, 200], dtype=np.float64)
    for i, (base, amp, col) in enumerate(specs):
        top = ridge(150 + i * 7, base, amp, roughness=0.5)
        mask = layer_mask(top)
        depth = np.clip((YY - top[None, :]) / (H * 0.2), 0, 1)[..., None]
        color = np.asarray(col, dtype=np.float64) * (1 - depth * 0.25)
        color = color * (0.80 + 0.2 * depth) + fog_color * 0.0
        img = img * (1 - mask[..., None]) + color * mask[..., None]
        # fog bank lying in front of this layer: wide, soft band modulated slowly along x
        xmod = np.interp(np.linspace(0, 4, W), np.arange(6), np.random.default_rng(500 + i).random(6))
        center = base + 0.06
        band = np.exp(-((Y - center) / 0.045) ** 2) * (0.35 + 0.65 * xmod[None, :])
        img = over(img, fog_color, band * 0.50)
    return img


SCENES = {"fire": fire, "shadow": shadow, "electric": electric, "holy": holy, "neutral": neutral}


def main(argv):
    out = Path(argv[1]) if len(argv) > 1 else DEFAULT_OUT
    names = argv[2:] or PRESETS
    for name in names:
        path = out / f"{name}.png"
        write_png(SCENES[name](), path)
        print(f"{path} ({path.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main(sys.argv)
