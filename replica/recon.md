# Recon map: GSM Drive Test: 5G LTE RF (Android -> Web Application Clone)

Scope: Complete 5G NR / LTE / WCDMA / GSM RF drive-test telemetry suite, campaign recorder, interactive multi-palette route map, 7-tab analytics + route diagnostics engine, campaign comparison, and CSV/KML/GeoJSON/PDF exporter.
For: RF field engineers, telecom network optimizers, and cellular coverage analysts needing a full-featured browser-based & mobile-ready drive-test workbench.
Date: 2026-11-02

## Sources

| # | source | URL | notes |
| --- | --- | --- | --- |
| 1 | XAPK Package (`com.drivetest.app` v1.8.0) | `file:///d:/APP/Drive-Test-5G-LTE-RF/GSM+Drive+Test_+5G+LTE+RF_1.8.0.xapk` | Base APK, `classes.dex`–`classes3.dex`, Room schema, Compose UI tree |
| 2 | Reverse Engineering Report | `file:///d:/APP/Drive-Test-5G-LTE-RF/docs/reverse-engineering-report.md` | Complete SQLite DDL, domain models, RF algorithms, and screen specs |
| 3 | Android String & Localization Resources | `res/values-{en,fr,de,es,it,pt,nl}/strings.xml` | 7 languages, preset labels, diagnostic descriptions, CSV/KML headers |
| 4 | Room Database Schema (`DriveTestDatabase`) | `com/drivetest/app/data/local/DriveTestDatabase_Impl.java` | Exact 4 tables (`campaigns`, `measurements`, `speed_tests`, `session_events`) |

## Core loop

Start a geotagged RF measurement campaign with a preset/cadence, monitor live 5G NR / LTE serving & neighbor cells on the dashboard or route map, run throughput/latency probes, and diagnose coverage/handover issues or export standard engineering files (CSV, KML, GeoJSON, PDF).

## Screens

| ID | screen | route / how to reach | purpose | key components | states seen |
| --- | --- | --- | --- | --- | --- |
| S01 | Live Drive Test Dashboard | `/dashboard` (bottom nav tab 1) | Real-time RF gauge, serving cell + 5G NR CSI + Carrier Aggregation, GPS telemetry, neighbor cells, speed test, light probe, and campaign recording controls | `RfSignalGauge`, `ServingCellCard`, `CaComboBadge`, `GpsTelemetryBar`, `NeighborCellTable`, `CampaignStartModal`, `SpeedTestRunner`, `CarModeView`, `FinishedCampaignModal` | idle, recording, paused, weak-signal-alert, speed-testing, car-mode, campaign-finished |
| S02 | Interactive Route Map | `/map` (bottom nav tab 2) | Geotagged RF route visualization with 5 metric color modes, 4 palettes, threshold sliders, and cell/handover overlays | `RouteMapCanvas`, `MapColorModeSelector`, `PalettePicker`, `ThresholdSliderSheet`, `OverlayToggles`, `PointInspectorSheet` | empty, live-following, north-up, heading-up, point-selected, dark-map, light-map |
| S03 | Campaigns List & Export Manager | `/campaigns` (bottom nav tab 3) | Browse, search, compare, delete (with Undo), and export recorded campaigns in CSV, KML, GeoJSON, or PDF | `CampaignSearchBar`, `CampaignCard`, `RsrpSparkline`, `AvgRsrpBadge`, `ExportFormatSheet`, `UndoSnackbar` | empty, filled, filtered, multi-select, exporting, undo-delete |
| S04 | Analytics & Route Diagnostics | `/analytics` (bottom nav tab 4 or from S01/S03) | 7-tab deep RF analysis (`Signal`, `CDF`, `Network`, `Bands`, `Trace`, `Sites`, `Diagnosis`), correlated `Map / Timeline / Cells` route analyzer, and `A vs B` campaign comparison | `AnalyticsTabBar`, `SignalTimeSeriesChart`, `CdfCurveChart`, `BandUsageTable`, `HandoverTraceList`, `CellSiteInventory`, `DiagnosisScorecard`, `RouteTimelineScrubber`, `CampaignCompareView` | empty, single-campaign, advanced-route-view, compare-two-campaigns |
| S05 | Spotlight Tutorial, Widgets & Settings | Modal overlay / `/widgets` drawer from header | 5-step interactive spotlight onboarding, live `SignalWidget` & `CampaignWidget` previews, RF simulation scenario switcher, and 7-language switcher | `SpotlightTourOverlay`, `SignalWidgetPreview`, `CampaignWidgetPreview`, `LocaleSelector` | step-1..5, widget-preview, active-locale |

## Flows

```
F01 Start, record, and finish an RF Drive Test Campaign
    S01 Dashboard -> click "Start Campaign" -> pick Preset (Site audit / Urban / Highway / Indoor), Cadence (1s / 2s / 5s) & RSRP Alert Threshold -> record samples + optional Pause/Resume + Light Probe -> click "Stop" -> Finished Campaign Summary Modal -> S04 Analytics
    happy path clicks: 4
    edge: GPS fix lost (gpsValid=0), weak signal alert triggered (rsrp < alertThresholdRsrp), session paused and resumed

F02 Run active Speed Test & Light Network Probe during drive test
    S01 Dashboard -> click "Speed Test" -> Ping / Download / Upload phases execute -> result saved to speed_tests & linked to active campaign
    happy path clicks: 2
    edge: high loaded latency (>150ms), low throughput (<5 Mbps) flagged in S04 Diagnosis

F03 Inspect route coverage & handover anomalies on Interactive Map
    S02 Route Map -> select Color Mode (BY_RSRP / BY_RSRQ / BY_SINR / BY_TECHNOLOGY / BY_SPEED) -> select Palette (CLASSIC / THERMIQUE / VIRIDIS / OCEAN) -> toggle Handovers & Missed Handovers -> click route point -> Point Inspector Sheet
    happy path clicks: 4
    edge: ping-pong handover (<30s return), missed handover (neighbor >= serving + 6 dB while serving <= -95 dBm)

F04 Export campaign data to standard RF engineering formats
    S03 Campaigns -> select campaign -> click Export -> choose CSV (24-column spec), KML (OpenGIS 2.2), GeoJSON, or PDF Report -> browser downloads file
    happy path clicks: 3
    edge: batch selection, delete campaign with Undo restoration

F05 Diagnose route health & compare two campaigns (Before vs After optimization)
    S04 Analytics -> switch across 7 tabs (Signal, CDF, Network, Bands, Trace, Sites, Diagnosis) -> open Advanced Route Analysis (Map / Timeline / Cells) -> open Campaign Comparison (Campaign A vs Campaign B)
    happy path clicks: 3
    edge: 5 diagnostic issue types (WEAK_SIGNAL, POOR_QUALITY, FREQUENT_HANDOVERS, LOW_THROUGHPUT, HIGH_LOADED_LATENCY)
```

## Components

| component | variants | states | used on |
| --- | --- | --- | --- |
| `RfSignalGauge` | normal, car-mode, compact | excellent, good, fair, poor, alert-pulsing | S01, S05 |
| `ServingCellCard` | 5G-NR-SA, 5G-NR-NSA, LTE-CA, WCDMA, GSM | registered, searching, no-service | S01 |
| `NeighborCellTable` | compact, expanded | empty, populated, missed-ho-highlight | S01, S02 |
| `CampaignControlBar` | floating, docked, car-mode | idle, recording, paused, stopping | S01, S02 |
| `RouteMapCanvas` | dark, light | north-up, heading-up, follow-me, inspect-point | S02, S04 |
| `CampaignCard` | standard, compare-selected | default, hover, selected, deleting | S03 |
| `CdfCurveChart` | RSRP, RSRQ, SINR | default, hover-percentile (P10/P50/P90) | S04 |
| `DiagnosisScorecard` | grade-A..F | healthy, warning, critical | S01, S04 |
| `RouteTimelineScrubber` | Map, Timeline, Cells | scrubbing, synced, filtered | S04 |
| `GlanceWidgetCard` | SignalWidget, CampaignWidget | idle, recording | S05 |

## Inferred data model

```
Campaign       id (text PK), name, startTime, endTime, measurementCount, distanceMeters,
               isActive, speedTestStatus, speedDownloadMbps, speedUploadMbps, speedPingMs,
               speedJitterMs, speedTestTimestampMs, speedTestLat, speedTestLon, alertThresholdRsrp
               evidence: Room entity `campaigns`, S01 start/stop flow, S03 campaign cards
               confidence: high (100% verified from Room schema)

Measurement    id (int PK), campaignId (FK -> campaigns.id), timestampMs, simSlot,
               latitude, longitude, altitude, speed, accuracy, bearing, gpsValid,
               technology, operatorName, mcc, mnc, cellId, lac, pci, band, earfcn,
               dlFrequencyKhz, dlBandwidthKhz, rssi, rsrp, rsrq, sinr, signalLevel,
               cqi, timingAdvance, csiRsrp, csiRsrq, csiSinr, nrStatus,
               aggregatedBandsJson, isRegistered, connectionState, neighborCellsJson
               evidence: Room entity `measurements`, S01 telemetry cards, S02 map dots, CSV export
               confidence: high (100% verified from Room schema)

SpeedTest      id (int PK), campaignId (FK -> campaigns.id), timestampMs, status,
               downloadMbps, uploadMbps, pingMs, jitterMs, loadedPingMs, lat, lon,
               technology, band, cellId, pci, rsrp, transport, dataSubscriptionId
               evidence: Room entity `speed_tests`, S01 speed test runner, S04 Network/Diagnosis tabs
               confidence: high (100% verified from Room schema)

SessionEvent   id (int PK), campaignId (FK -> campaigns.id), type ('PAUSE' | 'LIGHT_PROBE'),
               timestampMs, durationMs, pingMs, jitterMs, success, lat, lon, technology, cellId
               evidence: Room entity `session_events`, S01 pause & light probe, S04 Advanced Timeline
               confidence: high (100% verified from Room schema)
```

Relationships: `Campaign 1-n Measurement` (`ON DELETE CASCADE`), `Campaign 1-n SpeedTest` (`ON DELETE CASCADE`), `Campaign 1-n SessionEvent` (`ON DELETE CASCADE`).

## Feature matrix

See `features.csv`. Must: 20, should: 11, could: 4, skip: 1 (total 36 rows, 100% parity on all 35 countable features).

## Out of scope (cannot or should not be cloned)

- Android OS-level `BOOT_COMPLETED` broadcast receiver (`RECEIVE_BOOT_COMPLETED`) that silently launches a native Android background service on phone reboot before any browser tab is opened — web browsers do not execute arbitrary background code on OS boot without user interaction.

## Size

Screens 5, flows 5, entities 4. Hard parts: real-time RF + Carrier Aggregation + neighbor cell telemetry engine, multi-palette interactive route map with handover/missed-handover spatial geometry, empirical CDF & 0–100 diagnostic scoring engine, and exact binary-compatible CSV / OpenGIS KML 2.2 / GeoJSON / PDF exports. Size: M.
