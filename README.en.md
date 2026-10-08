<p align="center">
  <img src="docs/banner.png" width="100%" alt="local music — a local-first Android HiFi player">
</p>

<h1 align="center">local music</h1>

<p align="center">
  A local-first Android player for high-resolution music: the library is built from files already on
  the device, <code>.ncm</code> is converted to FLAC on the device, and PCM is sent to a USB DAC at the
  file's native sample rate.
</p>

<p align="center">
  <a href="README.md">中文</a> · <b>English</b> (this file)
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="License: GPL-3.0"></a>
  <a href="https://github.com/chengxixian/local-music/releases"><img src="https://img.shields.io/badge/release-v0.7.0-blue" alt="Latest release: v0.7.0"></a>
  <img src="https://img.shields.io/badge/Android-13%2B%20(API%2033)-3DDC84?logo=android&logoColor=white" alt="Android 13+ (API 33)">
  <img src="https://img.shields.io/badge/USB%20passthrough-Android%2014%2B%20(API%2034)-3DDC84?logo=android&logoColor=white" alt="USB passthrough: Android 14+ (API 34)">
  <img src="https://img.shields.io/badge/language-Kotlin%20%2B%20C-7F52FF" alt="Kotlin + C">
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose%20%2B%20liquid--miuix-4285F4" alt="Jetpack Compose + liquid-miuix">
</p>

The playback core is ported from [Rueded/AURALIS](https://github.com/Rueded/AURALIS) (GPL-3.0); the
liquid glass UI uses [chengxixian/liquid-miuix](https://github.com/chengxixian/liquid-miuix) (vendored
under `third_party/`); `.ncm` decoding is ported from
[taurusxin/ncmdump](https://github.com/taurusxin/ncmdump).

## Features

### Audio formats

| Class | Formats | Notes |
|---|---|---|
| Lossless / hi-res PCM | FLAC, WAV, ALAC (m4a) | Real bit depth and sample rate are read from the file header during scanning (Android's media APIs often report 96 kHz material as 48 kHz) and shown per track on the library cards and the player page |
| Lossy | MP3, AAC (m4a), OGG Vorbis, Opus, AMR | Decoded by Media3 ExoPlayer |
| DSD | `.dsf` / `.dff` | Decoded on device: an in-app DSF/DFF header parser (DSD64/128/256) plus two cascaded 4:1 boxcar stages (an equivalent 16-tap triangular window) decimate the 1-bit stream to **176.4 kHz/24-bit PCM**, cached after the first conversion; the resulting PCM can still go through USB passthrough. This is **not** native DSD / DoP passthrough |
| NetEase container | `.ncm` | Decrypted and converted on device, with no network involved; see `.ncm` conversion below |

### Feature overview

| Module | Notes |
|---|---|
| Library | Two-column portrait card grid (square artwork on top, title and spec line below) with favourite / add-to-playlist / play buttons on each card; sources are the MediaStore volumes, folders the user has authorised through SAF, and app-private storage, de-duplicated by real path |
| Search | The top-bar search field filters the current list by title / artist / album; the queue panel on the player page searches too. The playback queue follows the filtered list, so it never jumps to a track outside the results |
| Playback | Media3 ExoPlayer with `DefaultAudioSink(enableFloatOutput = true)`; `MediaSessionService` provides background playback and the system media notification (previous / play / next) |
| Playback queue | Tap a row to jump, reorder, or remove; the `+` button on a card appends to the current queue; the queue is taken from the list shown on the page (library / playlist / favourites), tapping a track keeps the shuffle setting, and the queue wraps back to the first track after a full round (list repeat by default, switchable between off / list / single) |
| USB passthrough | A self-written UAC2 isochronous output path (JNI + usbfs) that sets the DAC clock to the file's native sample rate and follows the device's asynchronous feedback; see [USB passthrough](#usb-passthrough) |
| `.ncm` conversion | FLAC payload → restored byte for byte, lossless and with no re-encoding; MP3 payload → decoded and written as a valid FLAC stream, with the sidecar JSON recording `payloadFormat` honestly; FLAC that fails verification is still published but marked `flac-unverified-raw`, and an MP3 payload that cannot be converted is published as the original `.mp3`; output can be published to a SAF folder of choice, named `Artist - Title` |
| Artwork | User-chosen image → system thumbnail → embedded artwork, in that order; a chosen image is copied into app-private storage so it survives deletion of the original; thumbnails are cached to disk per URI and target size |
| Lyrics | Timestamped LRC with line highlighting and auto-scroll (the current line takes its colour from the cover of the playing track); sources in order: scraped cache → sibling `.lrc` → embedded lyrics |
| Scraping | Covers: NetEase Music → iTunes → Deezer; lyrics: NetEase Music → LRCLIB. Fills gaps only, throttled in batches, never overwrites embedded artwork, and embedded artwork can be restored in one tap |
| Playlists | Second dock tab, two-column cards; create / rename / change cover / delete; without a custom cover a playlist uses its first track's artwork, and an empty playlist falls back to the dot-matrix mark |
| Favourites | Heart buttons in the library and on the player page, with a dedicated favourites list and a queue that matches it |
| Equalizer | System `android.media.audiofx` (equalizer / bass boost / loudness enhancer) attached to the current playback session; the number of bands is decided by the device (usually 5); while USB passthrough is active the system mixer is bypassed and the panel says so instead of pretending to work |
| Liquid glass UI | A capture layer plus a glass layer: glass top bar, floating dock capsule, mini player bar, and the player page's cover halo and control panel. The player background is the current cover scaled up and blurred, drawn inside the capture layer, so every glass surface refracts that blurred cover |
| Click wheel | iPod-style wheel: rotate to step to the previous / next item (arc-length stepping, at most one step per frame, throttled haptic ticks), a single click on the centre key cycles Library → Playlists → Favourites → Settings, and a double click confirms (play the selected track / run the selected setting) |
| Tinted wheel glass | The wheel ring can take 8 presets (clear plus seven hues) or a custom hue / saturation / transmission, modelled as light absorption and applied to the wheel ring only |
| Languages | English / 简体中文 / 日本語 / Русский; every user-facing string lives in resources (English is the default fallback), switchable in Settings, and also through the Android 13+ per-app language setting |
| App icon | A project-owned dot-matrix `Lm` mark: an adaptive icon under `mipmap-anydpi-v26/` (no red dot) plus legacy PNGs under `mipmap-<dpi>/` (with the red dot) |
| Updates | "About → Check for updates" is triggered manually and reads `version.json` from the repository (GitHub raw, so it does not consume the REST API quota); a new version shows download progress and is handed to the system installer |
| About page | A dedicated page: dot-matrix mark and version, entries for source / check for updates / project notes / donate, and product, community and legal groups |

## Screenshots

| Home | Library | Player |
|---|---|---|
| <img src="docs/screenshots/home.png" width="250" alt="Home"><br><sub>Library overview and recently added</sub> | <img src="docs/screenshots/library-hires.png" width="250" alt="Library"><br><sub>Two-column cards with real format, bit depth and sample rate</sub> | <img src="docs/screenshots/player-glass.png" width="250" alt="Player"><br><sub>Blurred cover background and glass controls</sub> |
| <img src="docs/screenshots/playlists.png" width="250" alt="Playlists"><br><sub>Playlists: two-column cards</sub> | <img src="docs/screenshots/equalizer-glass.png" width="250" alt="Equalizer"><br><sub>Equalizer glass panel</sub> | <img src="docs/screenshots/export-folder.png" width="250" alt="Export folder"><br><sub>Prompt for the `.ncm` export folder</sub> |

<details>
<summary>More screenshots (library list)</summary>

| Library (search and favourites filter) |
|---|
| <img src="docs/screenshots/library-favorites.png" width="260" alt="Library list"> |

</details>

## Building

### Requirements

| Item | Requirement |
|---|---|
| JDK | 17 (`sourceCompatibility` and `jvmTarget` are both 17) |
| Android SDK | `compileSdk 37`, `targetSdk 36`, `minSdk 33` (Android 13+) |
| Gradle | 9.4.1 or newer (a wrapper is included) |
| Android Gradle Plugin | 9.2.1 (AGP 9 has built-in Kotlin support — do not declare `org.jetbrains.kotlin.android`) |
| Kotlin / Compose plugin | 2.4.20 |
| NDK | 28.2.13676358 (needed only by the native USB isochronous module in `:audio`) |

### Build and install

```bash
gradle :app:assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
gradle :audio:testDebugUnitTest    # unit tests for :audio
```

<details>
<summary>Dependencies, test fixtures and build notes</summary>

- All dependencies resolve from Google Maven and Maven Central; `:liquid-miuix` is provided by
  `third_party/liquid-miuix` inside the repository, so the build does not fetch that UI library separately.
- The repository list in `settings.gradle.kts` puts mirrors first; they can be removed for environments
  outside their regions.
- The wrapper's `distributionUrl` points at a mirror; it can be changed to the official Gradle
  distribution.
- The release build type has no separate signing configuration; this project's releases are debug builds.
- The NCM regression fixture `test.ncm` comes from upstream ncmdump, is third-party copyrighted content and
  is not distributed with the repository (see `.gitignore`): copy `test/test.ncm` from `taurusxin/ncmdump`
  to `audio/src/test/resources/ncm/test.ncm` when needed, otherwise the related test cases skip themselves.
- The real-world regression case needs the environment variable `LM_NCM_REAL_FILE` pointing at a `.ncm`
  file, and skips itself when the variable is unset.

</details>

## Project layout

```
app/                        Android application module (:app)
  src/main/java/com/localmusic/app/
    MainActivity.kt         capture layer / glass layer structure, dock, wheel and page switching
    data/                   library scanning, database, artwork cache, lyrics, scraping, playlists,
                            favourites, update check, DSD decoding
    ui/                     liquid glass components, player page, settings and glass panels, click wheel
audio/                      audio module (:audio)
  src/main/java/com/localmusic/audio/ncm/        .ncm decryption, FLAC validation, export and naming
  src/main/java/com/localmusic/audio/playback/   Media3 playback service, equalizer, UAC2 control and
                                                 isochronous stream, PCM decoding
  src/main/cpp/usbisoc.c                         native USB isochronous output (usbfs URB submit / reap
                                                 plus asynchronous feedback rate control)
third_party/liquid-miuix/   vendored liquid glass Compose library, included as :liquid-miuix
docs/                       banner, icons and screenshots
scripts/                    icon generation and artwork render scripts (Python)
version.json                manifest read by the in-app update check
NOTICE                      licence boundaries
```

## USB passthrough

Android's Java USB API (`UsbRequest`) supports control, bulk and interrupt transfers only — calling
`initialize()` on an isochronous endpoint fails outright — while audio streaming has to be isochronous.
The project therefore ships a minimal USB Audio Class 2 output implementation of its own.

| Stage | Approach |
|---|---|
| fd | The Kotlin side obtains the raw usbfs fd through `UsbDeviceConnection.getFileDescriptor()` |
| Transfer | The native layer (`audio/src/main/cpp/usbisoc.c`, built as `libusbisoc.so`) submits and reaps isochronous URBs with `USBDEVFS_SUBMITURB` / `USBDEVFS_REAPURB`, one iso packet per URB (a 125 µs USB high-speed frame), keeping several URBs in flight to avoid underflow |
| Device capabilities | UAC2 class descriptors are parsed for the clock source and for the bit depth and sample-rate table of each alternate setting; the rates the device accepts come from the system's `AudioDeviceInfo` audio profiles |
| Clock | `SET_CUR` sets the DAC clock to the **file's native sample rate** (no resampling) and `GET_CUR` reads it back for verification; the rate is set once more after the alternate setting is activated |
| Bit rate | Bytes per frame = sample rate × 4 bytes (32-bit subframes) × channels ÷ 8000 frames per second, aligned to whole sample frames so the channels cannot drift apart |
| Asynchronous feedback | When the device exposes a feedback endpoint, the native layer parses its Q16.16 rate and adjusts the bytes per frame accordingly (keeping the full fractional part in an accumulator), preventing periodic dropped or repeated samples |
| Data path | `MediaCodec` decodes → everything is normalised to 32-bit little-endian PCM → pipe → native isochronous writer → DAC. A sentinel guard aborts the stream when the share of near-full-scale samples looks wrong, so garbage is not pushed into the DAC |
| Sessions | Only one passthrough session at a time; stopping closes the write end of the pipe so the native `read` returns 0 and the interface is released, and only the session that still owns the interface may release it, so concurrent sessions cannot steal it from each other |
| UI | A "USB passthrough" switch in Settings; tapping a track in the library starts passthrough immediately; while it is active ExoPlayer is muted but still owns the UI, queue and progress |

### Two boundaries of the passthrough path

- **A sample rate the DAC does not support is refused**: the device reports the rates it accepts (for
  example a 48 kHz family without 44.1 kHz); when the file's rate is not among them the app does not pass
  it through and does not resample — it says so instead.
- **No measured end-to-end verification**: all the code can confirm is that the system and the device
  accepted the request; the analogue output has not been checked with measurement equipment, so both the
  UI and this document say "request accepted" rather than "measured bit-perfect".

## Known limitations

- **USB passthrough lacks measurement-grade verification**: no measurement equipment was used to check
  the DAC's analogue output, so the UI and this document only claim that the request was accepted. With no
  compatible DAC attached the app shows that it is waiting for one instead of pretending passthrough is on.
- **Only a narrow set of UAC2 devices has been exercised**: sample-rate capability is taken entirely from
  what the connected device reports, and a rate the DAC does not support is refused (better silence than
  playback that is off-pitch or noisy).
- **DSD is not native passthrough**: `.dsf` / `.dff` files are decoded to PCM before playback; Android's
  public API has no isochronous USB audio, and native DSD / DoP would need a separate driver. Whether a DAC
  advertises bit-perfect DSD is only probed, and the result is reported honestly in the diagnostic log
  (logcat tag `LMDsd`).
- **Hi-res has to go over USB**: Bluetooth is lossy and resamples; when the passthrough conditions are not
  met the app falls back to the system mixer (audible, but no longer bit-perfect).
- **Metadata scraping uses unofficial endpoints**: the NetEase Music endpoints are undocumented and need no
  account, so they may be rate-limited, region-dependent, or stop working (the app then falls back to
  iTunes / Deezer / LRCLIB); content returned by those services belongs to them and is for personal local
  use only.
- **`.ncm` to FLAC does not improve quality**: a lossy MP3 source converted into a FLAC container only
  changes the container, and the sidecar JSON records `payloadFormat` honestly; payloads that fail
  verification are marked `flac-unverified-raw`.
- **The equalizer depends on system audiofx**: when the device has no implementation the panel only says
  so, and while USB passthrough is active the system mixer is bypassed so the equalizer has no effect.
- **Updates are downloaded in-app**: this needs the `REQUEST_INSTALL_PACKAGES` permission and hands the APK
  to the system installer; releases use the debug signing configuration.
- **Localisation scope**: user-facing strings cover four languages; diagnostic and log strings in the data
  layer are still Chinese.
- **Android versions**: the app needs Android 13 (API 33) or newer; the system-level requests behind USB
  bit-perfect require Android 14 (API 34) or newer.

## Licence

- **Code**: licensed under the **GNU GPL-3.0** (see [LICENSE](LICENSE)). The playback core is ported from
  AURALIS, which is GPL-3.0 as well, so derivative works must also be released under GPL-3.0 with the
  copyright notices kept.
- **Brand assets**: the app icons (`app/src/main/res/mipmap-*`, `mipmap-anydpi-v26/`,
  `drawable-nodpi/ic_launcher_foreground.png`), `docs/banner.png`, `docs/app-icon.png`, every screenshot
  under `docs/screenshots/`, and the in-app dot-matrix `Lm` mark are original designs of this project and
  are **not covered by that GPL grant — all rights reserved**: forking the code, changing it and shipping
  it is welcome, and quoting the artwork in an article or review is fine with attribution, but the same or
  a closely similar icon / banner must not be used as the identity of another application, nor may it imply
  a connection with this project or an endorsement by it.
- The name "local music" and the marks above are **not registered trademarks**; "all rights reserved" in
  NOTICE is a copyright claim and does not amount to trademark registration.
- `.ncm` conversion and online scraping are meant for local music the user legitimately owns; the terms of
  the relevant services and local law still apply.
- The full text is in [NOTICE](NOTICE).

## Third-party

| Project | Used for | Licence / notes |
|---|---|---|
| [Rueded/AURALIS](https://github.com/Rueded/AURALIS) | Origin of the playback core | GPL-3.0; `:audio` ports its decode chain, output and true-spec parsing |
| [chengxixian/liquid-miuix](https://github.com/chengxixian/liquid-miuix) | Liquid glass UI library | Vendored at `third_party/liquid-miuix` and included as `:liquid-miuix` |
| [taurusxin/ncmdump](https://github.com/taurusxin/ncmdump) | `.ncm` decoding | Ported; licence text in [LICENSE-ncmdump.txt](LICENSE-ncmdump.txt) and `audio/.../ncm/LICENSE.ncmdump.txt` |
| Media3 / ExoPlayer 1.10.0 | Decoding and playback | Apache-2.0 |
| Miuix | Compose components | Apache-2.0 |
| jaudiotagger 3.0.1 | Reading real bit depth and sample rate | See [NOTICE](NOTICE) and `app/build.gradle.kts` |
| kotlinx-coroutines | Concurrency | See `app/build.gradle.kts` |
