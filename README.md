# PageDrop

PageDrop is a phone-first companion app for jailbroken Kindle and KOReader users.
It makes sideloading, local sync, covers, screensavers, fonts, dictionaries, and
repair tasks feel like one simple mobile workflow instead of a pile of desktop
tools, SFTP clients, Calibre plugins, and shell commands.

The product rule is simple:

> PageDrop is the only app users should have to interact with.

Calibre, Calibre-Web-Automated, command-line converters, article extractors, and
other backend tools can be used by PageDrop servers for heavy conversion and
formatting work, but they should remain implementation details behind
PageDrop's own UI and APIs.

## Features

### Direct Mode (built-in)

- Add books from Android document picker (supports EPUB, PDF, TXT, MOBI, AZW3)
- On-device EPUB/PDF/TXT → MOBI conversion with custom MobiWriter engine
- Extraction and persistence of cover images from EPUB files
- Local library with duplicate detection and metadata parsing
- Queue and transfer books to Kindle over SSH/SFTP
- Automatic thumbnail generation (EXTH UUID-based naming, resize to 330×430, upload to `/mnt/us/system/thumbnails`)
- Kindle library rescan trigger via `lipc-set-prop`
- Transfer history tracking with `lastTransferred` timestamps

### Find a book

- Searches Project Gutenberg, Standard Ebooks, Internet Archive, Digital Library
  of India, Open Library, English and Hindi Wikisource, Hindwi, Rekhta, Faded Page,
  ManyBooks, and user-added OPDS catalogs.
- Gutenberg falls back to its official search and edition pages when Gutendex
  cannot respond. Faded Page uses its public title-search form and resolves the
  actual EPUB/MOBI/PDF/TXT links on the chosen edition before downloading. Its
  required session cookie stays in memory and is sent only to its HTTPS host.
- Hindi and other scripts retain their letters and vowel marks for ranking,
  duplicate detection, and matching against the phone library. Different languages
  and unresolved Archive editions remain separate.
- Open Archive editions resolve their actual file list before downloading. EPUB
  downloads are checked for their ZIP structure, EPUB mimetype, and container.
- Hindwi, Rekhta, ManyBooks, and Wikisource results open their websites. Wikisource
  exports can be blocked or incomplete depending on the book's chapter structure;
  PageDrop does not guess a downloadable edition. Open Library borrowing also
  stays on its website; open editions are checked for direct downloads.
- ePustakalay, Anna’s Archive, Global Grey, and OceanofPDF have website searches below
  results. These and source detail links open in PageDrop's browser, where the user
  can browse editions, sign in if needed, and complete website verification.
  OceanofPDF opens its homepage for manual search. Access still depends on each site.
- Tapping a supported book download opens the book sheet with editable title and
  author. Choose Send to Kindle to download, validate, import and convert EPUB, then
  continue into the existing transfer flow; Add to library keeps the original format.
  The downloader uses the browser's user agent and live cookies for each destination,
  including redirects, without forwarding another host's cookies or full referring URL.
  Expired verification can be retried by reopening the source for a fresh download.
  Website guesses never silently reuse an existing library edition.
- Supported website downloads are HTTPS EPUB/MOBI/AZW3/PDF/TXT links or file attachments.
  Blob URLs, DRM readers, and pages without downloadable files remain unsupported.
  No JavaScript-to-native bridge, file access, or certificate-error bypass is enabled.
- Sources can be switched off in Settings → Book sources. Existing switches are
  preserved when new sources are introduced, including an explicit all-off choice.

Run the discovery regression tests with `./gradlew testDebugUnitTest --tests
'app.pagedrop.discover.*' --tests 'app.pagedrop.ui.discover.*'`. Real-site checks
are opt-in: `PAGEDROP_LIVE=1 ./gradlew testDebugUnitTest --tests
'app.pagedrop.discover.LiveSmokeTest.indianSourcesAndRealArchiveEpub'`.
For English searches and real EPUB downloads, use `PAGEDROP_LIVE=1 ./gradlew
testDebugUnitTest --tests 'app.pagedrop.discover.LiveSmokeTest.englishSourcesAndRealEpubDownloads'`.

The Android WebView regression tests run on a connected device with
`./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.pagedrop.ui.discover.SourceBrowserScreenTest`.
They exercise English links, Hindi extensionless attachments, session cookies and
in-app review using local fixtures. Live website CAPTCHAs require user interaction.

### Tools Suite

- **Screensaver Optimizer** — pick any image, select Kindle model, auto crop/resize/grayscale, upload to LinkSS
- **Font Installer** — curated catalog of e-ink-tuned fonts, download/validate/install to Kindle and KOReader paths
- **Dictionary Installer** — StarDict catalog, tar.bz2 extraction, install to KOReader dict paths
- **Device Dashboard** — read firmware/space/battery/jailbreak status, trigger rescan, reboot, repair recipes
- **KOReader Sync Broker** — scan `.sdr` folders, parse Lua metadata and highlights, read-only import
- **Article Sideloading** — share URL, extract readable content, build MOBI, transfer directly to Kindle

### Server-Assisted Mode (client built, server TBD)

- Optional PageDrop conversion server API client
- Per-file decision: skip backend for already-compatible formats
- Job polling and artifact downloading via OkHttp + kotlinx.serialization
- Supports `POST /v1/convert`, `GET /v1/jobs/{id}`, `GET /v1/artifacts/{id}`, `POST /v1/articles/convert`

## Architecture

- Android app: Kotlin, Jetpack Compose, Material 3
- Architecture: MVVM + Repository, Hilt DI, Room persistence
- Navigation: Navigation3 (type-safe nav keys)
- Transfer: JSch SSH/SFTP
- Local conversion: custom PalmDOC/LZ77 MobiWriter, epublib, PDFBox
- HTTP client: OkHttp with kotlinx.serialization (JSON)
- Image loading: Coil

### Package Structure

```
app/src/main/java/app/pagedrop/
├── converter/           EPUB/PDF/TXT → MOBI conversion, MobiWriter, ConversionResult
├── data/
│   ├── di/              Hilt modules, FakeBookRepository
│   ├── local/database/  Room entities, DAOs, AppDatabase
│   ├── BookRepository.kt
│   └── KindleSettings.kt
├── server/              PageDrop API client, DTOs, ServerSettings
├── tools/
│   ├── articles/        ArticleExtractor, ArticleMobiConverter
│   ├── dashboard/       DeviceCommandRunner, repair recipes
│   ├── dictionaries/    DictionaryCatalog, DictionaryInstaller
│   ├── fonts/           FontCatalog, FontInstaller
│   ├── screensavers/    KindleModelRegistry, ScreensaverProcessor
│   └── sync/            KoreaderSyncBroker, LuaTableParser, models
├── transfer/
│   ├── sftp/            KindleSftpClient
│   └── thumbnails/      KindleThumbnailGenerator
└── ui/
    ├── book/            Library, transfer bottom sheet, format picker
    ├── theme/           Material 3 color scheme, typography
    ├── tools/
    │   ├── articles/    ArticlesScreen + ViewModel
    │   ├── dashboard/   DashboardScreen + ViewModel
    │   ├── dictionaries/DictionariesScreen + ViewModel
    │   ├── fonts/       FontsScreen + ViewModel
    │   ├── screensavers/ScreensaverScreen + ViewModel
    │   ├── sync/        SyncScreen + ViewModel
    │   └── ToolsScreen.kt
    ├── MainActivity.kt
    ├── Navigation.kt
    └── NavigationKeys.kt
```

```
app/src/test/java/app/pagedrop/
├── converter/                MobiWriterTest
├── data/                     DefaultBookRepositoryTest
├── tools/
│   ├── articles/             ArticleExtractorTest
│   ├── screensavers/         KindleModelRegistryTest
│   └── sync/                 LuaTableParserTest, KoreaderSyncBrokerTest
├── transfer/thumbnails/      KindleThumbnailGeneratorTest
└── ui/book/                  BookViewModelTest
```

## Build

```bash
export ANDROID_HOME=/path/to/Android/sdk
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

If Gradle cannot find the Android SDK, configure either `ANDROID_HOME` or
`local.properties` with:

```properties
sdk.dir=/path/to/Android/sdk
```

## Documentation

- [Implementation plan](implementation_plan.md)
- [Research and roadmap](next_phase_research.md)
- [PageDrop server design](pagedrop_server.md)
- [Kindle and KOReader device paths](device_paths.md)
- [Current SFTP walkthrough](walkthrough.md)
- [Current task checklist](task.md)
- [MOBI header notes](mobi_comparison.md)
