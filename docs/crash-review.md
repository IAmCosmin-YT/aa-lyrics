# Phone startup review

## Confirmed cause: Android ICU regex initialization (fixed in build 23)

The supplied device-log summary identifies PatternSyntaxException / ExceptionInInitializerError in LrcParser and LyricsRepository. Reproducing the original patterns with native ICU 76 rejects the agent marker at offset 20 and fallback tag stripping at offset 26, matching the reported offsets. The same check found a third invalid pattern, the literal `{bg}` marker, rejected at offset 6.

Escaped the closing literal braces in all three patterns, and consistently escaped literal closing square brackets in the affected parser/tag patterns. All 22 production regex literals now compile with native ICU. Desktop JDK regex compilation tolerated the original bare closing braces; the earlier JVM/Robolectric tests therefore did not catch the Android-specific error. Added `tools/check_icu_regex.py` to CI so this compatibility failure is tested with ICU, and added parsing coverage for `{agent:v2}` and timed `{bg}` markers.

Build 23 validation: 52 core tests and 14 app startup/recovery/cache/artwork cases passed, with no failures or skips. Both debug variants built, and the local car-23 APK signature verified. Native ICU reproduction establishes the reported regex failure and correction; installation/startup of build 23 on the physical phone is not yet verified. The Metrolist, Google Search and auditd errors in the user's summary belong to separate processes and are not fixed by these changes.

## Earlier investigation, before the device errors were supplied


Reviewed the application and core source, manifests, build configuration, CI, and changes after the last working car APK.

## Regression range

The GitHub Actions history maps **car-18** to `bf4636dfbcb144b90ae33a56ac080defe58c6a96` (run 41200127927). Build 19 (`d2a8cb9`) replaced the letter animation engine, added six themes and duet/backing-vocal support, and bumped the lyrics-cache schema. Build 20 (`b47b779`) added parser/drawing exception handling. The startup Application and Activity were not changed between builds 18 and 20.

The reported phone is a Samsung S20 Ultra. Its installed Android version and a device crash trace were not available. The exact phone crash has **not been reproduced or conclusively attributed to the animations**. The original source passes the existing render tests, and Application/Activity lifecycle startup without granted permissions passes under Robolectric on APIs 29 and 33. Those checks cannot validate Samsung's hardware graphics driver. Activity lifecycle tests do not assert that the Compose UI renders on a real phone.

## Findings and changes

- **Crash reporting was inaccessible during a launch failure.** The existing report was inside Settings, and Java exception handling cannot catch a native process/GPU crash. Added a native-widget recovery screen, logcat reporting, a pending-Java-crash marker and inspection of Android's previous native-process exit reason (API 30+). A recovery button selects High contrast / Glance / word effects off. An Application initialization exception is exposed to the recovery screen instead of terminating before the Activity can open. Native exit metadata is diagnostic evidence, not proof of a GPU defect.
- **Permission setup drew an animated GPU background before granting access.** Replaced that screen's background with a static Compose gradient. Flat backgrounds now skip the RenderNode blur layer entirely, so the recovery graphics selection avoids that layer.
- **Player artwork bypassed the software decoder.** MediaSession bitmap artwork went directly to Palette and software car-art drawing. Hardware bitmaps do not support CPU pixel reads. Added normalization to software pixels and a maximum 512-pixel dimension before exposing player artwork; the URI artwork cache is now bounded by bytes (8 MB). This is a concrete crash risk, but is not confirmed as this phone's version-19 regression.
- **Caching existed, but was unbounded and invisible.** Room already persisted raw lyrics for offline replay. Added a write-through LRU memory layer (1 MB estimated key/text footprint), transactional retention of the newest 500 disk lookups, cached-song count, clear-cache control, and memory-pressure eviction. Clear-cache removes both lyric cache layers; currently displayed lyrics remain visible. Future lookups may populate the cache again. Existing user preferences and song offsets are preserved.
- **Permission access disappeared after onboarding.** Notification-listener access is a special Android settings grant, not a runtime notification popup. Local audio access is optional and previously only requestable on the onboarding screen. Added a Settings permissions section with current status, audio permission request, notification settings access and app settings access for denied/restricted permissions. Samsung/Android can require “Allow restricted settings” for sideloaded APKs; the app cannot grant that itself.
- **Settings preview animations were not released.** Added disposal of the preview controller when leaving Settings.

## Other observations

The public `AudioManager.getAudioDevicesForAttributes` API used here does not require a runtime permission; Android's framework implementation uses the unprotected routing query. Adding privileged media/audio permissions is not the solution.

Both flavors export Room schemas into the same directory. A parallel build encountered `Empty schema file` while both KSP tasks exported the same schema; serializing validation with `--max-workers=1` avoided the race. This is a build-time issue, not evidence of the installed phone crash.

## Validation and limits

The new tests cover Application/Activity startup on APIs 29 and 33, native recovery-screen selection after a recorded crash, memory cache hits, cache clearing, restoration from disk, recording-duration mismatch, oversized memory entries, and disk retention. Artwork checks cover aspect-ratio-preserving downscaling and compatibility with Palette/software Canvas. Robolectric falls back to software allocation for decoded images even when hardware allocation is requested, so real hardware-bitmap and GPU behavior still need a device test.

The existing all-theme render check exercises each background on phone and car canvas sizes. Full randomized rendering passed on the original source during environment onboarding; the targeted all-theme check is rerun for this change. No live third-party lyrics service or physical Android Auto host was exercised.

If the phone still fails, reopen the updated app and copy the recovery report. A system/native crash should provide an exit reason on Android 11+, while a Java crash should include its stack trace. Logcat remains the definitive source if Android supplies no exit metadata.

Final results: 51 core tests and 10 startup/cache/artwork regression tests passed with no failures or skips. The existing all-theme render test also passed in a separate targeted run. Both car and media debug APKs built successfully with local version code 21. These are local debug builds, not a published GitHub release; their signing key differs from builds produced on GitHub Actions.

## Build 22 follow-up

The user confirmed local car-21 installed through KingInstaller but still exited without a visible report. The phone is attached to their PC, not to the cloud workspace: `adb devices -l` here showed no devices. Device logcat has been requested; the cause remains unconfirmed.

Moved uncaught-exception installation into Application.attachBaseContext (before ContentProvider initialization), added file-backed startup stages and interrupted-launch detection, and deferred AppGraph construction. A new platform-only RecoveryActivity runs in a separate process with hardware acceleration disabled and is reachable through the launcher Recovery shortcut. It can copy/share the saved report and Android exit metadata without initializing Compose or playback. Build 22 and eight startup/recovery cases (API 29 and 33) passed; the APK signature verifies. Physical-device behavior has not been verified.
