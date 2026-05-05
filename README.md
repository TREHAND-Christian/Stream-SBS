# Stream SBS

Stream SBS is an Android project made of two apps that stream the screen of one smartphone to another smartphone on the same local network, with a Side-by-Side (SBS) display mode on the receiver.

The project provides two Android apps:

- `sender`: captures the sender phone screen, encodes the video stream, and sends it over the local network.
- `receiver`: discovers the sender/receiver pair, receives the video stream, and renders it in SBS mode or in a single full-screen view.

## Features

- Android-to-Android screen streaming over a local network.
- Side-by-Side rendering with two synchronized views of the same stream.
- Single-view mode for standard display use.
- Automatic receiver discovery through UDP broadcast.
- H.264 screen encoding with `MediaCodec`.
- UDP video transport using RTP packets and FU-A fragmentation.
- H.264 decoding on the receiver with `MediaCodec`.
- Configurable video profiles:
  - resolutions from 854x480 to 2220x1080;
  - 20, 24, or 30 fps;
  - bitrate from 3 to 10 Mbps.
- Render settings synchronized between both devices:
  - SBS mode;
  - horizontal and vertical zoom;
  - horizontal and vertical offset;
  - optional camera overlay opacity;
  - video profile.
- Receiver-side settings menu controlled with the volume buttons.
- Latency, rendered FPS, and active video profile indicators.
- Foreground streaming service on the sender.

## Architecture

The repository is split into three Gradle modules:

```text
common/    Shared code: protocols, ports, video profiles, RTP, H.264 helpers
sender/    Sender app: screen capture, H.264 encoding, UDP streaming
receiver/  Receiver app: H.264 decoding, OpenGL rendering, local controls
```

### Recommended Network Setup

For the most reliable behavior, create a Wi-Fi hotspot on the sender phone and connect the receiver phone to that hotspot.

This setup keeps both devices on a direct local network, improves receiver discovery, and avoids many router/client-isolation issues found on public or shared Wi-Fi networks.

When the receiver phone connects to a Wi-Fi network, the receiver app attempts to bring itself to the foreground automatically. This helps reduce the need to use the receiver touchscreen after it has joined the sender hotspot. Android may still limit background activity launches on some devices or OS versions, so the receiver app can always be opened manually if needed.

### Network Flow

Used ports:

| Port | Purpose |
| --- | --- |
| `5500/UDP` | RTP/H.264 video stream |
| `5501/UDP` | Receiver discovery |
| `5502/UDP` | Render settings sent to the receiver |
| `5503/UDP` | Receiver status sent back to the sender |

## Requirements

- Android Studio.
- JDK 17.
- Android SDK with `compileSdk 34`.
- Two Android devices on the same local network.
- Recommended: sender hotspot enabled, receiver connected to that hotspot.
- Android 10 or newer (`minSdk 29`).

## Build

From the repository root:

```bash
./gradlew assembleDebug
```

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

Debug APKs are generated in:

```text
sender/build/outputs/apk/debug/
receiver/build/outputs/apk/debug/
```

## Installation

Install the `receiver` app on the smartphone that will display the stream.

Install the `sender` app on the smartphone whose screen will be streamed.

Example with ADB:

```bash
adb install receiver/build/outputs/apk/debug/receiver-debug.apk
adb install sender/build/outputs/apk/debug/sender-debug.apk
```

## Usage

1. Enable the Wi-Fi hotspot on the sender phone.
2. Connect the receiver phone to the sender hotspot.
3. The receiver app should automatically move to the foreground when Wi-Fi connects. If Android blocks the auto-launch, open `Stream SBS Receiver` manually.
4. Start `Stream SBS Sender` on the sender phone.
5. Select a video profile if needed.
6. Tap `Start Stream`.
7. Accept the Android screen capture prompt and select the full screen.
8. The receiver displays the stream in SBS mode.

## Receiver Controls

The receiver can be controlled with the volume buttons:

- Volume up: toggle the optional camera overlay when the menu is closed.
- Volume down: open the menu.
- Volume up/down inside the menu: navigate between options.
- Long press volume up: enter or leave edit mode.
- Long press volume down: close the menu.

## Available Settings

- Video resolution.
- Target FPS.
- Video bitrate.
- Camera opacity.
- Horizontal zoom.
- Vertical zoom.
- Horizontal offset.
- Vertical offset.

## Tests

The `common` module includes unit tests for:

- H.264 Annex B and length-prefixed parsing;
- low-latency frame dropping;
- render settings serialization.

Run tests:

```bash
./gradlew test
```

On Windows:

```powershell
.\gradlew.bat test
```

## Release APKs

Installable debug APKs are published from GitHub releases:

- `stream-sbs-sender-<version>-debug.apk`
- `stream-sbs-receiver-<version>-debug.apk`

These APKs are intended for direct testing and local installation. Production signing is not configured in this repository.

## Technical Notes

- The video stream is encoded as H.264 AVC.
- UDP is used to favor low latency.
- Video packets include timestamp, resolution, target FPS, and bitrate metadata.
- The receiver drops stale frames to keep rendering responsive.
- SBS rendering is handled by an OpenGL ES view that draws the stream into two viewports.

## License

This project is distributed under the MIT license. See `LICENSE`.
