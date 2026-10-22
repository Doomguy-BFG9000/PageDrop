# F-Droid submission

PageDrop is in F-Droid since fdroid/fdroiddata!53274 (merged 2026-10-22). The live recipe is `metadata/app.pagedrop.yml` in fdroiddata; F-Droid's bot adds new versions from git tags (`AutoUpdateMode: Version`, `UpdateCheckMode: Tags`). This copy mirrors it for reference.

`app.pagedrop.yml` is the fdroiddata recipe for PageDrop. It passes `fdroid lint` and
`fdroid rewritemeta` (fdroidserver 2.4.5, with fdroiddata's `config/categories.yml` and
`config/antiFeatures.yml`). The recipe builds `v0.9.3` (R8 enabled, no dependency-info block), whose `settings.gradle.kts` uses only
Google Maven and Maven Central, so no `prebuild` is needed.

Ready-to-paste text for the fdroiddata merge request and the IzzyOnDroid request is in
[`SUBMISSION.md`](SUBMISSION.md).

## How to submit

1. Fork https://gitlab.com/fdroid/fdroiddata and clone it.
2. Copy `app.pagedrop.yml` to `metadata/app.pagedrop.yml` in the fork.
3. Run `fdroid lint app.pagedrop`, `fdroid rewritemeta app.pagedrop` and
   `fdroid build -v -l app.pagedrop` there.
4. Open a merge request against fdroiddata with the text from `SUBMISSION.md`.

## Signing: F-Droid signs its own build

The recipe has no `AllowedAPKSigningKeys` and no `Binaries`. F-Droid builds the app and
signs it with F-Droid's key. It does not publish the developer-signed APK.

Reproducible-build mode (publishing the developer-signed APK) needs F-Droid's build to
match the GitHub release byte for byte. It can't:

- Both builds include PageDrop's hosted conversion server (from 0.9.4; before that the
  F-Droid build passed empty `PAGEDROP_SUPABASE_*` props and had no server).
- The F-Droid build passes `PAGEDROP_GOOGLE_BOOKS_DEFAULT=false`, so Google Books lookups
  start switched off there and on in the GitHub release.

Different `BuildConfig` means different `classes.dex`, so the binaries can't match. If the
Google Books default were made the same in both builds, they would become identical and
reproducible-build mode would be possible (see below).

**Consequence for users:** the F-Droid APK and the GitHub/Obtainium/IzzyOnDroid APK have
different signing keys. Android refuses to install one over the other. Switching between
them means exporting anything you need (library, settings), uninstalling, and installing
the other build. Say this in the release notes and the README install section.

If both builds ever use the same `gradleprops`, they could become identical. Then reproducible-build mode would be possible: add
`AllowedAPKSigningKeys: c42b4f0044ebfd1785cb8e30e182a7861a6ab51554353c81459f87de395a0533`
and a `Binaries:` URL pointing at the release APK.

## Maven repositories

Up to `v0.9.0`, `settings.gradle.kts` also listed a dead raw.githubusercontent.com Maven
repo and an unused jitpack entry, which F-Droid's scanner rejects. Both are gone from
`v0.9.1` on, so the recipe starts at `v0.9.1` and needs no `prebuild`.

## Hosted conversion server

From 0.9.4 the F-Droid build includes the hosted conversion server, same as the GitHub
release: on by default, the app asks before the first upload, files are deleted about 24
hours later, and one switch keeps everything on the phone. The server is free software
(Supabase and Calibre, code in `server/`) and can be self-hosted, so it is not declared as
`NonFreeNet`; the reviewers may decide otherwise. To build without it, pass
`PAGEDROP_SUPABASE_URL=` and `PAGEDROP_SUPABASE_ANON_KEY=`.

## Anti-features

These are declared in the recipe. Each one can be dropped once its cause is gone.

- **NonFreeNet**: not declared from 0.9.1. Google Books lookups are behind Settings → Book
  details, which the recipe switches off by default with `PAGEDROP_GOOGLE_BOOKS_DEFAULT=false`
  (`app/src/main/java/app/pagedrop/metadata/MetadataSettings.kt`), so the F-Droid build only
  contacts Open Library for metadata unless the user switches Google Books on.
- **NonFreeAssets**: `app/src/main/res/drawable/bezos_bg.png` is poster art from bezos.click,
  used with the artist's permission (see `licenses/POSTER-ART.md`). Permission is not a free
  license, so the tag stays until the art is released under one (for example CC BY 4.0) or
  replaced.

Not needed: Ads, Tracking (no analytics, crash reporting or Google/Firebase libraries),
NonFreeDep (every dependency is free software from Maven Central or Google Maven),
NonFreeAdd, KnownVuln, TetheredNet.

## Other things reviewers may raise

- **Shadow-library shortcuts.** Discover can show search links to Anna's Archive and
  OceanofPDF. They are off by default (Settings → Book sources) and are website links, not
  built-in downloads. F-Droid has no anti-feature for this, but a reviewer may still ask
  about it.
- **Global Grey link.** The Global Grey shortcut opens a `google.com` site search in the
  in-app browser. The user taps it, so it is not NonFreeNet. A reviewer might still mention
  it.
- **Jailbroken Kindle.** SSH/SFTP transfer targets a jailbroken Kindle. That is the user's
  own device and needs no anti-feature.

Store text, changelogs and screenshots come from `fastlane/metadata/android/en-US/`.
