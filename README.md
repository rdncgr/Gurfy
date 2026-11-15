# Nota

Ev sunucusundaki Navidrome'dan müzik çalan, benzer şarkıları bulup tek dokunuşla
sunucuya indirten Android uygulaması.

İki parçadan oluşur:

| Parça | Nerede çalışır | Ne yapar |
|---|---|---|
| **Uygulama** (`app/`) | Android telefon | Kütüphane, arama, çalma listeleri, arka planda çalma, telefona indirip çevrimdışı dinleme, benzer şarkılar, Spotify listesi aktarma |
| **Nota servisi** (`server/`) | ZimaOS sunucu | Last.fm'den benzer şarkı bulur, yt-dlp ile indirir, etiketler, Navidrome klasörüne koyar ve taramayı başlatır |

## 1. Sunucuya Nota servisini kurma (ZimaOS)

1. **Last.fm anahtarı alın:** https://www.last.fm/api/account/create — yalnızca "Application name"
   doldurmak yeterli. Çıkan **API key** değerini kopyalayın.
2. ZimaOS **Dosyalar** uygulamasında `AppData` içinde `nota-servis` klasörü oluşturun ve
   `server/app.py` dosyasını içine yükleyin.
3. Navidrome uygulamasının ayarlarını açıp `/music` karşısında yazan klasör yolunu not edin.
4. `server/zimaos-app.yaml` dosyasını açın, **DEĞİŞTİR** yazan satırları doldurun
   (parola, Last.fm anahtarı, Navidrome kullanıcı/parola, müzik klasörü).
5. ZimaOS'ta **Özelleştirilmiş Uygulama Yükle → İçe aktar** deyip YAML'ı yapıştırın ve kurun.
6. İlk açılış 1-2 dakika sürer. Tarayıcıda `http://192.168.1.101:8099/health` açıldığında
   `"ok": true` görüyorsanız servis hazırdır.

Servis yalnızca müzik klasörüne yazar; fotoğraf diskine dokunmaz.

## 2. APK'yı alma

Kod GitHub'a gönderildiğinde **Actions** otomatik derler. Deponun **Releases** bölümündeki
en üstteki `nota-N.apk` dosyasını telefona indirip kurun. Yeni sürümler eskisinin üzerine kurulur.

## 3. Uygulama ayarları

- **Navidrome adresi:** `http://192.168.1.101:4533` (ev dışında Tailscale adresi)
- **Kullanıcı adı / parola:** Navidrome hesabınız
- **Nota servis adresi:** `http://192.168.1.101:8099`
- **API anahtarı:** YAML'daki `API_KEY` ile aynı

## Notlar

- **Radyolar:** Türkiye radyoları radio-browser.info'dan güncel adresleriyle çekilir ve
  `/DATA/AppData/nota-servis/radyolar.json` dosyasında saklanır (günde bir yenilenir). Bir adres
  bozuksa ya da listede olmayan bir radyo eklemek istersen aynı klasöre `radyolar-ozel.json` yaz:
  `[{"name": "Radyo Adı", "url": "https://akış-adresi"}, {"name": "İstemediğim Radyo", "hidden": true}]`.
  Aynı isimli kayıt otomatik listedekinin yerine geçer; Navidrome'daki aynı isimli radyonun adresi
  de kendiliğinden güncellenir (servisin Navidrome kullanıcısı yönetici olmalı).
- **Dünya radyoları:** radio-browser.info'da en çok dinlenen 50 radyo `radyolar-dunya.json` dosyasında
  saklanır, günde bir yenilenir.
- **İndirme geçmişi** `indirmeler.json` dosyasında tutulur; servis yeniden başlasa da kaybolmaz,
  yarım kalan indirmeler tekrar sıraya girer.
- **Sunucudan silme:** Uygulamadan "Sunucudan sil" denince servis dosyanın yerini Navidrome'dan sorar,
  müzik klasöründen siler ve taramayı başlatır. Servisin müzik klasörü Navidrome'unkiyle aynı klasör
  olmalı (varsayılan YAML'da ikisi de `/DATA/Media/Music`).
- **Türler:** Servis indirdiği şarkıya Last.fm etiketlerinden tür yazar (Türkçe Pop, Arabesk, Anadolu Rock…).
  Eski şarkılar için uygulamada Kütüphane > Türler > "Otomatik tür ekle" düğmesi var; yalnızca türü boş
  olan dosyalara yazar, türü olanlara dokunmaz, bitince Navidrome'u taratır.
- **Last.fm geçmişi:** Uygulamada Ayarlar > Bağlantılar'a Last.fm kullanıcı adı yazılınca servis
  dinleme geçmişini okur (yetki gerekmez): Keşfet > Sana özel öneriler, ana sayfada "Unuttukların",
  Kütüphane'deki istatistik ekranı. Sonuçlar serviste birkaç saat önbellekte tutulur.
- **YouTube engelleri:** İndirmeler sırayla yapılır ve iki YouTube erişimi arasında 20–30 sn beklenir
  (`DOWNLOAD_DELAY_MIN` / `DOWNLOAD_DELAY_MAX` ile değiştirilebilir). Bot engeline takılan şarkı 10 dk
  sonra bir kez daha denenir. YAML'daki `bgutil-provider` konteyneri YouTube'un istediği PO token'ı
  üretir (hesap gerekmez); `deno` paketi yt-dlp'nin YouTube'daki JS doğrulamasını çözer. İkisinin
  durumu uygulamada Ayarlar > Bağlantılar'da görünür.
- **Bu şarkı ne?** Radyo çalarken servis önce yayının kendi başlık bilgisine (ICY) bakar; orada
  "Sanatçı - Şarkı" yoksa ffmpeg ile 10 saniye kaydedip `shazamio` ile tanır. Bunun için YAML'daki
  `pip install` satırında `shazamio` olmalı (sunucu `/health` cevabında `"shazam": true` görünür).
- YouTube / YouTube Music listeleri (bağlantıda `list=` olmalı) yt-dlp ile okunur; her şarkı
  kendi video adresinden indirilir. Gizli listeler okunamaz.
- Spotify aktarma, herkese açık listelerde anahtarsız çalışır (ilk ~100 şarkı). Daha uzun
  listeler için YAML'a `SPOTIFY_CLIENT_ID` ve `SPOTIFY_CLIENT_SECRET` ekleyin.
- İndirilen şarkılar `Sanatçı/Albüm/Şarkı.m4a` düzeninde kaydedilir; albümü bulunamayanlar
  `Tekliler` klasörüne gider.
- **İmza anahtarı** (`keystore/debug.keystore`) depoya konmaz. GitHub'da Settings > Secrets and variables >
  Actions altına `KEYSTORE_BASE64` adıyla dosyanın base64 halini ekleyin
  (`base64 -w0 keystore/debug.keystore`). Aynı anahtarla imzalanmayan APK eskisinin üzerine kurulmaz.
