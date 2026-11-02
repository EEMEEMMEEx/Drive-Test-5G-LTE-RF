import { describe, expect, it } from 'vitest';
import {
  generateMeasurementsCsv,
  generateCampaignKml,
  generateCampaignGeoJson,
  parseImportedMeasurementsCsv,
} from '../services/exportService';
import type { Campaign, Measurement } from '../types/drivetest';

const mockCampaign: Campaign = {
  id: 1,
  name: 'Bangkok_5G_Urban_Audit',
  preset: 'URBAN_ROUTE',
  startTime: 1730540000000,
  endTime: 1730540600000,
  measurementCount: 2,
  distanceMeters: 450,
  isActive: false,
  speedTestStatus: 'SUCCESS',
  speedDownloadMbps: 412.5,
  speedUploadMbps: 68.2,
  speedPingMs: 14,
  speedJitterMs: 2.3,
  speedTestTimestampMs: 1730540300000,
  speedTestLat: 13.7563,
  speedTestLon: 100.5018,
  alertThresholdRsrp: -110,
};

const mockMeasurements: Measurement[] = [
  {
    id: 1,
    campaignId: 1,
    timestampMs: 1730540000000,
    radio: {
      technology: 'NR_5G',
      operatorName: 'AIS 5G',
      mcc: '520',
      mnc: '03',
      simSlot: 0,
      cellId: 501201,
      lac: 2100,
      pci: 312,
      band: 78,
      earfcn: 632000,
      dlFrequencyKhz: 3500000,
      dlBandwidthKhz: 100000,
      rssi: -62,
      rsrp: -81,
      rsrq: -8,
      sinr: 21.5,
      signalLevel: 4,
      cqi: 14,
      timingAdvance: 1,
      csiRsrp: -80,
      csiRsrq: -8,
      csiSinr: 22,
      nrStatus: 'SA',
      aggregatedBands: [],
      aggregatedNrBands: [78],
      isRegistered: true,
      connectionState: 'CONNECTED',
      neighborCells: [],
    },
    gps: {
      latitude: 13.7563,
      longitude: 100.5018,
      altitude: 12,
      speed: 8.5,
      accuracy: 3.5,
      bearing: 180,
      timestampMs: 1730540000000,
      isValid: true,
    },
  },
];

describe('exportService', () => {
  it('generates CSV matching exact APK column header format and parses it back', () => {
    const csv = generateMeasurementsCsv(mockMeasurements);
    const lines = csv.trim().split('\n');
    expect(lines[0]).toBe(
      'timestamp,latitude,longitude,altitude,speed_ms,accuracy_m,bearing,gps_valid,technology,operator,mcc,mnc,cell_id,lac_tac,pci,band,earfcn,rssi_dbm,rsrp_dbm,rsrq_db,sinr_db,registered,connection_state'
    );
    expect(lines.length).toBe(2);
    expect(lines[1]).toContain('NR_5G');
    expect(lines[1]).toContain('501201');
    expect(lines[1]).toContain('-81');

    const parsed = parseImportedMeasurementsCsv(csv, 99);
    expect(parsed.length).toBe(1);
    expect(parsed[0].campaignId).toBe(99);
    expect(parsed[0].radio.rsrp).toBe(-81);
    expect(parsed[0].radio.pci).toBe(312);
    expect(parsed[0].gps.latitude).toBeCloseTo(13.7563, 4);
  });

  it('generates OpenGIS KML 2.2 document with track LineString and styled Placemarks', () => {
    const kml = generateCampaignKml(mockCampaign, mockMeasurements);
    expect(kml).toContain('<?xml version="1.0" encoding="UTF-8"?>');
    expect(kml).toContain('<kml xmlns="http://www.opengis.net/kml/2.2">');
    expect(kml).toContain('<Placemark><name>Track</name><LineString>');
    expect(kml).toContain('RSRP: -81 dBm');
  });

  it('generates valid GeoJSON FeatureCollection', () => {
    const geojsonStr = generateCampaignGeoJson(mockCampaign, mockMeasurements);
    const parsed = JSON.parse(geojsonStr);
    expect(parsed.type).toBe('FeatureCollection');
    expect(parsed.features.length).toBeGreaterThanOrEqual(1);
    expect(parsed.features[0].properties.rsrp).toBe(-81);
  });
});

