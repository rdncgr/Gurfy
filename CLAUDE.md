# Bu depoda iki uygulama var, arayüzleri ortak

| | **Gurfy Sync** | **Gurfy** |
|---|---|---|
| Ne | Navidrome + Gurfy servisi (ev sunucusu) ile çalışan istemci | Sunucusuz: kütüphane, indirme, öneri, şarkı tanıma telefonda |
| Gradle modülü | `:app` (`app/`) + sunucu `server/` | `:gurfy` (`gurfy/app/`) |
| Ortak arayüz | `:shared` (`shared/`): bütün ekranlar, oynatıcı, modeller | aynı |
| Veri katmanı | `Subsonic.kt`, `ServiceApi.kt`, `SyncBackend.kt` | `LocalLibrary.kt`, `LocalService.kt`, `GurfyApp.kt` |
| Paket / applicationId | `com.rdncgr.gurfysync` | `com.rdncgr.gurfy` |
| Derleme | `./derle.sh` -> `gurfy-sync.apk` | `gurfy/derle.sh` -> `gurfy/gurfy.apk` |
| Emülatör | `telefon.bat` | `gurfy/telefon.bat` |
| CI | `.github/workflows/apk.yml` (`gurfy/**` hariç) | `.github/workflows/gurfy-apk.yml` (`gurfy/**` ve `shared/**`) |
| Release etiketi | `surum-N`, `gurfy-sync-N.apk` | `gurfy-N`, `gurfy-N.apk` |
| Simge / vurgu rengi | Sarı `#F2B84B` | Turkuaz `#4BC3B4` |

## Yapı

- Tek Gradle derlemesi (kök `settings.gradle.kts`): `:shared`, `:app`, `:gurfy`.
- Ekranlar veriyi yalnızca `shared/.../Backend.kt` arayüzlerinden alır: `Library` (Navidrome'un işi) ve
  `MusicService` (Gurfy servisinin işi). Adlar eski kodla uyum için `Graph.subsonic` ve `Graph.service`.
- Her uygulama kendi `Application` sınıfında `Graph.backendFactory`'yi doldurur (`SyncApp`, `GurfyApp`).
- Uygulamaya göre değişen davranış `Backend` bayraklarıyla seçilir (`hasRemoteLibrary`, `accent`, `audioProcessors`,
  `ConnectionSettings`); metinler `Words` nesnesinde.

## Kurallar

- Ekran / arayüz değişikliği `shared/` içinde yapılır ve iki uygulamaya birden gelir. Değiştikten sonra iki APK da
  derlenip denenir. Yalnızca bir uygulamaya özel davranış gerekiyorsa `Backend`'e bayrak eklenir.
- Veri katmanı yalnızca kendi modülündedir: `:gurfy` `com.rdncgr.gurfysync` paketini ya da `server/app.py`'yi asla
  çağırmaz; `:app` Gurfy'nin yerel sınıflarını kullanmaz.
- `LocalService`, `server/app.py` uç noktalarıyla aynı JSON biçimini döndürür. Sunucuda bir uç nokta değişirse
  Gurfy'deki karşılığı da güncellenir (ve tersi).
- Bir istek hangi uygulamayla ilgili belli değilse sor.
- `.tools/`, `.gradle-home/` ve `keystore/` iki uygulama tarafından ortak kullanılır.
- Arayüz metinleri ve hata mesajları Türkçe.
