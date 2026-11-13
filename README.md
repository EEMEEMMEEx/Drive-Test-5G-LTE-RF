# RF Field Suite — 5G NR / LTE Drive Test & Route Diagnostics (`v1.14.0`)

A local-first web application replicating the full capabilities, UI screens, data schema, and RF engineering analytics of **GSM Drive Test: 5G LTE RF (`com.drivetest.app` v1.8.0)**, built following the [replica-skill](https://github.com/Jakeschincariol/replica-skill) clean-room methodology, complete with PWA offline-first support and Android Capacitor/Native runtime.

- **Live Web App:** [https://sigpath-web.saweksoot.workers.dev/](https://sigpath-web.saweksoot.workers.dev/)
- **GitHub Pages Landing Page:** [https://EEMEEMMEEx.github.io/Drive-Test-5G-LTE-RF/](https://EEMEEMMEEx.github.io/Drive-Test-5G-LTE-RF/)

## Key Features

- **PWA & Android Native Runtime (v1.14.0):**
  - **PWA Offline-First:** Service worker precaching, Web App Manifest, offline map tile caching (`stale-while-revalidate` / `network-first`), and standalone install prompt.
  - **Capacitor Android Wrapper:** Bridge plugin (`DriveTestPlugin.java`) with foreground service (`DriveTestForegroundService.java`) for real cellular telephony radio metrics and GNSS location tracking on device.
  - **Android Native Project (`android-native/`):** Standalone Kotlin Jetpack Compose app (`v1.13.3`) with dual-SIM benchmark logging (live SIM 2 dashboard gauges, dual-SIM route map layers with 5 color modes, isolated KMZ/CSV SIM layers), route START/END markers with live route stats chips (distance, time, avg RSRP), In-Place Telemetry Dynamic Island Fluid Cloud capsule (with Telemetry HUD tile, custom Stop/New Session actions, and suppressed audio scrubber), Play In-App Updates, Google Maps SDK, 3D KMZ export with Google Earth styling, VectorCraft official logo, real-time telephony collector, and background foreground service.
- **Screen S01 — Live Drive Test Dashboard (`DriveTestDashboard`):**
  - Real-time semi-circular RSRP gauge (`-135` to `-55 dBm`) with live RSRQ, SINR, RSSI, CSI-RSRP, CQI (`1..15`), and Timing Advance (`TA`).
  - Serving Cell & **5G NR (`SA` / `NSA`) + LTE Carrier Aggregation (`CA`)** component monitor (`Band`, `EARFCN`, `DL Frequency`, `Bandwidth`, `PCI`, `Cell ID`, `TAC/LAC`, `PLMN`).
  - Dual-SIM slot selector (`SIM 1 · AIS 5G SA`, `SIM 2 · TRUE-DTAC 5G`), Sampling Cadence selector (`Eco · 5 s`, `Standard · 2 s`, `Precise · 1 s`), and **Dual-SIM Benchmark logging toggle** (SIM 2 generation on/off).
  - **SIM 1 LIVE** card with RAT badge + **SIM 2 LIVE** card (operator, RAT, RSRP/RSRQ/SINR) + **dual-SIM RSRP trend chart** with legend (SIM 1 solid / SIM 2 dashed).
  - GNSS / GPS Telemetry (`Latitude`, `Longitude`, `Altitude`, `Speed km/h`, `Bearing`, `Accuracy`) supporting both **Browser Geolocation API** and the **5G/LTE RF Route Simulator** (`Urban Route`, `Highway`, `Site Audit`, `Indoor`).
  - Neighboring Cells table with live RSRP delta calculation, configurable Weak Signal RSRP Alert banner, Active Throughput & Latency Speed Test (`PING` -> `DOWNLOAD` -> `UPLOAD` -> `DONE`), Continuous Light Network Probe, and fullscreen **Car HUD Mode**.
- **Screen S02 — Interactive Route Map (`RouteMapView`):**
  - Interactive Leaflet map + Vector Route Inspector supporting **5 Color Modes** (`BY_RSRP`, `BY_RSRQ`, `BY_SINR`, `BY_TECHNOLOGY`, `BY_SPEED`) and **4 Color Palettes** (`CLASSIC`, `THERMIQUE`, `VIRIDIS`, `OCEAN`).
  - Layer toggles for **Serving Sector Lines**, **Neighbor Candidates**, **Handover Events** (including `<30 s` Ping-Pong detection), **Missed Handovers** (`marginDb >= 6 dB`), and **SIM 2 Route** overlay (dashed dual-SIM polyline).
  - Route **START/END markers** + floating stats chips (**Samples**, **Dist**, **Time**, **Avg RSRP**, **Handovers**, **Missed HO**).
- **Screen S03 — Campaigns Manager & Multi-Format Exporter (`CampaignListView`):**
  - Searchable campaign list with RSRP sparklines, multi-select batch operations, Delete with Undo, and per-campaign **Dual-SIM Benchmark** block (SIM 1/2 avg RSRP & SINR, RSRP/SINR Δ chips, WINNER badge).
  - Exports to **CSV** (exact 23-column APK schema), **OpenGIS KML 2.2** (`.kml`), **GeoJSON** (`.geojson`), and printable **PDF Engineering Report**, plus **CSV Log Import**.
- **Screen S04 — 8-Tab Analytics & Automated Route Diagnostics (`AnalyticsView`):**
  - `Signal` (Stats & RSRP/RSRQ time series), `CDF` (Cumulative Distribution Function with P10/P50/P90), `Network` (2G/3G/4G/5G breakdown & 5G NR SA+NSA usage %), `Bands` (Frequency band breakdown), `Trace` (2D heatmap), `Sites & Mobility` (Cell site inventory + Handover/Ping-Pong/Missed Handover log), `Diagnosis` (Automated 0–100 Route Score, configurable `DiagnosticThresholds`, and 5 problem area types), `Advanced Correlation` (`MAP`, `TIMELINE`, and `CELLS` correlation views), and spatial **Campaign Comparison** (`RSRP` / `RSRQ` / `SINR` delta).
- **Screen S05 — Home Screen Widgets HUD & Interactive Spotlight Guide:**
  - Live preview of `GSM Signal Widget` and `GSM Campaign Widget` + 5-step interactive spotlight tutorial and 5-language support (`EN`, `TH`, `FR`, `DE`, `ES`).

## Project Documentation & Replica Artifacts

- [Reverse Engineering Analysis Report](docs/reverse-engineering-report.md)
- [Play Protect บล็อกการติดตั้ง APK (วิเคราะห์ + วิธีแก้ฝั่งผู้ใช้)](docs/play-protect-install.md)
- [Incident: Google OAuth token หลุดไปกับ APK (revoke / audit / rotate)](docs/security-token-incident.md)
- [Runbook: แจกจ่ายผ่าน Play internal testing + ย้ายจาก sideload](docs/play-release-runbook.md)
- [Replica Recon Map](replica/recon.md) & [Feature Matrix (`features.csv`)](replica/features.csv)
- [Replica Architecture](replica/architecture.md) & [SQL Schema (`schema.sql`)](replica/schema.sql)
- [Design Tokens (`tokens.json`)](replica/design/tokens.json) & [Component Specs](replica/design/components.md)
- [Test Plan](replica/test-plan.md) & [Bug Log](replica/bugs.md)

## Android Native Build (`android-native/`)

แอป Kotlin + Jetpack Compose อยู่ใน `android-native/` ใช้ Gradle wrapper ของตัวเอง (ไม่ใช้ `npm`)

```powershell
cd android-native
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:bundleRelease   # ต้องตั้งค่า release signing ด้านล่างก่อน
```

ตั้งค่า `android-native/local.properties` (git-ignored, ห้าม commit) โดยคัดลอกจาก `local.properties.example`

| Key | จำเป็น | ใช้ทำอะไร |
| --- | --- | --- |
| `sdk.dir` | ใช่ | ตำแหน่ง Android SDK |
| `MAPS_API_KEY` | ใช่ | Google Maps SDK |
| `RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` | ใช่ (สำหรับ release) | เซ็น release build / AAB |
| `CLOUD_UPLOAD_URL` | ไม่บังคับ | endpoint ของ Worker รับอัปโหลด Drive: `https://<worker-host>/api/drive-upload` (ยังไม่ deploy — ดูหมายเหตุด้านล่าง) |
| `GEMINI_API_KEY`, `FIREBASE_DB_URL`, `FIREBASE_AUTH_TOKEN` | ไม่บังคับ | cloud sync / telemetry เสริม (`FIREBASE_AUTH_TOKEN` ถูกใช้เป็น header `x-app-auth` ด้วย — อย่าใช้ค่าที่ละเอียดอ่อนร่วมกัน) |

การเปลี่ยนแปลงที่ต้องรู้:

- **`GDRIVE_ACCESS_TOKEN` และ `GDRIVE_FOLDER_ID` ถูกถอดออกจาก `local.properties` และ `BuildConfig` แล้ว** — ค่าเดิมเคยถูกฝังลง APK ที่แจก ที่แทนคือการอัปโหลดผ่าน Cloudflare Worker ที่ถือ credential ของ Google ฝั่ง server (`worker/drive-upload.js`, route `POST /api/drive-upload`, ผูกผ่าน `wrangler.jsonc:7`): ตั้ง secret ด้วย `wrangler secret put GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` / `GOOGLE_REFRESH_TOKEN` / `APP_AUTH_TOKEN` / `GDRIVE_FOLDER_ID` (ตรวจด้วย `wrangler secret list`) แล้ว `wrangler deploy` และตั้ง `CLOUD_UPLOAD_URL` ฝั่งแอป ห้ามใส่ token ของ Google ลง `local.properties`, `BuildConfig` หรือไฟล์ที่ commit อีก — ดู [Incident runbook](docs/security-token-incident.md) (รวมถึง follow-up เรื่อง `x-app-auth` ที่ยังอิง `FIREBASE_AUTH_TOKEN` ซึ่งฝังอยู่ใน APK)
  - **ยังไม่ deploy ณ 2026-11-12:** ถ้าไม่ตั้ง `CLOUD_UPLOAD_URL` การอัปโหลดจะถูกข้าม (status `NONE`) และ session ยังอยู่ในเครื่องครบ ไม่มี data loss; ถ้าตั้งแล้ว endpoint ล่มจะได้ status `FAILED` + retry 3 ครั้ง ไม่ crash
- **release build ต้องเซ็นด้วย release keystore** — ถ้าตั้งค่าไม่ครบ Gradle จะ fallback ไป debug signing (`android-native/app/build.gradle.kts` ในบล็อก `signingConfigs`) ซึ่งทำให้ AAB อัปโหลด Play ไม่ผ่าน และผู้ใช้ติดตั้งทับตัวเดิมไม่ได้เพราะ signature ไม่ตรง ต้องเห็นว่า build log ไม่มีบรรทัด `Release keystore not configured; falling back to debug signing.`
- **การแจกจ่ายเปลี่ยนจาก APK บน GitHub Releases + OTA ที่ติดตั้งเอง → Play internal testing + Play In-App Updates** การย้ายจาก sideload ต้อง export session ก่อนถอนแอป ดู [Release runbook](docs/play-release-runbook.md)

## Quick Start & Verification

```bash
# Install dependencies
npm install

# Run unit & component test suite (Vitest)
npm test

# Run TypeScript typecheck & production build
npm run typecheck
npm run build

# Run replica-skill verification tools
python replica-skill/replica-diff/parity.py replica/features.csv --fail-under 100
python replica-skill/replica-design/contrast.py replica/design/tokens.json
python replica-skill/replica-brand/sweep.py src --config replica/brand.json
```

