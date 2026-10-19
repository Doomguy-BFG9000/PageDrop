<!-- website link -->

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" alt="PageDrop icon" width="88" height="88">
</p>

<h1 align="center">PageDrop</h1>

<p align="center"><b>The store closed. The Kindle didn't.</b></p>

<p align="center">
  <a href="https://github.com/spandan-kumar/PageDrop/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/spandan-kumar/PageDrop?color=121212&labelColor=efebe1"></a>
  <a href="LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-b3261e"></a>
  <a href="https://discord.gg/cfsqhuHVfb"><img alt="Join the Discord" src="https://img.shields.io/badge/Discord-join%20the%20chat-5865F2?logo=discord&logoColor=white"></a>
  <img alt="Android 8.0+ (API 26)" src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3ddc84?logo=android&logoColor=white">
  <a href="https://github.com/spandan-kumar/PageDrop/actions/workflows/build.yml"><img alt="Build status" src="https://img.shields.io/github/actions/workflow/status/spandan-kumar/PageDrop/build.yml?branch=main"></a>
</p>

<p align="center">
  <a href="https://github.com/spandan-kumar/PageDrop/releases/latest">Download</a> ·
  <!-- website link -->Website (soon) ·
  <a href="https://discord.gg/cfsqhuHVfb">Discord</a> ·
  <a href="https://github.com/spandan-kumar/PageDrop/issues/new/choose">Report a bug</a>
</p>

PageDrop finds books on your phone and sends them over Wi-Fi to your old, jailbroken Kindle running [KOReader](https://koreader.rocks/). Free, open source, no cable, no Calibre, no permission.

## Why

On May 20, 2026, every Kindle and Fire tablet released in 2012 or earlier lost the ability to buy, borrow or download Kindle Store content on the device. Books already on them stay. Sideloading still works. Perfectly good e-readers, some of them in drawers, some still in people's hands, can no longer get anything from the store on their own.

They still read fine. A jailbroken Kindle running KOReader is a fine reader for the rest of its life, but getting books onto it meant cables, desktop tools and shell commands. PageDrop turns that into one app on your phone. It was built and tested on a 2012 Kindle Paperwhite (firmware 5.6.1.1); see [tested devices](#tested-devices).

## What it does

- **Find books in the app.** Search Project Gutenberg, Standard Ebooks, free Internet Archive items and your own OPDS catalogs, or browse other book websites in PageDrop's built-in browser and capture downloads right there.
- **Send over Wi-Fi.** Books land in both the Kindle home screen (`/mnt/us/documents`) and KOReader's library (`/mnt/us/Books`).
- **Make PDFs readable.** PDF reflow unwraps broken lines and strips running headers and page numbers, so a text PDF reads like a book on a small screen. It needs PDFs that contain text; it does not OCR scanned pages. Keep scans as PDF.
- **Fix the details.** Title, author and cover are pulled from the file and open catalogs, then written into the book.
- **Manage the Kindle.** See what is on it, delete books, save copies back to your phone.
- **Get your Kindle ready.** A walkthrough that hands off to the [Kindle Modding Wiki](https://kindlemodding.org/)'s jailbreak wizard.
- **Extras.** Screensavers, fonts, dictionaries, saved articles, and a read-only import of KOReader highlights and reading progress to your phone.
- **Conversion server, on by default.** When a book needs converting, PageDrop uploads it to PageDrop's conversion server (Calibre), whose cleanup job deletes it about 24 hours later. PageDrop asks before the first upload. One switch in Settings keeps everything on the phone, and if the server fails PageDrop converts on the phone instead. You can also self-host the server. Details: [What leaves your phone](docs/PRIVACY.md).

## Tested devices

PageDrop talks to KOReader over SSH, so any jailbroken Kindle that runs KOReader should work. Only one has actually been tested.

| Kindle | Firmware | Status |
|---|---|---|
| Kindle Paperwhite (1st gen, 2012) | 5.6.1.1 | Confirmed |
| Kindle Touch (2011) | | Untested |
| Kindle 4 (2011) | | Untested |
| Kindle Keyboard (2010) | | Untested |
| Later Kindles (2013 on) running KOReader | | Untested |

Tried it on something else? [Open an issue](https://github.com/spandan-kumar/PageDrop/issues/new/choose) or say so on [Discord](https://discord.gg/cfsqhuHVfb) with the model and firmware, and it goes in the table as "Reported".

## Screenshots

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="180" alt="PageDrop screenshot 1">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="180" alt="PageDrop screenshot 2">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="180" alt="PageDrop screenshot 3">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="180" alt="PageDrop screenshot 4">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width="180" alt="PageDrop screenshot 5">
</p>

## Getting started

**You need** an Android phone (8.0 or newer) and a Kindle that is jailbroken, running KOReader with its SSH server enabled. No jailbreak yet? PageDrop walks you through it.

**Install**

- **GitHub Releases:** download the APK from the [latest release](https://github.com/spandan-kumar/PageDrop/releases/latest). Each release is signed; see [SECURITY.md](SECURITY.md) to verify it.
- **Obtainium:** add `https://github.com/spandan-kumar/PageDrop` as an app source and get updates automatically.
- **F-Droid:** planned (see [`fdroid/`](fdroid/)).

**First run**

1. Not jailbroken yet? Open **Get your Kindle ready**. Its checklist takes you through the Kindle Modding Wiki's jailbreak wizard, KOReader and blocking updates.
2. In KOReader, open Tools (cog) → Network → SSH server and switch it on. Keep the Kindle on the same Wi-Fi as your phone.
3. In PageDrop, tap **Find my Kindle**, then search for a book and send it.

## Privacy

No account to create, no analytics, no ads. Your phone talks to your Kindle over local Wi-Fi. It also contacts the book sources you search, Open Library and Google Books for book details, GitHub and SourceForge when you install fonts or dictionaries, and, unless you switch it off, PageDrop's conversion server. Websites you open in the built-in browser see you like any browser would. The full host-by-host list is in [What leaves your phone](docs/PRIVACY.md).

## Community

PageDrop is made by people who like old Kindles. Come say hi.

- **Chat:** [Discord](https://discord.gg/cfsqhuHVfb) for questions, setup help and show-and-tell.
- **Ideas and bugs:** [GitHub Issues](https://github.com/spandan-kumar/PageDrop/issues/new/choose).
- **Contribute:** read [CONTRIBUTING.md](CONTRIBUTING.md). First-timers welcome. Please follow the [Code of Conduct](CODE_OF_CONDUCT.md).
- **Security:** see [SECURITY.md](SECURITY.md).

## Building from source

You need JDK 21 and the Android SDK.

```bash
export ANDROID_HOME=/path/to/Android/sdk   # or sdk.dir in local.properties
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

To build without the conversion server, pass empty properties:

```bash
./gradlew assembleDebug -PPAGEDROP_SUPABASE_URL= -PPAGEDROP_SUPABASE_ANON_KEY=
```

Release signing keys are never in the repo. Gradle reads them from `PAGEDROP_KEYSTORE_PATH`, `PAGEDROP_KEYSTORE_PASSWORD`, `PAGEDROP_KEY_ALIAS` and `PAGEDROP_KEY_PASSWORD`, or from a properties file (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) at `~/.pagedrop-release/keystore.properties` (override with `-PPAGEDROP_KEYSTORE_PROPERTIES`). With neither, `./gradlew assembleRelease` makes an unsigned APK. Pushing a `v*` tag runs [`release.yml`](.github/workflows/release.yml), which needs the matching GitHub secrets plus `PAGEDROP_KEYSTORE_BASE64`.

More: [CONTRIBUTING.md](CONTRIBUTING.md) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (discovery tests, live-site checks, WebView tests).

## Self-hosting the conversion server

Release builds use PageDrop's own conversion server. It is a Supabase project plus a worker running Calibre, and it handles conversions and article extraction. You can run your own and build the app against it. Setup is in [server/README.md](server/README.md).

## Project structure

```
app/        Android app (Kotlin, Jetpack Compose, MVVM + Hilt, Room)
server/     Conversion server (Supabase + Calibre worker)
website/    Project website
fastlane/   Store listing text, icon and screenshots
fdroid/     F-Droid submission recipe
licenses/   Third-party licenses
docs/       Architecture, privacy and technical notes
```

The full package layout, sources and test notes are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Credits

- [KOReader](https://koreader.rocks/) does the actual reading. PageDrop just feeds it.
- [Kindle Modding Wiki](https://kindlemodding.org/) for the jailbreak guides and wizard.
- [Calibre](https://calibre-ebook.com/) powers the optional conversion server.
- [Project Gutenberg](https://www.gutenberg.org/), [Standard Ebooks](https://standardebooks.org/) and the [Internet Archive](https://archive.org/) for keeping books free to read.
- [Literata](https://fonts.google.com/specimen/Literata), used under the SIL Open Font License (see [`licenses/`](licenses/)).
- Poster art from [bezos.click](https://bezos.click), used with permission (not covered by the GPL; see [`licenses/POSTER-ART.md`](licenses/POSTER-ART.md)).

## License

[GPL-3.0](LICENSE). Contributions are accepted under the same license.

PageDrop does not host or provide books; respect copyright law where you live. PageDrop is not affiliated with or endorsed by Amazon. Kindle is a trademark of Amazon.com, Inc.
