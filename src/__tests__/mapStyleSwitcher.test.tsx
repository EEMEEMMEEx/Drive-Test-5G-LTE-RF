import React from 'react';
import { beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import App, { MAP_TYPE_STORAGE_KEY } from '../App';
import { RouteMapView } from '../components/map/RouteMapView';
import { DEFAULT_MAP_TYPE, sanitizeMapType, type MapOptions } from '../types/drivetest';
import { TRANSLATIONS } from '../i18n/translations';

describe('Map Style Switcher & Options Removal', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  const baseOptions: MapOptions = {
    colorMode: 'BY_RSRP',
    mapType: 'GOOGLE_HYBRID',
    signalPalette: 'CLASSIC',
    showCurrentCell: true,
    showNeighborCells: false,
    showHandovers: true,
    showMissedHandovers: true,
    showSim2Layer: true,
    dotSize: 8,
    rsrpGood: -90,
    rsrpPoor: -110,
    followMyPosition: true,
    headingUp: false,
  };

  it('renders only remaining map styles in radio group without "Dark Map" or "OSM"', () => {
    render(
      <RouteMapView
        campaigns={[]}
        selectedCampaignId={1}
        onSelectCampaign={() => {}}
        measurements={[]}
        isRunning={false}
        currentLocation={null}
        options={baseOptions}
        onUpdateOptions={() => {}}
        onNavigateToAnalytics={() => {}}
        t={TRANSLATIONS.en}
      />
    );

    // Verify "Dark Map" and "OSM" are completely removed
    expect(screen.queryByRole('radio', { name: /Dark Map/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('radio', { name: /OSM/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Toggle Dark Map/i })).not.toBeInTheDocument();

    // Verify remaining options in the radiogroup
    const radioGroup = screen.getByRole('radiogroup', { name: /Map Layer Engine/i });
    expect(radioGroup).toBeInTheDocument();

    const hybridRadio = screen.getByRole('radio', { name: /Google Hybrid/i });
    const satRadio = screen.getByRole('radio', { name: /Google Sat/i });
    const roadRadio = screen.getByRole('radio', { name: /Google Road/i });

    expect(hybridRadio).toBeInTheDocument();
    expect(satRadio).toBeInTheDocument();
    expect(roadRadio).toBeInTheDocument();

    // Default option Google Hybrid is selected
    expect(hybridRadio).toHaveAttribute('aria-checked', 'true');
    expect(satRadio).toHaveAttribute('aria-checked', 'false');
    expect(roadRadio).toHaveAttribute('aria-checked', 'false');
  });

  it('falls back to default Google Hybrid when options.mapType is set to removed options', () => {
    const updatedOptionsList: MapOptions[] = [];
    render(
      <RouteMapView
        campaigns={[]}
        selectedCampaignId={1}
        onSelectCampaign={() => {}}
        measurements={[]}
        isRunning={false}
        currentLocation={null}
        // simulate legacy/removed option passed from persisted storage
        options={{ ...baseOptions, mapType: 'CARTO_DARK' as any }}
        onUpdateOptions={(next) => updatedOptionsList.push(next)}
        onNavigateToAnalytics={() => {}}
        t={TRANSLATIONS.en}
      />
    );

    // Active selection should fall back to Google Hybrid
    const hybridRadio = screen.getByRole('radio', { name: /Google Hybrid/i });
    expect(hybridRadio).toHaveAttribute('aria-checked', 'true');
    expect(screen.queryByRole('radio', { name: /Dark Map/i })).not.toBeInTheDocument();

    // Verification of fallback sanitizer function
    expect(sanitizeMapType('CARTO_DARK')).toBe(DEFAULT_MAP_TYPE);
    expect(sanitizeMapType('NORMAL')).toBe(DEFAULT_MAP_TYPE);
    expect(sanitizeMapType('UNKNOWN_STYLE')).toBe(DEFAULT_MAP_TYPE);
    expect(sanitizeMapType('GOOGLE_SATELLITE')).toBe('GOOGLE_SATELLITE');
  });

  it('handles localStorage containing removed map styles and falls back without breaking in App', () => {
    // Simulate legacy saved preference in localStorage
    localStorage.setItem(MAP_TYPE_STORAGE_KEY, 'CARTO_DARK');
    localStorage.setItem('mapType', 'CARTO_DARK');
    localStorage.setItem('mapOptions', JSON.stringify({ mapType: 'CARTO_DARK' }));

    render(<App />);

    // Navigate to Route Map
    fireEvent.click(screen.getByRole('button', { name: /Route Map/i }));

    // Should NOT crash and should have Google Hybrid selected
    const hybridRadio = screen.getByRole('radio', { name: /Google Hybrid/i });
    expect(hybridRadio).toBeInTheDocument();
    expect(hybridRadio).toHaveAttribute('aria-checked', 'true');

    // Neither Dark Map nor OSM should exist
    expect(screen.queryByRole('radio', { name: /Dark Map/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('radio', { name: /OSM/i })).not.toBeInTheDocument();
  });

  it('restores valid saved map style from localStorage', () => {
    localStorage.setItem(MAP_TYPE_STORAGE_KEY, 'GOOGLE_SATELLITE');

    render(<App />);

    fireEvent.click(screen.getByRole('button', { name: /Route Map/i }));

    const satRadio = screen.getByRole('radio', { name: /Google Sat/i });
    expect(satRadio).toHaveAttribute('aria-checked', 'true');
  });
});
