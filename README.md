# PostScraper — Android article corpus extractor

PostScraper crawls a website from a seed URL, extracts article text, merges multi-page articles where the site exposes a recognizable next-page link, de-duplicates canonical article URLs, and exports a plain-text corpus to the Android **Downloads** folder.

## Features

- Native Android app; minimum Android API 26.
- Crawls same-domain links with article URLs prioritized over category pages.
- Local extraction using Jsoup, with AMP fallback for JalanTikus and JavaScript rendering via WebView.
- Pagination-aware merging: attempts to combine up to 12 pages of the same article into one corpus entry.
- Filters common navigation, sidebar, ad, sharing, related-content, and comment elements.
- Keeps an anti-duplicate history of successfully exported canonical article URLs.
- Stops safely and preserves articles already collected during a run.
- Exports UTF-8 `.txt` files to Downloads.

## Crawling order

1. Fetch regular HTML on-device.
2. When needed, try the site's AMP route if a known AMP fallback exists.
3. If article content is still too short, render the page through WebView to execute JavaScript.
4. If the parser still cannot identify the article, optionally use the local Qwen GGUF model as the final extraction fallback.

## Article pagination

For article pages exposing a clear continuation link, PostScraper looks for common pagination markup, rel-next links, next/continue labels, and numbered article-page links. It merges pages that appear to share the same article title/canonical URL, with a hard limit of 12 pages. Websites with custom pagination patterns may need a site-specific rule; unrelated pages are not intentionally combined.

## Export format

One article per section: title, paragraph lines, then a blank line.

```text
Judul Artikel Pertama
Paragraf pertama.
Paragraf kedua.

Judul Artikel Kedua
Isi artikel.
```

Output file format: `korpus_crawl_<domain>_<timestamp>.txt`.

## Project structure

- `MainActivity.kt` — input validation, UI state, history, lifecycle, and export orchestration.
- `CrawlEngine.kt` — work queues, crawl loop, duplicate handling, fallback order, and page merging.
- `ArticleExtractor.kt` — URL normalization, pagination detection, article content extraction, cleanup, and Markdown parsing.
- `PageLoader.kt` — HTTP fetching, request cancellation, and WebView JavaScript rendering.
- `LocalModelCleaner.kt` — optional on-device GGUF extraction fallback used only when normal extraction cannot recover enough article text.
- `TextExporter.kt` — final text assembly and Android Downloads export.

## Build APK

The GitHub Actions workflow `Build APK` runs on each push to `main`. Open the latest successful run, then download the `PostScraper-debug-apk` artifact.

- Android minimum API: 26.
- Compile/target API: 37.
- Android Gradle Plugin: 9.2.1.
- Gradle: 9.4.1.
- CI JDK: 25; app bytecode targets Java 17.

## Responsible use

Respect the target site's Terms of Service, robots/access policies, copyright, rate limits, and content ownership. Do not use PostScraper to bypass login controls or technical access restrictions.


## On-device article extraction fallback

The hamburger settings allow importing Qwen2.5-3B-Instruct-Abliterated.Q4_K_M.gguf. The model runs only as the final fallback when HTML, AMP, and WebView have not produced enough article text. No external reader or scraping API is used. Article fetching still requires an internet connection, but final extraction runs on-device. If an inference is already running, Stop may need to wait for that one model call to return.
