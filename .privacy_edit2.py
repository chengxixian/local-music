import sys

P = r"D:\dsh work region\local-music\README.md"
raw = open(P, "rb").read()
s = raw.decode("utf-8-sig")
assert "\r" not in s

def rep(old, new, label):
    global s
    n = s.count(old)
    if n != 1:
        print("FAIL", label, "count=", n); sys.exit(1)
    s = s.replace(old, new); print("ok  ", label)

rep("| 原文件保留 | 通过 | 授权目录里的 `贝贝.ncm` 仍在 |",
    "| 原文件保留 | 通过 | 授权目录里的原始 `.ncm` 文件仍在 |",
    "row original-file")
rep("圆形/方形裁切预览见 `shots/local-music/icon-preview.png` |",
    "自适应图标的前景/背景裁切预览正常 |",
    "icon row shots path")
rep('$env:LM_NCM_REAL_FILE = "...\\宇多田ヒカル - Distance.ncm"',
    '$env:LM_NCM_REAL_FILE = "<坏文件路径>"',
    "regression env var")

# restore the single CRLF the file originally carried on the 多语言 bullet
old = "- **多语言**：界面文案全部走资源文件"
i = s.index(old)
j = s.index("\n", i)
s = s[:j] + "\r" + s[j:]

open(P, "wb").write(b"\xef\xbb\xbf" + s.encode("utf-8"))
raw2 = open(P, "rb").read()
print("after: BOM", raw2[:3] == b"\xef\xbb\xbf", "CR", raw2.count(b"\r"), "LF", raw2.count(b"\n"))
