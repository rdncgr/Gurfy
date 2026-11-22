# Gurfy

**Sunucusuz Gurfy Sync.** Arayüz ve özellikler Gurfy Sync ile aynı. Navidrome'un ve ev sunucusundaki
Gurfy servisinin işlerini telefon kendisi yapar. Müzik uygulama üzerinden aranır, dinlenir ve telefona indirilir.

- Android 10 veya üstü
- İndirilen şarkılar telefonun **Music/Gurfy** klasörüne kaydedilir: `Sanatçı/Albüm/Şarkı.m4a`.
  Uygulama silinse de kalır; yeniden kurulunca kütüphane bu klasörden geri gelir.
- Benzer şarkılar, öneriler, türler, popüler listeler ve istatistikler için Ayarlar > Bağlantılar'a
  ücretsiz bir [Last.fm API anahtarı](https://www.last.fm/api/account/create) girilir. Dinlemelerin
  Last.fm hesabına gitmesi için gizli anahtar da girilip bir kez "Last.fm'e bağlan"a basılır.

## Sunucu işleri telefonda nasıl yapılıyor?

| Gurfy Sync'te | Gurfy'de |
|---|---|
| Navidrome kütüphanesi, albüm/sanatçı/tür, listeler, beğeniler, dinleme sayıları | Room veritabanı (`LocalLibrary.kt`) |
| Navidrome'un müzik klasörü ve taraması | MediaStore, Music/Gurfy (`MediaFiles.kt`) |
| yt-dlp ile arama ve indirme | NewPipeExtractor, 2 MB'lık parçalarla M4A (`YouTube.kt`, `Downloads.kt`) |
| mutagen + ffmpeg ile etiket ve kapak | Kotlin MP4 etiket yazıcı (`Mp4Tags.kt`) |
| İndirme kuyruğu, 20–30 sn ara, bot engelinde 10 dk sonra yeniden deneme | Aynısı; Room'da kuyruk + ön plan servisi |
| Last.fm / ListenBrainz: öneriler, istatistik, unuttukların, benzerler, türler | Telefondan doğrudan, aynı algoritma (`LastFm.kt`) |
| Arama (Last.fm) | YouTube Music şarkı araması (anahtar gerekmez) |
| Radyo listesi (radio-browser) | Telefondan doğrudan (`LocalService.kt` > `Radios`) |
| Radyoda şarkı tanıma (ffmpeg kaydı + shazamio) | Oynatıcının çıkışından ses kopyası + Kotlin Shazam imzası (`AudioTap.kt`, `Shazam.kt`) |
| Spotify / YouTube listesi aktarma | Spotify embed sayfası ve NewPipe liste çözücü |
| Navidrome'un Last.fm'e scrobble etmesi | Telefondan doğrudan Last.fm'e |
| Ayarların sunucuda yedeği | Android otomatik yedekleme |
| "Telefona indir", "Sık dinlenenleri otomatik indir" | Gerekmez, gizli: kütüphane zaten telefonda |

Ekranlar Gurfy Sync ile aynı koddur (`shared/` modülü). Ayrıntı: depo kökündeki `CLAUDE.md`.

## Bilinen sınırlar

- **YouTube değişiklikleri:** NewPipeExtractor güncellenince `app/build.gradle.kts` içindeki sürüm yükseltilip
  gönderilir; GitHub Actions yeni APK çıkarır.
- **Yeniden kurulumdan önceki dosyalar:** Android, uygulamanın kendisinin yazmadığı dosyayı sormadan değiştirmeye
  izin vermiyor. Yeniden kurulumdan önce inen şarkıların türü uygulamada değişir ama dosyaya yazılmaz; silmek için
  Dosyalar uygulaması gerekir.
- **Lisans:** NewPipeExtractor GPL-3.0. APK herkese açık dağıtılırsa kaynak kod da GPL-3.0 olmalı.

## Derleme

```
gurfy/derle.sh                  # release APK -> gurfy/gurfy.apk
gurfy/derle.sh assembleDebug    # hata ayıklama sürümü
gurfy/telefon.bat               # sanal telefonda kurup açar
```
