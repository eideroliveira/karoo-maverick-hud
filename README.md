# Karoo → Maverick HUD

A Hammerhead Karoo extension that streams live ride data to Everysight Maverick
smart glasses over Bluetooth, rendering selected fields as a two-page HUD.

## Layout

```
Page 1                    Page 2
┌──────────┬──────────┐   ┌──────────┬──────────┐
│  POWER   │   CAD    │   │   DIST   │   AVG    │
├──────────┼──────────┤   ├──────────┼──────────┤
│   L/R    │  SPEED   │   │    HR    │   TIME   │
└──────────┴──────────┘   └──────────┴──────────┘
```

Pages auto-cycle every 5 s by default (configurable in settings).

## Refresh rate

Locked at 1 Hz to match the Karoo's native sensor cadence. No point pulling
faster — ANT+/BLE cycling sensors don't update more often than that.

## Build / install

```sh
# 1. Drop the Everysight developer key in:
#    app/src/main/res/raw/sdk.key
#    (already present in this checkout)
./gradlew :app:assembleDebug

# 2. Side-load to the Karoo:
adb connect <karoo-ip>:5555
adb install -r app/build/outputs/apk/debug/maverick-hud.apk

# 3. On the Karoo: Settings → Extensions → enable "Maverick HUD"
#    Then launch the app once to pair with the glasses.
```

## Bundled SDKs

| What | Where it lives | Source |
|---|---|---|
| `karoo-ext` 1.1.9 | `karoo-ext/` (as a local Gradle module) | https://github.com/hammerheadnav/karoo-ext |
| `EvsKit.aar` + `NativeEvsKit.jar` 2.5.0 | `app/libs/` | Maverick SDK 2.6.1 zip |
| `sdk.key` | `app/src/main/res/raw/sdk.key` | Everysight developer portal |

## Logs

```sh
adb logcat -s RideHudExtension MaverickBridge HudScreen Evs Timber
```

## Known v1 gaps

- Pairing UI is whatever EvsKit's `connectSecured()` provides — needs validation
  on the Karoo's Android 12/13 build.
- `FOLLOW_KAROO` and `MANUAL` page-switch modes are stubbed.
- The bridge polls `comm().isConnected` every 2 s. Once we confirm the public
  callback API (`IEvsGlassesEvents.registerGlassesEvents`), swap polling for the
  event listener.
- Glasses battery / low-battery alerts not wired yet.
