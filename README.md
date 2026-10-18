# PostScraper Crawler — Aplikasi Native Android (Kotlin)

Aplikasi native Android yang **merayap (crawl) otomatis** mulai dari 1 URL seed (mis. homepage blog), masuk ke tiap postingan, mengambil **isi artikelnya saja**, lalu mencari postingan berikutnya. Hasilnya disimpan sebagai file **`.txt` bersih** di **`Internal Storage > Download`**.

## Alur Crawler
1. Isi URL awal (seed), mis. `https://jalantikus.com`, dan **maks. post** yang mau diambil.
2. Halaman diambil lewat HTTP biasa. Jika isinya kosong (situs dirender JavaScript), halaman otomatis **dirender ulang dengan WebView** lalu dibaca.
3. Semua link se-domain dikumpulkan. Link yang mirip **postingan** (slug panjang, `.html`, path bertanggal) masuk antrian prioritas; halaman kategori/index (`/tips`, `/gadgets`, `/page/2`, dst.) hanya dipakai untuk mencari link baru.
4. Tiap postingan: ambil judul + isi artikel, buang menu, footer, sidebar, iklan, komentar, "Baca juga", **link**, dan **garis pemisah (`=====`)**.
5. Dari dalam postingan, link post lain ikut diantrikan, jadi crawl berlanjut dari post ke post.
6. Baris yang berulang di banyak post (boilerplate) dibuang di akhir, lalu file disimpan.
7. **Riwayat anti-dobel** hanya mencatat post yang benar-benar berhasil diambil. Bisa di-toggle ON/OFF dan dihapus.
8. **Stop** kapan saja — hasil yang sudah terkumpul tetap disimpan.

## Format file hasil
Teks polos, tanpa header, tanpa link, tanpa garis pemisah. Satu post = judul, lalu paragraf satu per baris, lalu satu baris kosong:
```
Judul Post Pertama
Paragraf pertama isi artikel.
Paragraf kedua isi artikel.

Judul Post Kedua
Paragraf pertama ...
```
Nama file: `korpus_crawl_<domain>_<timestamp>.txt`.

## Cara Build
1. Buka **Android Studio** → `Open` → folder proyek.
2. Tunggu Gradle sync (OkHttp, Jsoup, Coroutines terunduh otomatis).
3. Run ke HP Android (USB debugging) atau emulator.
4. Min SDK 24 (Android 7.0), target SDK 34. Android 10+ tidak perlu izin storage (pakai `MediaStore.Downloads`).

### Build APK otomatis (GitHub Actions)
Jika file `.github/workflows/build-apk.yml` ada, setiap push ke `main` menjalankan workflow **Build APK**. Unduh hasilnya di tab **Actions** → run terbaru → **Artifacts** → `PostScraper-debug-apk`.

## Catatan Penting
- Hormati **Terms of Service** situs target.
- Halaman yang dirender WebView lebih lambat (sekitar 3–5 detik per halaman).
- Situs dengan login wall atau proteksi bot tidak bisa diambil.
- Jeda antar request 800 ms (sopan) — bisa diubah di kode (`delay(800)`).
- Riwayat disimpan di internal app (`filesDir/posts_history.txt`) — tombol **Hapus Riwayat** untuk reset.
- Toggle "Pembersihan" OFF = tidak membuang menu/footer/iklan, tetapi link dan garis pemisah tetap dibuang.

## Teknologi
- Kotlin + Coroutines (crawl di background, bisa di-stop)
- OkHttp 4.12 (HTTP client)
- Jsoup 1.17 (parsing HTML, ekstrak link & teks)
- WebView (render halaman JavaScript)
- MediaStore.Downloads (Android 10+) / direct file (≤9)
