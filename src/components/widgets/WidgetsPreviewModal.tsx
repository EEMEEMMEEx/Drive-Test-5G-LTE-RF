import React from 'react';
import { Play, Radio, RefreshCw, Signal, Square, X } from 'lucide-react';
import type { RadioInfo } from '../../types/drivetest';

export interface WidgetsPreviewModalProps {
  isOpen: boolean;
  onClose: () => void;
  currentRadio: RadioInfo;
  isRunning: boolean;
  activeCampaignName: string;
  measurementCount: number;
  distanceMeters: number;
  onStartQuickCampaign: () => void;
  onStopCampaign: () => void;
  onRefreshSignal: () => void;
}

export function WidgetsPreviewModal(props: WidgetsPreviewModalProps) {
  const {
    isOpen,
    onClose,
    currentRadio,
    isRunning,
    activeCampaignName,
    measurementCount,
    distanceMeters,
    onStartQuickCampaign,
    onStopCampaign,
    onRefreshSignal,
  } = props;

  if (!isOpen) return null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Home Screen Widgets Preview"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="bg-surface border border-border rounded-2xl max-w-2xl w-full p-6 space-y-5 shadow-2xl">
        <div className="flex items-center justify-between border-b border-border pb-3">
          <div>
            <h2 className="text-base font-bold text-text">
              Home Screen AppWidgets (v1.8.0 Glance Widgets)
            </h2>
            <p className="text-xs text-text-muted">
              Live interactive preview of SignalWidgetReceiver & CampaignWidgetReceiver
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close widgets preview"
            className="p-1.5 rounded-lg bg-surface-elevated border border-border text-text-muted hover:text-text"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {/* Widget 1: GSM Signal Widget */}
          <div className="bg-surface-elevated border border-border rounded-2xl p-4 flex flex-col justify-between">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold uppercase tracking-wider text-accent flex items-center gap-1.5">
                <Signal className="w-4 h-4" /> GSM Signal Widget
              </span>
              <button
                type="button"
                onClick={onRefreshSignal}
                aria-label="Refresh signal widget"
                className="p-1.5 rounded-lg bg-surface border border-border text-text-muted hover:text-text"
              >
                <RefreshCw className="w-3.5 h-3.5" />
              </button>
            </div>

            <div className="my-4 flex items-baseline justify-between">
              <div>
                <div className="text-3xl font-mono font-bold text-text">
                  {currentRadio.rsrp} <span className="text-sm font-normal">dBm</span>
                </div>
                <div className="text-xs text-text-muted mt-1">
                  RSRQ {currentRadio.rsrq} dB · SINR {currentRadio.sinr.toFixed(1)} dB
                </div>
              </div>
              <div className="text-right font-mono text-xs">
                <div className="px-2 py-0.5 rounded bg-accent/15 text-accent font-bold">
                  {currentRadio.technology.replace('_', ' ')} ({currentRadio.nrStatus})
                </div>
                <div className="text-text-muted mt-1">
                  {currentRadio.operatorName} · B{currentRadio.band} · PCI {currentRadio.pci}
                </div>
              </div>
            </div>

            <div className="text-[11px] text-text-muted border-t border-border pt-2 flex justify-between">
              <span>Cell ID: {currentRadio.cellId}</span>
              <span>Live telemetry active</span>
            </div>
          </div>

          {/* Widget 2: GSM Campaign Widget */}
          <div className="bg-surface-elevated border border-border rounded-2xl p-4 flex flex-col justify-between">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold uppercase tracking-wider text-accent flex items-center gap-1.5">
                <Radio className="w-4 h-4" /> GSM Campaign Widget
              </span>
              <span
                className={`text-[11px] font-mono px-2 py-0.5 rounded ${
                  isRunning
                    ? 'bg-success/15 text-success font-semibold'
                    : 'bg-surface text-text-muted'
                }`}
              >
                {isRunning ? 'Campaign running' : 'No campaign'}
              </span>
            </div>

            <div className="my-4">
              <div className="text-sm font-bold text-text truncate">
                {isRunning ? activeCampaignName : 'Ready to record drive test'}
              </div>
              <div className="grid grid-cols-2 gap-2 mt-2 font-mono text-xs">
                <div className="bg-surface rounded-lg p-2 border border-border">
                  <span className="text-text-muted block text-[10px]">Measurements</span>
                  <span className="font-bold text-sm">{measurementCount}</span>
                </div>
                <div className="bg-surface rounded-lg p-2 border border-border">
                  <span className="text-text-muted block text-[10px]">Distance</span>
                  <span className="font-bold text-sm">{(distanceMeters / 1000).toFixed(1)} km</span>
                </div>
              </div>
            </div>

            <div className="pt-2 border-t border-border flex justify-end">
              {!isRunning ? (
                <button
                  type="button"
                  onClick={onStartQuickCampaign}
                  className="w-full py-2 rounded-lg bg-accent text-on-accent text-xs font-bold flex items-center justify-center gap-1.5"
                >
                  <Play className="w-3.5 h-3.5" /> Start Campaign
                </button>
              ) : (
                <button
                  type="button"
                  onClick={onStopCampaign}
                  className="w-full py-2 rounded-lg bg-danger text-on-accent text-xs font-bold flex items-center justify-center gap-1.5"
                >
                  <Square className="w-3.5 h-3.5" /> Stop Campaign
                </button>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

