# OpenDash

Open-source, low-power motorcycle navigation and ride management for the Royal Enfield Tripper Dash.

OpenDash renders navigation off-screen, hardware-encodes it as H.264, and streams it to the bike over Wi-Fi. The phone display can remain off during a ride, reducing heat and battery use compared with screen mirroring.

> OpenDash is an independent community project. It is not affiliated with, endorsed by, or supported by Royal Enfield. The Tripper protocol is unofficial and reverse-engineered. Use it at your own risk.

## Main Features

### Tripper navigation

- Discover and pair with `RE_*` Tripper Dash Wi-Fi networks.
- Confirm the exact discovered SSID before it is stored.
- Reconnect to the confirmed dash on later rides.
- Share a destination from Google Maps into OpenDash.
- Build road routes with OSRM and preview them with MapLibre and OpenFreeMap.
- Stream a dedicated off-screen map to the Tripper Dash using hardware H.264 encoding and RTP over UDP.
- Show remaining distance, ETA, GPS health, and automatic off-route recalculation.
- Continue streaming while the phone screen is off.
- Choose voice guidance modes: Off, Chime, or Full TTS.

### Home and ride history

The Home screen keeps ride-related actions together:

- Start navigation.
- Connect to or open the live Dash view.
- Open saved destinations.
- Review total distance, ride count, recorded time, and the three latest rides.
- Open full ride history with distance, duration, average speed, maximum speed, and route tracks.

Connected rides are recorded automatically. Poor GPS fixes and stationary drift are filtered so they do not inflate ride distance or create false rides.

### Idle dash wallpapers

- Store up to five wallpaper items in app-private storage.
- Select multiple images, GIFs, or MP4 videos from Android's media picker.
- Crop content to the dash render resolution.
- Use Crop, Fit height, or Fit width for photos and content such as QR codes.
- Preview the visible Tripper display area with the on-screen guide.
- Swipe through the gallery in Settings.
- Use the Tripper joystick left/right controls to change wallpaper while the dash is idle.

Wallpaper rendering is used only while idle. Active navigation keeps its existing map and guidance behavior.

### Media and calls on the dash

- Forward the active song title, album, and artist to the Tripper Dash.
- Show incoming caller information in the projected frame.
- Use left/right media controls to move between tracks when media is active.
- Use joystick Up to answer and Down to reject or end a call.
- Keep album-art transmission disabled until the dash fragment format is fully verified.

Media information requires Android notification access. Call actions additionally require the `ANSWER_PHONE_CALLS` runtime permission. OpenDash does not request contact or call-log access.

### Vehicles, expenses, and garage

- Add and edit vehicle profiles.
- Track vehicle name, nickname, PUC expiry, insurance expiry, and service information.
- Record expenses for fuel, repairs, accessories, riding gear, food, stays, transport, and other categories.
- Add category-specific details such as vehicle, fuel quantity, odometer, store, replaced parts, and notes.
- Filter expenses and export them as CSV or document files through the Android share sheet.
- Track maintenance intervals and mark completed service work.
- Maintain a fuel diary with litres, cost, odometer, location, mileage, and 30-day summaries.

### Appearance and sync

- App-wide Material 3 interface.
- Six selectable themes with Hanle Black as the default.
- Compact theme dropdown in More -> Theming.
- Fully local operation without an account.
- Optional Firebase authentication and Firestore sync using your own Firebase project.

## Requirements

- Android device with Bluetooth, Wi-Fi, and precise location support.
- Android 10 or newer is required for the Tripper Wi-Fi connection flow based on `WifiNetworkSpecifier`.
- A compatible Royal Enfield Tripper Dash.
- Location permission for routing and ride tracking.
- Nearby-device/Wi-Fi permission where required by Android.
- Internet access while calculating routes and downloading map tiles.

The app has been tested against Tripper firmware `11.63`. Other firmware versions may behave differently.

## Install

1. Open the [OpenDash Releases page](https://github.com/subtlesayak/open-dash/releases).
2. Download the latest APK matching your device. Most current Android phones use `arm64-v8a`.
3. Allow installation from the browser or file manager when Android asks.
4. Install or update OpenDash.

The current beta APK uses the debug application package `com.opendash.app.mui3` and is debug-signed. Android may show an unverified-app warning. Builds signed by a different debug key cannot update each other in place.

## First-Run Setup

### 1. Open the app

Sign in only if you configured Firebase sync, or continue in local-only mode. Navigation, wallpapers, vehicles, garage, expenses, and ride recording do not require an account.

### 2. Grant required permissions

Allow precise location and the Wi-Fi/nearby-device permissions requested by Android. Notification permission is recommended so the foreground dash service remains visible.

### 3. Pair the Tripper Dash

1. Turn on the motorcycle and wait for the Tripper Dash to start.
2. In OpenDash, open Home and tap **Connect to dash**.
3. Select the discovered `RE_*` network when Android displays its Wi-Fi dialog.
4. Confirm the exact SSID inside OpenDash.
5. Wait for Wi-Fi, authentication, and streaming to complete.

The confirmed SSID and password are stored using AndroidX encrypted preferences. Use **Forget Dash** in Settings when changing motorcycles or pairing again.

## Start Navigation

1. Open Google Maps and select a destination.
2. Tap **Share** and choose OpenDash.
3. Review the destination and route in OpenDash.
4. Save the destination if you want quick access later.
5. Tap **Start navigation**.
6. Connect to the Tripper Dash if it is not already connected.
7. Turn the phone screen off after projection starts.

OpenDash keeps navigation active while the screen is off through its foreground service, wake handling, location updates, and dash connection.

## Configure Wallpapers

1. Open **More**.
2. Find **Dash Wallpaper**.
3. Tap **Add media** and select one or more images, GIFs, or videos.
4. Adjust the horizontal and vertical crop position.
5. Choose Crop, Fit height, or Fit width.
6. Use the display guide to check what the round Tripper screen will show.
7. Save the media.

The gallery accepts up to five items. Use the preview controls in Settings or the Tripper joystick while idle to switch items.

## Enable Media and Calls

1. Open **More -> Media & calls on dash**.
2. Enable **Now playing & caller cards** and grant notification access in Android Settings.
3. Enable **Answer calls from joystick** and grant call-control permission.
4. Start music through an app that publishes an Android media session.

Notification content is processed in memory for the active dash session. Song titles and caller names are not written to OpenDash logs.

## App Navigation

The five main tabs can be tapped or swiped horizontally:

| Tab | Purpose |
| --- | --- |
| Home | Dash connection, navigation, saved destinations, and recent rides |
| Vehicles | Add and edit motorcycle profiles and expiry details |
| Expenses | Add, filter, review, and export expenses |
| Garage | Maintenance intervals, service history, odometer, and fuel diary |
| More | Connection, themes, wallpaper, media/calls, voice, units, account, and sync |

Route, Dash, and full Ride History are child pages opened from Home. Android Back returns to Home rather than cycling through every tab.

## How It Works

```text
Google Maps share
        |
        v
Destination parser -> OSRM route -> off-screen tile/map renderer
                                      |
                                      v
                             MediaCodec H.264
                                      |
                                      v
                              RTP over UDP :5000
                                      |
                                      v
                         Royal Enfield Tripper Dash

Control plane: K1G over UDP :2000/:2002 with RSA/AES authentication
```

The phone preview uses MapLibre with keyless OpenFreeMap vector tiles. The physical dash receives OpenDash's purpose-built 526 x 300 off-screen render. The dash connection uses a reverse-engineered K1G control plane and a custom H.264/RTP stream.

Protocol-critical connection sequencing, authentication, frame ACK handling, route-card behavior, socket targets, and RTP packetization are documented in [`docs/PROTOCOL_FREEZE.md`](docs/PROTOCOL_FREEZE.md). Media and call cards are limited to the reviewed additive `05 0D` and `05 22` packet extension.

The control-plane implementation builds on reverse-engineering from [better-dash](https://github.com/norbertFeron/better-dash).

## Technology

- Kotlin and Jetpack Compose with Material 3.
- Android MediaCodec hardware AVC/H.264 encoding.
- Custom NAL processing and RTP packetization.
- MapLibre and OpenFreeMap for keyless map preview.
- OSRM for road routing.
- SQLite for local rides, garage, fuel, expenses, and saved destinations.
- Android LocationManager for GPS tracking.
- Android TextToSpeech for voice guidance.
- Optional Firebase Auth and Firestore sync.
- AndroidX Security encrypted preferences for dash Wi-Fi configuration.

## Build From Source

```bash
git clone https://github.com/subtlesayak/open-dash.git
cd open-dash
./gradlew :app:assembleDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

The build produces ABI-specific debug APKs and a universal APK under:

```text
app/build/outputs/apk/debug/
```

Useful outputs include:

- `app-arm64-v8a-debug.apk` for most current phones.
- `app-armeabi-v7a-debug.apk` for older 32-bit ARM devices.
- `app-x86-debug.apk` and `app-x86_64-debug.apk` for compatible emulators.
- `app-universal-debug.apk` containing every supported ABI.

Run unit tests with:

```bash
./gradlew :app:testDebugUnitTest
```

Build optimized unsigned release artifacts with:

```bash
./gradlew :app:assembleRelease
```

Release signing never falls back to the debug key. Provide your own keystore through these Gradle properties or CI secrets:

```text
OPENDASH_RELEASE_STORE_FILE
OPENDASH_RELEASE_STORE_PASSWORD
OPENDASH_RELEASE_KEY_ALIAS
OPENDASH_RELEASE_KEY_PASSWORD
```

## Optional Configuration

The navigation and map path is keyless. No Google Maps API key is required.

For Google sign-in, place your web client ID in the gitignored `local.properties` file or provide it as a Gradle property:

```properties
GOOGLE_WEB_CLIENT_ID=your_web_client_id
```

Use [`local.defaults.properties`](local.defaults.properties) as the placeholder reference. Do not commit real project identifiers, API keys, keystores, or `google-services.json`.

For optional cross-device sync:

1. Create your own Firebase project.
2. Enable Authentication and Firestore.
3. Download `google-services.json` into `app/`.
4. Configure `GOOGLE_WEB_CLIENT_ID` locally.
5. Rebuild OpenDash.

Without Firebase configuration, OpenDash remains local-only.

## Security and Privacy

- Dash credentials are stored in encrypted preferences.
- Exact SSID pairing requires rider confirmation.
- Wallpaper media stays in app-private storage.
- Release builds avoid logging full URLs, coordinates, media titles, and caller names.
- Secret files, keystores, logs, APKs, and local configuration are excluded from Git.
- Firebase and Google sign-in are optional and bring-your-own-project.

Do not commit secrets. See [`CHANGELOG.md`](CHANGELOG.md) for release history.

## Known Limitations

- The Tripper protocol is unofficial and requires real hardware for full validation.
- Public OSRM routing and online map tiles require internet access.
- Map and call behavior may vary by Android vendor, dialer, media app, and Tripper firmware.
- Album-art packet fragmentation is not enabled because its dash framing is not verified.
- The public beta currently provides an arm64 APK; other ABI artifacts can be built locally.
- Full Android lint still reports inherited minimum-SDK/API issues in older maintenance, thermal, and Wi-Fi code even though debug tests and release builds pass.

## Contributing

Issues and pull requests are welcome, especially for:

- Compatibility reports from other Tripper firmware versions.
- Reproducible navigation, pairing, or screen-off behavior issues.
- Real-ride battery and thermal measurements.
- Packet captures for still-unverified maneuver, media, or dash behavior.
- Tests that improve protocol, storage, routing, or parser coverage.

When reporting a problem, remove personal information, coordinates, SSIDs, caller names, account identifiers, and secrets from logs or screenshots.

## License

OpenDash is distributed under the terms in [`LICENSE`](LICENSE).

## References

- [norbertFeron/better-dash](https://github.com/norbertFeron/better-dash) - Motivation
- [adityadasika21/NorthStar](https://github.com/adityadasika21/NorthStar) - App base
