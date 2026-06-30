## 1.0.1

- Migrated the Android Gradle setup to Flutter's built-in Kotlin flow.
- Improved Android MRZ detection stability with passport-focused Tesseract heuristics.
- Updated the README and architecture notes to document the real iOS and Android OCR stacks.

## 1.0.0

- Rebranded the package to `easy_mrz`.
- Reworked the iOS implementation to use Apple Vision OCR.
- Reworked the Android implementation around CameraX and local Tesseract-based
  MRZ OCR.
- Added cleaner package documentation and project metadata.
- Preserved credit to the original `flutter_mrz_scanner` project and author.
