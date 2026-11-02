-- Replica Schema for GSM Drive Test: 5G LTE RF (com.drivetest.app v1.8.0)
-- Compatible with SQLite (Room 1:1 DDL) and PostgreSQL / IndexedDB persistence layer

-- 1. Campaigns table
CREATE TABLE IF NOT EXISTS campaigns (
  id TEXT PRIMARY KEY NOT NULL,
  name TEXT NOT NULL,
  startTime INTEGER NOT NULL,
  endTime INTEGER,
  measurementCount INTEGER NOT NULL DEFAULT 0,
  distanceMeters REAL NOT NULL DEFAULT 0.0,
  isActive INTEGER NOT NULL DEFAULT 0 CHECK (isActive IN (0, 1)),
  speedTestStatus TEXT NOT NULL DEFAULT 'NONE' CHECK (speedTestStatus IN ('NONE', 'RUNNING', 'SUCCESS', 'FAILED')),
  speedDownloadMbps REAL,
  speedUploadMbps REAL,
  speedPingMs INTEGER,
  speedJitterMs INTEGER,
  speedTestTimestampMs INTEGER,
  speedTestLat REAL,
  speedTestLon REAL,
  alertThresholdRsrp INTEGER
);

CREATE INDEX IF NOT EXISTS index_campaigns_startTime ON campaigns (startTime DESC);
CREATE INDEX IF NOT EXISTS index_campaigns_isActive ON campaigns (isActive);

-- 2. Measurements table
CREATE TABLE IF NOT EXISTS measurements (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  campaignId TEXT NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
  timestampMs INTEGER NOT NULL,
  simSlot INTEGER NOT NULL DEFAULT 0,
  latitude REAL NOT NULL,
  longitude REAL NOT NULL,
  altitude REAL NOT NULL DEFAULT 0.0,
  speed REAL NOT NULL DEFAULT 0.0,
  accuracy REAL NOT NULL DEFAULT 0.0,
  bearing REAL NOT NULL DEFAULT 0.0,
  gpsValid INTEGER NOT NULL DEFAULT 1 CHECK (gpsValid IN (0, 1)),
  technology TEXT NOT NULL,
  operatorName TEXT NOT NULL,
  mcc TEXT NOT NULL,
  mnc TEXT NOT NULL,
  cellId INTEGER NOT NULL,
  lac INTEGER NOT NULL,
  pci INTEGER NOT NULL,
  band TEXT NOT NULL,
  earfcn INTEGER NOT NULL,
  dlFrequencyKhz INTEGER,
  dlBandwidthKhz INTEGER,
  rssi INTEGER,
  rsrp INTEGER NOT NULL,
  rsrq INTEGER NOT NULL,
  sinr INTEGER NOT NULL,
  signalLevel INTEGER NOT NULL DEFAULT 0 CHECK (signalLevel BETWEEN 0 AND 4),
  cqi INTEGER,
  timingAdvance INTEGER,
  csiRsrp INTEGER,
  csiRsrq INTEGER,
  csiSinr INTEGER,
  nrStatus TEXT,
  aggregatedBandsJson TEXT,
  isRegistered INTEGER NOT NULL DEFAULT 1 CHECK (isRegistered IN (0, 1)),
  connectionState TEXT NOT NULL,
  neighborCellsJson TEXT
);

CREATE INDEX IF NOT EXISTS index_measurements_campaignId ON measurements (campaignId);
CREATE INDEX IF NOT EXISTS index_measurements_campaignId_timestampMs ON measurements (campaignId, timestampMs);
CREATE INDEX IF NOT EXISTS index_measurements_cellId ON measurements (cellId);

-- 3. Speed Tests table
CREATE TABLE IF NOT EXISTS speed_tests (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  campaignId TEXT NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
  timestampMs INTEGER NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
  downloadMbps REAL,
  uploadMbps REAL,
  pingMs INTEGER,
  jitterMs INTEGER,
  loadedPingMs INTEGER,
  lat REAL,
  lon REAL,
  technology TEXT,
  band TEXT,
  cellId INTEGER,
  pci INTEGER,
  rsrp INTEGER,
  transport TEXT,
  dataSubscriptionId INTEGER
);

CREATE INDEX IF NOT EXISTS index_speed_tests_campaignId ON speed_tests (campaignId);
CREATE INDEX IF NOT EXISTS index_speed_tests_timestampMs ON speed_tests (campaignId, timestampMs DESC);

-- 4. Session Events table
CREATE TABLE IF NOT EXISTS session_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  campaignId TEXT NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
  type TEXT NOT NULL CHECK (type IN ('PAUSE', 'LIGHT_PROBE')),
  timestampMs INTEGER NOT NULL,
  durationMs INTEGER,
  pingMs INTEGER,
  jitterMs INTEGER,
  success INTEGER CHECK (success IN (0, 1)),
  lat REAL,
  lon REAL,
  technology TEXT,
  cellId INTEGER
);

CREATE INDEX IF NOT EXISTS index_session_events_campaignId ON session_events (campaignId);
CREATE INDEX IF NOT EXISTS index_session_events_type ON session_events (campaignId, type);
