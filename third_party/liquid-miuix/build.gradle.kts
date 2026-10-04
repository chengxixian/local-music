plugins {
    // 注意：AGP 9 起 `com.android.library` 已内置 Kotlin 支持，
    // 再显式声明 `org.jetbrains.kotlin.android` 会报
    // "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support"。
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.liquidmiuix"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }

    buildFeatures {
        compose = true
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
}

dependencies {
    // ── miuix：MIUI / HyperOS 风格组件库 ──
    // 注意 groupId 是 top.yukonga.miuix.kmp（不是 top.miuix）。
    // 0.9.4 的 AAR 元数据要求 compileSdk 37 + AGP >= 9.1.0，且 miuix-blur 硬编码 minSdk 33。
    api("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    api("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")

    // ── 液态玻璃：真·折射 ──
    // miuix-blur 只有 blur + 描边，没有 lens（折射）；而折射才是「液态」与「磨砂」的分界。
    api("io.github.kyant0:backdrop-android:2.0.1")

    // ── 动态取色（Monet）：从壁纸取主色 ──
    api("com.materialkolor:material-kolor:4.1.1")

    // ── Material3 ──
    // 版本必须与 miuix 自己的依赖对齐！miuix 0.9.4 会把 material3 提到 1.5.0-alpha22，
    // 声明别的版本会被 Gradle 按「最高版本优先」改写，最终运行的版本既不是声明的那个、
    // 也可能与 miuix 编译时的 API 对不上。用 `./gradlew :library:dependencies` 核对。
    api("androidx.compose.material3:material3:1.5.0-alpha22")
    api("androidx.compose.material:material-icons-extended:1.7.8")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
}
