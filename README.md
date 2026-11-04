# RF Field Suite — 5G NR / LTE Drive Test & Route Diagnostics (`v1.10.8`)

A local-first web application replicating the full capabilities, UI screens, data schema, and RF engineering analytics of **GSM Drive Test: 5G LTE RF (`com.drivetest.app` v1.8.0)**, built following the [replica-skill](https://github.com/Jakeschincariol/replica-skill) clean-room methodology, complete with PWA offline-first support and Android Capacitor/Native runtime.

- **Live Web App:** [https://sigpath-web.saweksoot.workers.dev/](https://sigpath-web.saweksoot.workers.dev/)
- **GitHub Pages Landing Page:** [https://EEMEEMMEEx.github.io/Drive-Test-5G-LTE-RF/](https://EEMEEMMEEx.github.io/Drive-Test-5G-LTE-RF/)

## Key Features

- **PWA & Android Native Runtime (v1.10.8):**
  - **PWA Offline-First:** Service worker precaching, Web App Manifest, offline map tile caching (`stale-while-revalidate` / `network-first`), and standalone install prompt.
  - **Capacitor Android Wrapper:** Bridge plugin (`DriveTestPlugin.java`) with foreground service (`DriveTestForegroundService.java`) for real cellular telephony radio metrics and GNSS location tracking on device.
  - **Android Native Project (`android-native/`):** Standalone Kotlin Jetpack Compose app (`v1.8.3`) with Dynamic Island Fluid Cloud capsule around front camera (with clean RF telemetry controls and suppressed audio scrubber), In-App OTA Updates, Google Maps SDK, 3D KMZ export with Google Earth styling, VectorCraft official logo, real-time telephony collector, and background foreground service.
- **Screen S01 — Live Drive Test Dashboard (`DriveTestDashboard`):**
  - Real-time semi-circular RSRP gauge (`-135` to `-55 dBm`) with live RSRQ, SINR, RSSI, CSI-RSRP, CQI (`1..15`), and Timing Advance (`TA`).
  - Serving Cell & **5G NR (`SA` / `NSA`) + LTE Carrier Aggregation (`CA`)** component monitor (`Band`, `EARFCN`, `DL Frequency`, `Bandwidth`, `PCI`, `Cell ID`, `TAC/LAC`, `PLMN`).
  - Dual-SIM slot selector (`SIM 1 · AIS 5G SA`, `SIM 2 · TRUE-DTAC 5G`) and Sampling Cadence selector (`Eco · 5 s`, `Standard · 2 s`, `Precise · 1 s`).
  - GNSS / GPS Telemetry (`Latitude`, `Longitude`, `Altitude`, `Speed km/h`, `Bearing`, `Accuracy`) supporting both **Browser Geolocation API** and the **5G/LTE RF Route Simulator** (`Urban Route`, `Highway`, `Site Audit`, `Indoor`).
  - Neighboring Cells table with live RSRP delta calculation, configurable Weak Signal RSRP Alert banner, Active Throughput & Latency Speed Test (`PING` -> `DOWNLOAD` -> `UPLOAD` -> `DONE`), Continuous Light Network Probe, and fullscreen **Car HUD Mode**.
- **Screen S02 — Interactive Route Map (`RouteMapView`):**
  - Interactive Leaflet map + Vector Route Inspector supporting **5 Color Modes** (`BY_RSRP`, `BY_RSRQ`, `BY_SINR`, `BY_TECHNOLOGY`, `BY_SPEED`) and **4 Color Palettes** (`CLASSIC`, `THERMIQUE`, `VIRIDIS`, `OCEAN`).
  - Layer toggles for **Serving Sector Lines**, **Neighbor Candidates**, **Handover Events** (including `<30 s` Ping-Pong detection), and **Missed Handovers** (`marginDb >= 6 dB`).
- **Screen S03 — Campaigns Manager & Multi-Format Exporter (`CampaignListView`):**
  - Searchable campaign list with RSRP sparklines, multi-select batch operations, and Delete with Undo.
  - Exports to **CSV** (exact 23-column APK schema), **OpenGIS KML 2.2** (`.kml`), **GeoJSON** (`.geojson`), and printable **PDF Engineering Report**, plus **CSV Log Import**.
- **Screen S04 — 8-Tab Analytics & Automated Route Diagnostics (`AnalyticsView`):**
  - `Signal` (Stats & RSRP/RSRQ time series), `CDF` (Cumulative Distribution Function with P10/P50/P90), `Network` (2G/3G/4G/5G breakdown & 5G NR SA+NSA usage %), `Bands` (Frequency band breakdown), `Trace` (2D heatmap), `Sites & Mobility` (Cell site inventory + Handover/Ping-Pong/Missed Handover log), `Diagnosis` (Automated 0–100 Route Score, configurable `DiagnosticThresholds`, and 5 problem area types), `Advanced Correlation` (`MAP`, `TIMELINE`, and `CELLS` correlation views), and spatial **Campaign Comparison** (`RSRP` / `RSRQ` / `SINR` delta).
- **Screen S05 — Home Screen Widgets HUD & Interactive Spotlight Guide:**
  - Live preview of `GSM Signal Widget` and `GSM Campaign Widget` + 5-step interactive spotlight tutorial and 5-language support (`EN`, `TH`, `FR`, `DE`, `ES`).

## Project Documentation & Replica Artifacts

- [Reverse Engineering Analysis Report](docs/reverse-engineering-report.md)
- [Replica Recon Map](replica/recon.md) & [Feature Matrix (`features.csv`)](replica/features.csv)
- [Replica Architecture](replica/architecture.md) & [SQL Schema (`schema.sql`)](replica/schema.sql)
- [Design Tokens (`tokens.json`)](replica/design/tokens.json) & [Component Specs](replica/design/components.md)
- [Test Plan](replica/test-plan.md) & [Bug Log](replica/bugs.md)

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

