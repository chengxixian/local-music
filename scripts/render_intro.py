#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
介绍段动画（与开场动画的点阵**严格对齐**）。

对齐做法：格距 cell 与原点 (ox, oy) 使用与 render_promo.py **完全相同**的公式：
    total_cols 由同一套字形/字距推导（这里取 "local music" 的宽度，与开场结尾一致）
    cell = min(width / (total_cols + 3), height / 6.5)
    ox   = (width - total_cols * cell) / 2
    oy   = (height - CELL_ROWS * cell) / 2
所以两段视频裁接时，点与点落在同一个网格上。

版面：**右侧**放介绍文字（点阵淡入 + 涟漪扫过），**左侧留空**（只保留极暗的点阵，
        方便把实机演示视频贴上去；如需完全纯黑，把 LEFT_DIM 设为 0）。

用法：
    python render_intro.py                                     # 4K60
    python render_intro.py --width 1920 --height 1080 --fps 30  # 快速预览
"""

import argparse
import math
import os
import shutil
import subprocess

from PIL import Image, ImageDraw, ImageFilter

# 与大写/小写字形（5 列；大写 7 行，小写为 x-height 行数，底边对齐）
GLYPHS = {
    # 小写
    'l': ["X....", "X....", "X....", "X....", "X....", "X....", "XXXXX"],
    'o': [".XXX.", "X...X", "X...X", "X...X", ".XXX."],
    'c': [".XXX.", "X....", "X....", "X....", ".XXX."],
    'a': [".XXX.", "....X", ".XXXX", "X...X", ".XXXX"],
    'm': ["X.X.X", "XXXXX", "X.X.X", "X.X.X", "X.X.X"],
    'u': ["X...X", "X...X", "X...X", "X...X", ".XXXX"],
    's': [".XXXX", "X....", ".XXX.", "....X", "XXXX."],
    'i': ["..X..", ".....", "..X..", "..X..", "..X..", "..X.."],
    # 大写
    'A': [".XXX.", "X...X", "X...X", "XXXXX", "X...X", "X...X", "X...X"],
    'B': ["XXXX.", "X...X", "X...X", "XXXX.", "X...X", "X...X", "XXXX."],
    'C': [".XXX.", "X...X", "X....", "X....", "X....", "X...X", ".XXX."],
    'D': ["XXXX.", "X...X", "X...X", "X...X", "X...X", "X...X", "XXXX."],
    'F': ["XXXXX", "X....", "X....", "XXXX.", "X....", "X....", "X...."],
    'H': ["X...X", "X...X", "X...X", "XXXXX", "X...X", "X...X", "X...X"],
    'I': ["XXXXX", "..X..", "..X..", "..X..", "..X..", "..X..", "XXXXX"],
    'L': ["X....", "X....", "X....", "X....", "X....", "X....", "XXXXX"],
    'O': [".XXX.", "X...X", "X...X", "X...X", "X...X", "X...X", ".XXX."],
    'S': [".XXXX", "X....", "X....", ".XXX.", "....X", "....X", "XXXX."],
    'U': ["X...X", "X...X", "X...X", "X...X", "X...X", "X...X", ".XXX."],
    ' ': [".....", "....."],
}

GLYPH_W, GAP, SPACE_W, CELL_ROWS = 5, 1, 3, 7
GRID_TEXT = "local music"          # 只用来推导宽度，保证与开场同一网格

# ── 版面 ──
LINES = ["local", "music", "HIFI", "FLAC"]   # 右侧自上而下的四行
LEFT_KEEP = 0.44                    # 左侧 44% 留给实机演示
LEFT_DIM = 0.10                     # 左侧点阵亮度（0 = 完全纯黑）
RIGHT_X = 0.52                      # 介绍文字的起始列比例

DURATION = 12.0
LINE_STEP = (1.2, 3.6)              # 每行点亮的时间间隔（秒）
LINE_FADE = 1.1                     # 单行淡入时长
RIPPLE_PERIOD = 1.7
RIPPLE_WAVELEN = 3.0
GLOW_DIV = 4


def clamp01(v):
    return 0.0 if v < 0 else (1.0 if v > 1 else v)


def text_width(text):
    w = 0
    for ch in text:
        w += (SPACE_W if ch == ' ' else GLYPH_W) + GAP
    return w - GAP


def grid_geometry(width, height):
    """与 render_promo.py 完全一致的网格几何。"""
    total_cols = text_width(GRID_TEXT)
    cell = min(width / (total_cols + 3.0), height / 6.5)
    ox = (width - total_cols * cell) / 2.0
    oy = (height - CELL_ROWS * cell) / 2.0
    return total_cols, cell, ox, oy


def main(out_path, width, height, fps):
    total_cols, cell, ox, oy = grid_geometry(width, height)
    print("网格: total_cols=%d cell=%.2f ox=%.2f oy=%.2f" % (total_cols, cell, ox, oy))

    # 右侧每行的落位（按格）
    right_col = int(round((width * RIGHT_X - ox) / cell))
    line_rows = CELL_ROWS + 2
    top_row = int(round((CELL_ROWS / 2.0) - (len(LINES) * line_rows) / 2.0))

    total_frames = int(round(DURATION * fps))
    print("帧数 %d（%.1fs @ %dfps，%dx%d）" % (total_frames, DURATION, fps, width, height))

    ffmpeg = shutil.which("ffmpeg") or r"D:\tool\ffmpeg\bin\ffmpeg.exe"
    cmd = [ffmpeg, "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
           "-s", "%dx%d" % (width, height), "-r", str(fps), "-i", "-",
           "-an", "-c:v", "libx264", "-preset", "medium", "-crf", "16",
           "-pix_fmt", "yuv420p", "-movflags", "+faststart", out_path]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)

    gw, gh = max(width // GLOW_DIV, 1), max(height // GLOW_DIV, 1)
    scale = 1.0 / GLOW_DIV
    dot_r = cell * 0.155

    # 涟漪源头：与开场结尾一致（红点位置 → 用文字块 m 的右上角）
    src_col = text_width("local m") + GAP + 4
    src_row = 2

    # 预排每行的点
    rendered_lines = []
    for i, line in enumerate(LINES):
        pts = []
        cursor = right_col
        row0 = top_row + i * line_rows
        for ch in line:
            g = GLYPHS.get(ch)
            if g is None:
                cursor += GLYPH_W + GAP
                continue
            top = row0 + (CELL_ROWS - len(g))
            for r, row in enumerate(g):
                for c, v in enumerate(row):
                    if v == 'X':
                        pts.append((cursor + c, top + r))
            cursor += (SPACE_W if ch == ' ' else GLYPH_W) + GAP
        rendered_lines.append(pts)

    try:
        for fi in range(total_frames):
            t = fi / float(fps)
            img = Image.new("RGB", (width, height), (0, 0, 0))
            glow = Image.new("RGBA", (gw, gh), (0, 0, 0, 0))
            d = ImageDraw.Draw(img)
            g = ImageDraw.Draw(glow)

            def circle(draw, xy, radius, color, s=1.0):
                x, y = xy[0] * s, xy[1] * s
                rr = radius * s
                draw.ellipse([x - rr, y - rr, x + rr, y + rr], fill=color)

            def px(c, r):
                return (ox + (c + 0.5) * cell, oy + (r + 0.5) * cell)

            scx = ox + (src_col + 0.5) * cell
            scy = oy + (src_row + 0.5) * cell

            # ① 满屏点阵（与开场同网格）；左侧压到极暗，留出实机演示空间
            c0 = int(math.floor((0 - ox) / cell)) - 1
            c1 = int(math.ceil((width - ox) / cell)) + 1
            r0 = int(math.floor((0 - oy) / cell)) - 1
            r1 = int(math.ceil((height - oy) / cell)) + 1
            for rr_ in range(r0, r1 + 1):
                for cc_ in range(c0, c1 + 1):
                    cx_ = ox + (cc_ + 0.5) * cell
                    cy_ = oy + (rr_ + 0.5) * cell
                    dpx = math.hypot(cx_ - scx, cy_ - scy)
                    wave = 0.5 + 0.5 * math.sin(2 * math.pi * (t / RIPPLE_PERIOD - dpx / (RIPPLE_WAVELEN * cell)))
                    left = cx_ < width * LEFT_KEEP
                    amp = LEFT_DIM if left else 0.42
                    shade = int(255 * clamp01(amp * (0.25 + 1.0 * wave)))
                    circle(d, (cx_, cy_), dot_r * (0.70 + 0.55 * wave), (shade, shade, shade))

            # ② 右侧四行：依次淡入，落位后保持
            for i, pts in enumerate(rendered_lines):
                start = LINE_STEP[0] + i * (LINE_STEP[1] - LINE_STEP[0]) / max(len(LINES) - 1, 1)
                a_line = clamp01((t - start) / LINE_FADE)
                if a_line <= 0.01:
                    continue
                for (cc_, rr_) in pts:
                    dpx = math.hypot(ox + (cc_ + 0.5) * cell - scx, oy + (rr_ + 0.5) * cell - scy)
                    a_pt = clamp01(a_line * 2.2 - dpx / (max(width, height) * 0.75))
                    if a_pt <= 0.01:
                        continue
                    fl = max(0.0, math.sin(math.pi * clamp01(a_pt / 0.55)))
                    sh = int(255 * a_pt)
                    circle(d, px(cc_, rr_), dot_r * (1.0 + 0.45 * fl), (sh, sh, sh))
                    if fl > 0.04:
                        circle(g, px(cc_, rr_), dot_r * (1.5 + 1.0 * fl), (255, 255, 255, int(60 * fl)), scale)

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
    ap.add_argument("--out", default=r"D:\dsh work region\promo\local-music-intro-4k60.mp4")
    ap.add_argument("--width", type=int, default=3840)
    ap.add_argument("--height", type=int, default=2160)
    ap.add_argument("--fps", type=int, default=60)
    a = ap.parse_args()
    main(a.out, a.width, a.height, a.fps)
