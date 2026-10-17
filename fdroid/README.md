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
- `AllowedAPKSigningKeys` lets F-Droid publish the developer-signed APK (needed for
  updates to match GitHub/Obtainium builds). This needs a reproducible build; if F-Droid
  cannot reproduce it, drop that line and F-Droid will sign with its own key.
- Store text, changelogs and screenshots come from `fastlane/metadata/android/en-US/`.
- F-Droid reviewers may add anti-features or decline the app because of the background
  artwork and the in-app browsing of third-party (shadow-library) book sites. Be ready
  to explain these or adjust.
