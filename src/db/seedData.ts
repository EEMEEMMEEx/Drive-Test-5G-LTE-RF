import type {
  Campaign,
  CampaignPreset,
  Measurement,
  SessionEvent,
  SpeedTestResult,
} from '../types/drivetest';
import {
  DEFAULT_SIM_CARDS,
  generateSimulatedMeasurement,
  runSimulatedSpeedTestStep,
} from '../services/driveSimulator';
import { haversineDistanceMeters } from '../services/rfAnalyticsEngine';

export interface SeedDatabaseState {
  campaigns: Campaign[];
  measurements: Measurement[];
  speedTests: SpeedTestResult[];
  sessionEvents: SessionEvent[];
}

function buildSeedCampaign(params: {
  id: number;
  name: string;
  preset: CampaignPreset;
  startTime: number;
  sampleCount: number;
  stepIntervalMs: number;
  simIndex: number;
  rsrpOffsetDb?: number;
  alertThresholdRsrp: number | null;
}): {
  campaign: Campaign;
  measurements: Measurement[];
  speedTests: SpeedTestResult[];
  sessionEvents: SessionEvent[];
} {
  const sim = DEFAULT_SIM_CARDS[params.simIndex] ?? DEFAULT_SIM_CARDS[0];
  const measurements: Measurement[] = [];
  let totalDist = 0;

  for (let i = 0; i < params.sampleCount; i++) {
    const ts = params.startTime + i * params.stepIntervalMs;
    const m = generateSimulatedMeasurement({
      campaignId: params.id,
      stepIndex: i,
      preset: params.preset,
      sim,
      timestampMs: ts,
      rsrpOffsetDb: params.rsrpOffsetDb ?? 0,
    });
    m.id = params.id * 10000 + i + 1;
    if (i > 0) {
      const prev = measurements[i - 1];
      totalDist += haversineDistanceMeters(
        prev.gps.latitude,
        prev.gps.longitude,
        m.gps.latitude,
        m.gps.longitude
      );
    }
    measurements.push(m);
  }

  const midSample = measurements[Math.floor(measurements.length / 2)];
  const stMetrics = runSimulatedSpeedTestStep(midSample.radio.technology, midSample.radio.rsrp);

  const speedTests: SpeedTestResult[] = [
    {
      id: params.id * 100 + 1,
      campaignId: params.id,
      timestampMs: midSample.timestampMs,
      status: 'SUCCESS',
      downloadMbps: stMetrics.downloadMbps,
      uploadMbps: stMetrics.uploadMbps,
      pingMs: stMetrics.pingMs,
      jitterMs: stMetrics.jitterMs,
      loadedPingMs: stMetrics.loadedPingMs,
      latitude: midSample.gps.latitude,
      longitude: midSample.gps.longitude,
      technology: midSample.radio.technology,
      band: midSample.radio.band,
      cellId: midSample.radio.cellId,
      pci: midSample.radio.pci,
      rsrp: midSample.radio.rsrp,
      transport: 'MOBILE',
      dataSubscriptionId: sim.subscriptionId,
    },
  ];

  // Add a second speed test in a weak-signal segment if any
  const weakSample = measurements.find((m) => m.radio.rsrp < -105) ?? measurements[measurements.length - 3];
  if (weakSample) {
    speedTests.push({
      id: params.id * 100 + 2,
      campaignId: params.id,
      timestampMs: weakSample.timestampMs,
      status: 'SUCCESS',
      downloadMbps: 6.8,
      uploadMbps: 2.1,
      pingMs: 54,
      jitterMs: 14.5,
      loadedPingMs: 215,
      latitude: weakSample.gps.latitude,
      longitude: weakSample.gps.longitude,
      technology: weakSample.radio.technology,
      band: weakSample.radio.band,
      cellId: weakSample.radio.cellId,
      pci: weakSample.radio.pci,
      rsrp: weakSample.radio.rsrp,
      transport: 'MOBILE',
      dataSubscriptionId: sim.subscriptionId,
    });
  }

  const sessionEvents: SessionEvent[] = measurements
    .filter((_, idx) => idx % 5 === 2)
    .map((m, idx) => ({
      id: params.id * 1000 + idx + 1,
      campaignId: params.id,
      type: idx === 1 ? 'PAUSE' : 'LIGHT_PROBE',
      timestampMs: m.timestampMs,
      durationMs: idx === 1 ? 8000 : 250,
      pingMs: m.radio.rsrp < -105 ? 185 : 22 + idx * 4,
      jitterMs: m.radio.rsrp < -105 ? 28 : 3.2 + idx,
      success: true,
      latitude: m.gps.latitude,
      longitude: m.gps.longitude,
      technology: m.radio.technology,
      cellId: m.radio.cellId,
    }));

  const campaign: Campaign = {
    id: params.id,
    name: params.name,
    preset: params.preset,
    startTime: params.startTime,
    endTime: params.startTime + params.sampleCount * params.stepIntervalMs,
    measurementCount: measurements.length,
    distanceMeters: Math.round(totalDist),
    isActive: false,
    speedTestStatus: 'SUCCESS',
    speedDownloadMbps: stMetrics.downloadMbps,
    speedUploadMbps: stMetrics.uploadMbps,
    speedPingMs: stMetrics.pingMs,
    speedJitterMs: stMetrics.jitterMs,
    speedTestTimestampMs: midSample.timestampMs,
    speedTestLat: midSample.gps.latitude,
    speedTestLon: midSample.gps.longitude,
    alertThresholdRsrp: params.alertThresholdRsrp,
  };

  return { campaign, measurements, speedTests, sessionEvents };
}

export function createInitialSeedData(): SeedDatabaseState {
  const baseTime = 1730530000000;

  // Campaign 1 & Campaign 2 share the URBAN_ROUTE corridor so users can immediately test Campaign Comparison!
  const c1 = buildSeedCampaign({
    id: 1,
    name: 'Sukhumvit_5G_SA_Post_Optimization',
    preset: 'URBAN_ROUTE',
    startTime: baseTime + 3600_000 * 4,
    sampleCount: 28,
    stepIntervalMs: 2000,
    simIndex: 0,
    rsrpOffsetDb: 4, // Post-optimization (+4 dB stronger)
    alertThresholdRsrp: -110,
  });

  const c2 = buildSeedCampaign({
    id: 2,
    name: 'Sukhumvit_5G_Baseline_Pre_Tilt',
    preset: 'URBAN_ROUTE',
    startTime: baseTime,
    sampleCount: 28,
    stepIntervalMs: 2000,
    simIndex: 0,
    rsrpOffsetDb: -5, // Pre-optimization baseline (-5 dB weaker)
    alertThresholdRsrp: -110,
  });

  const c3 = buildSeedCampaign({
    id: 3,
    name: 'Rama9_Expressway_Mobility_Test',
    preset: 'HIGHWAY',
    startTime: baseTime + 3600_000 * 8,
    sampleCount: 24,
    stepIntervalMs: 2000,
    simIndex: 1,
    rsrpOffsetDb: 0,
    alertThresholdRsrp: -108,
  });

  const c4 = buildSeedCampaign({
    id: 4,
    name: 'Siam_Paragon_Indoor_DAS_Audit',
    preset: 'INDOOR',
    startTime: baseTime + 3600_000 * 12,
    sampleCount: 20,
    stepIntervalMs: 5000,
    simIndex: 0,
    rsrpOffsetDb: -2,
    alertThresholdRsrp: -112,
  });

  return {
    campaigns: [c1.campaign, c3.campaign, c4.campaign, c2.campaign],
    measurements: [
      ...c1.measurements,
      ...c2.measurements,
      ...c3.measurements,
      ...c4.measurements,
    ],
    speedTests: [
      ...c1.speedTests,
      ...c2.speedTests,
      ...c3.speedTests,
      ...c4.speedTests,
    ],
    sessionEvents: [
      ...c1.sessionEvents,
      ...c2.sessionEvents,
      ...c3.sessionEvents,
      ...c4.sessionEvents,
    ],
  };
}

