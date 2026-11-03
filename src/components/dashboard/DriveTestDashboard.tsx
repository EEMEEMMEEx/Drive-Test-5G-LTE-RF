import React, { useEffect, useId, useRef, useState } from 'react';
import {
  Activity,
  AlertTriangle,
  BatteryCharging,
  Car,
  CheckCircle2,
  Compass,
  Cpu,
  Gauge,
  Layers,
  MapPin,
  Pause,
  Play,
  Radio,
  RefreshCw,
  RotateCcw,
  ShieldAlert,
  Signal,
  Smartphone,
  Square,
  Timer,
  Wifi,
  Zap,
} from 'lucide-react';
import type {
  CampaignFinishedSummary,
  CampaignPreset,
  GpsInfo,
  LightNetworkProbe,
  Measurement,
  MeasurementCadence,
  RadioInfo,
  SimInfo,
  SpeedTestProgress,
  SpeedTestStatus,
} from '../../types/drivetest';
import type { TranslationDictionary } from '../../i18n/translations';
import { getBandFrequencyLabel } from '../../services/rfAnalyticsEngine';

export interface DriveTestDashboardProps {
  isRunning: boolean;
  isPaused: boolean;
  activeCampaignName: string;
  activePreset: CampaignPreset;
  elapsedMs: number;
  measurementCount: number;
  distanceMeters: number;
  handoverCount: number;
  currentRadio: RadioInfo;
  currentGps: GpsInfo;
  recentMeasurements: Measurement[];
  availableSims: SimInfo[];
  selectedSim: SimInfo;
  onSelectSim: (sim: SimInfo) => void;
  cadence: MeasurementCadence;
  onChangeCadence: (cadence: MeasurementCadence) => void;
  alertThresholdRsrp: number | null;
  onChangeAlertThreshold: (threshold: number | null) => void;
  lightProbeEnabled: boolean;
  onToggleLightProbe: (enabled: boolean) => void;
  lastLightProbe: LightNetworkProbe | null;
  isSpeedTestRunning: boolean;
  speedTestProgress: SpeedTestProgress | null;
  lastSpeedTestStatus: SpeedTestStatus;
  lastSpeedDownloadMbps: number;
  lastSpeedUploadMbps: number;
  lastSpeedPingMs: number;
  lastSpeedJitterMs: number;
  onRunSpeedTest: () => void;
  onStartCampaign: (name: string, preset: CampaignPreset, alertThreshold: number | null) => void;
  onPauseResumeCampaign: () => void;
  onStopCampaign: () => void;
  finishedSummary: CampaignFinishedSummary | null;
  onDismissFinishedSummary: () => void;
  onViewFinishedAnalysis: (campaignId: number) => void;
  onSwitchToLiveMap: () => void;
  useRealBrowserGps: boolean;
  onToggleRealBrowserGps: (useReal: boolean) => void;
  t: TranslationDictionary;
}

export function DriveTestDashboard(props: DriveTestDashboardProps) {
  const {
    isRunning,
    isPaused,
    activeCampaignName,
    activePreset,
    elapsedMs,
    measurementCount,
    distanceMeters,
    handoverCount,
    currentRadio,
    currentGps,
    recentMeasurements,
    availableSims,
    selectedSim,
    onSelectSim,
    cadence,
    onChangeCadence,
    alertThresholdRsrp,
    onChangeAlertThreshold,
    lightProbeEnabled,
    onToggleLightProbe,
    lastLightProbe,
    isSpeedTestRunning,
    speedTestProgress,
    lastSpeedDownloadMbps,
    lastSpeedUploadMbps,
    lastSpeedPingMs,
    lastSpeedJitterMs,
    onRunSpeedTest,
    onStartCampaign,
    onPauseResumeCampaign,
    onStopCampaign,
    finishedSummary,
    onDismissFinishedSummary,
    onViewFinishedAnalysis,
    onSwitchToLiveMap,
    useRealBrowserGps,
    onToggleRealBrowserGps,
    t,
  } = props;

  const [newCampaignName, setNewCampaignName] = useState('Bangkok_5G_NR_Drive');
  const [selectedPreset, setSelectedPreset] = useState<CampaignPreset>(activePreset);
  const [enableAlert, setEnableAlert] = useState<boolean>(alertThresholdRsrp !== null);
  const [thresholdInput, setThresholdInput] = useState<number>(alertThresholdRsrp ?? -110);
  const [isCarMode, setIsCarMode] = useState(false);
  const [showGoToMapPrompt, setShowGoToMapPrompt] = useState(false);

  const startDialogRef = useRef<HTMLDialogElement | null>(null);
  const stopDialogRef = useRef<HTMLDialogElement | null>(null);
  const startDialogTitleId = useId();
  const stopDialogTitleId = useId();

  // Modern Web Guidance: Fallback for <dialog closedby="any"> light-dismiss
  useEffect(() => {
    const dialogs = [startDialogRef.current, stopDialogRef.current].filter(
      (d): d is HTMLDialogElement => d !== null
    );
    if (typeof HTMLDialogElement !== 'undefined' && !('closedBy' in HTMLDialogElement.prototype)) {
      const handlers = dialogs.map((dialog) => {
        const onClick = (event: MouseEvent) => {
          if (event.target !== dialog) return;
          const rect = dialog.getBoundingClientRect();
          const isDialogContent =
            rect.top <= event.clientY &&
            event.clientY <= rect.top + rect.height &&
            rect.left <= event.clientX &&
            event.clientX <= rect.left + rect.width;
          if (!isDialogContent) {
            dialog.close();
          }
        };
        dialog.addEventListener('click', onClick);
        return { dialog, onClick };
      });
      return () => {
        handlers.forEach(({ dialog, onClick }) => dialog.removeEventListener('click', onClick));
      };
    }
  }, []);

  const openStartModal = () => {
    const dialog = startDialogRef.current;
    if (dialog && typeof dialog.showModal === 'function') {
      dialog.showModal();
    }
  };

  const closeStartModal = () => {
    const dialog = startDialogRef.current;
    if (dialog && typeof dialog.close === 'function') {
      dialog.close();
    }
  };

  const openStopModal = () => {
    const dialog = stopDialogRef.current;
    if (dialog && typeof dialog.showModal === 'function') {
      dialog.showModal();
    } else {
      onStopCampaign();
    }
  };

  const closeStopModal = () => {
    const dialog = stopDialogRef.current;
    if (dialog && typeof dialog.close === 'function') {
      dialog.close();
    }
  };

  const handleConfirmStart = (e: React.FormEvent) => {
    e.preventDefault();
    const finalName = newCampaignName.trim() || `Drive_${selectedPreset}_${Date.now().toString().slice(-4)}`;
    const alertVal = enableAlert ? thresholdInput : null;
    onChangeAlertThreshold(alertVal);
    onStartCampaign(finalName, selectedPreset, alertVal);
    closeStartModal();
    setShowGoToMapPrompt(true);
  };

  const formatDuration = (ms: number) => {
    const totalSec = Math.floor(ms / 1000);
    const m = Math.floor(totalSec / 60);
    const s = totalSec % 60;
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  };

  const isStandby = !isRunning || currentRadio.technology === 'UNKNOWN';
  const rsrpPercent = isStandby ? 0 : Math.max(0, Math.min(100, ((currentRadio.rsrp + 135) / 80) * 100));
  const rsrpQualityColor =
    currentRadio.rsrp >= -90
      ? 'text-success'
      : currentRadio.rsrp >= -105
      ? 'text-accent'
      : currentRadio.rsrp >= -112
      ? 'text-warning'
      : 'text-danger';

  const isWeakSignalAlertActive =
    !isStandby && alertThresholdRsrp !== null && currentRadio.rsrp < alertThresholdRsrp;

  const totalCaBandwidthMhz = Math.round(
    currentRadio.aggregatedBands.reduce((acc, b) => acc + b.dlBandwidthKhz, 0) / 1000
  );

  if (isCarMode) {
    return (
      <div className="fixed inset-0 z-50 bg-bg text-text flex flex-col justify-between p-6">
        <div className="flex items-center justify-between border-b border-border pb-4">
          <div className="flex items-center gap-3">
            <Car className="w-7 h-7 text-accent" />
            <div>
              <h2 className="text-xl font-bold tracking-tight">
                {t.carMode} — {isRunning ? activeCampaignName : 'STANDBY'}
              </h2>
              <p className="text-sm text-text-muted">
                {currentRadio.operatorName} · {currentRadio.technology} ({currentRadio.nrStatus}) · Band {currentRadio.band} · PCI {currentRadio.pci}
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={() => setIsCarMode(false)}
            className="px-5 py-2.5 rounded-lg bg-surface-elevated border border-border font-semibold hover:border-accent"
          >
            {t.exitCarMode}
          </button>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-6 my-auto">
          <div className="bg-surface border border-border rounded-2xl p-8 flex flex-col items-center justify-center">
            <span className="text-sm uppercase tracking-wider text-text-muted font-semibold">
              RSRP (dBm)
            </span>
            <span className={`text-7xl font-mono font-bold mt-2 ${rsrpQualityColor}`}>
              {currentRadio.rsrp}
            </span>
            <span className="text-sm text-text-muted mt-3">
              RSRQ {currentRadio.rsrq} dB · SINR {currentRadio.sinr} dB
            </span>
          </div>

          <div className="bg-surface border border-border rounded-2xl p-8 flex flex-col items-center justify-center">
            <span className="text-sm uppercase tracking-wider text-text-muted font-semibold">
              {t.servingCell}
            </span>
            <span className="text-5xl font-mono font-bold text-accent mt-2">
              PCI {currentRadio.pci}
            </span>
            <span className="text-base text-text-muted mt-3">
              Cell ID {currentRadio.cellId} · {getBandFrequencyLabel(currentRadio.band, currentRadio.technology === 'NR_5G', currentRadio.dlFrequencyKhz)}
            </span>
            <span className="text-sm text-success mt-1">
              CA {currentRadio.aggregatedBands.length}CC · {totalCaBandwidthMhz} MHz
            </span>
          </div>

          <div className="bg-surface border border-border rounded-2xl p-8 flex flex-col items-center justify-center">
            <span className="text-sm uppercase tracking-wider text-text-muted font-semibold">
              SPEED & SESSION
            </span>
            <span className="text-6xl font-mono font-bold text-text mt-2">
              {Math.round(currentGps.speed * 3.6)} <span className="text-2xl">km/h</span>
            </span>
            <span className="text-base text-text-muted mt-3">
              {measurementCount} pts · {(distanceMeters / 1000).toFixed(2)} km · {handoverCount} HO
            </span>
          </div>
        </div>

        <div className="flex items-center justify-between border-t border-border pt-4">
          <div className="text-sm font-mono text-text-muted">
            GPS: {currentGps.latitude.toFixed(5)}, {currentGps.longitude.toFixed(5)} (±{currentGps.accuracy}m)
          </div>
          <div className="flex items-center gap-3">
            {!isRunning ? (
              <button
                type="button"
                onClick={() => onStartCampaign(newCampaignName, selectedPreset, alertThresholdRsrp)}
                className="px-6 py-3 rounded-xl bg-accent text-on-accent font-bold flex items-center gap-2"
              >
                <Play className="w-5 h-5" /> {t.startDriveTest}
              </button>
            ) : (
              <>
                <button
                  type="button"
                  onClick={onPauseResumeCampaign}
                  className="px-5 py-3 rounded-xl bg-surface-elevated border border-border font-semibold flex items-center gap-2"
                >
                  <Pause className="w-5 h-5" /> {isPaused ? t.resumeDriveTest : t.pauseDriveTest}
                </button>
                <button
                  type="button"
                  onClick={onStopCampaign}
                  className="px-6 py-3 rounded-xl bg-danger text-on-accent font-bold flex items-center gap-2"
                >
                  <Square className="w-5 h-5" /> {t.stopDriveTest}
                </button>
              </>
            )}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      {/* Weak Signal Alert Banner */}
      {isWeakSignalAlertActive && (
        <div
          role="alert"
          className="flex items-center justify-between gap-3 px-4 py-3 rounded-xl bg-danger/15 border border-danger text-danger"
        >
          <div className="flex items-center gap-2.5">
            <ShieldAlert className="w-5 h-5 shrink-0" />
            <span className="text-sm font-semibold">
              {t.weakSignalAlert}: RSRP at {isStandby ? '--- dBm' : `${currentRadio.rsrp} dBm`} (below threshold {alertThresholdRsrp} dBm) on Cell {currentRadio.cellId} (PCI {currentRadio.pci})
            </span>
          </div>
          <span className="text-xs font-mono uppercase px-2 py-0.5 rounded bg-danger/20">
            ALERT ACTIVE
          </span>
        </div>
      )}

      {/* Go To Live Map Prompt after starting test */}
      {showGoToMapPrompt && isRunning && (
        <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-3 rounded-xl bg-accent/15 border border-accent/50 text-text">
          <div className="flex items-center gap-2.5">
            <Compass className="w-5 h-5 text-accent" />
            <span className="text-sm font-medium">
              Drive test started ({activeCampaignName}). Switch to the Route Map to follow measurements live?
            </span>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => {
                setShowGoToMapPrompt(false);
                onSwitchToLiveMap();
              }}
              className="px-3 py-1.5 rounded-lg bg-accent text-on-accent text-xs font-semibold"
            >
              {t.navMap}
            </button>
            <button
              type="button"
              onClick={() => setShowGoToMapPrompt(false)}
              className="px-3 py-1.5 rounded-lg bg-surface-elevated border border-border text-xs"
            >
              Stay here
            </button>
          </div>
        </div>
      )}

      {/* Finished Campaign Summary Banner */}
      {finishedSummary && (
        <div className="p-4 rounded-xl bg-surface-elevated border border-success/50 flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <CheckCircle2 className="w-6 h-6 text-success shrink-0" />
            <div>
              <h3 className="text-sm font-bold text-text">
                Test Finished: {finishedSummary.campaignName}
              </h3>
              <p className="text-xs text-text-muted mt-0.5">
                {finishedSummary.measurementCount} measurements · {(finishedSummary.distanceMeters / 1000).toFixed(2)} km · Duration {formatDuration(finishedSummary.durationMs)} · Avg RSRP {finishedSummary.avgRsrp} dBm (Min {finishedSummary.minRsrp} / Max {finishedSummary.maxRsrp} dBm) · {finishedSummary.handoverCount} handovers
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => onViewFinishedAnalysis(finishedSummary.campaignId)}
              className="px-3.5 py-1.5 rounded-lg bg-accent text-on-accent text-xs font-semibold"
            >
              View Analysis
            </button>
            <button
              type="button"
              onClick={onDismissFinishedSummary}
              className="px-3 py-1.5 rounded-lg bg-surface border border-border text-xs text-text-muted hover:text-text"
            >
              Close
            </button>
          </div>
        </div>
      )}

      {/* Top Campaign Control Bar */}
      <div className="bg-surface border border-border rounded-xl p-4 flex flex-col lg:flex-row lg:items-center lg:justify-between gap-4">
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex items-center gap-2">
            <Smartphone className="w-4 h-4 text-accent" />
            <label htmlFor="sim-select" className="text-xs font-medium text-text-muted">
              {t.simSelector}:
            </label>
            <select
              id="sim-select"
              value={selectedSim.subscriptionId}
              onChange={(e) => {
                const found = availableSims.find((s) => s.subscriptionId === Number(e.target.value));
                if (found) onSelectSim(found);
              }}
              className="bg-surface-elevated border border-border-input rounded-lg px-2.5 py-1.5 text-xs font-medium text-text focus:outline-none focus:ring-2 focus:ring-accent"
            >
              {availableSims.map((sim) => (
                <option key={sim.subscriptionId} value={sim.subscriptionId}>
                  {sim.displayName} ({sim.mcc}-{sim.mnc})
                </option>
              ))}
            </select>
          </div>

          <div className="flex items-center gap-1.5 bg-surface-elevated border border-border rounded-lg p-1">
            {(['ECO', 'STANDARD', 'PRECISE'] as MeasurementCadence[]).map((cad) => (
              <button
                key={cad}
                type="button"
                onClick={() => onChangeCadence(cad)}
                className={`px-2.5 py-1 rounded text-xs font-medium transition ${
                  cadence === cad
                    ? 'bg-accent text-on-accent font-semibold'
                    : 'text-text-muted hover:text-text'
                }`}
              >
                {cad === 'ECO'
                  ? t.cadenceEco
                  : cad === 'STANDARD'
                  ? t.cadenceStandard
                  : t.cadencePrecise}
              </button>
            ))}
          </div>

          <button
            type="button"
            onClick={() => setIsCarMode(true)}
            className="px-3 py-1.5 rounded-lg bg-surface-elevated border border-border text-xs font-medium text-text hover:border-accent flex items-center gap-1.5"
          >
            <Car className="w-4 h-4 text-accent" />
            {t.carMode}
          </button>
        </div>

        {/* Recording Status & Start/Stop Controls */}
        <div className="flex flex-wrap items-center gap-3">
          {isRunning && (
            <div className="flex items-center gap-3 px-3 py-1.5 rounded-lg bg-surface-elevated border border-border text-xs font-mono">
              <span className="inline-flex items-center gap-1.5 text-success font-semibold">
                <span className="w-2 h-2 rounded-full bg-success animate-pulse" />
                {isPaused ? 'PAUSED' : 'RECORDING'}
              </span>
              <span>{formatDuration(elapsedMs)}</span>
              <span>{measurementCount} pts</span>
              <span>{(distanceMeters / 1000).toFixed(2)} km</span>
              <span>{handoverCount} HO</span>
            </div>
          )}

          {!isRunning ? (
            <div className="flex items-center gap-2">
              <button
                type="button"
                data-tutorial="start-btn"
                onClick={openStartModal}
                className="px-4 py-2 rounded-lg bg-accent text-on-accent text-xs font-bold flex items-center gap-2 hover:opacity-95 transition"
              >
                <Play className="w-4 h-4" />
                {t.startDriveTest}
              </button>
              <button
                type="button"
                onClick={() =>
                  onStartCampaign(
                    `${newCampaignName}_${Date.now().toString().slice(-3)}`,
                    selectedPreset,
                    alertThresholdRsrp
                  )
                }
                className="px-3 py-2 rounded-lg bg-surface-elevated border border-border text-xs font-medium text-text hover:border-accent"
              >
                Quick Start ({selectedPreset.replace('_', ' ')})
              </button>

            </div>
          ) : (
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={onPauseResumeCampaign}
                className="px-3.5 py-2 rounded-lg bg-surface-elevated border border-border text-xs font-semibold flex items-center gap-1.5 hover:border-accent"
              >
                {isPaused ? <Play className="w-4 h-4 text-success" /> : <Pause className="w-4 h-4 text-warning" />}
                {isPaused ? t.resumeDriveTest : t.pauseDriveTest}
              </button>
              <button
                type="button"
                onClick={openStopModal}
                className="px-4 py-2 rounded-lg bg-danger text-on-accent text-xs font-bold flex items-center gap-1.5 hover:opacity-90"
              >
                <Square className="w-4 h-4" />
                {t.stopDriveTest}
              </button>
            </div>
          )}
        </div>
      </div>

      {/* Main 3-Column RF Engineering Dashboard Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-5">
        {/* Column 1: Real-time RF Signal Gauge & Metrics (4 cols) */}
        <section
          data-tutorial="rf-gauge"
          className="lg:col-span-4 bg-surface border border-border rounded-xl p-5 flex flex-col justify-between rf-containment-panel"
        >
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Gauge className="w-5 h-5 text-accent" />
              <h2 className="text-sm font-bold uppercase tracking-wider text-text">
                RF Signal Telemetry
              </h2>
            </div>
            <span className="px-2.5 py-1 rounded-full text-xs font-mono font-bold bg-accent/15 text-accent border border-accent/40">
              {currentRadio.technology.replace('_', ' ')} · {currentRadio.nrStatus}
            </span>
          </div>

          {/* SVG Semi-Circular RF Gauge */}
          <div className="my-4 flex flex-col items-center">
            <svg viewBox="0 0 220 130" className="w-56 h-32 overflow-visible">
              <defs>
                <linearGradient id="rsrpGaugeGrad" x1="0%" y1="0%" x2="100%" y2="0%">
                  <stop offset="0%" stopColor="#f87171" />
                  <stop offset="45%" stopColor="#fbbf24" />
                  <stop offset="75%" stopColor="#38bdf8" />
                  <stop offset="100%" stopColor="#4ade80" />
                </linearGradient>
              </defs>
              <path
                d="M 20 110 A 90 90 0 0 1 200 110"
                fill="none"
                stroke="#1c273b"
                strokeWidth="16"
                strokeLinecap="round"
              />
              <path
                d="M 20 110 A 90 90 0 0 1 200 110"
                fill="none"
                stroke="url(#rsrpGaugeGrad)"
                strokeWidth="16"
                strokeLinecap="round"
                strokeDasharray="283"
                strokeDashoffset={283 - (283 * rsrpPercent) / 100}
              />
              <text
                x="110"
                y="88"
                textAnchor="middle"
                className="fill-current text-text font-mono text-3xl font-bold"
              >
                {isStandby ? '--- dBm' : `${currentRadio.rsrp} dBm`}
              </text>
              <text
                x="110"
                y="110"
                textAnchor="middle"
                className="fill-current text-text-muted font-sans text-xs"
              >
                {isStandby ? 'STANDBY / NO SIGNAL' : `RSRP · Level ${currentRadio.signalLevel}/4`}
              </text>
            </svg>

            {/* Primary RF Metric Grid */}
            <div className="grid grid-cols-3 gap-2.5 w-full mt-2">
              <div className="bg-surface-elevated border border-border rounded-lg p-2.5 text-center">
                <div className="text-[11px] text-text-muted uppercase">RSRQ</div>
                <div className="text-base font-mono font-bold text-text mt-0.5">
                  {currentRadio.rsrq} <span className="text-xs font-normal">dB</span>
                </div>
              </div>
              <div className="bg-surface-elevated border border-border rounded-lg p-2.5 text-center">
                <div className="text-[11px] text-text-muted uppercase">SINR</div>
                <div className="text-base font-mono font-bold text-accent mt-0.5">
                  {currentRadio.sinr.toFixed(1)} <span className="text-xs font-normal">dB</span>
                </div>
              </div>
              <div className="bg-surface-elevated border border-border rounded-lg p-2.5 text-center">
                <div className="text-[11px] text-text-muted uppercase">RSSI</div>
                <div className="text-base font-mono font-bold text-text mt-0.5">
                  {currentRadio.rssi} <span className="text-xs font-normal">dBm</span>
                </div>
              </div>
              <div className="bg-surface-elevated border border-border rounded-lg p-2.5 text-center">
                <div className="text-[11px] text-text-muted uppercase">CSI-RSRP</div>
                <div className="text-sm font-mono font-semibold text-text mt-0.5">
                  {currentRadio.csiRsrp} dBm
                </div>
              </div>
              <div className="bg-surface-elevated border border-border rounded-lg p-2.5 text-center">
                <div className="text-[11px] text-text-muted uppercase">CQI</div>
                <div className="text-sm font-mono font-semibold text-text mt-0.5">
                  {currentRadio.cqi} / 15
                </div>
              </div>
              <div className="bg-surface-elevated border border-border rounded-lg p-2.5 text-center">
                <div className="text-[11px] text-text-muted uppercase">Timing Adv</div>
                <div className="text-sm font-mono font-semibold text-text mt-0.5">
                  TA {currentRadio.timingAdvance}
                </div>
              </div>
            </div>
          </div>

          {/* Weak Signal Threshold Quick Control */}
          <div className="pt-3 border-t border-border flex items-center justify-between gap-2 text-xs">
            <label htmlFor="dashboard-alert-toggle" className="flex items-center gap-2 text-text-muted cursor-pointer">
              <input
                id="dashboard-alert-toggle"
                type="checkbox"
                checked={alertThresholdRsrp !== null}
                onChange={(e) => {
                  const checked = e.target.checked;
                  setEnableAlert(checked);
                  onChangeAlertThreshold(checked ? thresholdInput : null);
                }}
                className="rounded border-border-input"
              />
              {t.weakSignalAlert}
            </label>
            <div className="flex items-center gap-1.5">
              <input
                type="number"
                aria-label={t.rsrpThresholdLabel}
                value={thresholdInput}
                min={-135}
                max={-70}
                onChange={(e) => {
                  const val = Number(e.target.value);
                  setThresholdInput(val);
                  if (alertThresholdRsrp !== null) onChangeAlertThreshold(val);
                }}
                className="w-20 bg-surface-elevated border border-border-input rounded px-2 py-1 font-mono text-xs text-text"
              />
              <span className="text-text-muted font-mono">dBm</span>
            </div>
          </div>
        </section>

        {/* Column 2: Serving Cell, Carrier Aggregation & GPS Telemetry (4 cols) */}
        <section className="lg:col-span-4 space-y-5 rf-containment-panel">
          {/* Serving Cell & Carrier Aggregation Card */}
          <div className="bg-surface border border-border rounded-xl p-5">
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-2">
                <Radio className="w-5 h-5 text-accent" />
                <h2 className="text-sm font-bold uppercase tracking-wider text-text">
                  {t.servingCell}
                </h2>
              </div>
              <span className="text-xs font-mono text-success">
                {currentRadio.connectionState}
              </span>
            </div>

            <div className="grid grid-cols-2 gap-2.5 text-xs">
              <div className="bg-surface-elevated border border-border rounded-lg p-2.5">
                <span className="text-text-muted block">Operator (PLMN)</span>
                <span className="font-mono font-bold text-sm text-text">
                  {currentRadio.operatorName} ({currentRadio.mcc}-{currentRadio.mnc})
                </span>
              </div>
              <div className="bg-surface-elevated border border-border rounded-lg p-2.5">
                <span className="text-text-muted block">Cell ID / TAC</span>
                <span className="font-mono font-bold text-sm text-text">
                  {currentRadio.cellId} / {currentRadio.lac}
                </span>
              </div>
              <div className="bg-surface-elevated border border-border rounded-lg p-2.5">
                <span className="text-text-muted block">PCI / Band</span>
                <span className="font-mono font-bold text-sm text-accent">
                  PCI {currentRadio.pci} · {getBandFrequencyLabel(currentRadio.band, currentRadio.technology === 'NR_5G', currentRadio.dlFrequencyKhz)}
                </span>
              </div>
              <div className="bg-surface-elevated border border-border rounded-lg p-2.5">
                <span className="text-text-muted block">EARFCN / BW</span>
                <span className="font-mono font-bold text-sm text-text">
                  {currentRadio.earfcn} · {Math.round(currentRadio.dlBandwidthKhz / 1000)} MHz
                </span>
              </div>
            </div>

            {/* Carrier Aggregation Snapshot */}
            <div className="mt-3 pt-3 border-t border-border">
              <div className="flex items-center justify-between text-xs mb-2">
                <span className="font-semibold text-text-muted flex items-center gap-1.5">
                  <Layers className="w-3.5 h-3.5 text-accent" />
                  {t.carrierAggregation}
                </span>
                <span className="font-mono text-accent font-semibold">
                  {currentRadio.aggregatedBands.length} components · {totalCaBandwidthMhz} MHz total
                </span>
              </div>
              <div className="flex flex-wrap gap-1.5">
                {currentRadio.aggregatedBands.map((cc, idx) => (
                  <span
                    key={`${cc.band}_${idx}`}
                    className="px-2.5 py-1 rounded bg-surface-elevated border border-border font-mono text-xs text-text"
                  >
                    {cc.isNr ? `n${cc.band}` : `B${cc.band}`} ({Math.round(cc.dlFrequencyKhz / 1000)} MHz · {Math.round(cc.dlBandwidthKhz / 1000)} MHz BW)
                  </span>
                ))}
              </div>
            </div>
          </div>

          {/* GNSS / GPS Telemetry Card */}
          <div className="bg-surface border border-border rounded-xl p-5">
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-2">
                <MapPin className="w-5 h-5 text-accent" />
                <h2 className="text-sm font-bold uppercase tracking-wider text-text">
                  {t.gpsTelemetry}
                </h2>
              </div>
              <button
                type="button"
                onClick={() => onToggleRealBrowserGps(!useRealBrowserGps)}
                className="text-xs px-2.5 py-1 rounded bg-surface-elevated border border-border text-text-muted hover:text-text"
              >
                Source: {useRealBrowserGps ? 'Browser Geolocation' : 'RF Route Sim'}
              </button>
            </div>

            <div className="grid grid-cols-3 gap-2.5 text-xs">
              <div className="bg-surface-elevated border border-border rounded-lg p-2.5">
                <span className="text-text-muted block">Latitude</span>
                <span className="font-mono font-semibold text-text">
                  {currentGps.latitude.toFixed(6)}°
                </span>
              </div>
              <div className="bg-surface-elevated border border-border rounded-lg p-2.5">
                <span className="text-text-muted block">Longitude</span>
                <span className="font-mono font-semibold text-text">
                  {currentGps.longitude.toFixed(6)}°
                </span>
              </div>
              <div className="bg-surface-elevated border border-border rounded-lg p-2.5">
                <span className="text-text-muted block">Speed</span>
                <span className="font-mono font-semibold text-accent">
                  {(currentGps.speed * 3.6).toFixed(1)} km/h
                </span>
              </div>
              <div className="bg-surface-elevated border border-border rounded-lg p-2.5">
                <span className="text-text-muted block">Altitude</span>
                <span className="font-mono font-semibold text-text">
                  {currentGps.altitude.toFixed(1)} m
                </span>
              </div>
              <div className="bg-surface-elevated border border-border rounded-lg p-2.5">
                <span className="text-text-muted block">Bearing</span>
                <span className="font-mono font-semibold text-text">
                  {currentGps.bearing}°
                </span>
              </div>
              <div className="bg-surface-elevated border border-border rounded-lg p-2.5">
                <span className="text-text-muted block">Accuracy</span>
                <span className="font-mono font-semibold text-success">
                  ±{currentGps.accuracy.toFixed(1)} m
                </span>
              </div>
            </div>
          </div>
        </section>

        {/* Column 3: Speed Test, Light Network Probe & Neighbor Cells (4 cols) */}
        <section className="lg:col-span-4 space-y-5 rf-containment-panel">
          {/* Active Speed Test & Light Network Probe Card */}
          <div className="bg-surface border border-border rounded-xl p-5">
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-2">
                <Zap className="w-5 h-5 text-accent" />
                <h2 className="text-sm font-bold uppercase tracking-wider text-text">
                  {t.speedTestTitle}
                </h2>
              </div>
              <button
                type="button"
                disabled={isSpeedTestRunning}
                onClick={onRunSpeedTest}
                className="px-3 py-1.5 rounded-lg bg-accent text-on-accent text-xs font-bold flex items-center gap-1.5 disabled:opacity-50"
              >
                <RefreshCw className={`w-3.5 h-3.5 ${isSpeedTestRunning ? 'animate-spin' : ''}`} />
                {isSpeedTestRunning && speedTestProgress
                  ? `${speedTestProgress.phase}…`
                  : t.runSpeedTest}
              </button>
            </div>

            <div className="grid grid-cols-2 gap-2.5 text-xs">
              <div className="bg-surface-elevated border border-border rounded-lg p-3">
                <span className="text-text-muted block">Download</span>
                <span className="text-xl font-mono font-bold text-success">
                  {(speedTestProgress?.downloadMbps ?? lastSpeedDownloadMbps).toFixed(1)}{' '}
                  <span className="text-xs font-normal">Mbps</span>
                </span>
              </div>
              <div className="bg-surface-elevated border border-border rounded-lg p-3">
                <span className="text-text-muted block">Upload</span>
                <span className="text-xl font-mono font-bold text-accent">
                  {(speedTestProgress?.uploadMbps ?? lastSpeedUploadMbps).toFixed(1)}{' '}
                  <span className="text-xs font-normal">Mbps</span>
                </span>
              </div>
              <div className="bg-surface-elevated border border-border rounded-lg p-2.5">
                <span className="text-text-muted block">Unloaded Ping / Jitter</span>
                <span className="font-mono font-semibold text-text">
                  {(speedTestProgress?.pingMs ?? lastSpeedPingMs).toFixed(0)} ms / {lastSpeedJitterMs.toFixed(1)} ms
                </span>
              </div>
              <div className="bg-surface-elevated border border-border rounded-lg p-2.5">
                <span className="text-text-muted block">Loaded Latency</span>
                <span className="font-mono font-semibold text-warning">
                  {(speedTestProgress?.loadedPingMs ?? lastSpeedPingMs * 2.1).toFixed(0)} ms
                </span>
              </div>
            </div>

            {/* Light Network Probe Toggle */}
            <div className="mt-3 pt-3 border-t border-border flex items-center justify-between text-xs">
              <label htmlFor="light-probe-toggle" className="flex items-center gap-2 text-text-muted cursor-pointer">
                <input
                  id="light-probe-toggle"
                  type="checkbox"
                  checked={lightProbeEnabled}
                  onChange={(e) => onToggleLightProbe(e.target.checked)}
                />
                {t.lightProbeTitle}
              </label>
              <span className="font-mono text-text">
                {lastLightProbe
                  ? `Ping ${lastLightProbe.pingMs} ms · Jitter ${lastLightProbe.jitterMs} ms`
                  : 'Standby'}
              </span>
            </div>
          </div>

          {/* Neighboring Cells Table */}
          <div className="bg-surface border border-border rounded-xl p-5">
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-2">
                <Signal className="w-5 h-5 text-accent" />
                <h2 className="text-sm font-bold uppercase tracking-wider text-text">
                  {t.neighborCells} ({currentRadio.neighborCells.length})
                </h2>
              </div>
            </div>

            <div className="rf-table-container">
              <table className="w-full text-left text-xs rf-data-table">
                <thead>
                  <tr className="border-b border-border text-text-muted">
                    <th className="py-2 px-2">PCI</th>
                    <th className="py-2 px-2">Tech</th>
                    <th className="py-2 px-2">EARFCN</th>
                    <th className="py-2 px-2">RSRP</th>
                    <th className="py-2 px-2">RSRQ</th>
                    <th className="py-2 px-2">Delta</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border font-mono">
                  {currentRadio.neighborCells.map((n) => {
                    const delta = n.rsrp - currentRadio.rsrp;
                    return (
                      <tr key={`${n.pci}_${n.earfcn}`}>
                        <th className="py-2 px-2 font-bold text-accent">PCI {n.pci}</th>
                        <td className="py-2 px-2">{n.technology.replace('_', ' ')}</td>
                        <td className="py-2 px-2">{n.earfcn}</td>
                        <td className="py-2 px-2 font-semibold">{n.rsrp} dBm</td>
                        <td className="py-2 px-2">{n.rsrq} dB</td>
                        <td
                          className={`py-2 px-2 font-bold ${
                            delta >= 6 ? 'text-warning' : 'text-text-muted'
                          }`}
                        >
                          {delta > 0 ? `+${delta}` : delta} dB
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        </section>
      </div>

      {/* Live Rolling RSRP Strip Chart */}
      <section className="bg-surface border border-border rounded-xl p-4">
        <div className="flex items-center justify-between mb-2">
          <div className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider text-text-muted">
            <Activity className="w-4 h-4 text-accent" />
            Live RSRP Stream (Last {recentMeasurements.length} Samples)
          </div>
          <span className="text-xs font-mono text-text-muted">
            Range: -130 dBm to -60 dBm
          </span>
        </div>
        <div className="h-24 w-full">
          <svg viewBox="0 0 600 90" className="w-full h-full overflow-visible">
            <line x1="0" y1="20" x2="600" y2="20" stroke="#26344d" strokeDasharray="4 4" />
            <line x1="0" y1="55" x2="600" y2="55" stroke="#26344d" strokeDasharray="4 4" />
            {recentMeasurements.length > 1 && (
              <polyline
                fill="none"
                stroke="#38bdf8"
                strokeWidth="2.5"
                points={recentMeasurements
                  .slice(-40)
                  .map((m, idx, arr) => {
                    const x = (idx / Math.max(1, arr.length - 1)) * 590 + 5;
                    const y = 85 - Math.max(0, Math.min(1, (m.radio.rsrp + 130) / 70)) * 75;
                    return `${x.toFixed(1)},${y.toFixed(1)}`;
                  })
                  .join(' ')}
              />
            )}
          </svg>
        </div>
      </section>

      {/* Start Campaign Modal Dialog (using modern-web-guidance <dialog closedby="any">) */}
      <dialog
        ref={startDialogRef}
        {...({ closedby: 'any' } as React.DialogHTMLAttributes<HTMLDialogElement>)}
        aria-labelledby={startDialogTitleId}
        className="bg-surface text-text border border-border rounded-2xl p-6 w-full max-w-md shadow-2xl"
      >
        <form onSubmit={handleConfirmStart} className="space-y-4">
          <h3 id={startDialogTitleId} className="text-lg font-bold">
            {t.newDriveTest}
          </h3>

          <div>
            <label htmlFor="campaign-name-input" className="block text-xs font-medium text-text-muted mb-1">
              {t.campaignName}
            </label>
            <input
              id="campaign-name-input"
              type="text"
              value={newCampaignName}
              onChange={(e) => setNewCampaignName(e.target.value)}
              placeholder="Ex: City_Center_Zone"
              className="w-full bg-surface-elevated border border-border-input rounded-lg px-3 py-2 text-sm text-text"
            />
          </div>

          <div>
            <span className="block text-xs font-medium text-text-muted mb-1.5">
              {t.campaignType}
            </span>
            <div className="grid grid-cols-2 gap-2">
              {(
                [
                  { id: 'URBAN_ROUTE', label: t.presetUrbanRoute },
                  { id: 'HIGHWAY', label: t.presetHighway },
                  { id: 'SITE_AUDIT', label: t.presetSiteAudit },
                  { id: 'INDOOR', label: t.presetIndoor },
                ] as { id: CampaignPreset; label: string }[]
              ).map((preset) => (
                <button
                  key={preset.id}
                  type="button"
                  onClick={() => setSelectedPreset(preset.id)}
                  className={`px-3 py-2 rounded-lg border text-xs font-medium text-left transition ${
                    selectedPreset === preset.id
                      ? 'bg-accent/15 border-accent text-accent font-semibold'
                      : 'bg-surface-elevated border-border text-text-muted hover:text-text'
                  }`}
                >
                  {preset.label}
                </button>
              ))}
            </div>
          </div>

          <div className="pt-2 border-t border-border space-y-2">
            <label className="flex items-center gap-2 text-xs text-text cursor-pointer">
              <input
                type="checkbox"
                checked={enableAlert}
                onChange={(e) => setEnableAlert(e.target.checked)}
              />
              {t.weakSignalAlert} ({t.rsrpThresholdLabel})
            </label>
            {enableAlert && (
              <input
                type="number"
                value={thresholdInput}
                min={-135}
                max={-70}
                onChange={(e) => setThresholdInput(Number(e.target.value))}
                className="w-full bg-surface-elevated border border-border-input rounded-lg px-3 py-1.5 text-sm font-mono text-text"
              />
            )}
          </div>

          <div className="flex justify-end gap-2 pt-3">
            <button
              type="button"
              onClick={closeStartModal}
              className="px-4 py-2 rounded-lg bg-surface-elevated border border-border text-xs font-medium"
            >
              Cancel
            </button>
            <button
              type="submit"
              className="px-4 py-2 rounded-lg bg-accent text-on-accent text-xs font-bold"
            >
              {t.startDriveTest}
            </button>
          </div>
        </form>
      </dialog>

      {/* Stop Campaign Confirmation Dialog */}
      <dialog
        ref={stopDialogRef}
        {...({ closedby: 'any' } as React.DialogHTMLAttributes<HTMLDialogElement>)}
        aria-labelledby={stopDialogTitleId}
        className="bg-surface text-text border border-border rounded-2xl p-6 w-full max-w-sm shadow-2xl"
      >
        <div className="space-y-4">
          <div className="flex items-center gap-2.5 text-warning">
            <AlertTriangle className="w-5 h-5" />
            <h3 id={stopDialogTitleId} className="text-base font-bold text-text">
              Stop the drive test?
            </h3>
          </div>
          <p className="text-xs text-text-muted">
            {measurementCount} measurements have been collected ({(distanceMeters / 1000).toFixed(2)} km). Stopping finalizes the campaign log.
          </p>
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={closeStopModal}
              className="px-4 py-2 rounded-lg bg-surface-elevated border border-border text-xs font-medium"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => {
                closeStopModal();
                onStopCampaign();
              }}
              className="px-4 py-2 rounded-lg bg-danger text-on-accent text-xs font-bold"
            >
              {t.stopDriveTest}
            </button>
          </div>
        </div>
      </dialog>
    </div>
  );
}

