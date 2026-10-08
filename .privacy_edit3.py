import sys

P = r"D:\dsh work region\local-music\README.md"
raw = open(P, "rb").read()
s = raw.decode("utf-8-sig")

def rep(old, new, label):
    global s
    n = s.count(old)
    if n != 1:
        print("FAIL", label, "count=", n); sys.exit(1)
    s = s.replace(old, new); print("ok  ", label)

rep("USB DAC 直通我在真机上**没有 DAC 可测**，这条只有实现、没有实测证据。",
    "USB DAC 直通**没有 DAC 可测**，这条只有实现、没有实测证据。",
    "bit-perfect note")
rep("- **USB Bit-perfect 未在有 DAC 的场景下验证**：手头没有 USB DAC，无法确认\"请求被接受后确实走了直通\"。",
    "- **USB Bit-perfect 未在有 DAC 的场景下验证**：没有 USB DAC，无法确认\"请求被接受后确实走了直通\"。",
    "no-DAC limitation")
rep("；`mipmap-anydpi-v26/ic_launcher(.round).xml`。自适应图标的前景/背景裁切预览正常 |",
    "；`mipmap-anydpi-v26/ic_launcher(.round).xml` |",
    "icon row tail")

open(P, "wb").write(b"\xef\xbb\xbf" + s.encode("utf-8"))
raw2 = open(P, "rb").read()
print("after: BOM", raw2[:3] == b"\xef\xbb\xbf", "CR", raw2.count(b"\r"), "LF", raw2.count(b"\n"))
