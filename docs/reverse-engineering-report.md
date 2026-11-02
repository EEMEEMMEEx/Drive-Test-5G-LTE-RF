# Reverse Engineering Analysis Report: `GSM Drive Test: 5G LTE RF` (`com.drivetest.app` v1.8.0)

## 1. Package & XAPK Metadata

### 1.1 Archive & Application Identity
| Property | Value |
| --- | --- |
| Source Archive | `GSM+Drive+Test_+5G+LTE+RF_1.8.0.xapk` |
| Package Name | `com.drivetest.app` |
| Application Title | `GSM Drive Test: 5G LTE RF` |
| Version Name | `1.8.0` |
| Version Code | `14` |
| Minimum SDK (`minSdk`) | `32` (Android 12L) |
| Target SDK (`targetSdk`) | `37` |
| Compile SDK | `37` |

### 1.2 Split APK Composition
| Split APK File | Role & Contents |
| --- | --- |
| `com.drivetest.app.apk` | Base APK containing `AndroidManifest.xml`, compiled Kotlin/Java bytecode (`classes.dex`–`classes3.dex`), Room SQLite schemas, Compose UI resources, and localized strings (`en`, `fr`, `de`, `es`, `it`, `pt`, `nl`). |
| `config.arm64_v8a.apk` | Native 64-bit ARM shared libraries (`lib/` for Maps SDK, SQLite/Room native bindings, and graphics acceleration). |
| `config.en.apk` | Base English language resource split. |
| `config.mdpi.apk` | Medium-density raster drawable and icon assets. |
| `config.zh.apk` | Chinese language resource split. |

### 1.3 Android Permissions & Manifest Components
- **Declared Permissions**:
  - `android.permission.ACCESS_FINE_LOCATION` — High-precision GNSS fix acquisition (`GpsInfo`: latitude, longitude, altitude, speed, bearing, horizontal accuracy).
  - `android.permission.ACCESS_COARSE_LOCATION` — Cell tower & coarse location fallback.
  - `android.permission.ACCESS_BACKGROUND_LOCATION` — Continuous drive-test logging while screen is off or app is backgrounded during active campaigns.
  - `android.permission.READ_PHONE_STATE` — Multi-SIM subscription discovery (`SubscriptionManager`), serving cell identity (`TelephonyManager.requestCellInfoUpdate`), operator MCC/MNC, and `ServiceState` / `SignalStrength` callbacks.
  - `android.permission.FOREGROUND_SERVICE` & `android.permission.FOREGROUND_SERVICE_LOCATION` — Persistent foreground measurement collection service (`DriveTestForegroundService`).
  - `android.permission.POST_NOTIFICATIONS` — Weak-signal threshold alerts (`alertThresholdRsrp`) and live campaign status notification.
  - `android.permission.INTERNET` & `android.permission.ACCESS_NETWORK_STATE` — Active throughput speed tests, Cloudflare/HTTP latency probes (`LightNetworkProbe`), and Google Maps tile loading.
  - `android.permission.WAKE_LOCK` — Keeps CPU awake during cadence ticks (`1000ms` / `2000ms` / `5000ms`).
  - `android.permission.RECEIVE_BOOT_COMPLETED` — Restarts widget update workers and recovers interrupted active campaigns.

- **Technology Stack**:
  - **UI Layer**: Jetpack Compose + Material 3 (`androidx.compose.material3`), custom Canvas gauges, sparklines, CDF curves, and interactive route timelines.
  - **Dependency Injection**: Dagger Hilt (`@HiltViewModel`, `@Singleton` repositories and DAOs).
  - **Persistence**: AndroidX Room SQLite (`DriveTestDatabase`, schema version with 4 core entities: `campaigns`, `measurements`, `speed_tests`, `session_events`).
  - **Mapping**: Google Maps SDK for Android + Maps Compose (`com.google.maps.android.compose`), custom vector polylines, cell sector markers, and heatmap/metric color palettes.
  - **Background Processing**: AndroidX WorkManager + Foreground Location Service.
  - **Home Screen Widgets**: Jetpack Glance AppWidgets (`SignalWidget`, `CampaignWidget`).

---

## 2. Complete Database Schema (Room SQLite)

The Room database (`DriveTestDatabase`) defines 4 tables with foreign-key cascading and indexed campaign/timestamp lookups.

### 2.1 `campaigns` Table
Stores metadata, aggregate counters, latest active speed test summary, and weak-signal alert threshold for each drive test session.

```sql
CREATE TABLE IF NOT EXISTS `campaigns` (
  `id` TEXT NOT NULL,
  `name` TEXT NOT NULL,
  `startTime` INTEGER NOT NULL,
  `endTime` INTEGER,
  `measurementCount` INTEGER NOT NULL DEFAULT 0,
  `distanceMeters` REAL NOT NULL DEFAULT 0.0,
  `isActive` INTEGER NOT NULL DEFAULT 0,
  `speedTestStatus` TEXT NOT NULL DEFAULT 'NONE',
  `speedDownloadMbps` REAL,
  `speedUploadMbps` REAL,
  `speedPingMs` INTEGER,
  `speedJitterMs` INTEGER,
  `speedTestTimestampMs` INTEGER,
  `speedTestLat` REAL,
  `speedTestLon` REAL,
  `alertThresholdRsrp` INTEGER,
  PRIMARY KEY(`id`)
);
CREATE INDEX IF NOT EXISTS `index_campaigns_startTime` ON `campaigns` (`startTime` DESC);
CREATE INDEX IF NOT EXISTS `index_campaigns_isActive` ON `campaigns` (`isActive`);
```

| Column | SQLite Type | Kotlin Type | Description |
| --- | --- | --- | --- |
| `id` | `TEXT NOT NULL` | `String` (UUID) | Primary key identifying the campaign. |
| `name` | `TEXT NOT NULL` | `String` | Campaign label (e.g., `Site audit - 2026-11-02 14:30`). |
| `startTime` | `INTEGER NOT NULL` | `Long` | Epoch milliseconds when recording started. |
| `endTime` | `INTEGER` | `Long?` | Epoch milliseconds when stopped (`null` while active). |
| `measurementCount` | `INTEGER NOT NULL` | `Int` | Total RF/GPS samples recorded. |
| `distanceMeters` | `REAL NOT NULL` | `Double` | Cumulative Haversine route distance in meters. |
| `isActive` | `INTEGER NOT NULL` | `Boolean` | `1` if campaign is currently recording, `0` otherwise. |
| `speedTestStatus` | `TEXT NOT NULL` | `String` | `'NONE'`, `'RUNNING'`, `'SUCCESS'`, `'FAILED'`. |
| `speedDownloadMbps` | `REAL` | `Double?` | Latest or average downlink throughput in Mbps. |
| `speedUploadMbps` | `REAL` | `Double?` | Latest or average uplink throughput in Mbps. |
| `speedPingMs` | `INTEGER` | `Int?` | Latest unloaded ICMP/HTTP RTT latency in ms. |
| `speedJitterMs` | `INTEGER` | `Int?` | Latest packet delay variation in ms. |
| `speedTestTimestampMs` | `INTEGER` | `Long?` | Timestamp of the last speed test execution. |
| `speedTestLat` | `REAL` | `Double?` | Latitude where the speed test executed. |
| `speedTestLon` | `REAL` | `Double?` | Longitude where the speed test executed. |
| `alertThresholdRsrp` | `INTEGER` | `Int?` | Optional RSRP threshold in dBm (e.g., `-110`) for weak-signal alerts. |

### 2.2 `measurements` Table
Stores periodic RF serving cell, 5G NR CSI, Carrier Aggregation (`aggregatedBandsJson`), Neighbor Cells (`neighborCellsJson`), and GNSS telemetry points.

```sql
CREATE TABLE IF NOT EXISTS `measurements` (
  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `campaignId` TEXT NOT NULL,
  `timestampMs` INTEGER NOT NULL,
  `simSlot` INTEGER NOT NULL DEFAULT 0,
  `latitude` REAL NOT NULL,
  `longitude` REAL NOT NULL,
  `altitude` REAL NOT NULL DEFAULT 0.0,
  `speed` REAL NOT NULL DEFAULT 0.0,
  `accuracy` REAL NOT NULL DEFAULT 0.0,
  `bearing` REAL NOT NULL DEFAULT 0.0,
  `gpsValid` INTEGER NOT NULL DEFAULT 1,
  `technology` TEXT NOT NULL,
  `operatorName` TEXT NOT NULL,
  `mcc` TEXT NOT NULL,
  `mnc` TEXT NOT NULL,
  `cellId` INTEGER NOT NULL,
  `lac` INTEGER NOT NULL,
  `pci` INTEGER NOT NULL,
  `band` TEXT NOT NULL,
  `earfcn` INTEGER NOT NULL,
  `dlFrequencyKhz` INTEGER,
  `dlBandwidthKhz` INTEGER,
  `rssi` INTEGER,
  `rsrp` INTEGER NOT NULL,
  `rsrq` INTEGER NOT NULL,
  `sinr` INTEGER NOT NULL,
  `signalLevel` INTEGER NOT NULL DEFAULT 0,
  `cqi` INTEGER,
  `timingAdvance` INTEGER,
  `csiRsrp` INTEGER,
  `csiRsrq` INTEGER,
  `csiSinr` INTEGER,
  `nrStatus` TEXT,
  `aggregatedBandsJson` TEXT,
  `isRegistered` INTEGER NOT NULL DEFAULT 1,
  `connectionState` TEXT NOT NULL,
  `neighborCellsJson` TEXT,
  FOREIGN KEY(`campaignId`) REFERENCES `campaigns`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS `index_measurements_campaignId` ON `measurements` (`campaignId`);
CREATE INDEX IF NOT EXISTS `index_measurements_campaignId_timestampMs` ON `measurements` (`campaignId`, `timestampMs`);
```

| Column | SQLite Type | Kotlin Type | Description |
| --- | --- | --- | --- |
| `id` | `INTEGER PK AUTOINCREMENT` | `Long` | Auto-incrementing sample ID. |
| `campaignId` | `TEXT NOT NULL` | `String` | Foreign key to `campaigns.id` (`ON DELETE CASCADE`). |
| `timestampMs` | `INTEGER NOT NULL` | `Long` | Sample capture timestamp (epoch ms). |
| `simSlot` | `INTEGER NOT NULL` | `Int` | Active SIM slot index (`0` = SIM 1, `1` = SIM 2). |
| `latitude` | `REAL NOT NULL` | `Double` | WGS84 latitude in decimal degrees. |
| `longitude` | `REAL NOT NULL` | `Double` | WGS84 longitude in decimal degrees. |
| `altitude` | `REAL NOT NULL` | `Double` | Ellipsoidal/MSL altitude in meters. |
| `speed` | `REAL NOT NULL` | `Float` | Ground speed in m/s (`speed * 3.6` for km/h). |
| `accuracy` | `REAL NOT NULL` | `Float` | Horizontal GPS accuracy radius in meters. |
| `bearing` | `REAL NOT NULL` | `Float` | Heading in degrees (`0.0`–`360.0`). |
| `gpsValid` | `INTEGER NOT NULL` | `Boolean` | `1` if GNSS fix is valid and fresh, `0` otherwise. |
| `technology` | `TEXT NOT NULL` | `String` | `'5G NR'`, `'LTE'`, `'WCDMA'`, `'GSM'`, `'NO SERVICE'`. |
| `operatorName` | `TEXT NOT NULL` | `String` | Carrier alpha tag (e.g., `AIS`, `True-H`, `Orange F`). |
| `mcc` | `TEXT NOT NULL` | `String` | 3-digit Mobile Country Code (e.g., `520`, `208`). |
| `mnc` | `TEXT NOT NULL` | `String` | 2/3-digit Mobile Network Code (e.g., `03`, `01`). |
| `cellId` | `INTEGER NOT NULL` | `Long` | 28-bit ECI (LTE) or 36-bit NCI (5G NR) or CID. |
| `lac` | `INTEGER NOT NULL` | `Int` | Tracking Area Code (`TAC`) for LTE/5G or `LAC` for 2G/3G. |
| `pci` | `INTEGER NOT NULL` | `Int` | Physical Cell Identity (`0`–`503` LTE, `0`–`1007` NR) or PSC/BSIC. |
| `band` | `TEXT NOT NULL` | `String` | Frequency band label (e.g., `n78`, `B3`, `B1`, `B20`, `n41`). |
| `earfcn` | `INTEGER NOT NULL` | `Int` | EARFCN (LTE), NR-ARFCN (5G), UARFCN (3G), or ARFCN (2G). |
| `dlFrequencyKhz` | `INTEGER` | `Int?` | Downlink center frequency in kHz (e.g., `3500000` for 3.5 GHz). |
| `dlBandwidthKhz` | `INTEGER` | `Int?` | Channel bandwidth in kHz (`20000`, `100000`, etc.). |
| `rssi` | `INTEGER` | `Int?` | Received Signal Strength Indicator in dBm (`-113` to `-51`). |
| `rsrp` | `INTEGER NOT NULL` | `Int` | Reference Signal Received Power (SS-RSRP / RSRP) in dBm (`-140` to `-44`). |
| `rsrq` | `INTEGER NOT NULL` | `Int` | Reference Signal Received Quality (SS-RSRQ / RSRQ) in dB (`-20` to `-3`). |
| `sinr` | `INTEGER NOT NULL` | `Int` | Signal-to-Interference-plus-Noise Ratio (SS-SINR / RSSNR) in dB (`-20` to `30`). |
| `signalLevel` | `INTEGER NOT NULL` | `Int` | Normalized Android signal bars (`0` to `4`). |
| `cqi` | `INTEGER` | `Int?` | Channel Quality Indicator (`0`–`15`). |
| `timingAdvance` | `INTEGER` | `Int?` | Timing Advance (`TA`) step value (`0`–`1282`, ~78m per step in LTE). |
| `csiRsrp` | `INTEGER` | `Int?` | 5G NR CSI-RSRP in dBm. |
| `csiRsrq` | `INTEGER` | `Int?` | 5G NR CSI-RSRQ in dB. |
| `csiSinr` | `INTEGER` | `Int?` | 5G NR CSI-SINR in dB. |
| `nrStatus` | `TEXT` | `String?` | 5G NR attachment state: `'SA'`, `'NSA'`, `'NOT_RESTRICTED'`, `'RESTRICTED'`, `'NONE'`. |
| `aggregatedBandsJson` | `TEXT` | `String?` | JSON array of secondary component carriers (`CaSnapshot` items). |
| `isRegistered` | `INTEGER NOT NULL` | `Boolean` | `1` if registered on serving cell. |
| `connectionState` | `TEXT NOT NULL` | `String` | `'CONNECTED'`, `'IDLE'`, `'SEARCHING'`, `'NO_SERVICE'`. |
| `neighborCellsJson` | `TEXT` | `String?` | JSON-serialized `List<NeighborCell>` detected at capture instant. |

### 2.3 `speed_tests` Table
Stores active HTTP/socket throughput and loaded latency measurements linked to a campaign.

```sql
CREATE TABLE IF NOT EXISTS `speed_tests` (
  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `campaignId` TEXT NOT NULL,
  `timestampMs` INTEGER NOT NULL,
  `status` TEXT NOT NULL,
  `downloadMbps` REAL,
  `uploadMbps` REAL,
  `pingMs` INTEGER,
  `jitterMs` INTEGER,
  `loadedPingMs` INTEGER,
  `lat` REAL,
  `lon` REAL,
  `technology` TEXT,
  `band` TEXT,
  `cellId` INTEGER,
  `pci` INTEGER,
  `rsrp` INTEGER,
  `transport` TEXT,
  `dataSubscriptionId` INTEGER,
  FOREIGN KEY(`campaignId`) REFERENCES `campaigns`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS `index_speed_tests_campaignId` ON `speed_tests` (`campaignId`);
```

### 2.4 `session_events` Table
Stores non-RF lifecycle and light network monitoring events (`PAUSE` intervals and `LIGHT_PROBE` active latency/jitter checks).

```sql
CREATE TABLE IF NOT EXISTS `session_events` (
  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `campaignId` TEXT NOT NULL,
  `type` TEXT NOT NULL,
  `timestampMs` INTEGER NOT NULL,
  `durationMs` INTEGER,
  `pingMs` INTEGER,
  `jitterMs` INTEGER,
  `success` INTEGER,
  `lat` REAL,
  `lon` REAL,
  `technology` TEXT,
  `cellId` INTEGER,
  FOREIGN KEY(`campaignId`) REFERENCES `campaigns`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS `index_session_events_campaignId` ON `session_events` (`campaignId`);
```

---

## 3. Complete Domain Models, Enums & UI States (`classes.dex`)

### 3.1 Core Telemetry & RF Models
- **`RadioInfo`**:
  - `simSlot: Int`, `operatorName: String`, `mcc: String`, `mnc: String`
  - `technology: String` (`"5G NR"`, `"LTE"`, `"WCDMA"`, `"GSM"`, `"NO SERVICE"`)
  - `cellId: Long`, `lac: Int`, `pci: Int`, `band: String`, `earfcn: Int`
  - `dlFrequencyKhz: Int?`, `dlBandwidthKhz: Int?`
  - `rssi: Int?`, `rsrp: Int`, `rsrq: Int`, `sinr: Int`, `signalLevel: Int`
  - `cqi: Int?`, `timingAdvance: Int?`
  - `csiRsrp: Int?`, `csiRsrq: Int?`, `csiSinr: Int?`, `nrStatus: String?`
  - `caSnapshot: CaSnapshot?`
  - `isRegistered: Boolean`, `connectionState: String`
  - `neighborCells: List<NeighborCell>`
  - Computed helpers: `eNodeBId: Long` (`cellId shr 8` for LTE), `sectorId: Int` (`cellId and 0xFF` for LTE), `estimatedDistanceMeters: Int?` (`timingAdvance * 78`).

- **`GpsInfo`**:
  - `latitude: Double`, `longitude: Double`, `altitude: Double`
  - `speed: Float` (m/s), `accuracy: Float` (m), `bearing: Float` (deg)
  - `gpsValid: Boolean`, `timestampMs: Long`, `satelliteCount: Int`

- **`NeighborCell`**:
  - `technology: String`, `pci: Int`, `earfcn: Int`, `band: String`
  - `rsrp: Int`, `rsrq: Int?`, `sinr: Int?`, `rssi: Int?`
  - `cellId: Long?`, `lac: Int?`

- **`CaSnapshot`** (Carrier Aggregation):
  - `primaryBand: String`, `primaryBandwidthKhz: Int?`, `primaryEarfcn: Int`
  - `secondaryCarriers: List<ComponentCarrier>` (`band: String`, `earfcn: Int`, `pci: Int`, `bandwidthKhz: Int`, `rsrp: Int?`, `sinr: Int?`)
  - `totalBandwidthMhz: Int`, `caComboLabel: String` (e.g., `"3CA: B3 (20MHz) + B1 (15MHz) + n78 (100MHz)"`)

- **`SpeedTestProgress` & `SpeedTestResult`**:
  - `phase: SpeedTestPhase` (`IDLE`, `PING`, `DOWNLOAD`, `UPLOAD`, `COMPLETED`, `ERROR`)
  - `progressFraction: Float` (`0.0f`..`1.0f`), `currentMbps: Double`
  - `pingMs: Int?`, `jitterMs: Int?`, `loadedPingMs: Int?`, `downloadMbps: Double?`, `uploadMbps: Double?`
  - `status: String`, `transport: String` (`"CELLULAR"`, `"WIFI"`), `dataSubscriptionId: Int`

- **`LightNetworkProbe`**:
  - `timestampMs: Long`, `pingMs: Int?`, `jitterMs: Int?`, `packetLossPct: Float`, `success: Boolean`, `targetHost: String` (`"1.1.1.1"` / `"connectivitycheck.gstatic.com"`)

### 3.2 Analytics, Mobility & Diagnostic Models
- **`SignalStats`**:
  - `minRsrp: Int`, `maxRsrp: Int`, `avgRsrp: Double`, `medianRsrp: Int`, `p10Rsrp: Int`, `p90Rsrp: Int`
  - `minRsrq: Int`, `maxRsrq: Int`, `avgRsrq: Double`
  - `minSinr: Int`, `maxSinr: Int`, `avgSinr: Double`
  - `excellentPct: Double` (`>= -80 dBm`), `goodPct: Double` (`-90 to -80 dBm`), `fairPct: Double` (`-100 to -90 dBm`), `poorPct: Double` (`< -100 dBm`)

- **`MobilityStats`, `HandoverEvent`, `MissedHandoverEvent`**:
  - `HandoverEvent`: `timestampMs: Long`, `latitude: Double`, `longitude: Double`, `fromCellId: Long`, `toCellId: Long`, `fromPci: Int`, `toPci: Int`, `fromBand: String`, `toBand: String`, `fromTech: String`, `toTech: String`, `rsrpBefore: Int`, `rsrpAfter: Int`, `isPingPong: Boolean`, `timeSincePreviousHoMs: Long?`
  - `MissedHandoverEvent`: `timestampMs: Long`, `latitude: Double`, `longitude: Double`, `servingCellId: Long`, `servingPci: Int`, `servingRsrp: Int`, `bestNeighborPci: Int`, `bestNeighborBand: String`, `bestNeighborRsrp: Int`, `marginDb: Int` (where `bestNeighborRsrp - servingRsrp >= 6 dB` while `servingRsrp <= -95 dBm`)
  - `MobilityStats`: `totalHandovers: Int`, `pingPongCount: Int`, `interBandHandovers: Int`, `interTechHandovers: Int`, `missedHandoverCount: Int`, `meanTimeBetweenHandoversSec: Double`, `handoverEvents: List<HandoverEvent>`, `missedHandoverEvents: List<MissedHandoverEvent>`

- **`CellSite` & `BandUsage`**:
  - `CellSite`: `cellId: Long`, `eNodeBId: Long`, `pci: Int`, `lac: Int`, `technology: String`, `band: String`, `earfcn: Int`, `centroidLat: Double`, `centroidLon: Double`, `sampleCount: Int`, `avgRsrp: Double`, `maxRsrp: Int`, `minRsrp: Int`, `dominancePct: Double`
  - `BandUsage`: `band: String`, `technology: String`, `earfcn: Int`, `sampleCount: Int`, `percentage: Double`, `avgRsrp: Double`, `avgSinr: Double`

- **`CampaignDiagnostic`, `DiagnosticIssue`, `DiagnosticThresholds`**:
  - `DiagnosticThresholds`: `weakSignalRsrpDbm: Int = -105`, `poorQualityRsrqDb: Int = -14`, `poorSinrDb: Int = 0`, `missedHoMarginDb: Int = 6`, `pingPongWindowMs: Long = 30000L`, `lowThroughputMbps: Double = 5.0`, `highLoadedLatencyMs: Int = 150`
  - `DiagnosticIssueType` (5 problem categories):
    1. `WEAK_SIGNAL` — Coverage hole / cell edge (`RSRP < -105 dBm`).
    2. `POOR_QUALITY` — High interference / pilot pollution (`RSRQ < -14 dB` or `SINR < 0 dB` despite adequate RSRP).
    3. `FREQUENT_HANDOVERS` — Ping-pong instability (`< 30s` return to prior cell) or excessive handovers/km.
    4. `LOW_THROUGHPUT` — Downlink speed test `< 5.0 Mbps` or stalled radio bearer.
    5. `HIGH_LOADED_LATENCY` — Bufferbloat / high loaded ping (`> 150 ms`) or light probe timeout.
  - `CampaignDiagnostic`: `overallScore: Int` (`0`–`100`), `grade: String` (`"A"`, `"B"`, `"C"`, `"D"`, `"F"`), `coverageScore: Int`, `qualityScore: Int`, `stabilityScore: Int`, `performanceScore: Int`, `issues: List<DiagnosticIssue>`, `recommendations: List<String>`

### 3.3 Screen UI State Classes
- **`DriveTestUiState`**:
  - `radioInfo: RadioInfo`, `gpsInfo: GpsInfo`, `selectedSimSlot: Int`, `availableSims: List<SimSubInfo>`
  - `activeCampaign: Campaign?`, `isPaused: Boolean`, `recordingCadenceMs: Long` (`1000L`, `2000L`, `5000L`)
  - `selectedPreset: CampaignPreset` (`SITE_AUDIT`, `URBAN_ROUTE`, `HIGHWAY`, `INDOOR`)
  - `alertThresholdRsrp: Int?`, `isWeakSignalAlertActive: Boolean`
  - `speedTestProgress: SpeedTestProgress`, `lightProbeEnabled: Boolean`, `latestLightProbe: LightNetworkProbe?`
  - `isCarMode: Boolean`, `finishedCampaignSummary: FinishedCampaignSummary?`
- **`MapUiState` & `MapOptions`**:
  - `colorMode: MapColorMode` (`BY_RSRP`, `BY_RSRQ`, `BY_SINR`, `BY_TECHNOLOGY`, `BY_SPEED`)
  - `colorPalette: MapColorPalette` (`CLASSIC`, `THERMIQUE`, `VIRIDIS`, `OCEAN`)
  - `dotSize: Float` (`4f`..`16f`), `rsrpGoodThreshold: Int` (`-85`), `rsrpPoorThreshold: Int` (`-105`)
  - `showCurrentCell: Boolean`, `showNeighborCells: Boolean`, `showHandovers: Boolean`, `showMissedHandovers: Boolean`
  - `orientationMode: MapOrientationMode` (`NORTH_UP`, `HEADING_UP`), `followMyPosition: Boolean`, `isDarkMapStyle: Boolean`
- **`CampaignListUiState`**:
  - `campaigns: List<CampaignWithSparkline>`, `searchQuery: String`, `selectedIds: Set<String>`, `isMultiSelectMode: Boolean`, `recentlyDeletedCampaign: Campaign?`, `exportInProgress: ExportFormat?` (`CSV`, `KML`, `GEOJSON`, `PDF`)
- **`AnalyticsUiState`**:
  - `selectedCampaign: Campaign?`, `selectedTab: AnalyticsTab` (`SIGNAL`, `CDF`, `NETWORK`, `BANDS`, `TRACE`, `SITES`, `DIAGNOSIS`)
  - `signalStats: SignalStats`, `mobilityStats: MobilityStats`, `bandUsages: List<BandUsage>`, `cellSites: List<CellSite>`, `diagnostic: CampaignDiagnostic`
  - `advancedRouteSubView: RouteSubView` (`MAP`, `TIMELINE`, `CELLS`)
- **`CampaignComparisonUiState`**:
  - `campaignA: Campaign?`, `campaignB: Campaign?`, `deltaRsrpDb: Double`, `deltaRsrqDb: Double`, `deltaSinrDb: Double`, `spatialGridComparison: List<SpatialCellComparison>`
- **`SpotlightStep`**:
  - 5-step interactive onboarding sequence (`STEP_1_RF_GAUGE`, `STEP_2_START_CAMPAIGN`, `STEP_3_MAP_COLOR_MODES`, `STEP_4_EXPORT_FORMATS`, `STEP_5_ROUTE_DIAGNOSIS`).

---

## 4. UI Structure & Screen Flow

### 4.1 Screen S01: Live Drive Test Dashboard (`DriveTestScreen`)
- **Top Control Bar**: App title, active SIM slot selector (`SIM 1` / `SIM 2`), Car Mode toggle (oversized high-contrast telemetry cards for windshield mount), Spotlight tutorial trigger (`?`), and Language/Settings menu.
- **RF Signal Gauge Card**:
  - Semi-circular radial dBm gauge displaying live `RSRP` (`-140` to `-44 dBm`), quality classification badge (`Excellent`, `Good`, `Fair`, `Poor`), and secondary metric pills for `RSRQ (dB)`, `SINR (dB)`, `RSSI (dBm)`, `CQI`, and `Timing Advance (TA)`.
  - 5G NR expansion row showing `NR Status` (`SA` / `NSA`) and CSI metrics (`CSI-RSRP`, `CSI-RSRQ`, `CSI-SINR`).
- **Serving Cell & Carrier Aggregation Card**:
  - Displays Operator (`MCC-MNC`), Technology badge (`5G NR`, `LTE`, `WCDMA`, `GSM`), `Cell ID` (`eNodeB ID` + `Sector`), `PCI`, `TAC/LAC`, `Band`, `EARFCN`, Downlink Frequency (`MHz`), and Channel Bandwidth (`MHz`).
  - **Carrier Aggregation (`CaSnapshot`)**: Lists PCC + SCC component carriers and total aggregated bandwidth.
- **GPS Telemetry Strip**:
  - Latitude, Longitude, Speed (`km/h` and `m/s`), Altitude (`m`), Bearing (`°`), Horizontal Accuracy (`±m`), and GPS validity indicator.
- **Neighbor Cells List**:
  - Real-time sorted table of detected intra/inter-frequency neighbor cells (`PCI`, `Band`, `EARFCN`, `RSRP dBm`, `RSRQ dB`, delta vs serving cell).
- **Campaign Control & Start Dialog**:
  - **Presets**: `Site audit` (Precise 1s cadence, stationary/walk focus), `Urban route` (Standard 2s cadence), `Highway` (Precise 1s cadence, high-speed mobility), `Indoor` (Standard 2s cadence, weak signal threshold `-105 dBm`).
  - **Cadence Selector**: `Eco (5s)`, `Standard (2s)`, `Precise (1s)`.
  - **Weak Signal Alert**: Configurable RSRP dBm slider (`-120` to `-85 dBm`) that triggers visual/audio alert banners when serving RSRP drops below threshold.
  - **Session Actions**: `Start Campaign`, `Pause / Resume` (logs `PAUSE` event in `session_events`), `Stop Campaign`, `Run Speed Test`, and `Light Network Probe` toggle (periodic ping/jitter probe logged as `LIGHT_PROBE`).
- **Finished Campaign Summary Modal**:
  - Appears immediately when stopping a campaign; displays duration, distance (`km`), sample count, average RSRP/RSRQ/SINR, handover count, diagnosis score badge, and quick shortcuts to **View Analytics** or **Export**.

### 4.2 Screen S02: Interactive Route Map (`MapScreen`)
- **5 Marker Color Modes (`MapColorMode`)**:
  1. `BY_RSRP` — Colors route points by RSRP dBm against configurable Good/Poor thresholds.
  2. `BY_RSRQ` — Colors route points by signal quality (`-3` to `-20 dB`).
  3. `BY_SINR` — Colors route points by interference ratio (`-10` to `+30 dB`).
  4. `BY_TECHNOLOGY` — Distinct categorical colors for `5G NR`, `LTE`, `WCDMA`, `GSM`, and `NO SERVICE`.
  5. `BY_SPEED` — Colors route points by vehicle ground speed (`km/h`).
- **4 Color Palettes (`MapColorPalette`)**:
  - `CLASSIC` (Green → Yellow → Orange → Red)
  - `THERMIQUE` (Thermal Purple → Red → Orange → Yellow)
  - `VIRIDIS` (Perceptually uniform Dark Purple → Teal → Green → Yellow)
  - `OCEAN` (Deep Navy → Cyan → Mint)
- **Map Overlays & Controls Drawer**:
  - **Dot Size Slider** (`4px`–`16px`) and **RSRP Threshold Sliders** (`Good >= -85 dBm`, `Poor <= -105 dBm`).
  - **Toggles**: `Current cell` (sector wedge + line to estimated site centroid), `Neighbor cells`, `Handovers` (swap markers along route), `Missed handovers` (warning triangles where neighbor > serving + 6 dB).
  - **Camera & Theme**: `Follow my position`, `North up / Heading up` rotation toggle, and `Dark / Light` map base style.
  - **Point Inspector Bottom Sheet**: Clicking any route dot displays full sample telemetry (`timestamp`, `RSRP/RSRQ/SINR`, `Cell ID`, `PCI`, `Band`, `EARFCN`, `Speed`, and active neighbors).

### 4.3 Screen S03: Campaigns List & Export Manager (`CampaignListScreen`)
- **Search & Filtering**: Instant filter by campaign name, operator, or technology.
- **Campaign Card**:
  - Displays campaign title, date/time range, duration, distance (`km`), sample count, **Avg RSRP badge** (color-coded), inline **RSRP Sparkline** canvas across the session, and speed test summary pill (`DL / UL Mbps`, `Ping ms`).
- **Multi-Select & Batch Operations**:
  - Long-press or select toggle to compare two campaigns (`Compare A vs B`), batch export, or delete with snackbar **Undo** restoration.
- **4 Native Export Formats**:
  1. **CSV Export** — Exact 24-column header:
     `timestamp,latitude,longitude,altitude,speed_ms,accuracy_m,bearing,gps_valid,technology,operator,mcc,mnc,cell_id,lac_tac,pci,band,earfcn,rssi_dbm,rsrp_dbm,rsrq_db,sinr_db,registered,connection_state`
  2. **KML Export** — OpenGIS KML 2.2 (`http://www.opengis.net/kml/2.2`) document with styled `<Placemark>` points, color-coded by RSRP level, and embedded HTML telemetry tables plus route `<LineString>`.
  3. **GeoJSON Export** — RFC 7946 `FeatureCollection` containing `Point` features for every measurement (with full RF properties) and a summary `LineString` feature.
  4. **PDF Report Export** — Formatted engineering drive-test report containing campaign summary, RF KPI distribution tables, band usage breakdown, handover summary, and route diagnosis scorecard.

### 4.4 Screen S04: Analytics & Route Diagnostics (`AnalyticsScreen`)
Organized into **7 core analytical tabs** plus **Advanced Route Analysis** and **Campaign Comparison**:
1. **Signal Tab**: Time-series dual/triple chart (`RSRP`, `RSRQ`, `SINR`), min/avg/max/median KPI cards, and coverage quality distribution bar (`Excellent / Good / Fair / Poor %`).
2. **CDF Tab**: Empirical Cumulative Distribution Function (`0%`–`100%` percentiles) curves for `RSRP (dBm)`, `RSRQ (dB)`, and `SINR (dB)` with `P10`, `P50 (Median)`, and `P90` crosshair readouts.
3. **Network Tab**: Technology split (`5G NR` vs `LTE` vs `3G/2G`), connection state dwell time, `NR Status` (`SA`/`NSA`), and speed test + light probe latency results.
4. **Bands Tab**: Breakdown of frequency bands (`n78`, `B3`, `B1`, `B20`, etc.) with sample count, share percentage, average RSRP, and average SINR per band.
5. **Trace Tab**: Chronological event log of every `HandoverEvent` (`Cell A -> Cell B`, `PCI`, `Band`, `RSRP before/after`, `Ping-Pong` flag) and `MissedHandoverEvent`.
6. **Sites Tab**: Aggregated unique `CellSite` inventory (`Cell ID`, `eNodeB ID`, `PCI`, `Band`, `EARFCN`, estimated centroid coordinates, sample count, dominance %, and RSRP range).
7. **Diagnosis Tab**:
   - **Overall Route Health Score (`0–100`)** with sub-scores for Coverage, Quality, Stability, and Performance.
   - **5 Problem Area Detections**: `WEAK_SIGNAL`, `POOR_QUALITY`, `FREQUENT_HANDOVERS`, `LOW_THROUGHPUT`, `HIGH_LOADED_LATENCY` with affected sample counts, GPS coordinates, and actionable RF engineering recommendations.
- **Advanced Route Analysis (`Map / Timeline / Cells` correlated view)**:
  - Synchronized scrubber correlating spatial map position with a multi-track timeline (`RSRP`, `Ping/Jitter` from `LIGHT_PROBE`, `PAUSE` windows, and `Handover` ticks) and cell dominance bars.
- **Campaign Comparison (`A vs B`)**:
  - Side-by-side KPI comparison and spatial delta calculation (`ΔRSRP`, `ΔRSRQ`, `ΔSINR`, handover rate delta, and coverage improvement %).

### 4.5 Screen S05: Spotlight Tutorial, Glance Widgets & Localization
- **5-Step Spotlight Tutorial**: Interactive guided overlay highlighting the RF Gauge, Campaign Preset/Cadence launcher, Map Color Modes, Multi-format Exporter, and Automated Route Diagnosis.
- **Home Screen AppWidgets (`Glance`)**:
  - `SignalWidget` (`2x2` / `4x1`): Displays live serving operator, technology badge, band, PCI, and color-coded RSRP/SINR readouts.
  - `CampaignWidget` (`4x2`): Displays active campaign status, sample count, elapsed distance, and one-tap Start/Pause/Stop triggers.
- **Multi-Language Support (7 Languages)**:
  - Full UI localization for `EN` (English), `FR` (Français), `DE` (Deutsch), `ES` (Español), `IT` (Italiano), `PT` (Português), and `NL` (Nederlands).

---

## 5. Core RF Logic & Measurement Algorithms

### 5.1 RF Metric Normalization & Thresholds
| Metric | Range | Excellent | Good | Fair | Poor |
| --- | --- | --- | --- | --- | --- |
| **RSRP / SS-RSRP** | `-140` to `-44 dBm` | `>= -80 dBm` | `-90` to `-80 dBm` | `-100` to `-90 dBm` | `< -100 dBm` |
| **RSRQ / SS-RSRQ** | `-20` to `-3 dB` | `>= -10 dB` | `-15` to `-10 dB` | `-17` to `-15 dB` | `< -17 dB` |
| **SINR / SS-SINR** | `-20` to `+30 dB` | `>= 20 dB` | `13` to `20 dB` | `0` to `13 dB` | `< 0 dB` |
| **RSSI** | `-113` to `-51 dBm` | `>= -65 dBm` | `-75` to `-65 dBm` | `-85` to `-75 dBm` | `< -85 dBm` |

### 5.2 Handover & Ping-Pong Detection Algorithm
For consecutive valid measurements $m_{i-1}$ and $m_i$ within a campaign:
1. **Handover Event**: Triggered when `m[i].cellId != m[i-1].cellId` (or `m[i].pci != m[i-1].pci` when `cellId` is unmapped).
2. **Classification**:
   - **Intra-band / Intra-tech**: `fromBand == toBand` and `fromTech == toTech`.
   - **Inter-band**: `fromBand != toBand` within the same RAT.
   - **Inter-RAT (IRAT)**: `fromTech != toTech` (e.g., `5G NR -> LTE` fallback).
3. **Ping-Pong Detection**: A handover at $t_i$ from `Cell B -> Cell A` is flagged as `isPingPong = true` if the previous handover at $t_{i-1}$ was `Cell A -> Cell B` and $\Delta t = t_i - t_{i-1} < 30,000\text{ ms}$ (`30 seconds`).

### 5.3 Missed Handover (Best Server Violation) Algorithm
At each measurement $m_i$ with parsed `neighborCells`:
1. Select the strongest same-technology or higher-priority neighbor $n_{\max} = \arg\max_{n} (n.\text{rsrp})$.
2. Compute neighbor margin: $\Delta_{\text{margin}} = n_{\max}.\text{rsrp} - m_i.\text{rsrp}$.
3. Flag a **`MissedHandoverEvent`** if:
   - $m_i.\text{rsrp} \le -95\text{ dBm}$ (serving cell is degrading), AND
   - $\Delta_{\text{margin}} \ge 6\text{ dB}$ ($n_{\max}$ is at least 6 dB stronger than serving cell), AND
   - Condition persists or occurs without a handover within the next measurement window.

### 5.4 Automated Route Diagnosis (`0–100` Scoring Engine)
The diagnostic engine computes four weighted sub-scores (`0–100`) and combines them into `overallScore`:
$$\text{OverallScore} = \text{round}(0.35 \cdot S_{\text{coverage}} + 0.25 \cdot S_{\text{quality}} + 0.20 \cdot S_{\text{stability}} + 0.20 \cdot S_{\text{performance}})$$
- **$S_{\text{coverage}}$ (35%)**: Penalizes ratio of samples with `RSRP < -105 dBm` (`WEAK_SIGNAL`).
- **$S_{\text{quality}}$ (25%)**: Penalizes ratio of samples with `RSRQ < -14 dB` or `SINR < 0 dB` (`POOR_QUALITY`).
- **$S_{\text{stability}}$ (20%)**: Penalizes Ping-Pong handovers (`< 30s`) and missed handover events (`FREQUENT_HANDOVERS`).
- **$S_{\text{performance}}$ (20%)**: Penalizes speed tests with `downloadMbps < 5.0` (`LOW_THROUGHPUT`) and light probes / loaded pings with `latency > 150 ms` (`HIGH_LOADED_LATENCY`).
