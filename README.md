# LyricDrive

Real-time synced (karaoke) lyrics on the Android Auto display for whatever is playing in another
app — Spotify, YouTube Music, Metrolist, Deezer, Tidal, local players. LyricDrive never plays
audio; it observes the system media sessions, finds lyrics, and renders them in sync.

## Two car surfaces

Android Auto runs **one navigation app at a time**, and only navigation-category apps get a raw
drawing surface. So LyricDrive ships two complementary car experiences:

| | Media-card mirror (`LyricsMirrorMediaService`) | Full-screen lyrics (`LyricsCarAppService`) |
|---|---|---|
| Runs next to Google Maps / Waze | **Yes** — AA's media card / split-screen rail | No — it occupies the navigation slot |
| What you see | Current lyric line as the "track title", song · artist as subtitle, album art | Animated karaoke view, fluid gradient, word fill |
| Controls | Play/pause/skip/seek forwarded to the real player | Tap a line to seek, swipe/pan to browse, action strip |
| Distribution | Works with Play-Store-style media apps | Navigation category → **sideload + AA developer mode** |

Pick LyricDrive as the media source in AA to get the mirror; open the LyricDrive launcher icon in AA
for the full-screen view.

## Driving behaviour

The lyrics stream is treated as glanceable now-playing metadata: auto-scroll, line transitions and
the word-by-word fill **keep running while the car moves**. LyricDrive does not try to defeat the
host's driver-distraction rules (template limits, restricted-state handling); instead the
full-screen view switches to a glanceable layout when vehicle speed > 0.5 m/s:

- only previous / current / next lines are drawn, no blur, maximum contrast;
- manual browsing (scroll, fling, pan) is disabled and auto-follow is forced back on;
- tap-to-seek on the visible rows and the action-strip transport controls stay available.

Speed comes from `CarHardwareManager` (`CAR_SPEED`). If the host doesn't report it, release builds
assume the car is moving; debug builds assume parked so you can test everything on the DHU.

## Architecture

```
core/   (pure Kotlin/JVM, unit-tested)
  model/      TrackMetadata, PlaybackSnapshot, Lyrics, LyricLine/Word, LyricsState
  lrc/        LrcParser — line + enhanced word timing, repeated tags, [offset:]
  sync/       PlaybackClock (elapsedRealtime interpolation), LyricsTimeline (O(log n) lookups,
              fractional fill), SyncEngine (frame ticker, boundary-sleeping line flow), LyricsEnergy
  match/      TrackNormalizer (YouTube titles, "- Topic"/VEVO, feat., remaster), MatchScorer
  domain/     LyricsRepository (cache → sources, negative cache, transient errors not cached),
              LyricsPipeline (400 ms debounce, flatMapLatest, retry on reconnect + backoff)
  embedded/   EmbeddedLyricsReader — ID3v2.3/2.4 SYLT/USLT, FLAC Vorbis comments

app/    (Android)
  data/media    MediaSessionObserver — MediaSessionManager + OnActiveSessionsChangedListener,
                MediaControllerCompat callbacks, picks the playing session
  data/remote   LrclibClient (/api/get, /api/search), LrclibLyricsSource
  data/local    Room cache (LyricsDatabase, RoomLyricsCache)
  data/embedded MediaStore lookup + core reader
  data/net      ConnectivityObserver
  domain        NowPlayingSession — single shared state (track, lyrics, timeline, clock, palette)
  service       LyricsNotificationListener (grants session access), LyricsMirrorMediaService
  car           CarAppService/Session/Screen, LyricsSurfaceRenderer (Choreographer loop,
                SpringAnimation/FlingAnimation), LyricsCanvasPainter, DrivingStateMonitor
  ui            Compose phone UI: FluidBackground, KaraokeText, LyricsScreen, onboarding
```

**Lyrics priority:** Room cache → word-synced sources: BetterLyrics (`lyrics-api.boidu.dev`, TTML,
then its KuGou endpoint), Unison (`unison.boidu.dev`), LyricsPlus (configurable; defaults to
binimum's server used by his am-lyrics component) → LRCLIB (`https://lrclib.net/api/get` with title, artist, album,
duration; then `/api/search` ranked by similarity/duration/sync) → embedded tags (local files).
Offline lookups show title + artist with an "offline" notice and retry automatically when the
network comes back.

**Sync:** `position = last_reported + (elapsedRealtime − last_update) × speed`, evaluated every
VSync frame (Compose `withFrameMillis`, `Choreographer` on the car surface). Every PlaybackState
change (seek, pause, buffering, speed) re-bases the clock. Tapping a line sends
`transportControls.seekTo()` *and* moves the local clock at once, so the UI jumps without waiting
for the player.

**Visuals:** Palette-API colors from the album art → four drifting radial blobs under a GPU blur
(`RenderEffect` / `Modifier.blur`, API 31+) and a dark scrim. Drift speed scales with lyric
density and slows when paused. Active line: bold, full opacity, glow; other lines 85 % scale,
40–50 % opacity, progressively blurred. The active line is spring-scrolled to the optical center;
word timing produces a continuous left-to-right fill across wrapped lines.

**Touch:** rows are ≥ 64 dp with ripple feedback. Dragging the list pauses auto-scroll; it resumes
4 s after release, or immediately via the "Resume auto-scroll" pill.

## Settings (⚙ in the phone app — applied to the car screen too)

The phone and the car draw with the **same renderer** (`render/`), so every option below looks
identical on Android Auto. The settings screen has a live preview at the top.

- **Theme:** Cover blend (default — blurred cover art in slowly rotating discs that blend into
  each other), album colors (moving / still), blurred album art, Spectrum, Lofi vinyl (grain,
  vignette, spinning record), Disco (cycling neon, spotlights, pulses on sung words), Black (OLED).
- **Animation:** Beautiful (default, inspired by the Beautiful Lyrics Spicetify extension:
  each syllable swells, lifts and glows as a soft gradient sweeps across it, long held notes
  animate letter by letter, instrumental breaks show three filling dots that breathe and shrink
  away before the vocals return), Fluid (soft wipe, sung words float up, held notes swell and glow), Karaoke
  (outlined text, crisp wipe), Bouncing ball, Typewriter (letter by letter), Cinematic (two
  subtitle lines), Glance & drive, Single line (+ progress bar). Fill fronts are soft gradients on
  the glyphs — no clipping rectangles.
- **Highlight color:** Auto (theme), Album opposite (complementary hue), White, Amber, Crimson,
  Ice blue, Emerald.
- **Word timing:** Auto (real word timestamps when the source has them — e.g. TTML or enhanced
  LRC — otherwise estimated), Real only, Off. The header shows which one you're seeing.
- **Brightness:** Auto (car night signal / phone dark mode), Light sensor (phone sensor: day, dim
  for tunnels/overcast, night), Always dark, Always bright.
- **Sync:** audio delay saved per output (Android Auto, Bluetooth, wired, speaker — detected
  automatically) plus per-song nudges from the sync
  button on the lyrics screen. Line-synced lyrics get word timing estimated from syllables at a
  rate measured per song.
- **Android Auto → Full screen when parked:** the cover panel slides in and all lines show when
  the car is parked; it collapses to the 3-line view as soon as you drive.

Disco pulses are rate-limited to 3 per second and toned down while moving. Players don't share
their audio, so Spectrum and Disco follow the lyrics' word onsets, not the actual waveform.

## Permissions

- `INTERNET`, `ACCESS_NETWORK_STATE` — LRCLIB.
- Notification access (`BIND_NOTIFICATION_LISTENER_SERVICE` on the listener service) — this is what
  lets a normal app read other apps' media sessions.
- `MEDIA_CONTENT_CONTROL` is declared but is signature/privileged: only a system-image build gets it.
- `READ_MEDIA_AUDIO` (optional) — embedded lyrics of local files.
- `androidx.car.app.NAVIGATION_TEMPLATES`, `ACCESS_SURFACE`, `CAR_SPEED` — car surface + speed.

## Build

```
./gradlew :core:test          # pure-Kotlin unit tests
./gradlew :app:assembleDebug  # APK in app/build/outputs/apk/debug/
```

Requires JDK 17+ and the Android SDK (compileSdk 35). CI runs both on every push.

## Testing on the Desktop Head Unit (DHU)

1. **Install the DHU:** Android Studio → SDK Manager → SDK Tools → *Android Auto Desktop Head Unit
   Emulator*. It lands in `$ANDROID_SDK_ROOT/extras/google/auto/`.
2. **Install the app** on a phone (or a Google-Play emulator image) with Android Auto:
   `adb install app/build/outputs/apk/debug/app-debug.apk`.
3. **Grant notification access:** open LyricDrive → *Grant notification access*. On Android 13+
   sideloaded apps may show the toggle greyed out: *Settings → Apps → LyricDrive → ⋮ → Allow
   restricted settings*, then toggle it on.
4. **Enable Android Auto developer mode:** Android Auto settings → tap *Version* 10× → ⋮ →
   *Developer settings* → enable **Unknown sources** (required for the sideloaded navigation-category
   app) → ⋮ → **Start head unit server**.
5. **Connect the DHU:**
   ```
   adb forward tcp:5277 tcp:5277
   cd $ANDROID_SDK_ROOT/extras/google/auto/
   ./desktop-head-unit            # add --usb for a USB-connected phone in accessory mode
   ```
6. Start music in Spotify / YouTube Music / Metrolist on the phone.
   - Launcher → **LyricDrive** (full-screen lyrics).
   - Or choose LyricDrive as the media source and open Maps: the media card shows the live line.
7. Useful DHU console commands: `day` / `night`, `restrict on` / `restrict off` (simulate driving
   restrictions), `screenshot out.png`.
8. `adb logcat -s MediaSessionObserver LyricsSurfaceRenderer` for diagnostics.

Real cars and Play-installed Android Auto ignore unknown-source navigation apps unless developer
mode + unknown sources is enabled on the phone.

## Lyrics data

Lyrics come from [LRCLIB](https://lrclib.net) at runtime and are cached locally on the device. The
repository contains no lyrics; test fixtures use invented lines.
