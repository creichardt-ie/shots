# Shots — espresso by the gram

A native Kotlin/Compose app for an Acaia scale and a La Marzocco Linea Micra.
The launcher label is **Shots**; `applicationId` stays `com.coffeeapp.droid`,
because changing it would make Android treat this as a different app and force
an uninstall, taking the database and the stored credentials with it.

It began as a port of a Raspberry Pi app, now retired; nothing of the Pi app or
its Python is part of this project.

## Building

Open the `android/` folder in Android Studio. Then:

```
gradlew test            # 223 unit tests, no device needed
gradlew assembleRelease # the build for everyday use
gradlew assembleDebug   # debuggable build, for run-as access to the app's files
```

Without Android Studio, set `ANDROID_HOME` to the SDK, and `JAVA_HOME` to a
JDK 21 (Android Studio's bundled `jbr` will do).

With `~/.android/shots-release.properties` present (the maintainer's
release key, never in the repo) `assembleRelease` signs with that key; see
`app/build.gradle.kts`. Otherwise both builds are signed with **your PC's debug key**
(`~/.android/debug.keystore`, created by the Android tooling), so either
installs over the other with `adb install -r` and keeps every byte of data.
Keep using the same PC (or copy that keystore): a build signed with a
different key cannot update the installed app, and the uninstall it forces
loses settings, the learned offset, the login and the BLE token (history
alone comes back from a backup). No keystore is, or should ever be,
committed to this repository. The release build is not debuggable, so
`run-as` is refused: the logs are written to
`Android/data/com.coffeeapp.droid/files/logs/` and read with
`adb shell cat` / `adb pull`.

`minSdk 26`, `targetSdk 36`, `compileSdk 37`, Kotlin 2.4, Compose BOM 2026.08,
AGP 9.5, Gradle 9.7.

The Gradle daemon JVM is pinned by `gradle/gradle-daemon-jvm.properties` to
JetBrains JDK 21, so the IDE and the command line share one daemon instead of
spawning two. AGP 9 compiles Kotlin itself (built-in Kotlin), so there is no
`org.jetbrains.kotlin.android` plugin; the Compose and serialization compiler
plugins are still applied explicitly and still read `kotlin` from the version
catalog.

## Module map

| Kotlin | What it does |
|---|---|
| `core/CoffeeRuntime.kt` + `core/CoffeeService.kt` | Single-threaded runtime that owns all hardware; foreground service keeps it alive |
| `ble/ScaleGattClient.kt` + `ble/ScaleProtocol.kt` | Scale GATT client for any brand (queue, retries, cooldown, rate log) and the per-brand protocol seam; was `AcaiaScaleClient` until 2.16.0 |
| `ble/AcaiaProtocol.kt` | Acaia frame codec and `AcaiaScaleProtocol` (ported from `aioacaia`; decoding from 0.2.3) |
| `ble/BookooProtocol.kt` | Bookoo Themis Ultra protocol and `BookooScaleProtocol` (from the maker's MIT spec) |
| `ble/ScaleAdapter.kt` | Scale lifecycle: connect, sleep/wake, idle disconnect |
| `core/shot/ShotStateMachine.kt` | Shot lifecycle and brew-by-weight safety |
| `core/shot/PlacementDetector.kt` | Vessel detection for auto-tare |
| `core/shot/ShotService.kt` | Ties scale samples, shot logic, auto-tare and machine calls together; stop retries, scale-lost handling |
| `core/shot/ShotPorts.kt` | `ScaleSource` / `ShotSink`: what ShotService needs, so it runs against fakes in tests |
| `core/cloud/CloudClient.kt` + `core/cloud/InstallationKey.kt` | La Marzocco cloud API and request signing (ported from `pylamarzocco`) |
| `core/cloud/CloudParsing.kt` | Pure reading of the dashboard and settings JSON |
| `core/cloud/CloudProblem.kt` | Why the cloud is failing (policy, refused, sign-in, network) and the warning text |
| `ble/MicraProtocol.kt` + `ble/MicraBleClient.kt` | Micra BLE protocol and link (ported from `pylamarzocco`, updated to 2.5.6) |
| `ble/MicraShotCounter.kt` | The Micra's BLE shot counter: brewing events and telemetry, parsed and tracked |
| `core/machine/MachineController.kt` | BLE/cloud routing |
| `core/machine/BoilerReadiness.kt` | Steam heating/ready from live BLE readings |
| `core/db/` | Room database: shots, beans |
| `core/config/ConfigStore.kt` + `SecretsStore.kt` | Settings (DataStore) and credentials (EncryptedSharedPreferences) |
| `core/log/FileLog.kt` | File + logcat logging |
| `core/backup/DocumentsBackup.kt` | Database and log mirror in `Documents/CoffeeApp/` |
| `ui/screens/*.kt`, `ui/CoffeeApp.kt` | Compose screens, status bar, vessel-driven navigation |

The single-threaded dispatcher in `CoffeeRuntime` is load-bearing: `ShotService`, `ScaleAdapter` and the
placement detector are all touched by exactly one thread and carry no
synchronisation of their own. Nothing outside the runtime should create its own
scope for hardware work.

## Three things Android forces

**One GATT operation at a time.** Android permits exactly one outstanding GATT
operation per connection and silently drops the rest — no error, no callback,
the write simply never happens. Every write goes through a serialised queue in
`ScaleGattClient`, mirroring `aioacaia`'s `process_queue` including the 100 ms
spacing. `MicraBleClient` does the same with a mutex, and runs each exchange
as its own job so a caller that times out (the BBW stop) abandons only its
wait, never an operation Android still has in flight.

**A foreground service.** An Android app that loses foreground has its BLE notifications throttled and can be killed
outright — which, mid-shot, would strand brew-by-weight with the machine still
pouring. `CoffeeService` runs with `foregroundServiceType="connectedDevice"`.

**Characteristics resolved across all services.** The Acaia Umbra exposes the
standard protocol on `fe41`/`fe42`, which sit under a vendor service, so the
usual `getService().getCharacteristic()` lookup cannot find them.
`findCharacteristic` scans every discovered service. A new-style Acaia
without the standard pair is tried on `fe41`/`fe42` next
(`ScaleProtocol.alternateChars`), so Pyxis, Lunar 2021 and Umbra all connect
with no UUIDs configured. The Micra does not
publish its service UUID either, so `MicraBleClient` resolves the same way.

## Machine control: Bluetooth first, cloud as fallback

Off by default. Settings → **Machine Bluetooth**: scan, pick the machine,
switch on Local control, SAVE. The app then connects to the Micra
in short sessions: during a shot (HIGH priority, 2 s poll, so the stop lands
in ~50 ms), from the dosing cup landing until the shot (PREP, BALANCED, 10 s
poll, up to 10 min; 2.9.0) and for RECONNECT NOW - nowhere else - released
cleanly 2 minutes after (`MicraBleClient.RELEASE_AFTER_MS`). Long, busy holds
were followed by the Micra's BLE wedging until power-cycled. The Micra has
one BLE slot, so the official La Marzocco app cannot connect while it is held
(its cloud features still work).

| Call | Route |
|---|---|
| BBW stop | BLE if connected — a StandBy write done at the write ack, no status read; failure or **300 ms** → cloud at once. Never waits on a reconnect |
| Power, coffee temp, steam level, steam on/off | BLE if connected (3 s; failure → cloud), else cloud |
| State | BLE poll of mode + boilers every 2 s during a shot (10 s for PREP and RECONNECT NOW), none while released; cloud refresh while the app is on screen (at once, then every 10 min) for name, firmware, ready times and the BLE token |
| Machine shot start | BLE shot counter when subscribed (2.11.0), else the cloud's `brewingStartTime` |
| Brew page TIME | From the BLE pump-on (2.12.0), else from the first drops (`SCALE TIME`); never from the cloud start |

- **Measured** (2026-10-17): with `CONNECTION_PRIORITY_HIGH` a BLE write
  acknowledges in ~65 ms and a write + read in ~160 ms (~500 ms without it).
  First BBW stop over BLE: 104 ms, +0.1 g during the call, against the
  cloud's 0.22–0.27 s and +0.3–1.2 g.
- **The token** is `bleAuthToken` from the cloud's settings response, synced
  into `SecretsStore` on every refresh; a masked field in Settings is the
  fallback. Never logged. A wrong token is not an auth error — the auth write
  always succeeds — so the client verifies with a `machineMode` read.
- **Steam** is a level on the cloud and a temperature over BLE:
  Level1/2/3 ↔ 126/128/131 °C (Apollo2's mapping, verified on our machine).
- **Live boiler temperatures** come only over BLE, and are dropped from the
  snapshot whenever the link is not up. Over BLE the steam card decides
  Heating / Ready itself from them (`BoilerReadiness`); the cloud's "Ready
  since" lags a level change by up to its 10-minute refresh. Steam
  readings are coarse and jump, mostly by ~3 degrees (125/128/131, the odd
  129 or 132), so the ready band is +/-2. The coffee target is the brew
  temperature at the group; the boiler is held a few degrees above it (97
  for 94, 94 for 90), so the coffee card labels its reading `Boiler`, not
  `Now`, and leaves readiness to the cloud.
- **A timed-out GATT op drops the link at once.** Android keeps the
  unanswered op outstanding and refuses every later one, so waiting for
  polls to fail cost a minute once; reconnecting clears the queue.
- **Reconnects** back off 2/5/10/30/60 s; RECONNECT NOW skips the wait.
- **Status-bar dot** beside the Micra power switch (`BluetoothDot`):
  filled blue while the BLE link is up (a shot, or RECONNECT NOW), a
  blue ring while released but the
  machine was advertising at the last look (`MicraBleClient.seen`: probe,
  pre-connect scan or connect), nothing when not seen or local control is
  off. Indicator only - nothing routes on it. With the app on screen a
  scan-only probe runs at once and then every minute (INFO only when the
  answer changes); off screen nothing probes (2.7.0; a 15-min background
  probe ran 2.3.0-2.6.0 for the hang investigation).
- Every routed call logs its path and any fallback reason at INFO
  (`MachineController`); the BBW line reads
  `BBW stop sent at Xg (flow, target, offset, cloud lead) via=BLE|cloud | round-trip Ys | +Zg flowed during call`.
- **Shot counter (2.11.0).** Firmware with the BLE shot timer (this Micra:
  gateway 7.4.2 and 7.5.3) pushes brewing events on `0e0b7847-...`; each
  session subscribes after auth. `ShotService` takes the machine's pump-on
  time from it (the cloud is asked only without it), runs the Brew page
  TIME from it (2.12.0; before the first drops too, and from the first
  drops as `SCALE TIME` without it), never finishes a shot
  while the machine reports brewing, and confirms the BBW stop: still
  brewing 2.5 s later, the stop goes again, then the alert. Evidence, never
  the trigger of a first stop. A subscribe that times out drops the link and
  turns the counter off until restart.
- **Cloud problems (2.11.0).** A 403 says why in its body;
  `USER_MUST_ACCEPT_POLICY` (La Marzocco's updated privacy policy, not yet
  accepted on the account) caused two outages of days. `CloudProblem`
  names it, keeps since when across launches, and warns on the Brew screen,
  in the idle notification and on the Machine page - also with the link up.
  The cure is in the official app; this app does not accept the policy.
- **Cloud lead (2.10.0).** With the link down the stop is known to go to
  the cloud, which reaches the machine ~0.6 s later than BLE while it keeps
  brewing at full flow. So the stop fires at `target - offset - flow x
  cloudStopDelaySeconds` (0.6 s, capped at 4 g). The learned offset is the
  BLE drip: a cloud or failed stop is not learned from; a cloud stop logs
  the delay it implies instead (`implied cloud delay`), which is what the
  0.6 s is tuned from, measured on the author's machine.

## Design notes

- **The cloud wire format is not pylamarzocco's field names.** The REST API
  sends **`brewingStartTime` as epoch milliseconds**; pylamarzocco's
  re-serialised model shows an ISO string. Reading it as a string compiles,
  runs, and silently never captures a machine time.
- **No polling loop.** `ShotService` publishes a snapshot per weight sample
  and Compose recomposes on it.
- **Five bottom-bar tabs**; Configuration lives in the app bar, because a vessel
  placement must never navigate away from a half-edited form.
- **`connect(force)`.** `aioacaia`'s 15 s reconnect cooldown had no bypass,
  so a wake button did nothing until an app restart. Explicit wakes bypass it.
  The only automatic connect is at startup; after a drop or a sleep the scale
  reconnects on WAKE/RECONNECT, TARE or a scale-settings SAVE.

## Tests

223 JVM unit tests, no device, scale or machine required (the migration test
runs SQLite under Robolectric).

| Suite | Count | What it pins |
|---|---|---|
| `AcaiaProtocolTest` | 31 | Frame codec: encoding against `aioacaia`, decoding against aioacaia 0.2.3's fixtures (real captures among them), split and coalesced frames; the Acaia protocol adapter sends what the old client sent; the Umbra characteristic fallback |
| `BookooProtocolTest` | 8 | Bookoo Ultra packets and commands built from the maker's spec (24-bit weight and timer, sign, checksum); scale names to brands |
| `PlacementDetectorTest` | 17 | Vessel detection and matching on recorded traces |
| `ShotStateMachineTest` | 18 | Shot lifecycle and BBW safety on recorded traces; the slow-start and machine-brewing finish holds |
| `InstallationKeyTest` | 15 | LM request signing against `pylamarzocco` |
| `MicraProtocolTest` | 23 | Micra BLE bytes against golden values from pylamarzocco driven against a fake |
| `MicraShotCounterTest` | 9 | Shot-counter parsing and tracking, replayed against pylamarzocco's Linea Micra recording |
| `MicraBleClientTest` | 11 | Configuration-mode detection, reconnect backoff, release timing, poll/priority by demand, probe age format, name matching |
| `MachineControllerTest` | 27 | BLE/cloud routing with fake transports; snapshot merging; link demand (armed counts only in IDLE); whether a stop will go to the cloud |
| `ShotServiceTest` | 40 | Stop retries and alert (cleared once the flow stops), scale lost mid-shot, RESET mid-shot, cancellable power-on, deferred auto-arm, offset learning (BLE stops only), the cloud lead; machine start from the shot counter, finish held while brewing, stop confirmation |
| `CloudParsingTest` | 5 | Dashboard/settings JSON, `brewingStartTime` in epoch ms |
| `CloudProblemTest` | 4 | Policy vs other 403, sign-in, network; the warning text and its 30-min network grace |
| `MergeEditsTest` | 3 | Settings SAVE keeps values learned while the screen was open |
| `MigrationTest` | 1 | A v1 history opens through `MIGRATION_1_2` with its rows (Robolectric) |
| `MachineConfigTest` | 5 | Pre-BLE stored config still decodes; BLE fields logged on change; the machine picture defaults to the La Marzocco photo; an older config stays on the Acaia |
| `BoilerReadinessTest` | 5 | Steam heating / ready / settling from logged readings |
| `HelpAnchorsTest` | 1 | Every section the "?" opens exists in `docs/APP_GUIDE.html` |

The expected values of the first four suites, `MicraProtocolTest` and
`MicraShotCounterTest` were produced by running the same input through the
original Python (the Pi's shot logic, aioacaia, pylamarzocco) or taken from
those libraries' own fixtures, and are hard-coded, so they pin behaviour rather
than anyone's reading of it. The generators are not part of this repository;
change an expectation only on purpose. The two that matter most: the synthetic placement
trace measures **949 g/s** (on hardware a placement averages ~540 g/s) and a
recorded whole extraction **2.03 g/s** through the same detector — two
orders of magnitude apart, against a floor of 20 — and the LM proof algorithm
is pinned on six inputs, because the cloud answers a wrong proof with a bare
401 that looks like a wrong password.

Several tests are named for the incident they prevent (the 2026-08-30 phantom
shot and machine power-cycle, the −140.1 g yield, the v1.6.0 rise-time cliff).

## Logging

`FileLog` is API-compatible with `android.util.Log`, so each file aliases the
import (`import com.coffeeapp.droid.core.log.FileLog as Log`) and every call
site is unchanged. Lines go to both logcat and a file:
one timestamped file per launch and per day (a new file at midnight), 5 MB × 3
rotation, 365-day prune (at launch and each day roll; 7 days before 2.11.1). Lines read
`yyyy-MM-dd HH:mm:ss.SSS - LEVEL - Tag - message`. An uncaught exception is
written to the file before the process dies, and each launch logs why the
previous process ended (Android 11+). Known secrets (BLE token, password,
cloud tokens) are masked in the file as a safety net. **Configuration → Diagnostics** opens it in a text viewer or shares it.

Logcat alone would not have been equivalent: it is a ring buffer, wiped on
reboot, and needs a cable — while the auto-tare narration is kept at INFO
precisely so the guard values can be tuned from it.

## Backups, and restoring history

**Automatic.** `core/backup/DocumentsBackup.kt` mirrors the Room database and
the recent logs into `Documents/CoffeeApp/` at every launch, after every
saved shot and after an import, and **Configuration → Back up now** forces one. That folder is
shared storage, so it survives the app being uninstalled — which is the point:
app-private storage does not, and an uninstall took the history once already.

The database goes out as one dated file per day, `coffee_history_<date>.db`,
the newest three kept. The copy is a consistent snapshot (`VACUUM INTO`; WAL
checkpoint and file copy on older SQLite), written as a new pending item that
replaces the day's previous copy only once complete - an interrupted copy can
never leave the only backup damaged. The undated `coffee_history.db` of
earlier versions is removed once a dated one exists. Logs: the current file,
its newest rotation and the previous day's file. Credentials are deliberately not mirrored: the key that
encrypts them is device-bound in the keystore, so a copy would restore as
undecryptable bytes.

**Restore: Configuration → Restore data → CHOOSE BACKUP FILE.** Pick the newest `coffee_history_<date>.db` from
`Documents/CoffeeApp/`, or an older database file. Repeatable — already-present shots
are skipped by timestamp, so importing a stale copy over a populated database
is safe.

Not a file copy: Room validates a prepackaged database against its own identity
hash and a foreign file has none. Rows are read out with the framework SQLite API and
inserted through Room, which also handles the older 8-column schema with no
`beans` table, and remaps bean IDs so shots keep pointing at the right beans.
Columns are read by name, so a backup made before schema v2 (no
`flow_at_stop_gps`) imports with that column empty.

**Schema.** `CoffeeDatabase` is at version 2; v2 added `shots.flow_at_stop_gps`
through `MIGRATION_1_2`. A new column needs a version bump and a migration —
there is deliberately no destructive fallback, so a missing migration crashes
rather than wiping the history.

## Screens and startup

- **Launch screen** (`ui/SplashScreen.kt`) — the launcher icon, name and
  author for four seconds, tap to skip, switchable off in **Configuration →
  About**. It repeats the launcher icon on purpose: Android 12+ always draws
  its own splash from that icon and cannot be told not to, so showing it again
  turns two screens into one that resolves.
- **Scale is the start destination.** Placing a vessel navigates on its own
  between Brew and Scale, so opening on the screen you would otherwise be sent
  away from was the wrong default.
- **Five accent schemes** — Micra Red (default), Celeste, La Marzocco Yellow,
  Copper, Crema — chosen in **Configuration → Appearance**. Only the accent
  moves: the background stays black, and green/amber/red keep their meanings.
  Celeste and the yellow are sampled from photographs of the machines rather
  than taken from a brand guide. Each scheme carries its own `onAccent`, since
  three of them are too light for white content.
- **Help** (`ui/screens/HelpScreen.kt`, 2.14.0) — the user guide in a
  WebView, offline, one section per tab (2.14.1: `HELP_SECTIONS`; CSS added
  to the bundled copy hides the rest, the source file stays whole). The **?** in the top bar opens it at the page on screen
  (`Destination.helpAnchor`; Configuration has its own **Help** row), and
  turns into a cross that closes it; the cog is hidden meanwhile so it cannot
  stack a second Configuration. `docs/APP_GUIDE.html` is the only copy: the
  `bundleGuide` task in `app/build.gradle.kts` copies it into generated
  assets on every build, pinned to its dark theme and without its Google
  Fonts link (the build fails if either marker is gone). The WebView runs
  without JavaScript, network or file access and follows no links.
  `HelpAnchorsTest` fails if a section the app opens is renamed in the guide.
  Since 2.14.2 each screenshot is cropped to what its section describes.
  To retake one: put the screen up on the phone, capture it with
  `adb exec-out screencap -p > shot.png` (no remote taps: the power switch
  sits beside the **?**), scale to 540 px wide, crop, blur account details,
  serial and Bluetooth addresses, and embed it as base64 JPEG.
- **Machine picture** (2.15.0, Configuration -> Appearance) — the Micra
  page shows La Marzocco's product photo (default), the user's own or none.
  The product photo is theirs and excluded from the MIT licence. An own photo
  comes from the system photo picker (no permission) and is stored scaled
  to 800 px as `files/machine_photo.jpg` (`core/MachinePhoto.kt`), not
  backed up; a missing one shows nothing, never La Marzocco's.
- **Editing a bean's grind.** Tap the grind line on a bean card. Shots already
  recorded keep the setting they were pulled at.
- **Deleting a shot or a bean asks first.** Both bins sit a thumb's width from
  controls used routinely. The bean dialog says what is lost beyond the row:
  past shots keep their grind but lose the bean name, because `shots.bean_id`
  has no foreign key and the join goes blank.

## Identity assets

Generated from source images rather than drawn in the repo, so regenerate them
the same way rather than editing pixels. The sources live in
`assets-source/` - our own artwork, created for the app with AI image tools
(MIT, like the code), kept in the repo because without them none of these
can be rebuilt:

| Source | Feeds |
|---|---|
| `assets-source/scale-artwork.png` | the launcher icon and `logo.png` |
| `assets-source/wordmark-shots.jpg` | `wordmark.png` |
| `assets-source/splash-composition.jpg` | the current launcher icon |


| Asset | Built from | Notes |
|---|---|---|
| `drawable-*/ic_launcher_foreground.png` | the splash composition | Cropped to the tile + wordmark. Portrait art must fit the adaptive icon's 72dp safe circle, which is what caps its size |
| `drawable-*/logo.png` | the scale artwork | Unpadded, for the splash — the launcher copy carries ~45% padding for the mask and looks small if reused |
| `drawable-*/wordmark.png` | a render of "Shots" | White pixels with a soft alpha ramp, tinted at runtime with the accent |
| `res/font/kaushan_script.ttf` | Google Fonts | The nearest OFL brush script; unused in code (the wordmark is an image), kept as the source if it is ever re-rendered |

## Attribution

`ble/Acaia*.kt`, `ble/Micra*.kt`, `core/cloud/InstallationKey.kt`,
`CloudClient.kt`, `CloudParsing.kt` and four tests (`AcaiaProtocolTest`,
`MicraProtocolTest`, `MicraShotCounterTest`, `InstallationKeyTest`) are
derived from the MIT-licensed `aioacaia` and `pylamarzocco`; they carry a
"Portions derived from" line and are listed, with the MIT notice, in
`../THIRD-PARTY-NOTICES.md`. The project is MIT licensed (`../LICENSE`);
every source file, the Gradle scripts included, starts with the copyright
and `SPDX-License-Identifier: MIT` header. The one
exception is La Marzocco's machine image `lineamicra_celeste.png`, kept and
excluded from the licence (notices, section 4).

## Known gaps

- Vessel `arm` is not editable in the UI. It is not the checkbox its name
  suggests — `PlacementDetector` excludes arm vessels from loaded matching, so
  a toggle would silently stop the dosing cup reading a dose. It is a statement
  about what a vessel is, set in `DefaultConfig`.
- Only the current log file can be shared from the app; earlier ones are in
  `Documents/CoffeeApp/` for 365 days.
- The Micra client's connect/poll/release loop has no unit test: it needs a
  fake GATT layer, and that refactor of the device-proven BLE code was judged
  not worth the risk.
- Credentials use `androidx.security:security-crypto`, which is deprecated but
  works; moving off it means migrating the stored secrets, not done.
- **The Micra's BLE can hang.** The machine stops advertising and ignores
  connects until it is power-cycled. Nine hangs from 2026-10-17 to 10-23,
  none since: after long busy holds, right after a clean release, and after
  hours with no session at all, so no single trigger is known. Commands fall back to the cloud meanwhile; the
  status-bar dot shows whether the machine is advertising. The machine is
  switched off between uses; gateway firmware 7.5.3 since 2026-11-17.
