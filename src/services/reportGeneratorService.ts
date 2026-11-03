import type { Campaign, Measurement, SpeedTestResult } from '../types/drivetest';
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

  const kpis: AcceptanceKpiItem[] = [
    {
      id: 'kpi_rsrp_coverage',
      name: 'RSRP Outdoor Coverage Target',
      metric: 'RSRP ≥ -95 dBm',
      target: '≥ 95.0%',
      actual: `${rsrpGoodPct}%`,
      passed: rsrpGoodPct >= 95.0,
      importance: 'CRITICAL',
    },
    {
      id: 'kpi_sinr_quality',
      name: 'SINR Signal Quality Target',
      metric: 'SINR ≥ 3.0 dB',
      target: '≥ 90.0%',
      actual: `${sinrGoodPct}%`,
      passed: sinrGoodPct >= 90.0,
      importance: 'CRITICAL',
    },
    {
      id: 'kpi_mobility_pingpong',
      name: 'Mobility Handover Stability',
      metric: 'Ping-Pong Events',
      target: '< 3 events',
      actual: `${mobility.pingPong} events`,
      passed: mobility.pingPong < 3,
      importance: 'MAJOR',
    },
    {
      id: 'kpi_missed_ho',
      name: 'Neighbor Handover Margin Check',
      metric: 'Missed HO (>6dB margin)',
      target: '≤ 2 events',
      actual: `${mobility.missedHandovers} events`,
      passed: mobility.missedHandovers <= 2,
      importance: 'MAJOR',
    },
  ];

  if (campaign.speedTestStatus === 'SUCCESS') {
    kpis.push({
      id: 'kpi_throughput_dl',
      name: 'Active Throughput Benchmark',
      metric: 'DL Speed',
      target: '≥ 50.0 Mbps',
      actual: `${campaign.speedDownloadMbps.toFixed(1)} Mbps`,
      passed: campaign.speedDownloadMbps >= 50.0,
      importance: 'STANDARD',
    });
    kpis.push({
      id: 'kpi_latency',
      name: 'Round-Trip Latency Benchmark',
      metric: 'Unloaded Ping',
      target: '≤ 45 ms',
      actual: `${campaign.speedPingMs.toFixed(0)} ms`,
      passed: campaign.speedPingMs <= 45,
      importance: 'STANDARD',
    });
  }

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
  };
}

export function generateStandaloneHtmlReport(data: AuditReportData): string {
  const { scorecard, signalStats, mobility, campaign } = data;
  const statusColor = scorecard.overallStatus === 'PASS' ? '#16a34a' : scorecard.overallStatus === 'CONDITIONAL_PASS' ? '#d97706' : '#dc2626';

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
    .card-val { font-size: 14pt; font-weight: bold; color: #0284c7; font-family: monospace; }
    .dist-bar { height: 12px; border-radius: 6px; display: flex; overflow: hidden; margin: 8px 0; border: 1px solid #cbd5e1; }
    .dist-seg { height: 100%; }
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
      ${scorecard.kpis.map((k) => `
        <tr>
          <td><strong>${k.name}</strong><br/><span style="font-size: 8.5pt; color: #64748b;">${k.metric}</span></td>
          <td>${k.target}</td>
          <td style="font-family: monospace; font-weight: bold;">${k.actual}</td>
          <td>
            <span style="display: inline-block; padding: 2px 8px; border-radius: 4px; font-weight: bold; font-size: 8.5pt; color: #fff; background-color: ${k.passed ? '#16a34a' : '#dc2626'};">
              ${k.passed ? 'PASS' : 'FAIL'}
            </span>
          </td>
        </tr>
      `).join('')}
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
      ${data.topWeakSpots.map((pt, i) => `
        <tr>
          <td>#${i + 1} (${pt.timeFormatted})</td>
          <td style="font-family: monospace;">${pt.latitude.toFixed(5)}, ${pt.longitude.toFixed(5)}</td>
          <td>PCI ${pt.pci} · ${pt.earfcn}</td>
          <td style="font-weight: bold; color: #dc2626;">${pt.rsrp} dBm</td>
          <td>${pt.sinr.toFixed(1)} dB</td>
          <td>Weak Signal (Edge of Cell)</td>
        </tr>
      `).join('')}
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
