"""点阵图标：生成三个字形方案并排预览（含小尺寸可读性对照），选定后写进 App 图标。

方案：
  A  7 格高的大写 L + 5 格高的小写 m（标准字高比例，m 底部对齐）
  B  全大写 LM（两个字母同为 7 格高，更"方形/工业"）
  C  全小写 lm（l 一根竖，m 5x5，最简约）
每列上面是 432px 大图（看字形），下面是 48px 小图（看图标尺寸下还认不认得出）。
"""
from PIL import Image, ImageDraw
import os

ROOT = r"D:\dsh work region\local-music"
OUT_PREVIEW = r"D:\dsh work region\shots\local-music\icon-variants.png"

WHITE = (255, 255, 255, 255)
GRID_DIM = (255, 255, 255, 26)
RED = (215, 25, 33, 255)

L7 = ["X....", "X....", "X....", "X....", "X....", "X....", "XXXXX"]
M5 = [".....", "X.X.X", "XXXXX", "X.X.X", "X.X.X"]
M7 = ["X...X", "XX.XX", "X.X.X", "X...X", "X...X", "X...X", "X...X"]
L1 = ["X", "X", "X", "X", "X"]


def stack(left, right, gap=1, rows=None):
    """把两个字形并排拼成一个点阵（右侧字形底部对齐）。"""
    lw, rw = max(len(r) for r in left), max(len(r) for r in right)
    h = rows or max(len(left), len(right))
    grid = [["." for _ in range(lw + gap + rw)] for _ in range(h)]
    for y, row in enumerate(left):
        for x, ch in enumerate(row):
            if ch == "X":
                grid[y][x] = "X"
    off = h - len(right)
    for y, row in enumerate(right):
        for x, ch in enumerate(row):
            if ch == "X":
                grid[off + y][x + lw + gap] = "X"
    return grid


VARIANTS = [
    ("A  7格L + 5格m", stack(L7, M5)),
    ("B  全大写 LM", stack(L7, M7)),
    ("C  全小写 lm", stack(L1, M5)),
]


def render(grid, canvas, block_ratio=0.60, accent=True):
    rows, cols = len(grid), len(grid[0])
    cell = canvas * block_ratio / cols
    dot, dim = cell * 0.43, cell * 0.30
    w, h = cell * cols, cell * rows
    ox, oy = (canvas - w) / 2, (canvas - h) / 2
    im = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)

    def dot_at(cx, cy, size, color):
        d.rounded_rectangle((cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2), radius=size * 0.34, fill=color)

    accent_cell = None
    if accent:
        # 强调点：最右上那个被点亮的格子
        for y in range(rows):
            for x in range(cols - 1, -1, -1):
                if grid[y][x] == "X":
                    accent_cell = (y, x)
                    break
            if accent_cell:
                break
    for y in range(rows):
        for x in range(cols):
            cx, cy = ox + (x + 0.5) * cell, oy + (y + 0.5) * cell
            if grid[y][x] == "X":
                dot_at(cx, cy, dot, RED if (y, x) == accent_cell else WHITE)
            else:
                dot_at(cx, cy, dim, GRID_DIM)
    return im


canvas = 432
cols = len(VARIANTS)
cellw, cellh = canvas + 24, canvas + 150
prev = Image.new("RGB", (cellw * cols + 24, cellh), (16, 16, 18))
d = ImageDraw.Draw(prev)

for i, (label, grid) in enumerate(VARIANTS):
    x0 = 12 + i * cellw
    # 大图（圆形裁切）
    big = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 255))
    big.alpha_composite(render(grid, canvas))
    mask = Image.new("L", (canvas, canvas), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, canvas - 1, canvas - 1), fill=255)
    big.putalpha(mask)
    prev.paste(big, (x0 + 12, 40), big)
    # 小图（48px，看图标尺寸下的可读性）
    small = Image.new("RGBA", (48, 48), (0, 0, 0, 255))
    small.alpha_composite(render(grid, 48))
    m2 = Image.new("L", (48, 48), 0)
    ImageDraw.Draw(m2).ellipse((0, 0, 47, 47), fill=255)
    small.putalpha(m2)
    prev.paste(small.resize((96, 96), Image.LANCZOS), (x0 + 12 + 168, 40 + 168), small.resize((96, 96), Image.LANCZOS))
    d.text((x0 + 12, 16), label, fill=(240, 240, 240))

prev.resize((prev.size[0] // 2, prev.size[1] // 2), Image.LANCZOS).save(OUT_PREVIEW)
print("wrote", OUT_PREVIEW)
