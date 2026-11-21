# Kuroka

An anime player for Android: a catalog to search, episodes to play, downloads to watch with no
network, subtitles you can actually style, Cast to a TV, and an AniList sign-in for the lists you
already keep. No ads, no account of its own, nothing sent anywhere except the catalog and the source
an episode comes from.

Free software under the **GPL-3.0** — see [LICENSE](LICENSE).

```
Kuroka
├── base/                 the Gradle project
│   ├── api/              the contract: the models and interfaces the UI talks to (plain Kotlin)
│   ├── sources/          AniList for the catalog, and the providers that have the episodes
│   ├── host-android/     the Android host: database, preferences, library, account, downloads,
│   │                     playback (Media3) and Cast
│   └── app/              the app itself: Compose UI, the player's screens and the settings
├── tools/check_tag.py    what a release tag means (version, version code)
└── .github/workflows/    the builds: a debug APK per push, signed APKs per tag
```

The split is the point: `:api` has no Android in it, `:sources` has no Android in it either, and
everything a phone provides — a database, a player, a foreground service, a file — sits in
`:host-android`. `:app` is only Compose over those three, so a screen can be read without knowing
how an episode is fetched, and a fetch can be tested without a screen.

## Install it

Kuroka is not on a store, so the builds come from GitHub Actions.

**From a workflow run** — the way to install the current `main`:

1. Open the [Android workflow](../../actions/workflows/android.yml) and pick the run you want
   (the newest one on `main` is the current code).
2. Under **Artifacts**, take `Kuroka-debug-<commit>`.
3. Unzip it and install one APK on the phone:

| APK | For |
| --- | --- |
| `Kuroka-debug-ARMv8.apk` | almost every phone since 2017 (arm64) |
| `Kuroka-debug-ARMv7.apk` | an older or 32-bit phone |
| `Kuroka-debug-universal.apk` | anything, and an Android TV |

Those are debug builds signed with the debug key; they install as `app.kuroka.dev` ("Kuroka dev")
next to a release install. The phone asks to allow installs from unknown sources the first time.

**From a release** — the signed ones:

Every tag (`v1.4.0`, or `v1.4.0-beta.2` for a beta) publishes
[Kuroka-\<version\>-ARMv8|-ARMv7|-universal|-TV.apk](../../releases) with their SHA-256 checksums.
A release install is `app.kuroka` ("Kuroka"), and an install over an earlier release keeps its data,
because every release is signed with the same key. A release also checks GitHub for a newer one
(Settings, Updates) and offers to take it down and install it.

**Build it yourself:**

```sh
cd base
./gradlew :app:assembleDebug                 # three debug APKs, one per ABI plus the universal one
./gradlew :app:assembleRelease :app:collectApks \
    -PappVersionName=1.4.0 -PappVersionCode=1040099 -PupdateRepo=owner/Kuroka
```

`collectApks` puts the release APKs, named as above, in `base/app/build/release-apks/`. A release
build signs with `base/keystore/keystore.properties` when it is there, or with the
`KUROKA_KEYSTORE*` environment variables the CI sets ([base/keystore/README.md](base/keystore/README.md));
with neither, a stable release stops rather than publishing a debug-signed APK.

## What it does

- **A catalog** — AniList's: trending, this season, the most popular, what airs next, the highest
  rated, the genres, and a search narrowed by genre, format, airing status, season, years, sub or
  dub and sort.
- **Episodes from several sources** — the resolver asks them in the order you set and the first one
  that has an anime gives its episodes; a sheet switches the source, another switches the season of
  a story, another the server of one episode. A source that fails three times is left out for five
  minutes, so a page still loads.
- **A player of its own** — Media3 under a Compose surface, with gestures (tap, double tap to jump
  ten seconds, drag down either half for the brightness and the volume, drag across to scrub),
  quality and audio track switching, speed, four fit modes, intro and outro skips (by hand or by
  themselves), picture-in-picture, and the audio going on in a pocket.
- **Subtitles that are yours** — drawn by the app over the picture rather than by the player's own
  view, so a size in percent, a colour, a box with its own alpha, an edge and an offset in seconds
  all work on a side-loaded `.ass` file as well as on a stream's own track.
- **Downloads** — an episode on the phone plays with no network at all; the subtitles come down with
  it, a finished file can be handed to another app, and the queue waits for Wi-Fi if you ask it to.
- **Cast** — a Chromecast or an Android TV with one: the episode goes to the TV over the local
  network (with its headers and its subtitles served from the phone), the phone becomes a remote,
  and taking the episode back resumes it where the TV got to.
- **A library** — the lists, the favourites and the history on the phone, and AniList's own when you
  sign in: a pull brings them down, what you watch here goes up.
- **Two looks** — Immersive, dark glass over the posters, and Classic, Material's own surfaces; nine
  accents, the wallpaper's colour instead, and pure black pages for an OLED phone.

## Signing in

The sign-in is AniList's, with a PKCE flow in the browser, so no client secret lives on the phone and
the token goes into the encrypted storage Android provides. A build with no AniList client id (the
default, since the id is not in the repository) offers the token sign-in instead: paste a personal
access token from AniList's own settings and everything works the same.

Give a build your own id with `-PanilistClientId=…` (and `-PanilistRedirectUri=…` if you changed the
scheme), or in `~/.gradle/gradle.properties` as `anilistClientId=…`.

Everything works signed out: the lists, the history and the favourites stay on the phone.

## What it is not

Kuroka plays what public third-party sources give it. It hosts no video, has no relation with those
sources, and whether an anime may be watched that way where you are is not the app's to decide —
nothing here is a substitute for the services that license it.

## Where the shape comes from

Two apps, both open, decided how this one is built:

- **[Orpheon](https://github.com/kota-zo/Orpheon)** by kota-zo (GPL-3.0) — the look of a player and
  its controls, the Cast protocol, the source order, the release workflow and the version codes.
  Kuroka is GPL-3.0 partly for that reason.
- **[Kitsune](https://github.com/Dovakiin0/Kitsune)** by Dovakiin0 (MIT) — drawing the player's own
  video element and subtitles rather than using a widget's, the gestures, and the episode-source
  handling.

Both are credited in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), with the libraries the build
uses and their licenses.

## The build

Java 21 for the CI (17 is the language level), Gradle 9.8.2 through the wrapper in `base/`, AGP
9.4.1, Kotlin 2.4.21, `compileSdk` 37, `targetSdk` 36, `minSdk` 29, Media3 1.8.0, OkHttp 5.5.1,
coroutines 1.11.0. The versions live in one place: [base/gradle/libs.versions.toml](base/gradle/libs.versions.toml).

There is no image-loading library and no dependency-injection framework: the posters are fetched and
cached by the app itself (`ui/components/Images.kt`), and the objects a screen needs come from one
locator (`di/Graph.kt`). View models are plain `ViewModel`s over `StateFlow`s.

[AGENTS.md](AGENTS.md) says how the code is written and how a change is checked;
[CHANGELOG.md](CHANGELOG.md) has the versions.
