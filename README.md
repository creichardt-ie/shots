# Shots — espresso by the gram

An Android app that pairs a Bluetooth coffee scale with a La Marzocco Linea
Micra: live weight and flow, brew-by-weight that stops the machine at your
target, shot history, a bean journal, and auto-tare that recognises your
vessels by their weight.

Native Kotlin and Jetpack Compose. The machine is controlled over Bluetooth
when it is in range, with the La Marzocco cloud as a fallback.

**[User guide](https://creichardt-ie.github.io/shots/)** ·
**[Download the APK](../../releases/latest)**

## Features

- **Brew by weight.** Set a target yield; the app stops the machine just
  before it, learning the drip that follows the stop. Over Bluetooth the stop
  reaches the machine in about 0.1 s; over the cloud the app fires earlier
  to make up for the delay.
- **Live shot view.** Time from the machine's pump-on, weight, flow rate, and
  a ready/armed state so a bumped scale never starts a shot.
- **Auto-tare.** Put a known vessel (dosing cup, milk pitcher, coffee cup) on
  the scale and it tares and switches to the right screen by itself. Off by
  default.
- **History and beans.** Every shot is saved with its bean and grind setting,
  with a dial-in hint (grind finer / coarser) against a target time window.
- **Machine control.** Power, coffee temperature, steam level and boiler
  readings.
- **Backups** of the history into `Documents/CoffeeApp/`, which survive an
  uninstall, and restore from them.
- **Offline help.** The [user guide](https://creichardt-ie.github.io/shots/)
  is built into the app; the **?** opens it at the screen you are on. Its
  source is [`docs/APP_GUIDE.html`](docs/APP_GUIDE.html).

## Supported hardware

| Device | Status |
|---|---|
| La Marzocco Linea Micra | Used daily by the author. Bluetooth and cloud |
| Acaia Umbra | Used daily by the author |
| Acaia Pyxis, Lunar 2021 | Same protocol as the Umbra (from `aioacaia`); less tested |
| Acaia Lunar (original, pre-2021) | Protocol present in the code, but there is no setting for it yet |
| Bookoo Themis Ultra | From Bookoo's published protocol spec; less tested |

Other La Marzocco machines use the same cloud API and may partly work, but
the app is built around the Micra.

Android 8.0 (API 26) or newer, with Bluetooth LE.

## Install

Download the APK from [Releases](../../releases) and open it on the phone
(allow installing from that source when Android asks).

## Building

Requires the Android SDK and JDK 21 (Android Studio ships both). Open the
`android/` folder in Android Studio, or from a shell:

```
cd android
gradlew test             # 223 unit tests, no device needed
gradlew assembleRelease  # app/build/outputs/apk/release/
```

From a plain shell, set `ANDROID_HOME` to the SDK and `JAVA_HOME` to a
JDK 21 (Android Studio's `jbr` folder will do).

**Signing.** A build of your own is signed with your PC's Android debug key
(`~/.android/debug.keystore`); no keystore is in this repository, and none
should ever be committed. Build updates on the same PC (or keep a copy of
that keystore): Android refuses to update an app signed with a different key,
and uninstalling to get round it deletes the app's settings, login and
Bluetooth token. Shot history can be restored from the automatic backup.

So an APK from the [Releases](../../releases) page and one you built
yourself cannot update each other. The published APKs are signed with the
project's release key, certificate SHA-256
`E0:F2:C1:4F:0A:3F:CC:27:A6:5E:0A:5E:D2:19:CB:17:57:03:9E:79:BB:A9:A0:34:E5:E5:D9:55:4D:DD:70:54`.

The module map, the design decisions and the test suites are described in
[`android/README.md`](android/README.md).

## First run

1. **Configuration → Scale → SCAN FOR SCALE**, pick your scale.
2. **Configuration → La Marzocco account**: your La Marzocco login and the
   machine's serial number (on the machine, or in the La Marzocco app). The
   login is kept in Android's encrypted storage, never in plain files or logs.
3. Optional: **Machine Bluetooth** to scan for the Micra and turn on local
   control (faster, more precise stops).
4. Optional: weigh your own cups and set them up under **Auto-tare** before
   switching it on. The vessels the app starts with are only examples.

If the cloud reports that the account must accept La Marzocco's updated
privacy policy, accept it in the official La Marzocco app. This app does not
accept it for you.

## Disclaimer

This is an independent hobby project. It is **not affiliated with, endorsed
by or supported by La Marzocco, Acaia or Bookoo**. Product names are
trademarks of their owners and are used only to say which hardware the app
works with.

The app talks to your machine through unofficial, reverse-engineered
interfaces that can change without notice. It can switch the machine on and
off and stop shots. Use it at your own risk, and stay with your machine while
it is brewing.

## Licence

MIT, see [`LICENSE`](LICENSE). Parts of the Bluetooth and cloud code are
derived from the MIT-licensed [`aioacaia`](https://github.com/zweckj/aioacaia)
and [`pylamarzocco`](https://github.com/zweckj/pylamarzocco) and from
Bookoo's open protocol specification; their notices, the bundled font's
licence and the dependency list are in
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

The Linea Micra product image
(`android/app/src/main/res/drawable-nodpi/lineamicra_celeste.png`) belongs to
La Marzocco and is **not** covered by the MIT licence (notices, section 4).
