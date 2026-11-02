import type {
  AnalyticsSnapshot,
  BandUsage,
  CampaignComparisonResult,
  CampaignDiagnostic,
  CdfPoint,
  CellSite,
  CellularTechnology,
  ComparisonMetric,
  ComparisonPoint,
  DiagnosticIssue,
  DiagnosticThresholds,
  HandoverEvent,
  MapColorMode,
  Measurement,
  MissedHandoverEvent,
  MobilityStats,
  SessionEvent,
  SignalPalette,
  SignalStats,
  SpeedTestResult,
  TechBreakdownItem,
  TimePoint,
} from '../types/drivetest';

export const DEFAULT_DIAGNOSTIC_THRESHOLDS: DiagnosticThresholds = {
  weakRsrp: -105,
  poorRsrq: -15,
  lowThroughputMbps: 10,
  highLoadedLatencyMs: 150,
};

export function haversineDistanceMeters(
  lat1: number,
  lon1: number,
  lat2: number,
  lon2: number
): number {
  const R = 6371000;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos((lat1 * Math.PI) / 180) *
      Math.cos((lat2 * Math.PI) / 180) *
      Math.sin(dLon / 2) *
      Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

export function calculateSignalStats(
  measurements: Measurement[],
  coverageThresholdRsrp = -105
): SignalStats {
  const valid = measurements.filter((m) => m.radio.rsrp > -140 && m.radio.rsrp <= -40);
  if (valid.length === 0) {
    return {
      avgRsrp: -120,
      minRsrp: -120,
      maxRsrp: -120,
      avgRsrq: -20,
      avgSinr: 0,
      avgRssi: -100,
      coveragePercent: 0,
      totalMeasurements: measurements.length,
    };
  }

  let sumRsrp = 0;
  let minRsrp = valid[0].radio.rsrp;
  let maxRsrp = valid[0].radio.rsrp;
  let sumRsrq = 0;
  let sumSinr = 0;
  let sumRssi = 0;
  let coveredCount = 0;

  for (const m of valid) {
    const { rsrp, rsrq, sinr, rssi } = m.radio;
    sumRsrp += rsrp;
    if (rsrp < minRsrp) minRsrp = rsrp;
    if (rsrp > maxRsrp) maxRsrp = rsrp;
    sumRsrq += rsrq;
    sumSinr += sinr;
    sumRssi += rssi;
    if (rsrp >= coverageThresholdRsrp) coveredCount++;
  }

  return {
    avgRsrp: Number((sumRsrp / valid.length).toFixed(1)),
    minRsrp,
    maxRsrp,
    avgRsrq: Number((sumRsrq / valid.length).toFixed(1)),
    avgSinr: Number((sumSinr / valid.length).toFixed(1)),
    avgRssi: Number((sumRssi / valid.length).toFixed(1)),
    coveragePercent: Number(((coveredCount / valid.length) * 100).toFixed(1)),
    totalMeasurements: measurements.length,
  };
}

export function calculateTimeSeries(measurements: Measurement[]): TimePoint[] {
  if (measurements.length === 0) return [];
  const sorted = [...measurements].sort((a, b) => a.timestampMs - b.timestampMs);
  const startMs = sorted[0].timestampMs;
  return sorted.map((m) => ({
    relativeMs: m.timestampMs - startMs,
    timestampMs: m.timestampMs,
    rsrp: m.radio.rsrp,
    rsrq: m.radio.rsrq,
    sinr: m.radio.sinr,
    technology: m.radio.technology,
    cellId: m.radio.cellId,
    pci: m.radio.pci,
  }));
}

export function calculateCdf(measurements: Measurement[]): CdfPoint[] {
  const valid = measurements
    .map((m) => m.radio.rsrp)
    .filter((rsrp) => rsrp > -140 && rsrp <= -40)
    .sort((a, b) => a - b);

  if (valid.length === 0) return [];

  return valid.map((rsrp, idx) => ({
    rsrp,
    percentile: Number((((idx + 1) / valid.length) * 100).toFixed(1)),
  }));
}

export function calculateTechBreakdown(measurements: Measurement[]): {
  techBreakdown: TechBreakdownItem[];
  nrUsagePercent: number;
} {
  if (measurements.length === 0) {
    return { techBreakdown: [], nrUsagePercent: 0 };
  }

  const counts = new Map<CellularTechnology, number>();
  let nrSamples = 0;

  for (const m of measurements) {
    const tech = m.radio.technology;
    counts.set(tech, (counts.get(tech) ?? 0) + 1);
    if (
      tech === 'NR_5G' ||
      m.radio.nrStatus === 'SA' ||
      m.radio.nrStatus === 'NSA'
    ) {
      nrSamples++;
    }
  }

  const total = measurements.length;
  const order: CellularTechnology[] = ['NR_5G', 'LTE_4G', 'WCDMA_3G', 'GSM_2G', 'UNKNOWN'];
  const techBreakdown: TechBreakdownItem[] = [];

  for (const tech of order) {
    const c = counts.get(tech) ?? 0;
    if (c > 0) {
      techBreakdown.push({
        technology: tech,
        count: c,
        percent: Number(((c / total) * 100).toFixed(1)),
      });
    }
  }

  return {
    techBreakdown,
    nrUsagePercent: Number(((nrSamples / total) * 100).toFixed(1)),
  };
}

export function getBandFrequencyLabel(band: number, isNr: boolean, dlFrequencyKhz?: number): string {
  const knownNr: Record<number, string> = {
    1: 'n1 · 2100 MHz',
    3: 'n3 · 1800 MHz',
    8: 'n8 · 900 MHz',
    28: 'n28 · 700 MHz',
    41: 'n41 · 2600 MHz',
    77: 'n77 · 3700 MHz',
    78: 'n78 · 3500 MHz',
    257: 'n257 · 28 GHz',
    258: 'n258 · 26 GHz',
  };
  const knownLte: Record<number, string> = {
    1: 'B1 · 2100 MHz',
    3: 'B3 · 1800 MHz',
    7: 'B7 · 2600 MHz',
    8: 'B8 · 900 MHz',
    20: 'B20 · 800 MHz',
    28: 'B28 · 700 MHz',
    38: 'B38 · 2600 TDD',
    40: 'B40 · 2300 TDD',
    41: 'B41 · 2500 TDD',
  };

  if (isNr && knownNr[band]) return knownNr[band];
  if (!isNr && knownLte[band]) return knownLte[band];
  if (dlFrequencyKhz && dlFrequencyKhz > 0) {
    const mhz = Math.round(dlFrequencyKhz / 1000);
    return `${isNr ? 'n' : 'B'}${band} · ${mhz} MHz`;
  }
  return `${isNr ? 'n' : 'B'}${band}`;
}

export function calculateBandUsage(measurements: Measurement[]): BandUsage[] {
  if (measurements.length === 0) return [];

  const map = new Map<string, { band: number; isNr: boolean; freqLabel: string; count: number }>();
  for (const m of measurements) {
    const isNr = m.radio.technology === 'NR_5G' || m.radio.nrStatus === 'SA';
    const key = `${isNr ? 'N' : 'L'}_${m.radio.band}`;
    const existing = map.get(key);
    if (existing) {
      existing.count++;
    } else {
      map.set(key, {
        band: m.radio.band,
        isNr,
        freqLabel: getBandFrequencyLabel(m.radio.band, isNr, m.radio.dlFrequencyKhz),
        count: 1,
      });
    }
  }

  const total = measurements.length;
  return Array.from(map.values())
    .sort((a, b) => b.count - a.count)
    .map((item) => ({
      ...item,
      percent: Number(((item.count / total) * 100).toFixed(1)),
    }));
}

export function extractCellSites(measurements: Measurement[]): CellSite[] {
  const siteMap = new Map<
    number,
    {
      cellId: number;
      pci: number;
      technology: CellularTechnology;
      band: number;
      earfcn: number;
      count: number;
      sumRsrp: number;
      firstSeenMs: number;
      sumLat: number;
      sumLon: number;
    }
  >();

  for (const m of measurements) {
    const cid = m.radio.cellId;
    const existing = siteMap.get(cid);
    if (existing) {
      existing.count++;
      existing.sumRsrp += m.radio.rsrp;
      existing.sumLat += m.gps.latitude;
      existing.sumLon += m.gps.longitude;
      if (m.timestampMs < existing.firstSeenMs) {
        existing.firstSeenMs = m.timestampMs;
      }
    } else {
      siteMap.set(cid, {
        cellId: cid,
        pci: m.radio.pci,
        technology: m.radio.technology,
        band: m.radio.band,
        earfcn: m.radio.earfcn,
        count: 1,
        sumRsrp: m.radio.rsrp,
        firstSeenMs: m.timestampMs,
        sumLat: m.gps.latitude,
        sumLon: m.gps.longitude,
      });
    }
  }

  return Array.from(siteMap.values())
    .sort((a, b) => b.count - a.count)
    .map((s) => ({
      cellId: s.cellId,
      pci: s.pci,
      technology: s.technology,
      band: s.band,
      earfcn: s.earfcn,
      count: s.count,
      avgRsrp: Number((s.sumRsrp / s.count).toFixed(1)),
      firstSeenMs: s.firstSeenMs,
      latitude: Number((s.sumLat / s.count).toFixed(6)),
      longitude: Number((s.sumLon / s.count).toFixed(6)),
    }));
}

export function detectMobilityEvents(
  measurements: Measurement[],
  missedHandoverMarginDb = 6
): MobilityStats {
  if (measurements.length === 0) {
    return {
      handovers: 0,
      pingPong: 0,
      distinctCells: 0,
      missedHandovers: 0,
      handoverEvents: [],
      missedHandoverEvents: [],
    };
  }

  const sorted = [...measurements].sort((a, b) => a.timestampMs - b.timestampMs);
  const distinctCellIds = new Set<number>();
  const handoverEvents: HandoverEvent[] = [];
  const missedHandoverEvents: MissedHandoverEvent[] = [];

  // Track recent cell transitions to detect ping-pong (<30 seconds return to prior cell)
  const lastLeftTimestampByCell = new Map<number, number>();

  for (let i = 0; i < sorted.length; i++) {
    const current = sorted[i];
    distinctCellIds.add(current.radio.cellId);

    if (i > 0) {
      const prev = sorted[i - 1];
      if (current.radio.cellId !== prev.radio.cellId) {
        const lastLeftTime = lastLeftTimestampByCell.get(current.radio.cellId);
        const isPingPong =
          lastLeftTime !== undefined && current.timestampMs - lastLeftTime < 30_000;

        lastLeftTimestampByCell.set(prev.radio.cellId, current.timestampMs);

        handoverEvents.push({
          id: `ho_${current.id}_${current.timestampMs}`,
          measurement: current,
          previousCellId: prev.radio.cellId,
          previousPci: prev.radio.pci,
          previousTechnology: prev.radio.technology,
          newCellId: current.radio.cellId,
          newPci: current.radio.pci,
          newTechnology: current.radio.technology,
          isPingPong,
        });
      }
    }

    // Check neighbor cells for missed handovers
    if (current.radio.neighborCells && current.radio.neighborCells.length > 0) {
      let strongestNeighbor = current.radio.neighborCells[0];
      for (const n of current.radio.neighborCells) {
        if (n.rsrp > strongestNeighbor.rsrp) {
          strongestNeighbor = n;
        }
      }
      const margin = strongestNeighbor.rsrp - current.radio.rsrp;
      if (
        strongestNeighbor.pci !== current.radio.pci &&
        margin >= missedHandoverMarginDb
      ) {
        missedHandoverEvents.push({
          id: `mho_${current.id}_${current.timestampMs}`,
          measurement: current,
          servingPci: current.radio.pci,
          servingRsrp: current.radio.rsrp,
          neighborPci: strongestNeighbor.pci,
          neighborRsrp: strongestNeighbor.rsrp,
          neighborTechnology: strongestNeighbor.technology,
          marginDb: margin,
        });
      }
    }
  }

  const pingPong = handoverEvents.filter((h) => h.isPingPong).length;

  return {
    handovers: handoverEvents.length,
    pingPong,
    distinctCells: distinctCellIds.size,
    missedHandovers: missedHandoverEvents.length,
    handoverEvents,
    missedHandoverEvents,
  };
}

export function calculateCampaignDiagnostic(
  measurements: Measurement[],
  speedTests: SpeedTestResult[],
  sessionEvents: SessionEvent[],
  thresholds: DiagnosticThresholds = DEFAULT_DIAGNOSTIC_THRESHOLDS
): CampaignDiagnostic {
  if (measurements.length === 0) {
    return {
      score: 100,
      weakSignalPercent: 0,
      poorQualityPercent: 0,
      handovers: 0,
      pingPongCount: 0,
      issues: [],
      verdict: 'EXCELLENT',
    };
  }

  const sorted = [...measurements].sort((a, b) => a.timestampMs - b.timestampMs);
  const mobility = detectMobilityEvents(sorted);
  const issues: DiagnosticIssue[] = [];

  const weakSamples = sorted.filter((m) => m.radio.rsrp < thresholds.weakRsrp);
  const poorQualitySamples = sorted.filter((m) => m.radio.rsrq < thresholds.poorRsrq);

  const weakSignalPercent = Number(((weakSamples.length / sorted.length) * 100).toFixed(1));
  const poorQualityPercent = Number(((poorQualitySamples.length / sorted.length) * 100).toFixed(1));

  // Cluster weak signal zones
  if (weakSamples.length > 0) {
    const avgRsrp =
      weakSamples.reduce((acc, m) => acc + m.radio.rsrp, 0) / weakSamples.length;
    const avgRsrq =
      weakSamples.reduce((acc, m) => acc + m.radio.rsrq, 0) / weakSamples.length;
    const mid = weakSamples[Math.floor(weakSamples.length / 2)];
    issues.push({
      id: `issue_weak_${mid.timestampMs}`,
      type: 'WEAK_SIGNAL',
      startMs: weakSamples[0].timestampMs,
      endMs: weakSamples[weakSamples.length - 1].timestampMs,
      samples: weakSamples.length,
      averageRsrp: Number(avgRsrp.toFixed(1)),
      averageRsrq: Number(avgRsrq.toFixed(1)),
      downloadMbps: 0,
      loadedPingMs: 0,
      latitude: mid.gps.latitude,
      longitude: mid.gps.longitude,
      cellId: mid.radio.cellId,
      pci: mid.radio.pci,
    });
  }

  // Cluster poor radio quality zones
  if (poorQualitySamples.length > 0) {
    const avgRsrp =
      poorQualitySamples.reduce((acc, m) => acc + m.radio.rsrp, 0) /
      poorQualitySamples.length;
    const avgRsrq =
      poorQualitySamples.reduce((acc, m) => acc + m.radio.rsrq, 0) /
      poorQualitySamples.length;
    const mid = poorQualitySamples[Math.floor(poorQualitySamples.length / 2)];
    issues.push({
      id: `issue_qual_${mid.timestampMs}`,
      type: 'POOR_QUALITY',
      startMs: poorQualitySamples[0].timestampMs,
      endMs: poorQualitySamples[poorQualitySamples.length - 1].timestampMs,
      samples: poorQualitySamples.length,
      averageRsrp: Number(avgRsrp.toFixed(1)),
      averageRsrq: Number(avgRsrq.toFixed(1)),
      downloadMbps: 0,
      loadedPingMs: 0,
      latitude: mid.gps.latitude,
      longitude: mid.gps.longitude,
      cellId: mid.radio.cellId,
      pci: mid.radio.pci,
    });
  }

  // Frequent handovers / ping-pong
  if (mobility.pingPong >= 1 || mobility.handovers >= Math.max(3, sorted.length * 0.25)) {
    const firstHo = mobility.handoverEvents[0].measurement;
    const lastHo = mobility.handoverEvents[mobility.handoverEvents.length - 1].measurement;
    issues.push({
      id: `issue_ho_${firstHo.timestampMs}`,
      type: 'FREQUENT_HANDOVERS',
      startMs: firstHo.timestampMs,
      endMs: lastHo.timestampMs,
      samples: mobility.handovers,
      averageRsrp: firstHo.radio.rsrp,
      averageRsrq: firstHo.radio.rsrq,
      downloadMbps: 0,
      loadedPingMs: 0,
      latitude: firstHo.gps.latitude,
      longitude: firstHo.gps.longitude,
      cellId: firstHo.radio.cellId,
      pci: firstHo.radio.pci,
    });
  }

  // Speed test throughput & loaded latency issues
  for (const st of speedTests) {
    if (st.status === 'SUCCESS' && st.downloadMbps < thresholds.lowThroughputMbps) {
      issues.push({
        id: `issue_tput_${st.id}`,
        type: 'LOW_THROUGHPUT',
        startMs: st.timestampMs,
        endMs: st.timestampMs,
        samples: 1,
        averageRsrp: st.rsrp,
        averageRsrq: -12,
        downloadMbps: st.downloadMbps,
        loadedPingMs: st.loadedPingMs,
        latitude: st.latitude,
        longitude: st.longitude,
        cellId: st.cellId,
        pci: st.pci,
      });
    }
    if (st.status === 'SUCCESS' && st.loadedPingMs > thresholds.highLoadedLatencyMs) {
      issues.push({
        id: `issue_lat_${st.id}`,
        type: 'HIGH_LOADED_LATENCY',
        startMs: st.timestampMs,
        endMs: st.timestampMs,
        samples: 1,
        averageRsrp: st.rsrp,
        averageRsrq: -12,
        downloadMbps: st.downloadMbps,
        loadedPingMs: st.loadedPingMs,
        latitude: st.latitude,
        longitude: st.longitude,
        cellId: st.cellId,
        pci: st.pci,
      });
    }
  }

  // Also check sessionEvents light probes for high latency
  const highPingProbes = sessionEvents.filter(
    (e) => e.type === 'LIGHT_PROBE' && e.pingMs > thresholds.highLoadedLatencyMs
  );
  if (
    highPingProbes.length > 0 &&
    !issues.some((i) => i.type === 'HIGH_LOADED_LATENCY')
  ) {
    const p = highPingProbes[0];
    issues.push({
      id: `issue_probe_${p.id}`,
      type: 'HIGH_LOADED_LATENCY',
      startMs: p.timestampMs,
      endMs: highPingProbes[highPingProbes.length - 1].timestampMs,
      samples: highPingProbes.length,
      averageRsrp: -102,
      averageRsrq: -13,
      downloadMbps: 0,
      loadedPingMs: p.pingMs,
      latitude: p.latitude,
      longitude: p.longitude,
      cellId: p.cellId,
      pci: 0,
    });
  }

  // Calculate composite 0..100 score
  const weakPenalty = weakSignalPercent * 0.45;
  const qualityPenalty = poorQualityPercent * 0.25;
  const hoPenalty = Math.min(15, mobility.pingPong * 4 + mobility.missedHandovers * 1.5);
  const netPenalty =
    issues.filter((i) => i.type === 'LOW_THROUGHPUT' || i.type === 'HIGH_LOADED_LATENCY')
      .length * 6;

  const rawScore = Math.round(100 - weakPenalty - qualityPenalty - hoPenalty - netPenalty);
  const score = Math.max(0, Math.min(100, rawScore));

  let verdict: CampaignDiagnostic['verdict'] = 'EXCELLENT';
  if (score < 50) verdict = 'CRITICAL';
  else if (score < 70) verdict = 'FAIR';
  else if (score < 85) verdict = 'GOOD';

  return {
    score,
    weakSignalPercent,
    poorQualityPercent,
    handovers: mobility.handovers,
    pingPongCount: mobility.pingPong,
    issues,
    verdict,
  };
}

export function buildAnalyticsSnapshot(
  measurements: Measurement[],
  speedTests: SpeedTestResult[],
  sessionEvents: SessionEvent[],
  thresholds: DiagnosticThresholds = DEFAULT_DIAGNOSTIC_THRESHOLDS
): AnalyticsSnapshot {
  const signalStats = calculateSignalStats(measurements, thresholds.weakRsrp);
  const timeSeries = calculateTimeSeries(measurements);
  const cdfRsrp = calculateCdf(measurements);
  const { techBreakdown, nrUsagePercent } = calculateTechBreakdown(measurements);
  const bandUsage = calculateBandUsage(measurements);
  const cellSites = extractCellSites(measurements);
  const mobilityStats = detectMobilityEvents(measurements);
  const diagnostic = calculateCampaignDiagnostic(
    measurements,
    speedTests,
    sessionEvents,
    thresholds
  );

  return {
    signalStats,
    timeSeries,
    cdfRsrp,
    techBreakdown,
    bandUsage,
    nrUsagePercent,
    cellSites,
    mobilityStats,
    diagnostic,
  };
}

export function compareCampaignMeasurements(
  activeMeasurements: Measurement[],
  referenceMeasurements: Measurement[],
  metric: ComparisonMetric = 'RSRP',
  maxMatchDistanceMeters = 120
): CampaignComparisonResult {
  if (activeMeasurements.length < 2 || referenceMeasurements.length < 2) {
    return {
      points: [],
      matchedCount: 0,
      geographicMatchCount: 0,
      totalCount: activeMeasurements.length,
      avgDelta: 0,
      improvedPercent: 0,
      degradedPercent: 0,
      error: 'NOT_ENOUGH_DATA',
    };
  }

  const getMetricValue = (m: Measurement): number => {
    if (metric === 'RSRP') return m.radio.rsrp;
    if (metric === 'RSRQ') return m.radio.rsrq;
    return m.radio.sinr;
  };

  const points: ComparisonPoint[] = [];
  let improved = 0;
  let degraded = 0;
  let sumDelta = 0;

  for (const act of activeMeasurements) {
    let bestRef: Measurement | null = null;
    let bestDist = Infinity;

    for (const ref of referenceMeasurements) {
      const dist = haversineDistanceMeters(
        act.gps.latitude,
        act.gps.longitude,
        ref.gps.latitude,
        ref.gps.longitude
      );
      if (dist < bestDist) {
        bestDist = dist;
        bestRef = ref;
      }
    }

    if (bestRef && bestDist <= maxMatchDistanceMeters) {
      const activeValue = getMetricValue(act);
      const referenceValue = getMetricValue(bestRef);
      const delta = Number((activeValue - referenceValue).toFixed(2));
      points.push({
        latitude: act.gps.latitude,
        longitude: act.gps.longitude,
        activeValue,
        referenceValue,
        delta,
      });
      sumDelta += delta;
      if (delta >= 1) improved++;
      else if (delta <= -1) degraded++;
    }
  }

  if (points.length === 0) {
    return {
      points: [],
      matchedCount: 0,
      geographicMatchCount: 0,
      totalCount: activeMeasurements.length,
      avgDelta: 0,
      improvedPercent: 0,
      degradedPercent: 0,
      error: 'NO_OVERLAP',
    };
  }

  return {
    points,
    matchedCount: points.length,
    geographicMatchCount: points.length,
    totalCount: activeMeasurements.length,
    avgDelta: Number((sumDelta / points.length).toFixed(2)),
    improvedPercent: Number(((improved / points.length) * 100).toFixed(1)),
    degradedPercent: Number(((degraded / points.length) * 100).toFixed(1)),
    error: null,
  };
}

export function getSignalColor(
  value: number,
  mode: MapColorMode,
  palette: SignalPalette,
  rsrpGood = -90,
  rsrpPoor = -110
): string {
  const palettes: Record<SignalPalette, [string, string, string, string]> = {
    // [Excellent, Good, Fair, Poor]
    CLASSIC: ['#22c55e', '#84cc16', '#f59e0b', '#ef4444'],
    THERMIQUE: ['#fef08a', '#f97316', '#dc2626', '#7f1d1d'],
    VIRIDIS: ['#fde725', '#35b779', '#31688e', '#440154'],
    OCEAN: ['#38bdf8', '#0284c7', '#1d4ed8', '#1e1b4b'],
  };

  const colors = palettes[palette] ?? palettes.CLASSIC;

  if (mode === 'BY_RSRP') {
    const mid = (rsrpGood + rsrpPoor) / 2;
    if (value >= rsrpGood) return colors[0];
    if (value >= mid) return colors[1];
    if (value >= rsrpPoor) return colors[2];
    return colors[3];
  }

  if (mode === 'BY_RSRQ') {
    if (value >= -9) return colors[0];
    if (value >= -12) return colors[1];
    if (value >= -15) return colors[2];
    return colors[3];
  }

  if (mode === 'BY_SINR') {
    if (value >= 20) return colors[0];
    if (value >= 10) return colors[1];
    if (value >= 0) return colors[2];
    return colors[3];
  }

  if (mode === 'BY_SPEED') {
    // speed in m/s (15 m/s = 54 km/h)
    if (value >= 20) return colors[0];
    if (value >= 10) return colors[1];
    if (value >= 3) return colors[2];
    return colors[3];
  }

  return colors[0];
}

export function getTechnologyColor(tech: CellularTechnology): string {
  switch (tech) {
    case 'NR_5G':
      return '#a855f7';
    case 'LTE_4G':
      return '#38bdf8';
    case 'WCDMA_3G':
      return '#f59e0b';
    case 'GSM_2G':
      return '#ef4444';
    default:
      return '#94a3b8';
  }
}

