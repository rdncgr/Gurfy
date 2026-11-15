# Nota — Proje Notları

claude.ai'deki "Android müzik uygulaması Navidrome" sohbetinden aktarıldı (14.11.2026).

## Hedef

Android için "Nota" adında bir müzik uygulaması:

- Evdeki sunucuda (ZimaOS) çalışan **Navidrome**'dan müzik çekip çalar.
- **Benzer müzik önerisi** yapar (Last.fm).
- Önerilen şarkıları **yt-dlp** ile sunucuya indirir. Böylece kullanıcı kendi arşivini oluşturur.

## Alınan kararlar

| Konu | Karar |
|---|---|
| APK derleme | GitHub Actions ile otomatik. Push edilince APK derlenip **Releases** bölümüne düşer |
| Benzer müzik kaynağı | Last.fm API |
| Sunucu indirme tarafı | Kendi küçük servisimiz (`nota-servis`). Telefon bu servise istek atar |
| İlk sürüm kapsamı | Temel çalar, Benzer müzik + indir, Çevrimdışı dinleme, Spotify listesi aktarma |

## İlk sohbette yazılanlar (dosyalar sohbette ek olarak duruyor)

- `Nota proje.zip`: Android uygulaması ve README
- `nota-servis-app.py`: sunucu servisi
- `nota-servis-zimaos.yaml`: ZimaOS (Docker) kurulum dosyası

**Telefon uygulaması:** Navidrome kütüphanesi, arama, çalma listeleri, arka planda çalma, telefona indirip çevrimdışı dinleme, benzer şarkılar ve Spotify listesi aktarma.

**Sunucu servisi:** Last.fm'den benzer şarkıyı bulur, yt-dlp ile indirir, etiketler, Navidrome klasörüne koyar ve Navidrome taramasını başlatır.

## Durum ve bilinen riskler

- APK **henüz hiç derlenmedi**. Android kodu sadece yazım denetiminden geçti, ilk derlemede hatalar çıkabilir.
- Servis testleri yalnızca sahte verilerle geçti. Gerçek YouTube, Last.fm ve Spotify bağlantıları denenmedi.
- Spotify aktarma anahtarsız yöntemle yapılıyor. Sadece herkese açık listelerde ve muhtemelen ilk 100 şarkıda çalışır.
- YAML'da müzik klasörü `/DATA/Media/Music` olarak **varsayıldı**. Yol yanlışsa indirilen şarkılar Navidrome'da görünmez.

## Açık yapılacaklar

1. [x] Sohbetteki ekleri bu klasöre çıkar. Proje kökte, orijinal dosyalar `_kaynak/` içinde.
   - [x] APK yerelde hatasız derlendi (14.11.2026): `./derle.sh` komutu `nota.apk` dosyasını üretir. Telefonda henüz denenmedi.
2. [ ] GitHub'da `nota` adında boş ve **private** bir depo aç.
3. [ ] Last.fm API anahtarı al: https://www.last.fm/api/account/create
4. [ ] ZimaOS'ta Navidrome'un müzik klasörünün gerçek yolunu doğrula.
5. [ ] Kodu GitHub'a gönder, ilk derlemenin hatalarını düzelt.
6. [ ] Sunucu servisini ZimaOS'a kur (README'deki adımlar).
