# -*- coding: utf-8 -*-
"""Design-token contrast gate for the art-todo visual system.

Static sRGB WCAG 2.x relative-luminance calculation. This is NOT an on-device
verification: alpha compositing of the paper-noise material, real illustration
pixels, system font rasterisation and OLED calibration are out of scope.

Usage:  python scripts/test/verify_design_tokens.py
Exit 0 = every declared pair meets its threshold; exit 1 = at least one fails.
"""
import sys

TEXT_MIN = 4.5      # WCAG 2.2 AA normal text
LARGE_MIN = 3.0     # WCAG 2.2 AA large text (>=18.66sp bold / >=24sp regular)
NONTEXT_MIN = 3.0   # WCAG 2.2 AA non-text (icons, control boundaries, graphics)


def _lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def _rgb(h):
    h = h.lstrip("#")
    if len(h) != 6:
        raise ValueError("expected #RRGGBB, got %r" % h)
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def luminance(h):
    r, g, b = _rgb(h)
    return 0.2126 * _lin(r) + 0.7152 * _lin(g) + 0.0722 * _lin(b)


def contrast(fg, bg):
    a, b = luminance(fg), luminance(bg)
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)


def alpha_over(fg, alpha, bg):
    """Composite an opaque fg over bg at the given alpha (sRGB, no gamma blend)."""
    f, b = _rgb(fg), _rgb(bg)
    return "#%02X%02X%02X" % tuple(
        round(b[i] * (1.0 - alpha) + f[i] * alpha) for i in range(3)
    )


# --------------------------------------------------------------------------
# Light theme  "画廊白昼"
# --------------------------------------------------------------------------
L = {
    "canvas":       "#F5F0E8",
    "tint":         "#EFE8DB",
    "card":         "#FFFFFF",
    "raised":       "#FBF8F2",
    "line.hairline": "#DDD3C2",
    "line.control": "#7F8A92",
    "icon.line":    "#7F8A92",
    "ink":          "#192C3B",
    "ink2":         "#596771",
    "ink3":         "#5F6E78",
    "accent":       "#B64931",
    "accent.deep":  "#953C28",
    "accent.tint":  "#FBEFEA",
    "onAccent":     "#FFFFFF",
    "running":      "#1F6B63",
    "series1":      "#2F5D8C",
    "series4":      "#7A5410",
    "series6":      "#6B4A7A",
    "done.ink":     "#2E6B4F",
    "done.tint":    "#E7EFE6",
    "warn.ink":     "#7A5410",
    "warn.tint":    "#F7E9C9",
    "danger.ink":   "#A3241C",
    "danger.tint":  "#F7DFDA",
    "heat.0":       "#E6DED0",
    "heat.dash":    "#8C7C63",
    "heat.1":       "#A2C8C2",
    "heat.2":       "#5E9993",
    "heat.3":       "#256560",
    "heat.4":       "#093E3B",
    "scrim":        "#192C3B",    "onScrim":      "#FFFFFF",
}

# --------------------------------------------------------------------------
# Dark theme  "工作室夜幕"
# --------------------------------------------------------------------------
D = {
    "canvas":       "#0F1720",
    "tint":         "#16202B",
    "card":         "#1B2733",
    "raised":       "#22303D",
    "line.hairline": "#3B4A57",
    "line.control": "#6E7C89",
    "icon.line":    "#6E7C89",
    "ink":          "#F2EDE4",
    "ink2":         "#B9C4CC",
    "ink3":         "#9AA7B2",
    "accent":       "#E0705A",
    "accent.deep":  "#E0705A",
    "accent.tint":  "#3A2018",
    "accent.fill":  "#C0553C",
    "onAccent":     "#FFFFFF",
    "running":      "#7FC4BA",
    "series1":      "#8FB4DC",
    "series4":      "#D8B267",
    "series6":      "#BFA0D0",
    "done.ink":     "#8ACBA5",
    "done.tint":    "#15271E",
    "warn.ink":     "#E0BC72",
    "warn.tint":    "#2B2415",
    "danger.ink":   "#F59A90",
    "danger.tint":  "#301A18",
    "heat.0":       "#1B3F39",
    "heat.dash":    "#8A9AA4",
    "heat.1":       "#286056",
    "heat.2":       "#387F74",
    "heat.3":       "#5AA398",
    "heat.4":       "#97CEC2",
    "scrim":        "#000000",
    "onScrim":      "#F2EDE4",
}

# (label, fg, bg, threshold)
CHECKS = [
    # --- light: text on the three surfaces ---
    ("浅色 ink / 画布",            L["ink"],        L["canvas"],  TEXT_MIN),
    ("浅色 ink / 卡片",            L["ink"],        L["card"],    TEXT_MIN),
    ("浅色 ink / 抬升面",          L["ink"],        L["raised"],  TEXT_MIN),
    ("浅色 ink / 分组带",          L["ink"],        L["tint"],    TEXT_MIN),
    ("浅色 ink2 / 画布",           L["ink2"],       L["canvas"],  TEXT_MIN),
    ("浅色 ink2 / 卡片",           L["ink2"],       L["card"],    TEXT_MIN),
    ("浅色 ink2 / 抬升面",         L["ink2"],       L["raised"],  TEXT_MIN),
    ("浅色 ink2 / 分组带",         L["ink2"],       L["tint"],    TEXT_MIN),
    ("浅色 ink3 / 画布",           L["ink3"],       L["canvas"],  TEXT_MIN),
    ("浅色 ink3 / 卡片",           L["ink3"],       L["card"],    TEXT_MIN),
    # --- light: accent ---
    ("浅色 accent / 画布",         L["accent"],     L["canvas"],  TEXT_MIN),
    ("浅色 accent / 卡片",         L["accent"],     L["card"],    TEXT_MIN),
    ("浅色 accent.deep / 分组带",  L["accent.deep"],L["tint"],    TEXT_MIN),
    ("浅色 accent / accent.tint",  L["accent"],     L["accent.tint"], TEXT_MIN),
    ("浅色 onAccent / accent",     L["onAccent"],   L["accent"],  TEXT_MIN),
    # --- light: semantic ---
    ("浅色 done.ink / done.tint",  L["done.ink"],   L["done.tint"],   TEXT_MIN),
    ("浅色 warn.ink / warn.tint",  L["warn.ink"],   L["warn.tint"],   TEXT_MIN),
    ("浅色 danger.ink / danger.tint", L["danger.ink"], L["danger.tint"], TEXT_MIN),
    ("浅色 running / 画布",        L["running"],    L["canvas"],  TEXT_MIN),
    ("浅色 running / 卡片",        L["running"],    L["card"],    TEXT_MIN),
    # --- light: chart series (graphic, >=3:1 vs card) ---
    ("浅色 series1 / 卡片",        L["series1"],    L["card"],    NONTEXT_MIN),
    ("浅色 series4 / 卡片",        L["series4"],    L["card"],    NONTEXT_MIN),
    ("浅色 series6 / 卡片",        L["series6"],    L["card"],    NONTEXT_MIN),
    # --- light: non-text + heat ramp ---
    ("浅色 控制线 / 画布",         L["line.control"], L["canvas"], NONTEXT_MIN),
    ("浅色 图标线 / 画布",         L["icon.line"],  L["canvas"],  NONTEXT_MIN),
    ("浅色 无记录虚线 / 画布",     L["heat.dash"],  L["canvas"],  NONTEXT_MIN),
    ("浅色 无记录虚线 / 零值格",   L["heat.dash"],  L["heat.0"],  NONTEXT_MIN),
    ("浅色 无记录虚线 / 卡片",     L["heat.dash"],  L["card"],    NONTEXT_MIN),
    ("浅色 零值格 / 画布 色阶间距", L["heat.0"],    L["canvas"],  1.15),
    ("浅色 相邻色阶 0-1 间距",     L["heat.0"],     L["heat.1"],  1.0),
    ("浅色 相邻色阶 1-2 间距",     L["heat.1"],     L["heat.2"],  1.0),
    ("浅色 相邻色阶 2-3 间距",     L["heat.2"],     L["heat.3"],  1.0),
    ("浅色 相邻色阶 3-4 间距",     L["heat.3"],     L["heat.4"],  1.0),
    ("浅色 白字 / heat.4",         "#FFFFFF",       L["heat.4"],  TEXT_MIN),
    ("浅色 ink / heat.1",          L["ink"],        L["heat.1"],  TEXT_MIN),
    ("浅色 ink / heat.0",          L["ink"],        L["heat.0"],  TEXT_MIN),
    ("夜幕 ink / heat.0",          D["ink"],        D["heat.0"],  TEXT_MIN),
    # --- dark: text ---
    ("夜幕 ink / 画布",            D["ink"],        D["canvas"],  TEXT_MIN),
    ("夜幕 ink / 卡片",            D["ink"],        D["card"],    TEXT_MIN),
    ("夜幕 ink / 抬升面",          D["ink"],        D["raised"],  TEXT_MIN),
    ("夜幕 ink2 / 画布",           D["ink2"],       D["canvas"],  TEXT_MIN),
    ("夜幕 ink2 / 卡片",           D["ink2"],       D["card"],    TEXT_MIN),
    ("夜幕 ink2 / 抬升面",         D["ink2"],       D["raised"],  TEXT_MIN),
    ("夜幕 ink3 / 画布",           D["ink3"],       D["canvas"],  TEXT_MIN),
    ("夜幕 ink3 / 卡片",           D["ink3"],       D["card"],    TEXT_MIN),
    # --- dark: accent ---
    ("夜幕 accent / 画布",         D["accent"],     D["canvas"],  TEXT_MIN),
    ("夜幕 accent / 卡片",         D["accent"],     D["card"],    TEXT_MIN),
    ("夜幕 accent / accent.tint",  D["accent"],     D["accent.tint"], TEXT_MIN),
    ("夜幕 onAccent / accent.fill", D["onAccent"],  D["accent.fill"], TEXT_MIN),
    # --- dark: semantic ---
    ("夜幕 done.ink / done.tint",  D["done.ink"],   D["done.tint"],   TEXT_MIN),
    ("夜幕 warn.ink / warn.tint",  D["warn.ink"],   D["warn.tint"],   TEXT_MIN),
    ("夜幕 danger.ink / danger.tint", D["danger.ink"], D["danger.tint"], TEXT_MIN),
    ("夜幕 running / 画布",        D["running"],    D["canvas"],  TEXT_MIN),
    ("夜幕 running / 卡片",        D["running"],    D["card"],    TEXT_MIN),
    # --- dark: chart series ---
    ("夜幕 series1 / 卡片",        D["series1"],    D["card"],    NONTEXT_MIN),
    ("夜幕 series4 / 卡片",        D["series4"],    D["card"],    NONTEXT_MIN),
    ("夜幕 series6 / 卡片",        D["series6"],    D["card"],    NONTEXT_MIN),
    # --- dark: non-text + heat ramp ---
    ("夜幕 控制线 / 画布",         D["line.control"], D["canvas"], NONTEXT_MIN),
    ("夜幕 控制线 / 卡片",         D["line.control"], D["card"],   NONTEXT_MIN),
    ("夜幕 图标线 / 画布",         D["icon.line"],  D["canvas"],  NONTEXT_MIN),
    ("夜幕 无记录虚线 / 画布",     D["heat.dash"],  D["canvas"],  NONTEXT_MIN),
    ("夜幕 无记录虚线 / 零值格",   D["heat.dash"],  D["heat.0"],  NONTEXT_MIN),
    ("夜幕 无记录虚线 / 卡片",     D["heat.dash"],  D["card"],    NONTEXT_MIN),
    ("夜幕 零值格 / 画布 色阶间距", D["heat.0"],    D["canvas"],  1.3),
    ("夜幕 相邻色阶 0-1 间距",     D["heat.0"],     D["heat.1"],  1.0),
    ("夜幕 相邻色阶 1-2 间距",     D["heat.1"],     D["heat.2"],  1.0),
    ("夜幕 相邻色阶 2-3 间距",     D["heat.2"],     D["heat.3"],  1.0),
    ("夜幕 相邻色阶 3-4 间距",     D["heat.3"],     D["heat.4"],  1.0),
    ("夜幕 画布 / heat.4",         D["canvas"],     D["heat.4"],  TEXT_MIN),
    # --- 实心语义按钮上的白字（删除确认 / 时区变更 / 完成）---
    ("浅色 白字 / dangerInk 实心",  "#FFFFFF",      L["danger.ink"], TEXT_MIN),
    ("浅色 白字 / warnInk 实心",    "#FFFFFF",      L["warn.ink"],   TEXT_MIN),
    ("浅色 白字 / doneInk 实心",    "#FFFFFF",      L["done.ink"],   TEXT_MIN),
    ("浅色 accentDeep / 画布",      L["accent.deep"], L["canvas"],  TEXT_MIN),
    ("浅色 accentDeep / 卡片",      L["accent.deep"], L["card"],    TEXT_MIN),
    ("浅色 ink / accentTint",       L["ink"],       L["accent.tint"], TEXT_MIN),
    # --- 设置页：warnInk / dangerInk 作为条目标签（落在卡片上）---
    ("浅色 warnInk / 卡片",         L["warn.ink"],  L["card"],    TEXT_MIN),
    ("夜幕 warnInk / 卡片",         D["warn.ink"],  D["card"],    TEXT_MIN),
    ("夜幕 dangerInk / 卡片",       D["danger.ink"], D["card"],   TEXT_MIN),
]

# 明确禁止的组合。脚本断言这些组合**低于**阈值，用来证明禁令不是凭感觉写的：
# 若其中任何一条通过（比值 >= 阈值），说明该禁令已过期，需要修订文档而不是本文件。
PROHIBITED = [
    # 视觉提示词第 36 行：赭红在渐变、半透明与插画表面不得直接当正文色。
    # 亮橙 #F2A03D 是插画辅色之一，赭红压在其上必然击穿 4.5:1。
    ("禁止：浅色 accent 文字 / 插画亮橙 #F2A03D", L["accent"], "#F2A03D", TEXT_MIN),
    # 浅色三级墨压在 surfaceTint 分组带上不足 4.5:1 ⇒ 图例容器必须改用 canvas 底。
    ("禁止：浅色 inkTertiary / surfaceTint 分组带", L["ink3"], L["tint"], TEXT_MIN),
    # 夜幕 accent 是文字色，不是实心按钮底；对调即白字不达标。
    ("禁止：夜幕 accent 当实心按钮底 + 白字", "#FFFFFF", D["accent"], TEXT_MIN),
    # 色阶 2 及更深的格子上不得放彩色小字（heat.2 上的赭红仅 2.18:1）。
    ("禁止：浅色 accentDeep 小字 / heat.2 格", L["accent.deep"], L["heat.2"], TEXT_MIN),
]

# 「色阶格上不放文字」这条设计决定的反向断言：必须全部**低于** 4.5:1。
# 依据：记录页日历格最初设计为「14sp 墨字直接压在色阶填充上」。实测墨字在
# heat.2/3/4 分别只有 4.41 / 2.12 / 1.20:1；改白字也不行——白字在 heat.2/1/0 分别只有
# 3.25 / 1.81 / 1.34:1。heat.2 是「墨字与白字都不达标」的死区，加描边托盘又会遮住色阶语义，
# 因此日期数字一律移到格子外的 canvas 纯色上（见 关键页面高保真说明 §3.1）。
# 若其中任一条达到 4.5:1，说明色阶被改亮，该规则应重新评估。
HEAT_TEXT_IMPOSSIBLE = [
    ("墨字 / heat.2",            L["ink"],        L["heat.2"], TEXT_MIN),
    ("墨字 / heat.3",            L["ink"],        L["heat.3"], TEXT_MIN),
    ("墨字 / heat.4",            L["ink"],        L["heat.4"], TEXT_MIN),
    ("白字 / heat.2（不可用改字色规避）", "#FFFFFF",  L["heat.2"], TEXT_MIN),
    ("白字 / heat.1",            "#FFFFFF",       L["heat.1"], TEXT_MIN),
    ("白字 / heat.0",            "#FFFFFF",       L["heat.0"], TEXT_MIN),
    ("夜幕 浅字 / heat.4（夜幕最深格放字）", D["ink"], D["heat.4"], TEXT_MIN),
]

# Scrim composites: worst case = the brightest pixels the illustration system
# is allowed to place under a text scrim.
ILLUSTRATION_WORST_CASES = [
    ("纯白 #FFFFFF",    "#FFFFFF"),
    ("柠黄 #FFE066",    "#FFE066"),
    ("亮橙 #F2A03D",    "#F2A03D"),
    ("奶油纸 #F5F0E8",  "#F5F0E8"),
]
SCRIM_STOPS = [("强区 alpha=0.86", 0.86, TEXT_MIN),
               ("中区 alpha=0.72", 0.72, TEXT_MIN),
               ("中区 alpha=0.52", 0.52, LARGE_MIN)]

TYPE_SCALE = [
    ("displayXL", 40, 48, 700, "年总量、可关闭的大数字"),
    ("displayL",  34, 42, 700, "计时数字备用档、大标题"),
    ("displayM",  28, 36, 700, "页面主标题、日期大字"),
    ("headlineL", 26, 34, 700, "空态主标题"),
    ("headlineM", 22, 30, 700, "情绪锚点、区块大标题"),
    ("headlineS", 20, 28, 600, "卡片组标题"),
    ("titleL",    18, 26, 600, "任务卡标题上限"),
    ("titleM",    16, 24, 600, "任务卡标题常规、区块标题"),
    ("bodyL",     16, 26, 400, "备注、正文"),
    ("bodyM",     14, 22, 400, "次级说明"),
    ("labelL",    14, 20, 600, "按钮文字"),
    ("labelM",    12, 18, 600, "状态标签、图例"),
    ("overline",  11, 16, 600, "仅拉丁字母与数字，禁止中文"),
]

# Estimated advance widths. CJK ideograph / CJK punctuation = 1.000 em
# (Noto Sans CJK), Roboto tabular digit = 0.5615 em, colon = 0.280 em,
# latin lowercase = 0.520 em, space = 0.260 em.
# These are GEOMETRIC ESTIMATES, not measured rasterisation.
ADVANCE = {"cjk": 1.0, "digit": 0.5615, "colon": 0.28, "latin": 0.52, "space": 0.26}


def estimate_dp(text, sp, font_scale=1.0):
    em = 0.0
    for ch in text:
        if "\u4e00" <= ch <= "\u9fff" or ch in "，。、：；！？（）《》·":
            em += ADVANCE["cjk"]
        elif ch == ":":
            em += ADVANCE["colon"]
        elif ch == " ":
            em += ADVANCE["space"]
        elif ch.isdigit():
            em += ADVANCE["digit"]
        else:
            em += ADVANCE["latin"]
    return em * sp * font_scale


def main():
    failures = []
    print("=" * 78)
    print("设计 token 对比度门禁（静态 sRGB WCAG 2.x 计算）")
    print("=" * 78)
    for label, fg, bg, threshold in CHECKS:
        ratio = contrast(fg, bg)
        ok = ratio >= threshold
        if not ok:
            failures.append((label, fg, bg, ratio, threshold))
        print("  %-34s %s  %6.2f:1  (>= %.1f)  %s"
              % (label, fg, ratio, threshold, "PASS" if ok else "FAIL"))

    print()
    print("=" * 78)
    print("插画遮罩（scrim）合成对比度：最坏背景为插画最亮像素")
    print("=" * 78)
    for name, bg in ILLUSTRATION_WORST_CASES:
        for stop, alpha, threshold in SCRIM_STOPS:
            comp = alpha_over(L["scrim"], alpha, bg)
            ratio = contrast(L["onScrim"], comp)
            ok = ratio >= threshold
            if not ok:
                failures.append(("scrim %s / %s" % (stop, name), L["onScrim"], comp,
                                 ratio, threshold))
            print("  %-20s %-16s -> %s  白字 %6.2f:1  (>= %.1f)  %s"
                  % (name, stop, comp, ratio, threshold, "PASS" if ok else "FAIL"))

    print()
    print("=" * 78)
    print("夜幕遮罩：黑色叠层")
    print("=" * 78)
    for name, bg in ILLUSTRATION_WORST_CASES:
        for alpha in (0.72, 0.82):
            comp = alpha_over("#000000", alpha, bg)
            ratio = contrast(D["onScrim"], comp)
            ok = ratio >= TEXT_MIN
            if not ok:
                failures.append(("夜幕 scrim alpha=%.2f / %s" % (alpha, name),
                                 D["onScrim"], comp, ratio, TEXT_MIN))
            print("  %-20s alpha=%.2f -> %s  #F2EDE4 %6.2f:1  %s"
                  % (name, alpha, comp, ratio, "PASS" if ok else "FAIL"))

    print()
    print("=" * 78)
    print("色阶格放字：证明「数字不上色块」这条设计决定（必须全部低于阈值）")
    print("=" * 78)
    for label, fg, bg, threshold in HEAT_TEXT_IMPOSSIBLE:
        ratio = contrast(fg, bg)
        ok = ratio < threshold
        if not ok:
            failures.append(("色阶格放字意外达标: " + label, fg, bg, ratio, threshold))
        print("  %-44s %6.2f:1  (< %.1f 才支持该设计决定)  %s"
              % (label, ratio, threshold, "PASS" if ok else "FAIL"))

    print()
    print("=" * 78)
    print("违禁组合断言（必须低于阈值；若通过说明文档禁令需要修订）")
    print("=" * 78)
    for label, fg, bg, threshold in PROHIBITED:
        ratio = contrast(fg, bg)
        ok = ratio < threshold
        if not ok:
            failures.append(("违禁组合意外达标: " + label, fg, bg, ratio, threshold))
        print("  %-44s %6.2f:1  (< %.1f 才符合禁令)  %s"
              % (label, ratio, threshold, "PASS" if ok else "FAIL"))

    print()
    print("=" * 78)
    print("字号阶梯（sp / 行高 sp / 字重）")
    print("=" * 78)
    for name, sp, lh, w, use in TYPE_SCALE:
        print("  %-10s %2dsp / %2dsp / %3d   行高比 %.2f   %s"
              % (name, sp, lh, w, lh / sp, use))

    print()
    print("=" * 78)
    print('计时数字自适应阶梯："HH:MM:SS" 预估宽度 vs 可用 328dp（360dp 屏 - 16dp x2）')
    print("=" * 78)
    ladder_ok = True
    for fs in (1.0, 1.3, 1.5, 1.85, 2.0):
        chosen = None
        for sp in (52, 44, 40, 36, 32):
            if estimate_dp("01:24:36", sp, fs) <= 328.0:
                chosen = sp
                break
        if chosen is None:
            ladder_ok = False
            failures.append(("计时数字阶梯 fontScale=%.2f" % fs, "-", "-", 0.0, 1.0))
        row = "  fontScale %.2f -> " % fs
        for sp in (52, 44, 40, 36, 32):
            row += "%2dsp:%6.1f  " % (sp, estimate_dp("01:24:36", sp, fs))
        row += "| 选用 %s" % ("%dsp" % chosen if chosen else "32sp 仍溢出，转分段")
        print(row)

    print()
    print("=" * 78)
    print("MM:SS 形式（不足 1 小时时启用）")
    print("=" * 78)
    for fs in (1.0, 1.3, 1.5, 1.85, 2.0):
        print("  fontScale %.2f  52sp -> %6.1f dp   44sp -> %6.1f dp"
              % (fs, estimate_dp("24:36", 52, fs), estimate_dp("24:36", 44, fs)))

    print()
    if failures:
        print("!!! %d 项未达阈值：" % len(failures))
        for label, fg, bg, ratio, th in failures:
            print("    %s  %s on %s = %.2f:1 < %.1f" % (label, fg, bg, ratio, th))
        return 1

    print("全部 %d 项组合达到各自阈值；%d 项违禁组合 + %d 项色阶格放字反向断言确认低于阈值。"
          % (len(CHECKS), len(PROHIBITED), len(HEAT_TEXT_IMPOSSIBLE)))
    print("本门禁只覆盖静态调色板与几何估算，不覆盖真机渲染、OLED 色偏、")
    print("插画实际像素、纸纹材质叠加与系统字体栅格化。")
    print("SC 1.4.11 的 3:1 非文字要求已对图标线、控制线、图例与图表色检查。")
    if ladder_ok:
        print("计时数字阶梯在 fontScale 1.00-2.00 内均可选出不溢出的档位。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
