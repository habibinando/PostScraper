# PostScraper AI — local Android assistant and article scraper

PostScraper AI combines an on-device GGUF chat assistant, TXT editing, and an article scraper. Chat and text editing use the imported model locally; web requests are only used by the scraper. There is no external chat API dependency.

The scraper crawls a website from a seed URL, extracts article text, merges multi-page articles where the site exposes a recognizable next-page link, de-duplicates canonical article URLs, and exports a plain-text corpus to the Android **Downloads** folder.

## Features

- Native Android app; minimum Android API 26.
- Chat-first interface with a responsive side drawer, edge-to-edge/system-bar handling, and an input composer that stays above the keyboard and navigation bar.
- Chat with the imported local GGUF model using on-device CPU inference.
- Import a TXT file, request proofreading/reformatting/summarization/translation, then save the result as a separate TXT file.
- GGUF import checks the file header and copy size; the model-use switch is disabled during import and there is visible progress feedback.
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
- `LocalModelCleaner.kt` — shared serialized access to a local GGUF model for chat, text editing, and optional article-extraction fallback.
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


## Chat/model limitations

- Inference is CPU-only with the current free `llama-android:0.1.1` AAR. The API returns the full response only after native generation finishes; it does not expose token streaming or a mid-generation interrupt. The UI therefore shows the current phase and elapsed time, and normal chat output is capped at 128 tokens to avoid very long waits if a model fails to emit an end token.
- TXT input is capped at 6,000 characters. Text-edit output is capped at 512 tokens; review long edits before replacing any source document.
- The scraper can cancel HTTP requests and WebView rendering immediately. If the local GGUF model is already generating an extraction, Stop records the request but must wait for that native inference call to finish.
- A valid GGUF header does not guarantee a compatible model or sufficient RAM. Model quality and response speed depend on the selected file and device hardware.

## Current app version

- Version name: 1.9
- Version code: 10
