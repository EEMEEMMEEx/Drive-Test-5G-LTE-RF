# Architecture: GSM Drive Test: 5G LTE RF (Web Replica of `com.drivetest.app` v1.8.0)

## Stack

| layer | choice | why |
| --- | --- | --- |
| web | React 19 + TypeScript + Vite | Instant SPA navigation across all 5 screens (`S01`–`S05`), sub-millisecond state updates during 1s RF cadence ticks, zero server latency |
| styling | Tailwind CSS + CSS Custom Properties (`replica/design/tokens.json`) | Exact dark RF telemetry instrumentation theme with verified WCAG AA contrast ratios |
| mapping | Leaflet / Custom Interactive HTML5 Vector Map Canvas | Supports all 5 color modes (`BY_RSRP`, `BY_RSRQ`, `BY_SINR`, `BY_TECHNOLOGY`, `BY_SPEED`), 4 palettes (`CLASSIC`, `THERMIQUE`, `VIRIDIS`, `OCEAN`), sector wedges, and North-Up/Heading-Up rotation without API key lockouts |
| database | Local-first SQLite-compatible store (`IndexedDB` / `localStorage` backed by exact Room schema in `replica/schema.sql`) | Preserves offline-first field drive-test operation just like Android Room SQLite (`DriveTestDatabase`) |
| RF & GPS engine | Browser Geolocation API + Deterministic Multi-Scenario 5G NR / LTE RF Telemetry Simulator | Works with real GPS coordinates in the field while providing rich 5G SA/NSA, Carrier Aggregation, Neighbor Cell, and Handover telemetry on desktop/mobile browsers |
| exports | Native client-side Blob generators (`CSV`, `OpenGIS KML 2.2`, `RFC 7946 GeoJSON`, `PDF`) | Generates byte-accurate engineering exports offline with zero backend dependency |
| i18n | Built-in 7-locale dictionary (`EN`, `FR`, `DE`, `ES`, `IT`, `PT`, `NL`) | Matches every language shipped in the original XAPK splits |

## Schema

Tables: 4 (`campaigns`, `measurements`, `speed_tests`, `session_events`). Access rules: Local-first single-operator data store with cascading deletes (`ON DELETE CASCADE`) matching Android Room foreign key constraints.

See full DDL in [`replica/schema.sql`](file:///d:/APP/Drive-Test-5G-LTE-RF/replica/schema.sql).

```sql
-- Summary of the 4 core tables (full DDL in replica/schema.sql):
-- 1. campaigns (id PK, name, startTime, endTime, measurementCount, distanceMeters, isActive, speedTest*, alertThresholdRsrp)
-- 2. measurements (id PK, campaignId FK, timestampMs, simSlot, lat/lon/alt/speed/acc/bearing, tech/mcc/mnc/cellId/lac/pci/band/earfcn, rssi/rsrp/rsrq/sinr/csi*, nrStatus, aggregatedBandsJson, neighborCellsJson)
-- 3. speed_tests (id PK, campaignId FK, timestampMs, status, downloadMbps, uploadMbps, pingMs, jitterMs, loadedPingMs, lat/lon, tech/band/cellId/pci/rsrp)
-- 4. session_events (id PK, campaignId FK, type ['PAUSE'|'LIGHT_PROBE'], timestampMs, durationMs, pingMs, jitterMs, success, lat/lon, tech/cellId)
```

## API & Client Service Actions

| action / method | does | who | input | output | flow |
| --- | --- | --- | --- | --- | --- |
| `campaignService.start(preset, cadenceMs, name, alertThresholdRsrp)` | Creates active `Campaign` and starts cadence timer (`1s`/`2s`/`5s`) | Operator | `CampaignStartParams` | `Campaign` | `F01` |
| `campaignService.pause() / resume()` | Pauses/resumes sampling and writes `PAUSE` event with `durationMs` to `session_events` | Operator | `campaignId` | `SessionEvent` | `F01` |
| `campaignService.stop()` | Finalizes `endTime`, computes `FinishedCampaignSummary`, marks `isActive = 0` | Operator | `campaignId` | `FinishedCampaignSummary` | `F01` |
| `telemetryEngine.sampleTick(simSlot)` | Samples live/simulated `RadioInfo` + `GpsInfo`, appends `Measurement`, updates `distanceMeters` via Haversine formula | Cadence Worker | `simSlot` | `Measurement` | `F01` |
| `speedTestRunner.execute(campaignId)` | Runs phased Ping, Jitter, Loaded Ping, Download, and Upload test; persists `SpeedTest` and updates `Campaign` summary fields | Operator | `campaignId` | `SpeedTestResult` | `F02` |
| `lightProbeRunner.tick(campaignId)` | Performs lightweight latency/jitter check and inserts `LIGHT_PROBE` row into `session_events` | Background Timer | `campaignId` | `LightNetworkProbe` | `F02` |
| `analyticsEngine.analyze(campaignId)` | Computes `SignalStats`, empirical CDF percentiles (`P10`/`P50`/`P90`), `MobilityStats` (`HandoverEvent`, `PingPong < 30s`, `MissedHandoverEvent >= 6 dB`), `BandUsage`, `CellSite` centroids, and `CampaignDiagnostic` (`0–100`) | Operator / UI | `campaignId` | `AnalyticsBundle` | `F03`, `F05` |
| `analyticsEngine.compare(campaignAId, campaignBId)` | Computes spatial grid & aggregate KPI deltas (`ΔRSRP`, `ΔRSRQ`, `ΔSINR`, handover delta) | Operator | `campaignAId, campaignBId` | `CampaignComparisonUiState` | `F05` |
| `exportService.exportCampaign(campaignId, format)` | Serializes campaign & measurements into `CSV` (24 cols), `KML` (OpenGIS 2.2), `GeoJSON`, or printable `PDF` report | Operator | `campaignId, 'CSV'\|'KML'\|'GEOJSON'\|'PDF'` | `Blob / Download` | `F04` |

Webhooks in: None (local-first field tool). Webhooks out: None.
Jobs:
- `CadenceSamplerJob`: Runs every `1000ms` (Precise), `2000ms` (Standard), or `5000ms` (Eco) while a campaign is active and unpaused.
- `LightProbeJob`: Runs every `10000ms` when Light Network Monitoring is enabled during an active campaign.

## The parts that bite

- **Ping-Pong vs Normal Handover Window**: A handover from `Cell A -> Cell B -> Cell A` must only be flagged as `isPingPong = true` when $\Delta t < 30,000\text{ ms}$; longer intervals are normal route traversal.
- **Missed Handover Margin**: Must parse `neighborCellsJson` at each sample and check `bestNeighbor.rsrp - serving.rsrp >= 6 dB` while `serving.rsrp <= -95 dBm` so strong-signal cell centers do not trigger false alarms.
- **Exact CSV Column Compatibility**: Downstream RF post-processing tools (QGIS, MapInfo, Actix) expect the exact 24-column header (`timestamp,latitude,longitude,altitude,speed_ms,accuracy_m,bearing,gps_valid,technology,operator,mcc,mnc,cell_id,lac_tac,pci,band,earfcn,rssi_dbm,rsrp_dbm,rsrq_db,sinr_db,registered,connection_state`).
- **Pause Duration Exclusion**: When a campaign is paused, `PAUSE` events in `session_events` record `durationMs`, and Haversine distance accumulation pauses until resumed.
- **5G NR SA vs NSA Representation**: In `5G NR NSA` (EN-DC), the primary anchor is LTE (`B3`/`B1`) while secondary carriers (`aggregatedBandsJson`) include `n78` (`3500 MHz`, `100 MHz BW`) alongside `csiRsrp`/`csiRsrq`/`csiSinr`.

## Build order

1. **Vertical slice**: `replica/schema.sql` data layer + RF telemetry simulator + `S01` Live Dashboard (`RfSignalGauge`, `ServingCellCard`, `CampaignStartModal`) + `S03` CSV export.
2. **Must-haves**: `S02` Interactive Route Map (5 color modes, 4 palettes, handover/missed-handover overlays), `S03` KML/GeoJSON exports, `S04` 7-tab Analytics & Route Diagnosis (`0–100` scoring + 5 issue types).
3. **Should-haves**: Speed Test runner, Light Network Probe, Car Mode, Finished Campaign modal, Advanced Route Analysis (`Map / Timeline / Cells` scrubber), Campaign Comparison (`A vs B`), PDF report export, Undo delete.
4. **Could-haves**: `S05` 5-Step Spotlight Tutorial, `SignalWidget` & `CampaignWidget` Glance previews, and 7-language localization (`EN`, `FR`, `DE`, `ES`, `IT`, `PT`, `NL`).
