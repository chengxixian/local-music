import sys

P = r"D:\dsh work region\local-music\README.en.md"
raw = open(P, "rb").read()
print("before: BOM", raw[:3] == b"\xef\xbb\xbf", "CR", raw.count(b"\r"), "LF", raw.count(b"\n"))
s = raw.decode("utf-8-sig")

def rep(old, new, label):
    global s
    n = s.count(old)
    if n != 1:
        print("FAIL", label, "count=", n); sys.exit(1)
    s = s.replace(old, new); print("ok  ", label)

rep("> **Bluetooth is lossy and resamples, so hi-res has to go over USB.** The USB path is\r\n"
    "> implemented but **not verified on real hardware** \u2014 I have no DAC to test with.",
    "> **Bluetooth is lossy and resamples, so hi-res has to go over USB.** The USB path is\r\n"
    "> implemented but **not verified on real hardware** \u2014 no USB DAC was available for testing.",
    "bit-perfect note")

rep("- **DSD over USB is not verified end-to-end** \u2014 the code path exists, but I have no DSD\r\n"
    "  material and no DAC to test with. The app can *probe* whether a connected DAC advertises",
    "- **DSD over USB is not verified end-to-end** \u2014 the code path exists, but it has not been\r\n"
    "  exercised with DSD material or a DAC. The app can *probe* whether a connected DAC advertises",
    "DSD limitation")

out = b"\xef\xbb\xbf" + s.encode("utf-8")
open(P, "wb").write(out)
raw2 = open(P, "rb").read()
print("after : BOM", raw2[:3] == b"\xef\xbb\xbf", "CR", raw2.count(b"\r"), "LF", raw2.count(b"\n"))
