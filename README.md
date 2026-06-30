# easy_mrz

`easy_mrz` is a Flutter MRZ scanner for passports, visas, and identity documents
with machine-readable zones.

This package is a revamp of the original
[flutter_mrz_scanner](https://github.com/olexale/flutter_mrz_scanner) by
[Oleksandr Leushchenko](https://github.com/olexale). The public Flutter API was
kept familiar, while the native implementations were reworked for modern iOS
and Android builds.

Agent and tooling notes live in [AGENTS.md](AGENTS.md). Internal implementation
notes live in [doc/ARCHITECTURE.md](doc/ARCHITECTURE.md).

## Highlights

- Local-only OCR on both iOS and Android
- Live camera scanning with Flutter platform views
- Parsed MRZ output through `mrz_parser`
- Optional overlay to guide document placement
- Flashlight control
- Still photo capture with optional crop
- Passport-focused Android heuristics for better TD3 MRZ handling

## Platform Support

- iOS 13+
- Android 21+

## Native OCR Stack

### iOS

`easy_mrz` uses:

- `AVFoundation` for camera preview/capture
- `Vision` (`VNRecognizeTextRequest`) for OCR

Why:

- Apple Vision is fast and highly optimized on Apple hardware
- It keeps OCR fully local on-device
- It avoids shipping third-party OCR models on iOS
- In practice, it is the fastest and most stable path for passport MRZ on iPhone

### Android

`easy_mrz` uses:

- `CameraX` for preview, torch, lifecycle binding, and still capture
- `Tesseract4Android` with bundled `ocrb.traineddata`
- Passport-specific OCR heuristics on top of Tesseract

Why:

- Android OCR quality was inconsistent with the previous generic text pipeline
  on some devices, especially when `ImageAnalysis` frames were negotiated at
  low resolution
- MRZ text uses an OCR-B style character set, which Tesseract can target more
  directly with a whitelist and a bundled traineddata file
- The package needs to remain fully local and offline
- CameraX is the current Android camera stack that best fits Flutter plugin
  lifecycle handling

Tradeoff:

- iOS is generally faster out of the box
- Android requires more normalization and scoring logic because device camera
  behavior varies much more

## Privacy

OCR stays local on the device. The plugin does not send camera frames or MRZ
text to a server.

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

Add camera usage text to `Info.plist`:

```xml
<key>NSCameraUsageDescription</key>
<string>Camera access is required to scan MRZ documents.</string>
```

### Android

Declare camera permission in your app manifest if it is not already present:

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

## Usage

```dart
import 'package:easy_mrz/easy_mrz.dart';
import 'package:flutter/material.dart';

class ScannerPage extends StatelessWidget {
  const ScannerPage({super.key});

  @override
  Widget build(BuildContext context) {
    return MRZScanner(
      withOverlay: true,
      onControllerCreated: (controller) {
        controller.onParsed = (result) {
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

`MRZScanner` provides an `MRZController`.

Methods:

- `startPreview({bool isFrontCam = false})`
- `stopPreview()`
- `flashlightOn()`
- `flashlightOff()`
- `takePhoto({bool crop = true})`

Callbacks:

- `onParsed(MRZResult result)`
- `onError(String message)`

## How Parsing Works

1. Native code captures and OCRs candidate MRZ text.
2. Native code sends normalized candidate lines to Dart through `onParsed`.
3. Dart runs `mrz_parser` to validate and parse the candidate into `MRZResult`.

This split is intentional:

- Native code owns camera and OCR behavior
- Dart owns the final parsing contract
- The Flutter API remains stable even if native OCR changes later

## Android Behavior Notes

Android scanning is tuned to stay local and reasonably fast while still being
strict enough to reject obvious garbage OCR.

Current Android strategy:

- Prefer passport TD3 MRZ detection first
- Crop the lower MRZ band
- OCR each passport line separately
- Repair common OCR substitutions like `0/O`, `1/I`, `5/S`
- Score candidates using MRZ structure and check digits
- Require repeated weaker detections before emitting them

This is more complex than the iOS path because Android device camera behavior
varies more across vendors and frame pipelines.

## Recommendations

For best results:

- Use a real device
- Keep the document inside the overlay
- Use even lighting
- Avoid glare on laminated pages
- Hold the phone steady for a brief moment when Android is close to locking

## Example App

Example identifiers in this repository:

- Plugin package: `com.mlabs.easy_mrz`
- Example Android applicationId: `com.example.easy_mrz`
- Example iOS bundle identifier: `com.example.easymrz`

Run the example:

```bash
cd example
flutter run
```

## Why The Platforms Differ

The plugin does not force the same OCR stack on both platforms.

That is intentional:

- iOS has first-party Vision APIs that are better than shipping a separate OCR
  engine there
- Android benefits from a more controlled OCR-B-oriented approach for MRZ
  scanning, especially on devices where generic text OCR is noisy

The goal is not stack symmetry. The goal is reliable local MRZ scanning.

## Credits

Original project and concept:
[Oleksandr Leushchenko](https://github.com/olexale)

Revamp and maintenance:
[kingace2056](https://github.com/kingace2056)

## License

MIT. See [LICENSE](LICENSE).
