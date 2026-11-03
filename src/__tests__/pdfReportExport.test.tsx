import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import {
  RfSiteAcceptanceReportModal,
  getInitialSnapshotEnabled,
  getInitialSnapshotMapType,
} from '../components/reports/RfSiteAcceptanceReportModal';
import {
  generateAuditReportData,
  generateStandaloneHtmlReport,
  getTileUrlForType,
} from '../services/reportGeneratorService';
import { BLANK_RADIO_INFO } from '../services/driveSimulator';
import type { Campaign, Measurement } from '../types/drivetest';

describe('PDF Site Acceptance Report & Route Map Snapshot', () => {
  const mockCampaign: Campaign = {
    id: 101,
    name: 'Sukhumvit_5G_SA_Acceptance',
    preset: 'URBAN_ROUTE',
    startTime: 1715000000000,
    endTime: 1715003600000,
    measurementCount: 3,
    distanceMeters: 4500,
    isActive: false,
    speedTestStatus: 'SUCCESS',
    speedDownloadMbps: 185.4,
    speedUploadMbps: 42.1,
    speedPingMs: 14,
    speedJitterMs: 2,
    speedTestTimestampMs: 1715002000000,
    speedTestLat: 13.7367,
    speedTestLon: 100.5605,
    alertThresholdRsrp: -105,
    ratMode: 'AUTO',
  };

  const mockMeasurements: Measurement[] = [
    {
      id: 1,
      campaignId: 101,
      timestampMs: 1715000000000,
      gps: {
        latitude: 13.736,
        longitude: 100.56,
        altitude: 12,
        speed: 10,
        bearing: 90,
        accuracy: 3,
        timestampMs: 1715000000000,
        isValid: true,
      },
      radio: {
        ...BLANK_RADIO_INFO,
        technology: 'NR_5G',
        nrStatus: 'SA',
        connectionState: 'CONNECTED',
        operatorName: 'AIS 5G',
        mcc: '520',
        mnc: '01',
        lac: 1001,
        pci: 420,
        cellId: 123456,
        earfcn: 632000,
        band: 78,
        rsrp: -82,
        rsrq: -11,
        sinr: 18.5,
        rssi: -65,
        cqi: 15,
        timingAdvance: 1,
        simSlot: 0,
      },
    },
    {
      id: 2,
      campaignId: 101,
      timestampMs: 1715001000000,
      gps: {
        latitude: 13.738,
        longitude: 100.563,
        altitude: 12,
        speed: 11,
        bearing: 90,
        accuracy: 3,
        timestampMs: 1715001000000,
        isValid: true,
      },
      radio: {
        ...BLANK_RADIO_INFO,
        technology: 'NR_5G',
        nrStatus: 'SA',
        connectionState: 'CONNECTED',
        operatorName: 'AIS 5G',
        mcc: '520',
        mnc: '01',
        lac: 1001,
        pci: 420,
        cellId: 123456,
        earfcn: 632000,
        band: 78,
        rsrp: -98,
        rsrq: -13,
        sinr: 6.2,
        rssi: -78,
        cqi: 10,
        timingAdvance: 2,
        simSlot: 0,
      },
    },
    {
      id: 3,
      campaignId: 101,
      timestampMs: 1715002000000,
      gps: {
        latitude: 13.74,
        longitude: 100.565,
        altitude: 12,
        speed: 10,
        bearing: 90,
        accuracy: 3,
        timestampMs: 1715002000000,
        isValid: true,
      },
      radio: {
        ...BLANK_RADIO_INFO,
        technology: 'NR_5G',
        nrStatus: 'SA',
        connectionState: 'CONNECTED',
        operatorName: 'AIS 5G',
        mcc: '520',
        mnc: '01',
        lac: 1001,
        pci: 421,
        cellId: 123457,
        earfcn: 632000,
        band: 78,
        rsrp: -108,
        rsrq: -16,
        sinr: 1.0,
        rssi: -85,
        cqi: 6,
        timingAdvance: 3,
        simSlot: 0,
      },
    },
  ];

  beforeEach(() => {
    if (typeof localStorage !== 'undefined') {
      localStorage.clear();
    }
  });

  it('provides sensible defaults: snapshot enabled and Hybrid map type preselected', () => {
    expect(getInitialSnapshotEnabled()).toBe(true);
    expect(getInitialSnapshotMapType()).toBe('GOOGLE_HYBRID');
  });

  it('renders modal with Route Map Snapshot section by default', () => {
    const handleClose = vi.fn();
    render(
      <RfSiteAcceptanceReportModal
        campaign={mockCampaign}
        measurements={mockMeasurements}
        onClose={handleClose}
      />
    );

    // Verify modal header & metadata
    expect(screen.getByText('Automated RF Site Acceptance Report')).toBeInTheDocument();
    expect(screen.getByText(/A4 Executive PDF/i)).toBeInTheDocument();

    // Verify snapshot toggle is checked by default
    const checkbox = screen.getByLabelText(/Include Route Map Snapshot in PDF report/i);
    expect(checkbox).toBeChecked();
    expect(screen.getByText('ENABLED')).toBeInTheDocument();

    // Verify map snapshot section in printable canvas
    expect(
      screen.getByText(/Route Map Snapshot & Spatial RF Distribution/i)
    ).toBeInTheDocument();

    // Verify map style chips
    expect(screen.getByRole('button', { name: /Snapshot map style Hybrid/i })).toHaveAttribute(
      'aria-pressed',
      'true'
    );
  });

  it('toggles snapshot OFF/ON, updates localStorage, and hides snapshot from preview', () => {
    render(
      <RfSiteAcceptanceReportModal
        campaign={mockCampaign}
        measurements={mockMeasurements}
        onClose={() => {}}
      />
    );

    const checkbox = screen.getByLabelText(/Include Route Map Snapshot in PDF report/i);
    expect(checkbox).toBeChecked();

    // Toggle snapshot OFF
    fireEvent.click(checkbox);
    expect(checkbox).not.toBeChecked();
    expect(screen.getByText('DISABLED')).toBeInTheDocument();

    // Snapshot section should no longer be rendered
    expect(
      screen.queryByText(/Route Map Snapshot & Spatial RF Distribution/i)
    ).not.toBeInTheDocument();

    // Map style chips should be disabled
    const hybridBtn = screen.getByRole('button', { name: /Snapshot map style Hybrid/i });
    expect(hybridBtn).toBeDisabled();

    // Verify persistence in localStorage
    expect(localStorage.getItem('rf_site_report_include_snapshot')).toBe('false');

    // Toggle back ON
    fireEvent.click(checkbox);
    expect(checkbox).toBeChecked();
    expect(screen.getByText('ENABLED')).toBeInTheDocument();
    expect(
      screen.getByText(/Route Map Snapshot & Spatial RF Distribution/i)
    ).toBeInTheDocument();
  });

  it('allows switching snapshot map type and updates state and localStorage', () => {
    render(
      <RfSiteAcceptanceReportModal
        campaign={mockCampaign}
        measurements={mockMeasurements}
        onClose={() => {}}
      />
    );

    const satBtn = screen.getByRole('button', { name: /Snapshot map style Satellite/i });
    fireEvent.click(satBtn);

    expect(satBtn).toHaveAttribute('aria-pressed', 'true');
    expect(localStorage.getItem('rf_site_report_snapshot_map_type')).toBe('GOOGLE_SATELLITE');

    const terrainBtn = screen.getByRole('button', { name: /Snapshot map style Terrain/i });
    fireEvent.click(terrainBtn);

    expect(terrainBtn).toHaveAttribute('aria-pressed', 'true');
    expect(localStorage.getItem('rf_site_report_snapshot_map_type')).toBe('GOOGLE_TERRAIN');
  });

  it('generates standalone HTML report with snapshot included and excluded', () => {
    const data = generateAuditReportData(mockCampaign, mockMeasurements);
    expect(data.routePoints.length).toBeGreaterThan(0);

    // With snapshot (default)
    const htmlWithMap = generateStandaloneHtmlReport(data, {
      includeSnapshot: true,
      snapshotMapType: 'GOOGLE_HYBRID',
    });
    expect(htmlWithMap).toContain('Route Map & Spatial RF Coverage Snapshot');
    expect(htmlWithMap).toContain('Snapshot Engine: <strong>Hybrid</strong>');

    // Without snapshot
    const htmlWithoutMap = generateStandaloneHtmlReport(data, {
      includeSnapshot: false,
    });
    expect(htmlWithoutMap).not.toContain('Route Map & Spatial RF Coverage Snapshot');
  });

  it('maps map types to valid tile configurations', () => {
    expect(getTileUrlForType('GOOGLE_HYBRID').label).toBe('Hybrid');
    expect(getTileUrlForType('GOOGLE_ROADMAP').label).toBe('Normal (Roadmap)');
    expect(getTileUrlForType('GOOGLE_SATELLITE').label).toBe('Satellite');
    expect(getTileUrlForType('GOOGLE_TERRAIN').label).toBe('Terrain');
  });
});
