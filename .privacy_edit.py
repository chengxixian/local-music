import io, sys

P = r"D:\dsh work region\local-music\README.md"
raw = open(P, "rb").read()
print("before: BOM", raw[:3] == b"\xef\xbb\xbf", "CR", raw.count(b"\r"), "LF", raw.count(b"\n"))
s = raw.decode("utf-8-sig")
assert "\r\n" not in s, "unexpected CRLF"

def rep(old, new, label):
    global s
    n = s.count(old)
    if n != 1:
        print("FAIL", label, "count=", n)
        sys.exit(1)
    s = s.replace(old, new)
    print("ok  ", label)

# 2. toolchain line -> version-agnostic, no local paths
rep(
    "本机工具链在 `D:\\tool`（JDK 17 / Android SDK compileSdk 37 / Gradle 9.4.1 / NDK）。",
    "构建需要：JDK 17、Android SDK（compileSdk 37，含 targetSdk 36 / minSdk 33 对应的平台与构建工具）、"
    "Gradle，以及原生 USB 模块所需的 NDK。",
    "toolchain",
)

# 3. personal env-loading block -> portable commands only
rep(
    "```powershell\n"
    "# 载入工具链环境（本机执行策略为 Restricted，必须用 ScriptBlock 方式）\n"
    "$sb = [ScriptBlock]::Create((Get-Content \"D:\\tool\\env.ps1\" -Raw)); & $sb\n"
    "\n"
    "cd \"D:\\dsh work region\\local-music\"\n"
    "gradle :app:assembleDebug --console=plain          # 产物 app/build/outputs/apk/debug/app-debug.apk\n"
    "gradle :audio:testDebugUnitTest --console=plain    # NCM 解码器的真实样本回归测试\n"
    "```",
    "```bash\n"
    "gradle :app:assembleDebug --console=plain          # 产物 app/build/outputs/apk/debug/app-debug.apk\n"
    "gradle :audio:testDebugUnitTest --console=plain    # NCM 解码器的真实样本回归测试\n"
    "```",
    "env block",
)

# 5 + 6. section five heading -> project-level, no device/date
rep(
    "## 五、真机验证记录（2026-09-28，小米 25019PNF3C / Android 17）",
    "## 五、实机验证记录",
    "section5 heading",
)

# 4. emulator note
rep(
    "- **未在模拟器上验证**：本机没有安装 emulator 系统镜像；验证是在真机（小米 25019PNF3C / Android 17 / HyperOS）上做的，记录见下节。",
    "- **未在模拟器上验证**：目前的验证是在实体 Android 设备上完成的，模拟器上的行为可能不同。",
    "emulator note",
)

# evidence column: drop session-log narrative
rep(
    "| 安装启动 | 通过，无崩溃 | `adb install` Success；pid 存活；logcat 无 `FATAL/AndroidRuntime` |",
    "| 安装启动 | 通过，无崩溃 | `adb install` 成功；进程存活；logcat 无 `FATAL/AndroidRuntime` |",
    "row install",
)
rep(
    "| 播放链路 | 通过 | `dumpsys audio`：`package:com.localmusic.app type:android.media.AudioTrack`、`format update:FormatInfo{sampleRate=96000}`、HAL `sample_rate 96000, format 0x5`(PCM_FLOAT) |",
    "| 播放链路 | 通过 | `dumpsys audio` 中音频输出归属 `package:com.localmusic.app` 的 `android.media.AudioTrack`，`sampleRate=96000`、格式为 PCM_FLOAT |",
    "row playback",
)
rep(
    "| ncm → 真 FLAC | 通过 | `files/music/ncm/ncm-a1586b….flac`（155,727 B）+ 侧车 JSON；用独立 Python 脚本按规范解析：`16bit 44100Hz 2ch samples=176400`，文件 sha256 `973bb281…` |",
    "| ncm → 真 FLAC | 通过 | 私有目录产出 `.flac`（155,727 B）+ 侧车 JSON；按规范独立解析结果：`16bit 44100Hz 2ch samples=176400` |",
    "row ncm",
)
rep(
    "| 转换结果入库命名 | 通过 | 曲库出现「贝贝 / 李荣浩 · 耳朵 / FLAC 16bit/44.1kHz 0:04」——名字来自侧车元数据，不是哈希文件名 |",
    "| 转换结果入库命名 | 通过 | 曲库条目显示「艺术家 - 歌名 · FLAC 16bit/44.1kHz」——名字来自侧车元数据，不是哈希文件名 |",
    "row naming",
)
rep(
    "| 界面 | 通过 | 见 `shots/local-music/`：莫奈取色主页、玻璃 dock（滑动高亮胶囊）、玻璃迷你播放条、全屏玻璃播放器 |",
    "| 界面 | 通过 | 莫奈取色主页、玻璃 dock（滑动高亮胶囊）、玻璃迷你播放条、全屏玻璃播放器均正常显示 |",
    "row ui",
)

# 6. personal screenshot directory line
rep("截图目录：`D:\\dsh work region\\shots\\local-music\\`。\n\n", "", "shots line")

# 7. personal setting state -> neutral statement of the default
rep(
    "- 本机上「自动转换 ncm → FLAC」开关**已被我关掉**（`settings.xml` 里 `autoNcm=false`），\n"
    "  免得它接着把剩下 100 多个 ncm 也转掉；代码里的默认值仍是**开**。要用时在「设置」里打开即可。",
    "- 「自动转换 ncm → FLAC」开关的代码默认值是**开**（`settings.xml` 的 `autoNcm`）。不需要自动转换时，在「设置」里关掉即可。",
    "autoNcm",
)

out = b"\xef\xbb\xbf" + s.encode("utf-8")
open(P, "wb").write(out)
raw2 = open(P, "rb").read()
print("after : BOM", raw2[:3] == b"\xef\xbb\xbf", "CR", raw2.count(b"\r"), "LF", raw2.count(b"\n"), "lines", raw2.count(b"\n"))
