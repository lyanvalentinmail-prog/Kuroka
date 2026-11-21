# AGENTS.md

For a coding agent — or a person — working in this repository. It says what the project is, how the
code is written, what must not be reintroduced, and how a change is checked before it is pushed.

## What Kuroka is

An anime player for Android, in the style of [Orpheon](https://github.com/kota-zo/Orpheon)
(GPL-3.0, kota-zo) and [Kitsune](https://github.com/Dovakiin0/Kitsune) (MIT, Dovakiin0): a catalog to
search, episodes from several sources, a player of its own, downloads, Cast, subtitles you can style,
and an AniList sign-in for the lists you already keep. No ads, no account of its own, no tracking.

It is **GPL-3.0**. That has consequences for a change:

- Anything taken from Orpheon stays GPL-3.0 and keeps its credit in the file's header comment and in
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- A dependency added to the build has to be compatible with the GPL-3.0, and has to be listed in
  THIRD_PARTY_NOTICES.md with its license.
- A published build has to carry the source, which the public repository is.

## The modules, and what may not cross between them

```
base/
├── api/              the contract: models and interfaces, plain Kotlin
├── sources/          AniList for the catalog, and the providers that have the episodes
├── host-android/     the Android host: db, prefs, library, account, downloads, playback, cast
└── app/              the Compose UI, the player's screens, the settings
```

The rules that keep those four readable:

- **`:api` has no Android in it and no implementation in it.** It is the models and the interfaces a
  screen talks to (`Catalog`, `EpisodeResolver`, `LibraryStore`'s types, `AppSettings`). Adding an
  Android import here is a mistake; adding a `suspend fun` that a host implements is the point.
- **`:sources` has no Android in it either.** It is OkHttp and `org.json`-shaped parsing over
  coroutines, so a provider can be exercised by a JVM test. `Http.kt` is the only place a client is
  built, and `Memo.kt` the only place an answer is remembered.
- **`:host-android` is where the phone is.** A database, a `SharedPreferences` file, the encrypted
  store, a foreground service, a `MediaStore` write, a `NsdManager` search: all of it here, and none
  of it in `:app`. Its public surface is `KurokaHost` plus the objects a screen collects
  (`PlayerHost`, `CastController`, `DownloadRepo`, `LibraryStore`, `AccountRepo`, `Prefs`).
- **`:app` is only Compose over those three.** A screen collects a `StateFlow`, calls a function on a
  view model, and draws. It does not open a database, build an OkHttp client, or reach into a
  provider.

## How the code is written

- **Plain view models over `StateFlow`s.** No Hilt, no Dagger, no Koin. A view model takes what it
  needs as constructor parameters with defaults from `di/Graph.kt`, and `KurokaViewModels` is a
  hand-written factory (no reflection) so a screen can ask for one with
  `viewModel(factory = KurokaViewModels)`.
- **One player, `object PlayerHost`.** There is no `PlaybackSession` and no `Graph.playback`: the
  screen, the mini player, the media-session service and the notification all read the same object's
  `snapshot` and call the same functions. Do not reintroduce a wrapper around it.
- **No image-loading library.** `ui/components/Images.kt` is a hand-written `Posters` cache
  (sampled bitmaps on disk, an OkHttp fetch with the referer a source needs) and `NetworkImage` over
  it. Adding Coil or Glide would be a regression, not an improvement.
- **Comments are prose, and they say why.** Every file opens with a block comment that says what the
  file is, what decided its shape, and — where something was worked from Orpheon or Kitsune — who it
  came from and under what license. It ends with the line
  `This file is part of Kuroka (GPL-3.0, see <relative path>/LICENSE).` A comment inside a function
  explains a decision ("a source that fails three times is left out for five minutes, so a page still
  loads"), not the code it sits on.
- **Names read as the domain does.** `episode`, `bundle`, `resolver`, `shelf`, `chrome`, `cue`. A
  boolean is a sentence (`hasNext`, `isCasting`, `keepScreenOn`), and a function that changes
  something is a verb (`selectServer`, `takeDown` never; `download`, `pause`, `remove`).
- **The app's own words are written in one register.** The UI copy is plain, unhedged English that
  says what a thing does rather than what it is called: "Take the next 5 episodes down", "Go on
  watching", "Ask Wi-Fi for streams", "A source that fails three times is left out for five minutes".
  No exclamation marks, no "Oops", no marketing. A settings row's subtitle explains the consequence of
  the setting, not the setting's name again. Keep new copy in that voice.
- **Kotlin, not Java.** `data class`, sealed interfaces for a state, `?.let`, `takeIf`, expression
  bodies where the body is the point. No `!!` in a screen: a nullable is either defaulted, filtered or
  handled by a `when`.
- **Coroutines for anything that waits.** A `suspend fun` in `:sources` and `:host-android`, a
  `StateFlow` at the boundary to the UI, `viewModelScope` in a view model. A `ListenableFuture` is
  awaited with `suspendCancellableCoroutine` and `kotlin.coroutines.resume` — no Guava listener.

## How a change is checked

There is no emulator in a sandbox, and the build needs a JDK and the Android SDK, so the order is:

1. **Read the host before you call it.** Every `Graph.*`, `PlayerHost.*`, `DownloadRepo.*` and
   `Prefs.*` a new file touches has to be grepped against `:host-android` (or `:api`) first. The
   mistake this repository keeps making is a screen that invents a host function; the check is cheap.
2. **`./gradlew :app:assembleDebug`** from `base/` — the compile is the real test of a UI change.
3. **`./gradlew test`** — the unit tests of `:api`, `:sources` and `:host-android`.
4. **`./gradlew :app:lintDebug`** if the change touched the manifest, the resources or a permission.
5. **Push, and let the workflow build it.** [`.github/workflows/android.yml`](.github/workflows/android.yml)
   compiles the project and publishes installable debug APKs as an artifact of the run; a pull request
   gets the same. That artifact is the way to install a change on a phone without a toolchain.

If a build cannot be run locally, say so in the pull request rather than claiming it was checked.

## Releases

A release is a tag, and the workflow does the rest:

```
v1.4.0          -> Kuroka-1.4.0-ARMv8.apk, -ARMv7.apk, -universal.apk, -TV.apk, checksums.txt
v1.4.0-beta.2   -> Kuroka-Beta-1.4.0-beta.2-… (a pre-release, and the beta application id)
```

- `tools/check_tag.py` turns the tag into a version name and a version code
  (`major*1000000 + minor*10000 + patch*100 + beta`, where a release is 99), and stops a tag that
  would not compare cleanly. Run it before pushing a tag: `python3 tools/check_tag.py v1.4.0`.
- The signing key comes from the repository's secrets (`KUROKA_KEYSTORE` as base64, plus
  `KUROKA_KEYSTORE_PASSWORD`, `KUROKA_KEY_ALIAS`, `KUROKA_KEY_PASSWORD`). **Never** commit a keystore,
  a `keystore.properties`, or a token; `base/keystore/README.md` says where they go on a machine.
- The release's notes are the version's section in [CHANGELOG.md](CHANGELOG.md), so write that
  section before tagging. The heading has to be `## [1.4.0] - …` for the workflow to find it.
- `BuildConfig.UPDATE_REPO` is set from `github.repository` in a release build, which is what makes a
  published app able to check for the next one. A local build leaves it empty and says so.
- A stable release without a key stops rather than publishing a debug-signed APK: Android would take
  it as another app, and a user's data would be stranded.

## Files that are not the app

| Path | What it is |
| --- | --- |
| `LICENSE` | The GPL-3.0 text; every source file points at it. |
| `CHANGELOG.md` | The versions, and the source of each release's notes. |
| `THIRD_PARTY_NOTICES.md` | The libraries, the fonts, the icons, and the two apps Kuroka is worked from. |
| `tools/check_tag.py` | What a release tag means; the release job calls it. |
| `.github/workflows/android.yml` | The debug APKs per push, the tests, and the signed release per tag. |
| `base/keystore/README.md` | Where a release key goes, on a machine and in the CI. |
| `base/licenses/` | The Apache-2.0 text and the two fonts' OFL notices. |

`HANDOFF.md` and `PROMPT-*.md` are working notes and are ignored by git: they stay on the machine.

## Things that were tried and should not be tried again

- **A `PlaybackSession` over the player** — it duplicated `PlayerHost`, drifted from it, and its
  draft imported a class that does not exist. One player object, and a media session over it.
- **Glide (or any image library)** — added once, reverted. The posters are the app's own.
- **A self-designed `CastChannel`** — the ported Orpheon channel takes constructor lambdas, has
  `open()`, `send(destination, namespace, JSONObject)` and exposes `localAddress`. Read it before
  calling it.
- **Assuming a host API from its name** — the repeated failure mode here. Grep `:host-android` first.
