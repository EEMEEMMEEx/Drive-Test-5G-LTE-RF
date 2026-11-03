export type CellularTechnology = 'GSM_2G' | 'WCDMA_3G' | 'LTE_4G' | 'NR_5G' | 'UNKNOWN';
export type NrStatus = 'SA' | 'NSA' | 'NONE' | 'UNKNOWN';
export type ConnectionState = 'CONNECTED' | 'IDLE' | 'DISCONNECTED';
export type MeasurementCadence = 'ECO' | 'STANDARD' | 'PRECISE';
export type RatSelectionMode = 'AUTO' | 'NR_5G_ONLY' | 'LTE_4G_ONLY';
export type CampaignPreset = 'SITE_AUDIT' | 'URBAN_ROUTE' | 'HIGHWAY' | 'INDOOR';
export type SpeedTestStatus =
  | 'NOT_RUN'
  | 'SUCCESS'
  | 'FAILED'
  | 'NO_NETWORK'
  | 'NO_MOBILE_NETWORK'
  | 'SIM_NOT_DATA';
export type SpeedTestPhase = 'PING' | 'DOWNLOAD' | 'UPLOAD' | 'DONE';
export type TransportType = 'MOBILE' | 'WIFI' | 'UNKNOWN';
export type SessionEventType = 'PAUSE' | 'LIGHT_PROBE';

export type MapColorMode = 'BY_RSRP' | 'BY_RSRQ' | 'BY_SINR' | 'BY_TECHNOLOGY' | 'BY_SPEED';
export type SignalPalette = 'CLASSIC' | 'THERMIQUE' | 'VIRIDIS' | 'OCEAN';
export type MapBaseType =
  | 'GOOGLE_HYBRID'
  | 'GOOGLE_SATELLITE'
  | 'GOOGLE_ROADMAP'
  | 'GOOGLE_TERRAIN'
  | 'SATELLITE'
  | 'TERRAIN'
  | 'HYBRID';

export const DEFAULT_MAP_TYPE: MapBaseType = 'GOOGLE_HYBRID';

export const VALID_MAP_TYPES: readonly MapBaseType[] = [
  'GOOGLE_HYBRID',
  'GOOGLE_SATELLITE',
  'GOOGLE_ROADMAP',
  'GOOGLE_TERRAIN',
  'SATELLITE',
  'TERRAIN',
  'HYBRID',
] as const;

export function isValidMapType(val: unknown): val is MapBaseType {
  return typeof val === 'string' && VALID_MAP_TYPES.includes(val as MapBaseType);
}

export function sanitizeMapType(val: unknown, fallback: MapBaseType = DEFAULT_MAP_TYPE): MapBaseType {
  if (isValidMapType(val)) {
    return val;
  }
  return fallback;
}

export type DiagnosticIssueType =
  | 'WEAK_SIGNAL'
  | 'POOR_QUALITY'
  | 'FREQUENT_HANDOVERS'
  | 'LOW_THROUGHPUT'
  | 'HIGH_LOADED_LATENCY';

export type AnalyticsTabId =
  | 'SIGNAL'
  | 'CDF'
  | 'NETWORK'
  | 'BANDS'
  | 'TRACE'
  | 'SITES'
  | 'DIAGNOSTIC'
  | 'ADVANCED';

export type AdvancedViewMode = 'MAP' | 'TIMELINE' | 'CELLS';
export type AdvancedMetricType = 'RSRP' | 'PING' | 'JITTER';
export type ComparisonMetric = 'RSRP' | 'RSRQ' | 'SINR';
export type ComparisonError = 'NOT_ENOUGH_DATA' | 'NO_OVERLAP' | 'METRIC_UNAVAILABLE' | null;

export type MainNavTab = 'DASHBOARD' | 'MAP' | 'CAMPAIGNS' | 'ANALYTICS';

export interface SimInfo {
  subscriptionId: number;
  slotIndex: number;
  displayName: string;
  carrierName: string;
  mcc: string;
  mnc: string;
  isActive: boolean;
}

export interface NeighborCell {
  technology: CellularTechnology;
  pci: number;
  earfcn: number;
  rsrp: number;
  rsrq: number;
  cellId: number;
}

export interface CaComponent {
  band: number;
  isNr: boolean;
  dlFrequencyKhz: number;
  dlBandwidthKhz: number;
}

export interface RadioInfo {
  technology: CellularTechnology;
  operatorName: string;
  mcc: string;
  mnc: string;
  simSlot: number;
  cellId: number;
  lac: number;
  pci: number;
  band: number;
  earfcn: number;
  dlFrequencyKhz: number;
  dlBandwidthKhz: number;
  rssi: number;
  rsrp: number;
  rsrq: number;
  sinr: number;
  signalLevel: number; // 0..4
  cqi: number;
  timingAdvance: number;
  csiRsrp: number;
  csiRsrq: number;
  csiSinr: number;
  nrStatus: NrStatus;
  aggregatedBands: CaComponent[];
  aggregatedNrBands: number[];
  isRegistered: boolean;
  connectionState: ConnectionState;
  neighborCells: NeighborCell[];
}

export interface GpsInfo {
  latitude: number;
  longitude: number;
  altitude: number;
  speed: number; // m/s
  accuracy: number;
  bearing: number;
  timestampMs: number;
  isValid: boolean;
}

export interface Measurement {
  id: number;
  campaignId: number;
  timestampMs: number;
  radio: RadioInfo;
  gps: GpsInfo;
}

export interface Campaign {
  id: number;
  name: string;
  preset?: CampaignPreset;
  startTime: number;
  endTime: number | null;
  measurementCount: number;
  distanceMeters: number;
  isActive: boolean;
  speedTestStatus: SpeedTestStatus;
  speedDownloadMbps: number;
  speedUploadMbps: number;
  speedPingMs: number;
  speedJitterMs: number;
  speedTestTimestampMs: number;
  speedTestLat: number;
  speedTestLon: number;
  alertThresholdRsrp: number | null;
  ratMode?: RatSelectionMode;
}

export interface SpeedTestResult {
  id: number;
  campaignId: number;
  timestampMs: number;
  status: SpeedTestStatus;
  downloadMbps: number;
  uploadMbps: number;
  pingMs: number;
  jitterMs: number;
  loadedPingMs: number;
  latitude: number;
  longitude: number;
  technology: CellularTechnology;
  band: number;
  cellId: number;
  pci: number;
  rsrp: number;
  transport: TransportType;
  dataSubscriptionId: number;
}

export interface SpeedTestProgress {
  phase: SpeedTestPhase;
  pingMs: number;
  downloadMbps: number;
  uploadMbps: number;
  loadedPingMs: number;
}

export interface LightNetworkProbe {
  pingMs: number;
  jitterMs: number;
  success: boolean;
  timestampMs: number;
}

export interface SessionEvent {
  id: number;
  campaignId: number;
  type: SessionEventType;
  timestampMs: number;
  durationMs: number;
  pingMs: number;
  jitterMs: number;
  success: boolean;
  latitude: number;
  longitude: number;
  technology: CellularTechnology;
  cellId: number;
}

export interface CampaignFinishedSummary {
  campaignId: number;
  campaignName: string;
  durationMs: number;
  distanceMeters: number;
  measurementCount: number;
  avgRsrp: number;
  minRsrp: number;
  maxRsrp: number;
  handoverCount: number;
  ratMode?: RatSelectionMode;
}

export interface SignalStats {
  avgRsrp: number;
  minRsrp: number;
  maxRsrp: number;
  avgRsrq: number;
  avgSinr: number;
  avgRssi: number;
  coveragePercent: number; // % of samples >= rsrpGood or >= -105 dBm
  totalMeasurements: number;
}

export interface TimePoint {
  relativeMs: number;
  timestampMs: number;
  rsrp: number;
  rsrq: number;
  sinr: number;
  technology: CellularTechnology;
  cellId: number;
  pci: number;
}

export interface CdfPoint {
  rsrp: number;
  percentile: number; // 0..100
}

export interface TechBreakdownItem {
  technology: CellularTechnology;
  count: number;
  percent: number;
}

export interface BandUsage {
  band: number;
  isNr: boolean;
  freqLabel: string;
  count: number;
  percent: number;
}

export interface CellSite {
  cellId: number;
  pci: number;
  technology: CellularTechnology;
  band: number;
  earfcn: number;
  count: number;
  avgRsrp: number;
  firstSeenMs: number;
  latitude: number;
  longitude: number;
}

export interface HandoverEvent {
  id: string;
  measurement: Measurement;
  previousCellId: number;
  previousPci: number;
  previousTechnology: CellularTechnology;
  newCellId: number;
  newPci: number;
  newTechnology: CellularTechnology;
  isPingPong: boolean;
}

export interface MissedHandoverEvent {
  id: string;
  measurement: Measurement;
  servingPci: number;
  servingRsrp: number;
  neighborPci: number;
  neighborRsrp: number;
  neighborTechnology: CellularTechnology;
  marginDb: number;
}

export interface MobilityStats {
  handovers: number;
  pingPong: number;
  distinctCells: number;
  missedHandovers: number;
  handoverEvents: HandoverEvent[];
  missedHandoverEvents: MissedHandoverEvent[];
}

export interface DiagnosticThresholds {
  weakRsrp: number; // default -105 dBm
  poorRsrq: number; // default -15 dB
  lowThroughputMbps: number; // default 10 Mbps
  highLoadedLatencyMs: number; // default 150 ms
}

export interface DiagnosticIssue {
  id: string;
  type: DiagnosticIssueType;
  startMs: number;
  endMs: number;
  samples: number;
  averageRsrp: number;
  averageRsrq: number;
  downloadMbps: number;
  loadedPingMs: number;
  latitude: number;
  longitude: number;
  cellId: number;
  pci: number;
}

export interface CampaignDiagnostic {
  score: number; // 0..100
  weakSignalPercent: number;
  poorQualityPercent: number;
  handovers: number;
  pingPongCount: number;
  issues: DiagnosticIssue[];
  verdict: 'EXCELLENT' | 'GOOD' | 'FAIR' | 'CRITICAL';
}

export interface AnalyticsSnapshot {
  signalStats: SignalStats;
  timeSeries: TimePoint[];
  cdfRsrp: CdfPoint[];
  techBreakdown: TechBreakdownItem[];
  bandUsage: BandUsage[];
  nrUsagePercent: number;
  cellSites: CellSite[];
  mobilityStats: MobilityStats;
  diagnostic: CampaignDiagnostic;
}

export interface MapOptions {
  colorMode: MapColorMode;
  mapType: MapBaseType;
  signalPalette: SignalPalette;
  showCurrentCell: boolean;
  showNeighborCells: boolean;
  showHandovers: boolean;
  showMissedHandovers: boolean;
  dotSize: number;
  rsrpGood: number; // default -90 dBm
  rsrpPoor: number; // default -110 dBm
  followMyPosition: boolean;
  headingUp: boolean;
}

export interface ComparisonPoint {
  latitude: number;
  longitude: number;
  activeValue: number;
  referenceValue: number;
  delta: number; // activeValue - referenceValue
}

export interface CampaignComparisonResult {
  points: ComparisonPoint[];
  matchedCount: number;
  geographicMatchCount: number;
  totalCount: number;
  avgDelta: number;
  improvedPercent: number;
  degradedPercent: number;
  error: ComparisonError;
}

