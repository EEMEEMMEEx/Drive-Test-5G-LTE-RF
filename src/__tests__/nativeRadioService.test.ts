import { describe, expect, it } from 'vitest';
import {
  convertNativeSnapshotToMeasurement,
  isNativeAndroid,
  type NativeRadioMetrics,
} from '../services/nativeRadioService';

describe('nativeRadioService', () => {
  it('detects native Android environment safely without throwing', () => {
    // In node/jsdom test environment, it should evaluate to false
    const isAndroid = isNativeAndroid();
    expect(typeof isAndroid).toBe('boolean');
    expect(isAndroid).toBe(false);
  });

  it('converts a 5G NR native snapshot to a standard Measurement structure', () => {
    const mockSnapshot: NativeRadioMetrics = {
      timestamp: 1729000000000,
      gps: {
        latitude: 13.736717,
        longitude: 100.523186,
        altitude: 18.5,
        speedKmh: 45.0, // 45 km/h = 12.5 m/s
        bearing: 180.0,
        accuracy: 4.2,
      },
      servingCell: {
        rat: '5G NR',
        rsrp: -88,
        rsrq: -10,
        sinr: 22,
        csiRsrp: -91,
        pci: 412,
        nci: 1084201,
        tac: 54321,
        nrarfcn: 640000,
        band: 'n78 (3500 MHz)',
        registered: true,
      },
      neighborCells: [
        {
          rat: '5G NR',
          rsrp: -95,
          rsrq: -12,
          sinr: 14,
          pci: 413,
          tac: 54321,
          nrarfcn: 640000,
          band: 'n78',
        },
      ],
    };

    const measurement = convertNativeSnapshotToMeasurement(mockSnapshot, 0, 101);

    expect(measurement.campaignId).toBe(101);
    expect(measurement.timestampMs).toBe(1729000000000);
    expect(measurement.radio.technology).toBe('NR_5G');
    expect(measurement.radio.nrStatus).toBe('SA');
    expect(measurement.radio.rsrp).toBe(-88);
    expect(measurement.radio.rsrq).toBe(-10);
    expect(measurement.radio.sinr).toBe(22);
    expect(measurement.radio.csiRsrp).toBe(-91);
    expect(measurement.radio.pci).toBe(412);
    expect(measurement.radio.cellId).toBe(1084201);
    expect(measurement.radio.lac).toBe(54321);
    expect(measurement.radio.earfcn).toBe(640000);
    expect(measurement.radio.neighborCells).toHaveLength(1);
    expect(measurement.radio.neighborCells[0].rsrp).toBe(-95);
    expect(measurement.radio.neighborCells[0].pci).toBe(413);

    // GPS validation
    expect(measurement.gps.latitude).toBeCloseTo(13.736717);
    expect(measurement.gps.longitude).toBeCloseTo(100.523186);
    expect(measurement.gps.altitude).toBe(18.5);
    expect(measurement.gps.speed).toBeCloseTo(12.5); // 45 / 3.6
    expect(measurement.gps.bearing).toBe(180);
    expect(measurement.gps.accuracy).toBe(4.2);
    expect(measurement.gps.isValid).toBe(true);
  });

  it('converts an LTE native snapshot to a standard Measurement structure', () => {
    const mockSnapshot: NativeRadioMetrics = {
      timestamp: 1729000010000,
      gps: {
        latitude: 13.750000,
        longitude: 100.500000,
        altitude: 12.0,
        speedKmh: 36.0, // 10 m/s
        bearing: 90.0,
        accuracy: 5.0,
      },
      servingCell: {
        rat: 'LTE',
        rsrp: -102,
        rsrq: -13,
        sinr: 8,
        cqi: 9,
        ta: 3,
        pci: 198,
        ci: 54312,
        tac: 12345,
        earfcn: 1650,
        band: 'B3 (1800 MHz)',
        registered: true,
      },
      neighborCells: [],
    };

    const measurement = convertNativeSnapshotToMeasurement(mockSnapshot, 1, 102);

    expect(measurement.radio.technology).toBe('LTE_4G');
    expect(measurement.radio.nrStatus).toBe('NONE');
    expect(measurement.radio.rsrp).toBe(-102);
    expect(measurement.radio.sinr).toBe(8);
    expect(measurement.radio.cqi).toBe(9);
    expect(measurement.radio.timingAdvance).toBe(3);
    expect(measurement.radio.pci).toBe(198);
    expect(measurement.radio.cellId).toBe(54312);
    expect(measurement.gps.speed).toBeCloseTo(10.0);
  });
});
