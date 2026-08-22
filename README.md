# Island Pill

A Dynamic-Island-style overlay for Android. Personal build — no Play Store constraints.

Verified working on **Realme GT Neo2 (RMX3363)**, Android 13 / Realme UI V13.1.0,
1080x2400 @ density 408, punch-hole top-left (150x107 px). Built and installed over USB ADB.

---

## Build

Android Studio's bundled JBR is **JDK 25**, which forces Gradle 9.x. Building from Git Bash:

```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

Stack: Gradle 9.7.1 / AGP 9.3.1 / Kotlin 2.4.10 / compileSdk 37 / Compose BOM 2026.08.00.
Note AGP 9 has **built-in Kotlin support** — applying `org.jetbrains.kotlin.android` is a hard error.

`local.properties` must use forward slashes; backslashes parse as properties escapes and fail with
a bare `IOException: Invalid file path`.

## Install & permissions

Play Protect blocks the APK if you tap it in a file manager. Install over ADB instead:

```bash
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

Then in the app grant, in this order:

1. **Accessibility overlay** — *not optional*, see below
2. **Display over other apps**
3. **Notification access**
4. **Unrestricted battery** (ColorOS kills overlays otherwise)

Finally flip **Island enabled**, then press **Auto-align pill over the hole**.

> On ColorOS, `appops set`, `pm grant` and `settings put secure` are all refused from `adb shell`
> unless **Disable permission monitoring** is on in Developer options. Turning that toggle on also
> resets USB debugging, so re-enable it afterwards.

---

## What is implemented

| Layer | File | Status |
|---|---|---|
| Overlay window, edge-anchored so a corner hole grows inward | [OverlayHost.kt](app/src/main/kotlin/com/nahope/island/service/overlay/OverlayHost.kt) | ✅ |
| Accessibility host — the only way above the status bar | [IslandAccessibilityService.kt](app/src/main/kotlin/com/nahope/island/service/IslandAccessibilityService.kt) | ✅ |
| Foreground service: data sources + fallback window | [IslandService.kt](app/src/main/kotlin/com/nahope/island/service/IslandService.kt) | ✅ |
| Punch-hole auto-detection + one-tap alignment | [CutoutDetector.kt](app/src/main/kotlin/com/nahope/island/util/CutoutDetector.kt) | ✅ |
| Compose inside a non-Activity window | [OverlayLifecycleOwner.kt](app/src/main/kotlin/com/nahope/island/service/overlay/OverlayLifecycleOwner.kt) | ✅ |
| State machine: idle / minimal / compact-split / expanded | [IslandRepository.kt](app/src/main/kotlin/com/nahope/island/island/IslandRepository.kt) | ✅ |
| Spring morph animation, waveform, expanded card | [IslandOverlay.kt](app/src/main/kotlin/com/nahope/island/ui/island/IslandOverlay.kt) | ✅ |
| Media: art, title, transport, scrub position | [MediaSource.kt](app/src/main/kotlin/com/nahope/island/island/sources/MediaSource.kt) | ✅ |
| Charging / silent-mode / Bluetooth buds (+ battery via reflection) | [SystemSource.kt](app/src/main/kotlin/com/nahope/island/island/sources/SystemSource.kt) | ✅ |
| Notification intercept | [IslandNotificationListener.kt](app/src/main/kotlin/com/nahope/island/service/IslandNotificationListener.kt) | ✅ |
| X/Y/W/H/radius calibration + permission onboarding | [MainActivity.kt](app/src/main/kotlin/com/nahope/island/MainActivity.kt) | ✅ |
| Boot persistence | [BootReceiver.kt](app/src/main/kotlin/com/nahope/island/service/BootReceiver.kt) | ✅ |

## Not built yet (next phases)

- **Calls** — incoming/ongoing call pill. Needs `READ_PHONE_STATE` or an `InCallService`.
- **Turn-by-turn navigation** — parse the Google Maps ongoing notification.
- **Timers / screen recording / voice memo** — same notification-parsing path.
- **RGB edge glow** around the cutout.
- **Idle camera-hole shortcuts** (tap = torch, double-tap = screenshot) — the AccessibilityService
  is already in place, so this is now just wiring up `performGlobalAction`.
- **AGSL metaball shader** for the gooey split/merge (API 33+ only).
- Swipe-in / swipe-out to hide and restore a live activity.

## Gotchas already handled

- The overlay window is `WRAP_CONTENT` while the island is idle, so only the pill itself takes
  touches. The moment anything lands on it the window goes full width and Compose takes over
  horizontal placement — that is what lets the pill glide to screen centre as it expands.
  A `WRAP_CONTENT` window derives its measure ceiling from its own current frame, so if anything
  clamps it narrow it can never grow back: the card silently stays whatever width it managed at
  that instant. Full width has no ceiling.
  Trade-off: while an event is showing, a full-width strip the height of the pill consumes touches.
  `ViewTreeObserver.TOUCHABLE_INSETS_REGION` would narrow that back to the pill, but it is @hide.
- `FLAG_WATCH_OUTSIDE_TOUCH` collapses the expanded card when you tap elsewhere.
- Transient events (charging, silent, buds) freeze their dismiss timers while the card is expanded
  and re-arm on collapse.
- Media session access is gated on the NotificationListenerService actually being bound, so the
  service rebinds when you grant notification access later.
- `specialUse` foreground-service type with a declared subtype — required from Android 14.
- **The status bar outranks ordinary overlays.** On this device `TYPE_APPLICATION_OVERLAY` sits at
  layer 111000 and the status bar at 151000, so the pill renders *behind* it and is invisible.
  `TYPE_ACCESSIBILITY_OVERLAY` lands at 631000 — hence the AccessibilityService.
- Compose resolves its recomposer from `view.rootView`, so the ViewTree owners must be set on the
  window's root view. Setting them on the ComposeView throws `ViewTreeLifecycleOwner not found`.
- Enabling the island writes a pref *and* starts the service; the write has to be awaited first,
  or the service reads `enabled=false` on its first emission and stops itself.
- The data sources live in the **AccessibilityService**, not the foreground service. ColorOS does
  not restart the foreground service after a reinstall or a low-memory kill, which left the island
  on screen but blind. The system always rebinds an accessibility service.
- `SourceSet.bindMedia()` remembers the notification-access flag instead of acting on it, because
  the flow emits its current value before `start()` has run.
- Calibration sliders write to DataStore only on release; while the finger is down they publish to
  `IslandPreview`, an in-memory config the overlay prefers. The two expanded sliders also open the
  card on first touch so it can be sized against something real.
- Not every app fills in media metadata — MX Player publishes a session with `description=null`.
  The title falls back to the app label so the pill is never blank.
- Progress is extrapolated from `PlaybackState.lastPositionUpdateTime`, not from when we happened
  to read the state; otherwise a stale position races ahead of the track.
