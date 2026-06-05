# Karoo → Maverick HUD

A Hammerhead Karoo extension that streams your live ride data to **EverySight
Maverick** smart glasses over Bluetooth and renders it as a heads-up display, so
you never look down. It ships with a Karoo-native settings app to pair the
glasses and configure everything — pages, fields, training zones and your
drivetrain.

> Licensed under Apache 2.0 (see [LICENSE](LICENSE)).

## What it does

- **Glasses HUD** — renders your chosen fields in the Maverick's two edge
  columns (corner-first layout, 2 or 3 rows), keeping the centre clear for the
  road. A small clock and glasses-battery readout live in the corner / control
  window.
- **Karoo-native settings app** — a dark, glove-friendly Jetpack Compose UI in a
  hub-and-detail layout, with a live WYSIWYG preview of the glasses.
- **Live preview on the glasses** — while the settings app is open and the
  glasses are connected, a preview of your current config is mirrored straight
  onto the Maverick so you can see edits in real time.

## Fields

Pick any of these per page (settings → Data Pages):

| Group | Fields |
|---|---|
| Power | Power, Avg, Max, Normalized Power (NP) |
| Heart rate | HR, Avg, Max |
| Cadence | Cadence, Avg |
| Speed & distance | Speed, Avg, Max, Distance |
| Time | Ride, Lap, Last-lap, Interval |
| This lap | Distance, Speed, NP, HR |
| Last lap | Distance, Speed, Power, NP, HR, Cadence |
| Training | Intensity Factor (IF), Variability Index (VI), TSS |
| Drivetrain | Gears, L/R balance |

Power, HR and cadence values are **zone-coloured** (live and lap/avg variants).

## Training zones

Editable in settings (Rider Profile), used to colour the HUD live:

- **Power** — 7 named bands as a % of FTP, drag-free per-zone editors:
  `Z0 Leisure` (cyan) · `Z1 Recovery` (white) · `Z2 Endurance` (green) ·
  `Z3 Tempo` (yellow) · `Z4 Threshold` (orange) · `Z5 VO2 Max` (red) ·
  `Z6 Anaerobic` (purple).
- **Heart rate** — 5-band Coggan model as a % of Max HR.
- **Cadence** — coloured by deviation from your ideal rpm, with an **under-gear
  warning** (turns red when you grind a big gear: low cadence sustained while
  power is in Z3+).

## Gears

The gear field shows the **engaged teeth** (e.g. `50/14`). Teeth come from the
shifting sensor when it reports them (SRAM AXS, Di2); otherwise they're resolved
from the reported gear *position* using your configured drivetrain — pick a real
cassette from the built-in datasheet (SRAM X-Range / XPLR / Eagle, Shimano
11/12-speed) so the position→teeth mapping is exact. If the configured
drivetrain doesn't match the bike, it falls back to the plain gear-position
number. Readout style is selectable: **teeth / ratio / gear inches**.

## Auto workout page

When a structured workout is loaded to the ride, an extra page appears first:
current **power & cadence vs the interval target** (range-coloured), the
interval's **normalized power**, the **step number**, and the **time remaining
in the interval**.

## Settings app

Hub → detail screens:

- **Rider Profile** — FTP, Max HR, ideal cadence and the editable power/HR zones.
- **Gear** — auto-detect or manual drivetrain, cassette & chainring pickers, HUD
  readout style.
- **Data Pages** — a tap-a-slot WYSIWYG editor over a mock of the glasses lens;
  add/remove/reorder pages, 2- or 3-row layouts.
- **Glasses** — pair / connect / disconnect, plus live controls: display on/off,
  brightness, screen X·Y (IPD), and the EvsKit configure/adjust screens.
- **Display & Units** — metric/imperial, page-switching mode, seconds-per-page,
  show-clock and **field-icons** toggles.

## On-glasses & on-Karoo controls

- **Temple-pad gestures** — swipe forward/back to change page; tap opens a centre
  control window where forward/back adjust brightness and a long-tap toggles
  auto-brightness.
- **Karoo data field** — a "Glasses" field mirrors the HUD on the head unit; a
  tap advances the page.
- **Ride-menu action** — "Pair glasses" kicks off pairing mid-ride (GPS is on,
  which the BLE scan needs).
- **In-ride alert** — a one-shot nudge if the glasses drop mid-ride.

## Page modes & refresh

- **Auto-cycle** — rotate pages on a timer (seconds configurable).
- **Follow Karoo** — mirror whatever page you swipe on the head unit.
- **Manual** — switch with the glasses temple pad / data-field tap.

Refresh is locked at **1 Hz** to match the Karoo's native sensor cadence —
ANT+/BLE cycling sensors don't update faster than that.

## Build / install

```sh
# 1. Drop the Everysight developer key in (git-ignored — back it up!):
#    app/src/main/res/raw/sdk.key
./gradlew :app:assembleDebug

# 2. Side-load to the Karoo:
adb connect <karoo-ip>:5555
adb install -r app/build/outputs/apk/debug/maverick-hud.apk

# 3. On the Karoo: Settings → Extensions → enable "Maverick HUD",
#    then launch the app once to pair with the glasses.
```

Unit tests: `./gradlew :app:testDebugUnitTest`.

## Bundled SDKs

| What | Where it lives | Source |
|---|---|---|
| `karoo-ext` 1.1.9 | `karoo-ext/` (local Gradle module) | https://github.com/hammerheadnav/karoo-ext |
| `EvsKit.aar` + `NativeEvsKit.jar` | `app/libs/` | Maverick SDK zip |
| `sdk.key` | `app/src/main/res/raw/sdk.key` (git-ignored) | Everysight developer portal |

## Logs

```sh
adb logcat -s RideHudExtension MaverickBridge HudScreen Evs Timber
```

## Notes

- `sdk.key` is required or the glasses drop the secured link right after
  connecting (`ApiKeyMissing`). It's git-ignored — keep a backup outside the repo.
- On Android 12+, if the Nearby-devices permission was ever *permanently* denied,
  the pair dialog won't reappear — grant it under **Settings → Apps → Maverick
  HUD → Permissions**.
- The rear-cassette position→teeth direction assumes gear position 1 is the
  **largest** cog (and the smallest front ring). Most reporting works off the
  sensor's own teeth values, so this only affects position-only drivetrains.
