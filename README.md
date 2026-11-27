# Drive Test LTE / 5G — RF Field Suite & Live Fleet NOC

ชุดเครื่องมือทดสอบสัญญาณโครงข่ายมือถือ **5G NR (SA/NSA) / LTE** แบบครบวงจร ประกอบด้วยแอป Android Native (`v1.13.16`), ศูนย์ควบคุม **Live Fleet NOC Dashboard** (`v1.15.0`), **Cloudflare Worker** สำหรับอัปโหลดไฟล์ขึ้น Google Drive ฝั่ง server และ **Firebase Realtime Database** สำหรับซิงก์ข้อมูล Telemetry แบบ Real-Time

- **Repository:** [https://github.com/EEMEEMMEEx/Drive-Test-5G-LTE-RF](https://github.com/EEMEEMMEEx/Drive-Test-5G-LTE-RF)
- **GitHub Pages Landing Page:** [https://EEMEEMMEEx.github.io/Drive-Test-5G-LTE-RF/](https://EEMEEMMEEx.github.io/Drive-Test-5G-LTE-RF/)
- **NOC Dashboard Docs:** [noc-dashboard/README.md](noc-dashboard/README.md)

---

## Repository Structure

| Path | รายละเอียด |
| --- | --- |
| `android-native/` | แอป Android Native (Kotlin + Jetpack Compose, `com.drivetest.lte5g`, `v1.13.16` / `versionCode 60`) |
| `noc-dashboard/` | Live Fleet NOC Console (`v1.15.0`) — Leaflet GIS, Historical Playback, IDW RF Heatmap และ Go local dev server |
| `worker/` | Cloudflare Worker (`drive-upload.js`) สำหรับ proxy `POST /api/drive-upload` และเสิร์ฟไฟล์ static ของ NOC |
| `firebase/` | กฎความปลอดภัยและ index ของ Firebase Realtime Database (`database.rules.json`) |
| `docs/` | GitHub Pages (`index.html`), รายงานวิเคราะห์ และ Runbook ด้าน Release / Security |
| `scripts/` | สคริปต์เสริม (`convert_trails.py`, `extract_apk_details.py`, `generate-pwa-icons.mjs`) |
| `designs/` | ไฟล์ออกแบบ UI (`dashboard.figcraft`) |

---

## Key Features

### 1. Android Native App (`android-native/` · `v1.13.16`)

- **Live Dual-SIM RF Dashboard (`DashboardScreen`):**
  - ตรวจจับและแสดงผลค่าสัญญาณสด **5G NR (SA / NSA) + LTE** รองรับ Dual-SIM (`SIM 1 Primary` และ `SIM 2 Benchmark`), RSRP / RSRQ / SINR / PCI / NCI / Band / EARFCN พร้อมกราฟแนวโน้ม RSRP แบบ Dual-SIM
  - แถบสถานะ RSRP สดพร้อมการตรวจสอบช่วงค่าตามมาตรฐาน 3GPP และเครื่องมือ **RAT Lock** (`RatLockManager`)
- **Interactive Route Map (`DriveTestMapScreen`):**
  - แผนที่ **Google Maps SDK** (`maps-compose`) รองรับ **5 Color Modes**, เลเยอร์เส้นทาง Dual-SIM แยกอิสระ, หมุด `START` / `END` และแถบสถิติเส้นทางสด
- **Crash-Safe Recording & Background Telemetry (`DriveTestForegroundService`):**
  - บันทึกข้อมูลแบบ NDJSON ที่ทนต่อการปิดแอป/crash, ใช้ `WakeLock`, กู้คืนด้วย `START_STICKY`, โหลดตัวอย่างแบบ lazy-load, ตัวกรองพิกัด GPS ค้าง (`stale GPS guard`), แจ้งเตือนสถานะแบบ 2 ภาษา (EN/TH) และกู้คืนคิวอัปโหลดเมื่อกลับมาออนไลน์
- **Sessions & Multi-Format GIS / Report Export (`SessionsScreen`):**
  - ส่งออกข้อมูลได้ 5 รูปแบบ: **CSV**, **3D KMZ / KML** (แยกเลเยอร์ SIM 1/SIM 2 สำหรับ Google Earth), **GeoJSON** (รองรับ Dual-SIM), **MapInfo MIF/MID** (ZIP) และ **PDF Site Acceptance Report**
- **RF Analytics & Gemini AI Diagnostics (`AnalyticsScreen`):**
  - วิเคราะห์สถิติสัญญาณ Dual-SIM, ตรวจจับจุดอับสัญญาณ/สัญญาณรบกวน และสรุปคำแนะนำเชิงวิศวกรรมผ่าน **Gemini RF Advisor** (`GeminiRfAdvisor`)
- **Live Fleet Sync & Auto-Upload (`CloudTelemetrySyncManager`, `GoogleDriveUploadWorker`):**
  - สตรีม Telemetry แบบ Real-Time ขึ้น Firebase RTDB (`/fleet/vehicles`, `/fleet/sessions`, `/fleet/trails`) พร้อมระบบจอง **Vehicle ID** แบบ atomic ผ่าน `/fleet/vehicleClaims` เพื่อป้องกันชื่อรถชนกัน
  - อัปโหลดไฟล์ผลทดสอบขึ้น Google Drive อัตโนมัติบน Wi-Fi ผ่าน `WorkManager` → Cloudflare Worker (`POST /api/drive-upload`)
- **Dual-Channel App Updates (`OtaUpdateManager`, `PlayUpdateManager`):**
  - **Sideload build:** ตรวจสอบและติดตั้งอัปเดตจาก GitHub Releases ผ่าน Android `PackageInstaller` พร้อมตรวจสอบ **SHA-256** ก่อนติดตั้ง (ซ่อน UI ของ Play Store อัตโนมัติ)
  - **Google Play build:** รองรับ Google Play In-App Updates (`app-update-ktx`)

### 2. Live Fleet NOC Dashboard (`noc-dashboard/` · `v1.15.0`)

- แผนที่ Dark NOC (OpenStreetMap + Leaflet 1.9.4) แสดงตำแหน่งรถที่กำลังบันทึกสดทุกคันพร้อมทิศทางหัวรถและเส้นทางสีตามเกณฑ์ RSRP 5 ระดับ
- **Historical Session Playback:** ค้นหา กรองตามรถ เรียงลำดับ และเล่นเส้นทางย้อนหลังด้วย Timeline Scrubber (`1x` / `2x` / `5x`) พร้อมคีย์ลัด
- **Multi-Vehicle RF Coverage Heatmap:** เอนจิน **IDW Interpolation** บน HTML5 Canvas สลับดูได้ทั้ง **RSRP** และ **SINR** พร้อมปรับความโปร่งใส (`15%–95%`)
- **Dual-SIM RF Inspector & Live Anomaly Feed:** เปรียบเทียบ SIM 1 vs SIM 2 พร้อมค่า `Δ RSRP` และแจ้งเตือน `COVERAGE_HOLE` / `INTERFERENCE` แบบคลิกบินไปพิกัดได้ทันที
- รายละเอียดการใช้งานและการรันทั้งหมดดูที่ [noc-dashboard/README.md](noc-dashboard/README.md)

---

## Android Native Build & Configuration

โปรเจกต์รองรับการรัน Gradle Wrapper ได้ทั้งจาก root repo และจากโฟลเดอร์ `android-native/` (ต้องใช้ **JDK 17**, Android `minSdk 29`, `compileSdk / targetSdk 37`)

```powershell
# รันจาก root ของ repo (หรือ cd android-native)
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:bundleRelease   # ต้องตั้งค่า release signing ด้านล่างก่อน
```

สร้างไฟล์ `android-native/local.properties` (git-ignored, ห้าม commit) โดยคัดลอกจาก `android-native/local.properties.example`:

| Key | จำเป็น | ใช้ทำอะไร |
| --- | --- | --- |
| `sdk.dir` | ใช่ | ตำแหน่ง Android SDK |
| `MAPS_API_KEY` | ใช่ | Google Maps SDK |
| `RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` | ใช่ (สำหรับ release) | เซ็น release APK / AAB |
| `CLOUD_UPLOAD_URL` | ไม่บังคับ | endpoint ของ Worker รับอัปโหลด Drive (`https://<worker-host>/api/drive-upload`) |
| `GEMINI_API_KEY`, `FIREBASE_DB_URL`, `FIREBASE_AUTH_TOKEN` | ไม่บังคับ | AI advisor / cloud sync (`FIREBASE_AUTH_TOKEN` ถูกส่งเป็น header `x-app-auth` ด้วย — อย่าใช้ค่าลับร่วมกับระบบอื่น) |

### Security & Release Notes

- **Server-side Drive Upload (`worker/drive-upload.js`):** `GDRIVE_ACCESS_TOKEN` และ `GDRIVE_FOLDER_ID` ถูกถอดออกจาก `local.properties` และ `BuildConfig` แล้ว พร้อม task `guardNoDriveToken` ใน `android-native/app/build.gradle.kts` ที่จะทำให้ build ล้มเหลวทันทีหากมี token `ya29.` หรือ `GDRIVE_ACCESS_TOKEN` หลุดกลับเข้ามาใน `BuildConfig`
  - ตั้งค่า secret ฝั่ง Cloudflare Worker ด้วย `wrangler secret put` (`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REFRESH_TOKEN`, `APP_AUTH_TOKEN`, และ `GDRIVE_FOLDER_ID` ถ้าต้องการ) แล้ว deploy ด้วย `npx wrangler deploy`
  - หากไม่ตั้ง `CLOUD_UPLOAD_URL` การอัปโหลดจะถูกข้ามอย่างปลอดภัย (สถานะ `NONE`) โดยข้อมูล session ยังอยู่ในเครื่องครบ
- **Release Signing:** หากตั้งค่า keystore ไม่ครบ Gradle จะ fallback ไปใช้ debug signing และพิมพ์คำเตือน `Release keystore not configured; falling back to debug signing.` ซึ่งจะไม่สามารถอัปโหลด AAB ขึ้น Play Console หรือติดตั้งทับ release เดิมได้
- **Distribution:** รองรับทั้งการแจกจ่ายผ่าน Play internal testing (Play In-App Updates) และ sideload APK ผ่าน GitHub Releases (PackageInstaller + SHA-256 verification) — ดูรายละเอียดการย้ายช่องทางติดตั้งใน [Release Runbook](docs/play-release-runbook.md)

---

## Cloud Infrastructure (Cloudflare Workers & Firebase RTDB)

```powershell
# Deploy NOC Dashboard + /api/drive-upload Worker (อิง wrangler.jsonc)
npx wrangler dev
npx wrangler deploy

# Deploy Firebase Realtime Database rules (อิง firebase.json + firebase/database.rules.json)
firebase deploy --only database
```

---

## Project Documentation

- [Live Fleet NOC Dashboard Guide](noc-dashboard/README.md)
- [Reverse Engineering Analysis Report](docs/reverse-engineering-report.md)
- [Play Protect บล็อกการติดตั้ง APK (วิเคราะห์ + วิธีแก้ฝั่งผู้ใช้)](docs/play-protect-install.md)
- [Incident: Google OAuth token หลุดไปกับ APK (revoke / audit / rotate)](docs/security-token-incident.md)
- [Runbook: แจกจ่ายผ่าน Play internal testing + ย้ายจาก sideload](docs/play-release-runbook.md)
