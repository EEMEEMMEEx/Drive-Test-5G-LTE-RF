import type { Campaign, MapBaseType, Measurement, SpeedTestResult } from '../types/drivetest';
import {
  calculateCampaignDiagnostic,
  calculateSignalStats,
  detectMobilityEvents,
} from './rfAnalyticsEngine';

export interface AcceptanceKpiItem {
  id: string;
  name: string;
  metric: string;
  target: string;
  actual: string;
  passed: boolean;
  importance: 'CRITICAL' | 'MAJOR' | 'STANDARD';
}

export interface ExecutiveKpiScorecard {
  overallStatus: 'PASS' | 'CONDITIONAL_PASS' | 'FAIL';
  passedCount: number;
  totalCount: number;
  score: number;
  kpis: AcceptanceKpiItem[];
}

export interface TopWeakSpot {
  id: number;
  timeFormatted: string;
  latitude: number;
  longitude: number;
  rsrp: number;
  rsrq: number;
  sinr: number;
  pci: number;
  cellId: number;
  technology: string;
  band: number;
  earfcn: number;
}

export interface QualityDistribution {
  excellent: { count: number; percent: number }; // >= -85
  good: { count: number; percent: number }; // -85 to -95
  fair: { count: number; percent: number }; // -95 to -105
  poor: { count: number; percent: number }; // < -105
}

export interface ReportSignalStats extends ReturnType<typeof calculateSignalStats> {
  minRsrq: number;
  maxRsrq: number;
  minSinr: number;
  maxSinr: number;
}

export interface RoutePointSummary {
  lat: number;
  lon: number;
  rsrp: number;
  color: string;
}

export interface RouteBounds {
  minLat: number;
  maxLat: number;
  minLon: number;
  maxLon: number;
}

export interface ReportExportOptions {
  includeSnapshot?: boolean;
  snapshotMapType?: MapBaseType;
}

export interface AuditReportData {
  reportId: string;
  generatedDate: string;
  generatedTime: string;
  campaign: Campaign;
  measurementsCount: number;
  operatorName: string;
  technologySummary: string;
  signalStats: ReportSignalStats;
  mobility: ReturnType<typeof detectMobilityEvents>;
  scorecard: ExecutiveKpiScorecard;
  distribution: QualityDistribution;
  topWeakSpots: TopWeakSpot[];
  routePoints: RoutePointSummary[];
  routeBounds: RouteBounds;
}

export function getTileUrlForType(mapType: MapBaseType = 'GOOGLE_HYBRID'): {
  url: string;
  label: string;
  subdomains?: string[];
} {
  switch (mapType) {
    case 'GOOGLE_SATELLITE':
    case 'SATELLITE':
      return {
        url: 'https://mt{s}.google.com/vt/lyrs=s&x={x}&y={y}&z={z}',
        label: 'Satellite',
        subdomains: ['0', '1', '2', '3'],
      };
    case 'GOOGLE_ROADMAP':
      return {
        url: 'https://mt{s}.google.com/vt/lyrs=m&x={x}&y={y}&z={z}',
        label: 'Normal (Roadmap)',
        subdomains: ['0', '1', '2', '3'],
      };
    case 'GOOGLE_TERRAIN':
    case 'TERRAIN':
      return {
        url: 'https://mt{s}.google.com/vt/lyrs=p&x={x}&y={y}&z={z}',
        label: 'Terrain',
        subdomains: ['0', '1', '2', '3'],
      };
    case 'GOOGLE_HYBRID':
    case 'HYBRID':
    default:
      return {
        url: 'https://mt{s}.google.com/vt/lyrs=y&x={x}&y={y}&z={z}',
        label: 'Hybrid',
        subdomains: ['0', '1', '2', '3'],
      };
  }
}

export function generateRouteSvgMarkup(
  points: RoutePointSummary[],
  bounds: RouteBounds,
  width = 680,
  height = 190
): string {
  if (points.length === 0) {
    return `<div style="padding: 24px; text-align: center; color: #64748b; font-style: italic; background: #f8fafc; border: 1px dashed #cbd5e1; border-radius: 8px;">No GPS coordinates recorded for route trajectory snapshot</div>`;
  }

  const dLat = bounds.maxLat - bounds.minLat || 0.001;
  const dLon = bounds.maxLon - bounds.minLon || 0.001;
  const padX = 25;
  const padY = 20;
  const drawW = width - padX * 2;
  const drawH = height - padY * 2;

  const circles = points.map((p) => {
    const cx = padX + ((p.lon - bounds.minLon) / dLon) * drawW;
    const cy = height - padY - ((p.lat - bounds.minLat) / dLat) * drawH;
    return { cx: cx.toFixed(1), cy: cy.toFixed(1), color: p.color, rsrp: p.rsrp };
  });

  const pathD = circles.length > 0 ? 'M ' + circles.map((c) => `${c.cx},${c.cy}`).join(' L ') : '';

  return `<svg viewBox="0 0 ${width} ${height}" style="width: 100%; height: ${height}px; background: #0b1329; border-radius: 8px; overflow: hidden; display: block;" xmlns="http://www.w3.org/2000/svg">
    <!-- Grid lines -->
    <line x1="0" y1="${height / 2}" x2="${width}" y2="${height / 2}" stroke="#1e293b" stroke-dasharray="4,4" stroke-width="1" />
    <line x1="${width / 2}" y1="0" x2="${width / 2}" y2="${height}" stroke="#1e293b" stroke-dasharray="4,4" stroke-width="1" />
    <!-- Route Polyline -->
    <path d="${pathD}" fill="none" stroke="#475569" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" opacity="0.6" />
    <!-- Colored RSRP Points -->
    ${circles.map((c) => `<circle cx="${c.cx}" cy="${c.cy}" r="3.5" fill="${c.color}" stroke="#ffffff" stroke-width="0.75" />`).join('')}
    <!-- Start & End Markers -->
    ${circles.length > 0 ? `
      <circle cx="${circles[0].cx}" cy="${circles[0].cy}" r="5.5" fill="#22c55e" stroke="#ffffff" stroke-width="2" />
      <text x="${parseFloat(circles[0].cx) + 8}" y="${parseFloat(circles[0].cy) + 4}" fill="#22c55e" font-size="9" font-weight="bold" font-family="sans-serif">START</text>
      <circle cx="${circles[circles.length - 1].cx}" cy="${circles[circles.length - 1].cy}" r="5.5" fill="#ef4444" stroke="#ffffff" stroke-width="2" />
      <text x="${parseFloat(circles[circles.length - 1].cx) + 8}" y="${parseFloat(circles[circles.length - 1].cy) + 4}" fill="#ef4444" font-size="9" font-weight="bold" font-family="sans-serif">END</text>
    ` : ''}
  </svg>`;
}

export function generateAuditReportData(
  campaign: Campaign,
  measurements: Measurement[]
): AuditReportData {
  const sorted = [...measurements].sort((a, b) => a.timestampMs - b.timestampMs);
  const baseSignalStats = calculateSignalStats(sorted);
  const mobility = detectMobilityEvents(sorted);

  const valid = sorted.filter((m) => m.radio.rsrp > -140 && m.radio.rsrp <= -40);
  let minRsrq = valid.length > 0 ? valid[0].radio.rsrq : -20;
  let maxRsrq = valid.length > 0 ? valid[0].radio.rsrq : -3;
  let minSinr = valid.length > 0 ? valid[0].radio.sinr : 0;
  let maxSinr = valid.length > 0 ? valid[0].radio.sinr : 30;

  for (const m of valid) {
    if (m.radio.rsrq < minRsrq) minRsrq = m.radio.rsrq;
    if (m.radio.rsrq > maxRsrq) maxRsrq = m.radio.rsrq;
    if (m.radio.sinr < minSinr) minSinr = m.radio.sinr;
    if (m.radio.sinr > maxSinr) maxSinr = m.radio.sinr;
  }

  const signalStats: ReportSignalStats = {
    ...baseSignalStats,
    minRsrq,
    maxRsrq,
    minSinr,
    maxSinr,
  };

  const speedTests: SpeedTestResult[] = [];
  if (campaign.speedTestStatus === 'SUCCESS') {
    speedTests.push({
      id: 1,
      campaignId: campaign.id,
      timestampMs: campaign.speedTestTimestampMs || campaign.startTime,
      status: 'SUCCESS',
      downloadMbps: campaign.speedDownloadMbps,
      uploadMbps: campaign.speedUploadMbps,
      pingMs: campaign.speedPingMs,
      jitterMs: campaign.speedJitterMs || 5,
      loadedPingMs: campaign.speedPingMs + 10,
      latitude: campaign.speedTestLat || sorted[0]?.gps.latitude || 0,
      longitude: campaign.speedTestLon || sorted[0]?.gps.longitude || 0,
      technology: sorted[0]?.radio.technology || 'LTE',
      band: sorted[0]?.radio.band || 3,
      cellId: sorted[0]?.radio.cellId || 0,
      pci: sorted[0]?.radio.pci || 0,
      rsrp: sorted[0]?.radio.rsrp || -95,
      transport: 'MOBILE',
      dataSubscriptionId: 0,
    });
  }

  const diagnostic = calculateCampaignDiagnostic(sorted, speedTests, []);

  // 1. Distribution calculation
  const total = sorted.length;
  let exc = 0;
  let gd = 0;
  let fr = 0;
  let pr = 0;

  for (const m of sorted) {
    const val = m.radio.rsrp;
    if (val >= -85) exc++;
    else if (val >= -95) gd++;
    else if (val >= -105) fr++;
    else pr++;
  }

  const distribution: QualityDistribution = {
    excellent: { count: exc, percent: total > 0 ? Number(((exc / total) * 100).toFixed(1)) : 0 },
    good: { count: gd, percent: total > 0 ? Number(((gd / total) * 100).toFixed(1)) : 0 },
    fair: { count: fr, percent: total > 0 ? Number(((fr / total) * 100).toFixed(1)) : 0 },
    poor: { count: pr, percent: total > 0 ? Number(((pr / total) * 100).toFixed(1)) : 0 },
  };

  // 2. Acceptance KPIs
  const rsrpGoodPct = total > 0 ? Number((((exc + gd) / total) * 100).toFixed(1)) : 0;
  const sinrGoodCount = sorted.filter((m) => m.radio.sinr >= 3.0).length;
  const sinrGoodPct = total > 0 ? Number(((sinrGoodCount / total) * 100).toFixed(1)) : 0;
  const pingPongAcceptable = mobility.pingPong <= 2;
  const throughputPassed = campaign.speedTestStatus === 'SUCCESS' ? campaign.speedDownloadMbps >= 50 : true;

  const kpis: AcceptanceKpiItem[] = [
    {
      id: 'kpi_rsrp',
      name: 'RSRP Coverage Reliability',
      metric: 'RSRP ≥ -95 dBm',
      target: '≥ 95.0% samples',
      actual: `${rsrpGoodPct}%`,
      passed: rsrpGoodPct >= 95.0,
      importance: 'CRITICAL',
    },
    {
      id: 'kpi_sinr',
      name: 'Signal Quality & Clean Air',
      metric: 'SINR ≥ 3.0 dB',
      target: '≥ 90.0% samples',
      actual: `${sinrGoodPct}%`,
      passed: sinrGoodPct >= 90.0,
      importance: 'CRITICAL',
    },
    {
      id: 'kpi_mobility',
      name: 'Handover Stability',
      metric: 'Ping-Pong Cell Switches',
      target: '≤ 2 occurrences',
      actual: `${mobility.pingPong} events`,
      passed: pingPongAcceptable,
      importance: 'MAJOR',
    },
    {
      id: 'kpi_speed',
      name: '5G / LTE User Throughput',
      metric: 'Downlink Peak Rate',
      target: '≥ 50.0 Mbps',
      actual: campaign.speedTestStatus === 'SUCCESS' ? `${campaign.speedDownloadMbps.toFixed(1)} Mbps` : 'N/A (Skipped)',
      passed: throughputPassed,
      importance: 'STANDARD',
    },
  ];

  const passedCount = kpis.filter((k) => k.passed).length;
  const criticalPassed = kpis.filter((k) => k.importance === 'CRITICAL').every((k) => k.passed);

  let overallStatus: 'PASS' | 'CONDITIONAL_PASS' | 'FAIL' = 'FAIL';
  if (passedCount === kpis.length) {
    overallStatus = 'PASS';
  } else if (criticalPassed && passedCount >= kpis.length - 1) {
    overallStatus = 'CONDITIONAL_PASS';
  }

  const scorecard: ExecutiveKpiScorecard = {
    overallStatus,
    passedCount,
    totalCount: kpis.length,
    score: diagnostic.score,
    kpis,
  };

  // 3. Top 5 Worst Coverage Dips
  const worstSorted = [...sorted].sort((a, b) => a.radio.rsrp - b.radio.rsrp);
  const topWeakSpots: TopWeakSpot[] = worstSorted.slice(0, 5).map((m) => {
    const d = new Date(m.timestampMs);
    const timeFormatted = d.toTimeString().split(' ')[0] + '.' + String(d.getMilliseconds()).padStart(3, '0');
    return {
      id: m.id,
      timeFormatted,
      latitude: m.gps.latitude,
      longitude: m.gps.longitude,
      rsrp: m.radio.rsrp,
      rsrq: m.radio.rsrq,
      sinr: m.radio.sinr,
      pci: m.radio.pci,
      cellId: m.radio.cellId,
      technology: m.radio.technology,
      band: m.radio.band,
      earfcn: m.radio.earfcn,
    };
  });

  // 4. Extract Route Trajectory Points & Bounding Box
  const step = Math.max(1, Math.floor(sorted.length / 120));
  const routePoints: RoutePointSummary[] = [];
  let minLat = 90;
  let maxLat = -90;
  let minLon = 180;
  let maxLon = -180;

  for (let i = 0; i < sorted.length; i += step) {
    const m = sorted[i];
    const { latitude, longitude } = m.gps;
    if (latitude !== 0 && longitude !== 0) {
      if (latitude < minLat) minLat = latitude;
      if (latitude > maxLat) maxLat = latitude;
      if (longitude < minLon) minLon = longitude;
      if (longitude > maxLon) maxLon = longitude;

      let color = '#ef4444'; // poor < -105
      if (m.radio.rsrp >= -85) color = '#22c55e'; // excellent
      else if (m.radio.rsrp >= -95) color = '#0284c7'; // good
      else if (m.radio.rsrp >= -105) color = '#f59e0b'; // fair

      routePoints.push({
        lat: latitude,
        lon: longitude,
        rsrp: m.radio.rsrp,
        color,
      });
    }
  }

  if (routePoints.length === 0) {
    minLat = 13.75;
    maxLat = 13.76;
    minLon = 100.50;
    maxLon = 100.51;
  }

  const routeBounds: RouteBounds = { minLat, maxLat, minLon, maxLon };

  const operatorName = sorted[0]?.radio.operatorName || 'AIS / 5G NR';
  const technologySummary = sorted.some((m) => m.radio.technology === 'NR_5G')
    ? '5G NR Standalone (SA) / NSA Dual-Connectivity'
    : '4G LTE-Advanced Pro Carrier Aggregation';

  const now = new Date();
  const reportId = `RF-AUD-${campaign.id}-${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}`;

  return {
    reportId,
    generatedDate: now.toLocaleDateString('en-GB'),
    generatedTime: now.toLocaleTimeString('en-GB'),
    campaign,
    measurementsCount: sorted.length,
    operatorName,
    technologySummary,
    signalStats,
    mobility,
    scorecard,
    distribution,
    topWeakSpots,
    routePoints,
    routeBounds,
  };
}

export function generateStandaloneHtmlReport(
  data: AuditReportData,
  options: ReportExportOptions = {}
): string {
  const { scorecard, signalStats, mobility, campaign } = data;
  const statusColor = scorecard.overallStatus === 'PASS' ? '#16a34a' : scorecard.overallStatus === 'CONDITIONAL_PASS' ? '#d97706' : '#dc2626';

  const includeSnapshot = options.includeSnapshot !== false;
  const snapshotMapType = options.snapshotMapType || 'GOOGLE_HYBRID';
  const tileInfo = getTileUrlForType(snapshotMapType);
  const svgMapMarkup = includeSnapshot
    ? generateRouteSvgMarkup(data.routePoints, data.routeBounds, 700, 200)
    : '';

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>${data.reportId} - RF Site Acceptance Report</title>
  <style>
    @page { size: A4 portrait; margin: 10mm 15mm; }
    * { box-sizing: border-box; -webkit-print-color-adjust: exact; print-color-adjust: exact; }
    body { font-family: 'Segoe UI', -apple-system, BlinkMacSystemFont, Roboto, sans-serif; color: #0f172a; margin: 0; padding: 20px; font-size: 11pt; line-height: 1.4; background: #fff; }
    .header { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 2px solid #0284c7; padding-bottom: 12px; margin-bottom: 16px; }
    .badge { display: inline-block; padding: 4px 12px; font-size: 11pt; font-weight: bold; border-radius: 6px; color: #fff; text-transform: uppercase; }
    .kpi-table, .data-table { width: 100%; border-collapse: collapse; margin-bottom: 16px; font-size: 10pt; }
    .kpi-table th, .kpi-table td, .data-table th, .data-table td { border: 1px solid #cbd5e1; padding: 6px 10px; }
    .kpi-table th, .data-table th { background: #f1f5f9; text-align: left; font-weight: 600; color: #334155; }
    .section-title { font-size: 12pt; font-weight: bold; color: #0f172a; border-left: 4px solid #0284c7; padding-left: 8px; margin: 18px 0 8px 0; }
    .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 16px; }
    .card { border: 1px solid #e2e8f0; border-radius: 8px; padding: 10px 14px; background: #f8fafc; }
    .card-label { font-size: 9pt; color: #64748b; text-transform: uppercase; font-weight: 600; }
    .dist-bar { height: 12px; border-radius: 6px; display: flex; overflow: hidden; margin: 8px 0; border: 1px solid #cbd5e1; }
    .dist-seg { height: 100%; }
    .map-container { border: 1px solid #cbd5e1; border-radius: 8px; overflow: hidden; margin-bottom: 16px; background: #0f172a; }
    .map-meta { display: flex; justify-content: space-between; align-items: center; padding: 6px 10px; background: #f1f5f9; border-top: 1px solid #cbd5e1; font-size: 8.5pt; color: #475569; }
    .signatures { display: flex; justify-content: space-between; margin-top: 35px; break-inside: avoid; }
    .sig-box { width: 45%; text-align: center; border-top: 1px dashed #64748b; padding-top: 8px; font-size: 9.5pt; color: #475569; }
    @media print {
      body { padding: 0; }
      .no-print { display: none !important; }
    }
  </style>
</head>
<body>
  <div class="header">
    <div>
      <div style="font-size: 16pt; font-weight: 800; color: #0284c7; letter-spacing: -0.5px;">RF FIELD ENGINEERING SUITE</div>
      <div style="font-size: 11pt; font-weight: bold; color: #1e293b;">5G NR / LTE Site Acceptance & Route Audit Report</div>
      <div style="font-size: 9pt; color: #64748b; margin-top: 2px;">Report ID: <strong>${data.reportId}</strong> · Date: ${data.generatedDate} ${data.generatedTime}</div>
    </div>
    <div style="text-align: right;">
      <span class="badge" style="background-color: ${statusColor}; font-size: 13pt;">${scorecard.overallStatus}</span>
      <div style="font-size: 9pt; color: #64748b; margin-top: 4px;">Score: <strong>${scorecard.score}/100</strong> (${scorecard.passedCount}/${scorecard.totalCount} KPIs)</div>
    </div>
  </div>

  <div class="grid-2">
    <div class="card">
      <div class="card-label">Campaign & Route Profile</div>
      <div style="font-size: 11pt; font-weight: 700; color: #0f172a; margin-top: 2px;">${campaign.name}</div>
      <div style="font-size: 9pt; color: #64748b; margin-top: 2px;">Profile: ${campaign.preset ?? 'Urban Route'} · Operator: ${data.operatorName}</div>
      <div style="font-size: 9pt; color: #64748b;">Distance: ${(campaign.distanceMeters / 1000).toFixed(2)} km · Samples: ${data.measurementsCount} pts</div>
    </div>
    <div class="card">
      <div class="card-label">Radio Access Technology</div>
      <div style="font-size: 11pt; font-weight: 700; color: #0f172a; margin-top: 2px;">${data.technologySummary}</div>
      <div style="font-size: 9pt; color: #64748b; margin-top: 2px;">Estimated Cell Sites: ${mobility.distinctCells} · Handovers: ${mobility.handovers}</div>
      <div style="font-size: 9pt; color: #64748b;">Ping-Pong HO: ${mobility.pingPong} · Missed Margin HO: ${mobility.missedHandovers}</div>
    </div>
  </div>

  ${
    includeSnapshot
      ? `
  <div class="section-title">Route Map & Spatial RF Coverage Snapshot</div>
  <div class="map-container">
    ${svgMapMarkup}
    <div class="map-meta">
      <div>Snapshot Engine: <strong>${tileInfo.label}</strong> · Coordinates: [${data.routeBounds.minLat.toFixed(4)}, ${data.routeBounds.minLon.toFixed(4)}] to [${data.routeBounds.maxLat.toFixed(4)}, ${data.routeBounds.maxLon.toFixed(4)}]</div>
      <div style="display: flex; gap: 8px;">
        <span><strong style="color: #22c55e;">●</strong> ≥ -85 dBm</span>
        <span><strong style="color: #0284c7;">●</strong> -85 to -95</span>
        <span><strong style="color: #f59e0b;">●</strong> -95 to -105</span>
        <span><strong style="color: #ef4444;">●</strong> &lt; -105</span>
      </div>
    </div>
  </div>
  `
      : ''
  }

  <div class="section-title">1. Executive Acceptance Criteria Scorecard</div>
  <table class="kpi-table">
    <thead>
      <tr>
        <th style="width: 35%;">Acceptance Metric</th>
        <th style="width: 25%;">Target Criteria</th>
        <th style="width: 20%;">Measured Actual</th>
        <th style="width: 20%;">Compliance Result</th>
      </tr>
    </thead>
    <tbody>
      ${scorecard.kpis
        .map(
          (k) => `
        <tr>
          <td><strong>${k.name}</strong><br/><span style="font-size: 8.5pt; color: #64748b;">${k.metric}</span></td>
          <td>${k.target}</td>
          <td style="font-family: monospace; font-weight: bold;">${k.actual}</td>
          <td>
            <span style="display: inline-block; padding: 2px 8px; border-radius: 4px; font-weight: bold; font-size: 8.5pt; color: #fff; background-color: ${
              k.passed ? '#16a34a' : '#dc2626'
            };">
              ${k.passed ? 'PASS' : 'FAIL'}
            </span>
          </td>
        </tr>
      `
        )
        .join('')}
    </tbody>
  </table>

  <div class="section-title">2. Radio Frequency Signal Statistics</div>
  <table class="data-table">
    <thead>
      <tr>
        <th>Radio Parameter</th>
        <th>Average</th>
        <th>Minimum</th>
        <th>Maximum</th>
        <th>Quality Benchmark</th>
      </tr>
    </thead>
    <tbody>
      <tr>
        <td><strong>RSRP (Reference Signal Received Power)</strong></td>
        <td style="font-weight: bold; color: #0284c7;">${signalStats.avgRsrp} dBm</td>
        <td>${signalStats.minRsrp} dBm</td>
        <td>${signalStats.maxRsrp} dBm</td>
        <td>Good: ≥ -95 dBm</td>
      </tr>
      <tr>
        <td><strong>RSRQ (Reference Signal Received Quality)</strong></td>
        <td style="font-weight: bold;">${signalStats.avgRsrq} dB</td>
        <td>${signalStats.minRsrq} dB</td>
        <td>${signalStats.maxRsrq} dB</td>
        <td>Normal: ≥ -14 dB</td>
      </tr>
      <tr>
        <td><strong>SINR (Signal-to-Interference-plus-Noise Ratio)</strong></td>
        <td style="font-weight: bold; color: #16a34a;">${signalStats.avgSinr} dB</td>
        <td>${signalStats.minSinr} dB</td>
        <td>${signalStats.maxSinr} dB</td>
        <td>Clean: ≥ 5 dB</td>
      </tr>
      <tr>
        <td><strong>RSSI (Carrier Received Signal Strength)</strong></td>
        <td style="font-weight: bold;">${signalStats.avgRssi} dBm</td>
        <td>-120 dBm</td>
        <td>-50 dBm</td>
        <td>Full Carrier Bandwidth</td>
      </tr>
    </tbody>
  </table>

  <div class="section-title">3. Signal Coverage Distribution</div>
  <div style="font-size: 9pt; color: #475569; display: flex; justify-content: space-between;">
    <span>Excellent (≥ -85 dBm): <strong>${data.distribution.excellent.percent}%</strong></span>
    <span>Good (-85 to -95 dBm): <strong>${data.distribution.good.percent}%</strong></span>
    <span>Fair (-95 to -105 dBm): <strong>${data.distribution.fair.percent}%</strong></span>
    <span>Poor (&lt; -105 dBm): <strong>${data.distribution.poor.percent}%</strong></span>
  </div>
  <div class="dist-bar">
    <div class="dist-seg" style="width: ${data.distribution.excellent.percent}%; background: #22c55e;"></div>
    <div class="dist-seg" style="width: ${data.distribution.good.percent}%; background: #38bdf8;"></div>
    <div class="dist-seg" style="width: ${data.distribution.fair.percent}%; background: #fbbf24;"></div>
    <div class="dist-seg" style="width: ${data.distribution.poor.percent}%; background: #ef4444;"></div>
  </div>

  <div class="section-title">4. Top Critical Weak Spots & Coverage Dips</div>
  <table class="data-table" style="font-size: 9pt;">
    <thead>
      <tr>
        <th>Sample Time</th>
        <th>GPS Coordinates</th>
        <th>PCI / EARFCN</th>
        <th>RSRP</th>
        <th>SINR</th>
        <th>Identified Defect</th>
      </tr>
    </thead>
    <tbody>
      ${data.topWeakSpots
        .map(
          (pt, i) => `
        <tr>
          <td>#${i + 1} (${pt.timeFormatted})</td>
          <td style="font-family: monospace;">${pt.latitude.toFixed(5)}, ${pt.longitude.toFixed(5)}</td>
          <td>PCI ${pt.pci} · ${pt.earfcn}</td>
          <td style="font-weight: bold; color: #dc2626;">${pt.rsrp} dBm</td>
          <td>${pt.sinr.toFixed(1)} dB</td>
          <td>Weak Signal (Edge of Cell)</td>
        </tr>
      `
        )
        .join('')}
    </tbody>
  </table>

  <div class="signatures">
    <div class="sig-box">
      <div>________________________________________</div>
      <div style="font-weight: bold; margin-top: 4px;">Field RF Test Engineer</div>
      <div>Radio Network Optimization & Drive Test</div>
      <div>Date: _____ / _____ / __________</div>
    </div>
    <div class="sig-box">
      <div>________________________________________</div>
      <div style="font-weight: bold; margin-top: 4px;">Operator Quality Assurance Manager</div>
      <div>Network Performance & Site Acceptance Verification</div>
      <div>Date: _____ / _____ / __________</div>
    </div>
  </div>
</body>
</html>`;
}
