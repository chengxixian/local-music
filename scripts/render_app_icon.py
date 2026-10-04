#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
App 图标重绘脚本：按点阵规则**原生重绘**任意尺寸（不是位图放大），用于商店/文档。

图形：黑色圆角方块 + 白色点阵 Lm + m 右上角一个红点（与工程内启动图标一致）。
点阵形状与 Pages.kt 的 DotMatrixMark、make-dotmatrix-icon.py 同源。

用法：
    python render_app_icon.py                       # 输出 512 与 1024
    python render_app_icon.py --sizes 256,512,1024,2048
"""

import argparse
import os

from PIL import Image, ImageDraw

MARK = [
    "X..........",
    "X..........",
    "X.....X.X.X",
    "X.....XXXXX",
    "X.....X.X.X",
    "X.....X.X.X",
    "XXXXX.X.X.X",
]
COLS, ROWS = 11, 7
RED_AT = (10, 2)          # m 的点（第 2 行最后一列）
BG = (0, 0, 0, 255)
FG = (255, 255, 255, 255)
RED = (255, 59, 48, 255)


def draw_icon(size, radius_ratio=0.235, dot_ratio=0.055, inset=0.235, super_sample=2):
    """super_sample 倍绘制后缩小 -> 边缘平滑。"""
    s = size * super_sample
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 圆角黑底（留出一点透明度让系统遮罩可裁）
    pad = int(s * 0.0)
    r = int(s * radius_ratio)
    d.rounded_rectangle([pad, pad, s - 1 - pad, s - 1 - pad], radius=r, fill=BG)

    # 点阵区域：占图标宽度的 (1 - 2*inset)
    grid_w = s * (1 - 2 * inset)
    cell = grid_w / COLS
    ox = (s - cell * COLS) / 2.0
    oy = (s - cell * ROWS) / 2.0
    dot_r = cell * 0.185                          # 点径 = 格宽 18.5%，贴近工程内既有图标

    for y in range(ROWS):
        for x in range(COLS):
            if MARK[y][x] != 'X':
                continue
            cx = ox + (x + 0.5) * cell
            cy = oy + (y + 0.5) * cell
            color = RED if (x, y) == RED_AT else FG
            d.ellipse([cx - dot_r, cy - dot_r, cx + dot_r, cy + dot_r], fill=color)

    if super_sample > 1:
        img = img.resize((size, size), Image.LANCZOS)
    return img


def main(outdir, sizes):
    os.makedirs(outdir, exist_ok=True)
    for size in sizes:
        img = draw_icon(size)
        path = os.path.join(outdir, "app-icon-%d.png" % size)
        img.save(path)
        print(path, img.size)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--outdir", default=r"D:\dsh work region\icons\app-icon")
    ap.add_argument("--sizes", default="256,512,1024")
    a = ap.parse_args()
    main(a.outdir, [int(x) for x in a.sizes.split(",")])
