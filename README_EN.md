# FoldReader

[![CI](https://github.com/llzx373/FoldReader/actions/workflows/ci.yml/badge.svg)](https://github.com/llzx373/FoldReader/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/llzx373/FoldReader?sort=semver)](https://github.com/llzx373/FoldReader/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-13%2B%20(API%2033)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)

**A local-first e-book, comic and PDF reader built for foldable phones.**

> When a foldable unfolds it is not "a bigger phone" — it is an open book. The hinge is the spine; the two halves are the two pages.

FoldReader treats the unfolded screen as a book rather than a wider canvas. In unfolded state each half renders its own page, the hinge area is avoided and drawn as a spine shadow, and the margin on the spine side is widened. Half-opened (tabletop) it puts the text in the upper half and the controls in the lower half. Reading position survives folding, unfolding, rotating and posture changes.

English | [简体中文](README.md)

---

## Features

- **Foldable layouts** — single page when folded, **dual-page book mode** when unfolded, **tabletop mode** when half-opened. Hinge-spine geometry, spine shadow, spine-side margin widening, and posture continuity that never loses your reading position. In AUTO mode a portrait window (taller than wide) always falls back to single page; FORCE_DUAL overrides orientation.
- **Typesetting engine** — `StaticLayout`-based pagination, Chinese kinsoku (line-break prohibition rules), justification, first-line indent, 18–40 characters per line, adjustable everywhere. Two-layer page-boundary cache (memory LRU + disk).
- **Huge files** — 100 MB+ TXT books are read through windowed byte offsets, never loaded fully into memory. Chapter indexing and prewarming run on a background queue.
- **Encoding detection** — UTF-8 (with or without BOM), UTF-16 LE/BE, GBK, GB18030, Big5, plus manual override.
- **Reading tools** — hierarchical TOC, bookmarks, multi-colour highlights and notes, full-text search grouped by chapter, auto page-turn, optional **simulated 2.5D page-peel** (off by default; e-books, comics and paged PDFs), reading stats, 5 built-in themes (green / parchment / grey-white / night / AMOLED) plus custom colours, in-app brightness.
- **In-page anchors** — bookmarks and annotations can point at a page position (point or rectangle), for reflowable text, comics and PDFs alike. The middle tap zone is configurable: single and double tap each bind to a chosen action.
- **TTS read-aloud** — the system TTS engine reads from the current position or the whole chapter, sentence by sentence, with page turns following the narration. A foreground service plus MediaSession keeps playback alive on the lock screen and routes headset buttons to pause / resume / stop. Chinese engine for now.
- **AI translation & helpers** (your own provider; zero network until configured) — translate-on-selection (saveable as an annotation), translate this page (streaming side-by-side panel), translate a chapter, and **whole-book translation** (batch queue, resume-from-breakpoint, foreground-service progress notification). Three bilingual views: translated-text mode, dual-page original|translation, and per-paragraph comparison in scroll mode. **Comic translation** runs bubble detection and OCR fully on-device and sends only the recognised text to the AI; optional vision-model mode sends whole page images with per-book consent. A **glossary** with global / series / per-book scopes keeps names and terms consistent. AI helpers also infer chapter-splitting rules, recommend cleanup recipes, and fill in missing book metadata (fill-empty only, batch supported).
- **On-device OCR & models** — local ONNX inference (CPU). Scanned PDFs get an OCR text layer (searchable, selectable); comic bubble detection feeds the translation pipeline. Chinese / English / Japanese recognition models are imported under "Settings → OCR models" with SHA-256 verification; custom fine-tuned .onnx files are welcome and take precedence.
- **Library** — grid/list shelf, groups, auto-generated covers, batch import, a built-in **file browser** (SAF-granted external folders: open a book in place, import a whole directory as a group, open an image folder as a comic), and automatic **same-series previous/next volume switching**.
- **Smart cleanup for downloaded novels** — an offline, deterministic rule engine that fixes what scraped TXT files usually suffer from: mangled whitespace, trailing spaces, blank lines inside paragraphs, paragraphs broken by hard wrapping, chapter titles buried mid-line or named inconsistently, quotes split by spaces/newlines, asterisk-masked words, site promos and forum leftovers. Three presets plus 14 individual switches, a change-report preview before you commit, and "smart tidy" for books already in the library. No model, no network.
- **Privacy** — local-first: no accounts, no first-party cloud, no telemetry. The `INTERNET` permission ships with the AI features (Settings → AI Service: bring your own API key and configure your provider's endpoint; the app talks directly to the provider you configure, with first-use consent per feature, an outbound-content ledger, and Keystore-encrypted credentials). Until you configure an AI service, the app makes no network requests at all. WebDAV backup/restore (self-hosted) is still planned.

## Supported formats

| Type | Extensions / containers |
| --- | --- |
| Plain text | `.txt` (large files, multiple encodings) |
| EPUB | `.epub` (EPUB3 NAV and EPUB2 NCX) |
| FictionBook | `.fb2`, `.fb2.zip` |
| PDF | `.pdf` — text PDFs can also be read as reflowable e-books; scanned PDFs get an on-device OCR text layer (searchable / selectable) |
| Comics | `.cbz`/`.zip`, `.cbr`/`.rar`, `.cbt`/`.tar`, `.cb7`/`.7z`, or an image folder picked via SAF |

Format detection is **magic-bytes first, extension/MIME as fallback** — mislabelled archives (a `.cbr` that is really a zip) are common in comic collections and only open correctly when the real bytes are inspected.

## Install

Download the latest APK from [Releases](https://github.com/llzx373/FoldReader/releases) — pick one of two variants (identical features, same package name and signature; only one can be installed, switching via overwrite install works):

- `FoldReader-<version>-full.apk` — **bundles all OCR / comic-translation models**, ready out of the box (larger download);
- `FoldReader-<version>-lite.apk` — no bundled models (smaller); import models under "Settings → OCR models → Model manager", including your own fine-tuned / custom .onnx files.

Requires **Android 13 (API 33) or newer** (arm64-v8a or x86_64). The APK is distributed outside any app store, so you must allow installing from unknown sources.

Large foldables (Galaxy Z Fold, Huawei Mate X, and equivalents) and wide foldables are the primary targets. Bar phones and flip phones work but fall back to single-page reading.

## Build from source

Build environment, flavors, commands, signing and versioning live in **[docs/构建与打包.md](docs/构建与打包.md)** (Chinese). Quick start: install JDK 25 and the Android SDK, create `local.properties` with `sdk.dir=/path/to/Android/Sdk`, then run `./gradlew :app:assembleLiteDebug`.

## Architecture

Single Gradle module (`:app`), Kotlin + Jetpack Compose. Three ideas run through the whole codebase:

1. **Hand-rolled dependency injection** — an `AppContainer` in `FoldReaderApplication` wires the database, repositories, parsers, use cases and caches. No Hilt/Koin.
2. **Format engine separated from the reading core** — non-TXT formats (EPUB / FB2 / text PDFs) are flattened into `TXT + .toc` sidecar files at import time and then flow through the same pagination engine. The reading core has zero format branches.
3. **A paged-reading seam** — comics and PDFs both implement `PagedImageSource`; `ReaderHost` dispatches once between text and paged reading, and the two paged formats share anchors, bookmarks, zoom and gestures.

## Known limitations

- **The database is upgrade-safe.** Every release since `v1.0.0` can upgrade in place: a schema change must bump `version` and add a migration in `core/data/db/DatabaseMigrations.kt`, and every exported schema snapshot under `app/schemas/` is kept. Destructive fallback is deliberately not enabled — a missing migration fails loudly instead of wiping data. Exporting an in-app backup before a major upgrade is still recommended.
- **OCR is fully on-device and needs models** — the scanned-PDF text layer and comic bubble detection run on local ONNX inference; no online OCR. Models ship with the full variant or are imported manually on lite.
- **Simulated page-turn is optional** (COVER / SIMULATION / NONE / SCROLL); the default remains COVER. The old 3D curl/hinge path stays deleted; the current model is a 2.5D crease reflection (see the design spec appendix v2.4).
- Not published on any app store; distributed via GitHub Releases only.

## Documentation

The detailed design and requirements document is written in Chinese: [docs/需求与设计说明书.md](docs/需求与设计说明书.md). See also [CHANGELOG.md](CHANGELOG.md), [docs/构建与打包.md](docs/构建与打包.md) (build & packaging) and [docs/发布流程.md](docs/发布流程.md) (release process).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). For security issues use the private channel described in [SECURITY.md](SECURITY.md).

## License

[MIT](LICENSE). Third-party components: Jetpack Compose, AndroidX, Room, kotlinx-coroutines, OpenCC and the OCR / bubble-detection models (Apache-2.0), Apache Commons Compress (Apache-2.0), ONNX Runtime (MIT), XZ for Java (Public Domain), junrar (UnRAR License), PDFBox-Android and androidx.pdf (Apache-2.0). The same list is shown in-app under Settings → Open source licenses.
