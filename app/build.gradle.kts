plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Sürüm app/surum.txt dosyasında ("1.0", "1.1", "1.1.1"). Kullanıcıya giden her değişiklikte artırılır;
// CI aynı sürümü ikinci kez yayınlamaz. versionCode sürümden türetilir: 1.2.3 -> 10203.
val appVersion = rootProject.file("app/surum.txt").readText().trim()
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { v ->
    v.getOrElse(0) { 0 } * 10000 + v.getOrElse(1) { 0 } * 100 + v.getOrElse(2) { 0 }
}

android {
    namespace = "com.rdncgr.gurfysync"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rdncgr.gurfysync"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
    }

    // Her derlemede aynı imza kullanılsın ki yeni sürüm eskisinin üzerine kurulabilsin
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
    // Ekranlar, oynatıcı ve modeller ortak modülde; burada yalnızca sunucu (Navidrome + Gurfy servisi) bağlantısı var
    implementation(project(":shared"))
}
