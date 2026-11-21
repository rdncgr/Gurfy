"""
Gurfy müzik servisi
------------------
Telefon uygulaması ile ev sunucusu arasındaki küçük yardımcı servis.

- Last.fm üzerinden benzer şarkı önerir ve şarkı arar
- Önerilen şarkıyı yt-dlp ile indirir, etiketler, Navidrome müzik klasörüne koyar
- Spotify veya YouTube çalma listesini okuyup hangi şarkıların eksik olduğunu söyler

Ayarlar ortam değişkenleriyle verilir (docker-compose dosyasına bakın).
"""
import base64
import hashlib
import json
import os
import queue
import re
import secrets
import threading
import time
import unicodedata
import uuid
from pathlib import Path
from typing import List, Optional
from urllib.parse import quote

import requests
from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import FileResponse, HTMLResponse, StreamingResponse
from pydantic import BaseModel

API_KEY = os.getenv("API_KEY", "")
LASTFM_API_KEY = os.getenv("LASTFM_API_KEY", "")
MUSIC_DIR = Path(os.getenv("MUSIC_DIR", "/music"))
NAVIDROME_URL = os.getenv("NAVIDROME_URL", "").rstrip("/")
NAVIDROME_USER = os.getenv("NAVIDROME_USER", "")
NAVIDROME_PASS = os.getenv("NAVIDROME_PASS", "")
SPOTIFY_CLIENT_ID = os.getenv("SPOTIFY_CLIENT_ID", "")
SPOTIFY_CLIENT_SECRET = os.getenv("SPOTIFY_CLIENT_SECRET", "")
SINGLES_FOLDER = os.getenv("SINGLES_FOLDER", "Tekliler")
MAX_DURATION = int(os.getenv("MAX_DURATION_SECONDS", "1200"))

# YouTube engellerine karşı:
#  - PO Token: bgutil sağlayıcısı (ayrı konteyner) yt-dlp'nin istediği token'ı üretir, hesap gerekmez.
#  - İndirmeler sırayla ve aralarında 20-30 sn beklenerek yapılır.
BGUTIL_URL = os.getenv("BGUTIL_URL", "").rstrip("/")
DOWNLOAD_DELAY_MIN = float(os.getenv("DOWNLOAD_DELAY_MIN", "20"))
DOWNLOAD_DELAY_MAX = float(os.getenv("DOWNLOAD_DELAY_MAX", "30"))
BOT_ERROR = re.compile(r"not a bot|sign in to confirm|po token|http error 403|forbidden|429|too many requests", re.I)


def pot_candidates() -> List[str]:
    """PO token sağlayıcısının olası adresleri: YAML'da verilen, aynı ağdaki konteyner adı,
    sunucunun kendi IP'si (Navidrome adresinden) ve Docker köprüsü."""
    from urllib.parse import urlparse

    out = [BGUTIL_URL] if BGUTIL_URL else []
    out.append("http://bgutil-provider:4416")
    host = urlparse(NAVIDROME_URL).hostname if NAVIDROME_URL else None
    if host:
        out.append(f"http://{host}:4416")
    out += ["http://127.0.0.1:4416", "http://172.17.0.1:4416", "http://host.docker.internal:4416"]
    return list(dict.fromkeys(out))


_pot = {"url": None, "checked": 0.0, "tried": []}


def pot_url() -> Optional[str]:
    """Çalışan ilk sağlayıcı adresi (1 dk önbellekli)."""
    ttl = 60 if _pot["url"] else 300  # bulunamadıysa her istekte yeniden aramasın
    if time.time() - _pot["checked"] < ttl:
        return _pot["url"]
    found, tried = None, []
    for url in pot_candidates():
        try:
            if requests.get(f"{url}/ping", timeout=2).ok:
                found = url
                break
            tried.append(f"{url}: yanıt hatalı")
        except Exception as e:
            tried.append(f"{url}: {type(e).__name__}")
    _pot.update(url=found, checked=time.time(), tried=tried)
    return found


# YouTube "bot değilim" engeli istemciye göre değişiyor: biri engellenince bunlar sırayla denenir.
FALLBACK_CLIENTS = ["tv_simply", "android_vr", "mweb", "web_embedded"]


def ytdlp_download(opts: dict, source: str) -> None:
    """yt-dlp ile indirir; bot engelinde başka YouTube istemcileriyle tekrar dener."""
    import yt_dlp

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            ydl.download([source])
        return
    except Exception as e:
        if not BOT_ERROR.search(str(e)):
            raise
        first = e
    for client in FALLBACK_CLIENTS:
        o = dict(opts)
        o["extractor_args"] = {**opts.get("extractor_args", {}), "youtube": {"player_client": [client]}}
        try:
            with yt_dlp.YoutubeDL(o) as ydl:
                ydl.download([source])
            return
        except Exception:
            continue
    raise first


_ipv6 = {"ok": None, "checked": 0.0}


def youtube_ipv6() -> bool:
    """YouTube'a IPv6 ile çıkılabiliyor mu? Ev bağlantılarında IPv4 adresi sık sık "bot" diye
    engelleniyor, IPv6 engellenmiyor. Konteyner host ağındaysa (network_mode: host) IPv6 kullanılabilir."""
    import socket

    if _ipv6["ok"] is not None and time.time() - _ipv6["checked"] < 600:
        return _ipv6["ok"]
    ok = False
    try:
        for fam, typ, proto, _, addr in socket.getaddrinfo("www.youtube.com", 443, socket.AF_INET6, socket.SOCK_STREAM):
            with socket.socket(fam, typ, proto) as sock:
                sock.settimeout(4)
                sock.connect(addr)
                ok = True
                break
    except Exception:
        ok = False
    _ipv6.update(ok=ok, checked=time.time())
    return ok


def ytdlp_opts(**opts) -> dict:
    """Tüm yt-dlp çağrıları için ortak ayarlar: PO token sağlayıcısı ve (varsa) IPv6."""
    if youtube_ipv6():
        opts["source_address"] = "::"
    url = pot_url()
    if url:
        opts["extractor_args"] = {"youtubepot-bgutilhttp": {"base_url": [url]}}
    return opts


UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"}

app = FastAPI(title="Gurfy müzik servisi")


def auth(x_api_key: str = Header(default="")):
    if API_KEY and not secrets.compare_digest(x_api_key, API_KEY):
        raise HTTPException(401, "Servis parolası hatalı")


# ---------------------------------------------------------------- yardımcılar


# İngilizce indirme / bağlantı hatalarını kullanıcıya Türkçe anlatır (yt-dlp, ffmpeg, ağ)
_TR_ERRORS = [
    (r"private video", "Video gizli; indirilemiyor"),
    (r"video unavailable|this video is no longer available|has been removed|account .* terminated", "Video YouTube'dan kaldırılmış ya da erişilemiyor"),
    (r"not available in your country|blocked it in your country|geo.?restrict", "Video bu ülkede engelli"),
    (r"confirm your age|age.?restricted|inappropriate for some users", "Yaş doğrulaması isteyen video indirilemiyor"),
    (r"members.?only|join this channel", "Video yalnızca kanal üyelerine açık"),
    (r"premieres in|live event will begin|is upcoming", "Video henüz yayınlanmamış"),
    (r"requested format is not available|no video formats found|no formats", "Uygun ses biçimi bulunamadı"),
    (r"http error 403|forbidden", "YouTube erişimi reddetti (403)"),
    (r"http error 404", "Kaynak bulunamadı (404)"),
    (r"http error 429|too many requests", "YouTube çok fazla istek nedeniyle geçici olarak engelledi (429)"),
    (r"timed? ?out", "Bağlantı zaman aşımına uğradı"),
    (r"name or service not known|temporary failure in name resolution|getaddrinfo failed", "İnternet bağlantısı yok (adres çözülemedi)"),
    (r"connection (refused|reset|aborted)|unable to connect|failed to establish", "Bağlantı kurulamadı"),
    (r"ffmpeg|ffprobe|postprocess", "Ses dosyası dönüştürülemedi (ffmpeg)"),
    (r"no space left", "Sunucuda yer kalmadı"),
    (r"permission denied", "Müzik klasörüne yazma izni yok"),
    (r"no (entries|results)|did not match any|unable to extract", "YouTube'da bulunamadı"),
]


def tr_error(msg: str) -> str:
    text = re.sub(r"\x1b\[[0-9;]*m", "", str(msg or "")).replace("ERROR: ", "").strip()
    for pattern, tr in _TR_ERRORS:
        if re.search(pattern, text, re.I):
            return tr
    return "Beklenmeyen hata: " + text[:160] if text else "Bilinmeyen hata"

def norm(s: str) -> str:
    """Karşılaştırma için sadeleştir: küçük harf, aksansız, parantez/ek bilgi yok."""
    s = s or ""
    s = re.sub(r"\(.*?\)|\[.*?\]", " ", s)
    s = re.sub(r"\s-\s.*(remaster|version|edit|mix|live|mono|stereo).*$", " ", s, flags=re.I)
    s = re.sub(r"\b(feat|ft)\.?\s.*$", " ", s, flags=re.I)
    s = s.replace("ı", "i").replace("İ", "i")
    s = unicodedata.normalize("NFKD", s.casefold())
    s = "".join(c for c in s if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9]+", " ", s).strip()


def safe_name(s: str) -> str:
    """Dosya/klasör adı için güvenli hale getir."""
    s = re.sub(r'[\\/:*?"<>|\x00-\x1f]', " ", s or "")
    s = re.sub(r"\s+", " ", s).strip(" .")
    return s[:120] or "Bilinmeyen"


# ------------------------------------------------------------------ Navidrome

def navidrome(endpoint: str, **params):
    if not (NAVIDROME_URL and NAVIDROME_USER):
        return None
    salt = secrets.token_hex(6)
    token = hashlib.md5((NAVIDROME_PASS + salt).encode()).hexdigest()
    q = {"u": NAVIDROME_USER, "t": token, "s": salt, "v": "1.16.1", "c": "nota-servis", "f": "json"}
    q.update(params)
    r = requests.get(f"{NAVIDROME_URL}/rest/{endpoint}", params=q, timeout=15)
    r.raise_for_status()
    body = r.json().get("subsonic-response", {})
    if body.get("status") != "ok":
        raise RuntimeError(body.get("error", {}).get("message", "Navidrome hatası"))
    return body


def library_song(artist: str, title: str) -> Optional[dict]:
    """Kütüphanedeki eşleşen şarkı (Subsonic song nesnesi) ya da None."""
    try:
        body = navidrome("search3", query=f"{artist} {title}", songCount=10, albumCount=0, artistCount=0)
    except Exception:
        return None
    if not body:
        return None
    na, nt = norm(artist), norm(title)
    for s in body.get("searchResult3", {}).get("song", []):
        sa, st = norm(s.get("artist", "")), norm(s.get("title", ""))
        if st == nt and (na in sa or sa in na):
            return s
    return None


def in_library(artist: str, title: str) -> bool:
    return library_song(artist, title) is not None


def start_scan():
    try:
        navidrome("startScan")
    except Exception:
        pass


# -------------------------------------------------------------------- Last.fm

def lastfm(method: str, **params):
    if not LASTFM_API_KEY:
        raise HTTPException(503, "LASTFM_API_KEY ayarlanmamış")
    q = {"method": method, "api_key": LASTFM_API_KEY, "format": "json", "autocorrect": 1}
    q.update(params)
    r = requests.get("https://ws.audioscrobbler.com/2.0/", params=q, timeout=15)
    data = r.json()
    if "error" in data:
        return {}
    return data


def as_list(x):
    if x is None:
        return []
    return x if isinstance(x, list) else [x]


# ---------------------------------------------------------------- ListenBrainz
# Benzer sanatçılarda Last.fm'e ikinci kaynak, kişisel haftalık keşif listesi.
# Okuma için anahtar gerekmez; yalnızca kullanıcı adı. Sanatçılar MusicBrainz kimliğiyle (MBID) tanınır.

LB_UA = {"User-Agent": "GurfyServis/1.0 ( https://github.com/rdncgr/Nota )", "Accept": "application/json"}
LB_SIMILAR_ALGO = "session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30"
MBID_CACHE = Path(os.getenv("MBID_CACHE", "/app/mbid.json"))
_mb_lock = threading.Lock()
_mb_last = [0.0]
_mbids: dict = {}
_mbids_loaded = [False]


def _mbid_load() -> None:
    if _mbids_loaded[0]:
        return
    _mbids_loaded[0] = True
    try:
        _mbids.update(json.loads(MBID_CACHE.read_text(encoding="utf-8")))
    except Exception:
        pass


def _mbid_save() -> None:
    try:
        MBID_CACHE.write_text(json.dumps(_mbids, ensure_ascii=False), encoding="utf-8")
    except Exception:
        pass


def artist_mbid(name: str) -> Optional[str]:
    """Sanatçının MusicBrainz kimliği; bulunamayan da kaydedilir ki tekrar sorulmasın.
    MusicBrainz saniyede bir istek ister, bu yüzden istekler sıraya girer."""
    key = norm(name)
    if not key:
        return None
    with _mb_lock:
        _mbid_load()
        if key in _mbids:
            return _mbids[key] or None
        found = None
        for attempt in range(3):
            wait = 1.1 - (time.time() - _mb_last[0])
            if wait > 0:
                time.sleep(wait)
            _mb_last[0] = time.time()
            try:
                r = requests.get("https://musicbrainz.org/ws/2/artist/", headers=LB_UA, timeout=15,
                                 params={"query": f'artist:"{name}"', "fmt": "json", "limit": 3})
            except requests.RequestException:
                return None  # ağ hatası: kaydetme, sonra tekrar denensin
            if r.status_code == 503:
                time.sleep(2 + attempt * 2)
                continue
            if r.status_code != 200:
                return None
            for a in r.json().get("artists", []):
                names = [a.get("name", "")] + [x.get("name", "") for x in a.get("aliases", []) or []]
                if int(a.get("score") or 0) >= 90 and any(norm(n) == key for n in names):
                    found = a.get("id")
                    break
            break
        else:
            return None
        _mbids[key] = found or ""
        _mbid_save()
        return found


def lb_similar_artists(name: str) -> List[tuple]:
    """ListenBrainz'e göre benzer sanatçılar: [(ad, 0..1 puan)]."""
    def build():
        mbid = artist_mbid(name)
        if not mbid:
            return []
        try:
            r = requests.get("https://labs.api.listenbrainz.org/similar-artists/json", headers=LB_UA, timeout=20,
                             params={"artist_mbids": mbid, "algorithm": LB_SIMILAR_ALGO})
            rows = r.json() if r.status_code == 200 else []
        except Exception:
            return []
        rows = [x for x in rows if isinstance(x, dict) and x.get("name")] if isinstance(rows, list) else []
        top = max([float(x.get("score") or 0) for x in rows] or [1.0]) or 1.0
        return [(x["name"], float(x.get("score") or 0) / top) for x in rows[:40]]
    return cached(("lb-similar", norm(name)), 7 * 24 * 3600, build)


def lfm_similar_artists(name: str, limit: int = 20) -> List[tuple]:
    if not LASTFM_API_KEY:
        return []
    data = lastfm("artist.getSimilar", artist=name, limit=limit)
    return [(x.get("name", ""), float(x.get("match") or 0))
            for x in as_list(data.get("similarartists", {}).get("artist")) if x.get("name")]


def similar_artists(name: str, limit: int = 12) -> List[tuple]:
    """Last.fm ve ListenBrainz birleşik: ikisinin de benzer dediği sanatçılar öne çıkar."""
    with ThreadPoolExecutor(2) as ex:
        f_lfm = ex.submit(lfm_similar_artists, name, 25)
        f_lb = ex.submit(lb_similar_artists, name)
        lists = [f_lfm.result(), f_lb.result()]
    # Last.fm verisi daha geniş (özellikle arabesk / halk): ana kaynak o, ListenBrainz daha düşük ağırlıkla
    # eklenir; ikisinin de benzer dediği sanatçı, iki puanın küçüğü kadar ek puan alır.
    weights = (1.0, 0.6)
    score, label, per = {}, {}, ({}, {})
    for lst, w, d in zip(lists, weights, per):
        for n, m in lst:
            k = norm(n)
            if not k or k == norm(name):
                continue
            d[k] = max(d.get(k, 0.0), m)
            score[k] = score.get(k, 0.0) + m * w
            label.setdefault(k, n)
    for k in set(per[0]) & set(per[1]):
        score[k] += 0.5 * min(per[0][k], per[1][k])
    best = sorted(score, key=score.get, reverse=True)[:limit]
    return [(label[k], score[k]) for k in best]


def lb_get(path: str, **params):
    r = requests.get("https://api.listenbrainz.org/1/" + path, headers=LB_UA, params=params, timeout=20)
    if r.status_code == 404:
        raise HTTPException(404, "ListenBrainz kullanıcısı bulunamadı")
    if r.status_code != 200:
        raise HTTPException(502, f"ListenBrainz cevap vermedi ({r.status_code})")
    return r.json()


def lb_exploration(user: str) -> List[dict]:
    """ListenBrainz'in kullanıcıya her hafta hazırladığı keşif listesindeki şarkılar."""
    def build():
        lists = lb_get(f"user/{quote(user)}/playlists/createdfor", count=25).get("playlists", [])
        pick = None
        for item in lists:
            pl = item.get("playlist", {})
            if "exploration" in (pl.get("title") or "").lower():
                pick = pl
                break
        if not pick:
            return []
        pid = (pick.get("identifier") or "").rstrip("/").split("/")[-1]
        tracks = lb_get(f"playlist/{pid}").get("playlist", {}).get("track", [])
        return [{"artist": t.get("creator", ""), "title": t.get("title", ""),
                 "note": "ListenBrainz haftalık keşif"} for t in tracks if t.get("title") and t.get("creator")]
    return cached(("lb-explore", norm(user)), 6 * 3600, build)


def similar_tracks(artist: str, title: str, limit: int) -> List[dict]:
    out, seen = [], set()

    def add(a, t):
        key = (norm(a), norm(t))
        if a and t and key not in seen and key != (norm(artist), norm(title)):
            seen.add(key)
            out.append({"artist": a, "title": t})

    if title:
        data = lastfm("track.getSimilar", artist=artist, track=title, limit=limit)
        for t in as_list(data.get("similartracks", {}).get("track")):
            add(t.get("artist", {}).get("name", ""), t.get("name", ""))

    # Şarkıya göre sonuç azsa sanatçıya göre tamamla
    if len(out) < limit // 2 and artist:
        names = [n for n, _ in similar_artists(artist, 8)]
        for name in [artist] + names:
            if len(out) >= limit:
                break
            top = lastfm("artist.getTopTracks", artist=name, limit=3)
            for t in as_list(top.get("toptracks", {}).get("track")):
                add(t.get("artist", {}).get("name", name), t.get("name", ""))
    return out[:limit]


def album_of(artist: str, title: str) -> Optional[str]:
    try:
        data = lastfm("track.getInfo", artist=artist, track=title)
        return data.get("track", {}).get("album", {}).get("title") or None
    except Exception:
        return None


# -------------------------------------------------------------------- indirme

jobs = {}
jobs_lock = threading.Lock()
job_queue: "queue.Queue[str]" = queue.Queue()
# İndirme listesi diskte tutulur: servis yeniden başlayınca (güncelleme vb.) geçmiş kaybolmaz,
# yarım kalan indirmeler tekrar sıraya alınır.
JOBS_FILE = Path(os.getenv("JOBS_FILE", "/app/indirmeler.json"))


def save_jobs() -> None:
    try:
        with jobs_lock:
            data = json.dumps(list(jobs.values()), ensure_ascii=False)
        tmp = JOBS_FILE.with_suffix(".tmp")
        tmp.write_text(data, encoding="utf-8")
        tmp.replace(JOBS_FILE)
    except Exception:
        pass


def load_jobs() -> None:
    try:
        saved = json.loads(JOBS_FILE.read_text(encoding="utf-8"))
    except Exception:
        return
    for job in sorted(saved, key=lambda j: j.get("created", 0)):
        if not isinstance(job, dict) or not job.get("id"):
            continue
        jobs[job["id"]] = job
        if job.get("status") in ("queued", "downloading"):
            job["status"], job["message"] = "queued", ""
            job_queue.put(job["id"])


def new_job(artist: str, title: str, album: Optional[str] = None, url: Optional[str] = None) -> dict:
    job = {
        "id": uuid.uuid4().hex[:12], "artist": artist.strip(), "title": title.strip(),
        "album": (album or "").strip() or None, "url": url,
        "status": "queued", "message": "", "created": time.time(),
    }
    with jobs_lock:
        jobs[job["id"]] = job
        # Liste şişmesin: en eski bitmiş işleri at
        if len(jobs) > 300:
            for old in sorted(jobs.values(), key=lambda j: j["created"])[:50]:
                if old["status"] in ("done", "error", "exists"):
                    jobs.pop(old["id"], None)
    job_queue.put(job["id"])
    save_jobs()
    return job


def fetch_audio(job: dict, base: Path) -> None:
    """yt-dlp ile sesi indirir; çıktı: base + '.m4a'"""
    import yt_dlp

    opts = ytdlp_opts(**{
        "format": "bestaudio[ext=m4a]/bestaudio/best",
        "outtmpl": str(base).replace("%", "%%") + ".%(ext)s",
        "noplaylist": True,
        "playlist_items": "1",
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "writethumbnail": True,
        "match_filter": yt_dlp.utils.match_filter_func(f"duration < {MAX_DURATION}"),
        "postprocessors": [
            {"key": "FFmpegExtractAudio", "preferredcodec": "m4a"},
            {"key": "FFmpegThumbnailsConvertor", "format": "jpg"},
            {"key": "EmbedThumbnail"},
        ],
    })
    q = f"{job['artist']} {job['title']}"
    sources = [job["url"]] if job.get("url") else [
        f"https://music.youtube.com/search?q={quote(q)}#songs",   # önce resmi ses kayıtları
        f"ytsearch1:{job['artist']} - {job['title']} audio",
    ]
    last_error = None
    for src in sources:
        try:
            ytdlp_download(opts, src)
            if Path(str(base) + ".m4a").exists():
                return
        except Exception as e:  # sıradaki kaynağı dene
            last_error = e
    raise RuntimeError(str(last_error) if last_error else "Uygun bir kayıt bulunamadı")


def write_tags(path: Path, artist: str, title: str, album: str) -> None:
    from mutagen.mp4 import MP4

    f = MP4(str(path))
    f["\xa9nam"] = [title]
    f["\xa9ART"] = [artist]
    f["aART"] = [artist]
    f["\xa9alb"] = [album]
    genres = genre_for(artist, title)
    if genres:
        f["\xa9gen"] = genres
    f.save()


def run_job(job: dict) -> bool:
    """True: YouTube'a gidildi (sonraki indirmeden önce beklenir)."""
    artist, title = job["artist"], job["title"]
    if in_library(artist, title):
        job["status"], job["message"] = "exists", "Zaten kütüphanede"
        return False
    album = job["album"] or (album_of(artist, title) if LASTFM_API_KEY else None)
    folder = MUSIC_DIR / safe_name(artist) / safe_name(album or SINGLES_FOLDER)
    base = folder / safe_name(title)
    final = Path(str(base) + ".m4a")
    if final.exists():
        job["status"], job["message"] = "exists", "Dosya zaten var"
        return False
    folder.mkdir(parents=True, exist_ok=True)
    job["status"] = "downloading"
    save_jobs()
    fetch_audio(job, base)
    write_tags(final, artist, title, album or title)
    for leftover in folder.glob(safe_name(title) + ".*"):
        if leftover.suffix.lower() in (".jpg", ".webp", ".png", ".part"):
            leftover.unlink(missing_ok=True)
    job["status"], job["message"] = "done", str(final.relative_to(MUSIC_DIR))
    start_scan()
    return True


def requeue_later(job: dict, seconds: int) -> None:
    def later():
        time.sleep(seconds)
        job["status"], job["message"] = "queued", ""
        save_jobs()
        job_queue.put(job["id"])
    threading.Thread(target=later, daemon=True).start()


def worker():
    """Tek iş parçacığı: şarkılar aynı anda değil sırayla iner; YouTube'a gidilen her indirmeden
    sonra 20-30 sn beklenir. Bot engeline takılan şarkı 10 dk sonra bir kez daha denenir."""
    import random

    last_online = 0.0  # son YouTube erişiminin bittiği an
    while True:
        job_id = job_queue.get()
        job = jobs.get(job_id)
        if not job:
            continue
        # Bir önceki YouTube erişiminden bu yana 20-30 sn geçmediyse bekle
        gap = random.uniform(DOWNLOAD_DELAY_MIN, DOWNLOAD_DELAY_MAX) - (time.time() - last_online)
        if last_online and gap > 0:
            job["message"] = f"Engel yememek için {int(gap)} sn ara veriliyor"
            time.sleep(gap)
            job["message"] = ""
        went_online = False
        try:
            went_online = run_job(job)
        except Exception as e:
            went_online = True
            msg = str(e)
            if BOT_ERROR.search(msg) and job.get("retries", 0) < 1:
                job["retries"] = job.get("retries", 0) + 1
                job["status"] = "queued"
                job["message"] = "YouTube engeli: 10 dk sonra tekrar denenecek"
                requeue_later(job, 600)
            else:
                if BOT_ERROR.search(msg) and not re.search(r"http error 404", msg, re.I):
                    msg = "YouTube engeli (bot doğrulaması). PO token sağlayıcısının çalıştığını kontrol edin."
                else:
                    msg = tr_error(msg)
                job["status"], job["message"] = "error", msg[:300]
        save_jobs()
        if went_online:
            last_online = time.time()


load_jobs()
threading.Thread(target=worker, daemon=True).start()


# -------------------------------------------------------------------- Spotify

def spotify_playlist_id(url: str) -> str:
    m = re.search(r"playlist[/:]([A-Za-z0-9]+)", url)
    if not m:
        raise HTTPException(400, "Bu bir Spotify çalma listesi bağlantısı değil")
    return m.group(1)


def spotify_via_api(pid: str) -> dict:
    cred = base64.b64encode(f"{SPOTIFY_CLIENT_ID}:{SPOTIFY_CLIENT_SECRET}".encode()).decode()
    r = requests.post("https://accounts.spotify.com/api/token", data={"grant_type": "client_credentials"},
                      headers={"Authorization": f"Basic {cred}"}, timeout=15)
    r.raise_for_status()
    h = {"Authorization": f"Bearer {r.json()['access_token']}"}
    r = requests.get(f"https://api.spotify.com/v1/playlists/{pid}", headers=h, timeout=15)
    r.raise_for_status()
    data = r.json()
    tracks, page = [], data.get("tracks", {})
    while page:
        for item in page.get("items", []):
            t = item.get("track") or {}
            if t.get("name") and t.get("artists"):
                tracks.append({"artist": t["artists"][0]["name"], "title": t["name"],
                               "album": (t.get("album") or {}).get("name")})
        nxt = page.get("next")
        page = requests.get(nxt, headers=h, timeout=15).json() if nxt else None
    return {"name": data.get("name", "Spotify listesi"), "tracks": tracks}


def find_key(obj, key):
    if isinstance(obj, dict):
        if key in obj:
            return obj
        for v in obj.values():
            hit = find_key(v, key)
            if hit:
                return hit
    elif isinstance(obj, list):
        for v in obj:
            hit = find_key(v, key)
            if hit:
                return hit
    return None


def parse_spotify_embed(page: str) -> dict:
    m = re.search(r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>', page, re.S)
    if not m:
        raise HTTPException(502, "Spotify sayfası okunamadı (liste herkese açık mı?)")
    entity = find_key(json.loads(m.group(1)), "trackList")
    if not entity:
        raise HTTPException(502, "Spotify listesinde şarkı bulunamadı")
    tracks = []
    for t in entity.get("trackList", []):
        artist = re.split(r",\s*", t.get("subtitle", "").replace("\xa0", " "))[0]
        if t.get("title") and artist:
            tracks.append({"artist": artist, "title": t["title"], "album": None})
    return {"name": entity.get("name") or entity.get("title") or "Spotify listesi", "tracks": tracks}


def spotify_playlist(url: str) -> dict:
    pid = spotify_playlist_id(url)
    if SPOTIFY_CLIENT_ID and SPOTIFY_CLIENT_SECRET:
        try:
            return spotify_via_api(pid)
        except Exception:
            pass  # anahtarsız yönteme düş
    r = requests.get(f"https://open.spotify.com/embed/playlist/{pid}", headers=UA, timeout=20)
    if r.status_code != 200:
        raise HTTPException(502, "Spotify listesi açılamadı (liste herkese açık mı?)")
    return parse_spotify_embed(r.text)


# -------------------------------------------------------------------- YouTube

YT_NOISE = re.compile(
    r"\s*[\(\[][^\)\]]*(official|video|audio|lyric|lyrics|klip|clip|hd|4k|visualizer|music video|"
    r"resmi|şarkı sözleri|sözleri)[^\)\]]*[\)\]]",
    re.I,
)


def youtube_playlist_url(url: str) -> str:
    m = re.search(r"[?&]list=([A-Za-z0-9_-]+)", url)
    if not m:
        raise HTTPException(400, "Bu bir YouTube çalma listesi bağlantısı değil (bağlantıda 'list=' olmalı)")
    return f"https://www.youtube.com/playlist?list={m.group(1)}"


def split_youtube_title(title: str, channel: str) -> tuple:
    """'Sanatçı - Şarkı (Official Video)' gibi başlıkları sanatçı ve şarkı adına ayırır."""
    clean = YT_NOISE.sub("", title or "").strip()
    clean = re.sub(r"\s*\|\s*.*$", "", clean).strip()  # "Şarkı | Albüm" gibi ekleri at
    parts = re.split(r"\s+[-–—]\s+", clean, maxsplit=1)
    if len(parts) == 2 and parts[0] and parts[1]:
        return parts[0].strip(), parts[1].strip()
    artist = re.sub(r"\s*-\s*Topic$|VEVO$|\s+(Official|Oficial|Resmi)$", "", channel or "", flags=re.I).strip()
    return artist, clean


def youtube_playlist(url: str) -> dict:
    import yt_dlp

    opts = ytdlp_opts(**{"extract_flat": "in_playlist", "quiet": True, "no_warnings": True,
            "skip_download": True, "playlistend": 500})
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(youtube_playlist_url(url), download=False)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(502, f"YouTube listesi açılamadı (liste gizli olabilir): {tr_error(e)}")
    tracks = []
    for e in info.get("entries") or []:
        if not e or not e.get("id"):
            continue
        title = e.get("title") or ""
        if title in ("[Deleted video]", "[Private video]"):
            continue
        channel = e.get("channel") or e.get("uploader") or ""
        artist, song = split_youtube_title(title, channel)
        if not song:
            continue
        tracks.append({"artist": artist or "Bilinmeyen", "title": song, "album": None,
                       "url": f"https://www.youtube.com/watch?v={e['id']}"})
    return {"name": info.get("title") or "YouTube listesi", "tracks": tracks}


# ------------------------------------------------------------------------ API

class Track(BaseModel):
    artist: str
    title: str
    album: Optional[str] = None
    url: Optional[str] = None


class Batch(BaseModel):
    tracks: List[Track]


class SpotifyReq(BaseModel):
    url: str


def public(job: dict) -> dict:
    return {k: job[k] for k in ("id", "artist", "title", "album", "status", "message", "created")}


def _has(module: str) -> bool:
    import importlib.util
    return importlib.util.find_spec(module) is not None


@app.get("/health")
def health():
    try:
        import yt_dlp
        ytv = yt_dlp.version.__version__
    except Exception:
        ytv = None
    nav = False
    nav_error = None
    try:
        nav = bool(navidrome("ping"))
        if not nav:
            nav_error = "NAVIDROME_URL veya NAVIDROME_USER boş"
    except Exception as e:
        nav_error = f"{type(e).__name__}: {e}"
    _pot["checked"] = 0  # sağlık kontrolünde adresleri yeniden dene
    pot = pot_url()
    import shutil
    return {"ok": True, "lastfm": bool(LASTFM_API_KEY), "navidrome": nav,
            "youtube_ipv6": youtube_ipv6(),
            "po_token": pot is not None, "po_token_url": pot or ", ".join(_pot["tried"]), "js_runtime": shutil.which("deno") is not None,
            "download_delay": [DOWNLOAD_DELAY_MIN, DOWNLOAD_DELAY_MAX],
            "navidrome_error": nav_error, "navidrome_url": NAVIDROME_URL,
            "spotify_api": bool(SPOTIFY_CLIENT_ID and SPOTIFY_CLIENT_SECRET),
            "yt_dlp": ytv, "shazam": _has("shazamio"), "music_dir_writable": os.access(MUSIC_DIR, os.W_OK)}


STATUS_PAGE = """<!doctype html>
<html lang="tr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Gurfy servisi</title>
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 108 108'%3E%3Crect width='108' height='108' rx='24' fill='%23F2B84B'/%3E%3Cpath fill='%23121417' transform='translate(-8 4)' d='M48,34 L48,62 A9,9 0 1,0 54,70.5 L54,44 L70,40 L70,56 A9,9 0 1,0 76,64.5 L76,28 Z'/%3E%3C/svg%3E">
<style>
:root{--bg:#121417;--card:#1b1e23;--text:#f5f1e8;--muted:#9aa0a6;--amber:#f2b84b;--ok:#7bc47f;--bad:#e5735c;--off:#6b7078}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.5 "Segoe UI",system-ui,sans-serif}
main{max-width:640px;margin:0 auto;padding:32px 16px 48px}
.brand{display:flex;align-items:center;gap:14px}.logo{width:52px;height:52px;border-radius:14px;background:var(--amber);display:grid;place-items:center}
h1{margin:0;font-size:30px}.lead{color:var(--muted);margin:6px 0 24px}
.card{background:var(--card);border-radius:14px;padding:18px 20px;margin-bottom:16px}
h2{font-size:15px;text-transform:uppercase;letter-spacing:.06em;color:var(--muted);margin:0 0 10px}
.addr{font:600 20px/1.3 ui-monospace,Consolas,monospace;color:var(--amber);word-break:break-all}
ol{margin:8px 0 0;padding-left:20px}li{margin:4px 0}
.row{display:flex;gap:12px;align-items:flex-start;padding:9px 0;border-top:1px solid #262a30}.row:first-of-type{border-top:0}
.dot{flex:none;width:10px;height:10px;border-radius:50%;margin-top:8px;background:var(--off)}.dot.ok{background:var(--ok)}.dot.bad{background:var(--bad)}
.row b{display:block;font-weight:600}.row span{color:var(--muted);font-size:14px}
footer{color:var(--muted);font-size:13px;text-align:center}footer a{color:var(--muted)}
</style></head><body><main>
<div class="brand"><div class="logo"><svg width="34" height="34" viewBox="22 22 64 64"><path fill="#121417" d="M48,34 L48,62 A9,9 0 1,0 54,70.5 L54,44 L70,40 L70,56 A9,9 0 1,0 76,64.5 L76,28 Z"/></svg></div>
<div><h1>Gurfy servisi çalışıyor</h1></div></div>
<p class="lead">Bu servis, Gurfy Sync uygulaması için şarkı bulur, indirir ve radyoda çalan şarkıyı tanır. Bu sayfada yapman gereken bir şey yok; yalnızca uygulamayı bağlamak için aşağıdaki adresi kullan.</p>
<div class="card"><h2>Uygulamayı bağla</h2>
<div class="addr" id="addr"></div>
<ol><li>Telefonda Gurfy Sync'i aç, <b>Ayarlar &gt; Bağlantılar</b>'a git.</li>
<li><b>Gurfy servisi &gt; Adres</b> kutusuna yukarıdaki adresi yaz.</li>
<li><b>Servis parolası</b> kutusuna kurulumda YAML'da <code>API_KEY</code> olarak belirlediğin parolayı yaz.</li>
<li><b>Kaydet ve bağlantıları sına</b>'ya dokun.</li></ol></div>
<div class="card"><h2>Durum</h2><div id="status"><div class="row"><div class="dot"></div><div><b>Kontrol ediliyor…</b></div></div></div></div>
<footer>Geliştiriciler için: <a href="/docs">API belgeleri</a></footer>
</main><script>
document.getElementById('addr').textContent = location.origin;
const items = h => [
 ["Navidrome", h.navidrome, h.navidrome ? "Bağlı; indirilen şarkılar kütüphaneye eklenir" : (h.navidrome_error || "Bağlanılamıyor")],
 ["Müzik klasörü", h.music_dir_writable, h.music_dir_writable ? "Yazılabilir" : "Servis müzik klasörüne yazamıyor"],
 ["Last.fm", h.lastfm, h.lastfm ? "Benzer şarkılar, türler ve öneriler açık" : "YAML'da LASTFM_API_KEY yok"],
 ["YouTube indirme", h.yt_dlp ? true : false, h.yt_dlp ? "yt-dlp " + h.yt_dlp : "yt-dlp kurulu değil"],
 ["YouTube bot koruması", h.po_token, h.po_token ? "PO token sağlayıcısı çalışıyor" : "bgutil-provider konteynerine ulaşılamıyor"],
 ["YouTube IPv6", h.youtube_ipv6, h.youtube_ipv6 ? "IPv6 ile çıkılıyor" : "Yalnızca IPv4; bot engeli daha sık olabilir"],
 ["Radyoda şarkı tanıma", h.shazam, h.shazam ? "Hazır" : "shazamio kurulu değil"],
];
fetch('/health').then(r => r.json()).then(h => {
 document.getElementById('status').innerHTML = items(h).map(([t, ok, d]) =>
  `<div class="row"><div class="dot ${ok ? 'ok' : 'bad'}"></div><div><b>${t}</b><span></span></div></div>`).join('');
 document.querySelectorAll('#status .row span').forEach((s, i) => s.textContent = items(h)[i][2]);
}).catch(() => { document.getElementById('status').innerHTML = '<div class="row"><div class="dot bad"></div><div><b>Durum alınamadı</b></div></div>'; });
</script></body></html>"""


@app.get("/", response_class=HTMLResponse, include_in_schema=False)
def status_page():
    return STATUS_PAGE


@app.get("/similar", dependencies=[Depends(auth)])
def similar(artist: str, title: str = "", limit: int = 30):
    tracks = similar_tracks(artist, title, max(1, min(limit, 60)))
    for t in tracks:
        t["in_library"] = in_library(t["artist"], t["title"])
    return {"tracks": tracks}


@app.get("/search", dependencies=[Depends(auth)])
def search(q: str, limit: int = 25):
    data = lastfm("track.search", track=q, limit=max(1, min(limit, 50)))
    found = as_list(data.get("results", {}).get("trackmatches", {}).get("track"))
    tracks = [{"artist": t.get("artist", ""), "title": t.get("name", "")} for t in found if t.get("name")]
    for t in tracks:
        t["in_library"] = in_library(t["artist"], t["title"])
    return {"tracks": tracks}


@app.post("/download", dependencies=[Depends(auth)])
def download(track: Track):
    if not track.artist.strip() or not track.title.strip():
        raise HTTPException(400, "Sanatçı ve şarkı adı gerekli")
    return public(new_job(track.artist, track.title, track.album, track.url))


@app.post("/download/batch", dependencies=[Depends(auth)])
def download_batch(batch: Batch):
    created = [new_job(t.artist, t.title, t.album, t.url) for t in batch.tracks
               if t.artist.strip() and t.title.strip()]
    return {"jobs": [public(j) for j in created]}


class JobIds(BaseModel):
    ids: List[str] = []


@app.post("/jobs/delete", dependencies=[Depends(auth)])
def delete_jobs(req: JobIds):
    """İndirme listesinden kayıt siler (sürmekte olan indirme silinmez). ids boşsa bitmiş
    (indirilen, zaten olan, hatalı) kayıtların hepsi silinir."""
    removed = 0
    with jobs_lock:
        for jid, job in list(jobs.items()):
            if job["status"] == "downloading":
                continue
            if (req.ids and jid in req.ids) or (not req.ids and job["status"] in ("done", "exists", "error")):
                jobs.pop(jid, None)  # sıradaysa işçi atlar
                removed += 1
    save_jobs()
    return {"removed": removed}


@app.get("/jobs", dependencies=[Depends(auth)])
def list_jobs():
    with jobs_lock:
        items = sorted(jobs.values(), key=lambda j: j["created"], reverse=True)
    return {"jobs": [public(j) for j in items[:100]]}


@app.post("/jobs/retry", dependencies=[Depends(auth)])
def retry_jobs():
    """Hatayla biten indirmeleri yeniden sıraya alır."""
    n = 0
    for job in list(jobs.values()):
        if job["status"] == "error":
            job["status"], job["message"], job["retries"] = "queued", "", 0
            job_queue.put(job["id"])
            n += 1
    save_jobs()
    return {"requeued": n}


@app.post("/spotify/playlist", dependencies=[Depends(auth)])
def spotify(req: SpotifyReq):
    data = spotify_playlist(req.url)
    for t in data["tracks"]:
        t["in_library"] = in_library(t["artist"], t["title"])
    return data


# -------------------------------------------------------------------- önizleme

PREVIEW_DIR = Path(os.getenv("PREVIEW_DIR", "/tmp/nota-onizleme"))
PREVIEW_KEEP = 40
_preview_locks = {}
_preview_locks_guard = threading.Lock()


def preview_file(artist: str, title: str, url: Optional[str]) -> Path:
    """Önizleme sesini yt-dlp ile geçici klasöre indirir (YouTube'un ham adresleri dışarıdan
    istenince 403 veriyor). Son 40 önizleme saklanır; aynı şarkı tekrar açılınca hemen gelir."""
    key = hashlib.md5((url or f"{norm(artist)}|{norm(title)}").encode()).hexdigest()
    PREVIEW_DIR.mkdir(parents=True, exist_ok=True)
    with _preview_locks_guard:
        lock = _preview_locks.setdefault(key, threading.Lock())
    with lock:  # oynatıcı aynı anda birkaç istek atabilir: tek indirme yeter
        hit = next((f for f in PREVIEW_DIR.glob(key + ".*") if f.suffix not in (".part", ".ytdl")), None)
        if hit:
            hit.touch()
            return hit
        import yt_dlp

        opts = ytdlp_opts(**{
            "format": "bestaudio[ext=m4a]/bestaudio/best", "quiet": True, "no_warnings": True,
            "noprogress": True, "noplaylist": True, "playlist_items": "1",
            "outtmpl": str(PREVIEW_DIR / (key + ".%(ext)s")),
            "match_filter": yt_dlp.utils.match_filter_func(f"duration < {MAX_DURATION}"),
        })
        ytdlp_download(opts, url or f"ytsearch1:{artist} - {title} audio")
        hit = next((f for f in PREVIEW_DIR.glob(key + ".*") if f.suffix not in (".part", ".ytdl")), None)
        if not hit:
            raise HTTPException(404, "Önizleme bulunamadı")
    # eskileri temizle
    files = sorted((f for f in PREVIEW_DIR.iterdir() if f.is_file()), key=lambda f: f.stat().st_mtime, reverse=True)
    for old in files[PREVIEW_KEEP:]:
        old.unlink(missing_ok=True)
    return hit


@app.get("/preview")
def preview(artist: str = "", title: str = "", url: Optional[str] = None, key: str = ""):
    # Çalar başlık ekleyemediği için anahtar adres içinde gelir
    if API_KEY and not secrets.compare_digest(key, API_KEY):
        raise HTTPException(401, "API anahtarı hatalı")
    if not url and not (artist.strip() and title.strip()):
        raise HTTPException(400, "Sanatçı ve şarkı adı gerekli")
    try:
        f = preview_file(artist, title, url)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(502, f"Önizleme açılamadı: {tr_error(e)}")
    media = {".m4a": "audio/mp4", ".webm": "audio/webm", ".opus": "audio/ogg", ".mp3": "audio/mpeg"}
    return FileResponse(str(f), media_type=media.get(f.suffix, "application/octet-stream"))


@app.post("/youtube/playlist", dependencies=[Depends(auth)])
def youtube(req: SpotifyReq):
    data = youtube_playlist(req.url)
    for t in data["tracks"]:
        t["in_library"] = in_library(t["artist"], t["title"])
    return data


# ---------------------------------------------------------------------- radyo
#
# Türkiye radyoları radio-browser.info'nun açık veritabanından çekilir (güncel akış adresleri) ve
# RADIO_CACHE dosyasında saklanır; günde bir yenilenir. Bir adres yanlışsa ya da listede olmayan bir
# radyo eklemek istenirse RADIO_CUSTOM dosyası elle düzenlenir:
#   [{"name": "Radyo Adı", "url": "https://akış-adresi", "homepage": "...", "favicon": "..."},
#    {"name": "İstenmeyen Radyo", "hidden": true}]
# Aynı isimli kayıt otomatik listedekinin yerine geçer. Navidrome'daki aynı isimli radyoların adresi
# de buna göre güncellenir.

RADIO_CACHE = Path(os.getenv("RADIO_CACHE", "/app/radyolar.json"))
RADIO_CACHE_WORLD = Path(os.getenv("RADIO_CACHE_WORLD", "/app/radyolar-dunya.json"))
RADIO_WORLD_COUNT = 50
RADIO_CUSTOM = Path(os.getenv("RADIO_CUSTOM", "/app/radyolar-ozel.json"))
RADIO_MAX_AGE = 24 * 3600
RADIO_HOSTS = ["de1", "fi1", "nl1", "at1", "de2"]
radio_lock = threading.Lock()


def fetch_radio_browser(scope: str = "tr") -> List[dict]:
    if scope == "world":  # dünyada en çok dinlenenler (tıklanma sayısına göre)
        path = "/json/stations/topclick/150?hidebroken=true"
    else:
        path = "/json/stations/bycountrycodeexact/TR?order=clickcount&reverse=true&hidebroken=true&limit=200"
    last = None
    for host in RADIO_HOSTS:
        try:
            r = requests.get(f"https://{host}.api.radio-browser.info{path}",
                             headers={"User-Agent": "Gurfy/1.0"}, timeout=20)
            r.raise_for_status()
            stations, seen = [], set()
            for s in r.json():
                name = re.sub(r"\s+", " ", (s.get("name") or "")).strip()
                url = (s.get("url_resolved") or s.get("url") or "").strip()
                if not name or not url or norm(name) in seen:
                    continue
                seen.add(norm(name))
                stations.append({
                    "name": name, "url": url,
                    "homepage": s.get("homepage") or "", "favicon": s.get("favicon") or "",
                    "tags": s.get("tags") or "", "codec": s.get("codec") or "",
                    "bitrate": s.get("bitrate") or 0, "source": "radio-browser",
                    "country": s.get("countrycode") or "",
                })
            if scope == "world":  # Türk radyoları kendi listesinde; dünya listesine karışmasın
                stations = [x for x in stations if x["country"].upper() != "TR"][:RADIO_WORLD_COUNT]
            return stations
        except Exception as e:
            last = e
    raise RuntimeError(f"Radyo listesi alınamadı: {last}")


def read_json(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return default


def merge_custom(stations: List[dict], add_own: bool = True) -> List[dict]:
    custom = read_json(RADIO_CUSTOM, [])
    if not isinstance(custom, list):
        return stations
    by_name = {norm(s["name"]): s for s in stations}
    hidden, own = set(), []
    for c in custom:
        if not isinstance(c, dict) or not c.get("name"):
            continue
        key = norm(c["name"])
        if c.get("hidden"):
            hidden.add(key)
            continue
        if not c.get("url"):
            continue
        if key in by_name or add_own:
            own.append({**by_name.get(key, {}), **c, "source": "özel"})
            hidden.add(key)  # otomatik listedeki eşini çıkar, özel kayıt başta durur
    return own + [s for s in stations if norm(s["name"]) not in hidden]


def sync_navidrome_radios(stations: List[dict]) -> int:
    """Navidrome'daki aynı isimli radyoların adresi değiştiyse günceller (Navidrome yönetici yetkisi gerekir)."""
    try:
        body = navidrome("getInternetRadioStations")
    except Exception:
        return 0
    if not body:
        return 0
    wanted = {norm(s["name"]): s for s in stations}
    updated = 0
    for st in as_list(body.get("internetRadioStations", {}).get("internetRadioStation")):
        w = wanted.get(norm(st.get("name", "")))
        if w and w["url"] != st.get("streamUrl"):
            try:
                navidrome("updateInternetRadioStation", id=st["id"], streamUrl=w["url"], name=st["name"],
                          homepageUrl=w.get("homepage") or st.get("homepageUrl") or "")
                updated += 1
            except Exception:
                pass
    return updated


def load_radios(refresh: bool = False, scope: str = "tr") -> dict:
    world = scope == "world"
    path = RADIO_CACHE_WORLD if world else RADIO_CACHE
    with radio_lock:
        cache = read_json(path, {})
        stale = time.time() - cache.get("updated_at", 0) > RADIO_MAX_AGE
        error = None
        if refresh or stale or not cache.get("stations"):
            try:
                cache = {"updated_at": time.time(), "stations": fetch_radio_browser(scope)}
                path.write_text(json.dumps(cache, ensure_ascii=False), encoding="utf-8")
            except Exception as e:
                error = str(e)[:200]  # eski kayıtla devam
        stations = merge_custom(cache.get("stations", []), add_own=not world)
        if world:
            stations = [x for x in stations if (x.get("country") or "").upper() != "TR"]
        return {"updated_at": cache.get("updated_at", 0), "stations": stations, "error": error}


def radio_refresher():
    while True:
        try:
            data = load_radios()
            world = load_radios(scope="world")
            sync_navidrome_radios(data["stations"] + world["stations"])
        except Exception:
            pass
        time.sleep(6 * 3600)


threading.Thread(target=radio_refresher, daemon=True).start()


@app.get("/radios", dependencies=[Depends(auth)])
def radios(refresh: bool = False, scope: str = "tr"):
    data = load_radios(refresh, "world" if scope == "world" else "tr")
    if refresh:
        data["navidrome_updated"] = sync_navidrome_radios(data["stations"])
    return data


# ------------------------------------------------------------- listeler (Last.fm)

chart_cache = {}


@app.get("/charts", dependencies=[Depends(auth)])
def charts(scope: str = "tr", limit: int = 50):
    """Türkiye'de / dünyada en çok dinlenen şarkılar (Last.fm). 1 saat önbellekte tutulur."""
    scope = "world" if scope == "world" else "tr"
    limit = max(1, min(limit, 100))
    hit = chart_cache.get((scope, limit))
    if hit and time.time() - hit[0] < 3600:
        tracks = hit[1]
    else:
        if scope == "world":
            data = lastfm("chart.getTopTracks", limit=limit)
        else:
            data = lastfm("geo.getTopTracks", country="Turkey", limit=limit)
        tracks = [{"artist": (t.get("artist") or {}).get("name", ""), "title": t.get("name", ""),
                   "listeners": int(t.get("listeners") or 0)}
                  for t in as_list(data.get("tracks", {}).get("track")) if t.get("name")]
        chart_cache[(scope, limit)] = (time.time(), tracks)
    out = [dict(t) for t in tracks]
    for t in out:
        t["in_library"] = in_library(t["artist"], t["title"])
    return {"scope": scope, "tracks": out}


# ------------------------------------------------------- Last.fm dinleme geçmişi
#
# Navidrome dinlenen şarkıları Last.fm hesabına yazıyor (scrobble). Kullanıcı adıyla bu geçmiş okunur:
# istatistikler, geçmişe göre öneriler ve "unuttukların". Okumak için yetki gerekmez.

from concurrent.futures import ThreadPoolExecutor
from itertools import zip_longest

LASTFM_PERIODS = {"7day", "1month", "3month", "6month", "12month", "overall"}
_lfm_cache = {}  # anahtar -> (zaman, veri)


def cached(key, ttl: int, fn):
    hit = _lfm_cache.get(key)
    if hit and time.time() - hit[0] < ttl:
        return hit[1]
    data = fn()
    _lfm_cache[key] = (time.time(), data)
    if len(_lfm_cache) > 300:
        for k in sorted(_lfm_cache, key=lambda k: _lfm_cache[k][0])[:100]:
            _lfm_cache.pop(k, None)
    return data


def lfm_image(obj) -> Optional[str]:
    imgs = [i.get("#text") for i in as_list((obj or {}).get("image")) if i.get("#text")]
    url = imgs[-1] if imgs else None
    # Last.fm sanatçı görsellerini kaldırdı; hepsi aynı yıldız resmi
    return None if url and "2a96cbd8b46e442fc41c2b86b821562f" in url else url


def song_brief(s: Optional[dict]) -> Optional[dict]:
    if not s:
        return None
    return {k: s.get(k) for k in ("id", "title", "artist", "album", "albumId", "coverArt", "duration", "genre")}


def with_library(tracks: List[dict]) -> List[dict]:
    """Her şarkıya kütüphanedeki karşılığını ekler (paralel arama)."""
    with ThreadPoolExecutor(6) as ex:
        found = list(ex.map(lambda t: library_song(t["artist"], t["title"]), tracks))
    for t, f in zip(tracks, found):
        t["in_library"] = f is not None
        t["song"] = song_brief(f)
    return tracks


def lfm_user(user: str) -> dict:
    data = lastfm("user.getInfo", user=user)
    u = data.get("user")
    if not u:
        raise HTTPException(404, "Last.fm kullanıcısı bulunamadı")
    return u


def top_tracks(user: str, period: str, limit: int) -> List[dict]:
    data = lastfm("user.getTopTracks", user=user, period=period, limit=limit)
    return [{"artist": (t.get("artist") or {}).get("name", ""), "title": t.get("name", ""),
             "plays": int(t.get("playcount") or 0)}
            for t in as_list(data.get("toptracks", {}).get("track")) if t.get("name")]


def top_artists(user: str, period: str, limit: int) -> List[dict]:
    data = lastfm("user.getTopArtists", user=user, period=period, limit=limit)
    return [{"name": a.get("name", ""), "plays": int(a.get("playcount") or 0), "image": lfm_image(a)}
            for a in as_list(data.get("topartists", {}).get("artist")) if a.get("name")]


def top_albums(user: str, period: str, limit: int) -> List[dict]:
    data = lastfm("user.getTopAlbums", user=user, period=period, limit=limit)
    return [{"name": a.get("name", ""), "artist": (a.get("artist") or {}).get("name", ""),
             "plays": int(a.get("playcount") or 0), "image": lfm_image(a)}
            for a in as_list(data.get("topalbums", {}).get("album")) if a.get("name")]


@app.get("/lastfm/user", dependencies=[Depends(auth)])
def lastfm_user_info(user: str):
    u = lfm_user(user)
    return {"name": u.get("name"), "realname": u.get("realname") or "",
            "playcount": int(u.get("playcount") or 0), "image": lfm_image(u),
            "registered": int((u.get("registered") or {}).get("unixtime") or 0),
            "url": u.get("url")}


@app.get("/lastfm/stats", dependencies=[Depends(auth)])
def lastfm_stats(user: str, period: str = "1month"):
    """En çok dinlenen sanatçılar, şarkılar, albümler (dönem: 7day, 1month, 12month, overall)."""
    if period not in LASTFM_PERIODS:
        period = "1month"

    def build():
        with ThreadPoolExecutor(4) as ex:
            fu = ex.submit(lfm_user, user)
            fa = ex.submit(top_artists, user, period, 10)
            ft = ex.submit(top_tracks, user, period, 25)
            fb = ex.submit(top_albums, user, period, 10)
            u, artists, tracks, albums = fu.result(), fa.result(), ft.result(), fb.result()
        return {"user": user, "period": period, "total": int(u.get("playcount") or 0),
                "artists": artists, "tracks": with_library(tracks), "albums": albums}

    return cached(("stats", norm(user), period), 600, build)


@app.get("/lastfm/recommend", dependencies=[Depends(auth)])
def lastfm_recommend(user: str = "", limit: int = 40, refresh: bool = False, lb_user: str = ""):
    """Son 1 ay ve 1 yılda en çok dinlenen sanatçılara benzeyen, az dinlenmiş sanatçıların sevilen
    şarkılarından kütüphanede olmayanlar."""
    limit = max(5, min(limit, 80))

    def build():
        recent = top_artists(user, "1month", 10)
        year = top_artists(user, "12month", 15)
        known = {norm(a["name"]) for a in top_artists(user, "overall", 150)}
        # Ağırlık: son ay daha önemli
        seeds = {}
        for i, a in enumerate(recent):
            seeds[a["name"]] = seeds.get(a["name"], 0) + 2.0 - i * 0.1
        for i, a in enumerate(year):
            seeds[a["name"]] = seeds.get(a["name"], 0) + 1.0 - i * 0.05
        seed_list = sorted(seeds, key=seeds.get, reverse=True)[:15]

        def similar(name):
            return similar_artists(name, 12)

        with ThreadPoolExecutor(5) as ex:
            sims = list(ex.map(similar, seed_list))
        score, because = {}, {}
        for seed, lst in zip(seed_list, sims):
            for name, match in lst:
                k = norm(name)
                if not name or k in known:
                    continue
                score[name] = score.get(name, 0) + match * seeds[seed]
                because.setdefault(name, seed)
        picks = sorted(score, key=score.get, reverse=True)[:max(8, limit // 3)]

        def tracks_of(name):
            data = lastfm("artist.getTopTracks", artist=name, limit=5)
            return [{"artist": (t.get("artist") or {}).get("name", name), "title": t.get("name", ""),
                     "because": because.get(name, "")}
                    for t in as_list(data.get("toptracks", {}).get("track")) if t.get("name")]

        with ThreadPoolExecutor(5) as ex:
            per_artist = list(ex.map(tracks_of, picks))
        # Her sanatçıdan sırayla birer şarkı (çeşitlilik)
        out, i = [], 0
        while len(out) < limit * 2 and any(i < len(t) for t in per_artist):
            for lst in per_artist:
                if i < len(lst):
                    out.append(lst[i])
            i += 1
        out = with_library(out)
        return [t for t in out if not t["in_library"]][:limit]

    key = ("recommend", norm(user), limit)
    if refresh:
        _lfm_cache.pop(key, None)
        _lfm_cache.pop(("lb-explore", norm(lb_user)), None)
    tracks = cached(key, 6 * 3600, build) if user else []
    if lb_user:
        try:
            lb = [t for t in with_library(lb_exploration(lb_user)[:limit]) if not t["in_library"]]
        except HTTPException:
            lb = []
        # İki kaynağı sırayla karıştır, aynı şarkıyı bir kez göster
        seen, mixed = set(), []
        for pair in zip_longest(tracks, lb):
            for t in pair:
                k = (norm(t["artist"]), norm(t["title"])) if t else None
                if t and k not in seen:
                    seen.add(k)
                    mixed.append(t)
        tracks = mixed[:limit]
    return {"tracks": tracks}


@app.get("/listenbrainz/user", dependencies=[Depends(auth)])
def listenbrainz_user(user: str):
    data = lb_get(f"user/{quote(user)}/listen-count")
    return {"name": user, "listens": int((data.get("payload") or {}).get("count") or 0)}


@app.get("/lastfm/top-library", dependencies=[Depends(auth)])
def lastfm_top_library(user: str, period: str = "1month", limit: int = 50):
    """Dönemin en çok dinlenen şarkılarından kütüphanede olanlar (sana özel karışık için)."""
    if period not in LASTFM_PERIODS:
        period = "1month"
    limit = max(5, min(limit, 100))

    def build():
        found = with_library(top_tracks(user, period, limit))
        return [t for t in found if t["in_library"]]

    return {"tracks": cached(("top-library", norm(user), period, limit), 3600, build)}


@app.get("/lastfm/forgotten", dependencies=[Depends(auth)])
def lastfm_forgotten(user: str, limit: int = 40):
    """Bir zamanlar çok dinlenip son 3 aydır hiç dinlenmeyen şarkılar (kütüphanede olanlar)."""
    limit = max(5, min(limit, 100))

    def build():
        with ThreadPoolExecutor(2) as ex:
            fo = ex.submit(top_tracks, user, "overall", 300)
            fr = ex.submit(top_tracks, user, "3month", 500)
            overall, recent = fo.result(), fr.result()
        heard = {(norm(t["artist"]), norm(t["title"])) for t in recent}
        cands = [t for t in overall if t["plays"] >= 3 and (norm(t["artist"]), norm(t["title"])) not in heard]
        found = with_library(cands[:limit * 2])
        return [t for t in found if t["in_library"]][:limit]

    return {"tracks": cached(("forgotten", norm(user), limit), 6 * 3600, build)}


# ----------------------------------------------------------- sunucudan şarkı silme
#
# Subsonic API'de silme yok. Şarkının dosya yolu Navidrome'un kendi API'sinden alınır, müzik
# klasöründe bulunup silinir ve Navidrome taraması başlatılır.

class DeleteReq(BaseModel):
    id: str


def navidrome_native(path: str):
    if not (NAVIDROME_URL and NAVIDROME_USER):
        raise HTTPException(500, "Serviste Navidrome bilgileri eksik")
    r = requests.post(f"{NAVIDROME_URL}/auth/login", json={"username": NAVIDROME_USER, "password": NAVIDROME_PASS},
                      timeout=15)
    if r.status_code != 200:
        raise HTTPException(502, "Navidrome girişi başarısız")
    token = r.json().get("token")
    r = requests.get(f"{NAVIDROME_URL}{path}", headers={"x-nd-authorization": f"Bearer {token}"}, timeout=15)
    if r.status_code == 404:
        raise HTTPException(404, "Şarkı Navidrome'da bulunamadı")
    r.raise_for_status()
    return r.json()


def music_file_for(nd_path: str) -> Optional[Path]:
    """Navidrome'un verdiği yolu bu servisteki müzik klasörüne eşler (klasörler farklı bağlanmış olabilir)."""
    root = MUSIC_DIR.resolve()
    parts = [x for x in Path(nd_path.replace("\\", "/")).parts if x not in ("/", "")]
    for i in range(len(parts)):
        cand = MUSIC_DIR.joinpath(*parts[i:])
        if cand.is_file():
            real = cand.resolve()
            if root in real.parents:
                return real
    return None


def song_file(song_id: str) -> Path:
    """Navidrome şarkı kimliğinden müzik klasöründeki dosyayı bulur."""
    song = navidrome_native(f"/api/song/{quote(song_id)}")
    nd_path = song.get("path") or ""
    lib_path = song.get("libraryPath") or ""
    f = music_file_for(nd_path) or (music_file_for(lib_path.rstrip("/") + "/" + nd_path) if lib_path else None)
    if not f:
        raise HTTPException(404, f"Dosya müzik klasöründe bulunamadı: {nd_path}")
    return f


# ------------------------------------------------------- uygulama ayarlarının yedeği
# Telefon ayarlarını buraya yedekler; uygulama silinip yeniden kurulunca servis parolası
# girilince ayarlar geri gelir. Dosya yalnızca bu servisin klasöründe durur.

SETTINGS_FILE = Path(os.getenv("SETTINGS_FILE", "/app/uygulama-ayarlari.json"))


class SettingsReq(BaseModel):
    data: dict


@app.get("/settings", dependencies=[Depends(auth)])
def get_settings():
    try:
        return json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"data": None}


@app.post("/settings", dependencies=[Depends(auth)])
def save_settings(req: SettingsReq):
    body = {"data": req.data, "saved": time.time()}
    tmp = SETTINGS_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
    tmp.replace(SETTINGS_FILE)
    return {"saved": body["saved"]}


class GenreReq(BaseModel):
    id: str
    genres: List[str] = []


@app.post("/song/genre", dependencies=[Depends(auth)])
def set_song_genre(req: GenreReq):
    """Şarkının tür etiketini elle değiştirir (boş liste türü siler), sonra Navidrome'u taratır."""
    from mutagen import File as AudioFile
    genres = []
    for g in req.genres:
        g = " ".join(g.split())[:60]
        if g and g.lower() not in [x.lower() for x in genres]:
            genres.append(g)
    f = song_file(req.id)
    try:
        audio = AudioFile(str(f), easy=True)
        if audio is None:
            raise HTTPException(415, "Bu dosya türüne etiket yazılamıyor")
        if audio.tags is None:
            audio.add_tags()
        if genres:
            audio["genre"] = genres
        elif "genre" in audio:
            del audio["genre"]
        audio.save()
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(500, f"Tür yazılamadı: {tr_error(e)}")
    start_scan()
    return {"genres": genres, "file": str(f.relative_to(MUSIC_DIR.resolve()))}


@app.post("/song/delete", dependencies=[Depends(auth)])
def delete_song(req: DeleteReq):
    f = song_file(req.id)
    rel = str(f.relative_to(MUSIC_DIR.resolve()))
    try:
        f.unlink()
    except OSError as e:
        raise HTTPException(500, f"Dosya silinemedi: {tr_error(e)}")
    # boş kalan albüm / sanatçı klasörlerini temizle
    folder = f.parent
    root = MUSIC_DIR.resolve()
    while folder != root and root in folder.parents:
        try:
            folder.rmdir()  # yalnızca boşsa siler
        except OSError:
            break
        folder = folder.parent
    start_scan()
    return {"deleted": rel}


# ------------------------------------------------- radyoda çalan şarkıyı tanıma
#
# Önce yayının kendi bilgisine (ICY "StreamTitle") bakılır; çoğu radyo "Sanatçı - Şarkı" gönderir.
# Orada anlamlı bir şarkı adı yoksa ffmpeg ile 10 saniyelik parça kaydedilip shazamio ile tanınır.

class RecognizeReq(BaseModel):
    url: str
    name: str = ""
    force_audio: bool = False  # yayın bilgisini atla, doğrudan sesten tanı
    stream_title: Optional[str] = None  # telefondaki çaların yayından okuduğu başlık (varsa)


# Eski Shoutcast sunucuları tarayıcı kimliği görünce ses yerine HTML sayfası döndürüyor:
# radyolara her zaman bir medya oynatıcı kimliğiyle bağlanılır.
RADIO_UA = "VLC/3.0.20 LibVLC/3.0.20"


ICY_JUNK = re.compile(
    r"(reklam|advert|jingle|kesintisiz|unknown|bilinmiyor|untitled|www\.|https?:|\.com|\.net|"
    r"\bfm\b|radyo|radio|haber|news|canl[ıi] yay[ıi]n|on air|live)", re.I)


def read_exact(raw, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = raw.read(n - len(buf))
        if not chunk:
            break
        buf += chunk
    return buf


def icy_title(url: str) -> Optional[str]:
    """Yayının o anki başlığı (ICY metadata). Desteklemeyen yayınlarda None."""
    if ".m3u8" in url.lower():
        return None
    try:
        r = requests.get(url, headers={"User-Agent": RADIO_UA, "Icy-MetaData": "1"}, stream=True, timeout=8)
    except Exception:
        return None
    try:
        metaint = int(r.headers.get("icy-metaint") or 0)
        if not metaint:
            return None
        for _ in range(3):  # ilk bloklar bazen boş gelir
            if len(read_exact(r.raw, metaint)) < metaint:
                return None
            size = read_exact(r.raw, 1)
            if not size:
                return None
            n = size[0] * 16
            if not n:
                continue
            data = read_exact(r.raw, n).rstrip(b"\0")
            try:
                text = data.decode("utf-8")
            except UnicodeDecodeError:
                text = data.decode("cp1254", "replace")
            m = re.search(r"StreamTitle='(.*?)';", text, re.S)
            return m.group(1).strip() if m else None
        return None
    except Exception:
        return None
    finally:
        r.close()


def split_stream_title(raw: Optional[str], station: str) -> Optional[tuple]:
    """'Sanatçı - Şarkı' biçimindeyse (sanatçı, şarkı) döner; reklam, radyo adı vb. ise None."""
    if not raw:
        return None
    parts = re.split(r"\s+[-–—]\s+", raw.strip(), maxsplit=1)
    if len(parts) != 2:
        return None
    artist, title = (p.strip(" '\"") for p in parts)
    if len(artist) < 2 or len(title) < 2 or ICY_JUNK.search(raw):
        return None
    if station and norm(station) in (norm(artist), norm(title), norm(raw)):
        return None
    if sum(c.isdigit() for c in raw) > len(raw) / 2:
        return None
    return artist, title


def record_clip(url: str, seconds: int = 10) -> Path:
    import subprocess
    import tempfile

    out = Path(tempfile.gettempdir()) / f"nota-tani-{uuid.uuid4().hex}.wav"
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-user_agent", RADIO_UA,
           "-i", url, "-t", str(seconds), "-vn", "-ac", "1", "-ar", "44100", str(out)]
    try:
        subprocess.run(cmd, capture_output=True, timeout=seconds + 25, check=True)
    except subprocess.TimeoutExpired:
        out.unlink(missing_ok=True)
        raise HTTPException(504, "Yayından ses alınamadı (zaman aşımı)")
    except subprocess.CalledProcessError as e:
        out.unlink(missing_ok=True)
        raise HTTPException(502, "Yayından ses alınamadı: " + tr_error((e.stderr or b"").decode("utf-8", "replace")[-300:]))
    if not out.exists() or out.stat().st_size < 50_000:
        out.unlink(missing_ok=True)
        raise HTTPException(502, "Yayından yeterli ses alınamadı")
    return out


def shazam(path: Path) -> Optional[dict]:
    import asyncio
    from shazamio import Shazam

    async def run():
        async with Shazam(language="tr", endpoint_country="TR") as sz:
            return await sz.recognize(str(path))

    res = asyncio.run(run())
    t = (res or {}).get("track")
    if not t or not t.get("title"):
        return None
    album = None
    for sec in t.get("sections") or []:
        for md in sec.get("metadata") or []:
            if (md.get("title") or "").lower() in ("album", "albüm"):
                album = md.get("text")
    return {"artist": t.get("subtitle") or "", "title": t["title"], "album": album,
            "cover": (t.get("images") or {}).get("coverart")}


@app.post("/recognize", dependencies=[Depends(auth)])
def recognize(req: RecognizeReq):
    """Radyoda o an çalan şarkıyı bulur."""
    raw = None
    if not req.force_audio:
        # Telefon başlığı zaten okuduysa yayına tekrar bağlanmaya gerek yok
        hit = split_stream_title(req.stream_title, req.name)
        if hit:
            artist, title = hit
            return {"found": True, "source": "metadata", "artist": artist, "title": title,
                    "album": None, "cover": None, "stream_title": req.stream_title,
                    "in_library": in_library(artist, title)}
        raw = icy_title(req.url)
        hit = split_stream_title(raw, req.name)
        if hit:
            artist, title = hit
            return {"found": True, "source": "metadata", "artist": artist, "title": title,
                    "album": None, "cover": None, "stream_title": raw,
                    "in_library": in_library(artist, title)}
    try:
        import shazamio  # noqa: F401
    except ImportError:
        raise HTTPException(500, "Sunucuda shazamio kurulu değil (pip install shazamio)")
    clip = record_clip(req.url)
    try:
        hit = shazam(clip)
    except Exception as e:
        raise HTTPException(502, f"Tanıma servisine ulaşılamadı: {tr_error(e)}")
    finally:
        clip.unlink(missing_ok=True)
    if not hit:
        return {"found": False, "source": "shazam", "stream_title": raw,
                "message": "Şarkı tanınamadı. Konuşma ya da reklam olabilir; biraz sonra tekrar deneyin."}
    return {"found": True, "source": "shazam", **hit, "stream_title": raw,
            "in_library": in_library(hit["artist"], hit["title"])}


# ----------------------------------------------------------------------- türler
#
# Navidrome türü dosyadaki etiketten okur. Tür Last.fm etiketlerinden çıkarılır (önce şarkının,
# yoksa sanatçının etiketleri), Türkçe ve tutarlı isimlere çevrilir.

GENRE_RULES = [
    (r"arabesk|arabesque", "Arabesk"),
    (r"fantezi", "Fantezi"),
    (r"t[üu]rk[üu]\b|halk m|turkish folk|anatolian folk|bozlak|zeybek", "Türk Halk Müziği"),
    (r"sanat m|turkish classical|ottoman|\btsm\b", "Türk Sanat Müziği"),
    (r"anatolian rock|anadolu rock", "Anadolu Rock"),
    (r"\brap\b|hip.?hop|\btrap\b|drill", "Rap"),
    (r"metal", "Metal"),
    (r"punk", "Punk"),
    (r"rock|grunge", "Rock"),
    (r"electro|\bedm\b|techno|house|trance|dubstep|synth|drum and bass|dnb", "Elektronik"),
    (r"dance|disco", "Dans"),
    (r"jazz|swing", "Caz"),
    (r"classical|orchestral|baroque|opera", "Klasik"),
    (r"r&b|\brnb\b|soul|funk", "R&B / Soul"),
    (r"latin|reggaeton|salsa|bachata", "Latin"),
    (r"reggae|ska\b", "Reggae"),
    (r"soundtrack|\bost\b|film|score", "Film Müziği"),
    (r"blues", "Blues"),
    (r"country", "Country"),
    (r"indie", "Indie"),
    (r"alternative", "Alternatif"),
    (r"chill|lounge|ambient|lo.?fi|downtempo", "Chill"),
    (r"folk|singer.?songwriter", "Folk"),
    (r"acoustic", "Akustik"),
    (r"pop", "Pop"),
]
GENRE_RULES = [(re.compile(rx, re.I), g) for rx, g in GENRE_RULES]
TURKISH_TAG = re.compile(r"turk|türk|turkce|türkçe|turkey|türkiye|anatolia", re.I)
TURKISH_PREFIX = {"Pop": "Türkçe Pop", "Rock": "Türkçe Rock", "Rap": "Türkçe Rap", "Folk": "Türk Halk Müziği"}
_genre_artist_cache = {}
_lastfm_gate = threading.Lock()
_lastfm_last = [0.0]


def lastfm_slow(method: str, **params):
    """Toplu işlerde Last.fm'i yormamak için saniyede en fazla ~4 istek."""
    with _lastfm_gate:
        wait = 0.25 - (time.time() - _lastfm_last[0])
        if wait > 0:
            time.sleep(wait)
        _lastfm_last[0] = time.time()
    return lastfm(method, **params)


def genres_from_tags(tags: List[dict]) -> List[str]:
    tags = [t for t in tags if int(t.get("count") or 0) >= 10 and t.get("name")]
    turkish = any(TURKISH_TAG.search(t["name"]) for t in tags[:8])
    out = []
    for t in tags[:12]:
        name = t["name"].lower()
        for rx, genre in GENRE_RULES:
            if rx.search(name):
                if turkish:
                    genre = TURKISH_PREFIX.get(genre, genre)
                if genre not in out:
                    out.append(genre)
                break
        if len(out) >= 2:
            break
    return out


def genre_for(artist: str, title: str) -> List[str]:
    if not LASTFM_API_KEY or not artist:
        return []
    try:
        if title:
            data = lastfm_slow("track.getTopTags", artist=artist, track=title)
            found = genres_from_tags(as_list(data.get("toptags", {}).get("tag")))
            if found:
                return found
        key = norm(artist)
        if key not in _genre_artist_cache:
            data = lastfm_slow("artist.getTopTags", artist=artist)
            _genre_artist_cache[key] = genres_from_tags(as_list(data.get("toptags", {}).get("tag")))
            if len(_genre_artist_cache) > 5000:
                _genre_artist_cache.clear()
        return _genre_artist_cache[key]
    except Exception:
        return []


AUDIO_EXT = {".m4a", ".mp3", ".flac", ".ogg", ".opus", ".mp4", ".aac", ".wma"}
backfill = {"running": False}
backfill_lock = threading.Lock()


def first_tag(tags, *keys) -> str:
    for k in keys:
        v = tags.get(k) if tags else None
        if v:
            return str(v[0] if isinstance(v, list) else v).strip()
    return ""


def run_backfill() -> None:
    from mutagen import File as AudioFile

    st = backfill
    files = [f for f in MUSIC_DIR.rglob("*") if f.suffix.lower() in AUDIO_EXT and f.is_file()]
    st.update(total=len(files), scanned=0, updated=0, had_genre=0, not_found=0, errors=0,
              current="", started_at=time.time(), finished_at=None)
    for f in files:
        st["scanned"] += 1
        st["current"] = str(f.relative_to(MUSIC_DIR))
        try:
            audio = AudioFile(str(f), easy=True)
            if audio is None:
                continue
            if audio.tags is None:
                st["not_found"] += 1  # etiketsiz dosyada sanatçı da yok
                continue
            if first_tag(audio.tags, "genre"):
                st["had_genre"] += 1
                continue
            artist = first_tag(audio.tags, "artist", "albumartist")
            title = first_tag(audio.tags, "title")
            genres = genre_for(artist, title)
            if not genres:
                st["not_found"] += 1
                continue
            audio["genre"] = genres
            audio.save()
            st["updated"] += 1
        except Exception:
            st["errors"] += 1
    st["current"] = ""
    st["finished_at"] = time.time()
    if st["updated"]:
        start_scan()


@app.post("/genres/backfill", dependencies=[Depends(auth)])
def start_backfill():
    """Türü boş olan şarkılara tür yazar (türü olanlara dokunmaz), sonra Navidrome'u taratır."""
    if not LASTFM_API_KEY:
        raise HTTPException(503, "LASTFM_API_KEY ayarlanmamış")
    with backfill_lock:
        if backfill.get("running"):
            return backfill
        backfill.clear()
        backfill["running"] = True

    def job():
        try:
            run_backfill()
        except Exception as e:
            backfill["error"] = str(e)[:200]
        finally:
            backfill["running"] = False

    threading.Thread(target=job, daemon=True).start()
    return backfill


@app.get("/genres/backfill", dependencies=[Depends(auth)])
def backfill_status():
    return backfill


@app.get("/genre", dependencies=[Depends(auth)])
def genre_lookup(artist: str, title: str = ""):
    return {"genres": genre_for(artist, title)}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", "8099")))
