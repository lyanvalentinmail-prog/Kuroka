# Kuroka release build: R8 with the rules the libraries do not ship themselves.

# The Cast sender talks to a device over a hand-coded protobuf; nothing reflects on it, but the
# JSON keys it sends are its API, so the message builders stay as they are.
-keep class app.kuroka.host.cast.CastMessage { *; }
-keep class app.kuroka.host.cast.CastProtocol { *; }

# Media3 is read through its interfaces (Player, MediaSession); R8 keeps what the manifests name.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# OkHttp's optional companions (Conscrypt, Bouncy Castle) are not on the classpath.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# org.json is part of Android; the unit tests use the real one on the desktop.
-dontwarn org.json.**

# The player's surface is a SurfaceView Compose wraps: keep the view classes the manifest names.
-keep class app.kuroka.host.playback.KurokaPlaybackService { *; }
-keep class app.kuroka.host.downloads.KurokaDownloadService { *; }
