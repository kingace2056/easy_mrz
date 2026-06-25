## 1.0.0

- Rebranded the package to `easy_mrz`.
- Reworked the iOS implementation to use Apple Vision.
- Cleaned up the example app and removed CocoaPods scaffolding.
- Preserved credit to the original `flutter_mrz_scanner` project.

## 3.0.0

- Migrated the iOS implementation to Swift Package Manager support.
- Replaced the iOS OCR dependency with Vision-based text recognition.
- Improved MRZ candidate normalization before parsing.
- Updated package constraints for Dart 3 / modern Flutter.

## 2.2.1

* Fix Android 16KB Page size incompatibility by updating tesseract4android to 4.9.0 (by @khamidjon)

## 2.2.0

* Fix repository for 'io.fotoapparat:fotoapparat:2.7.0'
* Update example

## 2.1.1

* Add namespace (by @makhosi6)

## 2.1.0

* Support for Flutter 3.0.5 (by @dadagov125)

## 2.0.1

* Fix : Android crash (by @eusopht2021)

## 2.0.0

* Fix : iOS compiling errors for iOS 15 (by @gdaguin)
* Improvements : on Android, the camera is now focusing automatically (by @gdaguin)
* Port to null safety

## 1.0.0
* Android version redeveloped with Fotoapparat library
* Add overlay widget
* Flashlight on/off
* Take a photo

## 0.7.1

* Provide possibility to disable overlay

## 0.7.0

* First public version with basic iOS and Android scanners
