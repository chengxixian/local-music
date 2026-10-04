// local music —— 工程结构
//
// 三个模块：
//   :app          前端（liquid-miuix：Monet 取色主页 + 液态玻璃播放控件/dock）
//   :audio        后端（移植自 Rueded/AURALIS 的播放/扫描内核 + ncmdump 的 ncm 解码）
//   :liquid-miuix 前端库，直接引用工作区里已 clone 的 chengxixian/liquid-miuix 源码
//
// 关于 :liquid-miuix —— 不复制代码，用 projectDir 指到库的 library 模块。
// 库的 plugin 版本由本工程根 build.gradle.kts 统一声明（include 构建时用的是
// 本工程的 pluginManagement，不是库自己的）。
pluginManagement {
    repositories {
        // 国内镜像优先；海外用户可删掉这四行，直接用 google() / mavenCentral()
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "local-music"

include(":app")
include(":audio")

// chengxixian/liquid-miuix 的 library 模块
include(":liquid-miuix")
project(":liquid-miuix").projectDir = file("third_party/liquid-miuix")
