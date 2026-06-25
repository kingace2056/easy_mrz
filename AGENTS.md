# easy_mrz Agent Guide

This repository is a Flutter plugin for MRZ scanning on iOS and Android.

## Goal

- Keep the public Dart API stable unless a breaking change is intentional.
- Keep OCR local on device.
- Prefer modern native stacks:
  - iOS: Apple Vision
  - Android: CameraX + bundled ML Kit text recognition

## Public API

Primary export:

- `lib/easy_mrz.dart`

Main widget and controller:

- `MRZScanner`
- `MRZController`

Important behavior:

- The Flutter side expects the native platform view type to stay `easy_mrz_scanner`.
- The Flutter side expects per-instance method channels named `easy_mrz_scanner_<id>`.
- Native code should emit:
  - `onParsed`
  - `onError`

## Repository Layout

- `lib/`
  - Public Dart API and MRZ parsing glue.
- `android/`
  - Android plugin implementation.
  - Current stack: CameraX + ML Kit.
- `ios/flutter_mrz_scanner/`
  - iOS Swift Package plugin implementation.
  - Current stack: Apple Vision.
- `example/`
  - Demo application for manual validation.

## Local-Only Requirement

Keep OCR local.

- Do not switch Android OCR to Play Services remote-delivered models.
- Prefer bundled or on-device processing only.
- Do not add network-dependent scanning behavior.

## Build and Validation

Root package:

```bash
flutter pub get
flutter analyze
```

Example app:

```bash
cd example
flutter pub get
flutter run
```

Useful validation commands:

```bash
cd example
flutter build apk --debug
flutter build ios --simulator --no-codesign
```

## Android Notes

- Plugin entrypoint is declared in `pubspec.yaml`.
- Keep Android package name aligned with:
  - `com.mlabs.easy_mrz`
- Host apps need camera permission.
- The plugin manifest already contributes:
  - `android.permission.CAMERA`

## iOS Notes

- Swift Package root:
  - `ios/flutter_mrz_scanner/Package.swift`
- Plugin class:
  - `EasyMrzPlugin`
- Example iOS bundle identifier must remain a valid Apple identifier.
- Underscores are fine for Android ids, but avoid them in iOS bundle ids.

## Editing Guidance

- Prefer additive changes to docs and architecture notes over silent native behavior changes.
- If changing native channel names or platform view ids, update Dart and both platforms together.
- If changing OCR normalization, verify both native output and Dart-side MRZ parsing still align.

## Known Expectations

- The example app requests camera permission in Dart.
- The Android platform view must not do heavy startup work before the Flutter side sizes the view.
- The scanner widget should receive bounded layout constraints from Flutter.
