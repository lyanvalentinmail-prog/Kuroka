# Third-party notices

Kuroka is free software under the **GPL-3.0** (see [LICENSE](LICENSE)). It is built on libraries that
are free software too, and its shape is worked from two open apps. This file says what is used, under
what license, and where the text of each license is.

## The apps Kuroka is worked from

| App | Author | License | What came from it |
| --- | --- | --- | --- |
| [Orpheon](https://github.com/kota-zo/Orpheon) | kota-zo | GPL-3.0 | The player's look and its controls, the Cast protocol (discovery, session, media messages and the channel), the source order and its failure handling, the release workflow, the version codes and the shape of the settings. |
| [Kitsune](https://github.com/Dovakiin0/Kitsune) | Dovakiin0 | MIT | Drawing the player's own video element and subtitles rather than using a widget's, the gestures over the picture, the episode-source handling and the idea of a subtitle offset and fit mode as settings. |

Orpheon is GPL-3.0, the same license as Kuroka, so what was taken from it stays under it; the Cast
files that were ported keep their original headers, and this file keeps the notice. Kitsune is MIT,
which allows use in a GPL-3.0 work with its copyright notice kept:

> MIT License — Copyright (c) Dovakiin0
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
> associated documentation files (the "Software"), to deal in the Software without restriction,
> including without limitation the rights to use, copy, modify, merge, publish, distribute,
> sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial
> portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
> NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
> NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES
> OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
> CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

Neither project endorses Kuroka, and neither is responsible for it.

## The catalog

| Service | Terms | What it provides |
| --- | --- | --- |
| [AniList](https://anilist.co) | [AniList's API terms](https://anilist.gitbook.io/anilist-apiv2-docs/) | The catalog: titles, posters and banners, genres, formats, airing times, relations, recommendations and — when you sign in — your own lists and favourites. |

Kuroka hosts none of this content and is not affiliated with AniList. The episode streams come from
public third-party services, listed in the app under Settings, Sources; Kuroka hosts no video and has
no relation with those services.

## The libraries

Every one of these is a dependency of the build, declared in
[base/gradle/libs.versions.toml](base/gradle/libs.versions.toml).

| Library | License | Used for |
| --- | --- | --- |
| [Kotlin](https://kotlinlang.org) (stdlib, coroutines) | Apache-2.0 | The language, and the `suspend` functions and `Flow`s the sources and the host are written in. |
| [AndroidX](https://developer.android.com/jetpack/androidx) — core, appcompat, lifecycle, navigation, palette, profileinstaller | Apache-2.0 | The activity, the view-model and navigation plumbing, the palette of a poster, the startup profile. |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) — ui, foundation, material3, material-icons-extended | Apache-2.0 | Every screen, the two looks, and the subtitles drawn over the picture. |
| [Media3](https://github.com/androidx/media) — ExoPlayer, HLS, the session, the UI | Apache-2.0 | The player: the streams (progressive and HLS), the track selection, the media session and the notification. |
| [OkHttp](https://square.github.io/okhttp/) | Apache-2.0 | Every HTTP request: the catalog, the providers, the posters, the Cast messages and the downloads. |
| [org.json](https://github.com/stleary/JSON-java) | Public domain (as published by the JSON license) | Parsing on the JVM side, in the tests. Android provides its own `org.json` at runtime. |
| [JUnit 5](https://junit.org/junit5/) | Eclipse Public License 2.0 | The unit tests of `:api`, `:sources` and `:host-android`. |
| [Gradle](https://gradle.org) and the [Android Gradle plugin](https://developer.android.com/build) | Apache-2.0 | The build itself, the wrapper in `base/gradle/wrapper/`, and the ABI splits. |

The Apache License 2.0 text is in [base/licenses/Apache-2.0.txt](base/licenses/Apache-2.0.txt).

## The icons and the fonts

| Asset | License | Where it is |
| --- | --- | --- |
| [Material Symbols](https://fonts.google.com/icons) (the Rounded set) | Apache-2.0 | The icons of the app, through `material-icons-extended`. |
| [Inter](https://github.com/rsms/inter) | SIL Open Font License 1.1 | The app's text face — `base/app/src/main/res/font/inter.ttf`, license in `base/licenses/Inter-OFL.txt`. |
| [Manrope](https://github.com/sharanda/manrope) | SIL Open Font License 1.1 | The display face of the headings — `base/app/src/main/res/font/manrope.ttf`, license in `base/licenses/Manrope-OFL.txt`. |

Both fonts are bundled in the APK, so the app reads the same on every phone; the OFL allows that as
long as the fonts are not sold by themselves and the notices travel with them, which this file does.

## What is deliberately not here

- **No image-loading library** — the posters are fetched, sampled and cached by the app itself
  (`base/app/src/main/kotlin/app/kuroka/ui/components/Images.kt`), which is why no Coil, Glide or
  Fresco notice appears above.
- **No dependency-injection framework** — no Hilt, Dagger or Koin: one locator, `di/Graph.kt`.
- **No ads, no analytics, no crash reporting, no third-party tracker** — there is nothing to declare,
  and the app sends nothing anywhere except the catalog and the sources an episode comes from.
