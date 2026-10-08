plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.localmusic.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.localmusic.app"
        minSdk = 33
        targetSdk = 36
        versionCode = 71
        versionName = "0.7.1"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation(project(":audio"))
    implementation(project(":liquid-miuix"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.media3:media3-session:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("net.jthink:jaudiotagger:3.0.1")
    testImplementation("junit:junit:4.13.2")
}
