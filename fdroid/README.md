# F-Droid submission

`app.pagedrop.yml` is a draft fdroiddata recipe for PageDrop.

How to submit:

1. Fork https://gitlab.com/fdroid/fdroiddata and clone it.
2. Copy `app.pagedrop.yml` to `metadata/app.pagedrop.yml` in the fork.
3. Make sure the tag `v0.8.0` exists on GitHub and matches `versionCode 13`.
4. Run `fdroid lint app.pagedrop` and `fdroid build -v -l app.pagedrop` locally.
5. Open a merge request against fdroiddata and follow the reviewers' feedback.

Notes:

- The recipe builds with empty `PAGEDROP_SUPABASE_URL` / `PAGEDROP_SUPABASE_ANON_KEY`,
  so the F-Droid build has no conversion server and converts everything on the phone.
- No `AntiFeatures: NonFreeNet` is declared on purpose. That anti-feature would be needed
  if the F-Droid build shipped with PageDrop's hosted conversion server on by default. The
  recipe's empty `gradleprops` give an empty server URL and key (`app/build.gradle.kts`
  treats an empty property as "no server", and `ServerSettings.hasBuiltInServer` is then
  false), so the F-Droid APK never contacts it. If you ever drop those two `gradleprops`,
  add `AntiFeatures: NonFreeNet` with a description saying the optional conversion server is
  enabled by default (after a first-upload prompt).
- `AllowedAPKSigningKeys` lets F-Droid publish the developer-signed APK (needed for
  updates to match GitHub/Obtainium builds). This needs a reproducible build; if F-Droid
  cannot reproduce it, drop that line and F-Droid will sign with its own key.
- Store text, changelogs and screenshots come from `fastlane/metadata/android/en-US/`.
- F-Droid reviewers may add anti-features or decline the app because of the background
  artwork and the in-app browsing of third-party (shadow-library) book sites. Be ready
  to explain these or adjust.
