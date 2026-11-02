# LyricDrive

**Synced lyrics for your phone and Android Auto.** Keep listening in Spotify, YouTube Music, Metrolist or another music app—LyricDrive follows the song and shows the words in time.

## Download and install

**[Download latest car APK](https://github.com/IAmCosmin-YT/aa-lyrics/releases/latest/download/LyricDrive-car.apk)** · **[Download latest media APK](https://github.com/IAmCosmin-YT/aa-lyrics/releases/latest/download/LyricDrive-media.apk)**

Android 10 or newer. Download an APK on your phone and open it to install. **You do not need Android Studio or Gradle.** Choose one variant; both use the same app ID.

| Choose | What you get | Android Auto setup |
| --- | --- | --- |
| **Car** | Animated lyrics on their own car surface, with full-screen and beside-Maps modes | Car App Library host and installation restrictions apply. Use a trusted Play installation or a compatible installer setup, such as KingInstaller. |
| **Media** | Current lyrics in Android Auto’s media player, alongside Maps or Waze | Enable Android Auto developer mode and **Unknown sources** for sideloaded media apps. |

Both variants show lyrics on your phone. [All releases and version history](https://github.com/IAmCosmin-YT/aa-lyrics/releases) · [Latest build status](https://github.com/IAmCosmin-YT/aa-lyrics/actions)

The download links always select the latest successfully published build. CI publishes both APKs after tests and builds pass on the default branch. APKs are debug-signed testing builds; updates must use the same signing key. Android may require uninstalling a build signed with a different key, which removes its local data.

## First launch

1. Open LyricDrive and tap **Grant notification access**. Enable LyricDrive in the Android settings screen, then return to the app.
2. Start a song in your music player. Lyrics appear automatically; LyricDrive does not play or record audio.
3. Connect Android Auto, then open LyricDrive on the car display. In the car variant, choose the display mode under **Settings → Android Auto**.

Local audio permission is optional. It lets LyricDrive read lyrics embedded in music files on your phone.

**Samsung / sideloaded apps:** if notification access is greyed out, open **App info → ⋮ → Allow restricted settings**, then try again.

## Lyrics when the phone app is closed

Notification access must remain enabled. If Android stops background lookups, open **Settings → App → Background activity** in LyricDrive:

- In Android’s battery optimization list, choose **All apps → LyricDrive → Don’t optimize**.
- On Samsung, also check **App info → Battery → Unrestricted**.

Android Auto and the notification listener can start LyricDrive without opening the phone UI. Battery settings may help Android keep it running; device restrictions can still affect background execution.

## Make it yours

Settings are organized into four pages:

- **Appearance:** a live preview, nine distinct backgrounds, lyric animations, highlight colors and brightness. Similar album backgrounds are consolidated into **Cover blend**; the old Black option becomes **OLED minimal**.
- **Playback:** automatic, real-only or disabled word timing, plus audio delay for the current output route.
- **Android Auto:** display mode, driving layout and parked full-screen behavior.
- **App:** background activity, permissions, offline cache, optional lyrics server and diagnostics.

Tap a lyric line to seek. Drag to browse; auto-follow resumes after four seconds. Use the sync button for a per-song timing adjustment. Appearance choices apply to both phone and car.

## Offline use and sources

Lyrics are saved automatically for replay without an internet connection. Settings shows how many songs are cached and lets you clear them without resetting your preferences or sync adjustments. The app keeps up to 500 disk lookups and a small memory cache.

Sources include BetterLyrics, Apple Music lyrics through Paxsenix, Unison, LyricsPlus, LRCLIB and embedded file tags. Availability and word timing depend on the song and provider; line-timed songs can use estimated word timing. The app contains no bundled song lyrics.

## Troubleshooting

- **No song detected:** check notification access, start playback in your music app, then return to LyricDrive.
- **No lyrics:** check connectivity and try another track. Cached songs remain available offline.
- **Lyrics stop in the car:** check **App → Background activity** and ensure notification access is still enabled.
- **App closes on launch:** long-press the LyricDrive icon and choose **Recovery**. Share the report or try **Open with safe graphics**.
- **Car variant missing in Android Auto:** check **Settings → Android Auto** for installer information and setup instructions. Android Auto’s Unknown sources switch alone does not unlock Car App Library apps.
- **Changed a car display mode:** reconnect Android Auto for the change to appear.

## For developers

JDK 17+ and Android SDK 35 are required only when building from source:

```sh
python3 tools/check_icu_regex.py  # Native ICU runtime required
./gradlew :core:test
./gradlew :app:assembleCarDebug :app:assembleMediaDebug --max-workers=1
```

[Development and architecture reference](docs/development.md) · [Startup crash investigation](docs/crash-review.md)
