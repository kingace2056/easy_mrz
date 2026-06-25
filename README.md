# easy_mrz

`easy_mrz` is a Flutter MRZ scanner for passports, IDs, visas, and other travel documents with machine readable zones.

This repository is a revamp of the original
[flutter_mrz_scanner](https://github.com/olexale/flutter_mrz_scanner) project.
The rewrite keeps the same core plugin API while modernizing the package name,
documentation, and native iOS implementation.

Agent and tooling notes live in [AGENTS.md](/Users/sarthak/Documents/Acid/eleven_hype_flutter/packages/flutter_mrz_scanner/AGENTS.md) and [docs/ARCHITECTURE.md](/Users/sarthak/Documents/Acid/eleven_hype_flutter/packages/flutter_mrz_scanner/docs/ARCHITECTURE.md).

## What It Does

- Scans MRZ lines from live camera preview.
- Parses MRZ text into structured data with `mrz_parser`.
- Supports an optional document overlay to guide alignment.
- Lets you start and stop scanning from Dart.
- Can toggle the flashlight on supported devices.
- Can capture a still photo with optional crop.
- Uses Apple Vision on iOS instead of the old Tesseract path.
- Uses CameraX and on-device ML Kit text recognition on Android.

## Supported Platforms

- iOS 13+
- Android 21+

## Example App Ids

- Plugin package: `com.mlabs.easy_mrz`
- Example Android applicationId: `com.example.easy_mrz`
- Example iOS bundle identifier: `com.example.easy_mrz`

## Installation

```yaml
dependencies:
  easy_mrz: ^1.0.0
```

Then run:

```bash
flutter pub get
```

## Permissions

### iOS

Add a camera usage description in `Info.plist`:

```xml
<key>NSCameraUsageDescription</key>
<string>Camera access is required to scan MRZ documents.</string>
```

### Android

If your host app does not already include it, declare camera permission in the
app manifest:

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

If you are using the example app, this is already included.

## Usage

```dart
import 'package:easy_mrz/easy_mrz.dart';

class ScannerPage extends StatelessWidget {
  const ScannerPage({super.key});

  @override
  Widget build(BuildContext context) {
    return MRZScanner(
      withOverlay: true,
      onControllerCreated: (controller) {
        controller.onParsed = (result) {
          // Handle MRZResult here.
          debugPrint('MRZ parsed: ${result.toJson()}');
        };

        controller.onError = (message) {
          debugPrint('Scanner error: $message');
        };

        controller.startPreview();
      },
    );
  }
}
```

## Controller API

The `MRZScanner` widget gives you an `MRZController`.

Available methods:

- `startPreview({bool isFrontCam = false})`
- `stopPreview()`
- `flashlightOn()`
- `flashlightOff()`
- `takePhoto({bool crop = true})`

Callbacks:

- `onParsed(MRZResult result)`
- `onError(String message)`

## Features In Detail

### Live Parsing

The scanner continuously analyzes the camera feed and emits MRZ results once a
valid parse is found.

### iOS Vision OCR

The iOS implementation uses Apple Vision text recognition.

### Android CameraX + ML Kit

The Android implementation uses CameraX for preview/capture and bundled ML Kit
text recognition for local on-device OCR.

### Overlay

Set `withOverlay: true` to show the guide frame around the expected document
region.

### Still Capture

Use `takePhoto()` if you want the raw image bytes, or pass `crop: true` to crop
the document region before returning the image.

### Front Camera Support

The controller can switch to the front camera with:

```dart
controller.startPreview(isFrontCam: true);
```

## Recommended Usage

For the best scan rate and reliability:

- Use a real device instead of an emulator or simulator.
- Hold the document inside the overlay frame.
- Make sure lighting is even.
- Avoid strong glare on laminated pages.

## Example

Run the example app from the `example/` folder:

```bash
cd example
flutter run
```

## Migration Notes

This revamp was done to:

- Rebrand the package to `easy_mrz`
- Modernize the iOS scanning path
- Replace the Android Fotoapparat/Tesseract stack with CameraX and ML Kit
- Improve OCR normalization
- Improve MRZ parsing stability
- Clean up the plugin structure
- Add clearer documentation

## Credits

Original project:
[Oleksii Leushchenko](https://github.com/olexale)

Revamp and maintenance:
[kingace2056](https://github.com/kingace2056)

## License

MIT. See [LICENSE](/LICENSE).
