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

# 排版：字形块占画布 48%（原来 60% 太满、圆角遮罩还容易切到角上的点）
block = int(CANVAS * 0.48)
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
        if lit:
            rounded_dot(cx, cy, dot, dot_radius, WHITE)
        else:
            rounded_dot(cx, cy, dim, dim * 0.34, GRID_DIM)

os.makedirs(os.path.dirname(OUT_FG), exist_ok=True)
fg.save(OUT_FG, "PNG", optimize=True)
print("wrote", OUT_FG, os.path.getsize(OUT_FG), "bytes")

# ── 原始（非自适应）图标：带红点的版本，放进 mipmap-<dpi>/ 作为 <26 的兜底 ──
# 注意：本项目 minSdk=33，自适应图标从 API 26 起就是强制的，所以这些 PNG **在当前 minSdk 下
# 永远不会被系统选中**；保持它们在仓库里是为了架构正确（将来若降 minSdk 就自动生效）。
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def render(canvas, with_accent):
    rows, cols = ROWS, COLS
    cell_px = canvas * 0.48 / cols
    dot_px, dim_px = cell_px * 0.43, cell_px * 0.30
    w, h = cell_px * cols, cell_px * rows
    ox, oy = (canvas - w) / 2, (canvas - h) / 2
    im = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 255))   # 原始图标自带背景色
    dr = ImageDraw.Draw(im)

    def blip(cx, cy, size, color):
        dr.rounded_rectangle((cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2),
                             radius=size * 0.34, fill=color)

    accent_cell = None
    if with_accent:
        for y in range(rows):
            for x in range(cols - 1, -1, -1):
                if grid[y][x] == "X":
                    accent_cell = (y, x)
                    break
            if accent_cell:
                break
    for y in range(rows):
        for x in range(cols):
            cx, cy = ox + (x + 0.5) * cell_px, oy + (y + 0.5) * cell_px
            if grid[y][x] == "X":
                blip(cx, cy, dot_px, RED if (y, x) == accent_cell else WHITE)
            else:
                blip(cx, cy, dim_px, GRID_DIM)
    return im


for dpi, size in LEGACY.items():
    d = os.path.join(ROOT, "app", "src", "main", "res", f"mipmap-{dpi}")
    os.makedirs(d, exist_ok=True)
    square = render(size, with_accent=True)          # 原始：带红点
    square.convert("RGB").save(os.path.join(d, "ic_launcher.png"), "PNG", optimize=True)
    round_im = square.copy()
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    round_im.putalpha(mask)
    round_im.save(os.path.join(d, "ic_launcher_round.png"), "PNG", optimize=True)
print("wrote legacy mipmaps（带红点）:", ", ".join(f"{k}={v}px" for k, v in LEGACY.items()))

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
