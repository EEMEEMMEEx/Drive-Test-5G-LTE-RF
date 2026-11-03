import React, { useMemo, useState } from 'react';
import {
  Activity,
  AlertTriangle,
  ArrowDownRight,
  ArrowUpRight,
  BarChart3,
  CheckCircle2,
  Compass,
  Cpu,
  Download,
  Filter,
  GitCompare,
  Gauge,
  Layers,
  MapPin,
  Network,
  PauseCircle,
  Radio,
  RefreshCw,
  ShieldAlert,
  Sliders,
  Sparkles,
  TrendingUp,
  Wifi,
  Zap,
} from 'lucide-react';
import type {
  AdvancedMetricType,
  AdvancedViewMode,
  AnalyticsTabId,
  Campaign,
  ComparisonMetric,
  DiagnosticIssueType,
  DiagnosticThresholds,
  Measurement,
  SessionEvent,
  SpeedTestResult,
} from '../../types/drivetest';
import {
  buildAnalyticsSnapshot,
  compareCampaignMeasurements,
  getBandFrequencyLabel,
  getSignalColor,
  getTechnologyColor,
} from '../../services/rfAnalyticsEngine';
import {
  generateAdvancedEventsCsv,
  triggerFileDownload,
} from '../../services/exportService';
import type { TranslationDictionary } from '../../i18n/translations';
import { RfToggleSwitch } from '../common/RfToggleSwitch';

export interface AnalyticsViewProps {
  campaigns: Campaign[];
  selectedCampaignId: number;
  onSelectCampaign: (id: number) => void;
  measurements: Measurement[];
  allMeasurements: Measurement[];
  speedTests: SpeedTestResult[];
  sessionEvents: SessionEvent[];
  thresholds: DiagnosticThresholds;
  onUpdateThresholds: (next: DiagnosticThresholds) => void;
  t: TranslationDictionary;
}

const SUB_TABS: {
  id: AnalyticsTabId;
  key: keyof Pick<
    TranslationDictionary,
    | 'tabSignal'
    | 'tabCdf'
    | 'tabNetwork'
    | 'tabBands'
    | 'tabTrace'
    | 'tabSites'
    | 'tabDiagnostic'
    | 'tabAdvanced'
  >;
  icon: React.ComponentType<{ className?: string }>;
}[] = [
  { id: 'SIGNAL', key: 'tabSignal', icon: Activity },
  { id: 'CDF', key: 'tabCdf', icon: TrendingUp },
  { id: 'NETWORK', key: 'tabNetwork', icon: Network },
  { id: 'BANDS', key: 'tabBands', icon: Layers },
  { id: 'TRACE', key: 'tabTrace', icon: Compass },
  { id: 'SITES', key: 'tabSites', icon: Radio },
  { id: 'DIAGNOSTIC', key: 'tabDiagnostic', icon: ShieldAlert },
  { id: 'ADVANCED', key: 'tabAdvanced', icon: Sparkles },
];

const ISSUE_FILTER_OPTIONS: { type: DiagnosticIssueType | 'ALL'; label: string }[] = [
  { type: 'ALL', label: 'All Issues' },
  { type: 'WEAK_SIGNAL', label: 'Weak Signal' },
  { type: 'POOR_QUALITY', label: 'Poor Quality' },
  { type: 'FREQUENT_HANDOVERS', label: 'Handovers' },
  { type: 'LOW_THROUGHPUT', label: 'Low Throughput' },
  { type: 'HIGH_LOADED_LATENCY', label: 'High Latency' },
];

function formatTimeShort(ts: number): string {
  const d = new Date(ts);
  return d.toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

function getVerdictBadgeStyle(verdict: 'EXCELLENT' | 'GOOD' | 'FAIR' | 'CRITICAL'): string {
  switch (verdict) {
    case 'EXCELLENT':
      return 'bg-emerald-500/15 text-emerald-400 border-emerald-500/40';
    case 'GOOD':
      return 'bg-lime-500/15 text-lime-400 border-lime-500/40';
    case 'FAIR':
      return 'bg-amber-500/15 text-amber-400 border-amber-500/40';
    case 'CRITICAL':
      return 'bg-rose-500/15 text-rose-400 border-rose-500/40';
  }
}

export function AnalyticsView({
  campaigns,
  selectedCampaignId,
  onSelectCampaign,
  measurements,
  allMeasurements,
  speedTests,
  sessionEvents,
  thresholds,
  onUpdateThresholds,
  t,
}: AnalyticsViewProps) {
  const [activeTab, setActiveTab] = useState<AnalyticsTabId>('SIGNAL');
  const [comparisonCampaignId, setComparisonCampaignId] = useState<number | null>(null);
  const [comparisonMetric, setComparisonMetric] = useState<ComparisonMetric>('RSRP');
  const [hoveredTimeIndex, setHoveredTimeIndex] = useState<number | null>(null);
  const [hoveredTraceIndex, setHoveredTraceIndex] = useState<number | null>(null);
  const [issueFilter, setIssueFilter] = useState<DiagnosticIssueType | 'ALL'>('ALL');
  const [advancedViewMode, setAdvancedViewMode] = useState<AdvancedViewMode>('MAP');
  const [advancedMetric, setAdvancedMetric] = useState<AdvancedMetricType>('RSRP');
  const [anomaliesOnly, setAnomaliesOnly] = useState<boolean>(false);

  const activeCampaign = useMemo(
    () => campaigns.find((c) => c.id === selectedCampaignId) ?? campaigns[0] ?? null,
    [campaigns, selectedCampaignId]
  );

  const snapshot = useMemo(
    () => buildAnalyticsSnapshot(measurements, speedTests, sessionEvents, thresholds),
    [measurements, speedTests, sessionEvents, thresholds]
  );

  const referenceCampaigns = useMemo(
    () => campaigns.filter((c) => c.id !== selectedCampaignId),
    [campaigns, selectedCampaignId]
  );

  const comparisonResult = useMemo(() => {
    if (comparisonCampaignId === null) return null;
    const refMeasurements = allMeasurements.filter(
      (m) => m.campaignId === comparisonCampaignId
    );
    return compareCampaignMeasurements(measurements, refMeasurements, comparisonMetric);
  }, [comparisonCampaignId, allMeasurements, measurements, comparisonMetric]);

  const cdfPercentiles = useMemo(() => {
    const pts = snapshot.cdfRsrp;
    if (pts.length === 0) {
      return { p10: -110, p50: -95, p90: -80 };
    }
    const findAt = (target: number) => {
      const found = pts.find((p) => p.percentile >= target);
      return found ? found.rsrp : pts[pts.length - 1].rsrp;
    };
    return {
      p10: findAt(10),
      p50: findAt(50),
      p90: findAt(90),
    };
  }, [snapshot.cdfRsrp]);

  const traceBounds = useMemo(() => {
    if (measurements.length === 0) {
      return {
        minLat: 13.75,
        maxLat: 13.76,
        minLon: 100.5,
        maxLon: 100.51,
      };
    }
    let minLat = measurements[0].gps.latitude;
    let maxLat = measurements[0].gps.latitude;
    let minLon = measurements[0].gps.longitude;
    let maxLon = measurements[0].gps.longitude;
    for (const m of measurements) {
      if (m.gps.latitude < minLat) minLat = m.gps.latitude;
      if (m.gps.latitude > maxLat) maxLat = m.gps.latitude;
      if (m.gps.longitude < minLon) minLon = m.gps.longitude;
      if (m.gps.longitude > maxLon) maxLon = m.gps.longitude;
    }
    const padLat = Math.max(0.0005, (maxLat - minLat) * 0.12);
    const padLon = Math.max(0.0005, (maxLon - minLon) * 0.12);
    return {
      minLat: minLat - padLat,
      maxLat: maxLat + padLat,
      minLon: minLon - padLon,
      maxLon: maxLon + padLon,
    };
  }, [measurements]);

  const filteredIssues = useMemo(() => {
    if (issueFilter === 'ALL') return snapshot.diagnostic.issues;
    return snapshot.diagnostic.issues.filter((iss) => iss.type === issueFilter);
  }, [snapshot.diagnostic.issues, issueFilter]);

  const momentsToInvestigate = useMemo(() => {
    const weakSignalCount = measurements.filter(
      (m) => m.radio.rsrp < thresholds.weakRsrp
    ).length;
    const highPingProbeCount = sessionEvents.filter(
      (e) => e.type === 'LIGHT_PROBE' && e.pingMs > thresholds.highLoadedLatencyMs
    ).length;
    const pauseCount = sessionEvents.filter((e) => e.type === 'PAUSE').length;
    const cellChangeCount = snapshot.mobilityStats.handovers;
    return {
      weakSignalCount,
      highPingProbeCount,
      pauseCount,
      cellChangeCount,
    };
  }, [measurements, sessionEvents, thresholds, snapshot.mobilityStats.handovers]);

  const correlatedTimeline = useMemo(() => {
    interface TimelineRow {
      id: string;
      timestampMs: number;
      category: 'RADIO_SAMPLE' | 'HANDOVER' | 'LIGHT_PROBE' | 'PAUSE' | 'SPEED_TEST';
      title: string;
      detail: string;
      rsrp: number | null;
      pingMs: number | null;
      jitterMs: number | null;
      cellId: number;
      technology: string;
      isAnomaly: boolean;
      latitude: number;
      longitude: number;
    }

    const items: TimelineRow[] = [];

    for (const m of measurements) {
      const isWeak = m.radio.rsrp < thresholds.weakRsrp || m.radio.rsrq < thresholds.poorRsrq;
      items.push({
        id: `m_${m.id}`,
        timestampMs: m.timestampMs,
        category: 'RADIO_SAMPLE',
        title: `${m.radio.technology} Serving Sample · PCI ${m.radio.pci}`,
        detail: `RSRP ${m.radio.rsrp} dBm · RSRQ ${m.radio.rsrq} dB · SINR ${m.radio.sinr.toFixed(1)} dB (${getBandFrequencyLabel(
          m.radio.band,
          m.radio.technology === 'NR_5G',
          m.radio.dlFrequencyKhz
        )})`,
        rsrp: m.radio.rsrp,
        pingMs: null,
        jitterMs: null,
        cellId: m.radio.cellId,
        technology: m.radio.technology,
        isAnomaly: isWeak,
        latitude: m.gps.latitude,
        longitude: m.gps.longitude,
      });
    }

    for (const ho of snapshot.mobilityStats.handoverEvents) {
      items.push({
        id: ho.id,
        timestampMs: ho.measurement.timestampMs,
        category: 'HANDOVER',
        title: ho.isPingPong
          ? `Ping-Pong Handover (${ho.previousPci} → ${ho.newPci})`
          : `Cell Handover (${ho.previousPci} → ${ho.newPci})`,
        detail: `Cell ${ho.previousCellId} (${ho.previousTechnology}) → Cell ${ho.newCellId} (${ho.newTechnology}) at ${ho.measurement.radio.rsrp} dBm`,
        rsrp: ho.measurement.radio.rsrp,
        pingMs: null,
        jitterMs: null,
        cellId: ho.newCellId,
        technology: ho.newTechnology,
        isAnomaly: ho.isPingPong || ho.measurement.radio.rsrp < thresholds.weakRsrp,
        latitude: ho.measurement.gps.latitude,
        longitude: ho.measurement.gps.longitude,
      });
    }

    for (const ev of sessionEvents) {
      if (ev.type === 'PAUSE') {
        items.push({
          id: `ev_${ev.id}`,
          timestampMs: ev.timestampMs,
          category: 'PAUSE',
          title: 'Session Measurement Pause',
          detail: `Paused for ${(ev.durationMs / 1000).toFixed(1)}s near Cell ${ev.cellId}`,
          rsrp: null,
          pingMs: null,
          jitterMs: null,
          cellId: ev.cellId,
          technology: ev.technology,
          isAnomaly: true,
          latitude: ev.latitude,
          longitude: ev.longitude,
        });
      } else {
        const highPing = ev.pingMs > thresholds.highLoadedLatencyMs || !ev.success;
        items.push({
          id: `ev_${ev.id}`,
          timestampMs: ev.timestampMs,
          category: 'LIGHT_PROBE',
          title: `Light Probe · Ping ${ev.pingMs.toFixed(0)} ms`,
          detail: `Jitter ${ev.jitterMs.toFixed(1)} ms · Status ${ev.success ? 'OK' : 'TIMEOUT'} · Cell ${ev.cellId}`,
          rsrp: null,
          pingMs: ev.pingMs,
          jitterMs: ev.jitterMs,
          cellId: ev.cellId,
          technology: ev.technology,
          isAnomaly: highPing,
          latitude: ev.latitude,
          longitude: ev.longitude,
        });
      }
    }

    for (const st of speedTests) {
      const isBad =
        st.downloadMbps < thresholds.lowThroughputMbps ||
        st.loadedPingMs > thresholds.highLoadedLatencyMs;
      items.push({
        id: `st_${st.id}`,
        timestampMs: st.timestampMs,
        category: 'SPEED_TEST',
        title: `Speed Test · DL ${st.downloadMbps.toFixed(1)} Mbps / UL ${st.uploadMbps.toFixed(1)} Mbps`,
        detail: `Unloaded Ping ${st.pingMs.toFixed(0)} ms · Loaded ${st.loadedPingMs.toFixed(0)} ms · Jitter ${st.jitterMs.toFixed(1)} ms`,
        rsrp: st.rsrp,
        pingMs: st.loadedPingMs,
        jitterMs: st.jitterMs,
        cellId: st.cellId,
        technology: st.technology,
        isAnomaly: isBad,
        latitude: st.latitude,
        longitude: st.longitude,
      });
    }

    const sorted = items.sort((a, b) => a.timestampMs - b.timestampMs);
    return anomaliesOnly ? sorted.filter((item) => item.isAnomaly) : sorted;
  }, [
    measurements,
    snapshot.mobilityStats.handoverEvents,
    sessionEvents,
    speedTests,
    thresholds,
    anomaliesOnly,
  ]);

  const cellPerformanceMatrix = useMemo(() => {
    return snapshot.cellSites.map((site) => {
      const cellProbes = sessionEvents.filter(
        (e) => e.type === 'LIGHT_PROBE' && e.cellId === site.cellId
      );
      const cellTests = speedTests.filter((st) => st.cellId === site.cellId);
      const cellHandovers = snapshot.mobilityStats.handoverEvents.filter(
        (ho) => ho.newCellId === site.cellId || ho.previousCellId === site.cellId
      ).length;

      const avgPing =
        cellProbes.length > 0
          ? Number(
              (
                cellProbes.reduce((acc, p) => acc + p.pingMs, 0) / cellProbes.length
              ).toFixed(1)
            )
          : cellTests.length > 0
          ? Number(
              (
                cellTests.reduce((acc, st) => acc + st.pingMs, 0) / cellTests.length
              ).toFixed(1)
            )
          : null;

      const avgJitter =
        cellProbes.length > 0
          ? Number(
              (
                cellProbes.reduce((acc, p) => acc + p.jitterMs, 0) / cellProbes.length
              ).toFixed(1)
            )
          : cellTests.length > 0
          ? Number(
              (
                cellTests.reduce((acc, st) => acc + st.jitterMs, 0) / cellTests.length
              ).toFixed(1)
            )
          : null;

      const avgDlMbps =
        cellTests.length > 0
          ? Number(
              (
                cellTests.reduce((acc, st) => acc + st.downloadMbps, 0) / cellTests.length
              ).toFixed(1)
            )
          : null;

      const hasAnomaly =
        site.avgRsrp < thresholds.weakRsrp ||
        (avgPing !== null && avgPing > thresholds.highLoadedLatencyMs) ||
        (avgDlMbps !== null && avgDlMbps < thresholds.lowThroughputMbps);

      return {
        ...site,
        freqLabel: getBandFrequencyLabel(site.band, site.technology === 'NR_5G'),
        avgPing,
        avgJitter,
        avgDlMbps,
        cellHandovers,
        hasAnomaly,
      };
    });
  }, [
    snapshot.cellSites,
    snapshot.mobilityStats.handoverEvents,
    sessionEvents,
    speedTests,
    thresholds,
  ]);

  const handleExportAdvanced = () => {
    if (!activeCampaign) return;
    const csv = generateAdvancedEventsCsv(activeCampaign, speedTests, sessionEvents);
    const safeName = activeCampaign.name.toLowerCase().replace(/[^a-z0-9-_]+/g, '_');
    triggerFileDownload(`${safeName}_advanced_events.csv`, csv, 'text/csv;charset=utf-8');
  };

  return (
    <div className="space-y-4 pb-8">
      {/* Top Campaign Selector & Campaign Comparison Bar */}
      <section className="rounded-xl border border-slate-800 bg-slate-900/90 p-4 shadow-lg">
        <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex flex-wrap items-center gap-3">
            <div className="flex items-center gap-2">
              <BarChart3 className="h-5 w-5 text-sky-400" />
              <span className="text-sm font-semibold uppercase tracking-wider text-slate-300">
                {t.navAnalytics}
              </span>
            </div>

            <label className="flex items-center gap-2 text-xs text-slate-400">
              <span>Active Session:</span>
              <select
                aria-label="Select Active Campaign"
                value={selectedCampaignId}
                onChange={(e) => onSelectCampaign(Number(e.target.value))}
                className="rounded-lg border border-slate-700 bg-slate-950 px-3 py-1.5 text-sm font-medium text-slate-100 focus:border-sky-400 focus:outline-none"
              >
                {campaigns.length === 0 && (
                  <option value={0}>No Campaigns Available</option>
                )}
                {campaigns.map((camp) => (
                  <option key={camp.id} value={camp.id}>
                    {camp.name} ({camp.measurementCount} pts)
                  </option>
                ))}
              </select>
            </label>
          </div>

          {/* Campaign Comparison Selector */}
          <div className="flex flex-wrap items-center gap-2">
            <div className="flex items-center gap-1.5 text-xs font-medium text-slate-300">
              <GitCompare className="h-4 w-4 text-sky-400" />
              <span>{t.compareCampaigns}:</span>
            </div>

            <select
              aria-label="Select Reference Campaign"
              value={comparisonCampaignId ?? ''}
              onChange={(e) => {
                const val = e.target.value;
                setComparisonCampaignId(val === '' ? null : Number(val));
              }}
              className="rounded-lg border border-slate-700 bg-slate-950 px-2.5 py-1.5 text-xs text-slate-200 focus:border-sky-400 focus:outline-none"
            >
              <option value="">None (Single Route)</option>
              {referenceCampaigns.map((ref) => (
                <option key={ref.id} value={ref.id}>
                  vs. {ref.name} ({ref.measurementCount} pts)
                </option>
              ))}
            </select>

            {comparisonCampaignId !== null && (
              <div className="inline-flex rounded-lg border border-slate-700 bg-slate-950 p-0.5">
                {(['RSRP', 'RSRQ', 'SINR'] as ComparisonMetric[]).map((metric) => (
                  <button
                    key={metric}
                    type="button"
                    onClick={() => setComparisonMetric(metric)}
                    className={`rounded-md px-2.5 py-1 text-xs font-semibold transition ${
                      comparisonMetric === metric
                        ? 'bg-sky-500 text-slate-950'
                        : 'text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    {metric}
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>

        {/* Comparison Results Banner */}
        {comparisonResult && (
          <div className="mt-4 rounded-lg border border-slate-800 bg-slate-950/90 p-3">
            {comparisonResult.error === 'NOT_ENOUGH_DATA' && (
              <div className="flex items-center gap-2 text-xs text-amber-400">
                <AlertTriangle className="h-4 w-4 shrink-0" />
                <span>
                  Not enough measurements in one of the selected campaigns to compute spatial delta (minimum 2 points required).
                </span>
              </div>
            )}
            {comparisonResult.error === 'NO_OVERLAP' && (
              <div className="flex items-center gap-2 text-xs text-amber-400">
                <AlertTriangle className="h-4 w-4 shrink-0" />
                <span>
                  No geographic overlap found within 120 m between the active campaign and reference campaign.
                </span>
              </div>
            )}
            {comparisonResult.error === null && (
              <div className="space-y-3">
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                  <div className="rounded-lg border border-slate-800/80 bg-slate-900/60 p-2.5">
                    <div className="text-[11px] uppercase text-slate-400">
                      Mean {comparisonMetric} Delta
                    </div>
                    <div
                      className={`mt-0.5 flex items-center gap-1 text-lg font-bold ${
                        comparisonResult.avgDelta >= 0 ? 'text-emerald-400' : 'text-rose-400'
                      }`}
                    >
                      {comparisonResult.avgDelta >= 0 ? (
                        <ArrowUpRight className="h-4 w-4" />
                      ) : (
                        <ArrowDownRight className="h-4 w-4" />
                      )}
                      <span>
                        {comparisonResult.avgDelta > 0 ? '+' : ''}
                        {comparisonResult.avgDelta} dB
                      </span>
                    </div>
                  </div>

                  <div className="rounded-lg border border-slate-800/80 bg-slate-900/60 p-2.5">
                    <div className="text-[11px] uppercase text-slate-400">Improved Zones</div>
                    <div className="mt-0.5 text-lg font-bold text-emerald-400">
                      {comparisonResult.improvedPercent}%
                    </div>
                  </div>

                  <div className="rounded-lg border border-slate-800/80 bg-slate-900/60 p-2.5">
                    <div className="text-[11px] uppercase text-slate-400">Degraded Zones</div>
                    <div className="mt-0.5 text-lg font-bold text-rose-400">
                      {comparisonResult.degradedPercent}%
                    </div>
                  </div>

                  <div className="rounded-lg border border-slate-800/80 bg-slate-900/60 p-2.5">
                    <div className="text-[11px] uppercase text-slate-400">Spatial Matches</div>
                    <div className="mt-0.5 text-lg font-bold text-sky-400">
                      {comparisonResult.matchedCount} / {comparisonResult.totalCount} pts
                    </div>
                  </div>
                </div>

                {/* Spatial Delta Sample Bars */}
                <div className="rf-table-container max-h-44 rounded-lg border border-slate-800">
                  <table className="rf-data-table w-full text-left text-xs">
                    <thead>
                      <tr className="border-b border-slate-800 text-[11px] uppercase text-slate-400">
                        <th className="px-3 py-2">GPS Coordinate</th>
                        <th className="px-3 py-2">Active ({comparisonMetric})</th>
                        <th className="px-3 py-2">Reference ({comparisonMetric})</th>
                        <th className="px-3 py-2">Spatial Delta</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-800/60">
                      {comparisonResult.points.slice(0, 12).map((pt, idx) => (
                        <tr key={idx} className="hover:bg-slate-900/50">
                          <td className="px-3 py-1.5 font-mono text-slate-300">
                            {pt.latitude.toFixed(5)}, {pt.longitude.toFixed(5)}
                          </td>
                          <td className="px-3 py-1.5 font-mono text-slate-200">
                            {pt.activeValue}
                          </td>
                          <td className="px-3 py-1.5 font-mono text-slate-400">
                            {pt.referenceValue}
                          </td>
                          <td className="px-3 py-1.5">
                            <div className="flex items-center gap-2">
                              <span
                                className={`font-mono font-semibold ${
                                  pt.delta > 0
                                    ? 'text-emerald-400'
                                    : pt.delta < 0
                                    ? 'text-rose-400'
                                    : 'text-slate-400'
                                }`}
                              >
                                {pt.delta > 0 ? `+${pt.delta}` : pt.delta} dB
                              </span>
                              <div className="h-1.5 w-20 overflow-hidden rounded-full bg-slate-800">
                                <div
                                  className={`h-full ${
                                    pt.delta >= 0 ? 'bg-emerald-500' : 'bg-rose-500'
                                  }`}
                                  style={{
                                    width: `${Math.min(100, Math.abs(pt.delta) * 5)}%`,
                                  }}
                                />
                              </div>
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </div>
        )}

        {/* 8 Sub-Tabs Navigation */}
        <div
          role="tablist"
          aria-label="Analytics Sub-Tabs"
          className="mt-4 flex flex-wrap gap-1.5 border-t border-slate-800 pt-3"
        >
          {SUB_TABS.map((tab) => {
            const Icon = tab.icon;
            const isSelected = activeTab === tab.id;
            return (
              <button
                key={tab.id}
                role="tab"
                aria-selected={isSelected}
                type="button"
                onClick={() => setActiveTab(tab.id)}
                className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-2 text-xs font-semibold transition ${
                  isSelected
                    ? 'bg-sky-500 text-slate-950 shadow-sm'
                    : 'bg-slate-950/70 text-slate-300 hover:bg-slate-800 hover:text-white'
                }`}
              >
                <Icon className="h-3.5 w-3.5" />
                <span>{t[tab.key]}</span>
              </button>
            );
          })}
        </div>
      </section>

      {/* TAB 1: SIGNAL */}
      {activeTab === 'SIGNAL' && (
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-7">
            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-3">
              <div className="text-[11px] uppercase text-slate-400">Avg RSRP</div>
              <div className="mt-1 font-mono text-xl font-bold text-sky-400">
                {snapshot.signalStats.avgRsrp} <span className="text-xs font-normal">dBm</span>
              </div>
            </div>
            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-3">
              <div className="text-[11px] uppercase text-slate-400">Min RSRP</div>
              <div className="mt-1 font-mono text-xl font-bold text-rose-400">
                {snapshot.signalStats.minRsrp} <span className="text-xs font-normal">dBm</span>
              </div>
            </div>
            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-3">
              <div className="text-[11px] uppercase text-slate-400">Max RSRP</div>
              <div className="mt-1 font-mono text-xl font-bold text-emerald-400">
                {snapshot.signalStats.maxRsrp} <span className="text-xs font-normal">dBm</span>
              </div>
            </div>
            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-3">
              <div className="text-[11px] uppercase text-slate-400">Avg RSRQ</div>
              <div className="mt-1 font-mono text-xl font-bold text-amber-400">
                {snapshot.signalStats.avgRsrq} <span className="text-xs font-normal">dB</span>
              </div>
            </div>
            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-3">
              <div className="text-[11px] uppercase text-slate-400">Avg SINR</div>
              <div className="mt-1 font-mono text-xl font-bold text-purple-400">
                {snapshot.signalStats.avgSinr} <span className="text-xs font-normal">dB</span>
              </div>
            </div>
            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-3">
              <div className="text-[11px] uppercase text-slate-400">Coverage</div>
              <div className="mt-1 font-mono text-xl font-bold text-emerald-400">
                {snapshot.signalStats.coveragePercent}%
              </div>
            </div>
            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-3">
              <div className="text-[11px] uppercase text-slate-400">Samples</div>
              <div className="mt-1 font-mono text-xl font-bold text-slate-100">
                {snapshot.signalStats.totalMeasurements}
              </div>
            </div>
          </div>

          {/* Interactive RSRP & RSRQ Over Time Chart */}
          <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-4">
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <Activity className="h-4 w-4 text-sky-400" />
                <h3 className="text-sm font-semibold text-slate-100">
                  RSRP & RSRQ Telemetry Over Time
                </h3>
              </div>
              <div className="flex items-center gap-4 text-xs">
                <span className="inline-flex items-center gap-1.5 text-sky-400">
                  <span className="h-2.5 w-2.5 rounded-full bg-sky-400" />
                  RSRP (dBm)
                </span>
                <span className="inline-flex items-center gap-1.5 text-amber-400">
                  <span className="h-2.5 w-2.5 rounded-full bg-amber-400" />
                  RSRQ (dB)
                </span>
              </div>
            </div>

            {snapshot.timeSeries.length === 0 ? (
              <div className="py-12 text-center text-xs text-slate-400">
                No time-series radio samples recorded for this campaign.
              </div>
            ) : (
              <div>
                <svg
                  viewBox="0 0 760 230"
                  className="h-56 w-full select-none overflow-visible rounded-lg bg-slate-950/80 p-2"
                >
                  {/* Horizontal Reference Lines */}
                  {[-60, -80, -100, -120].map((dbm) => {
                    const y = 20 + ((-50 - dbm) / 90) * 180;
                    return (
                      <g key={dbm}>
                        <line
                          x1={45}
                          y1={y}
                          x2={740}
                          y2={y}
                          stroke="#1e293b"
                          strokeDasharray="3 3"
                        />
                        <text
                          x={8}
                          y={y + 4}
                          fill="#64748b"
                          fontSize="10"
                          fontFamily="monospace"
                        >
                          {dbm}
                        </text>
                      </g>
                    );
                  })}

                  {/* RSRP Polyline */}
                  <polyline
                    fill="none"
                    stroke="#38bdf8"
                    strokeWidth="2.2"
                    points={snapshot.timeSeries
                      .map((pt, idx) => {
                        const x =
                          45 +
                          (idx / Math.max(1, snapshot.timeSeries.length - 1)) * 695;
                        const clampedRsrp = Math.max(-140, Math.min(-50, pt.rsrp));
                        const y = 20 + ((-50 - clampedRsrp) / 90) * 180;
                        return `${x.toFixed(1)},${y.toFixed(1)}`;
                      })
                      .join(' ')}
                  />

                  {/* RSRQ Polyline (scaled -3 to -25 dB) */}
                  <polyline
                    fill="none"
                    stroke="#f59e0b"
                    strokeWidth="1.8"
                    strokeDasharray="4 2"
                    points={snapshot.timeSeries
                      .map((pt, idx) => {
                        const x =
                          45 +
                          (idx / Math.max(1, snapshot.timeSeries.length - 1)) * 695;
                        const clampedRsrq = Math.max(-25, Math.min(-3, pt.rsrq));
                        const y = 20 + ((-3 - clampedRsrq) / 22) * 180;
                        return `${x.toFixed(1)},${y.toFixed(1)}`;
                      })
                      .join(' ')}
                  />

                  {/* Interactive Sample Points */}
                  {snapshot.timeSeries.map((pt, idx) => {
                    const x =
                      45 +
                      (idx / Math.max(1, snapshot.timeSeries.length - 1)) * 695;
                    const clampedRsrp = Math.max(-140, Math.min(-50, pt.rsrp));
                    const y = 20 + ((-50 - clampedRsrp) / 90) * 180;
                    const isHovered = hoveredTimeIndex === idx;
                    return (
                      <circle
                        key={pt.timestampMs}
                        cx={x}
                        cy={y}
                        r={isHovered ? 5 : 3}
                        fill={isHovered ? '#ffffff' : '#38bdf8'}
                        className="cursor-pointer"
                        onMouseEnter={() => setHoveredTimeIndex(idx)}
                      />
                    );
                  })}
                </svg>

                {hoveredTimeIndex !== null && snapshot.timeSeries[hoveredTimeIndex] && (
                  <div className="mt-2 flex flex-wrap items-center justify-between gap-2 rounded-lg border border-slate-800 bg-slate-950 px-3 py-2 text-xs">
                    <span className="font-mono text-slate-300">
                      t = +{(snapshot.timeSeries[hoveredTimeIndex].relativeMs / 1000).toFixed(1)}s (
                      {formatTimeShort(snapshot.timeSeries[hoveredTimeIndex].timestampMs)})
                    </span>
                    <span className="font-mono font-semibold text-sky-400">
                      RSRP: {snapshot.timeSeries[hoveredTimeIndex].rsrp} dBm
                    </span>
                    <span className="font-mono font-semibold text-amber-400">
                      RSRQ: {snapshot.timeSeries[hoveredTimeIndex].rsrq} dB
                    </span>
                    <span className="font-mono text-purple-400">
                      SINR: {snapshot.timeSeries[hoveredTimeIndex].sinr.toFixed(1)} dB
                    </span>
                    <span className="font-mono text-slate-400">
                      PCI {snapshot.timeSeries[hoveredTimeIndex].pci} · Cell{' '}
                      {snapshot.timeSeries[hoveredTimeIndex].cellId}
                    </span>
                  </div>
                )}
              </div>
            )}
          </div>

          {/* Measured Throughput (SpeedTests) */}
          <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-4">
            <div className="mb-3 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Gauge className="h-4 w-4 text-emerald-400" />
                <h3 className="text-sm font-semibold text-slate-100">
                  {t.speedTestTitle} ({speedTests.length})
                </h3>
              </div>
            </div>

            {speedTests.length === 0 ? (
              <div className="py-6 text-center text-xs text-slate-400">
                No active speed test measurements recorded in this campaign.
              </div>
            ) : (
              <div className="rf-table-container rounded-lg border border-slate-800">
                <table className="rf-data-table w-full text-left text-xs">
                  <thead>
                    <tr className="border-b border-slate-800 text-[11px] uppercase text-slate-400">
                      <th className="px-3 py-2">Time</th>
                      <th className="px-3 py-2">Tech / Band</th>
                      <th className="px-3 py-2">Download</th>
                      <th className="px-3 py-2">Upload</th>
                      <th className="px-3 py-2">Ping</th>
                      <th className="px-3 py-2">Loaded Ping</th>
                      <th className="px-3 py-2">RSRP</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-800/60">
                    {speedTests.map((st) => (
                      <tr key={st.id} className="hover:bg-slate-950/60">
                        <td className="px-3 py-2 font-mono text-slate-300">
                          {formatTimeShort(st.timestampMs)}
                        </td>
                        <td className="px-3 py-2 font-medium text-slate-200">
                          {st.technology} ·{' '}
                          {getBandFrequencyLabel(st.band, st.technology === 'NR_5G')}
                        </td>
                        <td className="px-3 py-2 font-mono font-semibold text-emerald-400">
                          {st.downloadMbps.toFixed(1)} Mbps
                        </td>
                        <td className="px-3 py-2 font-mono font-semibold text-sky-400">
                          {st.uploadMbps.toFixed(1)} Mbps
                        </td>
                        <td className="px-3 py-2 font-mono text-slate-200">
                          {st.pingMs.toFixed(0)} ms
                        </td>
                        <td
                          className={`px-3 py-2 font-mono font-semibold ${
                            st.loadedPingMs > thresholds.highLoadedLatencyMs
                              ? 'text-rose-400'
                              : 'text-amber-300'
                          }`}
                        >
                          {st.loadedPingMs.toFixed(0)} ms
                        </td>
                        <td className="px-3 py-2 font-mono text-slate-300">
                          {st.rsrp} dBm
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}

      {/* TAB 2: CDF */}
      {activeTab === 'CDF' && (
        <div className="space-y-4">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-3.5">
              <div className="text-xs text-slate-400">P10 (Cell Edge / Weakest 10%)</div>
              <div className="mt-1 font-mono text-2xl font-bold text-rose-400">
                {cdfPercentiles.p10} dBm
              </div>
            </div>
            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-3.5">
              <div className="text-xs text-slate-400">P50 (Median Route RSRP)</div>
              <div className="mt-1 font-mono text-2xl font-bold text-sky-400">
                {cdfPercentiles.p50} dBm
              </div>
            </div>
            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-3.5">
              <div className="text-xs text-slate-400">P90 (Peak 10% Signal)</div>
              <div className="mt-1 font-mono text-2xl font-bold text-emerald-400">
                {cdfPercentiles.p90} dBm
              </div>
            </div>
          </div>

          <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-4">
            <div className="mb-3 flex items-center gap-2">
              <TrendingUp className="h-4 w-4 text-sky-400" />
              <h3 className="text-sm font-semibold text-slate-100">
                Cumulative Distribution Function (CDF) — RSRP (dBm)
              </h3>
            </div>

            {snapshot.cdfRsrp.length === 0 ? (
              <div className="py-12 text-center text-xs text-slate-400">
                No valid RSRP samples available to build CDF curve.
              </div>
            ) : (
              <svg
                viewBox="0 0 760 250"
                className="h-60 w-full select-none rounded-lg bg-slate-950/80 p-2"
              >
                {[10, 50, 90].map((pct) => {
                  const y = 210 - (pct / 100) * 185;
                  return (
                    <g key={pct}>
                      <line
                        x1={50}
                        y1={y}
                        x2={730}
                        y2={y}
                        stroke="#1e293b"
                        strokeDasharray="4 4"
                      />
                      <text
                        x={10}
                        y={y + 4}
                        fill="#94a3b8"
                        fontSize="10"
                        fontFamily="monospace"
                      >
                        P{pct}
                      </text>
                    </g>
                  );
                })}

                <polyline
                  fill="none"
                  stroke="#38bdf8"
                  strokeWidth="2.5"
                  points={snapshot.cdfRsrp
                    .map((pt) => {
                      const clamped = Math.max(-135, Math.min(-55, pt.rsrp));
                      const x = 50 + ((clamped + 135) / 80) * 680;
                      const y = 210 - (pt.percentile / 100) * 185;
                      return `${x.toFixed(1)},${y.toFixed(1)}`;
                    })
                    .join(' ')}
                />

                {[
                  { label: 'P10', val: cdfPercentiles.p10, pct: 10, color: '#f87171' },
                  { label: 'P50', val: cdfPercentiles.p50, pct: 50, color: '#38bdf8' },
                  { label: 'P90', val: cdfPercentiles.p90, pct: 90, color: '#4ade80' },
                ].map((m) => {
                  const clamped = Math.max(-135, Math.min(-55, m.val));
                  const x = 50 + ((clamped + 135) / 80) * 680;
                  const y = 210 - (m.pct / 100) * 185;
                  return (
                    <g key={m.label}>
                      <circle cx={x} cy={y} r={5} fill={m.color} />
                      <text
                        x={x + 8}
                        y={y - 6}
                        fill={m.color}
                        fontSize="11"
                        fontWeight="bold"
                        fontFamily="monospace"
                      >
                        {m.label}: {m.val} dBm
                      </text>
                    </g>
                  );
                })}
              </svg>
            )}
          </div>
        </div>
      )}

      {/* TAB 3: NETWORK */}
      {activeTab === 'NETWORK' && (
        <div className="space-y-4">
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
            {/* 5G NR Usage Gauge */}
            <div className="flex flex-col items-center justify-center rounded-xl border border-slate-800 bg-slate-900/90 p-5">
              <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-purple-300">
                <Wifi className="h-4 w-4 text-purple-400" />
                <span>5G NR Usage (SA + NSA)</span>
              </div>
              <div className="mt-3 font-mono text-4xl font-extrabold text-purple-400">
                {snapshot.nrUsagePercent}%
              </div>
              <div className="mt-3 h-2.5 w-full overflow-hidden rounded-full bg-slate-800">
                <div
                  className="h-full bg-purple-500 transition-all"
                  style={{ width: `${Math.min(100, snapshot.nrUsagePercent)}%` }}
                />
              </div>
            </div>

            {/* Horizontal Stacked Distribution Bar & Cards */}
            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-5 lg:col-span-2">
              <h3 className="text-sm font-semibold text-slate-100">
                Radio Access Technology Breakdown
              </h3>
              <div className="mt-3 flex h-5 w-full overflow-hidden rounded-lg bg-slate-950">
                {snapshot.techBreakdown.map((item) => (
                  <div
                    key={item.technology}
                    style={{
                      width: `${item.percent}%`,
                      backgroundColor: getTechnologyColor(item.technology),
                    }}
                    title={`${item.technology}: ${item.percent}%`}
                  />
                ))}
              </div>

              <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
                {snapshot.techBreakdown.map((item) => (
                  <div
                    key={item.technology}
                    className="rounded-lg border border-slate-800 bg-slate-950/70 p-3"
                  >
                    <div className="flex items-center gap-2">
                      <span
                        className="h-2.5 w-2.5 rounded-full"
                        style={{ backgroundColor: getTechnologyColor(item.technology) }}
                      />
                      <span className="text-xs font-semibold text-slate-200">
                        {item.technology}
                      </span>
                    </div>
                    <div className="mt-1 font-mono text-lg font-bold text-slate-100">
                      {item.percent}%
                    </div>
                    <div className="text-[11px] text-slate-400">{item.count} samples</div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      )}

      {/* TAB 4: BANDS */}
      {activeTab === 'BANDS' && (
        <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-4">
          <div className="mb-3 flex items-center gap-2">
            <Layers className="h-4 w-4 text-sky-400" />
            <h3 className="text-sm font-semibold text-slate-100">
              Frequency Band Distribution
            </h3>
          </div>

          <div className="space-y-3">
            {snapshot.bandUsage.map((b) => (
              <div
                key={`${b.isNr ? 'NR' : 'LTE'}_${b.band}`}
                className="rounded-lg border border-slate-800 bg-slate-950/70 p-3"
              >
                <div className="flex items-center justify-between text-xs">
                  <div className="flex items-center gap-2">
                    <span
                      className={`rounded px-2 py-0.5 font-mono text-[11px] font-bold ${
                        b.isNr
                          ? 'bg-purple-500/20 text-purple-300'
                          : 'bg-sky-500/20 text-sky-300'
                      }`}
                    >
                      {b.isNr ? '5G NR' : 'LTE'}
                    </span>
                    <span className="font-semibold text-slate-100">{b.freqLabel}</span>
                  </div>
                  <div className="font-mono text-slate-300">
                    {b.count} pts ({b.percent}%)
                  </div>
                </div>
                <div className="mt-2 h-2 w-full overflow-hidden rounded-full bg-slate-800">
                  <div
                    className={`h-full ${b.isNr ? 'bg-purple-500' : 'bg-sky-500'}`}
                    style={{ width: `${Math.min(100, b.percent)}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* TAB 5: TRACE */}
      {activeTab === 'TRACE' && (
        <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-4">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <Compass className="h-4 w-4 text-sky-400" />
              <h3 className="text-sm font-semibold text-slate-100">
                2D GPS Route Signal Trace (RSRP Heatmap)
              </h3>
            </div>
            <div className="flex items-center gap-3 text-xs">
              <span className="inline-flex items-center gap-1 text-emerald-400">
                <span className="h-2.5 w-2.5 rounded-full bg-emerald-500" />
                &ge; -90 dBm
              </span>
              <span className="inline-flex items-center gap-1 text-lime-400">
                <span className="h-2.5 w-2.5 rounded-full bg-lime-500" />
                -90 to -100 dBm
              </span>
              <span className="inline-flex items-center gap-1 text-amber-400">
                <span className="h-2.5 w-2.5 rounded-full bg-amber-500" />
                -100 to -110 dBm
              </span>
              <span className="inline-flex items-center gap-1 text-rose-400">
                <span className="h-2.5 w-2.5 rounded-full bg-rose-500" />
                &lt; -110 dBm
              </span>
            </div>
          </div>

          <svg
            viewBox="0 0 760 320"
            className="h-72 w-full rounded-lg border border-slate-800 bg-slate-950 p-2"
          >
            {/* Polyline connecting route */}
            <polyline
              fill="none"
              stroke="#334155"
              strokeWidth="2"
              points={measurements
                .map((m) => {
                  const x =
                    40 +
                    ((m.gps.longitude - traceBounds.minLon) /
                      Math.max(0.0001, traceBounds.maxLon - traceBounds.minLon)) *
                      680;
                  const y =
                    290 -
                    ((m.gps.latitude - traceBounds.minLat) /
                      Math.max(0.0001, traceBounds.maxLat - traceBounds.minLat)) *
                      260;
                  return `${x.toFixed(1)},${y.toFixed(1)}`;
                })
                .join(' ')}
            />

            {measurements.map((m, idx) => {
              const x =
                40 +
                ((m.gps.longitude - traceBounds.minLon) /
                  Math.max(0.0001, traceBounds.maxLon - traceBounds.minLon)) *
                  680;
              const y =
                290 -
                ((m.gps.latitude - traceBounds.minLat) /
                  Math.max(0.0001, traceBounds.maxLat - traceBounds.minLat)) *
                  260;
              const color = getSignalColor(m.radio.rsrp, 'BY_RSRP', 'CLASSIC');
              const isHovered = hoveredTraceIndex === idx;
              return (
                <circle
                  key={m.id}
                  cx={x}
                  cy={y}
                  r={isHovered ? 7 : 4.5}
                  fill={color}
                  stroke="#0f172a"
                  strokeWidth="1.2"
                  className="cursor-pointer"
                  onMouseEnter={() => setHoveredTraceIndex(idx)}
                />
              );
            })}
          </svg>

          {hoveredTraceIndex !== null && measurements[hoveredTraceIndex] && (
            <div className="mt-2 flex flex-wrap items-center justify-between gap-2 rounded-lg border border-slate-800 bg-slate-950 px-3 py-2 text-xs">
              <span className="font-mono text-slate-300">
                <MapPin className="mr-1 inline h-3.5 w-3.5 text-sky-400" />
                {measurements[hoveredTraceIndex].gps.latitude.toFixed(5)},{' '}
                {measurements[hoveredTraceIndex].gps.longitude.toFixed(5)}
              </span>
              <span className="font-mono font-semibold text-sky-400">
                RSRP: {measurements[hoveredTraceIndex].radio.rsrp} dBm
              </span>
              <span className="font-mono text-amber-400">
                RSRQ: {measurements[hoveredTraceIndex].radio.rsrq} dB
              </span>
              <span className="font-mono text-slate-300">
                {measurements[hoveredTraceIndex].radio.technology} · PCI{' '}
                {measurements[hoveredTraceIndex].radio.pci} · Cell{' '}
                {measurements[hoveredTraceIndex].radio.cellId}
              </span>
            </div>
          )}
        </div>
      )}

      {/* TAB 6: SITES & MOBILITY */}
      {activeTab === 'SITES' && (
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-3">
              <div className="text-[11px] uppercase text-slate-400">Handovers</div>
              <div className="mt-1 font-mono text-2xl font-bold text-sky-400">
                {snapshot.mobilityStats.handovers}
              </div>
            </div>
            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-3">
              <div className="text-[11px] uppercase text-slate-400">Ping-Pong (&lt;30s)</div>
              <div className="mt-1 font-mono text-2xl font-bold text-amber-400">
                {snapshot.mobilityStats.pingPong}
              </div>
            </div>
            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-3">
              <div className="text-[11px] uppercase text-slate-400">Distinct Cells</div>
              <div className="mt-1 font-mono text-2xl font-bold text-emerald-400">
                {snapshot.mobilityStats.distinctCells}
              </div>
            </div>
            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-3">
              <div className="text-[11px] uppercase text-slate-400">Missed Handovers</div>
              <div className="mt-1 font-mono text-2xl font-bold text-rose-400">
                {snapshot.mobilityStats.missedHandovers}
              </div>
            </div>
          </div>

          {/* Detected Cell Sites Table */}
          <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-4">
            <h3 className="mb-3 text-sm font-semibold text-slate-100">
              Detected Serving Cell Sites ({snapshot.cellSites.length})
            </h3>
            <div className="rf-table-container rounded-lg border border-slate-800">
              <table className="rf-data-table w-full text-left text-xs">
                <thead>
                  <tr className="border-b border-slate-800 text-[11px] uppercase text-slate-400">
                    <th className="px-3 py-2">Cell ID</th>
                    <th className="px-3 py-2">PCI</th>
                    <th className="px-3 py-2">Technology</th>
                    <th className="px-3 py-2">Band</th>
                    <th className="px-3 py-2">EARFCN/NR-ARFCN</th>
                    <th className="px-3 py-2">Samples</th>
                    <th className="px-3 py-2">Avg RSRP</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-800/60">
                  {snapshot.cellSites.map((site) => (
                    <tr key={site.cellId} className="hover:bg-slate-950/60">
                      <th className="px-3 py-2 font-mono font-semibold text-sky-400">
                        {site.cellId}
                      </th>
                      <td className="px-3 py-2 font-mono text-slate-200">{site.pci}</td>
                      <td className="px-3 py-2 text-slate-200">{site.technology}</td>
                      <td className="px-3 py-2 text-slate-300">
                        {getBandFrequencyLabel(site.band, site.technology === 'NR_5G')}
                      </td>
                      <td className="px-3 py-2 font-mono text-slate-400">{site.earfcn}</td>
                      <td className="px-3 py-2 font-mono text-slate-200">{site.count}</td>
                      <td className="px-3 py-2 font-mono font-semibold text-emerald-400">
                        {site.avgRsrp} dBm
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          {/* Handover & Missed Handover Logs */}
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-4">
              <div className="mb-2 flex items-center gap-2">
                <RefreshCw className="h-4 w-4 text-sky-400" />
                <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-200">
                  Handover Event Log ({snapshot.mobilityStats.handoverEvents.length})
                </h4>
              </div>
              <div className="max-h-52 space-y-2 overflow-y-auto pr-1">
                {snapshot.mobilityStats.handoverEvents.map((ho) => (
                  <div
                    key={ho.id}
                    className="flex items-center justify-between rounded-lg border border-slate-800 bg-slate-950/70 px-3 py-2 text-xs"
                  >
                    <div>
                      <span className="font-mono font-semibold text-slate-200">
                        Cell {ho.previousCellId} (PCI {ho.previousPci}) → Cell {ho.newCellId} (PCI{' '}
                        {ho.newPci})
                      </span>
                      <div className="text-[11px] text-slate-400">
                        {formatTimeShort(ho.measurement.timestampMs)} ·{' '}
                        {ho.measurement.radio.rsrp} dBm
                      </div>
                    </div>
                    {ho.isPingPong && (
                      <span className="rounded bg-amber-500/20 px-2 py-0.5 text-[10px] font-bold text-amber-300">
                        PING-PONG
                      </span>
                    )}
                  </div>
                ))}
              </div>
            </div>

            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-4">
              <div className="mb-2 flex items-center gap-2">
                <AlertTriangle className="h-4 w-4 text-rose-400" />
                <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-200">
                  Missed Handover Candidates ({snapshot.mobilityStats.missedHandoverEvents.length})
                </h4>
              </div>
              <div className="max-h-52 space-y-2 overflow-y-auto pr-1">
                {snapshot.mobilityStats.missedHandoverEvents.map((mho) => (
                  <div
                    key={mho.id}
                    className="flex items-center justify-between rounded-lg border border-slate-800 bg-slate-950/70 px-3 py-2 text-xs"
                  >
                    <div>
                      <span className="font-mono font-semibold text-rose-300">
                        Serving PCI {mho.servingPci} ({mho.servingRsrp} dBm) vs Neighbor PCI{' '}
                        {mho.neighborPci} ({mho.neighborRsrp} dBm)
                      </span>
                      <div className="text-[11px] text-slate-400">
                        {formatTimeShort(mho.measurement.timestampMs)} · Neighbor{' '}
                        {mho.neighborTechnology}
                      </div>
                    </div>
                    <span className="rounded bg-rose-500/20 px-2 py-0.5 font-mono text-[11px] font-bold text-rose-300">
                      +{mho.marginDb} dB
                    </span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      )}

      {/* TAB 7: DIAGNOSTIC */}
      {activeTab === 'DIAGNOSTIC' && (
        <div className="space-y-4">
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
            {/* Circular Route Score & Verdict */}
            <div className="flex flex-col items-center justify-center rounded-xl border border-slate-800 bg-slate-900/90 p-5">
              <svg viewBox="0 0 120 120" className="h-32 w-32">
                <circle
                  cx="60"
                  cy="60"
                  r="50"
                  fill="none"
                  stroke="#1e293b"
                  strokeWidth="10"
                />
                <circle
                  cx="60"
                  cy="60"
                  r="50"
                  fill="none"
                  stroke="#38bdf8"
                  strokeWidth="10"
                  strokeLinecap="round"
                  strokeDasharray={`${(snapshot.diagnostic.score / 100) * 314} 314`}
                  transform="rotate(-90 60 60)"
                />
                <text
                  x="60"
                  y="58"
                  textAnchor="middle"
                  fill="#f8fafc"
                  fontSize="24"
                  fontWeight="bold"
                  fontFamily="monospace"
                >
                  {snapshot.diagnostic.score}
                </text>
                <text x="60" y="76" textAnchor="middle" fill="#94a3b8" fontSize="10">
                  / 100
                </text>
              </svg>
              <span
                className={`mt-2 rounded-full border px-3 py-1 text-xs font-bold ${getVerdictBadgeStyle(
                  snapshot.diagnostic.verdict
                )}`}
              >
                {snapshot.diagnostic.verdict}
              </span>
            </div>

            {/* Interactive Threshold Sliders */}
            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-4 lg:col-span-2">
              <div className="mb-3 flex items-center gap-2">
                <Sliders className="h-4 w-4 text-sky-400" />
                <h3 className="text-sm font-semibold text-slate-100">
                  Diagnostic Threshold Configuration
                </h3>
              </div>

              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <label className="block rounded-lg border border-slate-800 bg-slate-950/70 p-3 text-xs">
                  <div className="flex justify-between text-slate-300">
                    <span>Weak RSRP Threshold</span>
                    <span className="font-mono font-bold text-sky-400">
                      {thresholds.weakRsrp} dBm
                    </span>
                  </div>
                  <input
                    type="range"
                    min={-125}
                    max={-85}
                    step={1}
                    value={thresholds.weakRsrp}
                    onChange={(e) =>
                      onUpdateThresholds({
                        ...thresholds,
                        weakRsrp: Number(e.target.value),
                      })
                    }
                    className="mt-2 w-full accent-sky-400"
                  />
                </label>

                <label className="block rounded-lg border border-slate-800 bg-slate-950/70 p-3 text-xs">
                  <div className="flex justify-between text-slate-300">
                    <span>Poor RSRQ Threshold</span>
                    <span className="font-mono font-bold text-amber-400">
                      {thresholds.poorRsrq} dB
                    </span>
                  </div>
                  <input
                    type="range"
                    min={-22}
                    max={-8}
                    step={1}
                    value={thresholds.poorRsrq}
                    onChange={(e) =>
                      onUpdateThresholds({
                        ...thresholds,
                        poorRsrq: Number(e.target.value),
                      })
                    }
                    className="mt-2 w-full accent-amber-400"
                  />
                </label>

                <label className="block rounded-lg border border-slate-800 bg-slate-950/70 p-3 text-xs">
                  <div className="flex justify-between text-slate-300">
                    <span>Low Throughput Floor</span>
                    <span className="font-mono font-bold text-emerald-400">
                      {thresholds.lowThroughputMbps} Mbps
                    </span>
                  </div>
                  <input
                    type="range"
                    min={2}
                    max={100}
                    step={2}
                    value={thresholds.lowThroughputMbps}
                    onChange={(e) =>
                      onUpdateThresholds({
                        ...thresholds,
                        lowThroughputMbps: Number(e.target.value),
                      })
                    }
                    className="mt-2 w-full accent-emerald-400"
                  />
                </label>

                <label className="block rounded-lg border border-slate-800 bg-slate-950/70 p-3 text-xs">
                  <div className="flex justify-between text-slate-300">
                    <span>High Loaded Latency Ceiling</span>
                    <span className="font-mono font-bold text-rose-400">
                      {thresholds.highLoadedLatencyMs} ms
                    </span>
                  </div>
                  <input
                    type="range"
                    min={50}
                    max={400}
                    step={10}
                    value={thresholds.highLoadedLatencyMs}
                    onChange={(e) =>
                      onUpdateThresholds({
                        ...thresholds,
                        highLoadedLatencyMs: Number(e.target.value),
                      })
                    }
                    className="mt-2 w-full accent-rose-400"
                  />
                </label>
              </div>
            </div>
          </div>

          {/* Issue Filter Chips & Problem Zones */}
          <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-4">
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <ShieldAlert className="h-4 w-4 text-rose-400" />
                <h3 className="text-sm font-semibold text-slate-100">
                  Detected Problem Zones ({filteredIssues.length})
                </h3>
              </div>

              <div className="flex flex-wrap gap-1.5">
                {ISSUE_FILTER_OPTIONS.map((opt) => (
                  <button
                    key={opt.type}
                    type="button"
                    onClick={() => setIssueFilter(opt.type)}
                    className={`rounded-full px-2.5 py-1 text-xs font-medium transition ${
                      issueFilter === opt.type
                        ? 'bg-sky-500 text-slate-950'
                        : 'bg-slate-950 text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    {opt.label}
                  </button>
                ))}
              </div>
            </div>

            {filteredIssues.length === 0 ? (
              <div className="flex items-center justify-center gap-2 py-8 text-xs text-emerald-400">
                <CheckCircle2 className="h-4 w-4" />
                <span>No RF problem zones matched the selected threshold criteria.</span>
              </div>
            ) : (
              <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                {filteredIssues.map((issue) => (
                  <div
                    key={issue.id}
                    className="rounded-lg border border-slate-800 bg-slate-950/80 p-3.5"
                  >
                    <div className="flex items-center justify-between">
                      <span className="rounded bg-rose-500/20 px-2 py-0.5 font-mono text-xs font-bold text-rose-300">
                        {issue.type}
                      </span>
                      <span className="font-mono text-xs text-slate-400">
                        {issue.samples} samples · Cell {issue.cellId} (PCI {issue.pci})
                      </span>
                    </div>
                    <div className="mt-2 grid grid-cols-2 gap-2 text-xs text-slate-300">
                      <div>Avg RSRP: {issue.averageRsrp} dBm</div>
                      <div>Avg RSRQ: {issue.averageRsrq} dB</div>
                      {issue.downloadMbps > 0 && (
                        <div>Throughput: {issue.downloadMbps.toFixed(1)} Mbps</div>
                      )}
                      {issue.loadedPingMs > 0 && (
                        <div>Loaded Ping: {issue.loadedPingMs.toFixed(0)} ms</div>
                      )}
                    </div>
                    <div className="mt-2 font-mono text-[11px] text-slate-400">
                      GPS: {issue.latitude.toFixed(5)}, {issue.longitude.toFixed(5)}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}

      {/* TAB 8: ADVANCED CORRELATION */}
      {activeTab === 'ADVANCED' && (
        <div className="space-y-4">
          {/* Moments to Investigate Counters + Export Button */}
          <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <Sparkles className="h-4 w-4 text-sky-400" />
                <h3 className="text-sm font-semibold text-slate-100">
                  Moments to Investigate — Multi-Layer RF & Transport Correlation
                </h3>
              </div>

              <div className="flex flex-wrap items-center gap-2">
                <div className="flex items-center gap-2 bg-slate-950 border border-slate-800 rounded-lg px-2.5 py-1">
                  <Filter className="h-3.5 w-3.5 text-amber-400" />
                  <RfToggleSwitch
                    id="anomalies-only-toggle"
                    checked={anomaliesOnly}
                    onChange={setAnomaliesOnly}
                    label="Anomalies only"
                    size="sm"
                    variant="warning"
                  />
                </div>

                <button
                  type="button"
                  onClick={handleExportAdvanced}
                  className="inline-flex items-center gap-1.5 rounded-lg bg-sky-500 px-3 py-1.5 text-xs font-semibold text-slate-950 hover:bg-sky-400"
                >
                  <Download className="h-3.5 w-3.5" />
                  <span>Export advanced data</span>
                </button>
              </div>
            </div>

            <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-4">
              <div className="rounded-lg border border-slate-800 bg-slate-950/70 p-3">
                <div className="text-[11px] uppercase text-slate-400">Weak-Signal Samples</div>
                <div className="mt-1 font-mono text-xl font-bold text-rose-400">
                  {momentsToInvestigate.weakSignalCount}
                </div>
              </div>
              <div className="rounded-lg border border-slate-800 bg-slate-950/70 p-3">
                <div className="text-[11px] uppercase text-slate-400">High-Ping Probes</div>
                <div className="mt-1 font-mono text-xl font-bold text-amber-400">
                  {momentsToInvestigate.highPingProbeCount}
                </div>
              </div>
              <div className="rounded-lg border border-slate-800 bg-slate-950/70 p-3">
                <div className="text-[11px] uppercase text-slate-400">Measurement Pauses</div>
                <div className="mt-1 font-mono text-xl font-bold text-purple-400">
                  {momentsToInvestigate.pauseCount}
                </div>
              </div>
              <div className="rounded-lg border border-slate-800 bg-slate-950/70 p-3">
                <div className="text-[11px] uppercase text-slate-400">Cell Changes</div>
                <div className="mt-1 font-mono text-xl font-bold text-sky-400">
                  {momentsToInvestigate.cellChangeCount}
                </div>
              </div>
            </div>

            {/* Sub-View Switcher (MAP | TIMELINE | CELLS) & Metric Selector */}
            <div className="mt-4 flex flex-wrap items-center justify-between gap-2 border-t border-slate-800 pt-3">
              <div className="inline-flex rounded-lg border border-slate-800 bg-slate-950 p-0.5">
                {(['MAP', 'TIMELINE', 'CELLS'] as AdvancedViewMode[]).map((mode) => (
                  <button
                    key={mode}
                    type="button"
                    onClick={() => setAdvancedViewMode(mode)}
                    className={`rounded-md px-3 py-1 text-xs font-semibold transition ${
                      advancedViewMode === mode
                        ? 'bg-sky-500 text-slate-950'
                        : 'text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    {mode}
                  </button>
                ))}
              </div>

              <div className="inline-flex items-center gap-1.5">
                <span className="text-xs text-slate-400">Correlate Metric:</span>
                {(['RSRP', 'PING', 'JITTER'] as AdvancedMetricType[]).map((m) => (
                  <button
                    key={m}
                    type="button"
                    onClick={() => setAdvancedMetric(m)}
                    className={`rounded-md px-2.5 py-1 text-xs font-semibold transition ${
                      advancedMetric === m
                        ? 'bg-purple-500 text-slate-950'
                        : 'bg-slate-950 text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    {m}
                  </button>
                ))}
              </div>
            </div>
          </div>

          {/* Sub-view 1: MAP Correlated Route View */}
          {advancedViewMode === 'MAP' && (
            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-4">
              <div className="mb-2 flex flex-wrap items-center justify-between gap-2 text-xs text-slate-400">
                <span>
                  Correlated Spatial View ({advancedMetric}) · Handovers, Pauses & Light Probes overlaid
                </span>
                <div className="flex items-center gap-3">
                  <span className="inline-flex items-center gap-1 text-sky-400">
                    <Zap className="h-3.5 w-3.5" /> Light Probe
                  </span>
                  <span className="inline-flex items-center gap-1 text-purple-400">
                    <PauseCircle className="h-3.5 w-3.5" /> Session Pause
                  </span>
                  <span className="inline-flex items-center gap-1 text-amber-400">
                    <RefreshCw className="h-3.5 w-3.5" /> Handover
                  </span>
                </div>
              </div>

              <svg
                viewBox="0 0 760 280"
                className="h-64 w-full rounded-lg border border-slate-800 bg-slate-950 p-2"
              >
                {correlatedTimeline.map((item) => {
                  const x =
                    40 +
                    ((item.longitude - traceBounds.minLon) /
                      Math.max(0.0001, traceBounds.maxLon - traceBounds.minLon)) *
                      680;
                  const y =
                    250 -
                    ((item.latitude - traceBounds.minLat) /
                      Math.max(0.0001, traceBounds.maxLat - traceBounds.minLat)) *
                      220;

                  const strokeColor =
                    item.category === 'PAUSE'
                      ? '#a855f7'
                      : item.category === 'HANDOVER'
                      ? '#f59e0b'
                      : item.isAnomaly
                      ? '#ef4444'
                      : '#38bdf8';

                  return (
                    <circle
                      key={item.id}
                      cx={x}
                      cy={y}
                      r={item.category === 'RADIO_SAMPLE' ? 4 : 6}
                      fill={strokeColor}
                      stroke="#0f172a"
                      strokeWidth="1.2"
                    >
                      <title>{`${item.title} — ${item.detail}`}</title>
                    </circle>
                  );
                })}
              </svg>
            </div>
          )}

          {/* Sub-view 2: TIMELINE Chronological Event Correlation */}
          {advancedViewMode === 'TIMELINE' && (
            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-4">
              <div className="max-h-80 space-y-2 overflow-y-auto pr-1">
                {correlatedTimeline.map((item) => (
                  <div
                    key={item.id}
                    className={`flex flex-wrap items-center justify-between gap-2 rounded-lg border px-3 py-2 text-xs ${
                      item.isAnomaly
                        ? 'border-rose-500/40 bg-rose-950/20'
                        : 'border-slate-800 bg-slate-950/70'
                    }`}
                  >
                    <div className="space-y-0.5">
                      <div className="flex items-center gap-2">
                        <span className="font-mono text-[11px] text-slate-400">
                          {formatTimeShort(item.timestampMs)}
                        </span>
                        <span className="rounded bg-slate-800 px-1.5 py-0.5 font-mono text-[10px] font-semibold text-sky-300">
                          {item.category}
                        </span>
                        <span className="font-semibold text-slate-100">{item.title}</span>
                      </div>
                      <div className="text-slate-400">{item.detail}</div>
                    </div>

                    <div className="font-mono text-xs text-slate-300">
                      {advancedMetric === 'RSRP' && item.rsrp !== null && `${item.rsrp} dBm`}
                      {advancedMetric === 'PING' &&
                        item.pingMs !== null &&
                        `${item.pingMs.toFixed(0)} ms`}
                      {advancedMetric === 'JITTER' &&
                        item.jitterMs !== null &&
                        `${item.jitterMs.toFixed(1)} ms`}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Sub-view 3: CELLS Per-Cell Performance Matrix */}
          {advancedViewMode === 'CELLS' && (
            <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-4">
              <div className="mb-3 flex items-center gap-2">
                <Cpu className="h-4 w-4 text-sky-400" />
                <h4 className="text-xs font-semibold uppercase tracking-wider text-slate-200">
                  Per-Cell Correlated Performance Matrix
                </h4>
              </div>
              <div className="rf-table-container rounded-lg border border-slate-800">
                <table className="rf-data-table w-full text-left text-xs">
                  <thead>
                    <tr className="border-b border-slate-800 text-[11px] uppercase text-slate-400">
                      <th className="px-3 py-2">Cell ID</th>
                      <th className="px-3 py-2">PCI / Tech</th>
                      <th className="px-3 py-2">Band</th>
                      <th className="px-3 py-2">Avg RSRP</th>
                      <th className="px-3 py-2">Avg Ping</th>
                      <th className="px-3 py-2">Avg Jitter</th>
                      <th className="px-3 py-2">Avg DL</th>
                      <th className="px-3 py-2">Handovers</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-800/60">
                    {cellPerformanceMatrix
                      .filter((c) => !anomaliesOnly || c.hasAnomaly)
                      .map((cell) => (
                        <tr key={cell.cellId} className="hover:bg-slate-950/60">
                          <th className="px-3 py-2 font-mono font-semibold text-sky-400">
                            {cell.cellId}
                          </th>
                          <td className="px-3 py-2 text-slate-200">
                            PCI {cell.pci} · {cell.technology}
                          </td>
                          <td className="px-3 py-2 text-slate-300">{cell.freqLabel}</td>
                          <td className="px-3 py-2 font-mono font-semibold text-emerald-400">
                            {cell.avgRsrp} dBm
                          </td>
                          <td className="px-3 py-2 font-mono text-slate-200">
                            {cell.avgPing !== null ? `${cell.avgPing} ms` : '—'}
                          </td>
                          <td className="px-3 py-2 font-mono text-slate-300">
                            {cell.avgJitter !== null ? `${cell.avgJitter} ms` : '—'}
                          </td>
                          <td className="px-3 py-2 font-mono text-sky-400">
                            {cell.avgDlMbps !== null ? `${cell.avgDlMbps} Mbps` : '—'}
                          </td>
                          <td className="px-3 py-2 font-mono text-amber-400">
                            {cell.cellHandovers}
                          </td>
                        </tr>
                      ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
