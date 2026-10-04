# IzzyOnDroid 收录申请（直接复制粘贴提交）

**提交入口**：https://gitlab.com/IzzyOnDroid/repo/-/issues/new （需要你的 GitLab 账号；
IzzyOnDroid 的主仓库在 GitLab 而不是 GitHub，我手上的 GitHub 凭证无法代你提交）

**标题**：

```
App inclusion request: local music (com.localmusic.app)
```

**正文**（直接粘贴）：

```markdown
### App name
local music

### Package name
com.localmusic.app

### Source code repository
https://github.com/chengxixian/local-music

### License
GPL-3.0 (playback core ported from Rueded/AURALIS, also GPL-3.0).
Brand assets (app icon, banner, screenshots) are All Rights Reserved — see NOTICE.

### Issue tracker
https://github.com/chengxixian/local-music/issues

### Description
A local-first Android HiFi music player. Scans local storage (MediaStore + SAF folders),
converts NetEase `.ncm` files to FLAC/MP3, scrapes cover art and lyrics, plays through a
float-PCM path with USB bit-perfect output on Android 14+ (pinned to the USB DAC via
`setPreferredAudioDevice` + `AudioMixerAttributes(MIXER_BEHAVIOR_BIT_PERFECT)`).
DSD (`.dsf`/`.dff`) is supported by decimating the 1-bit stream to 176.4 kHz/24-bit PCM
with an in-app decoder — not native DSD/DoP passthrough.

### Releases / APK
APK is attached to GitHub releases (latest: v0.5.3), asset name pattern
`local-music-v<version>-debug.apk`.
Debug builds are used intentionally; there is no separate signing config.

### Anti-features disclosure (honest list)
- **Self-update (-ish)**: the app can check `version.json` on GitHub raw and download the
  APK itself. As of 0.5.3 the **automatic check on launch is disabled** — updates are only
  triggered manually from the About page. If a fully self-update-free build is required for
  inclusion, I can gate this behind a build flag and ship a variant without it.
- **NonFreeNet**: cover/lyrics scraping uses iTunes, Deezer, LRCLIB and undocumented NetEase
  endpoints (no account, no API key). NetEase serves copyrighted content; personal local use.
- **NonFreeFormat(net)**: `.ncm` is a proprietary NetEase container; conversion runs entirely
  on-device with no network involved.
- No ads, no analytics, no tracking, no accounts.

### Build notes
- `minSdk 33` (Android 13+), `compileSdk 37`, `targetSdk 36`, Kotlin 2.4.20, AGP 9.2.1.
- All dependencies resolve from Google/Maven Central. The glass UI library is **vendored in
  the repository** at `third_party/liquid-miuix/` (source in-repo, no JitPack), and
  `settings.gradle.kts` points `:liquid-miuix` at it — so the build is self-contained.
- Build command: `gradle :app:assembleDebug`
```

## 提交前建议先确认

1. **自更新**：`0.5.3` 已把启动自动检查关掉 ✓（仅在「关于 → 检查更新」手动触发 ✓）。
   如果对方要求彻底没有自更新，我可以加一个构建开关（`-PnoSelfUpdate=true` ✓）出一个干净变体 ✓。
2. **网易云接口** ⚠️：这是最可能被质疑的一项 ✓（非官方接口 + 版权内容 ✓）。若被要求，
   可以出一个"不含网易云源"的变体（刮削只用 iTunes / Deezer / LRCLIB ✓）。
3. **debug 签名**：我们按你的决定用 debug 构建发布 ✓；部分仓库只做提示、不拒绝 ✓，
   但若被要求 release 签名，我可以补一个 keystore 流程 ✓。

## 参考

- 收录规则与前提：IzzyOnDroid 的 GitLab 仓库说明（README / 申请模板）
- 我们的 NOTICE（授权边界）：[NOTICE](../NOTICE)
- 我们的 Release：https://github.com/chengxixian/local-music/releases
