#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
local music 宣传动画渲染脚本（独立于 Android 工程）。

视觉：**一个字母一个点阵块**（早期计算器/字符位显示）。
点阵按字母分块，块内 5x7 灰点阵，块与块之间保持截断（没有连接的点）。

时间轴（总长 12.0s，按**秒**定义阶段，便于延长）：
    0.0-0.5    全黑停留
    0.5-3.2    两个字母块（L 与 m）的灰点阵，自红点位置向外涟漪状淡入
    3.0-5.2    Lm 字形点亮成白（带放大回弹）
    5.2-6.6    m 右上角的点（红点）闪一圈红光
    6.6-10.2   L 块不动、m 块向右拉开到 "music"；中间的 o c a l ␣ u s i c
               各自以独立点阵块从左到右依次亮起，块间空隙始终截断
    10.2-12.0  成形定格；红点持续放出涟漪扫过所有字母块

性能：4K/60fps 下每帧 33 MB，若落 PNG 会写出几十 GB。所以本脚本**直接管道**把
      原始画面喂给 ffmpeg（rawvideo），不产生中间文件；红光晕在 1/4 分辨率上做模糊再放大。

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
DURATION = 12.0
T_GRID = (0.5, 3.2)      # 灰点阵涟漪淡入
T_LIGHT = (3.0, 5.2)     # Lm 点亮
T_FLASH = (5.2, 6.6)     # 红点闪光
T_MORPH = (6.6, 10.2)    # 拉开 + 补全
RIPPLE_PERIOD = 1.7      # 涟漪周期（秒）
RIPPLE_WAVELEN = 3.0     # 涟漪波长（格）
GLOW_DIV = 4             # 光晕按 1/N 分辨率计算


def clamp01(v):
    return 0.0 if v < 0 else (1.0 if v > 1 else v)


def smoothstep(a):
    return a * a * (3 - 2 * a)


def ease_out(a):
    return 1 - (1 - a) ** 3


def span(t, rng):
    """把时间 t 映射到阶段 rng 的 0..1 进度。"""
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


def main(out_path, width, height, fps, keep_frames=False):
    blocks, total_cols = build_layout()
    l_block = blocks[0]
    m_block = next(b for b in blocks if b['ch'] == 'm')
    INIT_COL = {id(l_block): 0, id(m_block): 6}
    src_row, src_col_off = 2, 4          # 红点：m 块内字形 m 的右上角
    max_d = math.hypot(total_cols, CELL_ROWS)

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
            v = clamp01(t / DURATION)

            grid_a = span(t, T_GRID)
            light = span(t, T_LIGHT)
            flash = clamp01((t - T_FLASH[0]) / 0.20)
            settled = clamp01((t - T_FLASH[1]) / 0.25)
            morph = span(t, T_MORPH)
            ease = smoothstep(morph)
            eo = ease_out(ease)
            pulse = max(0.0, math.sin(flash * math.pi))

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
                        dist = math.hypot(gx - cur_src_x, r - src_row)
                        front = clamp01((t - T_GRID[0] - (dist / max_d) * 1.60) / 0.90)
                        if front <= 0.01:
                            continue
                        wave = 0.5 + 0.5 * math.sin(2 * math.pi * (t / RIPPLE_PERIOD - dist / RIPPLE_WAVELEN))
                        base = 0.15 + 0.28 * wave
                        base *= 1.0 - 0.30 * ease
                        shade = int(255 * clamp01(front * base * appear))
                        circle(d, px(gx, r), dot_r, (shade, shade, shade))

                # ② 块内白色字形
                for r, line in b['rows'].items():
                    for c, chv in enumerate(line):
                        if chv != 'X':
                            continue
                        a = light if is_old else appear
                        if a <= 0.01:
                            continue
                        grow = 1.0 + 0.3 * (1.0 - a)
                        if b is m_block and r == src_row and c == src_col_off:
                            if flash > 0 or settled > 0:
                                circle(g, px(bc + c, r), dot_r * (3.0 + 2.6 * pulse),
                                       (255, 59, 48, int(110 * pulse)), scale)
                                circle(d, px(bc + c, r), dot_r * (1.0 + 0.7 * pulse), (255, 59, 48))
                            else:
                                shade = int(255 * a)
                                circle(d, px(bc + c, r), dot_r * grow, (shade, shade, shade))
                        else:
                            shade = int(255 * a)
                            circle(d, px(bc + c, r), dot_r * grow, (shade, shade, shade))

            # 红点持续微光（涟漪源头）
            if settled > 0:
                breathe = 0.5 + 0.5 * math.sin(2 * math.pi * t / RIPPLE_PERIOD)
                circle(g, px(cur_src_x, src_row), dot_r * (2.2 + 0.9 * breathe),
                       (255, 59, 48, int(65 * breathe)), scale)

            glow = glow.filter(ImageFilter.GaussianBlur(radius=max(1.0, cell * 0.4 * scale)))
            glow = glow.resize((width, height), Image.BILINEAR)
            img = Image.alpha_composite(img.convert("RGBA"), glow).convert("RGB")

            proc.stdin.write(img.tobytes())
            if fi % 30 == 0:
                print("  帧 %d/%d" % (fi, total_frames), flush=True)
    finally:
        proc.stdin.close()
        proc.wait()

    print("完成:", out_path, os.path.getsize(out_path) // 1024, "KB")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=r"D:\dsh work region\promo\local-music-promo-4k60.mp4")
    ap.add_argument("--width", type=int, default=3840)
    ap.add_argument("--height", type=int, default=2160)
    ap.add_argument("--fps", type=int, default=60)
    a = ap.parse_args()
    main(a.out, a.width, a.height, a.fps)
