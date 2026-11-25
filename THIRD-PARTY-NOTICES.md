# Third-party notices

CoffeeApp is Copyright (c) 2026 Christian Reichardt and licensed under the
MIT License (see `LICENSE`), except the La Marzocco image in section 4. The
derived portions listed in section 1 also carry their authors' MIT notices
(Josef Zweck; BooKoo), which must stay with them. This file records the third-party work it is
built on, split into the two categories that carry
different obligations:

- **Derived source** — code in this repository written by reading someone
  else's implementation. Their licence terms travel with it, so the notice is
  reproduced in full below.
- **Dependencies** — libraries used as published, not copied or modified. No
  third-party source is vendored into this repository.

---

## 1. Derived source

The Android port replaces two Python libraries rather than depending on them:
there is no Kotlin `aioacaia` and no Kotlin `pylamarzocco`. The Kotlin was
written here, but the wire formats, framing, message layout and the request
signing scheme were taken from these MIT-licensed projects, and the tests
assert values produced by running the original Python.

| File | Derived from |
|---|---|
| `android/.../ble/AcaiaProtocol.kt` | aioacaia — `helpers.py`; decoding from 0.2.3 `parser.py` and the frame loop in `scale.py` |
| `android/.../ble/ScaleGattClient.kt` (until 2.16.0 `AcaiaScaleClient.kt`) | aioacaia — `process_queue`, `on_bluetooth_data_received`, reconnect backoff |
| `android/.../ble/AcaiaMessage.kt` | aioacaia — `AcaiaDeviceState`, 0.2.3 `messages.py` |
| `android/.../core/cloud/InstallationKey.kt` | pylamarzocco — `util/_authentication.py` |
| `android/.../core/cloud/CloudClient.kt` | pylamarzocco — customer-API request shapes and token handling |
| `android/.../core/cloud/CloudParsing.kt` | pylamarzocco — dashboard and settings field names and widget codes |
| `android/.../ble/MicraProtocol.kt` | pylamarzocco — `clients/_bluetooth.py` characteristics, commands, read settings |
| `android/.../ble/MicraBleClient.kt` | pylamarzocco — `clients/_bluetooth.py` connect/auth sequence, 2.5.6 shot-counter subscribe |
| `android/.../ble/MicraShotCounter.kt` | pylamarzocco 2.5.6 — `models/_bluetooth.py` brewing/telemetry models, `_BrewingTracker` |
| `android/.../ble/MicraShotCounterTest.kt` | pylamarzocco 2.5.6 — its Linea Micra recording `shot_counter_2026-10-05.txt` |
| `android/.../ble/AcaiaProtocolTest.kt` | aioacaia — expected byte strings computed from it; 0.2.3 test fixtures |
| `android/.../core/cloud/InstallationKeyTest.kt` | pylamarzocco — expected signatures computed from it |
| `android/.../ble/MicraProtocolTest.kt` | pylamarzocco — expected bytes from driving its BLE client against a fake |

Each of these files carries a "Portions derived from" line naming its source.
Files that only cite these projects for a fact, such as the
`USER_MUST_ACCEPT_POLICY` error code (`CloudProblem.kt`) or the machine-name
prefixes (`MicraBleClientTest.kt`), contain no derived code and are not
listed.

Both projects are by Josef Zweck:

- **aioacaia** — https://github.com/zweckj/aioacaia
- **pylamarzocco** — https://github.com/zweckj/pylamarzocco

Both are MIT licensed, with the same notice. The 2026-11-17 ports (2.11.0)
came from aioacaia 0.2.3 and pylamarzocco 2.5.6, both MIT at those tags
(their LICENSE files and `pyproject.toml`, checked that day):

```
MIT License

Copyright (c) 2026 Josef Zweck

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### BOOKOO OpenSource protocol (2.16.0)

| File | Derived from |
|---|---|
| `android/.../ble/BookooProtocol.kt` | BooKooCode/OpenSource — `bookoo_ultra_scale/protocols.md`: UUIDs, weight packet layout, command format and checksum |
| `android/.../ble/BookooProtocolTest.kt` | the same spec — packets built field by field from its layout |

Source: https://github.com/BooKooCode/OpenSource (MIT, LICENSE file checked
2026-11-20). The GPL-3 drivers aiobookoo and Beanconqueror were read only for
facts the spec leaves out (the sign byte values, the advertised name); no code
was taken from them.

```
MIT License

Copyright (c) 2024 BooKoo

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 2. Bundled font

`android/app/src/main/res/font/kaushan_script.ttf` — **Kaushan Script**,
bundled with the app. The launch-screen wordmark is an image generated from
it; no screen sets the font directly. Licensed under the SIL Open Font
License 1.1, which requires this notice to travel with the font:

```
Copyright (c) 2011, Pablo Impallari (www.impallari.com|impallari@gmail.com),
Copyright (c) 2011, Igino Marini. (www.ikern.com|mail@iginomarini.com),
with Reserved Font Name Kaushan Script.

This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is available with a FAQ at: https://openfontlicense.org
```

The OFL permits bundling in an application, including a commercial one, and
forbids selling the font on its own. The reserved font name means a *modified*
copy may not be distributed under the name "Kaushan Script"; this copy is
unmodified.

## 3. Dependencies — Android app (Kotlin)

Resolved from Maven, unmodified, and bundled into the APK. See
`android/gradle/libs.versions.toml` for pinned versions.

| Group | Licence |
|---|---|
| androidx.* (Core, Lifecycle, Activity, Compose, Navigation, Room, DataStore, Security) | Apache-2.0 |
| org.jetbrains.kotlin / kotlinx.* (coroutines, serialization) | Apache-2.0 |
| io.ktor | Apache-2.0 |
| com.squareup.okhttp3, com.squareup.okio (via the Ktor OkHttp engine) | Apache-2.0 |
| junit:junit (test only) | EPL-1.0 |
| org.robolectric (test only) | MIT |
| androidx.test, Espresso (test only) | Apache-2.0 |

Apache-2.0 requires that this notice accompany binary distributions. If the
APK is ever shared outside this device, ship this file with it.

## 4. Image not covered by the MIT licence

| File | What it is |
|---|---|
| `android/app/src/main/res/drawable-nodpi/lineamicra_celeste.png` | Linea Micra product image, shown on the Micra page |

The image belongs to La Marzocco and is used here, unmodified, to show the
machine the app controls; it is the default picture, and Configuration ->
Appearance can replace it with the user's own photo or none (2.15.0). It is
**not** licensed under `LICENSE`; no rights
in it are granted to anyone. The same image appears in a screenshot in
`docs/APP_GUIDE.html`; that part of the screenshot is excluded too. If you
are La Marzocco and want it removed, open an issue and it will be.

The app's own artwork - the launcher icon, logo and "Shots" wordmark and
their sources in `android/assets-source/` - was
created for this project with AI image tools and is covered by `LICENSE`.
The wordmark is a render of the Kaushan Script font (section 2); the OFL
does not restrict images made with a font.

---

*Licences verified from published package metadata (Maven POMs, the PyPI API
and the GitHub licence API) rather than transcribed from memory.*
