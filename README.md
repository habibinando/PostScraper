# PostScraper — Android article corpus extractor

PostScraper crawls a website from a seed URL, extracts article text, merges multi-page articles where the site exposes a recognizable next-page link, de-duplicates canonical article URLs, and exports a plain-text corpus to the Android **Downloads** folder.

## Features

- Native Android app; minimum Android API 26.
- Crawls same-domain links with article URLs prioritized over category pages.
- Local extraction using Jsoup, with AMP fallback for JalanTikus and JavaScript rendering via WebView.
- Optional **Jina Reader** fallback for pages that ordinary HTML, AMP, and WebView cannot read.
- Pagination-aware merging: attempts to combine up to 12 pages of the same article into one corpus entry.
- Filters common navigation, sidebar, ad, sharing, related-content, and comment elements.
- Keeps an anti-duplicate history of successfully exported canonical article URLs.
- Stops safely and preserves articles already collected during a run.
- Exports UTF-8 `.txt` files to Downloads.

## Crawling order

1. Fetch regular HTML on-device.
2. When needed, try the site's AMP route if a known AMP fallback exists.
3. If the optional Jina Reader switch is enabled, request clean Markdown from the Reader service.
4. Try WebView rendering as a final fallback.

The Jina Reader service is external. When the switch is enabled, URLs sent to fallback may be processed by that service. Anonymous Reader requests have published rate limits; failures, bot protection, login walls, or site changes can still prevent extraction. No scraping tool can guarantee every website or article layout.

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
- `RemoteReader.kt` — optional external Reader fallback.
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


## Optional provider settings

The hamburger menu opens local settings for:
- **Firecrawl**: optional remote scrape fallback for article pages that local HTML/AMP extraction cannot read. Firecrawl charges credits per page, including on its free tier; add your own API key and enable it explicitly.
- **Groq**: optional text cleanup after the article has already been extracted. It does not crawl websites or defeat access controls. Choose a model ID returned by the Groq API; model availability and free-tier limits vary by account and can change.
- **HTTP proxy**: optional manual host and port for trusted proxies. PostScraper does not automatically harvest or rotate unknown public proxies. Such proxies are often unreliable and can observe traffic. WebView rendering may still use Android's normal network route.

Provider API keys are saved in the app's private preferences and sent only to the provider that uses them. Firecrawl receives the target URL when used; Groq receives article text for cleanup. Leave both features disabled to keep extraction local except for the existing optional Jina Reader fallback.
