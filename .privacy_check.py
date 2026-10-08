import re

pats = [r"D:\\", r"C:\\", r"/storage/emulated", "\u5c0f\u7c73", "25019", "\u672c\u673a",
        "\u6211\u8fd9", "\u6211\u5728", "\u6211\u5173", "\u624b\u5934", "\u771f\u673a",
        "HyperOS", "dsh work region", "shots/", "env.ps1", "ScriptBlock",
        r"\bsha256\b", r"\bpid\b", r"\bI have\b", r"\bmy\b", r"\bI\b"]

for f in [r"D:\dsh work region\local-music\README.md",
          r"D:\dsh work region\local-music\README.en.md"]:
    s = open(f, "rb").read().decode("utf-8-sig")
    lines = s.split("\n")
    print("=====", f.split("\\")[-1], "lines:", len(lines), "BOM:",
          open(f, "rb").read()[:3] == b"\xef\xbb\xbf")
    for p in pats:
        hits = [(i + 1, l.strip()[:160]) for i, l in enumerate(lines) if re.search(p, l)]
        if hits:
            print("  HIT", p, len(hits))
            for n, t in hits:
                print("      line", n, ":", t)
    print("  headings:", [l.strip() for l in lines if l.startswith("#")])
    print("  fences:", s.count("```"))
