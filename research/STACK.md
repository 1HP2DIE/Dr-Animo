# Animo — Locked Android Stack (verified against Maven + local cache)

Written from a verified research pass. Every version below was confirmed to exist on Maven
(HTTP 200 on its POM) **and** is compatible with this machine.

## Machine facts (verified)

| Fact | Value |
|---|---|
| JDK | **21 only** (`/usr/lib/jvm/java-21-openjdk-amd64`) — no JDK 17 present |
| Gradle | 8.11 (wrapper generated in repo) |
| AGP | 8.7.3 (cached) |
| Kotlin | 2.0.21 (cached) |
| SDK platforms | android-33, android-35, android-36 |
| Build tools | 33.0.2, 34.0.0, 35.0.0 (no 36) |

## Versions

| Coordinate | Version | Note |
|---|---|---|
| `com.android.application` | 8.7.3 | cached; supports compileSdk 35, **not 36** |
| `org.jetbrains.kotlin.android` | 2.0.21 | cached |
| `org.jetbrains.kotlin.plugin.compose` | 2.0.21 | **required** for Kotlin 2.x compose |
| `org.jetbrains.kotlin.plugin.serialization` | 2.0.21 | |
| `com.google.devtools.ksp` | **2.0.21-1.0.28** | must match Kotlin exactly |
| `androidx.compose:compose-bom` | **2024.12.01** | resolves ui/foundation/runtime 1.7.6, material3 1.3.1, icons-ext 1.7.6 — matches cache |
| `androidx.media3:*` | **1.5.1** | exoplayer, exoplayer-hls, ui, session, datasource-okhttp |
| `androidx.room:*` | **2.7.2** | Room ≤2.6.1 breaks on Kotlin 2.0 metadata |
| `androidx.navigation:navigation-compose` | 2.8.5 | |
| `androidx.work:work-runtime-ktx` | 2.10.0 | |
| `androidx.datastore:datastore-preferences` | 1.1.1 | |
| `io.coil-kt.coil3:coil-compose` / `coil-network-okhttp` | 3.0.4 | |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | cached; matches media3 1.5.1 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.7.3 | cached |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.9.0 | cached |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.8.7 | cached |
| `androidx.activity:activity-compose` | 1.9.3 | cached |
| `androidx.core:core-ktx` | 1.15.0 | cached |

**SDK levels:** `compileSdk = 35`, `minSdk = 24`, `targetSdk = 35`, `buildToolsVersion` **omitted**.

## Hard rules (each one is a known build-breaker)

1. **No `kotlin.jvmToolchain(17)`** — only JDK 21 installed → `No matching toolchains found`.
   Use `compileOptions{ source/target = VERSION_17 }` + `kotlin{ compilerOptions{ jvmTarget = JVM_17 } }`.
2. **Compose compiler plugin must be applied** in root (`apply false`) and app module.
3. **KSP pinned exactly** to `2.0.21-1.0.28`; never `+`.
4. **compileSdk stays 35**; never add build-tools 36.
5. **Room 2.7.2**; if it demands compileSdk 36, fall back to DataStore+JSON, do **not** bump SDK.
6. `media3-ui-compose:1.5.1` does not exist → use `media3-ui` `PlayerView` inside `AndroidView`.
7. No Hilt — manual `AppContainer` + `staticCompositionLocalOf`. No Retrofit — OkHttp + kotlinx.serialization (needs per-request header control).

## Playback header strategy (the core requirement)

`OkHttpDataSource.Factory.setDefaultRequestProperties` — javadoc: *"Sets the default request
headers for HttpDataSource instances created by the factory. The new request properties will be
used for future requests made by HttpDataSources created by the factory, including instances that
have already been created."* → applies to **multivariant playlist, media playlist, and every
segment**. Native playback works; no WebView needed.

```kotlin
@OptIn(UnstableApi::class)
fun hlsFactory(referer: String, client: OkHttpClient): HlsMediaSource.Factory {
    val ds = OkHttpDataSource.Factory(client)
        .setUserAgent(UA_DESKTOP_CHROME)
        .setDefaultRequestProperties(
            mapOf("Referer" to referer, "Origin" to referer.trimEnd('/'), "Accept" to "*/*")
        )
    return HlsMediaSource.Factory(ds).setAllowChunklessPreparation(true)
}
```

**One factory per playback, never a singleton** — `setDefaultRequestProperties` mutates the factory
and is inherited by already-created instances, so a shared factory leaks host A's Referer to host B
(→ 403) and races between simultaneous playbacks.

Retry/buffer for flaky mirrors: `DefaultLoadControl.Builder().setBufferDurationsMsForStreaming(15_000, 60_000, 2_000, 5_000)`
plus a `LoadErrorHandlingPolicy` allowing ~5 exponential-backoff retries.
Re-resolve trigger: catch `HttpDataSource.InvalidResponseCodeException` and read `responseCode`
(public field) → on 403/410 invalidate the cached manifest, re-resolve, swap `MediaItem` at the
current position.

## Cleartext

`targetSdk 35` blocks cleartext by default. Do **not** set `usesCleartextTraffic`. At integration
time run `grep -o 'URI="http://[^"]*' manifest.m3u8`; if any segment host is http, add a scoped
`res/xml/network_security_config.xml` `<domain-config cleartextTrafficPermitted="true">` for that
host only.

## Manifest essentials

`INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
`POST_NOTIFICATIONS`, `WAKE_LOCK`.
`<service android:name=".player.PlaybackService" android:exported="true" android:foregroundServiceType="mediaPlayback">`
with action `androidx.media3.session.MediaSessionService`.
Player activity: `configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden"`.

## Build gate order

1. Minimal Compose-only skeleton → `./gradlew assembleDebug` (proves AGP/Kotlin/Compose/KSP quartet).
2. Add Room + KSP → build.
3. Add Media3 → build.
Do not mix all three into the first build failure.
