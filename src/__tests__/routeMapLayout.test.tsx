import React from 'react';
import { beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { RouteMapView } from '../components/map/RouteMapView';
import { TRANSLATIONS } from '../i18n/translations';
import { DEFAULT_SIM_CARDS, generateSimulatedMeasurement } from '../services/driveSimulator';
import type { MapOptions, Measurement } from '../types/drivetest';

describe('Route Map Responsive Layout & Overlay Fixes', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  const mockOptions: MapOptions = {
    colorMode: 'BY_RSRP',
    mapType: 'GOOGLE_HYBRID',
    signalPalette: 'CLASSIC',
    showCurrentCell: true,
    showNeighborCells: false,
    showHandovers: true,
    showMissedHandovers: true,
    dotSize: 8,
    rsrpGood: -90,
    rsrpPoor: -110,
    followMyPosition: true,
    headingUp: false,
  };

  const sim = DEFAULT_SIM_CARDS[0];
  const mockMeasurements: Measurement[] = [
    generateSimulatedMeasurement({
      campaignId: 1,
      stepIndex: 1,
      preset: 'URBAN_ROUTE',
      sim,
      timestampMs: Date.now() - 5000,
      ratMode: 'AUTO',
    }),
    generateSimulatedMeasurement({
      campaignId: 1,
      stepIndex: 2,
      preset: 'URBAN_ROUTE',
      sim,
      timestampMs: Date.now(),
      ratMode: 'AUTO',
    }),
  ];

  it('renders stats overlay with Samples, Avg RSRP, and Handovers without zoom button interference', () => {
    const { container } = render(
      <RouteMapView
        campaigns={[]}
        selectedCampaignId={1}
        onSelectCampaign={() => {}}
        measurements={mockMeasurements}
        isRunning={false}
        currentLocation={null}
        options={mockOptions}
        onUpdateOptions={() => {}}
        onNavigateToAnalytics={() => {}}
        t={TRANSLATIONS.en}
      />
    );

    // Verify stats overlay
    const overlay = screen.getByTestId('route-stats-overlay');
    expect(overlay).toBeInTheDocument();
    expect(overlay).toHaveTextContent(/Samples:/i);
    expect(overlay).toHaveTextContent(/2/);
    expect(overlay).toHaveTextContent(/Avg RSRP:/i);
    expect(overlay).toHaveTextContent(/Handovers:/i);

    // Verify overlay classes for responsive wrapping
    expect(overlay.className).toContain('flex-wrap');
    expect(overlay.className).toContain('z-[400]');

    // Verify map canvas container has safe-area styling class
    const mapCard = container.querySelector('.rf-map-canvas');
    expect(mapCard).toBeInTheDocument();

    // Verify Point Telemetry Inspector rendered
    expect(screen.getByText('Point Telemetry Inspector')).toBeInTheDocument();

    // Verify touch-friendly stepper buttons exist with min-h-[44px]
    const prevBtn = screen.getByRole('button', { name: /Previous sample/i });
    const nextBtn = screen.getByRole('button', { name: /Next sample/i });
    expect(prevBtn).toBeInTheDocument();
    expect(nextBtn).toBeInTheDocument();
    expect(prevBtn.className).toContain('min-h-[44px]');
    expect(nextBtn.className).toContain('min-h-[44px]');
  });
});
