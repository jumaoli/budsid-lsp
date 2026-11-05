# BudsId · Xiaomi Earbud Device-ID Swap

An LSPosed module that replaces the earbud `deviceId` Xiaomi Bluetooth reports, so a pair of earbuds can reuse **another Xiaomi model's connection popup animation, Super Island notification and Settings device images** — without touching the audio link (A2DP/HFP stay untouched).

- Package: `com.mi.budsid`
- Scope: `com.xiaomi.bluetooth` (popup / Super Island / device details), `com.milink.service` (fusion device card), optional `com.android.settings` for the detail-page image
- Tested on HyperOS 4 / Android 17 (M610BB); should work on HyperOS 2/3 as well

---

## How it works

Xiaomi Bluetooth derives everything visual from the earbud's `deviceId`:

| Source | Code |
| --- | --- |
| Advertisement parsing | `MiuiAdvDataInfo(V2)#getDeviceId()`, `MiuiHeadsetInfo(V2)#getDeviceId()` |
| Cache | `saved<MAC>` in the `DeviceIdCached` SharedPreferences, read through `BluetoothReflect#getDeviceId(String, Context)` |

The ID is composed from advertisement bytes (`String.format("%06X%04X", majorId, minorId)`). Once the module substitutes it, all downstream code — `DeviceClassify`, `MiuiCloudParser`, `MiuiBluetoothNotification` — naturally loads the target model's resources:

```
popup frames     /data/user_de/0/com.xiaomi.bluetooth/files/fc_resources/<deviceId>/fc_<deviceId>_<frame>.webp
resource record  .../fc_resources/<deviceId>/resource_record.xml
Super Island mp4 .../files/my_internal_files/target/<deviceId>/*
CDN popup zip    https://cdn.cnbj1.fds.api.mi-img.com/bluetoothencrypt/fast_connect/<deviceId>.zip
```

Additional hooks cover `HeadsetProvider` / `MiuiBluetoothContentProvider` queries and `MiuiBluetoothNotification#getResourceUri` so no surface leaks the real ID.

Nothing on disk is modified; the module is read-path only and fully reversible.

## Usage

1. Install the APK, enable the module in LSPosed, tick `com.xiaomi.bluetooth` and `com.milink.service`.
2. Open the app, pick a target model from the device list (or search the catalog), confirm with **确定**.
3. Reconnect the earbuds — popup / Super Island / Settings detail page now use the target model.
4. Per-surface switches control popup, Super Island and detail page independently.

The catalog is pulled from Xiaomi's CDN (`00000000.zip` → `resource_record.xml`, ~455 entries incl. 10-digit peripheral IDs) and refreshed daily; a local list adds models missing from the cloud (e.g. Xiaomi AI Glasses `01013304`).

## UI

The interface follows SukiSU Ultra's Miuix style: GPU `RenderNode` blur (4dp), AGSL liquid-glass bottom bar with refraction and gravity-following highlight, floating nav pill with drag and overshoot, per-tab switches.

## Build

No Gradle. Requires JDK 17 and Android SDK (build-tools + a platform):

```powershell
./build.ps1
# output: dist/budsid-v1.0.0.apk
```

The script calls `aapt2`, `javac`, `d8`, `zipalign` and `apksigner` directly and generates a local debug keystore on first run. A GitHub Actions workflow builds the APK on every push.

## Layout

```
src/com/mi/budsid/          app UI, catalog, thumbnails, Miuix widgets
src/com/mi/budsid/hook/     Xposed entry and swap logic
assets/                     device catalog (devices.json), xposed_init
res/                        icons, strings, arrays
stubs/                      Xposed API stubs for compilation
build.ps1                   standalone build script
```

## Notes

- Tested with temporary root (SukiSU); reboot not required after enabling the module in most cases.
- Thumbnails load through the Bluetooth process because the app process receives HTTP 403 for `fast_connect/<id>.zip`.
- This is a display-identity swap only: audio codec, ANC and firmware behaviour remain the real model's.

## License

MIT
