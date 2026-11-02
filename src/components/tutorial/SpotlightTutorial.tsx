import React, { useState } from 'react';
import { ArrowRight, Check, Compass, FolderKanban, Gauge, LayoutGrid, Play, X } from 'lucide-react';

export interface SpotlightTutorialProps {
  isOpen: boolean;
  onClose: () => void;
  onNavigateTab: (tab: 'DASHBOARD' | 'MAP' | 'CAMPAIGNS' | 'ANALYTICS') => void;
  onOpenWidgets: () => void;
}

interface StepItem {
  key: string;
  title: string;
  body: string;
  tab?: 'DASHBOARD' | 'MAP' | 'CAMPAIGNS' | 'ANALYTICS';
}

const STEPS: StepItem[] = [
  {
    key: 'start',
    title: '1. Start your first campaign',
    body: 'Choose a route preset (Site Audit, Urban Route, Highway, or Indoor), configure weak-signal RSRP alerts, and start recording 5G/LTE telemetry.',
    tab: 'DASHBOARD',
  },
  {
    key: 'gauge',
    title: '2. Real-time RF Signal Gauge',
    body: 'Monitor live RSRP, RSRQ, SINR, RSSI, CSI-RSRP, CQI, Timing Advance, 5G NR SA/NSA Carrier Aggregation, and neighboring cells.',
    tab: 'DASHBOARD',
  },
  {
    key: 'map',
    title: '3. Follow your route on the live map',
    body: 'Visualize geolocated measurements colored by RSRP, RSRQ, SINR, Technology, or Speed across 4 palettes, with Handover and Missed Handover overlays.',
    tab: 'MAP',
  },
  {
    key: 'campaigns',
    title: '4. Manage & export your campaigns',
    body: 'Search recorded drives, view RSRP sparklines, and export logs to standard CSV, OpenGIS KML 2.2, GeoJSON, or printable PDF engineering reports.',
    tab: 'CAMPAIGNS',
  },
  {
    key: 'widgets',
    title: '5. 8-Tab Diagnostics & Home Widgets',
    body: 'Inspect CDF curves, band usage, automated 0–100 Route Diagnosis, Campaign Comparison, and preview the live Signal & Campaign Home Widgets.',
    tab: 'ANALYTICS',
  },
];

export function SpotlightTutorial(props: SpotlightTutorialProps) {
  const { isOpen, onClose, onNavigateTab, onOpenWidgets } = props;
  const [stepIdx, setStepIdx] = useState(0);

  if (!isOpen) return null;

  const step = STEPS[stepIdx];
  const isLast = stepIdx === STEPS.length - 1;

  const handleNext = () => {
    if (isLast) {
      setStepIdx(0);
      onClose();
      return;
    }
    const nextIdx = stepIdx + 1;
    setStepIdx(nextIdx);
    const nextStep = STEPS[nextIdx];
    if (nextStep.tab) {
      onNavigateTab(nextStep.tab);
    }
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Interactive Guide"
      className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/65 p-4"
    >
      <div className="bg-surface border border-accent/60 rounded-2xl max-w-md w-full p-5 space-y-4 shadow-2xl">
        <div className="flex items-center justify-between">
          <span className="text-xs font-mono uppercase tracking-wider px-2.5 py-0.5 rounded bg-accent/15 text-accent font-semibold">
            Spotlight Step {stepIdx + 1} / {STEPS.length}
          </span>
          <button
            type="button"
            onClick={() => {
              setStepIdx(0);
              onClose();
            }}
            className="text-xs text-text-muted hover:text-text flex items-center gap-1"
          >
            Skip <X className="w-3.5 h-3.5" />
          </button>
        </div>

        <div className="space-y-1.5">
          <h3 className="text-base font-bold text-text flex items-center gap-2">
            {stepIdx === 0 && <Play className="w-4 h-4 text-accent" />}
            {stepIdx === 1 && <Gauge className="w-4 h-4 text-accent" />}
            {stepIdx === 2 && <Compass className="w-4 h-4 text-accent" />}
            {stepIdx === 3 && <FolderKanban className="w-4 h-4 text-accent" />}
            {stepIdx === 4 && <LayoutGrid className="w-4 h-4 text-accent" />}
            {step.title}
          </h3>
          <p className="text-xs text-text-muted leading-relaxed">{step.body}</p>
        </div>

        <div className="flex items-center justify-between pt-2 border-t border-border">
          <div className="flex gap-1.5">
            {STEPS.map((s, idx) => (
              <span
                key={s.key}
                className={`h-1.5 rounded-full transition-all ${
                  idx === stepIdx ? 'w-6 bg-accent' : 'w-2 bg-border'
                }`}
              />
            ))}
          </div>

          <div className="flex items-center gap-2">
            {stepIdx === 4 && (
              <button
                type="button"
                onClick={() => {
                  onClose();
                  onOpenWidgets();
                }}
                className="px-3 py-1.5 rounded-lg bg-surface-elevated border border-border text-xs font-medium text-text hover:border-accent"
              >
                Open Widgets
              </button>
            )}
            <button
              type="button"
              onClick={handleNext}
              className="px-4 py-1.5 rounded-lg bg-accent text-on-accent text-xs font-bold flex items-center gap-1.5"
            >
              {isLast ? (
                <>
                  Done <Check className="w-3.5 h-3.5" />
                </>
              ) : (
                <>
                  Next <ArrowRight className="w-3.5 h-3.5" />
                </>
              )}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

