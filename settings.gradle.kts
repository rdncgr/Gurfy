pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") // NewPipeExtractor (yalnızca Gurfy)
    }
}
rootProject.name = "Nota"

// Ortak arayüz: ekranlar, oynatıcı, modeller. İki uygulama da bunu kullanır.
include(":shared")
// Gurfy Sync: Navidrome + Gurfy servisi (ev sunucusu) ile çalışır
include(":app")
// Gurfy: sunucusuz, her şey telefonda
include(":gurfy")
project(":gurfy").projectDir = file("gurfy/app")
