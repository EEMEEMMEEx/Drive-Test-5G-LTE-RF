import { describe, expect, it } from 'vitest';
import {
  calculateSignalStats,
  calculateCdf,
  detectMobilityEvents,
  calculateBandUsage,
  calculateTechBreakdown,
  calculateCampaignDiagnostic,
  compareCampaignMeasurements,
  getSignalColor,
  haversineDistanceMeters,
} from '../services/rfAnalyticsEngine';
import type { Measurement, SpeedTestResult, SessionEvent, DiagnosticThresholds } from '../types/drivetest';

function makeMeasurement(overrides: {
  id: number;
  timestampMs: number;
  lat?: number;
  lon?: number;
  technology?: 'GSM_2G' | 'WCDMA_3G' | 'LTE_4G' | 'NR_5G';
  nrStatus?: 'SA' | 'NSA' | 'NONE';
  cellId?: number;
  pci?: number;
  band?: number;
  earfcn?: number;
  dlFrequencyKhz?: number;
  rsrp?: number;
  rsrq?: number;
  sinr?: number;
  rssi?: number;
  neighbors?: Array<{ pci: number; rsrp: number; rsrq?: number; earfcn?: number; cellId?: number; technology?: 'LTE_4G' | 'NR_5G' }>;
}): Measurement {
  return {
    id: overrides.id,
    campaignId: 1,
    timestampMs: overrides.timestampMs,
    radio: {
      technology: overrides.technology ?? 'NR_5G',
      operatorName: 'AIS 5G',
      mcc: '520',
      mnc: '03',
      simSlot: 0,
      cellId: overrides.cellId ?? 1001,
      lac: 2010,
      pci: overrides.pci ?? 101,
      band: overrides.band ?? 78,
      earfcn: overrides.earfcn ?? 632000,
      dlFrequencyKhz: overrides.dlFrequencyKhz ?? 3500000,
      dlBandwidthKhz: 100000,
      rssi: overrides.rssi ?? -65,
      rsrp: overrides.rsrp ?? -85,
      rsrq: overrides.rsrq ?? -9,
      sinr: overrides.sinr ?? 18,
      signalLevel: 4,
      cqi: 13,
      timingAdvance: 2,
      csiRsrp: overrides.rsrp ?? -85,
      csiRsrq: overrides.rsrq ?? -9,
      csiSinr: overrides.sinr ?? 18,
      nrStatus: overrides.nrStatus ?? 'SA',
      aggregatedBands: [],
      aggregatedNrBands: [78],
      isRegistered: true,
      connectionState: 'CONNECTED',
      neighborCells: (overrides.neighbors ?? []).map((n) => ({
        technology: n.technology ?? 'NR_5G',
        pci: n.pci,
        earfcn: n.earfcn ?? 632000,
        rsrp: n.rsrp,
        rsrq: n.rsrq ?? -10,
        cellId: n.cellId ?? 2000 + n.pci,
      })),
    },
    gps: {
      latitude: overrides.lat ?? 13.7563,
      longitude: overrides.lon ?? 100.5018,
      altitude: 15,
      speed: 12,
      accuracy: 4,
      bearing: 90,
      timestampMs: overrides.timestampMs,
      isValid: true,
    },
  };
}

describe('rfAnalyticsEngine', () => {
  it('calculates signal statistics and coverage percent accurately', () => {
    const samples: Measurement[] = [
      makeMeasurement({ id: 1, timestampMs: 1000, rsrp: -80, rsrq: -8, sinr: 22, rssi: -60 }),
      makeMeasurement({ id: 2, timestampMs: 3000, rsrp: -95, rsrq: -11, sinr: 14, rssi: -72 }),
      makeMeasurement({ id: 3, timestampMs: 5000, rsrp: -115, rsrq: -17, sinr: -2, rssi: -92 }),
      makeMeasurement({ id: 4, timestampMs: 7000, rsrp: -90, rsrq: -10, sinr: 16, rssi: -68 }),
    ];

    const stats = calculateSignalStats(samples, -105);
    expect(stats.totalMeasurements).toBe(4);
    expect(stats.avgRsrp).toBeCloseTo(-95, 1);
    expect(stats.minRsrp).toBe(-115);
    expect(stats.maxRsrp).toBe(-80);
    expect(stats.avgRsrq).toBeCloseTo(-11.5, 1);
    expect(stats.avgSinr).toBeCloseTo(12.5, 1);
    expect(stats.coveragePercent).toBeCloseTo(75, 1); // 3 of 4 >= -105 dBm
  });

  it('builds cumulative distribution function (CDF) points sorted by RSRP', () => {
    const samples: Measurement[] = [
      makeMeasurement({ id: 1, timestampMs: 1000, rsrp: -110 }),
      makeMeasurement({ id: 2, timestampMs: 2000, rsrp: -80 }),
      makeMeasurement({ id: 3, timestampMs: 3000, rsrp: -100 }),
      makeMeasurement({ id: 4, timestampMs: 4000, rsrp: -90 }),
    ];

    const cdf = calculateCdf(samples);
    expect(cdf.length).toBe(4);
    expect(cdf[0].rsrp).toBe(-110);
    expect(cdf[0].percentile).toBeCloseTo(25, 1);
    expect(cdf[3].rsrp).toBe(-80);
    expect(cdf[3].percentile).toBeCloseTo(100, 1);
  });

  it('detects handovers, ping-pong (<30s return to previous cell), and missed handovers', () => {
    const samples: Measurement[] = [
      makeMeasurement({ id: 1, timestampMs: 1000, cellId: 1001, pci: 101, rsrp: -92 }),
      makeMeasurement({ id: 2, timestampMs: 5000, cellId: 1002, pci: 102, rsrp: -94 }), // Handover 1001 -> 1002
      makeMeasurement({
        id: 3,
        timestampMs: 15000,
        cellId: 1001,
        pci: 101,
        rsrp: -108,
        neighbors: [{ pci: 205, rsrp: -95 }], // 13 dB stronger neighbor -> Missed handover!
      }), // Handover 1002 -> 1001 within 10s (<30s) -> Ping-pong!
    ];

    const mobility = detectMobilityEvents(samples, 6);
    expect(mobility.handovers).toBe(2);
    expect(mobility.pingPong).toBe(1);
    expect(mobility.distinctCells).toBe(2);
    expect(mobility.missedHandovers).toBe(1);
    expect(mobility.missedHandoverEvents[0].neighborPci).toBe(205);
    expect(mobility.missedHandoverEvents[0].marginDb).toBe(13);
  });

  it('computes technology breakdown, 5G NR usage, and band usage', () => {
    const samples: Measurement[] = [
      makeMeasurement({ id: 1, timestampMs: 1000, technology: 'NR_5G', nrStatus: 'SA', band: 78, dlFrequencyKhz: 3500000 }),
      makeMeasurement({ id: 2, timestampMs: 2000, technology: 'LTE_4G', nrStatus: 'NSA', band: 3, dlFrequencyKhz: 1800000 }),
      makeMeasurement({ id: 3, timestampMs: 3000, technology: 'LTE_4G', nrStatus: 'NONE', band: 1, dlFrequencyKhz: 2100000 }),
    ];

    const { techBreakdown, nrUsagePercent } = calculateTechBreakdown(samples);
    expect(techBreakdown.find((t) => t.technology === 'NR_5G')?.count).toBe(1);
    expect(techBreakdown.find((t) => t.technology === 'LTE_4G')?.count).toBe(2);
    expect(nrUsagePercent).toBeCloseTo(66.67, 1); // 1 SA + 1 NSA out of 3

    const bands = calculateBandUsage(samples);
    expect(bands.length).toBe(3);
    expect(bands[0].freqLabel).toBeTruthy();
  });

  it('scores route diagnosis and identifies problem zones across all 5 issue categories', () => {
    const samples: Measurement[] = [
      makeMeasurement({ id: 1, timestampMs: 1000, rsrp: -115, rsrq: -18, cellId: 101 }),
      makeMeasurement({ id: 2, timestampMs: 3000, rsrp: -114, rsrq: -17, cellId: 102 }),
      makeMeasurement({ id: 3, timestampMs: 5000, rsrp: -116, rsrq: -19, cellId: 101 }),
    ];
    const speedTests: SpeedTestResult[] = [
      {
        id: 1,
        campaignId: 1,
        timestampMs: 2000,
        status: 'SUCCESS',
        downloadMbps: 3.2,
        uploadMbps: 1.1,
        pingMs: 45,
        jitterMs: 12,
        loadedPingMs: 240,
        latitude: 13.7563,
        longitude: 100.5018,
        technology: 'LTE_4G',
        band: 3,
        cellId: 101,
        pci: 101,
        rsrp: -115,
        transport: 'MOBILE',
        dataSubscriptionId: 1,
      },
    ];
    const sessionEvents: SessionEvent[] = [];
    const thresholds: DiagnosticThresholds = {
      weakRsrp: -105,
      poorRsrq: -15,
      lowThroughputMbps: 10,
      highLoadedLatencyMs: 150,
    };

    const diag = calculateCampaignDiagnostic(samples, speedTests, sessionEvents, thresholds);
    expect(diag.score).toBeLessThan(70);
    expect(diag.weakSignalPercent).toBe(100);
    expect(diag.poorQualityPercent).toBe(100);
    const issueTypes = diag.issues.map((i) => i.type);
    expect(issueTypes).toContain('WEAK_SIGNAL');
    expect(issueTypes).toContain('POOR_QUALITY');
    expect(issueTypes).toContain('LOW_THROUGHPUT');
    expect(issueTypes).toContain('HIGH_LOADED_LATENCY');
  });

  it('compares two overlapping campaigns and computes spatial RSRP delta', () => {
    const active: Measurement[] = [
      makeMeasurement({ id: 1, timestampMs: 1000, lat: 13.7563, lon: 100.5018, rsrp: -82 }),
      makeMeasurement({ id: 2, timestampMs: 2000, lat: 13.7568, lon: 100.5023, rsrp: -88 }),
    ];
    const reference: Measurement[] = [
      makeMeasurement({ id: 10, timestampMs: 1000, lat: 13.75632, lon: 100.50182, rsrp: -94 }),
      makeMeasurement({ id: 11, timestampMs: 2000, lat: 13.75682, lon: 100.50232, rsrp: -96 }),
    ];

    const cmp = compareCampaignMeasurements(active, reference, 'RSRP');
    expect(cmp.error).toBeNull();
    expect(cmp.points.length).toBe(2);
    expect(cmp.avgDelta).toBeCloseTo(10, 1); // (-82 - -94 = +12, -88 - -96 = +8) -> avg +10 dB
    expect(cmp.improvedPercent).toBe(100);
  });

  it('computes haversine distance and palette colors', () => {
    const d = haversineDistanceMeters(13.7563, 100.5018, 13.7663, 100.5018);
    expect(d).toBeGreaterThan(1000);
    expect(d).toBeLessThan(1200);
    expect(getSignalColor(-80, 'BY_RSRP', 'CLASSIC', -90, -110)).toMatch(/^#/);
  });
});

