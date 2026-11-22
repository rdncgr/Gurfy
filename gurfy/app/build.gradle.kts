plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    // Gurfy Sync "com.rdncgr.gurfysync" kullanır; iki uygulama telefonda yan yana kurulabilsin diye kimlik farklı
    namespace = "com.rdncgr.gurfy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rdncgr.gurfy"
        // Android 10+: şarkılar MediaStore ile ortak Müzik/Gurfy klasörüne yazılır (izin gerekmez)
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // Aynı imza: yeni sürüm eskisinin üzerine kurulur (anahtar depo kökündeki keystore/ klasöründe)
    signingConfigs {
        create("gurfy") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("gurfy")
        }
        getByName("release") {
            // R8 küçültme: APK boyutunu ~3'te 1'e indirir
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("gurfy")
        }
    }

    compileOptions {
        // NewPipeExtractor minSdk 33 altında java.nio desugaring ister
        isCoreLibraryDesugaringEnabled = true
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
    packaging {
        resources.excludes += setOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*", "**/*.proto")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Ekranlar, oynatıcı ve modeller ortak modülde (Gurfy Sync ile aynı arayüz)
    implementation(project(":shared"))

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.3")

    // Arama ve akış çözücü (sunucudaki yt-dlp'nin yerini alır)
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.27.1")

    // Yerel veritabanı: kütüphane, listeler, favoriler, indirme kuyruğu (Navidrome'un yerini alır)
    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // android.jar'daki org.json yalnızca taslak
}
