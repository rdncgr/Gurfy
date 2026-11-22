// Ortak arayüz modülü: Gurfy Sync (:app) ve Gurfy (:gurfy) aynı ekranları kullanır.
// Veri katmanı burada yalnızca arayüz (Backend, Library, MusicService); gerçeklemesi her uygulamadadır.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.rdncgr.gurfy.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    api(platform("androidx.compose:compose-bom:2024.12.01"))
    api("androidx.core:core-ktx:1.15.0")
    api("androidx.activity:activity-compose:1.9.3")
    api("androidx.compose.ui:ui")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.material3:material3")
    api("androidx.compose.material:material-icons-extended")
    api("androidx.media3:media3-exoplayer:1.5.1")
    api("androidx.media3:media3-session:1.5.1")
    api("androidx.media3:media3-exoplayer-hls:1.5.1") // HLS (.m3u8) radyo yayınları
    api("com.google.guava:guava:33.3.1-android")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("io.coil-kt:coil-compose:2.7.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
