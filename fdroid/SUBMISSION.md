# Submission texts

Ready-to-paste text for the two store requests. Background and the reasoning behind the
recipe are in [`README.md`](README.md).

Before submitting either one:

- [x] Poster art: used with the artist's permission (`licenses/POSTER-ART.md`). Not a free
      license, so NonFreeAssets stays declared.
- [x] Google Books: switchable since 0.9.1 and off by default without the server, so the
      F-Droid recipe no longer declares NonFreeNet.
- [ ] If the art is relicensed or replaced, tag a new release, delete the NonFreeAssets
      paragraphs below and the recipe entry.

---

## 1. IzzyOnDroid inclusion request

Where: https://codeberg.org/IzzyOnDroid/repodata/issues/new and choose the "App Inclusion
Request" form. IzzyOnDroid moved its tracker from GitLab to Codeberg in 2026. The form has
fields rather than a free body; use the text below for Summary, Description, Build
instructions and Further Notices. It also asks how much "AI" tools were used. Answer that
honestly: this repo's history shows Claude as co-author on many commits.

**Title:** `Inclusion request: PageDrop (app.pagedrop)`

```markdown
**App name:** PageDrop
**Package name:** app.pagedrop
**Source code:** https://github.com/spandan-kumar/PageDrop
**License:** GPL-3.0-only
**Releases / APK:** https://github.com/spandan-kumar/PageDrop/releases
(each release has one universal, signed APK, `PageDrop-vX.Y.Z.apk`, plus a `.sha256`.
v0.9.2 is about 11 MB; release builds are shrunk with R8.)
**Category:** Reading
**Fastlane metadata:** `fastlane/metadata/android/en-US/` (title, descriptions,
changelogs by versionCode, icon, phone screenshots)

**Summary:** Find books and send them to your jailbroken Kindle over Wi-Fi.

PageDrop is a phone-first companion for jailbroken Kindles running KOReader. You find a
book in public-domain and open catalogs (Project Gutenberg, Standard Ebooks, Internet
Archive, Wikisource and others) or your own OPDS catalogs, convert it to MOBI if needed,
and send it to the Kindle over SSH/SFTP.

**Requirements check:**
- Free and open source, GPL-3.0. All dependencies are FOSS from Maven Central / Google
  Maven: AndroidX, Compose, Hilt, Room, OkHttp, jsoup, JSch (mwiede), epublib,
  pdfbox-android, commons-compress, Coil, slf4j-android. No Google Play Services,
  Firebase, ML Kit or other proprietary libraries.
- No ads, no analytics, no crash reporting, no tracking.
- The APK is attached to each GitHub release and signed with the same key every time.
  Signing certificate SHA-256:
  `c42b4f0044ebfd1785cb8e30e182a7861a6ab51554353c81459f87de395a0533`
- No account needed.

**Network services, to be upfront (likely anti-features):**
- *NonFreeNet:* importing a book looks up its title and author on Open Library and, in
  this APK by default, Google Books (non-free). Settings → Book details turns Google Books
  off.
- *Hosted conversion server:* this APK uses the developer's conversion server (Supabase)
  by default. Before the first upload the app asks for consent and explains it. One
  switch in Settings keeps all conversion on the phone, and the phone also converts
  whenever the server fails. Uploaded files are deleted about 24 hours later. The server
  code is in the same repo under `server/`, so it can be self-hosted. Hosts the app
  contacts: https://github.com/spandan-kumar/PageDrop/blob/main/docs/PRIVACY.md
- *NonFreeAssets:* the library background is poster art from bezos.click, used with the
  artist's permission but not under a free license.

**Other notes:**
- Needs a jailbroken Kindle with KOReader and its SSH server. The app walks you through
  setup.
- Discover can show website shortcuts to shadow libraries (Anna's Archive, OceanofPDF).
  They are off by default and only open the website. PageDrop does not host books.
- A recipe for F-Droid's main repo is also being submitted. That build has no hosted
  server and is signed by F-Droid, so it can't update over the GitHub/IzzyOnDroid APK.

I'm the developer and happy to adjust anything.
```

---

## 2. F-Droid: fdroiddata merge request

Where: fork https://gitlab.com/fdroid/fdroiddata, add `metadata/app.pagedrop.yml` (copy
of `fdroid/app.pagedrop.yml`) on a branch named `app.pagedrop`, run
`fdroid lint app.pagedrop` and `fdroid build -v -l app.pagedrop`, then open the MR.
Fill in the MR template's checklist. The text below goes under it.

**Title:** `New app: PageDrop`

```markdown
Adds **PageDrop** (`app.pagedrop`), a GPL-3.0 companion app for jailbroken Kindles
running KOReader. It finds books in public-domain and open catalogs or the user's OPDS
catalogs, converts EPUB/PDF/text to MOBI on the phone, and sends them to the Kindle over
SSH/SFTP.

Source: https://github.com/spandan-kumar/PageDrop
Metadata: fastlane, in-repo (`fastlane/metadata/android/en-US/`)

### Build notes
- Plain `gradle: [yes]` build in `app/`. All dependencies come from Maven Central and
  Google Maven. No Play Services, Firebase, ML Kit or other proprietary libraries, and no
  prebuilt binaries apart from the Gradle wrapper.
- `gradleprops` sets `PAGEDROP_SUPABASE_URL` and `PAGEDROP_SUPABASE_ANON_KEY` to empty
  strings. The upstream GitHub build has the developer's hosted conversion server on by
  default. These empty values build the app with no server, so the F-Droid build always
  converts on the phone and never contacts that service. (`app/build.gradle.kts` treats an
  empty property as "no server".)
- No `AllowedAPKSigningKeys`: the F-Droid build differs from the upstream APK on purpose
  (no hosted server), so it can't be reproducible against it. Please sign it with the
  F-Droid key. Upstream documents that the F-Droid and GitHub builds can't update over
  each other.

### Anti-features
- No **NonFreeNet**: with no hosted server in this build, Google Books lookups are off by
  default (Settings → Book details); only Open Library is contacted for metadata.
- **NonFreeAssets**: the library/setup background is poster art from bezos.click, used
  with the artist's permission but not under a free license.

Not applicable: Ads, Tracking (no analytics/crash reporting), NonFreeDep, NonFreeAdd.

### Things you may want to look at
- Discover can show website shortcuts to Anna's Archive and OceanofPDF. They are off by
  default (Settings → Book sources), only open the site in an in-app browser, and PageDrop
  does not host or index books from them.
- The app targets a jailbroken Kindle, which is the user's own device.

I'm the upstream developer.
```
