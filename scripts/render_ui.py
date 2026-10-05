#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
第三段介绍动画：液态玻璃 + iPod 式滚轮。

与第一段（开场）、第二段（介绍）共用同一网格与涟漪相位，拼接严格对齐：
    OPENING_TAIL = 30.0   # 开场 18s + 介绍段 12s，涟漪相位从这里继续
    cell / ox / oy 公式与 render_promo.py、render_intro.py 完全一致

版面：右侧上方用**点阵画一个 iPod 式滚轮**（24 点圆环 + 中心键 + 四向键），
      下方三行 GLASS / CLICK / WHEEL；左侧 44% 渐变压暗留实机演示。
衔接：开头 1.3s 先保持第二段收尾的四行（local / music / HIFI / FLAC），再淡出过渡。

用法：
    python render_ui.py                                      # 4K60
    python render_ui.py --width 1920 --height 1080 --fps 30   # 快速预览
"""

import argparse
import math
import os
import shutil
import subprocess

from PIL import Image, ImageDraw, ImageFilter

GLYPHS = {
    # 小写（沿用前两段）
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
    'E': ["XXXXX", "X....", "XXXX.", "X....", "X....", "X....", "XXXXX"],
    'F': ["XXXXX", "X....", "X....", "XXXX.", "X....", "X....", "X...."],
    'G': [".XXX.", "X...X", "X....", "X..XX", "X...X", "X...X", ".XXX."],
    'H': ["X...X", "X...X", "X...X", "XXXXX", "X...X", "X...X", "X...X"],
    'I': ["XXXXX", "..X..", "..X..", "..X..", "..X..", "..X..", "XXXXX"],
    'K': ["X...X", "X..X.", "X.X..", "XX...", "X.X..", "X..X.", "X...X"],
    'L': ["X....", "X....", "X....", "X....", "X....", "X....", "XXXXX"],
    'O': [".XXX.", "X...X", "X...X", "X...X", "X...X", "X...X", ".XXX."],
    'S': [".XXXX", "X....", "X....", ".XXX.", "....X", "....X", "XXXX."],
    'U': ["X...X", "X...X", "X...X", "X...X", "X...X", "X...X", ".XXX."],
    'V': ["X...X", "X...X", "X...X", "X...X", "X...X", ".X.X.", "..X.."],
    'W': ["X...X", "X...X", "X...X", "X.X.X", "X.X.X", "XX.XX", "X...X"],
    'Y': ["X...X", "X...X", ".X.X.", "..X..", "..X..", "..X..", "..X.."],
    ' ': [".....", "....."],
}

GLYPH_W, GAP, SPACE_W, CELL_ROWS = 5, 1, 3, 7
GRID_TEXT = "local music"                 # 只用于推导网格
LINES = ["COVER", "LYRIC"]                # 本段正文：自动补全封面与歌词（不涉及任何音源平台）
BRIDGE_LINES = ["local", "music", "HIFI", "FLAC"]   # 上一段的收尾，用于衔接

LEFT_KEEP = 0.44
LEFT_DIM = 0.10
RIGHT_X = 0.50

DURATION = 12.0
LINE_START = 2.6                # 正文开始时间（滚轮先出现）
LINE_STEP = 0.9                 # 行间间隔
LINE_FADE = 1.0
WHEEL_START = 1.5
OPENING_TAIL = 30.0             # 前两段总时长：涟漪相位继续
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
    total_cols = text_width(GRID_TEXT)
    cell = min(width / (total_cols + 3.0), height / 6.5)
    ox = (width - total_cols * cell) / 2.0
    oy = (height - CELL_ROWS * cell) / 2.0
    return total_cols, cell, ox, oy


def lay_lines(lines, right_col, top_row, line_rows):
    """把若干行文本排成点集，**按行返回** [[(col,row), ...], ...]。"""
    out = []
    for i, line in enumerate(lines):
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
        out.append(pts)
    return out


def main(out_path, width, height, fps):
    total_cols, cell, ox, oy = grid_geometry(width, height)
    print("网格: total_cols=%d cell=%.2f ox=%.2f oy=%.2f" % (total_cols, cell, ox, oy))

    right_col = int(round((width * RIGHT_X - ox) / cell))
    dot_r = cell * 0.155

    # 滚轮：环心在正文上方
    wrad = 3.2
    wc = right_col + 10
    wr = 1
    wheel_pts = []
    for k in range(24):
        ang = 2 * math.pi * k / 24.0
        wheel_pts.append((wc + int(round(math.cos(ang) * wrad)),
                          wr + int(round(math.sin(ang) * wrad)), 'r'))
    wheel_pts.append((wc, wr, 'c'))
    for (dx, dy) in ((2, 2), (3, 3), (4, 4), (5, 5)):
        wheel_pts.append((wc + dx, wr + dy, 'k'))

    # 本段正文（三行）与上一段收尾（四行，用于衔接）
    line_rows = CELL_ROWS + 1
    body_top = wr + int(wrad) + 1
    body_pts = lay_lines(LINES, right_col, body_top, line_rows)
    line_rows_b = CELL_ROWS + 2
    top_b = int(round((CELL_ROWS / 2.0) - (len(BRIDGE_LINES) * line_rows_b) / 2.0))
    bridge_pts = lay_lines(BRIDGE_LINES, right_col, top_b, line_rows_b)

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
    src_col = text_width("local m") + GAP + 4      # 涟漪源头与开场一致
    src_row = 2

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

            # ① 满屏点阵（同网格）；左侧渐变压暗留白
            c0 = int(math.floor((0 - ox) / cell)) - 1
            c1 = int(math.ceil((width - ox) / cell)) + 1
            r0 = int(math.floor((0 - oy) / cell)) - 1
            r1 = int(math.ceil((height - oy) / cell)) + 1
            for rr_ in range(r0, r1 + 1):
                for cc_ in range(c0, c1 + 1):
                    cx_ = ox + (cc_ + 0.5) * cell
                    cy_ = oy + (rr_ + 0.5) * cell
                    dpx = math.hypot(cx_ - scx, cy_ - scy)
                    wave = 0.5 + 0.5 * math.sin(
                        2 * math.pi * ((t + OPENING_TAIL) / RIPPLE_PERIOD
                                       - dpx / (RIPPLE_WAVELEN * cell)))
                    lr = clamp01((t - 0.8) / 1.2)
                    ramp = clamp01((cx_ - width * 0.36) / (width * 0.12)) * lr
                    amp = LEFT_DIM + (0.20 - LEFT_DIM) * ramp
                    shade = int(255 * clamp01(amp * (0.25 + 1.0 * wave)))
                    circle(d, (cx_, cy_), dot_r * (0.70 + 0.55 * wave), (shade, shade, shade))

            # ② 衔接：先保持上一段的四行，1.3s 内淡出
            bridge = clamp01(1.0 - (t - 1.3) / 1.3)
            if bridge > 0.01:
                for line_pts in bridge_pts:
                    for (cc_, rr_) in line_pts:
                        sh = int(255 * bridge)
                        circle(d, px(cc_, rr_), dot_r * 1.75, (sh, sh, sh))

            # ③ 点阵放大镜：圆环（镜片）+ 中心点 + 对角手柄 —— 表示自动补全封面歌词
            wa = clamp01((t - WHEEL_START) / 1.4)
            if wa > 0.01:
                for (cc_, rr_, kind) in wheel_pts:
                    dpx = math.hypot(ox + (cc_ + 0.5) * cell - scx, oy + (rr_ + 0.5) * cell - scy)
                    a_ = clamp01(wa * 2.4 - dpx / (max(width, height) * 0.70))
                    if a_ <= 0.01:
                        continue
                    fl = max(0.0, math.sin(math.pi * clamp01(a_ / 0.55)))
                    sh = int(255 * a_)
                    rad = dot_r * (1.75 if kind == 'c' else 1.30) * (1.0 + 0.45 * fl)
                    circle(d, px(cc_, rr_), rad, (sh, sh, sh))

            # ④ 正文三行：依次淡入（body_pts 已按行分组）
            for i in range(len(LINES)):
                start = LINE_START + i * LINE_STEP
                a_line = clamp01((t - start) / LINE_FADE)
                if a_line <= 0.01:
                    continue
                for (cc_, rr_) in body_pts[i]:
                    dpx = math.hypot(ox + (cc_ + 0.5) * cell - scx, oy + (rr_ + 0.5) * cell - scy)
                    a_pt = clamp01(a_line * 3.0 - dpx / (max(width, height) * 0.60))
                    if a_pt <= 0.01:
                        continue
                    fl = max(0.0, math.sin(math.pi * clamp01(a_pt / 0.55)))
                    circle(d, px(cc_, rr_), dot_r * (1.75 + 0.55 * fl), (max(int(255 * a_pt), 150),) * 3)

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
    ap.add_argument("--out", default=r"D:\dsh work region\promo\local-music-ui-4k60.mp4")
    ap.add_argument("--width", type=int, default=3840)
    ap.add_argument("--height", type=int, default=2160)
    ap.add_argument("--fps", type=int, default=60)
    a = ap.parse_args()
    main(a.out, a.width, a.height, a.fps)
