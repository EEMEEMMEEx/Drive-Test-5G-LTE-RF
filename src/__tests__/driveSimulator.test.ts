import { describe, expect, it } from 'vitest';
import {
  DEFAULT_SIM_CARDS,
  generateSimulatedMeasurement,
  runSimulatedSpeedTestStep,
  generateLightProbeSample,
  getCadenceIntervalMs,
} from '../services/driveSimulator';
import { createInitialSeedData } from '../db/seedData';

describe('driveSimulator & seedData', () => {
  it('maps measurement cadence to exact APK intervals (Eco 5s, Standard 2s, Precise 1s)', () => {
    expect(getCadenceIntervalMs('ECO')).toBe(5000);
    expect(getCadenceIntervalMs('STANDARD')).toBe(2000);
    expect(getCadenceIntervalMs('PRECISE')).toBe(1000);
  });

  it('generates realistic 5G/LTE RF telemetry with carrier aggregation and neighbor cells', () => {
    const sim = DEFAULT_SIM_CARDS[0];
    const m = generateSimulatedMeasurement({
      campaignId: 1,
      stepIndex: 5,
      preset: 'URBAN_ROUTE',
      sim,
      timestampMs: 1730540010000,
    });

    expect(m.campaignId).toBe(1);
    expect(m.radio.rsrp).toBeGreaterThanOrEqual(-135);
    expect(m.radio.rsrp).toBeLessThanOrEqual(-55);
    expect(m.radio.neighborCells.length).toBeGreaterThanOrEqual(2);
    expect(m.gps.isValid).toBe(true);
  });

  it('generates speed test steps and light probe samples', () => {
    const step = runSimulatedSpeedTestStep('NR_5G', -82);
    expect(step.downloadMbps).toBeGreaterThan(50);
    expect(step.uploadMbps).toBeGreaterThan(10);
    expect(step.pingMs).toBeGreaterThan(0);

    const probe = generateLightProbeSample('NR_5G', -82, 1730540010000);
    expect(probe.pingMs).toBeGreaterThan(0);
    expect(probe.success).toBe(true);
  });

  it('creates initial seed database with multiple overlapping campaigns for comparison', () => {
    const seed = createInitialSeedData();
    expect(seed.campaigns.length).toBeGreaterThanOrEqual(3);
    expect(seed.measurements.length).toBeGreaterThanOrEqual(60);
    expect(seed.speedTests.length).toBeGreaterThanOrEqual(3);
    expect(seed.sessionEvents.length).toBeGreaterThanOrEqual(4);
  });
});

