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
  RatSelectionMode,
  SimInfo,
  SpeedTestProgress,
  SpeedTestStatus,
} from '../../types/drivetest';
import type { TranslationDictionary } from '../../i18n/translations';
import { RfToggleSwitch } from '../common/RfToggleSwitch';
import { OpenUiCard } from '../openui/OpenUiCard';
import { OpenUiMetric } from '../openui/OpenUiMetric';
import { OpenUiChips } from '../openui/OpenUiChips';
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
  ratMode: RatSelectionMode;
  onChangeRatMode: (mode: RatSelectionMode) => void;
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
    ratMode,
    onChangeRatMode,
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

      {/* OpenUI Quick Action & Session Control Bar */}
      <OpenUiCard className="mb-5 shadow-sm" bodyClassName="p-3 sm:p-4">
        <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-3.5">
          {/* SIM Selector & Cadence Chips */}
          <div className="flex flex-wrap items-center gap-2.5 sm:gap-3">
            <div className="flex items-center gap-2 bg-surface-elevated border border-border rounded-xl px-2.5 py-1.5">
              <Smartphone className="w-4 h-4 text-accent shrink-0" />
              <label htmlFor="sim-select" className="text-xs font-medium text-text-muted shrink-0">
                {t.simSelector}:
              </label>
              <select
                id="sim-select"
                value={selectedSim.subscriptionId}
                onChange={(e) => {
                  const found = availableSims.find((s) => s.subscriptionId === Number(e.target.value));
                  if (found) onSelectSim(found);
                }}
                className="bg-transparent text-xs font-semibold text-text focus:outline-none cursor-pointer"
              >
                {availableSims.map((sim) => (
                  <option key={sim.subscriptionId} value={sim.subscriptionId} className="bg-surface text-text">
                    {sim.displayName} ({sim.mcc}-{sim.mnc})
                  </option>
                ))}
              </select>
            </div>

            <OpenUiChips
              size="sm"
              selectedId={cadence}
              onChange={onChangeCadence}
              items={[
                { id: 'ECO', label: t.cadenceEco },
                { id: 'STANDARD', label: t.cadenceStandard },
                { id: 'PRECISE', label: t.cadencePrecise },
              ]}
              ariaLabel="Measurement Cadence"
            />

            <OpenUiChips
              size="sm"
              selectedId={ratMode}
              onChange={onChangeRatMode}
              items={[
                { id: 'AUTO', label: t.ratModeAuto },
                { id: 'NR_5G_ONLY', label: t.ratMode5gOnly },
                { id: 'LTE_4G_ONLY', label: t.ratMode4gOnly },
              ]}
              ariaLabel={t.ratSelector}
            />

            <button
              type="button"
              onClick={() => setIsCarMode(true)}
              className="px-3 py-1.5 rounded-xl bg-surface-elevated border border-border text-xs font-medium text-text hover:border-accent hover:text-accent flex items-center gap-1.5 transition-all"
            >
              <Car className="w-4 h-4 text-accent" />
              <span>{t.carMode}</span>
            </button>
          </div>

          {/* Drive Test Session Action Group */}
          <div className="flex flex-wrap items-center gap-2 sm:gap-3">
            {isRunning && (
              <div className="flex items-center gap-2.5 px-3 py-1.5 rounded-xl bg-surface-elevated border border-border text-xs font-mono">
                <span className="inline-flex items-center gap-1.5 text-success font-semibold">
                  <span className="w-2 h-2 rounded-full bg-success animate-pulse" />
                  {isPaused ? 'PAUSED' : 'RECORDING'}
                </span>
                <span className="text-text-muted">|</span>
                <span>{formatDuration(elapsedMs)}</span>
                <span className="text-text-muted">|</span>
                <span className="text-accent">{measurementCount} pts</span>
                <span className="text-text-muted">|</span>
                <span>{(distanceMeters / 1000).toFixed(2)} km</span>
                <span className="text-text-muted">|</span>
                <span className="text-warning">{handoverCount} HO</span>
              </div>
            )}

            {!isRunning ? (
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  data-tutorial="start-btn"
                  onClick={openStartModal}
                  className="px-4 py-2 rounded-xl bg-accent text-on-accent text-xs font-bold flex items-center gap-2 shadow-[0_0_15px_rgba(56,189,248,0.35)] hover:opacity-95 transition-all"
                >
                  <Play className="w-4 h-4 fill-current" />
                  <span>{t.startDriveTest}</span>
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
                  className="px-3 py-2 rounded-xl bg-surface-elevated border border-border text-xs font-medium text-text hover:border-accent transition-all"
                >
                  Quick Start ({selectedPreset.replace('_', ' ')})
                </button>
              </div>
            ) : (
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={onPauseResumeCampaign}
                  className="px-3.5 py-2 rounded-xl bg-surface-elevated border border-border text-xs font-semibold flex items-center gap-1.5 hover:border-accent transition-all"
                >
                  {isPaused ? <Play className="w-4 h-4 text-success fill-current" /> : <Pause className="w-4 h-4 text-warning fill-current" />}
                  <span>{isPaused ? t.resumeDriveTest : t.pauseDriveTest}</span>
                </button>

                <button
                  type="button"
                  onClick={openStopModal}
                  className="px-4 py-2 rounded-xl bg-danger text-on-accent text-xs font-bold flex items-center gap-1.5 shadow-[0_0_12px_rgba(248,113,113,0.3)] hover:opacity-95 transition-all"
                >
                  <Square className="w-4 h-4 fill-current" />
                  <span>{t.stopDriveTest}</span>
                </button>
              </div>
            )}
          </div>
        </div>
      </OpenUiCard>

      {/* Main 2-Column Responsive OpenUI RF Engineering Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-5">
        {/* Column 1: Real-time RF Signal Gauge & Metrics */}
        <OpenUiCard
          dataTutorial="rf-gauge"
          title="RF Signal Telemetry"
          icon={<Gauge className="w-4 h-4" />}
          badge={
            <div className="flex items-center gap-1.5">
              {ratMode !== 'AUTO' && (
                <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-warning/20 text-warning border border-warning/40">
                  {ratMode === 'NR_5G_ONLY' ? '5G LOCKED' : '4G LOCKED'}
                </span>
              )}
              <span className="px-2.5 py-1 rounded-full text-xs font-mono font-bold bg-accent/15 text-accent border border-accent/40">
                {currentRadio.technology.replace('_', ' ')} · {currentRadio.nrStatus}
              </span>
            </div>
          }
          className="lg:col-span-7 rf-containment-panel"
        >
          <div className="flex flex-col md:flex-row items-center justify-between gap-6">
            {/* SVG Semi-Circular RF Gauge */}
            <div className="flex flex-col items-center shrink-0">
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
                  stroke="var(--color-surface-elevated)"
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
            </div>

            {/* OpenUI Metric Tiles */}
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-2.5 w-full">
              <OpenUiMetric
                label="RSRQ"
                value={currentRadio.rsrq}
                unit="dB"
                size="sm"
              />
              <OpenUiMetric
                label="SINR"
                value={currentRadio.sinr.toFixed(1)}
                unit="dB"
                variant="accent"
                size="sm"
              />
              <OpenUiMetric
                label="RSSI"
                value={currentRadio.rssi}
                unit="dBm"
                size="sm"
              />
              <OpenUiMetric
                label="CSI-RSRP"
                value={currentRadio.csiRsrp}
                unit="dBm"
                size="sm"
              />
              <OpenUiMetric
                label="CQI"
                value={`${currentRadio.cqi} / 15`}
                size="sm"
              />
              <OpenUiMetric
                label="Timing Adv"
                value={`TA ${currentRadio.timingAdvance}`}
                size="sm"
              />
            </div>
          </div>

          {/* Weak Signal Threshold Quick Control */}
          <div className="mt-4 pt-3 border-t border-border flex items-center justify-between gap-2 text-xs">
            <RfToggleSwitch
              id="dashboard-alert-toggle"
              checked={alertThresholdRsrp !== null}
              onChange={(checked) => {
                setEnableAlert(checked);
                onChangeAlertThreshold(checked ? thresholdInput : null);
              }}
              label={t.weakSignalAlert}
              size="sm"
              variant="accent"
            />
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
                className="w-20 bg-surface-elevated border border-border-input rounded-lg px-2 py-1 font-mono text-xs text-text"
              />
              <span className="text-text-muted font-mono">dBm</span>
            </div>
          </div>
        </OpenUiCard>

        {/* Column 2: Serving Cell & Carrier Aggregation */}
        <OpenUiCard
          title={t.servingCell}
          icon={<Radio className="w-4 h-4" />}
          badge={
            <span className="text-xs font-mono font-bold text-success px-2 py-0.5 rounded-full bg-success/15 border border-success/30">
              {currentRadio.connectionState}
            </span>
          }
          className="lg:col-span-5 rf-containment-panel"
        >
          <div className="grid grid-cols-2 gap-2.5">
            <OpenUiMetric
              label="Operator (PLMN)"
              value={`${currentRadio.operatorName}`}
              subValue={`MCC ${currentRadio.mcc} · MNC ${currentRadio.mnc}`}
              size="sm"
            />
            <OpenUiMetric
              label="Cell ID / TAC"
              value={`${currentRadio.cellId}`}
              subValue={`TAC / LAC ${currentRadio.lac}`}
              size="sm"
            />
            <OpenUiMetric
              label="PCI / Band"
              value={`PCI ${currentRadio.pci}`}
              subValue={getBandFrequencyLabel(currentRadio.band, currentRadio.technology === 'NR_5G', currentRadio.dlFrequencyKhz)}
              variant="accent"
              size="sm"
            />
            <OpenUiMetric
              label="EARFCN / BW"
              value={`${currentRadio.earfcn}`}
              subValue={`${Math.round(currentRadio.dlBandwidthKhz / 1000)} MHz BW`}
              size="sm"
            />
          </div>

          {/* Carrier Aggregation Snapshot */}
          <div className="mt-3.5 pt-3 border-t border-border">
            <div className="flex items-center justify-between text-xs mb-2">
              <span className="font-semibold text-text-muted flex items-center gap-1.5">
                <Layers className="w-3.5 h-3.5 text-accent" />
                {t.carrierAggregation}
              </span>
              <span className="font-mono text-accent font-semibold">
                {currentRadio.aggregatedBands.length} CC · {totalCaBandwidthMhz} MHz total
              </span>
            </div>
            <div className="flex flex-wrap gap-1.5">
              {currentRadio.aggregatedBands.map((cc, idx) => (
                <span
                  key={`${cc.band}_${idx}`}
                  className="px-2.5 py-1 rounded-lg bg-surface-elevated border border-border font-mono text-xs text-text shadow-sm"
                >
                  {cc.isNr ? `n${cc.band}` : `B${cc.band}`} ({Math.round(cc.dlFrequencyKhz / 1000)} MHz · {Math.round(cc.dlBandwidthKhz / 1000)} MHz BW)
                </span>
              ))}
            </div>
          </div>
        </OpenUiCard>

        {/* GNSS / GPS Telemetry Card */}
        <OpenUiCard
          title={t.gpsTelemetry}
          icon={<MapPin className="w-4 h-4" />}
          actions={
            <RfToggleSwitch
              id="browser-gps-toggle"
              checked={useRealBrowserGps}
              onChange={onToggleRealBrowserGps}
              label={useRealBrowserGps ? 'Browser GPS' : 'Simulated GPS'}
              size="sm"
              variant="accent"
            />
          }
          className="lg:col-span-6"
        >
          <div className="grid grid-cols-2 sm:grid-cols-3 gap-2.5 text-xs">
            <OpenUiMetric
              label="Latitude"
              value={`${currentGps.latitude.toFixed(6)}°`}
              size="sm"
            />
            <OpenUiMetric
              label="Longitude"
              value={`${currentGps.longitude.toFixed(6)}°`}
              size="sm"
            />
            <OpenUiMetric
              label="Speed"
              value={(currentGps.speed * 3.6).toFixed(1)}
              unit="km/h"
              variant="accent"
              size="sm"
            />
            <OpenUiMetric
              label="Altitude"
              value={currentGps.altitude.toFixed(1)}
              unit="m"
              size="sm"
            />
            <OpenUiMetric
              label="Bearing"
              value={`${currentGps.bearing}°`}
              size="sm"
            />
            <OpenUiMetric
              label="Accuracy"
              value={`±${currentGps.accuracy.toFixed(1)}`}
              unit="m"
              variant="success"
              size="sm"
            />
          </div>
        </OpenUiCard>

        {/* Active Speed Test & Light Network Probe Card */}
        <OpenUiCard
          title={t.speedTestTitle}
          icon={<Zap className="w-4 h-4" />}
          actions={
            <button
              type="button"
              disabled={isSpeedTestRunning}
              onClick={onRunSpeedTest}
              className="px-3 py-1.5 rounded-xl bg-accent text-on-accent text-xs font-bold flex items-center gap-1.5 disabled:opacity-50 shadow-sm hover:opacity-95 transition-all"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${isSpeedTestRunning ? 'animate-spin' : ''}`} />
              {isSpeedTestRunning && speedTestProgress
                ? `${speedTestProgress.phase}…`
                : t.runSpeedTest}
            </button>
          }
          className="lg:col-span-6"
        >
          <div className="grid grid-cols-2 gap-2.5">
            <OpenUiMetric
              label="Download"
              value={(speedTestProgress?.downloadMbps ?? lastSpeedDownloadMbps).toFixed(1)}
              unit="Mbps"
              variant="success"
              size="lg"
            />
            <OpenUiMetric
              label="Upload"
              value={(speedTestProgress?.uploadMbps ?? lastSpeedUploadMbps).toFixed(1)}
              unit="Mbps"
              variant="accent"
              size="lg"
            />
            <OpenUiMetric
              label="Unloaded Ping / Jitter"
              value={`${(speedTestProgress?.pingMs ?? lastSpeedPingMs).toFixed(0)} ms`}
              subValue={`Jitter ${lastSpeedJitterMs.toFixed(1)} ms`}
              size="sm"
            />
            <OpenUiMetric
              label="Loaded Latency"
              value={`${(speedTestProgress?.loadedPingMs ?? lastSpeedPingMs * 2.1).toFixed(0)} ms`}
              variant="warning"
              size="sm"
            />
          </div>

          {/* Light Network Probe Toggle */}
          <div className="mt-3.5 pt-3 border-t border-border flex items-center justify-between text-xs">
            <RfToggleSwitch
              id="light-probe-toggle"
              checked={lightProbeEnabled}
              onChange={onToggleLightProbe}
              label={t.lightProbeTitle}
              size="sm"
              variant="accent"
            />
            <span className="font-mono text-text text-xs bg-surface-elevated px-2.5 py-1 rounded-lg border border-border">
              {lastLightProbe
                ? `Ping ${lastLightProbe.pingMs} ms · Jitter ${lastLightProbe.jitterMs} ms`
                : 'Standby'}
            </span>
          </div>
        </OpenUiCard>

        {/* Neighboring Cells Table */}
        <OpenUiCard
          title={`${t.neighborCells} (${currentRadio.neighborCells.length})`}
          icon={<Signal className="w-4 h-4" />}
          className="lg:col-span-7"
        >
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
        </OpenUiCard>

        {/* Live Rolling RSRP Strip Chart */}
        <OpenUiCard
          title={`Live RSRP Stream (Last ${recentMeasurements.length} Samples)`}
          icon={<Activity className="w-4 h-4" />}
          badge={
            <span className="text-xs font-mono text-text-muted">
              Range: -130 dBm to -60 dBm
            </span>
          }
          className="lg:col-span-5"
        >
          <div className="h-32 w-full">
            <svg viewBox="0 0 600 90" className="w-full h-full overflow-visible">
              <line x1="0" y1="20" x2="600" y2="20" stroke="var(--color-border)" strokeDasharray="4 4" />
              <line x1="0" y1="55" x2="600" y2="55" stroke="var(--color-border)" strokeDasharray="4 4" />
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
        </OpenUiCard>
      </div>

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

