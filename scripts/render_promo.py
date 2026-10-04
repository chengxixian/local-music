#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
local music 宣传动画渲染脚本（独立于 Android 工程）。

不依赖 App、不用录屏：直接用 Pillow 复刻同一条时间轴，逐帧画点阵，再交给 ffmpeg 编码。

时间轴（总长 4.2s）：
    0.00-0.05  全黑
    0.05-0.32  底色点阵自中心向外的涟漪淡入（灰）
    0.28-0.46  Lm 形状的点亮成白
    0.46-0.56  m 右上角的点（图标里的红点）闪一圈红光后定格为红
    0.56-0.82  L 与 m 左右拉开到 "local music" 的位置，其余字母用同一套点阵补全
    0.82-1.00  成形落位（轻微上移）

用法：
    python render_promo.py --out out.mp4 --width 1920 --height 1080 --fps 30
"""

import argparse
import math
import os
import shutil
import subprocess
import tempfile

from PIL import Image, ImageDraw, ImageFilter

# 与 App 内 INTRO_PATTERN / GLYPHS 完全一致
MARK = [
    "X..........",
    "X..........",
    "X.....X.X.X",
    "X.....XXXXX",
    "X.....X.X.X",
    "X.....X.X.X",
    "XXXXX.X.X.X",
]

GLYPHS = {
    'l': ["X....", "X....", "X....", "X....", "X....", "X....", "XXXXX"],
    'o': [".XXX.", "X...X", "X...X", "X...X", ".XXX."],
    'c': [".XXX.", "X....", "X....", "X....", ".XXX."],
    'a': [".XXX.", "X...X", "XXXXX", "X...X", "X...X"],
    'm': ["X.X.X", "XXXXX", "X.X.X", "X.X.X", "X.X.X"],
    'u': ["X...X", "X...X", "X...X", "X...X", ".XXXX"],
    's': [".XXXX", "X....", ".XXX.", "....X", "XXXX."],
    'i': ["..X..", ".....", "..X..", "..X..", "..X..", "..X.."],
    ' ': [".....", "....."],
}

GLYPH_W, GAP, SPACE_W, TOTAL_ROWS = 5, 1, 3, 7
TEXT = "local music"
DURATION = 4.2

WHITE = (255, 255, 255)
GRAY = (138, 138, 138)
RED = (255, 59, 48)


def text_dots():
    out = set()
    cursor = 0
    for ch in TEXT:
        g = GLYPHS.get(ch)
        if g is None:
            continue
        top = TOTAL_ROWS - len(g)
        for r, row in enumerate(g):
            for c, v in enumerate(row):
                if v == 'X':
                    out.add((cursor + c, top + r))
        cursor += (SPACE_W if ch == ' ' else GLYPH_W) + GAP
    return out


def mark_moves():
    cursor, l_col, m_col = 0, 0, 0
    for idx, ch in enumerate(TEXT):
        if idx == 0:
            l_col = cursor
        if ch == 'm':
            m_col = cursor
            break
        cursor += (SPACE_W if ch == ' ' else GLYPH_W) + GAP
    moves = {}
    for y, row in enumerate(MARK):
        for x, v in enumerate(row):
            if v != 'X':
                continue
            if x <= 4:
                target = (l_col + x, y)
            else:
                mc, mr = x - 6, y - 2
                target = (m_col + mc, TOTAL_ROWS - 5 + mr) if 0 <= mr <= 4 else None
            if target:
                moves[(x, y)] = target
    return moves


def clamp01(v):
    return 0.0 if v < 0 else (1.0 if v > 1 else v)


def smoothstep(a):
    return a * a * (3 - 2 * a)


def render(out_path, width, height, fps):
    targets = text_dots()
    moves = mark_moves()
    cols, rows = len(MARK[0]), len(MARK)
    max_col = max(c for c, _ in targets) + 1

    mid_x, mid_y = (cols - 1) / 2.0, (rows - 1) / 2.0
    max_d = max(math.hypot(mid_x, mid_y), 1e-6)
    text_mid_x = (max_col - 1) / 2.0
    text_max_d = max(math.hypot(text_mid_x, 3.0), 1e-6)

    total_frames = int(DURATION * fps)
    tmpdir = tempfile.mkdtemp(prefix="lmpromo-")
    print("帧目录:", tmpdir)

    for fi in range(total_frames):
        v = fi / float(total_frames - 1)
        morph = clamp01((v - 0.56) / 0.26)
        ease = smoothstep(morph)
        settle = clamp01((v - 0.82) / 0.18)
        flash = clamp01((v - 0.46) / 0.06)
        settled = clamp01((v - 0.52) / 0.05)
        pulse = max(0.0, math.sin(flash * math.pi))
        lift = (1.0 - settle) * (height * 0.024)

        active_cols = cols + (max_col - cols) * ease
        cell = min(width / (active_cols + 3.0), height / 6.5)
        ox = (width - active_cols * cell) / 2.0
        oy = (height - rows * cell) / 2.0 - lift
        dot_r = cell * 0.15

        def px(c, r):
            return (ox + (c + 0.5) * cell, oy + (r + 0.5) * cell)

        img = Image.new("RGB", (width, height), (0, 0, 0))
        glow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        g = ImageDraw.Draw(glow)

        def circle(draw, color, radius, center):
            x, y = center
            draw.ellipse([x - radius, y - radius, x + radius, y + radius], fill=color)

        # ① 底色点阵：中心向外的涟漪
        grid_fade = 1.0 - ease
        for y in range(rows):
            for x in range(cols):
                rr = min(math.hypot(x - mid_x, y - mid_y) / max_d, 1.0)
                a = clamp01((v - 0.05 - rr * 0.20) / 0.09) * grid_fade
                if a > 0.01:
                    circle(d, (int(GRAY[0]), int(GRAY[1]), int(GRAY[2])), dot_r,
                           px(x, y)) if False else None
                    # 用带 alpha 的颜色混合到黑底上
                    shade = int(255 * a * 0.5)
                    circle(d, (shade, shade, shade), dot_r, px(x, y))

        # ② Lm 的点
        for y in range(rows):
            for x in range(cols):
                if MARK[y][x] != 'X':
                    continue
                rr = min(math.hypot(x - mid_x, y - mid_y) / max_d, 1.0)
                a = clamp01((v - 0.28 - rr * 0.14) / 0.09)
                if a <= 0.01:
                    continue
                tx, ty = moves.get((x, y), (x, y))
                cx = x + (tx - x) * ease
                cy = y + (ty - y) * ease
                if y == 2 and x == cols - 1:
                    if flash > 0 or settled > 0:
                        alpha = int(255 * (0.35 + 0.65 * max(pulse, settled)))
                        circle(g, (RED[0], RED[1], RED[2], int(90 * pulse)),
                               dot_r * (2.6 + 2.4 * pulse), px(cx, cy))
                        circle(d, (RED[0], RED[1], RED[2]), dot_r * (1 + 0.6 * pulse), px(cx, cy))
                else:
                    shade = int(255 * a)
                    circle(d, (shade, shade, shade), dot_r, px(cx, cy))

        # ③ 补全的字母
        for (tc, tr) in targets:
            if (tc, tr) in set(moves.values()):
                continue
            rr = min(math.hypot(tc - text_mid_x, tr - 3.0) / text_max_d, 1.0)
            a = clamp01((v - 0.60 - rr * 0.16) / 0.10)
            if a > 0.01:
                shade = int(255 * a)
                circle(d, (shade, shade, shade), dot_r, px(tc, tr))

        # 红光晕（模糊后叠加）
        glow = glow.filter(ImageFilter.GaussianBlur(radius=max(2.0, cell * 0.35)))
        img = Image.alpha_composite(img.convert("RGBA"), glow).convert("RGB")
        img.save(os.path.join(tmpdir, "f%04d.png" % fi), compress_level=1)

    ffmpeg = shutil.which("ffmpeg") or r"D:\tool\ffmpeg\bin\ffmpeg.exe"
    cmd = [
        ffmpeg, "-y", "-framerate", str(fps),
        "-i", os.path.join(tmpdir, "f%04d.png"),
        "-c:v", "libx264", "-preset", "slow", "-crf", "16",
        "-pix_fmt", "yuv420p", "-movflags", "+faststart",
        out_path,
    ]
    print("编码:", " ".join(cmd))
    subprocess.run(cmd, check=True)
    shutil.rmtree(tmpdir, ignore_errors=True)
    print("完成:", out_path, os.path.getsize(out_path) // 1024, "KB")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=r"D:\dsh work region\promo\local-music-promo-1080p.mp4")
    ap.add_argument("--width", type=int, default=1920)
    ap.add_argument("--height", type=int, default=1080)
    ap.add_argument("--fps", type=int, default=30)
    a = ap.parse_args()
    render(a.out, a.width, a.height, a.fps)
