"""从用户提供的方图生成 Android 自适应图标资源。

产出：
  app/src/main/res/drawable-nodpi/ic_launcher_foreground.png   432x432，安全区内的前景
  shots/local-music/icon-preview.png                           圆形/方形蒙版预览（给我自己看效果）
  design/ic_launcher_background.txt                            取到的背景色（写进 colors.xml）

为什么不用整张图直接当图标：自适应图标会被系统按圆形/方形裁切，
原图里 logo 占比偏大，直接塞进去裁切后会顶到边。这里把它缩到安全区内（≤61%）。
"""
from PIL import Image, ImageDraw
import os

ROOT = r"D:\dsh work region\local-music"
SRC = os.path.join(ROOT, "design", "app-icon-source.jpg")
OUT_FG = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi", "ic_launcher_foreground.png")
OUT_PREVIEW = r"D:\dsh work region\shots\local-music\icon-preview.png"

im = Image.open(SRC).convert("RGB")
w, h = im.size
px = im.load()

# 背景色取四角中位数，避免取到压缩噪点
corners = [px[2, 2], px[w - 3, 2], px[2, h - 3], px[w - 3, h - 3]]
bg = tuple(sorted(c[i] for c in corners)[1] for i in range(3))
print("source:", im.size, "background:", bg, "#%02X%02X%02X" % bg)

# logo 掩码：与背景差异足够大的像素
def dist(c):
    return abs(c[0] - bg[0]) + abs(c[1] - bg[1]) + abs(c[2] - bg[2])

mask = Image.new("L", im.size, 0)
mp = mask.load()
for y in range(h):
    for x in range(w):
        mp[x, y] = 255 if dist(px[x, y]) > 90 else 0

bbox = mask.getbbox()
print("logo bbox:", bbox)

logo = im.crop(bbox)
# 前景画布：108dp 的 mdpi 基准 * 4 = 432px；logo 限制在中间 56%
CANVAS = 432
SAFE = 0.56
target = int(CANVAS * SAFE)
scale = target / max(logo.size)
logo = logo.resize((max(1, int(logo.size[0] * scale)), max(1, int(logo.size[1] * scale))), Image.LANCZOS)

canvas = Image.new("RGB", (CANVAS, CANVAS), bg)
canvas.paste(logo, ((CANVAS - logo.size[0]) // 2, (CANVAS - logo.size[1]) // 2))
os.makedirs(os.path.dirname(OUT_FG), exist_ok=True)
canvas.save(OUT_FG, "PNG", optimize=True)
print("wrote", OUT_FG, os.path.getsize(OUT_FG), "bytes")

with open(os.path.join(ROOT, "design", "ic_launcher_background.txt"), "w") as f:
    f.write("#%02X%02X%02X\n" % bg)

# 预览：看圆形裁剪后是否被切到
prev = Image.new("RGB", (CANVAS * 2 + 36, CANVAS + 24), (240, 240, 244))
for i, kind in enumerate(("circle", "squircle")):
    layer = canvas.convert("RGBA")
    m = Image.new("L", (CANVAS, CANVAS), 0)
    d = ImageDraw.Draw(m)
    if kind == "circle":
        d.ellipse((0, 0, CANVAS - 1, CANVAS - 1), fill=255)
    else:
        d.rounded_rectangle((0, 0, CANVAS - 1, CANVAS - 1), radius=int(CANVAS * 0.28), fill=255)
    layer.putalpha(m)
    prev.paste(layer, (12 + i * (CANVAS + 12), 12), layer)
prev = prev.resize((prev.size[0] // 2, prev.size[1] // 2), Image.LANCZOS)
prev.save(OUT_PREVIEW)
print("preview", OUT_PREVIEW, prev.size)
