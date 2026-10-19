# F-Droid submission

`app.pagedrop.yml` is the fdroiddata recipe for PageDrop. It passes `fdroid lint` and
`fdroid rewritemeta` (fdroidserver 2.4.5, with fdroiddata's `config/categories.yml` and
`config/antiFeatures.yml`). The F-Droid source scanner finds no problems in the `v0.9.0`
checkout once the recipe's `prebuild` has run.

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

- The GitHub release is built without `gradleprops`, so it includes PageDrop's hosted
  conversion server (URL and anon key in `BuildConfig`).
- The F-Droid build passes `PAGEDROP_SUPABASE_URL=` and `PAGEDROP_SUPABASE_ANON_KEY=`.
  `app/build.gradle.kts` treats an empty value as "no server", so the F-Droid APK has no
  built-in server and converts everything on the phone.

Different `BuildConfig` means different `classes.dex`, so the binaries can never match.
That is on purpose: the F-Droid build should not talk to the developer's server by default.

**Consequence for users:** the F-Droid APK and the GitHub/Obtainium/IzzyOnDroid APK have
different signing keys. Android refuses to install one over the other. Switching between
them means exporting anything you need (library, settings), uninstalling, and installing
the other build. Say this in the release notes and the README install section.

If the hosted server is ever dropped from the GitHub build too, the two builds could become
identical. Then reproducible-build mode would be possible: add
`AllowedAPKSigningKeys: c42b4f0044ebfd1785cb8e30e182a7861a6ab51554353c81459f87de395a0533`
and a `Binaries:` URL pointing at the release APK.

## The `prebuild` line

The `v0.9.0` tag's `settings.gradle.kts` lists two extra Maven repos:

- `https://raw.githubusercontent.com/nicjohnson145/epublib-repo/main/releases`. F-Droid's
  scanner rejects it as an unknown Maven repo. It is also dead (404). `epublib-core:3.1`
  has always come from Maven Central, which is listed first.
- `https://jitpack.io`. Nothing uses it.

Both lines are removed from `main`, so the `sed` is a no-op from the next tag on. It is
harmless to keep, and `AutoUpdateMode: Version` copies it into new builds. Remove it once
the oldest build in the recipe is newer than `v0.9.0`.

## Hosted conversion server

The recipe's empty `gradleprops` build without the hosted server. If you ever drop them,
the F-Droid build would use the developer's server by default. You would then also have to
describe that in the `NonFreeNet` anti-feature.

## Anti-features

These are declared in the recipe. Each one can be dropped once its cause is gone.

- **NonFreeNet**: importing a book automatically looks up its title and author on Google
  Books (and on Open Library, which is free) to suggest metadata and a cover. Google Books
  is a non-free service, and the lookup can't be turned off
  (`app/src/main/java/app/pagedrop/metadata/BookMetadataLookup.kt`). The app works without
  it, so NonFreeNet is arguable. Reviewers usually still flag it because it runs without the
  user asking. To drop it, add a setting that turns Google Books off, and make it off by
  default in builds without the hosted server.
- **NonFreeAssets**: `app/src/main/res/drawable/bezos_bg.png` is poster art credited to
  bezos.click (the website footer says so). bezos.click states no license, so PageDrop may
  not have the right to redistribute it. Replace it with art you made or art under a free
  license, or get written permission under a free license. Without that, F-Droid reviewers
  may reject the app rather than just tag it.

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
