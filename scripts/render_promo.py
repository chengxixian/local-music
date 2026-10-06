#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
local music 宣传动画渲染脚本（独立于 Android 工程）。

视觉：**一个字母一个点阵块**（早期计算器/字符位显示）。点阵按字母分块，
块内 5x7 灰点阵，块与块之间保持截断（没有连接的点）。

时间轴（总长 15.0s，按**秒**定义阶段）：
    0.0 - 0.5    全黑停留
    0.5 - 3.2    两个字母块（L 与 m）的灰点阵，自红点位置向外涟漪状淡入
    3.0 - 5.2    Lm 字形点亮成白（带放大回弹）
    5.2 - 6.6    m 右上角的点（红点）闪一圈红光
    6.6 - 10.2   L 块不动、m 块向右拉开；o c a l ␣ u s i c 各自独立成块依次亮起
    10.2- 11.2   成形保持（白字 + 红点，涟漪持续）
    11.2- 11.5   红点再闪一下，发出冲击波
    11.2- 13.6   冲击波向外扫过：被扫过的点**统一褪成灰色**（白字变灰、红点也变灰），
                 整个画面最终成为一片均匀的灰色点阵
    13.6- 15.0   全灰点阵，涟漪继续从红点原本的位置发出（那里现在也是灰点）

性能：4K/60fps 下每帧 33MB，若落 PNG 会写出几十 GB。所以本脚本**直接管道**把
      原始画面喂给 ffmpeg（rawvideo），不产生中间文件；红光晕/冲击波环在 1/4
      分辨率上绘制并模糊再放大。

用法：
    python render_promo.py                       # 默认 4K 60fps
    python render_promo.py --width 1920 --height 1080 --fps 30
"""

import argparse
import math
import os
import shutil
import subprocess

from PIL import Image, ImageDraw, ImageFilter

TEXT = "local music"

GLYPHS = {
    'l': ["X....", "X....", "X....", "X....", "X....", "X....", "XXXXX"],
    'o': [".XXX.", "X...X", "X...X", "X...X", ".XXX."],
    'c': [".XXX.", "X....", "X....", "X....", ".XXX."],
    'a': [".XXX.", "....X", ".XXXX", "X...X", ".XXXX"],
    'm': ["X.X.X", "XXXXX", "X.X.X", "X.X.X", "X.X.X"],
    'u': ["X...X", "X...X", "X...X", "X...X", ".XXXX"],
    's': [".XXXX", "X....", ".XXX.", "....X", "XXXX."],
    'i': ["..X..", ".....", "..X..", "..X..", "..X..", "..X.."],
    ' ': [".....", "....."],
}

GLYPH_W, GAP, SPACE_W, CELL_ROWS = 5, 1, 3, 7

# ── 时间轴（秒）──
DURATION = 18.0
T_GRID = (0.5, 3.2)          # 灰点阵涟漪淡入
T_LIGHT = (3.0, 5.2)         # Lm 点亮
T_FLASH = (5.2, 6.6)         # 红点首次闪光
T_MORPH = (6.6, 10.2)        # 拉开 + 补全
T_HOLD = 11.2                # 成形保持到此刻
T_SHOCK = (11.2, 14.0)       # 冲击波扫过（全画面转灰）
SHOCK_FLASH = 0.30           # 冲击波起步时红点再闪一下的时长
SHOCK_RING_LIFE = 1.20       # 可见冲击波环的存续时间
SHOCK_SOFT = 1.50            # 单点褪色过渡宽度（格）

RIPPLE_PERIOD = 1.7          # 涟漪周期（秒）
RIPPLE_WAVELEN = 3.0         # 涟漪波长（格）
GLOW_DIV = 4                 # 光晕/环按 1/N 分辨率计算

WHITE = (255, 255, 255)
GRAY = (150, 150, 150)       # 褪色后的点颜色
GRID_STEADY = 0.22           # 褪色后点阵的稳定亮度（0..1）


def clamp01(v):
    return 0.0 if v < 0 else (1.0 if v > 1 else v)


def smoothstep(a):
    return a * a * (3 - 2 * a)


def ease_out(a):
    return 1 - (1 - a) ** 3


def span(t, rng):
    return clamp01((t - rng[0]) / max(rng[1] - rng[0], 1e-6))


def build_layout():
    blocks = []
    cursor = 0
    for gi, ch in enumerate(TEXT):
        g = GLYPHS.get(ch)
        if g is None:
            continue
        width = SPACE_W if ch == ' ' else GLYPH_W
        top = CELL_ROWS - len(g)
        rows = {top + r: row for r, row in enumerate(g)}
        blocks.append({'ch': ch, 'gi': gi, 'final': cursor, 'width': width,
                       'rows': rows, 'lines': g})
        cursor += width + GAP
    return blocks, cursor - GAP


def main(out_path, width, height, fps):
    blocks, total_cols = build_layout()
    l_block = blocks[0]
    m_block = next(b for b in blocks if b['ch'] == 'm')
    INIT_COL = {id(l_block): 0, id(m_block): 6}
    src_row, src_col_off = 2, 4          # 红点：m 块内字形 m 的右上角
    max_d = math.hypot(total_cols, CELL_ROWS)
    shock_span = max_d + 2.0             # 冲击波要扫过的最大距离（格）

    total_frames = int(round(DURATION * fps))
    print("帧数 %d（%.1fs @ %dfps，%dx%d）" % (total_frames, DURATION, fps, width, height))

    ffmpeg = shutil.which("ffmpeg") or r"D:\tool\ffmpeg\bin\ffmpeg.exe"
    cmd = [
        ffmpeg, "-y",
        "-f", "rawvideo", "-pix_fmt", "rgb24",
        "-s", "%dx%d" % (width, height), "-r", str(fps), "-i", "-",
        "-an", "-c:v", "libx264", "-preset", "medium", "-crf", "16",
        "-pix_fmt", "yuv420p", "-movflags", "+faststart",
        out_path,
    ]
    print("编码:", " ".join(cmd))
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)

    gw, gh = max(width // GLOW_DIV, 1), max(height // GLOW_DIV, 1)
    scale = 1.0 / GLOW_DIV

    try:
        for fi in range(total_frames):
            t = fi / float(fps)

            grid_a = span(t, T_GRID)
            light = span(t, T_LIGHT)
            flash = clamp01((t - T_FLASH[0]) / 0.20)
            settled = clamp01((t - T_FLASH[1]) / 0.25)
            morph = span(t, T_MORPH)
            ease = smoothstep(morph)
            eo = ease_out(ease)
            pulse = max(0.0, math.sin(flash * math.pi))

            # 冲击波：front 是"已扫过的距离"（格）
            shock_p = span(t, T_SHOCK)
            shock_front = shock_p * shock_span
            ring_a = clamp01(1.0 - (t - T_SHOCK[0]) / SHOCK_RING_LIFE) if t >= T_SHOCK[0] else 0.0
            flash2 = clamp01((t - T_HOLD) / SHOCK_FLASH) if t >= T_HOLD else 0.0
            flash2 = math.sin(flash2 * math.pi) if 0.0 < flash2 < 1.0 else 0.0

            active_cols = 11 + (total_cols - 11) * ease
            cell = min(width / (active_cols + 3.0), height / 6.5)
            ox = (width - active_cols * cell) / 2.0
            oy = (height - CELL_ROWS * cell) / 2.0
            dot_r = cell * 0.15

            def px(c, r):
                return (ox + (c + 0.5) * cell, oy + (r + 0.5) * cell)

            img = Image.new("RGB", (width, height), (0, 0, 0))
            glow = Image.new("RGBA", (gw, gh), (0, 0, 0, 0))
            d = ImageDraw.Draw(img)
            g = ImageDraw.Draw(glow)

            def circle(draw, xy, radius, color, s=1.0):
                x, y = xy[0] * s, xy[1] * s
                rr = radius * s
                draw.ellipse([x - rr, y - rr, x + rr, y + rr], fill=color)

            def block_col(b):
                init = INIT_COL.get(id(b))
                return b['final'] if init is None else init + (b['final'] - init) * eo

            cur_src_x = block_col(m_block) + src_col_off

            # 单点褪色比例：0=原样，1=已成灰点
            def convert_at(cx, cy):
                dist = math.hypot(cx - cur_src_x, cy - src_row)
                return clamp01((shock_front - dist) / SHOCK_SOFT)

            # 冲击波把**整屏**铺成点阵（用与字母一致的格距，所以字母块内的点不会错位重复）
            if shock_p > 0:
                c0 = int(math.floor((0 - ox) / cell)) - 1
                c1 = int(math.ceil((width - ox) / cell)) + 1
                r0 = int(math.floor((0 - oy) / cell)) - 1
                r1 = int(math.ceil((height - oy) / cell)) + 1
                scx = ox + (cur_src_x + 0.5) * cell
                scy = oy + (src_row + 0.5) * cell
                for rr_ in range(r0, r1 + 1):
                    for cc_ in range(c0, c1 + 1):
                        cx_ = ox + (cc_ + 0.5) * cell
                        cy_ = oy + (rr_ + 0.5) * cell
                        dpx = math.hypot(cx_ - scx, cy_ - scy)
                        conv = clamp01((shock_front * cell - dpx) / (SHOCK_SOFT * cell))
                        if conv <= 0.01:
                            continue
                        wave = 0.5 + 0.5 * math.sin(2 * math.pi * (t / RIPPLE_PERIOD - dpx / (RIPPLE_WAVELEN * cell)))
                        shade = int(255 * clamp01(GRID_STEADY * (0.28 + 1.10 * wave) * conv))
                        circle(d, (cx_, cy_), dot_r * (0.70 + 0.60 * wave), (shade, shade, shade))
            for b in blocks:
                bc = block_col(b)
                is_old = id(b) in INIT_COL
                if is_old:
                    appear = grid_a
                else:
                    gi_norm = b['gi'] / float(len(TEXT) - 1)
                    appear = clamp01((t - (T_MORPH[0] + 0.35) - gi_norm * 1.30) / 1.10)
                if appear <= 0.01:
                    continue

                # ① 块内 5x7 灰点阵（块外无点 -> 字母之间截断）
                for r in range(CELL_ROWS):
                    for c in range(b['width']):
                        gx = bc + c
                        conv = convert_at(gx, r)
                        dist = math.hypot(gx - cur_src_x, r - src_row)
                        front = clamp01((t - T_GRID[0] - (dist / max_d) * 1.60) / 0.90)
                        if front <= 0.01:
                            continue
                        wave = 0.5 + 0.5 * math.sin(2 * math.pi * (t / RIPPLE_PERIOD - dist / RIPPLE_WAVELEN))
                        base = 0.10 + 0.44 * wave
                        base *= 1.0 - 0.30 * ease
                        # 冲击波扫过之后，点阵稳定在一个较亮的灰（整屏成为均匀灰点阵）
                        base = base * (1.0 - conv) + GRID_STEADY * (0.28 + 1.10 * wave) * conv
                        shade = int(255 * clamp01(front * base * appear))
                        circle(d, px(gx, r), dot_r, (shade, shade, shade))

                # ② 块内白色字形（被冲击波扫过后褪成灰点）
                for r, line in b['rows'].items():
                    for c, chv in enumerate(line):
                        if chv != 'X':
                            continue
                        a = light if is_old else appear
                        if a <= 0.01:
                            continue
                        gx = bc + c
                        conv = convert_at(gx, r)
                        grow = 1.0 + 0.3 * (1.0 - a)
                        is_red = (b is m_block and r == src_row and c == src_col_off)
                        if is_red:
                            # 红点：首次闪光 -> 被冲击波扫过时变灰
                            alive = 1.0 - conv
                            if (flash > 0 or settled > 0) and alive > 0.02:
                                circle(g, px(gx, r), dot_r * (3.0 + 2.6 * pulse),
                                       (255, 59, 48, int(110 * pulse)), scale)
                                circle(d, px(gx, r), dot_r * (1.0 + 0.7 * pulse), (255, 59, 48))
                            elif flash2 > 0 and alive > 0.02:
                                # 冲击波起步：再闪一下
                                circle(g, px(gx, r), dot_r * (3.2 + 3.0 * flash2),
                                       (255, 59, 48, int(150 * flash2)), scale)
                                circle(d, px(gx, r), dot_r * (1.0 + 1.0 * flash2), (255, 59, 48))
                            else:
                                col = tuple(int(RED_C * (1 - conv) + GRAY_C * conv)
                                            for RED_C, GRAY_C in zip((255, 59, 48), GRAY))
                                radius = dot_r * ((1.0 + 0.7 * max(pulse, settled)) * (1 - conv) + 1.0 * conv)
                                circle(d, px(gx, r), radius, col)
                        else:
                            conv = convert_at(gx, r)
                            if is_old:
                                dly = (math.hypot(gx - cur_src_x, r - src_row) / max_d) * 0.45
                                lp = clamp01((light - dly) / 0.30)
                                fl = max(0.0, math.sin(math.pi * lp))
                            else:
                                fl = 0.0
                            col = tuple(int(255 * (1 - conv) + GRAY_C * conv) for GRAY_C in GRAY)
                            radius = dot_r * (grow * (1 - conv) + 1.0 * conv) * (1.0 + 0.45 * fl)
                            circle(d, px(gx, r), radius, col)
                            if fl > 0.04:
                                circle(g, px(gx, r), dot_r * (1.5 + 1.1 * fl), (255, 255, 255, int(70 * fl)), scale)

            # 全屏点阵里重新浮现：Lm 与完整名称（从红点位置向外逐点亮起）
            re = clamp01((t - 15.0) / 1.4)
            if re > 0.01:
                for b in blocks:
                    bc = b['final']
                    for r_, line in b['rows'].items():
                        for c_, chv in enumerate(line):
                            if chv != 'X':
                                continue
                            gx_ = bc + c_
                            d_ = math.hypot(gx_ - cur_src_x, r_ - src_row)
                            a_ = clamp01(re * 2.6 - d_ / (max_d * 2.0))
                            if a_ <= 0.01:
                                continue
                            sh = int(255 * a_)
                            circle(d, px(gx_, r_), dot_r * (0.80 + 0.55 * a_), (sh, sh, sh))
            # 冲击波可见圆环（1/4 分辨率绘制 + 模糊）
            if ring_a > 0.01 and t >= T_SHOCK[0]:
                rr = shock_front * cell * scale
                cx, cy = px(cur_src_x, src_row)
                cx, cy = cx * scale, cy * scale
                w = max(1.0, cell * 0.55 * scale)
                g.ellipse([cx - rr, cy - rr, cx + rr, cy + rr],
                          outline=(255, 255, 255, int(210 * ring_a)), width=int(w))

            # 红点位置的持续涟漪微光（冲击波之后依然从这发出，只是点本身已经是灰的）
            if settled > 0:
                breathe = 0.5 + 0.5 * math.sin(2 * math.pi * t / RIPPLE_PERIOD)
                circle(g, px(cur_src_x, src_row), dot_r * (2.2 + 0.9 * breathe),
                       (255, 59, 48, int((65 * (1.0 - clamp01(shock_p)) ) * breathe)), scale)

            glow = glow.filter(ImageFilter.GaussianBlur(radius=max(1.0, cell * 0.4 * scale)))
            glow = glow.resize((width, height), Image.BILINEAR)
            img = Image.alpha_composite(img.convert("RGBA"), glow).convert("RGB")

            proc.stdin.write(img.tobytes())
            if fi % 60 == 0:
                print("  帧 %d/%d" % (fi, total_frames), flush=True)
    finally:
        proc.stdin.close()
        proc.wait()

    print("完成:", out_path, os.path.getsize(out_path) // 1024, "KB")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=r"D:\dsh work region\video\local-music-promo-4k60.mp4")
    ap.add_argument("--width", type=int, default=3840)
    ap.add_argument("--height", type=int, default=2160)
    ap.add_argument("--fps", type=int, default=60)
    a = ap.parse_args()
    main(a.out, a.width, a.height, a.fps)
