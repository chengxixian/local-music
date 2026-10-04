#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
local music 宣传动画渲染脚本（独立于 Android 工程）。

核心视觉：**一个字母一个点阵块**（早期计算器那种字符位显示）。
所以点阵不是一整片连续网格，而是按字母划分的独立区块；字母与字母之间是截断的
（区块内部有 5x7 灰点阵，区块之间是空的）。

时间轴（总长 7.5s）：
    0.00-0.04  全黑
    0.04-0.30  两个字母块（L 与 m）的灰点阵，自红点位置向外涟漪状淡入
    0.30-0.50  Lm 字形点亮成白（带放大回弹）
    0.50-0.66  m 右上角的点（红点）闪一圈红光
    0.66-0.92  L 块不动、m 块向右拉开到"music"的位置；中间的 o c a l ␣ u s i c
               各自以**独立点阵块**从左到右依次亮起（区块之间的空隙保持截断，不连线）
    0.92-1.00  成形；红点持续放出涟漪

涟漪：原点固定为红点所在格（m 块右上角），行波持续向外扩散，直到结尾。
      涟漪调制的是各字母块的灰点阵亮度 —— 所以涟漪会一圈圈扫过所有字母。

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

TEXT = "local music"

# 5 列点阵字形（行数可变，底边对齐）
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

GLYPH_W, GAP, SPACE_W, CELL_ROWS = 5, 1, 3, 7
DURATION = 7.5

T_GRID = (0.04, 0.30)
T_LIGHT = (0.28, 0.50)
T_FLASH = (0.50, 0.66)
T_MORPH = (0.66, 0.92)
RIPPLE_PERIOD = 1.7
RIPPLE_WAVELEN = 3.0


def clamp01(v):
    return 0.0 if v < 0 else (1.0 if v > 1 else v)


def smoothstep(a):
    return a * a * (3 - 2 * a)


def ease_out(a):
    return 1 - (1 - a) ** 3


def build_layout():
    """
    返回 (blocks, total_cols)
    blocks: [{ch, gi, start(begin col), final(最终列), rows(dict row->str)}]
    第 0 个是 'l'，第 6 个是 'm'（与 "local music" 的字符顺序对应）。
    """
    blocks = []
    cursor = 0
    for gi, ch in enumerate(TEXT):
        g = GLYPHS.get(ch)
        if g is None:
            continue
        width = SPACE_W if ch == ' ' else GLYPH_W
        rows = {}
        top = CELL_ROWS - len(g)
        for r, row in enumerate(g):
            rows[top + r] = row
        blocks.append({
            'ch': ch, 'gi': gi, 'final': cursor, 'width': width,
            'rows': rows, 'lines': g,
        })
        cursor += width + GAP
    return blocks, cursor - GAP


def main(out_path, width, height, fps):
    blocks, total_cols = build_layout()
    l_block = blocks[0]
    m_block = next(b for b in blocks if b['ch'] == 'm')

    # 初始态：只有 L 与 m 两个块，位置即标记里的 0 与 6
    INIT_COL = {id(l_block): 0, id(m_block): 6}
    # m 块在标记里是 5 行（y=2..6），字形的 'm' 也是 5 行、底边对齐 -> 直接一致
    src_x, src_y = 6 + 4, 0          # 红点：m 块右上角（块内第 0 行、第 4 列）

    max_d = math.hypot(total_cols, CELL_ROWS)

    total_frames = int(DURATION * fps)
    tmpdir = tempfile.mkdtemp(prefix="lmpromo-")
    print("帧目录:", tmpdir)

    for fi in range(total_frames):
        v = fi / float(total_frames - 1)
        t = v * DURATION

        grid_a = clamp01((v - T_GRID[0]) / max(T_GRID[1] - T_GRID[0], 1e-6))
        light = clamp01((v - T_LIGHT[0]) / max(T_LIGHT[1] - T_LIGHT[0], 1e-6))
        flash = clamp01((v - T_FLASH[0]) / 0.07)
        settled = clamp01((v - T_FLASH[1]) / 0.05)
        morph = clamp01((v - T_MORPH[0]) / max(T_MORPH[1] - T_MORPH[0], 1e-6))
        ease = smoothstep(morph)
        eo = ease_out(ease)
        pulse = max(0.0, math.sin(flash * math.pi))

        # 画布随迁移从"两个块"扩到全宽；点距不变
        active_cols = 11 + (total_cols - 11) * ease
        cell = min(width / (active_cols + 3.0), height / 6.5)
        ox = (width - active_cols * cell) / 2.0
        oy = (height - CELL_ROWS * cell) / 2.0
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

        # 每个字母块的位置：老块从初始列迁到最终列；新块直接出现在最终列（靠淡入）
        def block_col(b):
            init = INIT_COL.get(id(b))
            if init is None:
                return b['final']
            return init + (b['final'] - init) * eo

        # m 块当前的列（红点跟着它走）
        cur_src_x = block_col(m_block) + 4

        for b in blocks:
            bc = block_col(b)
            is_old = id(b) in INIT_COL
            # 块的出现时机：老的随 grid_a；新的按字序从左到右
            if is_old:
                appear = grid_a
            else:
                gi_norm = b['gi'] / float(len(TEXT) - 1)
                appear = clamp01((v - (T_MORPH[0] + 0.04) - gi_norm * 0.16) / 0.13)
            if appear <= 0.01:
                continue

            # ① 该字母块的灰点阵（块内 5x7；块外没有点 -> 字母之间是截断的）
            for r in range(CELL_ROWS):
                for c in range(b['width']):
                    gx = bc + c
                    dist = math.hypot(gx - cur_src_x, r - src_y)
                    front = clamp01((v - T_GRID[0] - (dist / max_d) * 0.22) / 0.11)
                    if front <= 0.01:
                        continue
                    wave = 0.5 + 0.5 * math.sin(2 * math.pi * (t / RIPPLE_PERIOD - dist / RIPPLE_WAVELEN))
                    base = 0.15 + 0.28 * wave
                    base *= 1.0 - 0.30 * ease
                    shade = int(255 * clamp01(front * base * appear))
                    circle(d, px(gx, r), dot_r, (shade, shade, shade))

            # ② 该字母的白点（字形）
            for r, line in b['rows'].items():
                for c, chv in enumerate(line):
                    if chv != 'X':
                        continue
                    gx = bc + c
                    a = appear if not is_old else light
                    if a <= 0.01:
                        continue
                    grow = 1.0 + 0.3 * (1.0 - a)
                    is_red = (b is m_block and r == 2 and c == 4 - 0) if False else False
                    # 红点 = m 块内第 0 行、第 4 列（字形 'm' 的右上角）
                    if b is m_block and r == 2 and c == 4:
                        # 注意：字形 m 占 r=2..6，其右上角是 (r=2, c=4)
                        if flash > 0 or settled > 0:
                            circle(g, px(gx, r), dot_r * (3.0 + 2.6 * pulse), (255, 59, 48, int(110 * pulse)))
                            circle(d, px(gx, r), dot_r * (1.0 + 0.7 * pulse), (255, 59, 48))
                        else:
                            shade = int(255 * a)
                            circle(d, px(gx, r), dot_r * grow, (shade, shade, shade))
                    else:
                        shade = int(255 * a)
                        circle(d, px(gx, r), dot_r * grow, (shade, shade, shade))

        # 红点持续微光（涟漪源头）
        if settled > 0:
            breathe = 0.5 + 0.5 * math.sin(2 * math.pi * t / RIPPLE_PERIOD)
            circle(g, px(cur_src_x, 2), dot_r * (2.2 + 0.9 * breathe), (255, 59, 48, int(65 * breathe)))

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
    main(a.out, a.width, a.height, a.fps)
