#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
local music 宣传动画渲染脚本（独立于 Android 工程）。

不依赖 App、不用录屏：用 Pillow 按时间轴逐帧画点阵，交给 ffmpeg 编码。

时间轴（总长 6.5s，放慢版）：
    0.00-0.04  全黑
    0.04-0.30  灰点阵自【红点位置】向外涟漪状淡入
    0.30-0.50  Lm 形状的点亮成白（带放大回弹）
    0.50-0.64  m 右上角的点（即图标里的红点）闪一圈红光
    0.64-0.90  L 与 m 左右拉开；其余字母按【字序从左到右】逐字补全
    0.90-1.00  稳定成形（**不再有位移**，只是红点涟漪继续）

要点：
    · 灰点阵**全程保留**，不淡出；从红点位置持续放出一圈圈涟漪直到结尾
    · 结尾没有上下位移（用户明确：成形后再降一下很奇怪）

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

# 与 App 内 INTRO_PATTERN / GLYPHS 同源
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
DURATION = 6.5

# 阶段分界（按比例放慢并留出更多过渡）
T_GRID_IN = (0.04, 0.30)
T_LIGHT = (0.28, 0.50)
T_FLASH = (0.50, 0.64)
T_MORPH = (0.64, 0.90)
RIPPLE_PERIOD = 1.6      # 涟漪一个周期的秒数（持续发出）
RIPPLE_WAVELEN = 3.2     # 波长（格）


def text_dots():
    """返回 {点: 字序} —— 字序用于"从左到右逐字补全"。"""
    out = {}
    cursor = 0
    for gi, ch in enumerate(TEXT):
        g = GLYPHS.get(ch)
        if g is None:
            continue
        top = TOTAL_ROWS - len(g)
        for r, row in enumerate(g):
            for c, v in enumerate(row):
                if v == 'X':
                    out[(cursor + c, top + r)] = gi
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


def ease_out(a):
    return 1 - (1 - a) ** 3


def render(out_path, width, height, fps):
    targets = text_dots()          # (col,row) -> glyph index
    moves = mark_moves()
    cols, rows = len(MARK[0]), len(MARK)
    max_col = max(c for c, _ in targets) + 1
    max_glyph = max(targets.values())

    # 涟漪原点 = 红点所在格（第 2 行最后一列）
    src_x, src_y = cols - 1, 2
    max_d = max(math.hypot(x - src_x, y - src_y) for x in range(max_col) for y in range(rows))

    total_frames = int(DURATION * fps)
    tmpdir = tempfile.mkdtemp(prefix="lmpromo-")
    print("帧目录:", tmpdir)

    for fi in range(total_frames):
        v = fi / float(total_frames - 1)
        t = v * DURATION

        grid_a = clamp01((v - T_GRID_IN[0]) / max(T_GRID_IN[1] - T_GRID_IN[0], 1e-6))
        light = clamp01((v - T_LIGHT[0]) / max(T_LIGHT[1] - T_LIGHT[0], 1e-6))
        flash = clamp01((v - T_FLASH[0]) / 0.06)
        settled = clamp01((v - T_FLASH[1]) / 0.05)
        morph = clamp01((v - T_MORPH[0]) / max(T_MORPH[1] - T_MORPH[0], 1e-6))
        ease = smoothstep(morph)
        pulse = max(0.0, math.sin(flash * math.pi))

        active_cols = cols + (max_col - cols) * ease
        cell = min(width / (active_cols + 3.0), height / 6.5)
        # 画布中心不变；**没有**结尾位移
        ox = (width - active_cols * cell) / 2.0
        oy = (height - rows * cell) / 2.0
        dot_r = cell * 0.15

        def px(c, r):
            return (ox + (c + 0.5) * cell, oy + (r + 0.5) * cell)

        img = Image.new("RGB", (width, height), (0, 0, 0))
        glow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        g = ImageDraw.Draw(glow)

        def circle(draw, xy, radius, color):
            x, y = xy
            draw.ellipse([x - radius, y - radius, x + radius, y + radius], fill=color)

        # ① 常驻灰点阵：从红点位置一圈圈持续发出的涟漪
        for y in range(rows):
            for x in range(cols):
                dist = math.hypot(x - src_x, y - src_y)
                front = clamp01((v - T_GRID_IN[0] - (dist / max(max_d, 1e-6)) * 0.18) / 0.10)
                if front <= 0.01:
                    continue
                # 持续涟漪：亮暗随"到红点的距离 + 时间"波动（向外传播）
                wave = 0.5 + 0.5 * math.sin(2 * math.pi * (t / RIPPLE_PERIOD - dist / RIPPLE_WAVELEN))
                base = 0.16 + 0.30 * wave
                # 迁移后点阵略暗，让白色字形更突出
                base *= 1.0 - 0.35 * ease
                shade = int(255 * clamp01(front * base))
                circle(d, px(x, y), dot_r, (shade, shade, shade))

        # ② Lm 的点变白（带放大回弹）
        for y in range(rows):
            for x in range(cols):
                if MARK[y][x] != 'X':
                    continue
                dist = math.hypot(x - src_x, y - src_y)
                a = clamp01((v - T_LIGHT[0] - (dist / max(max_d, 1e-6)) * 0.10) / 0.10)
                if a <= 0.01:
                    continue
                tx, ty = moves.get((x, y), (x, y))
                # 位移用 ease-out，落点更稳
                cx = x + (tx - x) * ease_out(ease)
                cy = y + (ty - y) * ease_out(ease)
                grow = 1.0 + 0.35 * (1.0 - a)
                if y == 2 and x == cols - 1:
                    if flash > 0 or settled > 0:
                        circle(g, px(cx, cy), dot_r * (3.0 + 2.6 * pulse),
                               (255, 59, 48, int(110 * pulse)))
                        circle(d, px(cx, cy), dot_r * (1.0 + 0.7 * pulse), (255, 59, 48))
                    else:
                        shade = int(255 * a)
                        circle(d, px(cx, cy), dot_r * grow, (shade, shade, shade))
                else:
                    shade = int(255 * a)
                    circle(d, px(cx, cy), dot_r * grow, (shade, shade, shade))

        # ③ 其余字母：按字序从左到右逐字补全（更多过渡）
        for (tc, tr), gi in targets.items():
            if (tc, tr) in moves.values():
                continue
            gi_norm = gi / max(max_glyph, 1)
            a = clamp01((v - (T_MORPH[0] + 0.06) - gi_norm * 0.14) / 0.12)
            if a > 0.01:
                shade = int(255 * a)
                circle(d, px(tc, tr), dot_r * (0.6 + 0.4 * a), (shade, shade, shade))

        # 红点持续微光（涟漪源头）
        if settled > 0:
            breathe = 0.5 + 0.5 * math.sin(2 * math.pi * t / RIPPLE_PERIOD)
            circle(g, px(*moves.get((cols - 1, 2), (cols - 1, 2))), dot_r * (2.2 + 0.8 * breathe),
                   (255, 59, 48, int(60 * breathe)))

        glow = glow.filter(ImageFilter.GaussianBlur(radius=max(2.0, cell * 0.4)))
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
