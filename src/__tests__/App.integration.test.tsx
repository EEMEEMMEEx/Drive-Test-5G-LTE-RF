import React from 'react';
import { beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import App, { setTourCompleted } from '../App';

describe('App End-to-End UI Integration', () => {
  beforeEach(() => {
    if (typeof localStorage !== 'undefined') {
      localStorage.clear();
    }
  });

  it('renders Live Monitor in Standby by default (no fake signal) and starts/stops a campaign', () => {
    render(<App />);

    // Verify header and clean initial Standby Live Monitor screen
    expect(screen.getByText('RF Field Suite')).toBeInTheDocument();
    expect(screen.getByText('RF Signal Telemetry')).toBeInTheDocument();
    expect(screen.getByText(/STANDBY \/ NO SIGNAL/i)).toBeInTheDocument();
    expect(screen.getByText(/--- dBm/i)).toBeInTheDocument();

    // Quick start a drive test session
    const quickStartBtn = screen.getByRole('button', { name: /Quick Start/i });
    fireEvent.click(quickStartBtn);

    expect(screen.getByText('RECORDING')).toBeInTheDocument();

    // Pause and resume
    const pauseBtn = screen.getByRole('button', { name: /Pause/i });
    fireEvent.click(pauseBtn);
    expect(screen.getByText('PAUSED')).toBeInTheDocument();

    // Stop session
    const stopButtons = screen.getAllByRole('button', { name: /Stop Session/i });
    fireEvent.click(stopButtons[0]);
    expect(screen.getByText(/Test Finished:/i)).toBeInTheDocument();
  });

  it('navigates to Route Map, Campaigns (starts clean, can load demo routes), and all 8 Analytics tabs', () => {
    render(<App />);

    // Switch to Route Map
    fireEvent.click(screen.getByRole('button', { name: /Route Map/i }));
    expect(screen.getByText(/Map Layer Controls/i)).toBeInTheDocument();

    // Switch to Campaigns - starts clean with No Matching Campaigns
    fireEvent.click(screen.getByRole('button', { name: /Campaigns/i }));
    expect(screen.getByPlaceholderText(/Filter campaigns/i)).toBeInTheDocument();
    expect(screen.getByText(/No Matching Campaigns/i)).toBeInTheDocument();

    // Reset demo routes back
    const resetBtn = screen.getByRole('button', { name: /Reset Demo Routes/i });
    fireEvent.click(resetBtn);
    expect(screen.getByText(/Sukhumvit_5G_SA_Post_Optimization/i)).toBeInTheDocument();

    // Switch to Analytics and test sub-tabs
    fireEvent.click(screen.getAllByRole('button', { name: /^Analytics$/i })[0]);
    expect(screen.getByRole('tab', { name: /CDF/i })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: /CDF/i }));
    fireEvent.click(screen.getByRole('tab', { name: /Network/i }));
    fireEvent.click(screen.getByRole('tab', { name: /Bands/i }));
    fireEvent.click(screen.getByRole('tab', { name: /Trace/i }));
    fireEvent.click(screen.getByRole('tab', { name: /Sites & Mobility/i }));
    fireEvent.click(screen.getByRole('tab', { name: /Diagnosis/i }));
    fireEvent.click(screen.getByRole('tab', { name: /Advanced Correlation/i }));

    expect(screen.getByText(/Export advanced data/i)).toBeInTheDocument();
  });

  it('opens Home Widgets HUD, Interactive Guide, and switches language to Thai', () => {
    render(<App />);

    // Open Widgets HUD
    fireEvent.click(screen.getByRole('button', { name: /Widgets HUD/i }));
    expect(screen.getByText(/GSM Signal Widget/i)).toBeInTheDocument();
    expect(screen.getByText(/GSM Campaign Widget/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Close widgets preview/i }));

    // Open Interactive Guide
    fireEvent.click(screen.getByRole('button', { name: /Interactive Guide/i }));
    expect(screen.getByText(/Spotlight Step 1 \/ 5/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Skip/i }));

    // Switch language to Thai
    const langSelect = screen.getByLabelText('Select interface language');
    fireEvent.change(langSelect, { target: { value: 'th' } });
    expect(screen.getByText('มอนิเตอร์สด')).toBeInTheDocument();
  });
  it('persists tour completed flag and allows replaying tour from Help menu', () => {
    setTourCompleted(false);
    const { unmount } = render(<App />);

    // On first visit, tour should be visible
    expect(screen.getByText(/Spotlight Step 1 \/ 5/i)).toBeInTheDocument();

    // Skip sets flag and closes tour
    fireEvent.click(screen.getByRole('button', { name: /Skip/i }));
    expect(screen.queryByText(/Spotlight Step 1 \/ 5/i)).not.toBeInTheDocument();

    unmount();

    // On subsequent visit, tour should remain closed
    render(<App />);
    expect(screen.queryByText(/Spotlight Step 1 \/ 5/i)).not.toBeInTheDocument();

    // Replay tour button reopens tour regardless of flag
    fireEvent.click(screen.getByRole('button', { name: /Replay tour/i }));
    expect(screen.getByText(/Spotlight Step 1 \/ 5/i)).toBeInTheDocument();

    // Test Next stepper and completion
    fireEvent.click(screen.getByRole('button', { name: /Next/i }));
    expect(screen.getByText(/Spotlight Step 2 \/ 5/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Skip/i }));
    expect(screen.queryByText(/Spotlight Step 2 \/ 5/i)).not.toBeInTheDocument();
  });

  it('supports mobile hamburger menu navigation and bottom dock page switching', () => {
    setTourCompleted(true);
    render(<App />);

    // Mobile hamburger menu toggle
    const menuBtn = screen.getByRole('button', { name: /Toggle navigation menu/i });
    expect(menuBtn).toHaveAttribute('aria-expanded', 'false');

    // Open mobile menu drawer
    fireEvent.click(menuBtn);
    expect(menuBtn).toHaveAttribute('aria-expanded', 'true');
    const drawer = screen.getByRole('dialog', { name: /Mobile Navigation Menu/i });
    expect(drawer).toBeInTheDocument();

    // Verify Navigation block is removed from the drawer
    expect(within(drawer).queryByText(/^Navigation$/i)).not.toBeInTheDocument();
    expect(within(drawer).queryByText(/Real-time RF Telemetry/i)).not.toBeInTheDocument();

    // Verify Tools & Guide are present in drawer
    expect(within(drawer).getByText(/Tools & Guide/i)).toBeInTheDocument();
    expect(within(drawer).getByText(/Widgets HUD/i)).toBeInTheDocument();

    // Test closing with Escape key
    fireEvent.keyDown(window, { key: 'Escape', code: 'Escape' });
    expect(screen.queryByRole('dialog', { name: /Mobile Navigation Menu/i })).not.toBeInTheDocument();

    // Test mobile bottom navigation dock - allows switching between all 4 main pages
    const bottomNav = screen.getByLabelText('Mobile Bottom Navigation');
    expect(bottomNav).toBeInTheDocument();

    // 1. Switch to Route Map via bottom dock
    const mapDockBtn = within(bottomNav).getByRole('button', { name: /^Map$/i });
    fireEvent.click(mapDockBtn);
    expect(screen.getByText(/Map Layer Controls/i)).toBeInTheDocument();

    // 2. Switch to Campaigns via bottom dock
    const campaignsDockBtn = within(bottomNav).getByRole('button', { name: /^Logs$/i });
    fireEvent.click(campaignsDockBtn);
    expect(screen.getByPlaceholderText(/Filter campaigns/i)).toBeInTheDocument();

    // 3. Switch to Analytics via bottom dock
    const analyticsDockBtn = within(bottomNav).getByRole('button', { name: /^Analytics$/i });
    fireEvent.click(analyticsDockBtn);
    expect(screen.getByRole('tab', { name: /CDF/i })).toBeInTheDocument();

    // 4. Return to Live Monitor via bottom dock
    const liveDockBtn = within(bottomNav).getByRole('button', { name: /^Live$/i });
    fireEvent.click(liveDockBtn);
    expect(screen.getByText(/RF Signal Telemetry/i)).toBeInTheDocument();
  });
});
