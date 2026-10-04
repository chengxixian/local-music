"""生成 Nothing 风格的点阵 "Lm" 应用图标（自适应图标前景 + 预览）。

设计要点（参考 nothingcn.com / Nothing Phone 的视觉语言）：
  · 点阵字：字母由网格里"点亮的圆点"构成，未点亮的格子留极暗的点，像 LED 阵列
  · 高对比单色：黑底 + 白点；只留一个红色的强调点（Nothing 的标志性细节）
  · 自适应图标安全区：前景是 432x432 的透明画布，字形控制在中间约 56% 内，
    这样被系统裁成圆形/方形时都不会顶到边

产出：
  app/src/main/res/drawable-nodpi/ic_launcher_foreground.png   432x432 透明前景
  design/ic_launcher_background.txt                            背景色（#000000）
  shots/local-music/icon-preview.png                           圆形/方形裁剪预览
"""
from PIL import Image, ImageDraw
import os

ROOT = r"D:\dsh work region\local-music"
OUT_FG = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi", "ic_launcher_foreground.png")
OUT_PREVIEW = r"D:\dsh work region\shots\local-music\icon-preview.png"
CANVAS = 432

WHITE = (255, 255, 255, 255)
GRID_DIM = (255, 255, 255, 30)      # 未点亮格子的微光点
RED = (215, 25, 33, 255)            # Nothing 红

# 5x7 的 "L"（大写）与 5x5 的 "m"（小写，底部对齐）—— 与 icon-variants.py 的方案 A 完全一致。
# ⚠️ 曾经试过把 m 拉成 7 格高，渲染出来像大写 M/梯子，用户一眼就看出"装机的不是 A"——
#    所以这里必须保持 5 格的小写 m，别再"为了比例"去拉高。
L = [
    "X....",
    "X....",
    "X....",
    "X....",
    "X....",
    "X....",
    "XXXXX",
]
M = [
    ".....",
    "X.X.X",
    "XXXXX",
    "X.X.X",
    "X.X.X",
]

# 拼成 11 列 x 7 行：左边 L，空一列，右边 m 底部对齐
COLS, ROWS = 11, 7
grid = [["." for _ in range(COLS)] for _ in range(ROWS)]
for y, row in enumerate(L):
    for x, ch in enumerate(row):
        if ch == "X":
            grid[y][x] = "X"
m_offset_y = ROWS - len(M)
for y, row in enumerate(M):
    for x, ch in enumerate(row):
        if ch == "X":
            grid[m_offset_y + y][x + 6] = "X"

# 强调点：m 的右上角那颗，点成红色
accent = (m_offset_y, 6 + 4)

# 排版：字形块占画布约 60%（自适应图标安全区上限约 61%），再按格子切分
block = int(CANVAS * 0.60)
cell = block / COLS
# Nothing 的 Ndot 字形用的是**圆角方点**而不是正圆，点大、间距紧，字重才均匀
dot = cell * 0.43          # 点亮格子的边长（图标尺寸下点要大才立得住）
dot_radius = dot * 0.34     # 圆角半径
dim = cell * 0.30          # 未点亮格子的微光点
width = cell * COLS
height = cell * ROWS
ox = (CANVAS - width) / 2
oy = (CANVAS - height) / 2

fg = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
d = ImageDraw.Draw(fg)


def rounded_dot(cx, cy, size, radius, color):
    d.rounded_rectangle((cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2), radius=radius, fill=color)


for y in range(ROWS):
    for x in range(COLS):
        cx = ox + (x + 0.5) * cell
        cy = oy + (y + 0.5) * cell
        lit = grid[y][x] == "X"
        if lit and (y, x) == accent:
            rounded_dot(cx, cy, dot, dot_radius, RED)
        elif lit:
            rounded_dot(cx, cy, dot, dot_radius, WHITE)
        else:
            rounded_dot(cx, cy, dim, dim * 0.34, GRID_DIM)

os.makedirs(os.path.dirname(OUT_FG), exist_ok=True)
fg.save(OUT_FG, "PNG", optimize=True)
print("wrote", OUT_FG, os.path.getsize(OUT_FG), "bytes")

with open(os.path.join(ROOT, "design", "ic_launcher_background.txt"), "w") as f:
    f.write("#000000\n")

# 预览：黑底圆 / 方圆角两种裁切，另加一张浅底看对比
prev_w, prev_h = CANVAS * 2 + 36, CANVAS + 24
prev = Image.new("RGB", (prev_w, prev_h), (18, 18, 20))
for i, kind in enumerate(("circle", "squircle")):
    layer = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 255))   # 黑底（自适应背景色）
    layer.alpha_composite(fg)
    m = Image.new("L", (CANVAS, CANVAS), 0)
    dm = ImageDraw.Draw(m)
    if kind == "circle":
        dm.ellipse((0, 0, CANVAS - 1, CANVAS - 1), fill=255)
    else:
        dm.rounded_rectangle((0, 0, CANVAS - 1, CANVAS - 1), radius=int(CANVAS * 0.28), fill=255)
    layer.putalpha(m)
    prev.paste(layer, (12 + i * (CANVAS + 12), 12), layer)
prev = prev.resize((prev.size[0] // 2, prev.size[1] // 2), Image.LANCZOS)
prev.save(OUT_PREVIEW)
print("preview", OUT_PREVIEW, prev.size)
