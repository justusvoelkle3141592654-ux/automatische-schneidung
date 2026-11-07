#!/usr/bin/env python3
"""Mittelformat-Look-Annaeherung fuer Kaminfeuer-Fotos.

Pipeline pro Bild (alle Parameter in config.json, pro Bild ueberschreibbar):
  1. JPEG laden, sRGB-Transferkurve entfernen -> lineare Werte (float32).
     Farbraum bleibt der des Originals (hier Display P3); das ICC-Profil wird
     in die Ausgaben uebernommen.
  2. Relative Tiefe mit Depth Anything V2 (transformers), kantengetreu auf
     volle Aufloesung gebracht (Guided Filter mit dem Bild als Fuehrung).
  3. Fokusebene: automatisch die schaerfste texturierte Region (nicht
     ausgefressen) oder per config (focus_x/focus_y).
  4. Schaerfentiefe: geschichtete Scheiben-Unschaerfe (Kreisblende) in
     linearem Licht, Ebenen von hinten nach vorn zusammengesetzt,
     normalisiert (keine dunklen Saeume, keine Farbe aus dem scharfen
     Vordergrund im unscharfen Hintergrund).
  5. Ton/Farbe: Lichter-Schulter auf Luminanz (Farbton bleibt), Begrenzung
     ueber den Maximalkanal, Chroma-Daempfung fuer Rot/Orange in OKLab,
     leichte Schattenanhebung und sanfte S-Kurve auf OKLab-L.
  6. Ausgabe: JPEG q95 (EXIF + ICC), TIFF 16 bit (ICC), Debug-PNGs.

Das ist eine Annaeherung an einen Look, keine Reproduktion einer Kamera.
"""
import argparse
import io
import json
import math
import sys
import time
from pathlib import Path

import cv2
import numpy as np
import tifffile
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
MODEL_ID = "depth-anything/Depth-Anything-V2-Base-hf"

# Display-P3 (D65) linear -> XYZ. Fuer sRGB-Originale wird BT.709 verwendet.
M_P3_XYZ = np.array([[0.4865709, 0.2656677, 0.1982173],
                     [0.2289746, 0.6917385, 0.0792869],
                     [0.0000000, 0.0451134, 1.0439444]], np.float32)
M_709_XYZ = np.array([[0.4124564, 0.3575761, 0.1804375],
                      [0.2126729, 0.7151522, 0.0721750],
                      [0.0193339, 0.1191920, 0.9503041]], np.float32)
M_XYZ_LMS = np.array([[0.8189330101, 0.3618667424, -0.1288597137],
                      [0.0329845436, 0.9293118715, 0.0361456387],
                      [0.0482003018, 0.2643662691, 0.6338517070]], np.float32)
M_LMS_LAB = np.array([[0.2104542553, 0.7936177850, -0.0040720468],
                      [1.9779984951, -2.4285922050, 0.4505937099],
                      [0.0259040371, 0.7827717662, -0.8086757660]], np.float32)


# ----------------------------------------------------------------- helpers
def srgb_to_linear(v):
    return np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4).astype(np.float32)


def linear_to_srgb(v):
    v = np.clip(v, 0.0, 1.0)
    return np.where(v <= 0.0031308, v * 12.92, 1.055 * np.power(v, 1 / 2.4) - 0.055).astype(np.float32)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def box(x, r):
    return cv2.boxFilter(x, -1, (2 * r + 1, 2 * r + 1), borderType=cv2.BORDER_REFLECT)


def guided_filter(guide, src, r, eps):
    """Guided Filter (He et al.) mit Graustufen-Fuehrungsbild."""
    mI, mp = box(guide, r), box(src, r)
    cov = box(guide * src, r) - mI * mp
    var = box(guide * guide, r) - mI * mI
    a = cov / (var + eps)
    b = mp - a * mI
    return box(a, r) * guide + box(b, r)


def disc_kernel(radius, ss=4):
    """Antialiaster Kreis (Kreisblende), Summe 1."""
    R = int(math.ceil(radius))
    n = 2 * R + 1
    c = (np.arange(n * ss) + 0.5) / ss - (R + 0.5)
    yy, xx = np.meshgrid(c, c, indexing="ij")
    m = (xx * xx + yy * yy <= radius * radius).astype(np.float32)
    k = m.reshape(n, ss, n, ss).mean(axis=(1, 3))
    return k / k.sum()


def disc_blur(img, radius, min_work_radius=12.0):
    """Scheibenfaltung; grosse Radien werden verkleinert gerechnet (Bokeh ist tieffrequent)."""
    if radius < 0.6:
        return img.copy()
    h, w = img.shape[:2]
    scale = min(1.0, min_work_radius / radius)
    if scale < 1.0:
        sw, sh = max(8, round(w * scale)), max(8, round(h * scale))
        small = cv2.resize(img, (sw, sh), interpolation=cv2.INTER_AREA)
        k = disc_kernel(radius * sw / w)
        out = cv2.filter2D(small, -1, k, borderType=cv2.BORDER_REFLECT)
        out = cv2.resize(out, (w, h), interpolation=cv2.INTER_CUBIC)
        if out.ndim == 2 and img.ndim == 3:
            out = out[..., None]
        return out
    k = disc_kernel(radius)
    return cv2.filter2D(img, -1, k, borderType=cv2.BORDER_REFLECT)


def rgb_to_oklab(rgb, M_rgb_xyz):
    lms = rgb.reshape(-1, 3) @ (M_XYZ_LMS @ M_rgb_xyz).T
    lms = np.cbrt(lms)
    return (lms @ M_LMS_LAB.T).reshape(rgb.shape)


def oklab_to_rgb(lab, M_rgb_xyz):
    lms = lab.reshape(-1, 3) @ np.linalg.inv(M_LMS_LAB).T
    lms = lms ** 3
    rgb = lms @ np.linalg.inv(M_XYZ_LMS @ M_rgb_xyz).T
    return rgb.reshape(lab.shape).astype(np.float32)


# ----------------------------------------------------------------- depth
_model = None


def estimate_disparity(rgb8):
    """Relative inverse Tiefe (gross = nah), Depth Anything V2, mit Spiegel-TTA."""
    global _model
    import torch
    from transformers import AutoImageProcessor, AutoModelForDepthEstimation
    if _model is None:
        try:
            proc = AutoImageProcessor.from_pretrained(MODEL_ID)
            model = AutoModelForDepthEstimation.from_pretrained(MODEL_ID).eval()
        except Exception as e:  # kein stiller Fallback
            sys.exit(f"FEHLER: Depth Anything V2 konnte nicht geladen werden: {e}")
        _model = (proc, model)
    proc, model = _model
    h, w = rgb8.shape[:2]
    preds = []
    torch.set_num_threads(max(1, torch.get_num_threads()))
    for flip in (False, True):
        im = rgb8[:, ::-1] if flip else rgb8
        inputs = proc(images=Image.fromarray(np.ascontiguousarray(im)), return_tensors="pt")
        with torch.no_grad():
            pd = model(**inputs).predicted_depth[0].numpy().astype(np.float32)
        pd = cv2.resize(pd, (w, h), interpolation=cv2.INTER_CUBIC)
        preds.append(pd[:, ::-1] if flip else pd)
    return np.mean(preds, axis=0)


# ----------------------------------------------------------------- focus
def auto_focus(lin, srgb, disp):
    """Schaerfste texturierte Region.

    Mass: lokale Detailenergie (fein + mittel) relativ zur lokalen Helligkeit.
    Ausgeschlossen: ausgefressene Bereiche (+Rand), sehr dunkle Bereiche
    (dort dominiert JPEG-Rauschen), 6 % Bildrand.
    """
    h, w = srgb.shape[:2]
    s = 1024 / max(h, w)
    small = cv2.resize(srgb, (round(w * s), round(h * s)), interpolation=cv2.INTER_AREA)
    y = small @ np.array([0.2126, 0.7152, 0.0722], np.float32)
    g1, g3 = cv2.GaussianBlur(y, (0, 0), 1.0), cv2.GaussianBlur(y, (0, 0), 3.0)
    win = (41, 41)
    energy = cv2.boxFilter((y - g1) ** 2 + (g1 - g3) ** 2, -1, win)
    mean = cv2.boxFilter(y, -1, win)
    score = energy / (mean + 0.1) ** 2
    clipped = cv2.dilate((small.max(axis=2) > 0.985).astype(np.uint8), np.ones((15, 15), np.uint8)).astype(bool)
    valid = (~clipped) & (y > 0.12)
    cov = cv2.boxFilter(valid.astype(np.float32), -1, win)
    score = np.where(cov > 0.7, score, 0)
    b = int(0.06 * max(small.shape[:2]))
    score[:b] = score[-b:] = 0
    score[:, :b] = score[:, -b:] = 0
    iy, ix = np.unravel_index(np.argmax(score), score.shape)
    return (ix + 0.5) / small.shape[1], (iy + 0.5) / small.shape[0], score


# ----------------------------------------------------------------- DoF
def render_dof(lin, d, d_f, p, W):
    """Geschichtete Scheibenunschaerfe in linearem Licht, hinten -> vorn."""
    n_layers = int(p["layers"])
    tol = float(p["dof_tolerance"])
    # Radius in px fuer den groessten im Bild vorkommenden Tiefenabstand (99. Perzentil)
    dmax = max(0.1, float(np.percentile(np.abs(d - d_f), 99.0)))
    k = float(p["blur_strength"]) / 100.0 * W / max(1e-3, (dmax - tol) / (1.0 - tol))
    step = 1.0 / (n_layers - 1)
    j0 = math.floor((0.0 - d_f) / step)
    j1 = math.ceil((1.0 - d_f) / step)
    centers = [d_f + j * step for j in range(j0, j1 + 1)]

    def coc(dd):
        return k * np.maximum(0.0, np.abs(dd) - tol) / (1.0 - tol)

    rmap = coc(d - d_f).astype(np.float32)

    # kleine Lichtpunkte (Top-Hat) leicht verstaerken, nur wo unscharf
    Y = lin @ np.array([0.2290, 0.6917, 0.0793], np.float32)
    ksz = max(5, int(0.012 * W) | 1)
    opened = cv2.morphologyEx(Y, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (ksz, ksz)))
    point = smoothstep(0.55, 0.95, Y) * smoothstep(0.08, 0.35, Y - opened)
    boost = 1.0 + float(p["highlight_boost"]) * point * smoothstep(2.0, 10.0, rmap)
    src = lin * boost[..., None]

    acc = np.zeros_like(lin)
    acc_a = np.zeros(lin.shape[:2], np.float32)
    layer_info = []
    for c in centers:  # aufsteigende Disparitaet = von hinten nach vorn
        a = np.maximum(0.0, 1.0 - np.abs(d - c) / step).astype(np.float32)
        if a.max() < 1e-4:
            continue
        r = float(coc(np.float32(c - d_f)))
        buf = np.concatenate([src * a[..., None], a[..., None]], axis=2)
        bl = disc_blur(buf, r)
        bc, ba = bl[..., :3], np.clip(bl[..., 3], 0.0, 1.0)
        acc = bc + acc * (1.0 - ba[..., None])
        acc_a = ba + acc_a * (1.0 - ba)
        layer_info.append({"disparity": round(c, 4), "radius_px": round(r, 2), "coverage": round(float(a.mean()), 4)})
    out = acc / np.maximum(acc_a, 1e-4)[..., None]
    return out, rmap, layer_info, k


# ----------------------------------------------------------------- tone
def tone(lin, p, M_rgb_xyz, lum_w):
    x = lin * (2.0 ** float(p["exposure_ev"]))
    x = np.maximum(x, 0.0)

    # Lichter-Schulter auf Luminanz, angewandt als Faktor -> Farbton/Saettigung bleiben
    k, wpt = float(p["shoulder_start"]), float(p["shoulder_white"])
    Y = x @ lum_w
    t = np.maximum(Y - k, 0.0) / (1.0 - k)
    g = t * (1.0 + t / (wpt * wpt)) / (1.0 + t)
    Yt = np.where(Y > k, k + (1.0 - k) * g, Y)
    x = x * (Yt / np.maximum(Y, 1e-6))[..., None]

    # weiche Begrenzung ueber den Maximalkanal (kein Kanal > 1, Farbverhaeltnis bleibt)
    m = x.max(axis=2)
    kn = 0.9
    mt = np.where(m > kn, kn + (1.0 - kn) * np.tanh((m - kn) / (1.0 - kn)), m)
    x = x * (mt / np.maximum(m, 1e-6))[..., None]

    # OKLab: Chroma-Daempfung (Rot/Orange), Schatten, S-Kurve
    lab = rgb_to_oklab(x, M_rgb_xyz)
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    C = np.sqrt(a * a + b * b)
    h = np.degrees(np.arctan2(b, a))
    # Gewicht: Maximum um 40 Grad (Rot-Orange), weich auslaufend bis ca. -20 / 100 Grad
    dh = (h - 40.0 + 180.0) % 360.0 - 180.0
    wh = np.clip(np.cos(np.radians(np.clip(dh, -60, 60)) * 1.5), 0, 1)
    thr, st = float(p["chroma_threshold"]), float(p["chroma_strength"])
    e = np.maximum(C - thr, 0.0)
    e2 = e / (1.0 + st * wh * e / 0.1)
    Cn = np.where(C > thr, thr + e2, C)
    sc = Cn / np.maximum(C, 1e-6)
    a, b = a * sc, b * sc

    lift, con = float(p["shadow_lift"]), float(p["contrast"])
    L = L + lift * np.exp(-L / 0.3) * smoothstep(0.0, 0.08, L)
    L = L - con * np.sin(2 * np.pi * np.clip(L, 0, 1)) / (2 * np.pi)
    lab = np.stack([L, a, b], axis=2)
    x = oklab_to_rgb(lab, M_rgb_xyz)

    vig = float(p["vignette"])
    if vig > 0:
        hh, ww = x.shape[:2]
        yy, xx = np.mgrid[0:hh, 0:ww].astype(np.float32)
        rr = ((xx - ww / 2) ** 2 + (yy - hh / 2) ** 2) / ((ww / 2) ** 2 + (hh / 2) ** 2)
        x = x * (1.0 - vig * smoothstep(0.3, 1.0, rr))[..., None]
    return np.clip(x, 0.0, 1.0)


# ----------------------------------------------------------------- main
def process(name, cfg, out_dir):
    p = dict(cfg["defaults"])
    p.update({k: v for k, v in cfg["images"][name].items() if not k.startswith("_")})
    src_path = ROOT / p["source"]
    t0 = time.time()
    pil = Image.open(src_path)
    exif = pil.info.get("exif")
    icc = pil.info.get("icc_profile")
    rgb8 = np.asarray(pil.convert("RGB"))
    H, W = rgb8.shape[:2]
    is_p3 = False
    if icc:
        from PIL import ImageCms
        desc = ImageCms.getProfileDescription(ImageCms.ImageCmsProfile(io.BytesIO(icc)))
        is_p3 = "P3" in desc
    M = M_P3_XYZ if is_p3 else M_709_XYZ
    lum_w = M[1].copy()

    srgb = rgb8.astype(np.float32) / 255.0
    lin = srgb_to_linear(srgb)

    # --- Tiefe
    disp_raw = estimate_disparity(rgb8)
    lo, hi = np.percentile(disp_raw, [0.5, 99.5])
    d = np.clip((disp_raw - lo) / (hi - lo), 0, 1).astype(np.float32)
    gray = cv2.cvtColor(srgb, cv2.COLOR_RGB2GRAY)
    gr = max(4, int(round(0.004 * W)))
    d = np.clip(guided_filter(gray, d, gr, float(p.get("guided_eps", 4e-3))), 0, 1).astype(np.float32)

    # --- Fokus
    if p.get("focus_x") is None or p.get("focus_y") is None:
        fx, fy, _ = auto_focus(lin, srgb, d)
        focus_src = "auto"
    else:
        fx, fy = float(p["focus_x"]), float(p["focus_y"])
        focus_src = "config"
    px, py = int(fx * (W - 1)), int(fy * (H - 1))
    rr = max(4, int(0.012 * W))
    patch = d[max(0, py - rr):py + rr + 1, max(0, px - rr):px + rr + 1]
    d_f = float(np.median(patch))

    # --- DoF
    dof, rmap, layers, kcoc = render_dof(lin, d, d_f, p, W)

    # --- Ton
    final = tone(dof, p, M, lum_w)
    enc = linear_to_srgb(final)

    # --- Ausgabe
    out_dir.mkdir(parents=True, exist_ok=True)
    u16 = np.round(enc * 65535.0).astype(np.uint16)
    extratags = [(34675, 7, len(icc), icc, True)] if icc else []
    tifffile.imwrite(out_dir / f"{name}_hasselblad.tif", u16, photometric="rgb",
                     compression="zlib", predictor=True, extratags=extratags)
    # 8 bit: TPDF-Dither (+-1 LSB) gegen Banding beim Quantisieren
    rng = np.random.default_rng(0)
    dith = (rng.random(enc.shape, np.float32) - rng.random(enc.shape, np.float32))
    u8 = np.clip(np.round(enc * 255.0 + dith), 0, 255).astype(np.uint8)
    kw = dict(quality=95, subsampling=0, optimize=True)
    if exif:
        kw["exif"] = exif
    if icc:
        kw["icc_profile"] = icc
    Image.fromarray(u8).save(out_dir / f"{name}_hasselblad.jpg", **kw)

    Image.fromarray(np.round(d * 65535).astype(np.uint16)).save(out_dir / f"{name}_depth.png")
    kmax = float(rmap.max())
    focus_vis = 1.0 - np.clip(rmap / max(1.0, 0.5 * kmax), 0, 1)
    focus_vis[rmap < 1.0] = 1.0
    Image.fromarray(np.round(focus_vis * 255).astype(np.uint8)).save(out_dir / f"{name}_focus.png")

    info = {
        "name": name, "source": str(p["source"]), "size": [W, H], "color_space": "Display P3" if is_p3 else "sRGB",
        "focus": {"x": round(fx, 4), "y": round(fy, 4), "source": focus_src, "disparity": round(d_f, 4)},
        "max_blur_radius_px": round(kmax, 1), "layers": layers, "params": p,
        "clipped_any_channel_pct": round(float((rgb8.max(axis=2) >= 254).mean() * 100), 2),
        "clipped_all_channels_pct": round(float((rgb8.min(axis=2) >= 254).mean() * 100), 2),
        "seconds": round(time.time() - t0, 1), "depth_model": MODEL_ID,
    }
    (out_dir / f"{name}_params.json").write_text(json.dumps(info, indent=2, ensure_ascii=False))
    print(json.dumps({k: info[k] for k in ("name", "focus", "max_blur_radius_px", "seconds")}, ensure_ascii=False))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default=str(Path(__file__).with_name("config.json")))
    ap.add_argument("--out", default=str(ROOT / "out"))
    ap.add_argument("names", nargs="*")
    a = ap.parse_args()
    cfg = json.loads(Path(a.config).read_text())
    for n in a.names or list(cfg["images"]):
        process(n, cfg, Path(a.out))


if __name__ == "__main__":
    main()
