// 顶层构建脚本。
//
// 版本必须与 liquid-miuix 对齐：miuix 0.9.4 的 AAR 元数据要求
// compileSdk 37 + AGP >= 9.1.0 + Gradle >= 9.4.1。
//
// 注意：**不要**声明 `org.jetbrains.kotlin.android`。
// AGP 9 起已内置 Kotlin 支持，再声明会直接报错：
// "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0"
plugins {
    id("com.android.application") version "9.2.1" apply false
    id("com.android.library") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
