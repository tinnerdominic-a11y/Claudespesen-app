# Receipts – expense receipt scanner for Android

Photograph receipts, let the app read them, fix anything it got wrong, and export a month at once.

## What it does

- **Scan**: take a photo with the in-app camera (or import one from the gallery).
- **Read automatically**: on-device text recognition (Google ML Kit, works offline) fills in
  company, date, total and currency. Tuned for Swiss/European receipts
  (`TOTAL`, `Summe`, `Total CHF`, `Montant`, `1'234.50`, `12.-`, `1.234,50 €` …), CHF by default.
- **Fix by hand**: every field is editable. Anything the scanner couldn't read is flagged, and
  receipts still missing details show **Needs review** in the list.
- **Month view**: browse month by month with totals per currency.
- **Monthly export** (share button at the top):
  - **PDF**: summary table with totals, then one page per receipt with its details and photo.
  - **Excel (.xlsx)**: one row per receipt with the photo embedded next to it, plus totals per currency.
  The file opens the Android share sheet, so you can email it, save it to Drive, etc.

All data stays on the phone (Room database + photos in app-private storage).

## Install

Download `app-release.apk` from the latest **Build APK** run in GitHub Actions
(Actions tab → latest run → Artifacts → `receipts-apk`), unzip it, copy it to the phone and open it.
Android will ask you to allow installing apps from that source.

## Build locally

Requires JDK 17+ and the Android SDK (Android Studio installs both).

```
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest   # receipt parser + Excel writer tests
```

## Code map

- `ocr/ReceiptParser.kt` – turns recognised text into merchant/date/amount/currency (unit tested)
- `ocr/ReceiptScanner.kt` – ML Kit OCR, joins label and amount columns into one line
- `export/PdfExporter.kt`, `export/ExcelExporter.kt`, `export/XlsxWriter.kt` – monthly exports
- `data/` – Room entity, DAO, repository
- `ui/` – Compose screens: list, camera, edit
