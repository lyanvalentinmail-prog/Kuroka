# Changelog

The versions of Kuroka, newest first. The format is [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the versioning is the one the release workflow reads: a tag is `vX.Y.Z` for a release and
`vX.Y.Z-beta.N` for a beta, and `tools/check_tag.py` turns it into a version code.

The heading of a version is what the release job publishes as that release's notes, so a section
here is a section on the release page.

## [Unreleased]

### Added

- Nothing yet: this is the space the next version is written in.

## [1.0.0] - 2026-11-20

The first build: an anime player that is only the app and the sources it asks.

### Added

- **A catalog from AniList** — trending, this season, the most popular, what airs next, the highest
  rated, the genres, and a search narrowed by genre (wanted or left out), format, airing status,
  season, years, subtitled or dubbed, and sort. The answers a search gets are remembered for an hour,
  so a page comes back without asking again.
- **Episodes from several sources** — the resolver asks the enabled sources in the order you set and
  the first one that has an anime gives its episodes; a sheet switches the source of an anime,
  another switches the season of a story, another the server of one episode. A source that fails
  three times in a row is left out for five minutes, so a page still loads while it recovers.
- **A player of its own** — Media3 under a Compose surface: gestures (a tap for the controls, a
  double tap on either half to jump ten seconds, a drag down the left half for the brightness and
  down the right half for the volume, a drag across to scrub), quality and audio-track switching
  through track overrides, speed, four fit modes, intro and outro skips (by hand or by themselves,
  from the times a source gives), picture-in-picture with the picture's own shape, the audio going on
  in a pocket, and the screen kept awake.
- **Subtitles drawn by the app** — over the picture rather than in the player's own view, which is
  what makes a size in percent, a colour, a box with its own alpha, an edge (outline, shadow or
  nothing), an offset in seconds and a preferred language work on a side-loaded `.vtt`, `.srt` or
  `.ass` file as well as on a track the stream carries. A live sample line is drawn on the page the
  settings are on.
- **Downloads** — an episode written through the same data source the player reads, so the two never
  disagree about headers or subtitles; a queue with its own notification, a pause and a resume per
  file, Wi-Fi-only waiting, a budget in gigabytes, the subtitles taken down with the episode, an HLS
  variant picked to the quality asked for, and a share that hands a finished file to another app as
  a video. Storage: inside Kuroka's own folder, or the device's `Movies/Kuroka` through MediaStore.
- **Cast** — a Chromecast or an Android TV on the local network: the discovery, the session, the
  media messages and the subtitles served from the phone as WebVTT, so a stream that needs a referer
  still plays on the TV. The phone becomes a remote (position, buffer, volume, tracks) and taking the
  episode back resumes it where the TV got to.
- **A library** — the lists, the favourites and the history on the phone, with the numbers they add
  up to (episodes, hours, average stars, the genres you watch most). Signed in to AniList, a pull
  brings your own lists and favourites down and what you watch here goes up; signed out, everything
  is local and kept in the same tables.
- **A sign-in** — AniList's, with a PKCE flow in the browser (so no client secret lives on the phone)
  and the code taken back through the app's own deep link; a pasted personal access token works too,
  and the token goes into the encrypted storage Android provides.
- **Two looks** — Immersive, dark glass over the posters with a floating bottom bar, and Classic,
  Material's own surfaces with a navigation bar; nine accents, the wallpaper's colour as the accent
  (Material You), pure black pages for an OLED phone, ten languages, and the order of the home page's
  rows as a setting.
- **Updates from GitHub** — the app reads its own releases, compares the version, offers the APK that
  matches the phone's ABI, takes it down and hands it to the installer; a beta install is offered
  betas.
- **A schedule** — what airs next, grouped by day, with the hour in the phone's own time zone and how
  far away that is.
- **A first-run setup** — four pages: what the app is, the look and the colour, the downloads, and the
  account (which can be signed in later).
- **A build that publishes itself** — GitHub Actions builds a debug APK per push and per pull request,
  runs the unit tests beside it, and publishes signed APKs (`ARMv8`, `ARMv7`, `universal` and `TV`,
  with SHA-256 checksums) as a GitHub release per tag.
