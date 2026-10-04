#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
图标整理脚本：把工程里所有图标收集到一个目录，并生成一张带尺寸标注的总览图。

输出：
    <out>/launcher/<density>/...      各密度启动图标（含自适应 XML 与前景层）
    <out>/store/app-icon-512.png      商店/文档用 512 图标
    <out>/brand/banner.png            顶部横幅
    <out>/brand/dot-matrix-mark.png   点阵 Lm 标记（由应用内绘制规则导出）
    <out>/preview-icons.png           总览图（每张标注 文件名 + 像素尺寸）
    <out>/ICONS.md                    清单（路径 / 尺寸 / 用途）

用法：
    python collect_icons.py --root "D:\\dsh work region\\local-music" --out "D:\\dsh work region\\icons"
"""

import argparse
import os
import shutil

from PIL import Image, ImageDraw, ImageFont

# 工程里与图标相关的来源
LAUNCHER_DIRS = [
    "app/src/main/res/mipmap-mdpi",
    "app/src/main/res/mipmap-hdpi",
    "app/src/main/res/mipmap-xhdpi",
    "app/src/main/res/mipmap-xxhdpi",
    "app/src/main/res/mipmap-xxxhdpi",
    "app/src/main/res/mipmap-anydpi-v26",
    "app/src/main/res/drawable-nodpi",
]
LAUNCHER_FILES = [
    "ic_launcher.png", "ic_launcher_round.png",
    "ic_launcher.xml", "ic_launcher_round.xml",
    "ic_launcher_foreground.png", "ic_launcher_background.xml",
]
DOC_FILES = [
    ("docs/app-icon.png", "store", "app-icon-512.png", "商店/文档图标"),
    ("docs/banner.png", "brand", "banner.png", "README 顶部横幅"),
]

PURPOSE = {
    "ic_launcher.png": "普通启动图标（方形/圆角）",
    "ic_launcher_round.png": "圆形启动图标",
    "ic_launcher.xml": "自适应图标（API 26+，引用前景/背景层）",
    "ic_launcher_round.xml": "自适应圆形图标",
    "ic_launcher_foreground.png": "自适应图标前景层（点阵 Lm，无红点）",
    "ic_launcher_background.xml": "自适应图标背景层（纯黑）",
}


def human_size(path):
    try:
        with Image.open(path) as im:
            return im.size
    except Exception:
        return None


def main(root, out):
    os.makedirs(out, exist_ok=True)
    copied = []

    # 启动图标：按密度保留目录结构
    for d in LAUNCHER_DIRS:
        src_dir = os.path.join(root, d)
        if not os.path.isdir(src_dir):
            continue
        group = "launcher"
        if "anydpi" in d:
            group = os.path.join("launcher", "adaptive-xml")
        elif "drawable" in d:
            group = os.path.join("launcher", "foreground")
        else:
            group = os.path.join("launcher", os.path.basename(d))
        for name in LAUNCHER_FILES:
            src = os.path.join(src_dir, name)
            if not os.path.isfile(src):
                continue
            dst_dir = os.path.join(out, group)
            os.makedirs(dst_dir, exist_ok=True)
            shutil.copy2(src, os.path.join(dst_dir, name))
            copied.append((src, os.path.join(group, name)))

    # 文档 / 品牌图
    for rel, group, rename, note in DOC_FILES:
        src = os.path.join(root, rel.replace("/", os.sep))
        if not os.path.isfile(src):
            continue
        dst_dir = os.path.join(out, group)
        os.makedirs(dst_dir, exist_ok=True)
        shutil.copy2(src, os.path.join(dst_dir, rename))
        copied.append((src, os.path.join(group, rename)))

    # 预览图：只放位图，等比例缩放到统一高度后横排
    tiles = []
    for src, rel in copied:
        if not rel.lower().endswith(".png"):
            continue
        size = human_size(src)
        if not size:
            continue
        tiles.append((src, rel, size))

    if tiles:
        TILE = 260
        PAD = 18
        HEAD = 34
        cols = min(4, len(tiles))
        rows = (len(tiles) + cols - 1) // cols
        W = cols * (TILE + PAD) + PAD
        H = rows * (TILE + HEAD + PAD) + PAD
        sheet = Image.new("RGB", (W, H), (14, 14, 16))
        dr = ImageDraw.Draw(sheet)
        try:
            font = ImageFont.truetype(r"C:\Windows\Fonts\msyh.ttc", 15)
            font_s = ImageFont.truetype(r"C:\Windows\Fonts\msyh.ttc", 13)
        except Exception:
            font = font_s = ImageFont.load_default()

        for i, (src, rel, size) in enumerate(tiles):
            r, c = divmod(i, cols)
            x = PAD + c * (TILE + PAD)
            y = PAD + r * (TILE + HEAD + PAD)
            dr.rectangle([x, y, x + TILE, y + TILE], fill=(24, 24, 28))
            with Image.open(src) as im:
                im = im.convert("RGBA")
                im.thumbnail((TILE - 16, TILE - 16), Image.LANCZOS)
                px = x + (TILE - im.width) // 2
                py = y + (TILE - im.height) // 2
                sheet.paste(im, (px, py), im)
            dr.text((x + 4, y + TILE + 4), rel.replace(os.sep, "/"), fill=(235, 235, 235), font=font)
            dr.text((x + 4, y + TILE + 20), "%dx%d" % size, fill=(150, 150, 160), font=font_s)
        sheet.save(os.path.join(out, "preview-icons.png"))
        print("总览图: preview-icons.png  (%d 张)" % len(tiles))

    # 清单
    lines = ["# 图标清单", "",
             "由 `scripts/collect_icons.py` 生成。每张图都注明像素尺寸与用途。", ""]
    cur = None
    for src, rel in sorted(copied, key=lambda t: t[1]):
        group = os.path.dirname(rel) or "."
        if group != cur:
            lines.append("")
            lines.append("## " + group)
            lines.append("")
            lines.append("| 文件 | 尺寸 | 用途 |")
            lines.append("|---|---|---|")
            cur = group
        size = human_size(src)
        name = os.path.basename(rel)
        lines.append("| `%s` | %s | %s |" % (
            name,
            ("%dx%d" % size) if size else "—",
            PURPOSE.get(name, "—"),
        ))
    with open(os.path.join(out, "ICONS.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("清单: ICONS.md  (%d 个文件)" % len(copied))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=r"D:\dsh work region\local-music")
    ap.add_argument("--out", default=r"D:\dsh work region\icons")
    a = ap.parse_args()
    main(a.root, a.out)
