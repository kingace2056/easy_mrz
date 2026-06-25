# Architecture

## Overview

`easy_mrz` is a platform-view based Flutter plugin.

Flow:

1. Flutter renders `MRZScanner`.
2. Flutter creates a native platform view with type `easy_mrz_scanner`.
3. Native code starts camera preview when `MRZController.startPreview()` is called.
4. Native OCR extracts likely MRZ text.
5. Native code sends raw normalized text through `onParsed`.
6. Dart parses the text into `MRZResult` using `mrz_parser`.

## Dart Layer

Files:

- `lib/easy_mrz.dart`
- `lib/src/mrz_scanner.dart`
- `lib/src/camera_overlay.dart`

Responsibilities:

- Expose the public widget/controller API.
- Create the native platform view.
- Maintain the method channel per scanner instance.
- Normalize candidate OCR lines again on Dart side.
- Parse MRZ data with `mrz_parser`.

## Android Layer

Files:

- `android/src/main/kotlin/com/mlabs/easy_mrz/EasyMrzPlugin.kt`
- `android/src/main/kotlin/com/mlabs/easy_mrz/CameraXMrzScanner.kt`

Responsibilities:

- Register the Android platform view.
- Attach to the current Flutter activity.
- Render preview with `PreviewView`.
- Use CameraX for preview, analysis, torch, and still capture.
- Use bundled ML Kit text recognition for local OCR.

Important invariants:

- View type: `easy_mrz_scanner`
- Channel name pattern: `easy_mrz_scanner_<id>`
- OCR stays local on device

## iOS Layer

Files:

- `ios/flutter_mrz_scanner/Sources/easy_mrz/EasyMrzPlugin.swift`
- `ios/flutter_mrz_scanner/Sources/easy_mrz/MRZScannerView.swift`

Responsibilities:

- Register the iOS platform view.
- Manage `AVCaptureSession`.
- Run Apple Vision text recognition locally.
- Return normalized OCR text and captured photo data.

## Parsing Strategy

Parsing is intentionally split:

- Native layers optimize for camera access and OCR.
- Dart owns the final MRZ parsing contract.

This keeps platform implementations simpler and reduces duplicated MRZ parsing logic.

## Why This Design

- Flutter API stays stable across native rewrites.
- Native layers can modernize independently.
- OCR remains local.
- The example app is enough for manual testing without extra backend infrastructure.
