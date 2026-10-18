# PostScraper Crawler — Aplikasi Native Android (Kotlin)

Aplikasi native Android yang **merayap (crawl) otomatis** mulai dari 1 URL seed (mis. homepage blog), menemukan semua link postingan se-domain, masuk ke tiap post, mengambil & membersihkan teksnya, lalu menyimpannya sebagai file **`.txt`** di **`Internal Storage > Download`**.

## Alur Crawler (sesuai permintaan)
1. User paste URL awal (seed), mis. `https://jalansmut.blogspot.com`, lalu tentukan **maks. post** yang mau diambil (default 10).
2. Aplikasi buka halaman seed, kumpulkan semua link, **filter yang se-domain** (host sama, `www` diabaikan), masukkan ke antrian BFS.
3. Untuk tiap halaman:
   - Jika berupa **postingan** (bukan index/search/label) dan teks ≥ ambang minimum → ambil semua teks, bersihkan minimal, tulis ke file.
   - Link se-domain di dalam post otomatis **ditambahkan ke antrian** untuk dilanjutkan.
   - Jika tidak ada link baru, otomatis lanjut ke antrian post lain (BFS).
4. **Riwayat anti-dobel**: URL yang pernah diambil disimpan permanen ke file, sehingga run berikutnya tidak mengulang post yang sama. Bisa di-toggle ON/OFF dan dihapus.
5. Bisa **Stop** kapan saja — hasil yang sudah terkumpul tetap disimpan.

## Kriteria korpus terpenuhi
- **Teks mentah (raw)** — hanya strip tag HTML; toggle "Pembersihan minimal" ON/OFF.
- **Volume cukup** — crawl otomatis, tentukan sendiri jumlah post (10, 50, 100…).
- **Representatif** — tiap post disertai judul + URL sumber sebagai header.
- **Pembersihan minimal** — ON: buang `script/style/iframe/iklan(ins)/form/komentar`, rapikan spasi. OFF: raw total.

## Cara Build
1. Buka **Android Studio** → `Open` → folder `PostScraper`.
2. Tunggu Gradle sync (OkHttp, Jsoup, Coroutines terunduh otomatis).
3. Run ke HP Android (USB debugging) atau emulator.
4. Min SDK 24 (Android 7.0), target SDK 34. Android 10+ tidak perlu izin storage (pakai `MediaStore.Downloads`).

### Build APK otomatis (GitHub Actions)
Setiap push ke `main` menjalankan workflow **Build APK**. Unduh hasilnya di tab **Actions** → run terbaru → **Artifacts** → `PostScraper-debug-apk`.

## Cara Pakai
1. Isi **URL awal** (seed) — default sudah terisi contoh blogspot.
2. Atur **Maks. post diambil** (mis. 10) dan **Min. karakter/post** (default 400, untuk menyaring halaman index).
3. Aktifkan **Lewati post yang pernah diambil** (riwayat) — disarankan ON.
4. Tekan **Mulai Crawling**. Lihat progres di log. Tekan **Stop** kapan saja.
5. Setelah selesai, file `korpus_crawl_<domain>_<timestamp>.txt` tersimpan di **`Download/`**.

## Format file hasil
```
KORPUS TEKS — PostScraper Crawler
Dibuat     : 2026-10-18 14:30:00
Seed       : https://jalansmut.blogspot.com
Domain     : jalansmut.blogspot.com
Target     : maks. 10 post (min. 400 karakter)
Bersih     : minimal
Encoding   : UTF-8
================================================================

===== POST #1 =====
Judul : Contoh Judul Postingan
URL   : https://jalansmut.blogspot.com/2024/05/judul.html
------------------------------------------------

... teks mentah postingan ...
```

## Catatan Penting
- Hormati **Terms of Service** situs target. Aplikasi ini mengambil HTML statis publik.
- Situs dengan konten JavaScript-heavy (SPA, login wall) tidak terambil penuh — gunakan API resmi platform tersebut.
- Jeda antar request 800 ms (sopan) — bisa diubah di kode (`delay(800)`).
- Riwayat disimpan di internal app (`filesDir/visited_history.txt`) — tombol **Hapus Riwayat** untuk reset.

## Teknologi
- Kotlin + Coroutines (async crawl di background, bisa di-stop)
- OkHttp 4.12 (HTTP client)
- Jsoup 1.17 (parsing HTML, ekstrak link & teks)
- BFS queue + riwayat persist (file-based dedup)
- MediaStore.Downloads (Android 10+) / direct file (≤9)
