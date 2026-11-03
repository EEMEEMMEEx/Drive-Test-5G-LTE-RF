import React, { useMemo, useRef, useState } from 'react';
import {
  Activity,
  BarChart3,
  CheckSquare,
  Clock,
  Download,
  FileCode2,
  FileSpreadsheet,
  FileText,
  FolderOpen,
  Globe,
  MapPin,
  Printer,
  Radio,
  RotateCcw,
  Route,
  Search,
  Sparkles,
  Square,
  Trash2,
  Undo2,
  Upload,
  Wifi,
  X,
} from 'lucide-react';
import type { Campaign, CampaignPreset, Measurement } from '../../types/drivetest';
import {
  calculateSignalStats,
  detectMobilityEvents,
} from '../../services/rfAnalyticsEngine';
import {
  generateCampaignGeoJson,
  generateCampaignKml,
  generateMeasurementsCsv,
  triggerFileDownload,
} from '../../services/exportService';
import type { TranslationDictionary } from '../../i18n/translations';
import { RfSiteAcceptanceReportModal } from '../reports/RfSiteAcceptanceReportModal';

export interface CampaignListViewProps {
  campaigns: Campaign[];
  measurementsByCampaign: Record<number, Measurement[]>;
  selectedCampaignId: number;
  onSelectCampaign: (id: number) => void;
  onOpenMapForCampaign: (id: number) => void;
  onOpenAnalyticsForCampaign: (id: number) => void;
  onDeleteCampaigns: (ids: number[]) => void;
  onUndoDelete: () => void;
  canUndoDelete: boolean;
  lastDeletedName: string | null;
  onImportCampaignCsv: (name: string, csvText: string) => void;
  onResetDemoData: () => void;
  onClearAllData?: () => void;
  t: TranslationDictionary;
}

function formatDuration(startMs: number, endMs: number | null): string {
  const finish = endMs ?? Date.now();
  const totalSec = Math.max(0, Math.round((finish - startMs) / 1000));
  const mins = Math.floor(totalSec / 60);
  const secs = totalSec % 60;
  if (mins >= 60) {
    const hrs = Math.floor(mins / 60);
    const remMins = mins % 60;
    return `${hrs}h ${remMins}m`;
  }
  return `${mins}m ${secs}s`;
}

function formatDistance(meters: number): string {
  if (meters >= 1000) {
    return `${(meters / 1000).toFixed(2)} km`;
  }
  return `${Math.round(meters)} m`;
}

function getRsrpBadgeClass(rsrp: number): string {
  if (rsrp >= -90) {
    return 'bg-emerald-500/20 text-emerald-300 border-emerald-500/40';
  }
  if (rsrp >= -102) {
    return 'bg-lime-500/20 text-lime-300 border-lime-500/40';
  }
  if (rsrp >= -110) {
    return 'bg-amber-500/20 text-amber-300 border-amber-500/40';
  }
  return 'bg-rose-500/20 text-rose-300 border-rose-500/40';
}

function getPresetLabel(preset: CampaignPreset | undefined, t: TranslationDictionary): string {
  switch (preset) {
    case 'URBAN_ROUTE':
      return t.presetUrbanRoute;
    case 'HIGHWAY':
      return t.presetHighway;
    case 'SITE_AUDIT':
      return t.presetSiteAudit;
    case 'INDOOR':
      return t.presetIndoor;
    default:
      return t.presetUrbanRoute;
  }
}

function buildSampleImportCsv(): string {
  const now = Date.now() - 120_000;
  const header =
    'timestamp,latitude,longitude,altitude,speed_ms,accuracy_m,bearing,gps_valid,technology,operator,mcc,mnc,cell_id,lac_tac,pci,band,earfcn,rssi_dbm,rsrp_dbm,rsrq_db,sinr_db,registered,connection_state';
  const rows: string[] = [header];
  for (let i = 0; i < 12; i++) {
    const ts = now + i * 5000;
    const lat = (13.7563 + i * 0.00045).toFixed(6);
    const lon = (100.5018 + i * 0.00055).toFixed(6);
    const rsrp = -82 - (i % 5) * 6;
    const rsrq = -9 - (i % 4) * 2;
    const sinr = (18 - (i % 5) * 2.5).toFixed(1);
    const pci = i < 6 ? 104 : 212;
    const cid = i < 6 ? 520101 : 520102;
    rows.push(
      `${ts},${lat},${lon},14.0,11.5,4.0,45.0,1,NR_5G,AIS 5G,520,03,${cid},1010,${pci},78,632000,-68,${rsrp},${rsrq},${sinr},1,CONNECTED`
    );
  }
  return rows.join('\n');
}

export function CampaignListView({
  campaigns,
  measurementsByCampaign,
  selectedCampaignId,
  onSelectCampaign,
  onOpenMapForCampaign,
  onOpenAnalyticsForCampaign,
  onDeleteCampaigns,
  onUndoDelete,
  canUndoDelete,
  lastDeletedName,
  onImportCampaignCsv,
  onResetDemoData,
  onClearAllData,
  t,
}: CampaignListViewProps) {
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [confirmDeleteIds, setConfirmDeleteIds] = useState<number[] | null>(null);
  const [confirmClearAll, setConfirmClearAll] = useState<boolean>(false);
  const [pdfPreviewCampaign, setPdfPreviewCampaign] = useState<Campaign | null>(null);

  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const filteredCampaigns = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    if (!q) return campaigns;
    return campaigns.filter((c) => {
      const presetText = (c.preset ?? '').toLowerCase();
      return c.name.toLowerCase().includes(q) || presetText.includes(q);
    });
  }, [campaigns, searchQuery]);

  const toggleSelectOne = (id: number) => {
    setSelectedIds((prev) =>
      prev.includes(id) ? prev.filter((item) => item !== id) : [...prev, id]
    );
  };

  const toggleSelectAll = () => {
    if (selectedIds.length === filteredCampaigns.length && filteredCampaigns.length > 0) {
      setSelectedIds([]);
    } else {
      setSelectedIds(filteredCampaigns.map((c) => c.id));
    }
  };

  const handleBatchExportCsv = () => {
    const combined: Measurement[] = [];
    for (const id of selectedIds) {
      const list = measurementsByCampaign[id] ?? [];
      combined.push(...list);
    }
    const csv = generateMeasurementsCsv(combined);
    triggerFileDownload(
      `batch_campaigns_${selectedIds.length}_export.csv`,
      csv,
      'text/csv;charset=utf-8;'
    );
  };

  const handleBatchDeleteRequest = () => {
    if (selectedIds.length === 0) return;
    setConfirmDeleteIds(selectedIds);
  };

  const handleConfirmDelete = () => {
    if (!confirmDeleteIds || confirmDeleteIds.length === 0) return;
    onDeleteCampaigns(confirmDeleteIds);
    setSelectedIds((prev) => prev.filter((id) => !confirmDeleteIds.includes(id)));
    setConfirmDeleteIds(null);
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      const text = typeof reader.result === 'string' ? reader.result : '';
      const cleanName = file.name.replace(/\.csv$/i, '') || 'Imported Drive Test';
      onImportCampaignCsv(cleanName, text);
    };
    reader.readAsText(file);
    e.target.value = '';
  };

  const handleImportSampleCsv = () => {
    const sampleCsv = buildSampleImportCsv();
    onImportCampaignCsv('Imported 5G NR Route Log', sampleCsv);
  };

  const handlePrintPdf = (campaign: Campaign) => {
    setPdfPreviewCampaign(campaign);
  };

  return (
    <div className="space-y-4">
      {/* Undo Delete Banner */}
      {canUndoDelete && (
        <div
          role="status"
          className="bg-amber-500/15 border border-amber-500/40 rounded-xl px-4 py-3 flex items-center justify-between gap-3"
        >
          <div className="flex items-center gap-2 text-sm text-amber-200">
            <RotateCcw className="w-4 h-4 text-amber-400 shrink-0" />
            <span>
              &ldquo;{lastDeletedName ?? 'Campaign'}&rdquo; deleted
            </span>
          </div>
          <button
            type="button"
            onClick={onUndoDelete}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-warning text-slate-950 hover:bg-amber-300 transition"
          >
            <Undo2 className="w-3.5 h-3.5" />
            <span>{t.undoDelete}</span>
          </button>
        </div>
      )}

      {/* Search & Global Actions Bar */}
      <div className="bg-surface border border-border rounded-xl p-3 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2.5 flex-1 min-w-[240px]">
          <button
            type="button"
            onClick={toggleSelectAll}
            className="inline-flex items-center gap-1.5 px-2.5 py-2 rounded-lg text-xs font-medium bg-surface-elevated border border-border text-text-muted hover:text-text"
            aria-label="Select All Campaigns"
          >
            {selectedIds.length > 0 && selectedIds.length === filteredCampaigns.length ? (
              <CheckSquare className="w-4 h-4 text-accent" />
            ) : (
              <Square className="w-4 h-4 text-text-muted" />
            )}
            <span>All</span>
          </button>

          <div className="relative flex-1">
            <Search className="w-4 h-4 text-text-muted absolute left-3 top-1/2 -translate-y-1/2" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={t.searchCampaigns}
              aria-label={t.searchCampaigns}
              className="w-full bg-surface-elevated border border-border rounded-lg pl-9 pr-8 py-2 text-sm text-text placeholder:text-text-muted focus:outline-none focus:border-accent"
            />
            {searchQuery && (
              <button
                type="button"
                onClick={() => setSearchQuery('')}
                className="absolute right-2.5 top-1/2 -translate-y-1/2 text-text-muted hover:text-text"
                aria-label="Clear search"
              >
                <X className="w-4 h-4" />
              </button>
            )}
          </div>
        </div>

        {/* Import CSV & Reset Demo Routes */}
        <div className="flex flex-wrap items-center gap-2">
          <input
            ref={fileInputRef}
            type="file"
            accept=".csv,text/csv"
            onChange={handleFileChange}
            className="hidden"
            data-testid="csv-file-input"
          />

          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs font-semibold bg-cyan-500/20 text-accent border border-accent/40 hover:bg-cyan-500/30 transition"
          >
            <Upload className="w-3.5 h-3.5" />
            <span>{t.importCsv}</span>
          </button>

          <button
            type="button"
            onClick={handleImportSampleCsv}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs font-medium bg-surface-elevated text-text border border-border hover:bg-surface-elevated transition"
            title="Generate and import a sample 5G NR CSV route log"
          >
            <Sparkles className="w-3.5 h-3.5 text-accent" />
            <span>Sample CSV</span>
          </button>

          <button
            type="button"
            onClick={onResetDemoData}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs font-medium bg-surface-elevated text-text-muted border border-border hover:bg-surface-elevated transition"
          >
            <RotateCcw className="w-3.5 h-3.5" />
            <span>Reset Demo Routes</span>
          </button>

          {onClearAllData && (
            <button
              type="button"
              onClick={() => {
                if (confirmClearAll) {
                  onClearAllData();
                  setConfirmClearAll(false);
                } else {
                  setConfirmClearAll(true);
                }
              }}
              className={`inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs font-medium border transition ${
                confirmClearAll
                  ? 'bg-rose-600 text-white border-rose-500 animate-pulse'
                  : 'bg-rose-500/10 text-rose-300 border-rose-500/30 hover:bg-rose-500/20'
              }`}
              title="ล้างข้อมูลแคมเปญและการวัดทั้งหมด (Clear all campaigns & measurements)"
            >
              <Trash2 className="w-3.5 h-3.5" />
              <span>{confirmClearAll ? 'Confirm Clear All?' : t.clearAllData}</span>
            </button>
          )}
        </div>
      </div>

      {/* Multi-Select Batch Toolbar */}
      {selectedIds.length > 0 && (
        <div className="bg-indigo-950/60 border border-indigo-500/40 rounded-xl px-4 py-2.5 flex flex-wrap items-center justify-between gap-3">
          <div className="text-xs font-semibold text-indigo-200">
            {selectedIds.length} selected
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={handleBatchExportCsv}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-accent text-on-accent hover:bg-cyan-400 transition"
            >
              <Download className="w-3.5 h-3.5" />
              <span>Batch {t.exportCsv}</span>
            </button>
            <button
              type="button"
              onClick={handleBatchDeleteRequest}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-rose-600 text-white hover:bg-rose-500 transition"
            >
              <Trash2 className="w-3.5 h-3.5" />
              <span>Batch {t.deleteCampaign}</span>
            </button>
          </div>
        </div>
      )}

      {/* Delete Confirmation Banner */}
      {confirmDeleteIds && confirmDeleteIds.length > 0 && (
        <div
          role="alertdialog"
          aria-label="Confirm Delete Campaign"
          className="bg-rose-950/60 border border-rose-500/50 rounded-xl p-4 flex flex-wrap items-center justify-between gap-3"
        >
          <div className="text-sm text-rose-200">
            Confirm deleting{' '}
            <span className="font-bold">{confirmDeleteIds.length}</span> campaign(s)? You can
            undo immediately after deletion.
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setConfirmDeleteIds(null)}
              className="px-3 py-1.5 rounded-lg text-xs font-medium bg-surface-elevated text-text hover:bg-surface-elevated"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={handleConfirmDelete}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-rose-600 text-white hover:bg-rose-500"
            >
              <Trash2 className="w-3.5 h-3.5" />
              <span>Confirm Delete</span>
            </button>
          </div>
        </div>
      )}

      {/* Campaign Cards Grid */}
      {filteredCampaigns.length === 0 ? (
        <div className="bg-surface border border-border rounded-xl p-10 text-center space-y-2">
          <FolderOpen className="w-10 h-10 text-text-muted mx-auto" />
          <div className="text-base font-semibold text-text-muted">No Matching Campaigns</div>
          <p className="text-xs text-text-muted">
            Try clearing your search filter, importing a CSV log, or resetting demo routes.
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4">
          {filteredCampaigns.map((campaign) => {
            const measurements = measurementsByCampaign[campaign.id] ?? [];
            const stats = calculateSignalStats(
              measurements,
              campaign.alertThresholdRsrp ?? -105
            );
            const mobility = detectMobilityEvents(measurements);
            const isSelected = campaign.id === selectedCampaignId;
            const isChecked = selectedIds.includes(campaign.id);

            // Sparkline points
            const sparkPoints = measurements.slice(-40);
            const sparkSvgPoints =
              sparkPoints.length > 1
                ? sparkPoints
                    .map((m, idx) => {
                      const x = (idx / (sparkPoints.length - 1)) * 220;
                      const clamped = Math.max(-130, Math.min(-60, m.radio.rsrp));
                      const y = 42 - ((clamped + 130) / 70) * 36;
                      return `${x.toFixed(1)},${y.toFixed(1)}`;
                    })
                    .join(' ')
                : '0,24 220,24';

            const safeSlug = campaign.name
              .toLowerCase()
              .replace(/[^a-z0-9]+/g, '_')
              .replace(/^_|_$/g, '');

            return (
              <div
                key={campaign.id}
                data-testid={`campaign-card-${campaign.id}`}
                onClick={() => onSelectCampaign(campaign.id)}
                className={`rounded-xl border p-4 transition cursor-pointer ${
                  isSelected
                    ? 'bg-surface border-accent/70 shadow-lg shadow-accent/10'
                    : 'bg-surface border-border hover:border-border'
                }`}
              >
                <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4">
                  {/* Left Info Block */}
                  <div className="flex items-start gap-3">
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        toggleSelectOne(campaign.id);
                      }}
                      aria-label={`Select ${campaign.name}`}
                      className="mt-1 text-text-muted hover:text-accent"
                    >
                      {isChecked ? (
                        <CheckSquare className="w-4 h-4 text-accent" />
                      ) : (
                        <Square className="w-4 h-4" />
                      )}
                    </button>

                    <div className="space-y-1.5">
                      <div className="flex flex-wrap items-center gap-2">
                        <h3 className="text-base font-bold text-text">
                          {campaign.name}
                        </h3>

                        <span className="px-2 py-0.5 rounded-md text-[11px] font-semibold bg-surface-elevated text-accent border border-border">
                          {getPresetLabel(campaign.preset, t)}
                        </span>

                        {campaign.isActive && (
                          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-bold bg-emerald-500/20 text-emerald-300 border border-emerald-500/40">
                            <Radio className="w-3 h-3 animate-pulse" />
                            ACTIVE
                          </span>
                        )}
                      </div>

                      {/* Metadata row */}
                      <div className="flex flex-wrap items-center gap-3 text-xs text-text-muted">
                        <span className="inline-flex items-center gap-1">
                          <Clock className="w-3.5 h-3.5 text-text-muted" />
                          {new Date(campaign.startTime).toLocaleString()} ·{' '}
                          {formatDuration(campaign.startTime, campaign.endTime)}
                        </span>
                        <span className="inline-flex items-center gap-1">
                          <Route className="w-3.5 h-3.5 text-text-muted" />
                          {formatDistance(campaign.distanceMeters)}
                        </span>
                        <span className="inline-flex items-center gap-1">
                          <Activity className="w-3.5 h-3.5 text-text-muted" />
                          {measurements.length || campaign.measurementCount} pts
                        </span>
                        <span className="inline-flex items-center gap-1 text-amber-300">
                          Handovers: {mobility.handovers}
                        </span>
                      </div>
                    </div>
                  </div>

                  {/* Middle Telemetry + Sparkline */}
                  <div className="flex flex-wrap items-center gap-4">
                    {/* Signal Badges */}
                    <div className="flex items-center gap-2.5">
                      <div
                        className={`px-3 py-1.5 rounded-lg border text-center ${getRsrpBadgeClass(
                          stats.avgRsrp
                        )}`}
                      >
                        <div className="text-[10px] uppercase tracking-wider opacity-80">
                          Avg RSRP
                        </div>
                        <div className="text-sm font-mono font-bold">
                          {stats.avgRsrp} dBm
                        </div>
                      </div>

                      <div className="bg-surface-elevated border border-border rounded-lg px-2.5 py-1.5 text-xs font-mono">
                        <div className="text-text-muted">
                          Min: <span className="text-rose-300">{stats.minRsrp}</span> dBm
                        </div>
                        <div className="text-text-muted">
                          Max: <span className="text-emerald-300">{stats.maxRsrp}</span> dBm
                        </div>
                      </div>

                      {campaign.speedTestStatus === 'SUCCESS' && (
                        <div className="bg-surface-elevated border border-border rounded-lg px-2.5 py-1.5 text-xs">
                          <div className="flex items-center gap-1 text-accent font-mono font-semibold">
                            <Wifi className="w-3 h-3" />
                            <span>
                              {campaign.speedDownloadMbps.toFixed(1)} /{' '}
                              {campaign.speedUploadMbps.toFixed(1)} Mbps
                            </span>
                          </div>
                          <div className="text-[11px] text-text-muted font-mono">
                            Ping {campaign.speedPingMs.toFixed(0)} ms
                          </div>
                        </div>
                      )}
                    </div>

                    {/* Mini SVG RSRP Sparkline */}
                    <div className="bg-surface-elevated border border-border rounded-lg px-2.5 py-1.5">
                      <div className="text-[10px] text-text-muted mb-0.5 flex justify-between">
                        <span>RSRP Trace</span>
                        <span className="font-mono text-text-muted">
                          Cov {stats.coveragePercent}%
                        </span>
                      </div>
                      <svg
                        width={160}
                        height={38}
                        viewBox="0 0 220 46"
                        className="overflow-visible"
                        aria-label={`RSRP Sparkline for ${campaign.name}`}
                      >
                        <line
                          x1={0}
                          y1={23}
                          x2={220}
                          y2={23}
                          stroke="var(--color-border)"
                          strokeDasharray="2 2"
                        />
                        <polyline
                          fill="none"
                          stroke="#22d3ee"
                          strokeWidth={2}
                          points={sparkSvgPoints}
                        />
                      </svg>
                    </div>
                  </div>
                </div>

                {/* Bottom Action Toolbar */}
                <div
                  className="mt-3 pt-3 border-t border-border flex flex-wrap items-center justify-between gap-2"
                  onClick={(e) => e.stopPropagation()}
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <button
                      type="button"
                      onClick={() => onOpenMapForCampaign(campaign.id)}
                      className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-cyan-500/20 text-accent border border-accent/40 hover:bg-cyan-500/30 transition"
                    >
                      <MapPin className="w-3.5 h-3.5" />
                      <span>{t.navMap}</span>
                    </button>

                    <button
                      type="button"
                      onClick={() => onOpenAnalyticsForCampaign(campaign.id)}
                      className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-indigo-500/20 text-indigo-300 border border-indigo-500/40 hover:bg-indigo-500/30 transition"
                    >
                      <BarChart3 className="w-3.5 h-3.5" />
                      <span>{t.navAnalytics}</span>
                    </button>
                  </div>

                  <div className="flex flex-wrap items-center gap-1.5">
                    <button
                      type="button"
                      onClick={() => {
                        const csv = generateMeasurementsCsv(measurements);
                        triggerFileDownload(
                          `${safeSlug || 'campaign'}.csv`,
                          csv,
                          'text/csv;charset=utf-8;'
                        );
                      }}
                      className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs font-medium bg-surface-elevated text-text hover:bg-surface-elevated border border-border transition"
                    >
                      <FileSpreadsheet className="w-3.5 h-3.5 text-emerald-400" />
                      <span>{t.exportCsv}</span>
                    </button>

                    <button
                      type="button"
                      onClick={() => {
                        const kml = generateCampaignKml(campaign, measurements);
                        triggerFileDownload(
                          `${safeSlug || 'campaign'}.kml`,
                          kml,
                          'application/vnd.google-earth.kml+xml'
                        );
                      }}
                      className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs font-medium bg-surface-elevated text-text hover:bg-surface-elevated border border-border transition"
                    >
                      <Globe className="w-3.5 h-3.5 text-accent" />
                      <span>{t.exportKml}</span>
                    </button>

                    <button
                      type="button"
                      onClick={() => {
                        const geojson = generateCampaignGeoJson(campaign, measurements);
                        triggerFileDownload(
                          `${safeSlug || 'campaign'}.geojson`,
                          geojson,
                          'application/geo+json'
                        );
                      }}
                      className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs font-medium bg-surface-elevated text-text hover:bg-surface-elevated border border-border transition"
                    >
                      <FileCode2 className="w-3.5 h-3.5 text-amber-400" />
                      <span>{t.exportGeoJson}</span>
                    </button>

                    <button
                      type="button"
                      onClick={() => handlePrintPdf(campaign)}
                      className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs font-medium bg-surface-elevated text-text hover:bg-surface-elevated border border-border transition"
                    >
                      <Printer className="w-3.5 h-3.5 text-purple-400" />
                      <span>{t.exportPdf}</span>
                    </button>

                    <button
                      type="button"
                      onClick={() => setConfirmDeleteIds([campaign.id])}
                      className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs font-medium bg-rose-950/60 text-rose-300 hover:bg-rose-900/70 border border-rose-800/60 transition"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                      <span>{t.deleteCampaign}</span>
                    </button>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* Automated RF Site Acceptance & Audit Report Modal */}
      {pdfPreviewCampaign && (
        <RfSiteAcceptanceReportModal
          campaign={pdfPreviewCampaign}
          measurements={measurementsByCampaign[pdfPreviewCampaign.id] ?? []}
          onClose={() => setPdfPreviewCampaign(null)}
        />
      )}
    </div>
  );
}
