import type {
  CaComponent,
  CampaignPreset,
  CellularTechnology,
  GpsInfo,
  LightNetworkProbe,
  Measurement,
  MeasurementCadence,
  NeighborCell,
  NrStatus,
  RadioInfo,
  RatSelectionMode,
  SimInfo,
} from '../types/drivetest';

export const DEFAULT_SIM_CARDS: SimInfo[] = [
  {
    subscriptionId: 1,
    slotIndex: 0,
    displayName: 'SIM 1 · AIS 5G SA',
    carrierName: 'AIS 5G',
    mcc: '520',
    mnc: '03',
    isActive: true,
  },
  {
    subscriptionId: 2,
    slotIndex: 1,
    displayName: 'SIM 2 · TRUE-DTAC 5G',
    carrierName: 'TRUE 5G',
    mcc: '520',
    mnc: '04',
    isActive: true,
  },
];

export const BLANK_RADIO_INFO: RadioInfo = {
  technology: 'UNKNOWN',
  operatorName: 'STANDBY (NO SIGNAL)',
  mcc: '---',
  mnc: '--',
  simSlot: 0,
  cellId: 0,
  lac: 0,
  pci: 0,
  band: 0,
  earfcn: 0,
  dlFrequencyKhz: 0,
  dlBandwidthKhz: 0,
  rssi: -120,
  rsrp: -140,
  rsrq: -20,
  sinr: -20,
  signalLevel: 0,
  cqi: 0,
  timingAdvance: 0,
  csiRsrp: -140,
  csiRsrq: -20,
  csiSinr: -20,
  nrStatus: 'NONE',
  aggregatedBands: [],
  aggregatedNrBands: [],
  isRegistered: false,
  connectionState: 'DISCONNECTED',
  neighborCells: [],
};

export function getCadenceIntervalMs(cadence: MeasurementCadence): number {
  switch (cadence) {
    case 'ECO':
      return 5000;
    case 'STANDARD':
      return 2000;
    case 'PRECISE':
      return 1000;
  }
}

interface PresetSector {
  cellId: number;
  pci: number;
  lac: number;
  technology: CellularTechnology;
  nrStatus: NrStatus;
  band: number;
  earfcn: number;
  dlFrequencyKhz: number;
  dlBandwidthKhz: number;
  baseRsrp: number;
  caBands: CaComponent[];
}

const PRESET_SECTORS: Record<CampaignPreset, PresetSector[]> = {
  URBAN_ROUTE: [
    {
      cellId: 520101,
      pci: 312,
      lac: 2101,
      technology: 'NR_5G',
      nrStatus: 'SA',
      band: 78,
      earfcn: 632000,
      dlFrequencyKhz: 3500000,
      dlBandwidthKhz: 100000,
      baseRsrp: -81,
      caBands: [
        { band: 78, isNr: true, dlFrequencyKhz: 3500000, dlBandwidthKhz: 100000 },
        { band: 28, isNr: true, dlFrequencyKhz: 700000, dlBandwidthKhz: 20000 },
      ],
    },
    {
      cellId: 520102,
      pci: 318,
      lac: 2101,
      technology: 'NR_5G',
      nrStatus: 'NSA',
      band: 41,
      earfcn: 518000,
      dlFrequencyKhz: 2600000,
      dlBandwidthKhz: 80000,
      baseRsrp: -89,
      caBands: [
        { band: 41, isNr: true, dlFrequencyKhz: 2600000, dlBandwidthKhz: 80000 },
        { band: 3, isNr: false, dlFrequencyKhz: 1800000, dlBandwidthKhz: 20000 },
        { band: 1, isNr: false, dlFrequencyKhz: 2100000, dlBandwidthKhz: 15000 },
      ],
    },
    {
      cellId: 410205,
      pci: 144,
      lac: 2102,
      technology: 'LTE_4G',
      nrStatus: 'NONE',
      band: 3,
      earfcn: 1650,
      dlFrequencyKhz: 1800000,
      dlBandwidthKhz: 20000,
      baseRsrp: -102,
      caBands: [
        { band: 3, isNr: false, dlFrequencyKhz: 1800000, dlBandwidthKhz: 20000 },
        { band: 1, isNr: false, dlFrequencyKhz: 2100000, dlBandwidthKhz: 15000 },
      ],
    },
    {
      cellId: 520103,
      pci: 405,
      lac: 2101,
      technology: 'NR_5G',
      nrStatus: 'SA',
      band: 78,
      earfcn: 632000,
      dlFrequencyKhz: 3500000,
      dlBandwidthKhz: 100000,
      baseRsrp: -78,
      caBands: [
        { band: 78, isNr: true, dlFrequencyKhz: 3500000, dlBandwidthKhz: 100000 },
        { band: 41, isNr: true, dlFrequencyKhz: 2600000, dlBandwidthKhz: 60000 },
      ],
    },
  ],
  HIGHWAY: [
    {
      cellId: 610301,
      pci: 210,
      lac: 3100,
      technology: 'NR_5G',
      nrStatus: 'NSA',
      band: 28,
      earfcn: 152000,
      dlFrequencyKhz: 700000,
      dlBandwidthKhz: 20000,
      baseRsrp: -86,
      caBands: [
        { band: 28, isNr: true, dlFrequencyKhz: 700000, dlBandwidthKhz: 20000 },
        { band: 3, isNr: false, dlFrequencyKhz: 1800000, dlBandwidthKhz: 20000 },
      ],
    },
    {
      cellId: 610302,
      pci: 215,
      lac: 3100,
      technology: 'LTE_4G',
      nrStatus: 'NONE',
      band: 3,
      earfcn: 1650,
      dlFrequencyKhz: 1800000,
      dlBandwidthKhz: 20000,
      baseRsrp: -96,
      caBands: [
        { band: 3, isNr: false, dlFrequencyKhz: 1800000, dlBandwidthKhz: 20000 },
        { band: 8, isNr: false, dlFrequencyKhz: 900000, dlBandwidthKhz: 10000 },
      ],
    },
    {
      cellId: 610303,
      pci: 88,
      lac: 3105,
      technology: 'WCDMA_3G',
      nrStatus: 'NONE',
      band: 1,
      earfcn: 10713,
      dlFrequencyKhz: 2100000,
      dlBandwidthKhz: 5000,
      baseRsrp: -109,
      caBands: [{ band: 1, isNr: false, dlFrequencyKhz: 2100000, dlBandwidthKhz: 5000 }],
    },
    {
      cellId: 610304,
      pci: 224,
      lac: 3100,
      technology: 'NR_5G',
      nrStatus: 'SA',
      band: 78,
      earfcn: 632000,
      dlFrequencyKhz: 3500000,
      dlBandwidthKhz: 100000,
      baseRsrp: -84,
      caBands: [
        { band: 78, isNr: true, dlFrequencyKhz: 3500000, dlBandwidthKhz: 100000 },
      ],
    },
  ],
  SITE_AUDIT: [
    {
      cellId: 700101,
      pci: 11,
      lac: 4001,
      technology: 'NR_5G',
      nrStatus: 'SA',
      band: 78,
      earfcn: 632000,
      dlFrequencyKhz: 3500000,
      dlBandwidthKhz: 100000,
      baseRsrp: -74,
      caBands: [
        { band: 78, isNr: true, dlFrequencyKhz: 3500000, dlBandwidthKhz: 100000 },
        { band: 41, isNr: true, dlFrequencyKhz: 2600000, dlBandwidthKhz: 80000 },
      ],
    },
    {
      cellId: 700102,
      pci: 12,
      lac: 4001,
      technology: 'NR_5G',
      nrStatus: 'SA',
      band: 78,
      earfcn: 632000,
      dlFrequencyKhz: 3500000,
      dlBandwidthKhz: 100000,
      baseRsrp: -79,
      caBands: [
        { band: 78, isNr: true, dlFrequencyKhz: 3500000, dlBandwidthKhz: 100000 },
      ],
    },
    {
      cellId: 700103,
      pci: 13,
      lac: 4001,
      technology: 'NR_5G',
      nrStatus: 'SA',
      band: 78,
      earfcn: 632000,
      dlFrequencyKhz: 3500000,
      dlBandwidthKhz: 100000,
      baseRsrp: -83,
      caBands: [
        { band: 78, isNr: true, dlFrequencyKhz: 3500000, dlBandwidthKhz: 100000 },
      ],
    },
  ],
  INDOOR: [
    {
      cellId: 800501,
      pci: 450,
      lac: 5001,
      technology: 'NR_5G',
      nrStatus: 'NSA',
      band: 28,
      earfcn: 152000,
      dlFrequencyKhz: 700000,
      dlBandwidthKhz: 20000,
      baseRsrp: -98,
      caBands: [
        { band: 28, isNr: true, dlFrequencyKhz: 700000, dlBandwidthKhz: 20000 },
        { band: 8, isNr: false, dlFrequencyKhz: 900000, dlBandwidthKhz: 10000 },
      ],
    },
    {
      cellId: 800502,
      pci: 452,
      lac: 5001,
      technology: 'LTE_4G',
      nrStatus: 'NONE',
      band: 8,
      earfcn: 3600,
      dlFrequencyKhz: 900000,
      dlBandwidthKhz: 10000,
      baseRsrp: -111,
      caBands: [{ band: 8, isNr: false, dlFrequencyKhz: 900000, dlBandwidthKhz: 10000 }],
    },
    {
      cellId: 800503,
      pci: 64,
      lac: 5002,
      technology: 'GSM_2G',
      nrStatus: 'NONE',
      band: 8,
      earfcn: 35,
      dlFrequencyKhz: 900000,
      dlBandwidthKhz: 200,
      baseRsrp: -116,
      caBands: [],
    },
  ],
};

function pseudoNoise(seed: number): number {
  const x = Math.sin(seed * 12.9898 + 78.233) * 43758.5453;
  return x - Math.floor(x);
}

export function generateSimulatedMeasurement(params: {
  campaignId: number;
  stepIndex: number;
  preset: CampaignPreset;
  sim: SimInfo;
  timestampMs: number;
  overrideGps?: Partial<GpsInfo>;
  rsrpOffsetDb?: number;
  ratMode?: RatSelectionMode;
}): Measurement {
  const {
    campaignId,
    stepIndex,
    preset,
    sim,
    timestampMs,
    overrideGps,
    rsrpOffsetDb = 0,
    ratMode = 'AUTO',
  } = params;

  let sectors = PRESET_SECTORS[preset] ?? PRESET_SECTORS.URBAN_ROUTE;

  if (ratMode === 'NR_5G_ONLY') {
    const nrOnly = sectors.filter((s) => s.technology === 'NR_5G');
    if (nrOnly.length > 0) {
      sectors = nrOnly;
    } else {
      sectors = sectors.map((s) => ({
        ...s,
        technology: 'NR_5G',
        nrStatus: 'SA',
        band: 78,
        earfcn: 632000,
        dlFrequencyKhz: 3500000,
        dlBandwidthKhz: 100000,
      }));
    }
  } else if (ratMode === 'LTE_4G_ONLY') {
    const lteOnly = sectors.filter((s) => s.technology === 'LTE_4G');
    if (lteOnly.length > 0) {
      sectors = lteOnly;
    } else {
      sectors = sectors.map((s) => ({
        ...s,
        technology: 'LTE_4G',
        nrStatus: 'NONE',
        band: 3,
        earfcn: 1650,
        dlFrequencyKhz: 1800000,
        dlBandwidthKhz: 20000,
        caBands: s.caBands.filter((ca) => !ca.isNr),
      }));
    }
  }
  // Transition across sectors with occasional ping-pong around step 8-10
  let sectorIdx = Math.floor(stepIndex / 6) % sectors.length;
  if (stepIndex === 9 && sectors.length > 1) {
    sectorIdx = 0; // Deliberate brief return to sector 0 for realistic ping-pong detection
  }
  const sector = sectors[sectorIdx];

  const wave = Math.sin(stepIndex * 0.45) * 7 + (pseudoNoise(stepIndex + campaignId) - 0.5) * 6;
  const rsrp = Math.max(-132, Math.min(-58, Math.round(sector.baseRsrp + wave + rsrpOffsetDb)));
  const rsrq = Math.max(-20, Math.min(-4, Math.round(-9 + (rsrp + 85) * 0.18)));
  const sinr = Number(
    Math.max(-6, Math.min(32, 16 + (rsrp + 85) * 0.45 + (pseudoNoise(stepIndex * 3) - 0.5) * 3)).toFixed(1)
  );
  const rssi = Math.min(-45, rsrp + 21);

  const signalLevel = rsrp >= -85 ? 4 : rsrp >= -95 ? 3 : rsrp >= -105 ? 2 : rsrp >= -115 ? 1 : 0;
  const cqi = Math.max(1, Math.min(15, Math.round((sinr + 6) / 2.4)));
  const timingAdvance = Math.max(0, Math.min(64, Math.round((Math.abs(rsrp) - 70) / 4)));

  // Build neighbor cells (occasionally making a neighbor >6 dB stronger to trigger MissedHandoverEvent)
  const neighborCells: NeighborCell[] = sectors
    .filter((_, idx) => idx !== sectorIdx)
    .map((nSec, idx) => {
      const isMissedHoStep = stepIndex % 11 === 4 && idx === 0;
      const nRsrp = isMissedHoStep
        ? Math.min(-65, rsrp + 9) // 9 dB stronger neighbor!
        : Math.max(-130, rsrp - 5 - idx * 4);
      return {
        technology: nSec.technology,
        pci: nSec.pci,
        earfcn: nSec.earfcn,
        rsrp: nRsrp,
        rsrq: Math.max(-19, rsrq - 2),
        cellId: nSec.cellId,
      };
    });

  // Base route coordinates in central Bangkok (Sukhumvit / Silom / Rama IX corridor)
  const baseLat = preset === 'HIGHWAY' ? 13.748 : preset === 'INDOOR' ? 13.7465 : 13.7563;
  const baseLon = preset === 'HIGHWAY' ? 100.562 : preset === 'INDOOR' ? 100.5347 : 100.5018;
  const stepDist = preset === 'HIGHWAY' ? 0.0014 : preset === 'INDOOR' ? 0.00018 : 0.00055;
  const speedMs =
    preset === 'HIGHWAY'
      ? Number((24 + pseudoNoise(stepIndex) * 6).toFixed(1))
      : preset === 'INDOOR'
      ? Number((1.2 + pseudoNoise(stepIndex) * 0.6).toFixed(1))
      : Number((9.5 + pseudoNoise(stepIndex) * 4.5).toFixed(1));

  const lat = overrideGps?.latitude ?? Number((baseLat + stepIndex * stepDist * 0.72).toFixed(6));
  const lon =
    overrideGps?.longitude ??
    Number((baseLon + stepIndex * stepDist * 0.85 + Math.sin(stepIndex * 0.3) * 0.0002).toFixed(6));

  const radio: RadioInfo = {
    technology: sector.technology,
    operatorName: sim.carrierName,
    mcc: sim.mcc,
    mnc: sim.mnc,
    simSlot: sim.slotIndex,
    cellId: sector.cellId + sim.slotIndex * 1000,
    lac: sector.lac,
    pci: sector.pci,
    band: sector.band,
    earfcn: sector.earfcn,
    dlFrequencyKhz: sector.dlFrequencyKhz,
    dlBandwidthKhz: sector.dlBandwidthKhz,
    rssi,
    rsrp,
    rsrq,
    sinr,
    signalLevel,
    cqi,
    timingAdvance,
    csiRsrp: rsrp + 1,
    csiRsrq: rsrq,
    csiSinr: Math.round(sinr),
    nrStatus: sector.nrStatus,
    aggregatedBands: sector.caBands,
    aggregatedNrBands: sector.caBands.filter((b) => b.isNr).map((b) => b.band),
    isRegistered: true,
    connectionState: 'CONNECTED',
    neighborCells,
  };

  const gps: GpsInfo = {
    latitude: lat,
    longitude: lon,
    altitude: overrideGps?.altitude ?? 14.5,
    speed: overrideGps?.speed ?? speedMs,
    accuracy: overrideGps?.accuracy ?? (preset === 'INDOOR' ? 12.5 : 3.8),
    bearing: overrideGps?.bearing ?? Math.round((45 + stepIndex * 7) % 360),
    timestampMs,
    isValid: overrideGps?.isValid ?? true,
  };

  return {
    id: timestampMs + stepIndex,
    campaignId,
    timestampMs,
    radio,
    gps,
  };
}

export function runSimulatedSpeedTestStep(
  technology: CellularTechnology,
  rsrp: number
): {
  pingMs: number;
  jitterMs: number;
  loadedPingMs: number;
  downloadMbps: number;
  uploadMbps: number;
} {
  const signalQualityFactor = Math.max(0.12, Math.min(1.2, (rsrp + 125) / 45));

  if (technology === 'NR_5G') {
    const downloadMbps = Number((380 * signalQualityFactor + 25).toFixed(1));
    const uploadMbps = Number((68 * signalQualityFactor + 8).toFixed(1));
    const pingMs = Number((Math.max(9, 28 - signalQualityFactor * 14)).toFixed(1));
    const jitterMs = Number((Math.max(1.2, 6 - signalQualityFactor * 3.5)).toFixed(1));
    const loadedPingMs = Number((pingMs * (2.2 - signalQualityFactor * 0.6)).toFixed(1));
    return { pingMs, jitterMs, loadedPingMs, downloadMbps, uploadMbps };
  }

  if (technology === 'LTE_4G') {
    const downloadMbps = Number((95 * signalQualityFactor + 6).toFixed(1));
    const uploadMbps = Number((28 * signalQualityFactor + 3).toFixed(1));
    const pingMs = Number((Math.max(18, 46 - signalQualityFactor * 18)).toFixed(1));
    const jitterMs = Number((Math.max(2.5, 11 - signalQualityFactor * 5)).toFixed(1));
    const loadedPingMs = Number((pingMs * (3.1 - signalQualityFactor * 0.8)).toFixed(1));
    return { pingMs, jitterMs, loadedPingMs, downloadMbps, uploadMbps };
  }

  const downloadMbps = Number((12 * signalQualityFactor + 1.2).toFixed(1));
  const uploadMbps = Number((3.5 * signalQualityFactor + 0.5).toFixed(1));
  const pingMs = Number((85 - signalQualityFactor * 20).toFixed(1));
  const jitterMs = Number((18 - signalQualityFactor * 5).toFixed(1));
  const loadedPingMs = Number((pingMs * 2.8).toFixed(1));
  return { pingMs, jitterMs, loadedPingMs, downloadMbps, uploadMbps };
}

export function generateLightProbeSample(
  technology: CellularTechnology,
  rsrp: number,
  timestampMs: number
): LightNetworkProbe {
  const basePing = technology === 'NR_5G' ? 16 : technology === 'LTE_4G' ? 32 : 75;
  const penalty = rsrp < -105 ? (Math.abs(rsrp) - 105) * 6 : 0;
  const pingMs = Number((basePing + penalty + (timestampMs % 7)).toFixed(1));
  const jitterMs = Number((Math.max(1.5, penalty * 0.22 + 2.4)).toFixed(1));
  const success = rsrp > -126;

  return {
    pingMs,
    jitterMs,
    success,
    timestampMs,
  };
}

