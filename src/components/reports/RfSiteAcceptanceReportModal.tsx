import React, { useEffect, useMemo, useRef, useState } from 'react';
import L from 'leaflet';
import {
  CheckCircle2,
  Compass,
  Download,
  FileCheck2,
  Layers,
  MapPin,
  Printer,
  Radio,
  SlidersHorizontal,
  X,
  XCircle,
} from 'lucide-react';
import type { Campaign, MapBaseType, Measurement } from '../../types/drivetest';
import {
  generateAuditReportData,
  generateRouteSvgMarkup,
  generateStandaloneHtmlReport,
  getTileUrlForType,
  type AuditReportData,
} from '../../services/reportGeneratorService';
import { triggerFileDownload } from '../../services/exportService';

export interface RfSiteAcceptanceReportModalProps {
  campaign: Campaign | null;
  measurements: Measurement[];
  onClose: () => void;
}

const STORAGE_SNAPSHOT_ENABLED = 'rf_site_report_include_snapshot';
const STORAGE_SNAPSHOT_MAP_TYPE = 'rf_site_report_snapshot_map_type';

export function getInitialSnapshotEnabled(): boolean {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      const stored = window.localStorage.getItem(STORAGE_SNAPSHOT_ENABLED);
      if (stored !== null) return stored === 'true';
    }
  } catch {
    // safe fallback
  }
  return true; // Sensible default: ON by default
}

export function getInitialSnapshotMapType(): MapBaseType {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      const stored = window.localStorage.getItem(STORAGE_SNAPSHOT_MAP_TYPE);
      if (
        stored &&
        ['GOOGLE_HYBRID', 'GOOGLE_ROADMAP', 'GOOGLE_SATELLITE', 'GOOGLE_TERRAIN'].includes(stored)
      ) {
        return stored as MapBaseType;
      }
    }
  } catch {
    // safe fallback
  }
  return 'GOOGLE_HYBRID'; // Sensible default: Hybrid preselected
}

export function RfSiteAcceptanceReportModal({
  campaign,
  measurements,
  onClose,
}: RfSiteAcceptanceReportModalProps) {
  if (!campaign) return null;

  const [includeSnapshot, setIncludeSnapshot] = useState<boolean>(() =>
    getInitialSnapshotEnabled()
  );
  const [snapshotMapType, setSnapshotMapType] = useState<MapBaseType>(() =>
    getInitialSnapshotMapType()
  );
  const [isOptionsOpen, setIsOptionsOpen] = useState(false);

  // Map DOM reference for Leaflet
  const mapContainerRef = useRef<HTMLDivElement | null>(null);
  const mapInstanceRef = useRef<L.Map | null>(null);
  const tileLayerRef = useRef<L.TileLayer | null>(null);

  const reportData: AuditReportData = useMemo(() => {
    return generateAuditReportData(campaign, measurements);
  }, [campaign, measurements]);

  const tileConfig = useMemo(() => {
    return getTileUrlForType(snapshotMapType);
  }, [snapshotMapType]);

  // Persist user preferences
  const handleToggleSnapshot = (enabled: boolean) => {
    setIncludeSnapshot(enabled);
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        window.localStorage.setItem(STORAGE_SNAPSHOT_ENABLED, String(enabled));
      }
    } catch {
      // ignore
    }
  };

  const handleSelectMapType = (type: MapBaseType) => {
    setSnapshotMapType(type);
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        window.localStorage.setItem(STORAGE_SNAPSHOT_MAP_TYPE, type);
      }
    } catch {
      // ignore
    }
  };

  // Initialize or update Leaflet map for Route Map Snapshot
  useEffect(() => {
    if (!includeSnapshot || !mapContainerRef.current) return;

    try {
      if (!mapInstanceRef.current) {
        const map = L.map(mapContainerRef.current, {
          zoomControl: false,
          attributionControl: false,
          dragging: false,
          scrollWheelZoom: false,
          doubleClickZoom: false,
          boxZoom: false,
          keyboard: false,
        });

        const tileLayer = L.tileLayer(tileConfig.url, {
          subdomains: tileConfig.subdomains ?? ['0', '1', '2', '3'],
          maxZoom: 19,
        }).addTo(map);

        tileLayerRef.current = tileLayer;
        mapInstanceRef.current = map;

        // Render drive test points colored by RSRP
        const latlngs: L.LatLngExpression[] = [];
        reportData.routePoints.forEach((pt) => {
          latlngs.push([pt.lat, pt.lon]);
          L.circleMarker([pt.lat, pt.lon], {
            radius: 3.5,
            fillColor: pt.color,
            color: '#ffffff',
            weight: 0.75,
            fillOpacity: 0.9,
          }).addTo(map);
        });

        if (latlngs.length > 0) {
          map.fitBounds(L.latLngBounds(latlngs), { padding: [15, 15] });
        }
      } else {
        // Update tile layer when style changes
        if (tileLayerRef.current) {
          mapInstanceRef.current.removeLayer(tileLayerRef.current);
        }
        const newLayer = L.tileLayer(tileConfig.url, {
          subdomains: tileConfig.subdomains ?? ['0', '1', '2', '3'],
          maxZoom: 19,
        }).addTo(mapInstanceRef.current);
        tileLayerRef.current = newLayer;
      }
    } catch {
      // Graceful fallback: SVG trajectory map renders underneath if Leaflet fails
    }

    return () => {
      // Keep map reference stable while modal is open
    };
  }, [includeSnapshot, snapshotMapType, tileConfig, reportData.routePoints]);

  // Clean up map instance on modal unmount
  useEffect(() => {
    return () => {
      if (mapInstanceRef.current) {
        mapInstanceRef.current.remove();
        mapInstanceRef.current = null;
      }
    };
  }, []);

  const handlePrint = () => {
    try {
      if (typeof window !== 'undefined' && typeof window.print === 'function') {
        window.print();
      }
    } catch {
      // Safe guard
    }
  };

  const handleDownloadHtml = () => {
    const html = generateStandaloneHtmlReport(reportData, {
      includeSnapshot,
      snapshotMapType,
    });
    triggerFileDownload(
      `${reportData.reportId}_Site_Acceptance_Report.html`,
      html,
      'text/html;charset=utf-8;'
    );
  };

  const statusBg =
    reportData.scorecard.overallStatus === 'PASS'
      ? 'bg-emerald-500 text-white'
      : reportData.scorecard.overallStatus === 'CONDITIONAL_PASS'
      ? 'bg-amber-500 text-white'
      : 'bg-rose-500 text-white';

  const mapStyleChips: { id: MapBaseType; label: string }[] = [
    { id: 'GOOGLE_HYBRID', label: 'Hybrid' },
    { id: 'GOOGLE_ROADMAP', label: 'Normal' },
    { id: 'GOOGLE_SATELLITE', label: 'Satellite' },
    { id: 'GOOGLE_TERRAIN', label: 'Terrain' },
  ];

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="RF Site Acceptance Report Modal"
      className="fixed inset-0 z-50 bg-black/80 backdrop-blur-xs flex flex-col items-center justify-start p-2 sm:p-4 overflow-hidden animate-in fade-in duration-200"
    >
      <div className="bg-surface border border-border rounded-2xl max-w-5xl w-full flex flex-col h-[94vh] shadow-2xl overflow-hidden my-auto">
        {/* Modal Top Header (Hidden during print) */}
        <div className="no-print px-4 sm:px-6 py-3 border-b border-border bg-surface-elevated/95 flex items-center justify-between gap-3 shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-accent/15 border border-accent/40 flex items-center justify-center text-accent shadow-xs">
              <FileCheck2 className="w-5 h-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h3 className="text-sm sm:text-base font-bold text-text">
                  Automated RF Site Acceptance Report
                </h3>
                <span className="text-[10px] sm:text-[11px] font-mono px-2 py-0.5 rounded-full bg-accent/15 border border-accent/30 text-accent font-semibold">
                  A4 Executive PDF
                </span>
              </div>
              <p className="text-xs text-text-muted truncate max-w-[250px] sm:max-w-md">
                {campaign.name} · {reportData.reportId}
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            {/* Mobile Options Expand Toggle */}
            <button
              type="button"
              onClick={() => setIsOptionsOpen((v) => !v)}
              className={`md:hidden p-2 rounded-xl border text-xs font-semibold flex items-center gap-1.5 transition min-h-[40px] min-w-[40px] justify-center ${
                isOptionsOpen
                  ? 'bg-accent/20 border-accent text-accent'
                  : 'bg-surface border-border text-text'
              }`}
              aria-label="Toggle snapshot settings"
              title="Report Snapshot Settings"
            >
              <SlidersHorizontal className="w-4 h-4" />
            </button>

            <button
              type="button"
              onClick={handleDownloadHtml}
              className="inline-flex items-center gap-1.5 px-3 py-2 rounded-xl text-xs font-semibold bg-surface border border-border text-text hover:border-accent hover:text-accent transition shadow-xs min-h-[40px]"
              title="Download standalone HTML report"
            >
              <Download className="w-4 h-4 text-accent" />
              <span className="hidden sm:inline">Download HTML</span>
            </button>

            <button
              type="button"
              onClick={handlePrint}
              className="inline-flex items-center gap-1.5 px-3.5 py-2 rounded-xl text-xs font-bold bg-accent text-on-accent hover:opacity-90 transition shadow-xs min-h-[40px]"
              title="Print or Save as PDF"
            >
              <Printer className="w-4 h-4" />
              <span>Print / Save PDF</span>
            </button>

            <button
              type="button"
              onClick={onClose}
              className="p-2 rounded-xl text-text-muted hover:text-text hover:bg-surface border border-transparent hover:border-border transition min-h-[40px] min-w-[40px] flex items-center justify-center"
              aria-label="Close Report"
            >
              <X className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* OpenUI Configuration Ribbon (Desktop always visible, Mobile collapsible) */}
        <div
          className={`no-print border-b border-border bg-surface px-4 sm:px-6 py-2.5 flex flex-wrap items-center justify-between gap-3 text-xs shrink-0 ${
            isOptionsOpen ? 'block' : 'hidden md:flex'
          }`}
        >
          {/* Option: Include Route Map Snapshot */}
          <div className="flex items-center gap-3">
            <label className="flex items-center gap-2 cursor-pointer font-semibold text-text select-none">
              <input
                type="checkbox"
                checked={includeSnapshot}
                onChange={(e) => handleToggleSnapshot(e.target.checked)}
                className="sr-only"
                aria-label="Include Route Map Snapshot in PDF report"
              />
              <div
                className={`w-9 h-5 rounded-full transition-colors relative flex items-center px-0.5 ${
                  includeSnapshot ? 'bg-accent' : 'bg-surface-elevated border border-border'
                }`}
              >
                <div
                  className={`w-4 h-4 rounded-full bg-white transition-transform ${
                    includeSnapshot ? 'translate-x-4 bg-on-accent shadow-xs' : 'translate-x-0'
                  }`}
                />
              </div>
              <span className="flex items-center gap-1.5">
                <MapPin className="w-3.5 h-3.5 text-accent" />
                <span>Include Route Map Snapshot</span>
              </span>
            </label>
            <span
              className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${
                includeSnapshot
                  ? 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400'
                  : 'bg-slate-500/15 text-text-muted'
              }`}
            >
              {includeSnapshot ? 'ENABLED' : 'DISABLED'}
            </span>
          </div>

          {/* Option: Map Style Selector (enabled only when snapshot is active) */}
          <div className="flex items-center gap-2">
            <span className="text-text-muted font-medium flex items-center gap-1 text-[11px]">
              <Layers className="w-3.5 h-3.5 text-accent" />
              <span>Map Style:</span>
            </span>

            <div className="flex items-center gap-1 bg-surface-elevated border border-border rounded-xl p-0.5">
              {mapStyleChips.map((chip) => {
                const isSelected = snapshotMapType === chip.id;
                return (
                  <button
                    key={chip.id}
                    type="button"
                    disabled={!includeSnapshot}
                    onClick={() => handleSelectMapType(chip.id)}
                    aria-label={`Snapshot map style ${chip.label}`}
                    aria-pressed={isSelected}
                    className={`px-2.5 py-1 rounded-lg text-xs font-semibold transition-all ${
                      !includeSnapshot
                        ? 'opacity-40 cursor-not-allowed text-text-muted'
                        : isSelected
                        ? 'bg-accent text-on-accent shadow-xs'
                        : 'text-text-muted hover:text-text hover:bg-surface/50'
                    }`}
                  >
                    {chip.label}
                  </button>
                );
              })}
            </div>
          </div>
        </div>

        {/* Scrollable Document Canvas Container */}
        <div className="flex-1 overflow-y-auto p-3 sm:p-6 bg-slate-950/60 flex justify-center">
          {/* Printable Report Page Canvas (Pixel-perfect A4 representation) */}
          <div
            id="printable-rf-report"
            className="bg-white text-slate-900 rounded-xl shadow-2xl mx-auto p-6 sm:p-8 w-full max-w-3xl space-y-6 text-xs font-sans print:shadow-none print:p-0 print:m-0 print:max-w-none print:rounded-none"
          >
            {/* Header Block */}
            <div className="flex flex-wrap items-start justify-between border-b-2 border-sky-600 pb-4 gap-4">
              <div>
                <div className="flex items-center gap-2 text-sky-600 font-extrabold text-lg tracking-tight">
                  <Radio className="w-5 h-5" />
                  <span>RF FIELD ENGINEERING SUITE</span>
                </div>
                <h1 className="text-sm font-bold text-slate-800 mt-0.5">
                  5G NR / LTE Site Acceptance & Route Audit Report
                </h1>
                <div className="text-[11px] text-slate-500 mt-1">
                  Report ID: <span className="font-mono font-semibold text-slate-700">{reportData.reportId}</span> · Generated: {reportData.generatedDate} {reportData.generatedTime}
                </div>
              </div>

              <div className="text-right">
                <span className={`inline-block px-3 py-1 rounded-md text-xs font-extrabold uppercase tracking-wider ${statusBg}`}>
                  {reportData.scorecard.overallStatus}
                </span>
                <div className="text-[11px] font-medium text-slate-600 mt-1">
                  Compliance Score: <strong className="text-sky-700">{reportData.scorecard.score}/100</strong> ({reportData.scorecard.passedCount}/{reportData.scorecard.totalCount} KPIs)
                </div>
              </div>
            </div>

            {/* Campaign Meta Overview */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
              <div className="border border-slate-200 rounded-lg p-3 bg-slate-50/70">
                <div className="text-[10px] uppercase font-bold text-slate-500">Campaign & Profile</div>
                <div className="font-bold text-slate-900 text-sm mt-0.5">{campaign.name}</div>
                <div className="text-slate-600 mt-1">
                  Route Profile: <strong>{campaign.preset ?? 'Urban Route'}</strong> · Operator: <strong>{reportData.operatorName}</strong>
                </div>
                <div className="text-slate-600">
                  Distance: <strong>{(campaign.distanceMeters / 1000).toFixed(2)} km</strong> · Samples: <strong>{reportData.measurementsCount} pts</strong>
                </div>
              </div>

              <div className="border border-slate-200 rounded-lg p-3 bg-slate-50/70">
                <div className="text-[10px] uppercase font-bold text-slate-500">Radio Access Technology</div>
                <div className="font-bold text-slate-900 text-sm mt-0.5">{reportData.technologySummary}</div>
                <div className="text-slate-600 mt-1">
                  Cell Sites: <strong>{reportData.mobility.distinctCells}</strong> · Handovers: <strong>{reportData.mobility.handovers}</strong>
                </div>
                <div className="text-slate-600">
                  Ping-Pong Events: <strong>{reportData.mobility.pingPong}</strong> · Missed Margins: <strong>{reportData.mobility.missedHandovers}</strong>
                </div>
              </div>
            </div>

            {/* NEW: Route Map Snapshot Section (Conditional) */}
            {includeSnapshot && (
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <h2 className="text-xs font-bold uppercase tracking-wider text-slate-800 border-l-3 border-sky-600 pl-2 flex items-center gap-2">
                    <span>Route Map Snapshot & Spatial RF Distribution</span>
                    <span className="text-[10px] font-mono px-2 py-0.2 rounded bg-sky-100 text-sky-800 font-semibold normal-case">
                      {tileConfig.label}
                    </span>
                  </h2>
                  <div className="text-[10px] text-slate-500 font-mono">
                    [{reportData.routeBounds.minLat.toFixed(3)}, {reportData.routeBounds.minLon.toFixed(3)}] to [{reportData.routeBounds.maxLat.toFixed(3)}, {reportData.routeBounds.maxLon.toFixed(3)}]
                  </div>
                </div>

                {/* Map Render Frame with Leaflet & fail-safe SVG fallback */}
                <div className="border border-slate-300 rounded-lg overflow-hidden bg-slate-950 shadow-xs relative">
                  {/* Interactive / Static Leaflet Container */}
                  <div
                    ref={mapContainerRef}
                    className="w-full h-52 z-10 relative"
                    style={{ minHeight: '208px' }}
                  />

                  {/* Graceful Fallback: Rendered if Leaflet fails or tiles cannot load */}
                  <div
                    className="w-full absolute inset-0 pointer-events-none"
                    dangerouslySetInnerHTML={{
                      __html: generateRouteSvgMarkup(
                        reportData.routePoints,
                        reportData.routeBounds,
                        700,
                        208
                      ),
                    }}
                  />

                  {/* Map Bottom Legend Ribbon */}
                  <div className="px-3 py-1.5 bg-slate-100 border-t border-slate-200 text-[10px] flex flex-wrap items-center justify-between text-slate-700">
                    <div className="flex items-center gap-1.5 font-medium">
                      <Compass className="w-3.5 h-3.5 text-sky-600" />
                      <span>Drive Route: <strong>{(campaign.distanceMeters / 1000).toFixed(2)} km</strong> ({reportData.routePoints.length} GPS breadcrumb points)</span>
                    </div>
                    <div className="flex items-center gap-3">
                      <span className="flex items-center gap-1 font-semibold">
                        <span className="w-2.5 h-2.5 rounded-full bg-emerald-500" /> ≥ -85 dBm
                      </span>
                      <span className="flex items-center gap-1 font-semibold">
                        <span className="w-2.5 h-2.5 rounded-full bg-sky-500" /> -85 to -95
                      </span>
                      <span className="flex items-center gap-1 font-semibold">
                        <span className="w-2.5 h-2.5 rounded-full bg-amber-400" /> -95 to -105
                      </span>
                      <span className="flex items-center gap-1 font-semibold">
                        <span className="w-2.5 h-2.5 rounded-full bg-rose-500" /> &lt; -105
                      </span>
                    </div>
                  </div>
                </div>
              </div>
            )}

            {/* Section 1: KPI Acceptance Scorecard */}
            <div className="space-y-2">
              <h2 className="text-xs font-bold uppercase tracking-wider text-slate-800 border-l-3 border-sky-600 pl-2">
                1. Executive Acceptance Criteria Scorecard
              </h2>
              <div className="border border-slate-200 rounded-lg overflow-hidden">
                <table className="w-full text-left text-[11px]">
                  <thead className="bg-slate-100 text-slate-700 font-bold border-b border-slate-200">
                    <tr>
                      <th className="p-2 sm:p-2.5">Acceptance Metric</th>
                      <th className="p-2 sm:p-2.5">Target Criteria</th>
                      <th className="p-2 sm:p-2.5">Measured Actual</th>
                      <th className="p-2 sm:p-2.5 text-center">Compliance</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {reportData.scorecard.kpis.map((kpi) => (
                      <tr key={kpi.id} className="hover:bg-slate-50">
                        <td className="p-2 sm:p-2.5">
                          <div className="font-semibold text-slate-900">{kpi.name}</div>
                          <div className="text-[10px] text-slate-500">{kpi.metric}</div>
                        </td>
                        <td className="p-2 sm:p-2.5 font-medium text-slate-700">{kpi.target}</td>
                        <td className="p-2 sm:p-2.5 font-mono font-bold text-slate-900">{kpi.actual}</td>
                        <td className="p-2 sm:p-2.5 text-center">
                          <span
                            className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-[10px] font-bold ${
                              kpi.passed
                                ? 'bg-emerald-100 text-emerald-800'
                                : 'bg-rose-100 text-rose-800'
                            }`}
                          >
                            {kpi.passed ? (
                              <CheckCircle2 className="w-3 h-3 text-emerald-600" />
                            ) : (
                              <XCircle className="w-3 h-3 text-rose-600" />
                            )}
                            {kpi.passed ? 'PASS' : 'FAIL'}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            {/* Section 2: RF Statistics Table */}
            <div className="space-y-2">
              <h2 className="text-xs font-bold uppercase tracking-wider text-slate-800 border-l-3 border-sky-600 pl-2">
                2. Radio Frequency Signal Statistics
              </h2>
              <div className="border border-slate-200 rounded-lg overflow-hidden">
                <table className="w-full text-left text-[11px]">
                  <thead className="bg-slate-100 text-slate-700 font-bold border-b border-slate-200">
                    <tr>
                      <th className="p-2">Radio Parameter</th>
                      <th className="p-2">Average</th>
                      <th className="p-2">Minimum</th>
                      <th className="p-2">Maximum</th>
                      <th className="p-2">Engineering Benchmark</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    <tr>
                      <td className="p-2 font-semibold text-slate-900">RSRP (Power)</td>
                      <td className="p-2 font-mono font-bold text-sky-700">{reportData.signalStats.avgRsrp} dBm</td>
                      <td className="p-2 font-mono text-slate-600">{reportData.signalStats.minRsrp} dBm</td>
                      <td className="p-2 font-mono text-slate-600">{reportData.signalStats.maxRsrp} dBm</td>
                      <td className="p-2 text-slate-500">Target: ≥ -95 dBm</td>
                    </tr>
                    <tr>
                      <td className="p-2 font-semibold text-slate-900">RSRQ (Quality)</td>
                      <td className="p-2 font-mono font-bold text-slate-800">{reportData.signalStats.avgRsrq} dB</td>
                      <td className="p-2 font-mono text-slate-600">{reportData.signalStats.minRsrq} dB</td>
                      <td className="p-2 font-mono text-slate-600">{reportData.signalStats.maxRsrq} dB</td>
                      <td className="p-2 text-slate-500">Target: ≥ -14 dB</td>
                    </tr>
                    <tr>
                      <td className="p-2 font-semibold text-slate-900">SINR (SNR)</td>
                      <td className="p-2 font-mono font-bold text-emerald-700">{reportData.signalStats.avgSinr} dB</td>
                      <td className="p-2 font-mono text-slate-600">{reportData.signalStats.minSinr} dB</td>
                      <td className="p-2 font-mono text-slate-600">{reportData.signalStats.maxSinr} dB</td>
                      <td className="p-2 text-slate-500">Target: ≥ 5 dB</td>
                    </tr>
                    <tr>
                      <td className="p-2 font-semibold text-slate-900">RSSI (Carrier)</td>
                      <td className="p-2 font-mono font-bold text-slate-800">{reportData.signalStats.avgRssi} dBm</td>
                      <td className="p-2 font-mono text-slate-600">-120 dBm</td>
                      <td className="p-2 font-mono text-slate-600">-50 dBm</td>
                      <td className="p-2 text-slate-500">Wideband Received Signal</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>

            {/* Section 3: Distribution Breakdown */}
            <div className="space-y-1.5">
              <h2 className="text-xs font-bold uppercase tracking-wider text-slate-800 border-l-3 border-sky-600 pl-2">
                3. Coverage Quality Distribution
              </h2>
              <div className="flex items-center justify-between text-[10px] text-slate-600">
                <span>Excellent (≥ -85 dBm): <strong>{reportData.distribution.excellent.percent}%</strong></span>
                <span>Good (-85 to -95 dBm): <strong>{reportData.distribution.good.percent}%</strong></span>
                <span>Fair (-95 to -105 dBm): <strong>{reportData.distribution.fair.percent}%</strong></span>
                <span>Poor (&lt; -105 dBm): <strong>{reportData.distribution.poor.percent}%</strong></span>
              </div>
              <div className="h-3 w-full rounded-md overflow-hidden flex border border-slate-200">
                <div style={{ width: `${reportData.distribution.excellent.percent}%` }} className="bg-emerald-500" />
                <div style={{ width: `${reportData.distribution.good.percent}%` }} className="bg-sky-500" />
                <div style={{ width: `${reportData.distribution.fair.percent}%` }} className="bg-amber-400" />
                <div style={{ width: `${reportData.distribution.poor.percent}%` }} className="bg-rose-500" />
              </div>
            </div>

            {/* Section 4: Top 5 Worst Coverage Dips */}
            <div className="space-y-2">
              <h2 className="text-xs font-bold uppercase tracking-wider text-slate-800 border-l-3 border-sky-600 pl-2">
                4. Top Critical Weak Spots & Coverage Dips
              </h2>
              <div className="border border-slate-200 rounded-lg overflow-hidden">
                <table className="w-full text-left text-[10px]">
                  <thead className="bg-slate-100 text-slate-700 font-bold border-b border-slate-200">
                    <tr>
                      <th className="p-1.5 sm:p-2">Sample Time</th>
                      <th className="p-1.5 sm:p-2">Coordinates</th>
                      <th className="p-1.5 sm:p-2">PCI / EARFCN</th>
                      <th className="p-1.5 sm:p-2">RSRP</th>
                      <th className="p-1.5 sm:p-2">SINR</th>
                      <th className="p-1.5 sm:p-2">Defect Diagnosis</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100 font-mono">
                    {reportData.topWeakSpots.map((pt, i) => (
                      <tr key={pt.id}>
                        <td className="p-1.5 sm:p-2 font-sans font-medium">#{i + 1} ({pt.timeFormatted})</td>
                        <td className="p-1.5 sm:p-2">{pt.latitude.toFixed(5)}, {pt.longitude.toFixed(5)}</td>
                        <td className="p-1.5 sm:p-2">PCI {pt.pci} · {pt.earfcn}</td>
                        <td className="p-1.5 sm:p-2 font-bold text-rose-600">{pt.rsrp} dBm</td>
                        <td className="p-1.5 sm:p-2">{pt.sinr.toFixed(1)} dB</td>
                        <td className="p-1.5 sm:p-2 font-sans text-slate-600">Weak Signal (Edge of Cell)</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            {/* Signatures & Approval Block (break-inside-avoid) */}
            <div className="pt-6 border-t border-slate-300 flex justify-between gap-6 break-inside-avoid">
              <div className="w-[45%] text-center space-y-1">
                <div className="border-b border-dashed border-slate-400 pb-8 text-slate-400 text-[10px]">
                  (Authorized Signature)
                </div>
                <div className="font-bold text-slate-800 text-[11px] mt-1">Field RF Test Engineer</div>
                <div className="text-[10px] text-slate-500">Radio Network Optimization Department</div>
                <div className="text-[10px] text-slate-500">Date: _____ / _____ / __________</div>
              </div>

              <div className="w-[45%] text-center space-y-1">
                <div className="border-b border-dashed border-slate-400 pb-8 text-slate-400 text-[10px]">
                  (Approval Stamp & Signature)
                </div>
                <div className="font-bold text-slate-800 text-[11px] mt-1">Operator Quality Assurance Manager</div>
                <div className="text-[10px] text-slate-500">Network Performance & Site Acceptance Verification</div>
                <div className="text-[10px] text-slate-500">Date: _____ / _____ / __________</div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
