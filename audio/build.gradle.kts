plugins {
    // 同根 build 注释：AGP 9 内置 Kotlin，不要再声明 org.jetbrains.kotlin.android。
    id("com.android.library")
}

android {
    namespace = "com.localmusic.audio"
    compileSdk = 37

    // USB 等时输出必须走原生层：Android 的 Java USB API（UsbRequest）不支持等时传输，
    // 实测对等时端点调用 initialize 直接返回 false。原生层用 usbfs 的
    // USBDEVFS_SUBMITURB / REAPURB 直接提交等时 URB。
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 33
        externalNativeBuild {
            cmake {
                // 只依赖 NDK 自带的 linux/usbdevice_fs.h 与 log
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // ── 播放内核：与 AURALIS 同源（Media3 ExoPlayer 1.10.0）──
    // 这就是"后端"：解码链路、AudioTrack 输出、格式支持范围全部由它决定。
    // ExoPlayer 1.10 覆盖 mp3 / aac(m4a) / flac / wav / ogg-vorbis / opus / alac / amr，
    // 也就是"主流格式"那一档。
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.media3:media3-session:1.10.0")

    // ── 真实位深/采样率解析：AURALIS 的关键工程处理 ──
    // 系统 MediaMetadataRetriever 会把 96kHz 文件报成 48kHz，
    // 扫描阶段改用 jaudiotagger 读文件头，结果落库，播放时读库而不是现问系统。
    implementation("net.jthink:jaudiotagger:3.0.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}
