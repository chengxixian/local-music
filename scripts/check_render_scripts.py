#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
宣传动画脚本自检：扫描 scripts/render_*.py，检查两类会"静默出错"的问题。

1) 缺字形：LINES / BRIDGE_LINES 里用到的每个字符，是否都在该脚本的 GLYPHS 字典里。
   （lay_lines 对缺失字符是**直接跳过**，所以少一个字母只会悄悄消失，不会报错。）
2) 孤点残留：清理图形时留下的绘制调用（例如 wheel_pts.append(...)）。

用法：
    python check_render_scripts.py [--dir scripts]
退出码：0 = 全部通过；1 = 有问题（会把问题逐条打印）。
"""

import argparse
import glob
import io
import os
import re
import sys


def glyph_keys(src):
    m = re.search(r"GLYPHS = \{(.*?)\n\}", src, re.S)
    if not m:
        return None
    return set(re.findall(r"'([^']+)':", m.group(1)))


def grab_list(src, name):
    m = re.search(r"(?m)^" + name + r" = \[(.*?)\]", src)
    if not m:
        return []
    return [s.strip() for s in m.group(1).replace('"', "").split(",") if s.strip()]


def main(root):
    files = sorted(glob.glob(os.path.join(root, "render_*.py")))
    bad = 0
    for path in files:
        name = os.path.basename(path)
        src = io.open(path, encoding="utf-8").read()
        keys = glyph_keys(src)
        lines = grab_list(src, "LINES")
        bridge = grab_list(src, "BRIDGE_LINES")
        need = set("".join(lines + bridge)) - {" "}
        problems = []
        if keys is None:
            problems.append("没有解析到 GLYPHS 字典")
        else:
            miss = sorted(ch for ch in need if ch not in keys)
            if miss:
                problems.append("缺字形: %s（这些字符会被静默跳过）" % " ".join(miss))
        if "wheel_pts.append(" in src and "for k in range(0)" not in src:
            problems.append("孤点残留: 仍有 wheel_pts.append(...) 绘制调用")
        if not lines:
            problems.append("LINES 为空或没解析到")
        print("%-22s LINES=%-28s BRIDGE=%-28s %s" % (
            name, "/".join(lines) or "-", "/".join(bridge) or "(GRID_TEXT)", 
            "OK" if not problems else " | ".join(problems)))
        bad += len(problems)
    print("---")
    print("检查 %d 个脚本，问题 %d 项" % (len(files), bad))
    return 1 if bad else 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="scripts")
    a = ap.parse_args()
    sys.exit(main(a.dir))
