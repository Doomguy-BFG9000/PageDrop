# Contributing to PageDrop

Thanks for wanting to help. PageDrop exists because a store closed and people's perfectly good Kindles got left behind. Bug reports, fixes, docs, translations and ideas all make that better. You don't have to be an Android expert to be useful.

By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). By contributing code you agree it is released under [GPL-3.0](LICENSE).

## Talk to us first

- **Discord:** https://discord.gg/cfsqhuHVfb is the best place for questions, setup help and "is this worth building?" chats.
- **Issues:** use the [issue templates](https://github.com/spandan-kumar/PageDrop/issues/new/choose). Search first, someone may have hit the same thing.
- For anything big (a new book source, a new tool, a redesign), open an issue or ask on Discord before writing code, so nobody's weekend is wasted.

Jailbreak trouble is usually not a PageDrop bug. The [Kindle Modding Wiki](https://kindlemodding.org/) and its community are the place for that.

## Setting up

You need JDK 21 and the Android SDK (API 36 platform, matching `compileSdk` in `app/build.gradle.kts`).

```bash
git clone git@github.com:spandan-kumar/PageDrop.git
cd PageDrop
export ANDROID_HOME=/path/to/Android/sdk   # or sdk.dir in local.properties
./gradlew assembleDebug
```

To build without the conversion server, add `-PPAGEDROP_SUPABASE_URL= -PPAGEDROP_SUPABASE_ANON_KEY=`. You never need release keys to contribute.

## Running tests

```bash
./gradlew testDebugUnitTest                      # unit tests, same as CI
./gradlew testDebugUnitTest --tests 'app.pagedrop.discover.*' --tests 'app.pagedrop.ui.discover.*'
./gradlew connectedDebugAndroidTest              # needs a device or emulator
```

Live-site checks are opt-in so CI stays quiet and polite to other people's servers: `PAGEDROP_LIVE=1 ./gradlew testDebugUnitTest --tests 'app.pagedrop.discover.LiveSmokeTest.*'`. More in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

Add or update tests with your change. If something is hard to test, say so in the PR.

## Code style

- **Kotlin**, following the [official style](https://kotlinlang.org/docs/coding-conventions.html). Keep functions small and names plain.
- **Jetpack Compose + Material 3** for UI. State lives in ViewModels, composables stay dumb.
- **MVVM + Repository, Hilt for DI, Room for storage.** Follow the existing package layout ([docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)).
- **Keep the paper and ink design language:** warm paper backgrounds, near-black ink, Literata for reading text, one red accent used sparingly. No gradients, no glossy chrome. Look at neighbouring screens before inventing new styling.
- Copy is warm, direct and short. Say what happened and what to do next.
- Don't add trackers, analytics or network calls the user didn't ask for. PageDrop is a quiet tool.
- New dependencies need a reason, and must be GPL-3.0 compatible (and F-Droid friendly: no proprietary SDKs).

## Branches and pull requests

1. Fork the repo and branch from `main` (`fix/pdf-header-strip`, `feat/opds-auth`).
2. Make small, focused commits. One idea per PR is easier to review.
3. Run the unit tests and build before pushing.
4. Open a PR against `main` and fill in the template. Include screenshots for UI changes and say which device you tested on.
5. CI must pass. A maintainer will review; expect questions, not gatekeeping. Push fixes to the same branch.

## Commit messages

Conventional style: `type: short summary in lowercase`.

`feat`, `fix`, `build`, `docs`, `refactor`, `test`, `chore`, `perf`, `ci`. Examples: `feat: capture website book downloads inside PageDrop`, `fix: keep KOReader sidecar when deleting a book`. Add a body when the why isn't obvious.

## Testing with a real Kindle (safely)

Real hardware testing is gold, and it is also how books get lost. Please:

- **Never run tests, scripts or experiments against your real library.** Use throwaway files with obvious names (`pagedrop-test-*.epub`) and delete only those.
- Code that deletes or overwrites on the Kindle must touch only files PageDrop was explicitly told about. Never glob, never recurse into `/mnt/us` roots. Automated tests must use fakes, not a real device.
- Back up `/mnt/us/documents`, `/mnt/us/Books` and your KOReader `settings`/`.sdr` data before trying risky changes.
- Say in the PR which Kindle model, firmware and KOReader version you used. Old devices behave differently from each other, so every data point helps.
- Never paste passwords, Wi-Fi details or Kindle serial numbers into issues or logs.

Thanks for keeping old Kindles alive.
