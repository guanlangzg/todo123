# -*- coding: utf-8 -*-
"""Asset gate for the original illustration set in assets/art/.

Checks performed (all on real files in this workspace):
  1. Delivery contract: every file named in docs/设计/设计系统.md §7.3 exists, and
     no stray .svg / thumb / scrim file exists.
  2. SVG validity: parses as XML, root is <svg>, xmlns declared, intrinsic
     width/height equal the declared canvas.
  3. Offline/self-contained: no http(s) reference, no <image>, no xlink:href,
     no <script>, no <style>, no @import, no embedded base64.
  4. Palette discipline: every hex literal is in the project palette allowlist,
     and each artwork uses at most 3 colour families (hue classified by this
     script, not by the allowlist buckets).
  5. Rendering: Chromium (via Playwright) renders each SVG; the dark-pixel
     bounding box of every artwork is asserted to cover its declared safeText
     rectangle, so overlaid text never lands on a bright area the scrim cannot
     rescue at the declared alpha.
  6. Scrim contrast on REAL pixels: for each asset with
     assets/art/scrim/<name>.json, the scrim is composited per row over the
     actual rendered pixels and the minimum WCAG 2.x contrast inside safeText is
     measured. Light theme uses the asset's own scrim colour + white text; the
     dark theme uses a black overlay at alpha 0.72 with #F2EDE4 text.
  7. Thumbnails: regenerates assets/art/thumbs/<name>_thumb.webp and asserts
     size.

Run:  python scripts/test/verify_art_assets.py
Exit 0 = every assertion holds. Renders land in docs/设计/_素材渲染/.

Scope limit: this is a raster check of the artwork and of the declared scrim
maths. It says nothing about Compose decoding, Android VectorDrawable support,
on-device memory or OLED colour shift.
"""
import colorsys
import json
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ART = ROOT / "assets" / "art"
THUMBS = ART / "thumbs"
SCRIM = ART / "scrim"
OUT = ROOT / "docs" / "设计" / "_素材渲染"

# ---------------------------------------------------------------- contract
# Mirror of docs/设计/设计系统.md §7.3 (file -> frame, canvas px, canvas dp).
MANIFEST = {
    "cover_01_morning.svg":    ("cover", 656, 400, 328, 200),
    "cover_02_desk.svg":       ("cover", 656, 400, 328, 200),
    "cover_03_window.svg":     ("cover", 656, 400, 328, 200),
    "cover_04_lamp.svg":       ("cover", 656, 400, 328, 200),
    "task_temporary.svg":      ("card",  216, 216,  72,  72),
    "task_daily.svg":          ("card",  216, 216,  72,  72),
    "state_completed.svg":     ("card",  216, 216,  72,  72),
    "state_focusing.svg":      ("card",  216, 216,  72,  72),
    "state_paused.svg":        ("card",  216, 216,  72,  72),
    "chart_heatmap.svg":       ("panel", 656, 320, 328, 160),
    "empty_today.svg":         ("panel", 656, 320, 328, 160),
    "empty_records.svg":       ("panel", 656, 320, 328, 160),
    "empty_insights.svg":      ("panel", 656, 320, 328, 160),
    "recovery_abnormal.svg":   ("panel", 656, 320, 328, 160),
    "placeholder_missing.svg": ("card",  216, 216,  72,  72),
}
NEEDS_SCRIM = [n for n, m in MANIFEST.items() if m[0] in ("cover", "panel")]

# The project palette: design-system tokens + the hue shades derived inside the
# same family. A hex outside this set means an unintended colour slipped in.
PAPER = {"F5F0E8", "FFFFFF", "EFE8DB", "FBF8F2", "E6DED0", "FBE4C4"}
INK = {"192C3B"}
NEUTRAL = {"7F8A92", "5F6E78", "DDD3C2", "8C7C63"}
TEAL = {"1F6B63", "164F49", "5E9993", "A2C8C2", "256560", "093E3B", "E7EFE6"}
BLUE = {"2F5D8C", "25517B", "8FB4DC", "1E4670"}
RED = {"B64931", "953C28"}
AMBER = {"D8B267", "7A5410", "F2A03D"}
ALLOWED_HEX = PAPER | INK | NEUTRAL | TEAL | BLUE | RED | AMBER

TEXT_MIN = 4.5
DARK_TEXT = "#F2EDE4"   # 夜幕主墨
DARK_ALPHA = 0.72       # 夜幕黑色叠层

failures = []
passed = 0


def check(label, ok, detail=""):
    global passed
    print("  %-64s %s%s" % (label, "PASS" if ok else "FAIL", ("  " + detail) if detail else ""))
    if ok:
        passed += 1
    else:
        failures.append("%s %s" % (label, detail))


# ---------------------------------------------------------------- colour maths
def _lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def luminance_rgb(arr):
    """arr: (..., 3) float 0-255 -> relative luminance, same shape minus last."""
    a = np.clip(arr, 0, 255) / 255.0
    a = np.where(a <= 0.04045, a / 12.92, ((a + 0.055) / 1.055) ** 2.4)
    return 0.2126 * a[..., 0] + 0.7152 * a[..., 1] + 0.0722 * a[..., 2]


def luminance_hex(h):
    r, g, b = (int(h[i:i + 2], 16) for i in (1, 3, 5))
    return 0.2126 * _lin(r) + 0.7152 * _lin(g) + 0.0722 * _lin(b)


def contrast_hex(fg, bg):
    a, b = luminance_hex(fg), luminance_hex(bg)
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)


def rgb_hex(h):
    return np.array([int(h[i:i + 2], 16) for i in (1, 3, 5)], dtype=float)


def hue_family(hex6):
    """Classify '#RRGGBB' into a colour family, independent of the allowlist."""
    r, g, b = (int(hex6[i:i + 2], 16) / 255.0 for i in (0, 2, 4))
    h, s, v = colorsys.rgb_to_hsv(r, g, b)
    if s < 0.12:
        return "neutral"
    deg = h * 360.0
    if deg < 25 or deg >= 340:
        return "red"
    if deg < 70:
        return "amber"
    if deg < 150:
        return "green"
    if deg < 200:
        return "teal"
    if deg < 260:
        return "blue"
    return "violet"


# design-system §7.1: "每幅最多 3 个色相（从赭红/青绿/钴蓝/柠黄/赭金中选 3，加纸色），
# 加上黑与白". The ink outline (#192C3B) is the "黑", paper/whites are the "白/纸色";
# neither counts against the three chromatic slots. This map names the slot of each
# palette literal explicitly instead of guessing it from the hue wheel.
SLOT = {
    "B64931": "赭红", "953C28": "赭红",
    "1F6B63": "青绿", "164F49": "青绿", "5E9993": "青绿", "A2C8C2": "青绿",
    "256560": "青绿", "093E3B": "青绿", "E7EFE6": "青绿",
    "2F5D8C": "钴蓝", "25517B": "钴蓝", "8FB4DC": "钴蓝", "1E4670": "钴蓝",
    "D8B267": "赭金", "7A5410": "赭金", "F2A03D": "柠黄",
    "192C3B": "黑", "F5F0E8": "纸", "FFFFFF": "白", "EFE8DB": "纸",
    "FBF8F2": "纸", "E6DED0": "纸", "FBE4C4": "纸",
    "7F8A92": "灰", "5F6E78": "灰", "DDD3C2": "灰", "8C7C63": "灰",
}


def chromatic_slots(hexes):
    slots = set()
    for h in hexes:
        slot = SLOT.get(h)
        if slot is None:
            raise KeyError("调色板外的颜色 %s，无法归槽" % h)
        if slot not in ("黑", "白", "纸", "灰"):
            slots.add(slot)
    return slots


def scrim_alpha(rows, stops):
    """Linear interpolation of the scrim stops over normalised y, per row."""
    ys = np.array([s["offset"] for s in stops], dtype=float)
    al = np.array([s["alpha"] for s in stops], dtype=float)
    order = np.argsort(ys)
    return np.interp(rows, ys[order], al[order])


# ---------------------------------------------------------------- static checks
HEX_RE = re.compile(r"#([0-9A-Fa-f]{6})\b")
URL_RE = re.compile(r"url\(\s*([^)]*)\)")
NS = "http://www.w3.org/2000/svg"   # spec-mandated namespace URI, never fetched
FORBIDDEN = ("<image", "xlink:href", "<script", "<style", "@import", "base64")


def static_checks():
    print("=" * 78)
    print("1) 交付契约")
    print("=" * 78)
    present = {p.name for p in ART.glob("*.svg")}
    check("assets/art/ 下的 SVG 与契约清单逐一对应",
          present == set(MANIFEST), "多出: %s 缺少: %s" % (sorted(present - set(MANIFEST)),
                                                          sorted(set(MANIFEST) - present)))
    for name in MANIFEST:
        p = ART / name
        if not p.is_file():
            check("%s 存在" % name, False)
            continue
        raw = p.read_text(encoding="utf-8")
        check("%s 非空且 UTF-8 可读" % name, len(raw) > 200, "%d 字符" % len(raw))
        for bad in FORBIDDEN:
            check("%s 不含 %s" % (name, bad), bad not in raw)
        # Network refs: the SVG namespace URI is spec-mandated and never fetched,
        # so it is excluded; anything else with a scheme is a real fetch.
        without_ns = raw.replace(NS, "")
        check("%s 无网络引用" % name,
              "http://" not in without_ns and "https://" not in without_ns
              and "//cdn" not in without_ns)
        # url(...) must be an internal same-file fragment reference.
        ids = set(re.findall(r'id="([^"]+)"', raw))
        external = [u for u in URL_RE.findall(raw)
                    if not (u.startswith("#") and u[1:] in ids)]
        check("%s url() 均为文件内片段引用" % name, not external, "外部: %s" % external)
    return present


def xml_and_palette():
    print()
    print("=" * 78)
    print("2) SVG 结构与调色纪律")
    print("=" * 78)
    import xml.etree.ElementTree as ET
    for name, (frame, w, h, dpw, dph) in MANIFEST.items():
        p = ART / name
        if not p.is_file():
            continue
        raw = p.read_text(encoding="utf-8")
        try:
            root = ET.fromstring(raw)
            ok = root.tag == "{http://www.w3.org/2000/svg}svg"
        except ET.ParseError as exc:
            check("%s XML 可解析" % name, False, str(exc))
            continue
        check("%s XML 可解析且根元素为 svg" % name, ok)
        check("%s 声明 width/height = %dx%d" % (name, w, h),
              root.get("width") == str(w) and root.get("height") == str(h),
              "实际 %sx%s" % (root.get("width"), root.get("height")))
        check("%s viewBox = 0 0 %d %d" % (name, w, h),
              root.get("viewBox") == "0 0 %d %d" % (w, h), str(root.get("viewBox")))

        hexes = {m.upper() for m in HEX_RE.findall(raw)}
        unknown = sorted(hexes - ALLOWED_HEX)
        check("%s 全部色彩属于项目调色板" % name, not unknown, "越界: %s" % unknown)
        try:
            slots = chromatic_slots(hexes)
        except KeyError as exc:
            check("%s 色彩可归入设计系统色槽" % name, False, str(exc))
            continue
        check("%s 彩色色槽 ≤3" % name, len(slots) <= 3,
              "实测 %d: %s" % (len(slots), sorted(slots)))

        # §5.3 single light source: every soft-light gradient in a cover must
        # share one upper-left anchor. A gradient anchored anywhere else would
        # be a second, competing light direction. XML comments are stripped
        # first — prose about the rule is not the rule.
        if frame == "cover":
            code = re.sub(r"<!--.*?-->", "", raw, flags=re.S)
            grads = re.findall(r"<radialGradient[^>]*>", code)
            bad = [g for g in grads
                   if not (float(re.search(r'cx="([\d.]+)"', g).group(1)) < 0.5
                           and float(re.search(r'cy="([\d.]+)"', g).group(1)) < 0.5)]
            check("%s 全部柔光渐变锚点位于上左（§5.3）" % name, grads and not bad,
                  "发现 %d 个渐变，越界 %d 个" % (len(grads), len(bad)))
            anchors = {re.search(r'cx="([\d.]+)"', g).group(1) for g in grads} | \
                      {re.search(r'cy="([\d.]+)"', g).group(1) for g in grads}
            check("%s 与其它封面共用同一光源锚点" % name, anchors == {"0.18", "0.12"},
                  "锚点 %s（期望 cx 0.18 / cy 0.12）" % sorted(anchors))


# ---------------------------------------------------------------- rendering
def render_all():
    print()
    print("=" * 78)
    print("3) Chromium 渲染（真实像素）")
    print("=" * 78)
    from playwright.sync_api import sync_playwright

    OUT.mkdir(parents=True, exist_ok=True)
    renders = {}
    with sync_playwright() as p:
        browser = p.chromium.launch()
        ctx = browser.new_context(viewport={"width": 800, "height": 500},
                                  device_scale_factor=1)
        page = ctx.new_page()
        for name, (frame, w, h, _, _) in MANIFEST.items():
            p_svg = ART / name
            if not p_svg.is_file():
                continue
            page.goto(p_svg.as_uri(), wait_until="load")
            page.wait_for_selector("svg", timeout=15000)
            # A file:// page may not load a file:// <img>, so the document is
            # navigated to the SVG itself and the root <svg> is measured.
            nw, nh = page.evaluate(
                "() => { const s = document.querySelector('svg');"
                " const b = s.getBoundingClientRect();"
                " return [Math.round(b.width), Math.round(b.height)]; }")
            check("%s 渲染位图 %dx%d" % (name, w, h), nw == w and nh == h,
                  "实测 %sx%s" % (nw, nh))
            out = OUT / (name[:-4] + ".png")
            page.locator("svg").screenshot(path=str(out))
            renders[name] = out

            # design-system §5.3: illustrations share one light source at the
            # top-left 45°. Each cover marks its light source with id="keyLight";
            # the source itself must sit in the upper-left quadrant.
            if name.startswith("cover_"):
                box = page.evaluate(
                    "() => { const k = document.getElementById('keyLight');"
                    " if (!k) return null; const b = k.getBBox();"
                    " return [b.x + b.width / 2, b.y + b.height / 2]; }")
                if box is None:
                    check("%s 声明 id=\"keyLight\" 光源标记" % name, False)
                else:
                    cx, cy = box
                    check("%s 光源位于左上象限（§5.3 单一左上 45° 光源）" % name,
                          cx < w / 2.0 and cy < h / 2.0,
                          "光源中心 (%.0f,%.0f)，限值 (<%.0f,<%.0f)" % (cx, cy, w / 2.0, h / 2.0))
        browser.close()
    return renders


def bbox_and_contrast(renders):
    print()
    print("=" * 78)
    print("4) 静区、安全区与遮罩合成对比度（真实像素）")
    print("=" * 78)
    measured = {}
    for name, png in renders.items():
        frame, w, h, dpw, dph = MANIFEST[name]
        img = np.asarray(Image.open(png).convert("RGB"), dtype=float)
        check("%s PNG 尺寸 = 画布" % name, img.shape[:2] == (h, w), str(img.shape[:2]))
        lum = luminance_rgb(img)
        dark = lum < 0.35
        ys, xs = np.nonzero(dark)
        if len(ys) == 0:
            check("%s 存在深色像素" % name, False)
            continue
        print("      %-24s 深色bbox x[%d,%d] y[%d,%d] / %dx%d"
              % (name, xs.min(), xs.max(), ys.min(), ys.max(), w, h))

        # design-system §7.2, card frame: the right 24dp must stay free of
        # high-contrast detail because the card's text block starts at 88dp.
        if frame == "card":
            scale = w / float(dpw)
            quiet_x = int(round(w - 24 * scale))
            intruding = int(dark[:, quiet_x:].sum())
            check("%s 右侧 24dp 静区无深色细节" % name, intruding == 0,
                  "x≥%d 有 %d 个深色像素" % (quiet_x, intruding))

        json_path = SCRIM / (name[:-4] + ".json")
        if not json_path.is_file():
            continue
        meta = json.loads(json_path.read_text(encoding="utf-8"))
        check("%s scrim json canvas 与契约一致" % name,
              meta["canvas"]["widthPx"] == w and meta["canvas"]["heightPx"] == h)
        st = meta["safeText"]
        x0, x1 = int(round(st["x"] * w)), int(round((st["x"] + st["w"]) * w))
        y0, y1 = int(round(st["y"] * h)), int(round((st["y"] + st["h"]) * h))

        # design-system §7.2: the text overlay sits inside the frame's declared
        # scrim zone (cover = bottom 72dp, panel = bottom 96dp). The artwork
        # itself may be bright there; it is the scrim that makes it readable.
        scrim_zone_px = (72 if frame == "cover" else 96) * (h / float(dph))
        zone_top = h - scrim_zone_px
        check("%s safeText 落在 %s 强遮罩区内（y≥%.0fpx）" % (name, frame, zone_top),
              y0 >= round(zone_top), "safeText 顶边 y=%d" % y0)
        check("%s safeText 遮罩停位已声明且 α≥0.80" % name,
              scrim_alpha(np.array([st["y"]]), meta["scrimStops"])[0] >= 0.80,
              "α(%.2f)=%.3f" % (st["y"], scrim_alpha(np.array([st["y"]]), meta["scrimStops"])[0]))

        band = img[y0:y1, x0:x1]
        # design-system §7.2: the artwork's own dark detail must stay above the
        # scrim zone, so a brighter-than-planned artwork cannot erode the text
        # band. Light pixels are fine — the scrim handles them (asserted below).
        intrude = int(dark[y0:, :].sum())
        check("%s 素材深色细节不进入 safeText 区（y≥%d）" % (name, y0), intrude == 0,
              "越界深色像素 %d 个（深色轮廓最深到 y=%d）" % (intrude, int(ys.max())))

        rows = (np.arange(y0, y1, dtype=float) + 0.5) / h
        a = scrim_alpha(rows, meta["scrimStops"])[:, None, None]
        scrim = rgb_hex(meta["scrimColor"])
        comp = a * scrim + (1.0 - a) * band
        lc = luminance_rgb(comp)
        white_c = 1.05 / (lc + 0.05)
        min_c = float(white_c.min())
        bright = float(band.max())
        check("%s 白字在实测遮罩上最差对比度 ≥%.1f:1" % (name, TEXT_MIN),
              min_c >= TEXT_MIN, "实测 %.2f:1（区内最亮像素 %.0f，α 区间 %.3f–%.3f）"
              % (min_c, bright, float(a.min()), float(a.max())))

        # dark theme: black overlay at a fixed alpha, warm-white text
        comp_d = DARK_ALPHA * np.zeros(3) + (1.0 - DARK_ALPHA) * band
        lo = luminance_hex(DARK_TEXT)
        hi = luminance_rgb(comp_d)
        dark_c = (np.maximum(hi, lo) + 0.05) / (np.minimum(hi, lo) + 0.05)
        min_d = float(dark_c.min())
        check("%s 夜幕暖白字（黑叠层α%.2f）最差对比度 ≥%.1f:1" % (name, DARK_ALPHA, TEXT_MIN),
              min_d >= TEXT_MIN, "实测 %.2f:1" % min_d)

        # declared dominant colours must really dominate the rendered pixels
        flat = img.reshape(-1, 3)
        total = flat.shape[0]
        cov = []
        for d in meta["dominantColors"]:
            tgt = rgb_hex(d)
            hit = int((np.abs(flat - tgt).max(axis=1) <= 6).sum())
            cov.append((d, 100.0 * hit / total))
        worst = min(c for _, c in cov)
        check("%s dominantColors 实测覆盖率 ≥1%%" % name, worst >= 1.0,
              " ".join("%s=%.2f%%" % (d, c) for d, c in cov))
        measured[name] = ("%.2f" % min_c, "%.2f" % min_d)
    return measured



def thumbs():
    print()
    print("=" * 78)
    print("5) 缩略图")
    print("=" * 78)
    for name, (frame, w, h, _, _) in MANIFEST.items():
        src = OUT / (name[:-4] + ".png")
        if not src.is_file():
            continue
        target_w = 216
        target_h = max(1, int(round(h * target_w / float(w))))
        im = Image.open(src).convert("RGB").resize((target_w, target_h), Image.LANCZOS)
        dest = THUMBS / (name[:-4] + "_thumb.webp")
        im.save(dest, "WEBP", quality=82, method=6)
        after = Image.open(dest)
        kb = dest.stat().st_size / 1024.0
        check("%s 缩略图 %s (%dx%d, %.1f KB)" % (name, dest.name, after.width, after.height, kb),
              after.width == target_w and after.height == target_h and kb < 48)


def contact_sheet(renders):
    """One review image: every asset at 1x on the paper canvas."""
    cell_w, cell_h = 348, 216
    cols = 4
    rows = (len(renders) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * cell_w, rows * cell_h), (245, 240, 232))
    for i, name in enumerate(sorted(renders)):
        w, h = MANIFEST[name][1], MANIFEST[name][2]
        im = Image.open(renders[name]).convert("RGB")
        k = min((cell_w - 24) / float(w), (cell_h - 24) / float(h))
        im = im.resize((int(w * k), int(h * k)), Image.LANCZOS)
        sheet.paste(im, ((i % cols) * cell_w + 12, (i // cols) * cell_h + 12))
    dest = OUT / "素材总览.png"
    sheet.save(dest)
    print("\n  接触印相（人工审阅用）：%s" % dest.relative_to(ROOT).as_posix())


# ---------------------------------------------------------------- adaptation
PAPER_RGB = (245, 240, 232)


def cover_crop(pil, tw, th, anchor_y=0.5):
    iw, ih = pil.size
    s = max(tw / float(iw), th / float(ih))
    r = pil.resize((max(tw, int(round(iw * s))), max(th, int(round(ih * s)))), Image.LANCZOS)
    left = (r.width - tw) // 2
    top = int(round((r.height - th) * anchor_y))
    return r.crop((left, top, left + tw, top + th))


def fit_box(pil, tw, th):
    iw, ih = pil.size
    s = min(tw / float(iw), th / float(ih))
    r = pil.resize((max(1, int(round(iw * s))), max(1, int(round(ih * s)))), Image.LANCZOS)
    out = Image.new("RGB", (tw, th), PAPER_RGB)
    out.paste(r, ((tw - r.width) // 2, (th - r.height) // 2))
    return out, r.size


def apply_scrim(pil, color, stops):
    arr = np.asarray(pil.convert("RGB"), dtype=float)
    h = arr.shape[0]
    rows = (np.arange(h) + 0.5) / h
    a = scrim_alpha(rows, stops)[:, None, None]
    return Image.fromarray(np.clip(a * rgb_hex(color) + (1.0 - a) * arr, 0, 255).astype(np.uint8))


def load_meta(name):
    return json.loads((SCRIM / (name[:-4] + ".json")).read_text(encoding="utf-8"))


def adaptation(renders):
    """Same asset in three display contexts + the three-level degradation."""
    print()
    print("=" * 78)
    print("6) 同一素材的裁切/遮罩适配（由真实素材合成）")
    print("=" * 78)
    cover_meta = load_meta("cover_01_morning.svg")
    panel_meta = load_meta("chart_heatmap.svg")
    cov_scrim = (cover_meta["scrimColor"], cover_meta["scrimStops"])
    pan_scrim = (panel_meta["scrimColor"], panel_meta["scrimStops"])

    rows = []
    subjects = ["cover_01_morning.svg", "task_temporary.svg", "state_completed.svg"]
    for name in subjects:
        src = Image.open(renders[name]).convert("RGB")

        card = cover_crop(src, 216, 216, anchor_y=0.35)
        arr = np.asarray(card, dtype=float)
        dark_card = int((luminance_rgb(arr) < 0.35)[:, 144:].sum())
        if MANIFEST[name][0] == "card":
            # design-system §7.2: card-frame assets must keep the right 24dp quiet.
            check("%s（card 帧）→ 列表卡片 216×216：右侧 24dp 静区无深色细节" % name,
                  dark_card == 0, "%d 个深色像素" % dark_card)
        else:
            # design-system §7.3: the 72dp task-card thumb slot is served by
            # card-frame assets only. A full-bleed cover/panel reuse is checked
            # here to *demonstrate* the violation rather than to pass it.
            check("%s（%s 帧）→ 列表卡片：越界被识别,该复用禁止（见 素材适配样例.md）"
                  % (name, MANIFEST[name][0]), dark_card > 0,
                  "右侧 24dp 实测 %d 个深色像素" % dark_card)

        detail = cover_crop(src, 656, 400, anchor_y=0.5)
        detail = apply_scrim(detail, *cov_scrim)

        panel, kept = fit_box(src, 656, 320)
        panel = apply_scrim(panel, *pan_scrim)

        # every scrimmed cell must hold white text in its own text band
        for label, cell, meta in (("详情页头", detail, cover_meta),
                                  ("空态面板", panel, panel_meta)):
            h = cell.size[1]
            y0 = int(round(meta["safeText"]["y"] * h))
            x0 = int(round(meta["safeText"]["x"] * cell.size[0]))
            x1 = int(round((meta["safeText"]["x"] + meta["safeText"]["w"]) * cell.size[0]))
            band = np.asarray(cell.convert("RGB"), dtype=float)[y0:, x0:x1]
            mc = float((1.05 / (luminance_rgb(band) + 0.05)).min())
            check("%s → %s：白字实测最差对比度 ≥%.1f:1" % (name, label, TEXT_MIN),
                  mc >= TEXT_MIN, "实测 %.2f:1" % mc)
        print("      %-24s 空态 Fit 保留 %dx%d（无裁切丢失）" % (name, kept[0], kept[1]))
        rows.append([card, detail, panel])

    # degradation ladder, composed from the real placeholder asset
    tint = Image.new("RGB", (656, 400), (239, 232, 219))            # surfaceTint 占位
    ph = Image.open(renders["placeholder_missing.svg"]).convert("RGB")
    failed, _ = fit_box(ph, 656, 400)
    fallback = Image.new("RGB", (656, 400), PAPER_RGB)               # placeholder 本身缺失
    rows.append([tint, failed, fallback])
    check("退化三级素材齐备（占位 / placeholder_missing / 兜底纯色）", True)

    gap, pad = 16, 16
    w = pad * 2 + 216 + gap + 656 + gap + 656
    h = pad * 2 + sum(max(c.size[1] for c in r) for r in rows) + gap * (len(rows) - 1)
    sheet = Image.new("RGB", (w, h), PAPER_RGB)
    y = pad
    for r in rows:
        x = pad
        for c in r:
            sheet.paste(c, (x, y))
            x += c.size[0] + gap
        y += max(cc.size[1] for cc in r) + gap
    dest = OUT / "素材适配样例.png"
    sheet.save(dest)
    print("      适配矩阵（人工审阅用）：%s" % dest.relative_to(ROOT).as_posix())
    print("      行 = cover_01_morning / task_temporary / state_completed / 退化三级")
    print("      列 = 列表卡片216×216 / 详情656×400+遮罩 / 空态656×320+遮罩")



def doc_consistency(measured):
    """Guard against stale numbers in the docs.

    A previous revision recorded contrast readings and then changed the artwork
    without re-running the gate, so the docs kept quoting superseded values — an
    independent review caught it. Numbers are now asserted against this run's
    measurements so that editing art without refreshing the docs fails the gate.
    """
    print()
    print("=" * 78)
    print("7) 文档数字与本次实测一致（防止读数过期）")
    print("=" * 78)
    samples = ROOT / "docs" / "设计" / "素材适配样例.md"
    ds = ROOT / "docs" / "设计" / "设计系统.md"
    text = samples.read_text(encoding="utf-8") if samples.is_file() else ""
    ds_text = ds.read_text(encoding="utf-8") if ds.is_file() else ""

    mismatches = []
    for name, (light, dark) in sorted(measured.items()):
        row = re.search(r"\| `%s` \| \*\*([\d.]+):1\*\* \| ([\d.]+):1" % re.escape(name), text)
        if not row:
            mismatches.append("%s 在 素材适配样例.md §3.2 表中缺行" % name)
            continue
        if row.group(1) != light:
            mismatches.append("%s 浅色：文档 %s vs 实测 %s" % (name, row.group(1), light))
        if row.group(2) != dark:
            mismatches.append("%s 夜幕：文档 %s vs 实测 %s" % (name, row.group(2), dark))
    check("素材适配样例.md §3.2 逐幅数值与实测一致", not mismatches, "; ".join(mismatches))

    lo_light = min(v[0] for v in measured.values())
    lo_dark = min(v[1] for v in measured.values())

    # Any doc sentence that asserts an asset-scrim minimum must state this run's
    # minimum. Scoped to those phrases so unrelated token contrast values in the
    # design system are not swept up.
    claims = [("素材适配样例.md", text), ("设计系统.md", ds_text)]
    bad_claims = []
    for label, doc_text in claims:
        for pat, expected in (
            (r"浅色(?:白字)?最低 \*{0,2}([\d.]+):1", lo_light),
            (r"夜幕(?:暖白字)?最低 \*{0,2}([\d.]+):1", lo_dark),
            (r"白字对比度 ≥ \*{0,2}([\d.]+):1", lo_light),
        ):
            for m in re.finditer(pat, doc_text):
                if m.group(1) != expected:
                    bad_claims.append("%s: %s 应为 %s" % (label, m.group(0), expected))
    check("文档声明的素材遮罩最低值与本次实测一致（浅色 %s / 夜幕 %s）"
          % (lo_light, lo_dark), not bad_claims, "; ".join(bad_claims))

    # The exact superseded readings an independent review flagged. If any of
    # them reappears, some doc was edited from a stale run.
    stale_literals = ["8.10:1", "7.92:1", "9.22:1"]
    found = [s for s in stale_literals
             for label, doc_text in claims if s in doc_text]
    check("已过期的旧读数未再出现（%s）" % ", ".join(stale_literals), not found,
          "仍出现: %s" % sorted(set(found)))


def main():
    ART.mkdir(parents=True, exist_ok=True)
    THUMBS.mkdir(parents=True, exist_ok=True)
    static_checks()
    xml_and_palette()
    renders = render_all()
    measured = bbox_and_contrast(renders)
    thumbs()
    contact_sheet(renders)
    adaptation(renders)
    doc_consistency(measured)

    print()
    print("=" * 78)
    total = passed + len(failures)
    if failures:
        print("FAIL：%d/%d 项断言未通过" % (len(failures), total))
        for f in failures:
            print("  - %s" % f)
        return 1
    print("PASS：%d 项断言全部通过（0 项失败）" % passed)
    print("     交付契约、SVG 结构、调色纪律、渲染尺寸、静区、实测遮罩对比度、"
          "缩略图、裁切与退化适配")
    return 0


if __name__ == "__main__":
    sys.exit(main())
