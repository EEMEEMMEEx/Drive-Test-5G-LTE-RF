/**
 * NOC Live Fleet Telemetry & GIS Dashboard — Drive Test LTE/5G v1.15.0
 * Features:
 * 1. 100% Real-Time Live Fleet Telemetry & Multi-SIM RF Inspector
 * 2. Historical Session Playback with Timeline Scrubber & Marker Animation
 * 3. Multi-Vehicle Coverage Heatmap (IDW Spatial Interpolation - RSRP & SINR)
 */

// ---------------------------------------------------------------------------
// Color Palettes & Thresholds
// ---------------------------------------------------------------------------
const COLORS = {
  tier1: '#10b981', // >= -80 dBm (Excellent)
  tier2: '#38bdf8', // -80 to -90 dBm (Good)
  tier3: '#f59e0b', // -90 to -100 dBm (Fair)
  tier4: '#f97316', // -100 to -110 dBm (Poor)
  tier5: '#ef4444'  // < -110 dBm (Coverage Hole)
};

function getRsrpColor(rsrp) {
  if (rsrp === null || rsrp === undefined || isNaN(rsrp)) return '#94a3b8';
  const val = Number(rsrp);
  if (val >= -80) return COLORS.tier1;
  if (val >= -90) return COLORS.tier2;
  if (val >= -100) return COLORS.tier3;
  if (val >= -110) return COLORS.tier4;
  return COLORS.tier5;
}

function getSinrColor(sinr) {
  if (sinr === null || sinr === undefined || isNaN(sinr)) return '#94a3b8';
  const val = Number(sinr);
  if (val >= 20) return COLORS.tier1;
  if (val >= 13) return COLORS.tier2;
  if (val >= 5)  return COLORS.tier3;
  if (val >= 0)  return COLORS.tier4;
  return COLORS.tier5;
}

function calculateBearing(lat1, lon1, lat2, lon2) {
  const toRad = (deg) => (deg * Math.PI) / 180;
  const toDeg = (rad) => (rad * 180) / Math.PI;
  const dLon = toRad(lon2 - lon1);
  const y = Math.sin(dLon) * Math.cos(toRad(lat2));
  const x = Math.cos(toRad(lat1)) * Math.sin(toRad(lat2)) - Math.sin(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.cos(dLon);
  return (toDeg(Math.atan2(y, x)) + 360) % 360;
}

// ---------------------------------------------------------------------------
// Global NOC State
// ---------------------------------------------------------------------------
const state = {
  mode: 'LIVE', // 'LIVE' | 'PLAYBACK'
  vehicles: new Map(), // vehicleId -> { data, marker, polyline, trail: [], rfPoints: [] }
  selectedVehicleId: null,
  alerts: [],
  totalSamples: 0,
  firebaseUrl: '',
  firebaseToken: '',
  pollTimer: null,

  // Historical Session Playback
  playback: {
    sessions: new Map(), // sessionId -> metadata
    currentSessionId: null,
    samples: [], // array of { lat, lon, rsrp, sinr, pci, rat, speed, ts, ... }
    currentIndex: 0,
    isPlaying: false,
    speed: 1, // 1, 2, 5
    playTimer: null,
    marker: null,
    fullPolyline: null,
    playedCasing: null,
    playedPolyline: null
  },

  // Multi-Vehicle Coverage Heatmap
  heatmap: {
    enabled: false,
    metric: 'RSRP', // 'RSRP' | 'SINR'
    opacity: 0.52,
    layer: null
  }
};

// ---------------------------------------------------------------------------
// Formatting Helpers (Consistent Dates, RAT Labels, Numbers)
// ---------------------------------------------------------------------------
const SHORT_MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function formatSessionDate(ts) {
  if (!ts) return '—';
  const d = new Date(Number(ts));
  if (isNaN(d.getTime())) return '—';
  const day = String(d.getDate()).padStart(2, '0');
  const mon = SHORT_MONTHS[d.getMonth()];
  const beYear = d.getFullYear() + 543;
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${day} ${mon} ${beYear} · ${hh}:${mm}`;
}

function formatTime24(ts, includeSeconds = true) {
  if (!ts) return includeSeconds ? '--:--:--' : '--:--';
  const d = new Date(Number(ts));
  if (isNaN(d.getTime())) return includeSeconds ? '--:--:--' : '--:--';
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  if (!includeSeconds) return `${hh}:${mm}`;
  const ss = String(d.getSeconds()).padStart(2, '0');
  return `${hh}:${mm}:${ss}`;
}

function formatRatLabel(rat) {
  if (!rat) return 'N/A';
  const upper = String(rat).toUpperCase().trim();
  if (upper === 'NR_NSA') return '5G NSA';
  if (upper === 'NR_SA') return '5G SA';
  if (upper === 'UNKNOWN') return 'N/A';
  return upper;
}

function formatCount(n) {
  const num = Number(n);
  if (isNaN(num)) return '0';
  return num.toLocaleString('en-US');
}

// ---------------------------------------------------------------------------
// Leaflet Map Initialization
// ---------------------------------------------------------------------------
let map;
function initMap() {
  map = L.map('map', {
    center: [13.7563, 100.5018], // Bangkok Center
    zoom: 12,
    zoomControl: false
  });

  // Position dark-themed zoom control on bottom-right so top toolbar never overlaps it
  L.control.zoom({ position: 'bottomright' }).addTo(map);

  // OpenStreetMap with Dark NOC Styling
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    maxZoom: 19,
    className: 'dark-noc-tile'
  }).addTo(map);

  initHeatmapLayer();
}

// ---------------------------------------------------------------------------
// Coverage Heatmap Layer Setup
// ---------------------------------------------------------------------------
function initHeatmapLayer() {
  if (state.heatmap.layer) return;

  const LayerClass = (typeof RfHeatmapLayer !== 'undefined')
    ? RfHeatmapLayer
    : (window.L && window.L.RfHeatmapLayer ? window.L.RfHeatmapLayer : null);

  if (LayerClass) {
    state.heatmap.layer = new LayerClass({
      radius: 52,
      cellSize: 5,
      power: 2,
      opacity: state.heatmap.opacity,
      metric: state.heatmap.metric,
      visible: state.heatmap.enabled
    });
    state.heatmap.layer.addTo(map);
  }
}

function setHeatmapOpacity(percent) {
  const clamped = Math.max(15, Math.min(95, Number(percent) || 52));
  state.heatmap.opacity = clamped / 100;
  const valEl = document.getElementById('heatmap-opacity-val');
  if (valEl) valEl.textContent = `${clamped}%`;
  if (state.heatmap.layer) {
    state.heatmap.layer.setOptions({ opacity: state.heatmap.opacity });
  }
}

function updateHeatmapData() {
  if (!state.heatmap.layer) {
    initHeatmapLayer();
  }
  if (!state.heatmap.layer) return;

  let points = [];
  if (state.mode === 'LIVE') {
    state.vehicles.forEach((entry) => {
      if (entry.rfPoints && entry.rfPoints.length > 0) {
        points.push(...entry.rfPoints);
      } else if (entry.data && entry.data.lat && entry.data.lon) {
        points.push({
          lat: entry.data.lat,
          lon: entry.data.lon,
          rsrp: entry.data.rsrp,
          sinr: entry.data.sinr
        });
      }
    });
  } else if (state.mode === 'PLAYBACK') {
    points = state.playback.samples.map(p => ({
      lat: p.lat,
      lon: p.lon,
      rsrp: p.rsrp,
      sinr: p.sinr
    }));
  }

  state.heatmap.layer.setMetric(state.heatmap.metric);
  state.heatmap.layer.setData(points);
}

function toggleHeatmap(forcedState) {
  state.heatmap.enabled = (forcedState !== undefined) ? Boolean(forcedState) : !state.heatmap.enabled;

  const btn = document.getElementById('btn-toggle-heatmap');
  const label = document.getElementById('heatmap-btn-label');
  const metricSelector = document.getElementById('heatmap-metric-selector');

  if (state.heatmap.enabled) {
    btn.classList.add('active');
    btn.setAttribute('aria-pressed', 'true');
    label.textContent = 'HEATMAP: ON';
    metricSelector.style.display = 'flex';

    if (!state.heatmap.layer) initHeatmapLayer();
    if (state.heatmap.layer) {
      state.heatmap.layer.setVisible(true);
      updateHeatmapData();
    }
  } else {
    btn.classList.remove('active');
    btn.setAttribute('aria-pressed', 'false');
    label.textContent = 'HEATMAP: OFF';
    metricSelector.style.display = 'none';

    if (state.heatmap.layer) {
      state.heatmap.layer.setVisible(false);
    }
  }

  updateLegendForMetric();
}

function setHeatmapMetric(metric) {
  const m = String(metric).toUpperCase();
  if (m !== 'RSRP' && m !== 'SINR') return;

  state.heatmap.metric = m;

  document.querySelectorAll('.metric-pill').forEach(btn => {
    const active = btn.getAttribute('data-metric') === m;
    btn.classList.toggle('active', active);
    btn.setAttribute('aria-pressed', active ? 'true' : 'false');
  });

  if (state.heatmap.layer) {
    state.heatmap.layer.setMetric(m);
  }

  updateLegendForMetric();
}

function updateLegendForMetric() {
  const titleEl = document.getElementById('legend-title');
  const scaleEl = document.getElementById('legend-scale');
  if (!titleEl || !scaleEl) return;

  if (state.heatmap.enabled && state.heatmap.metric === 'SINR') {
    titleEl.textContent = 'SINR SIGNAL TIERS (3GPP)';
    scaleEl.innerHTML = `
      <div class="scale-item"><span class="tier-pill bg-tier1">T1</span><span class="tier-range">&ge; 20 dB</span><span class="tier-name">Excellent</span></div>
      <div class="scale-item"><span class="tier-pill bg-tier2">T2</span><span class="tier-range">13 to 20 dB</span><span class="tier-name">Good</span></div>
      <div class="scale-item"><span class="tier-pill bg-tier3">T3</span><span class="tier-range">5 to 13 dB</span><span class="tier-name">Fair</span></div>
      <div class="scale-item"><span class="tier-pill bg-tier4">T4</span><span class="tier-range">0 to 5 dB</span><span class="tier-name">Poor</span></div>
      <div class="scale-item"><span class="tier-pill bg-tier5">T5</span><span class="tier-range">&lt; 0 dB</span><span class="tier-name">Interf.</span></div>
    `;
  } else {
    titleEl.textContent = 'RSRP SIGNAL TIERS (3GPP / ITU-R)';
    scaleEl.innerHTML = `
      <div class="scale-item"><span class="tier-pill bg-tier1">T1</span><span class="tier-range">&ge; -80 dBm</span><span class="tier-name">Excellent</span></div>
      <div class="scale-item"><span class="tier-pill bg-tier2">T2</span><span class="tier-range">-80 to -90 dBm</span><span class="tier-name">Good</span></div>
      <div class="scale-item"><span class="tier-pill bg-tier3">T3</span><span class="tier-range">-90 to -100 dBm</span><span class="tier-name">Fair</span></div>
      <div class="scale-item"><span class="tier-pill bg-tier4">T4</span><span class="tier-range">-100 to -110 dBm</span><span class="tier-name">Poor</span></div>
      <div class="scale-item"><span class="tier-pill bg-tier5">T5</span><span class="tier-range">&lt; -110 dBm</span><span class="tier-name">Hole</span></div>
    `;
  }
}

// ---------------------------------------------------------------------------
// Vehicle Marker Icon Factories
// ---------------------------------------------------------------------------
function createVehicleIcon(vehicleId, bearing = 0, alertState = 'NORMAL') {
  const isAlert = alertState === 'COVERAGE_HOLE' || alertState === 'INTERFERENCE';
  const glowColor = isAlert ? '#ef4444' : '#38bdf8';

  const html = `
    <div class="custom-vehicle-marker" style="transform: rotate(${bearing}deg);">
      <div class="vehicle-halo" style="box-shadow: 0 0 14px ${glowColor}; border-color: ${glowColor};"></div>
      <svg width="26" height="26" viewBox="0 0 24 24" fill="${glowColor}" stroke="#ffffff" stroke-width="1.8" stroke-linejoin="round">
        <path d="M12 2L4.5 20.29l.71.71L12 18l6.79 3 .71-.71z"/>
      </svg>
      <div class="vehicle-marker-tag" style="transform: rotate(-${bearing}deg); border-color: ${glowColor};">${vehicleId}</div>
    </div>
  `;
  return L.divIcon({
    html: html,
    className: 'leaflet-vehicle-icon',
    iconSize: [34, 34],
    iconAnchor: [17, 17]
  });
}

function createPlaybackMarkerIcon(label = 'PLAYBACK', bearing = 0, rsrp = -85) {
  const color = getRsrpColor(rsrp);
  const html = `
    <div class="custom-playback-marker" style="transform: rotate(${bearing}deg);">
      <div class="playback-marker-halo" style="border-color: ${color}; box-shadow: 0 0 18px ${color};"></div>
      <svg width="26" height="26" viewBox="0 0 24 24" fill="${color}" stroke="#ffffff" stroke-width="1.8" stroke-linejoin="round">
        <path d="M12 2L4.5 20.29l.71.71L12 18l6.79 3 .71-.71z"/>
      </svg>
      <div class="playback-marker-tag" style="transform: rotate(-${bearing}deg); border-color: ${color};">${label}</div>
    </div>
  `;
  return L.divIcon({
    html: html,
    className: 'leaflet-vehicle-icon',
    iconSize: [34, 34],
    iconAnchor: [17, 17]
  });
}

// ---------------------------------------------------------------------------
// Live Fleet Telemetry Management
// ---------------------------------------------------------------------------
function updateVehicle(data) {
  const vId = data.vehicleId;
  if (!vId) return;

  state.totalSamples++;
  document.getElementById('kpi-sample-count').textContent = state.totalSamples.toLocaleString();

  let entry = state.vehicles.get(vId);
  const latLng = [data.lat, data.lon];
  const rfPoint = {
    lat: data.lat,
    lon: data.lon,
    rsrp: data.rsrp,
    sinr: data.sinr
  };

  if (!entry) {
    const marker = L.marker(latLng, {
      icon: createVehicleIcon(vId, data.bearing || 0, data.alertState)
    });

    if (state.mode === 'LIVE') {
      marker.addTo(map);
    }

    marker.on('click', () => selectVehicle(vId));

    const polyline = L.polyline([], {
      color: getRsrpColor(data.rsrp),
      weight: 4,
      opacity: 0.85
    });

    if (state.mode === 'LIVE') {
      polyline.addTo(map);
    }

    entry = {
      data: data,
      marker: marker,
      polyline: polyline,
      trail: [latLng],
      rfPoints: [rfPoint]
    };
    state.vehicles.set(vId, entry);

    if (!state.selectedVehicleId && state.mode === 'LIVE') {
      selectVehicle(vId);
    }
  } else {
    entry.data = data;
    entry.marker.setLatLng(latLng);
    entry.marker.setIcon(createVehicleIcon(vId, data.bearing || 0, data.alertState));

    entry.trail.push(latLng);
    entry.rfPoints.push(rfPoint);
    if (entry.trail.length > 500) entry.trail.shift();
    if (entry.rfPoints.length > 500) entry.rfPoints.shift();

    entry.polyline.setLatLngs(entry.trail);
    entry.polyline.setStyle({ color: getRsrpColor(data.rsrp) });
  }

  // Anomaly Alerts
  if (data.alertState === 'COVERAGE_HOLE') {
    addAlert({
      type: 'COVERAGE_HOLE',
      title: `Coverage Hole Detected (${data.rsrp} dBm)`,
      desc: `${vId} (${data.vehicleName || 'Field Unit'}) RSRP dropped below -110 dBm at PCI ${data.pci || 'N/A'}`,
      vehicleId: vId,
      lat: data.lat,
      lon: data.lon,
      time: new Date()
    });
  } else if (data.alertState === 'INTERFERENCE') {
    addAlert({
      type: 'INTERFERENCE',
      title: `High Interference Area (SINR ${Number(data.sinr || 0).toFixed(1)} dB)`,
      desc: `${vId} Strong RSRP (${data.rsrp} dBm) but poor SINR, potential pilot pollution / cross-feeder`,
      vehicleId: vId,
      lat: data.lat,
      lon: data.lon,
      time: new Date()
    });
  }

  renderVehicleRoster();

  if (state.mode === 'LIVE' && state.selectedVehicleId === vId) {
    renderInspector(data);
  }

  if (state.mode === 'LIVE' && state.heatmap.enabled) {
    updateHeatmapData();
  }
}

function renderVehicleRoster() {
  const listEl = document.getElementById('vehicle-list');
  const countEl = document.getElementById('roster-badge');
  const kpiActive = document.getElementById('kpi-active-count');
  const mobFleetCount = document.getElementById('mobile-fleet-count');

  if (state.mode === 'LIVE') {
    countEl.textContent = `${state.vehicles.size} ACTIVE`;
    kpiActive.textContent = state.vehicles.size;
    if (mobFleetCount) mobFleetCount.textContent = state.vehicles.size;
  }

  if (state.vehicles.size === 0) {
    listEl.innerHTML = `
      <div class="empty-state">
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#38bdf8" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" style="margin-bottom: 8px;">
          <path d="M4.93 4.93a10 10 0 0 1 14.14 0"/>
          <path d="M7.76 7.76a6 6 0 0 1 8.48 0"/>
          <circle cx="12" cy="12" r="2"/>
          <path d="M12 14v8"/>
        </svg>
        <div style="font-weight: 700; color: #38bdf8; margin-bottom: 4px; font-size: 12px; letter-spacing: 0.5px;">WAITING FOR FIELD VEHICLES</div>
        <div style="font-size: 11px; color: #94a3b8; line-height: 1.5;">
          เชื่อมต่อฐานข้อมูลสำเร็จ!<br>
          กำลังรอรับข้อมูลจากรถทดสอบภาคสนาม...<br>
          เปิดแอปมือถือแล้วกดปุ่ม <b>START</b> เพื่อเริ่มสตรีมสด
        </div>
      </div>
    `;
    return;
  }

  let html = '';
  state.vehicles.forEach((entry, vId) => {
    const d = entry.data;
    const isSelected = state.selectedVehicleId === vId;
    const rsrpColor = getRsrpColor(d.rsrp);

    html += `
      <div class="vehicle-card ${isSelected ? 'selected' : ''}" onclick="selectVehicle('${vId}')">
        <div class="veh-top">
          <span class="veh-id">${d.vehicleId}</span>
          <span class="veh-status status-recording">● STREAMING</span>
        </div>
        <div class="veh-name">${d.operator || 'Field Unit'} • ${d.rat || '5G/LTE'}</div>
        <div class="veh-metrics-row">
          <div class="veh-metrics-item">
            <span class="lbl">RAT / BAND</span>
            <span class="val text-cyan">${d.rat || 'LTE'} ${d.band || ''}</span>
          </div>
          <div class="veh-metrics-item">
            <span class="lbl">RSRP</span>
            <span class="val" style="color: ${rsrpColor};">${d.rsrp !== undefined ? d.rsrp + ' dBm' : '—'}</span>
          </div>
          <div class="veh-metrics-item">
            <span class="lbl">SPEED</span>
            <span class="val">${Math.round(d.speedKmh || 0)} km/h</span>
          </div>
        </div>
      </div>
    `;
  });

  listEl.innerHTML = html;
}

function selectVehicle(vehicleId) {
  state.selectedVehicleId = vehicleId;
  renderVehicleRoster();

  const entry = state.vehicles.get(vehicleId);
  if (entry) {
    renderInspector(entry.data);
    map.panTo([entry.data.lat, entry.data.lon]);

    if (window.innerWidth <= 1024 && window.switchMobileTab) {
      window.switchMobileTab('map');
    }
  }
}

function removeVehicle(vId) {
  const entry = state.vehicles.get(vId);
  if (!entry) return;
  if (entry.marker) map.removeLayer(entry.marker);
  if (entry.polyline) map.removeLayer(entry.polyline);
  state.vehicles.delete(vId);

  if (state.selectedVehicleId === vId) {
    state.selectedVehicleId = null;
    const remaining = Array.from(state.vehicles.keys());
    if (remaining.length > 0) {
      selectVehicle(remaining[0]);
    } else {
      document.getElementById('inspector-vehicle-badge').textContent = 'NO ACTIVE FLEET';
      resetInspector();
    }
  }
}

function clearFleet() {
  state.vehicles.forEach(entry => {
    if (entry.marker) map.removeLayer(entry.marker);
    if (entry.polyline) map.removeLayer(entry.polyline);
  });
  state.vehicles.clear();
  state.selectedVehicleId = null;
  state.alerts = [];
  document.getElementById('kpi-active-count').textContent = '0';
  document.getElementById('kpi-alert-count').textContent = '0';
  document.getElementById('alerts-count-badge').textContent = '0 ALERTS';
  document.getElementById('inspector-vehicle-badge').textContent = 'WAITING FOR DATA';
  document.getElementById('alerts-feed').innerHTML = '<div class="empty-state">No active anomalies detected in current fleet route.</div>';
  const mobDrawer = document.getElementById('mobile-bottom-drawer');
  if (mobDrawer) mobDrawer.classList.remove('visible');
  const mobFleetCount = document.getElementById('mobile-fleet-count');
  if (mobFleetCount) mobFleetCount.textContent = '0';
  renderVehicleRoster();
  resetInspector();
  if (state.heatmap.enabled) updateHeatmapData();
}

// ---------------------------------------------------------------------------
// Dual-SIM Telemetry Inspector Rendering
// ---------------------------------------------------------------------------
function renderInspector(d) {
  if (!d) {
    resetInspector();
    return;
  }

  const vBadge = document.getElementById('inspector-vehicle-badge');
  if (state.mode === 'PLAYBACK') {
    const label = `PLAYBACK • ${d.vehicleId || 'DT-UNIT'}`;
    vBadge.textContent = label;
    vBadge.title = label;
    vBadge.className = 'badge badge-amber';
  } else {
    const label = `${d.vehicleId} • ${d.vehicleName || 'Vehicle'}`;
    vBadge.textContent = label;
    vBadge.title = label;
    vBadge.className = 'badge badge-cyan';
  }

  // SIM 1 (Primary)
  const sim1OpText = d.operator || 'Primary SIM';
  const sim1RatText = d.rat ? `${formatRatLabel(d.rat)} (${d.band || '—'})` : '—';
  const sim1PciText = `${d.pci || '—'} / ${d.cellId || '—'}`;
  const sim1OpEl = document.getElementById('sim1-op');
  const sim1RatEl = document.getElementById('sim1-rat');
  const sim1PciEl = document.getElementById('sim1-pci');
  sim1OpEl.textContent = sim1OpText; sim1OpEl.title = sim1OpText;
  sim1RatEl.textContent = sim1RatText; sim1RatEl.title = sim1RatText;
  sim1PciEl.textContent = sim1PciText; sim1PciEl.title = sim1PciText;

  if (d.rsrp !== undefined && d.rsrp !== null && !isNaN(d.rsrp)) {
    const rsrp1 = Number(d.rsrp);
    document.getElementById('sim1-rsrp').textContent = `${rsrp1} dBm`;
    document.getElementById('sim1-rsrp').style.color = getRsrpColor(rsrp1);
    const rsrpPercent1 = Math.max(5, Math.min(100, Math.round(((rsrp1 + 140) / 100) * 100)));
    const bar1 = document.getElementById('sim1-rsrp-bar');
    bar1.style.width = `${rsrpPercent1}%`;
    bar1.style.backgroundColor = getRsrpColor(rsrp1);
  } else {
    document.getElementById('sim1-rsrp').textContent = '— dBm';
    document.getElementById('sim1-rsrp').style.color = '#94a3b8';
    document.getElementById('sim1-rsrp-bar').style.width = '0%';
  }

  if (d.sinr !== undefined && d.sinr !== null && !isNaN(d.sinr)) {
    const sinr1 = Number(d.sinr);
    document.getElementById('sim1-sinr').textContent = `${sinr1.toFixed(1)} dB`;
    const sinrPercent1 = Math.max(5, Math.min(100, Math.round(((sinr1 + 10) / 40) * 100)));
    document.getElementById('sim1-sinr-bar').style.width = `${sinrPercent1}%`;
  } else {
    document.getElementById('sim1-sinr').textContent = '— dB';
    document.getElementById('sim1-sinr-bar').style.width = '0%';
  }

  // SIM 2 (Secondary / Benchmark)
  const sim2Col = document.getElementById('sim2-column');
  const deltaBox = document.getElementById('delta-box');
  const deltaEl = document.getElementById('delta-val');

  if (d.sim2Rsrp !== undefined && d.sim2Rsrp !== null && !isNaN(d.sim2Rsrp)) {
    if (sim2Col) sim2Col.classList.remove('sim-column--disabled');
    const sim2Rsrp = Math.round(Number(d.sim2Rsrp));
    const sim2OpText = d.sim2Operator || 'SIM 2 (Secondary)';
    const sim2RatText = d.sim2Rat
      ? `${formatRatLabel(d.sim2Rat)} (${d.sim2Band || '—'})`
      : (d.sim2Band ? `LTE (${d.sim2Band})` : '—');
    const sim2PciText = `${d.sim2Pci || '—'}`;

    const sim2OpEl = document.getElementById('sim2-op');
    const sim2RatEl = document.getElementById('sim2-rat');
    const sim2PciEl = document.getElementById('sim2-pci');
    sim2OpEl.textContent = sim2OpText; sim2OpEl.title = sim2OpText;
    sim2RatEl.textContent = sim2RatText; sim2RatEl.title = sim2RatText;
    sim2PciEl.textContent = sim2PciText; sim2PciEl.title = sim2PciText;

    document.getElementById('sim2-rsrp').textContent = `${sim2Rsrp} dBm`;
    document.getElementById('sim2-rsrp').style.color = getRsrpColor(sim2Rsrp);
    const rsrpPercent2 = Math.max(5, Math.min(100, Math.round(((sim2Rsrp + 140) / 100) * 100)));
    const bar2 = document.getElementById('sim2-rsrp-bar');
    bar2.style.width = `${rsrpPercent2}%`;
    bar2.style.backgroundColor = getRsrpColor(sim2Rsrp);

    if (d.sim2Sinr !== undefined && d.sim2Sinr !== null && !isNaN(d.sim2Sinr)) {
      const sim2Sinr = Number(d.sim2Sinr);
      document.getElementById('sim2-sinr').textContent = `${sim2Sinr.toFixed(1)} dB`;
      const sinrPercent2 = Math.max(5, Math.min(100, Math.round(((sim2Sinr + 10) / 40) * 100)));
      document.getElementById('sim2-sinr-bar').style.width = `${sinrPercent2}%`;
    } else {
      document.getElementById('sim2-sinr').textContent = '— dB';
      document.getElementById('sim2-sinr-bar').style.width = '0%';
    }

    // Delta
    if (d.rsrp !== undefined && d.rsrp !== null && !isNaN(d.rsrp)) {
      const delta = Number(d.rsrp) - sim2Rsrp;
      if (delta >= 0) {
        const msg = `SIM 1 +${delta.toFixed(1)} dB`;
        deltaEl.textContent = msg;
        deltaEl.title = `SIM 1 dominant by +${delta.toFixed(1)} dB over SIM 2`;
        deltaEl.className = 'delta-value text-emerald';
        if (deltaBox) deltaBox.className = 'delta-box';
      } else {
        const msg = `SIM 2 +${Math.abs(delta).toFixed(1)} dB`;
        deltaEl.textContent = msg;
        deltaEl.title = `SIM 2 dominant by +${Math.abs(delta).toFixed(1)} dB over SIM 1`;
        deltaEl.className = 'delta-value text-amber';
        if (deltaBox) deltaBox.className = 'delta-box delta-box--amber';
      }
    }
  } else {
    if (sim2Col) sim2Col.classList.add('sim-column--disabled');
    const sim2OpEl = document.getElementById('sim2-op');
    const sim2RatEl = document.getElementById('sim2-rat');
    const sim2PciEl = document.getElementById('sim2-pci');
    sim2OpEl.textContent = 'N/A'; sim2OpEl.title = 'SIM 2 inactive (Single SIM capture)';
    sim2RatEl.textContent = '—'; sim2RatEl.title = 'Not available in Single SIM mode';
    sim2PciEl.textContent = '—'; sim2PciEl.title = 'Not available in Single SIM mode';
    document.getElementById('sim2-rsrp').textContent = '—';
    document.getElementById('sim2-rsrp').style.color = '';
    document.getElementById('sim2-rsrp-bar').style.width = '0%';
    document.getElementById('sim2-sinr').textContent = '—';
    document.getElementById('sim2-sinr-bar').style.width = '0%';

    deltaEl.textContent = 'Single SIM · No Benchmark';
    deltaEl.title = 'Single SIM Mode — no secondary benchmark SIM recorded';
    deltaEl.className = 'delta-value text-slate';
    if (deltaBox) deltaBox.className = 'delta-box delta-box--neutral';
  }

  // Update Mobile Floating Bottom Drawer
  const mobDrawer = document.getElementById('mobile-bottom-drawer');
  if (mobDrawer) {
    mobDrawer.classList.add('visible');
    const idEl = document.getElementById('mob-drawer-id');
    const opEl = document.getElementById('mob-drawer-op');
    const ratEl = document.getElementById('mob-drawer-rat');
    const rsrpEl = document.getElementById('mob-drawer-rsrp');
    const sinrEl = document.getElementById('mob-drawer-sinr');
    const speedEl = document.getElementById('mob-drawer-speed');

    if (idEl) idEl.textContent = d.vehicleId || 'DT-UNIT';
    if (opEl) opEl.textContent = d.operator || 'Primary SIM';
    if (ratEl) ratEl.textContent = `${d.rat || '—'} ${d.band || ''}`.trim() || '—';
    if (rsrpEl) {
      if (d.rsrp !== undefined && d.rsrp !== null && !isNaN(d.rsrp)) {
        rsrpEl.textContent = `${d.rsrp} dBm`;
        rsrpEl.style.color = getRsrpColor(Number(d.rsrp));
      } else {
        rsrpEl.textContent = '— dBm';
        rsrpEl.style.color = '#94a3b8';
      }
    }
    if (sinrEl) {
      if (d.sinr !== undefined && d.sinr !== null && !isNaN(d.sinr)) {
        sinrEl.textContent = `${Number(d.sinr).toFixed(1)} dB`;
      } else {
        sinrEl.textContent = '— dB';
      }
    }
    if (speedEl) {
      speedEl.textContent = `${Math.round(d.speedKmh || d.speed || 0)} km/h`;
    }
  }
}

function resetInspector() {
  const vBadge = document.getElementById('inspector-vehicle-badge');
  if (vBadge) {
    vBadge.textContent = 'NO VEHICLE';
    vBadge.title = 'No vehicle selected';
    vBadge.className = 'badge badge-cyan';
  }

  document.getElementById('sim1-op').textContent = '—';
  document.getElementById('sim1-rat').textContent = '—';
  document.getElementById('sim1-pci').textContent = '—';
  document.getElementById('sim1-rsrp').textContent = '— dBm';
  document.getElementById('sim1-rsrp').style.color = '#94a3b8';
  document.getElementById('sim1-rsrp-bar').style.width = '0%';
  document.getElementById('sim1-sinr').textContent = '— dB';
  document.getElementById('sim1-sinr-bar').style.width = '0%';

  const sim2Col = document.getElementById('sim2-column');
  if (sim2Col) sim2Col.classList.add('sim-column--disabled');
  document.getElementById('sim2-op').textContent = 'N/A';
  document.getElementById('sim2-rat').textContent = '—';
  document.getElementById('sim2-pci').textContent = '—';
  document.getElementById('sim2-rsrp').textContent = '—';
  document.getElementById('sim2-rsrp').style.color = '';
  document.getElementById('sim2-rsrp-bar').style.width = '0%';
  document.getElementById('sim2-sinr').textContent = '—';
  document.getElementById('sim2-sinr-bar').style.width = '0%';

  const deltaBox = document.getElementById('delta-box');
  if (deltaBox) deltaBox.className = 'delta-box delta-box--neutral';
  const deltaEl = document.getElementById('delta-val');
  deltaEl.textContent = '—';
  deltaEl.title = 'No active benchmark comparison';
  deltaEl.className = 'delta-value text-slate';

  const mobDrawer = document.getElementById('mobile-bottom-drawer');
  if (mobDrawer && state.vehicles.size === 0 && state.mode === 'LIVE') {
    mobDrawer.classList.remove('visible');
  }
}

// ---------------------------------------------------------------------------
// Anomaly Alert Feed
// ---------------------------------------------------------------------------
function addAlert(alert) {
  const isDuplicate = state.alerts.some(a =>
    a.vehicleId === alert.vehicleId &&
    a.type === alert.type &&
    (alert.time - a.time) < 15000
  );
  if (isDuplicate) return;

  state.alerts.unshift(alert);
  if (state.alerts.length > 50) state.alerts.pop();

  document.getElementById('kpi-alert-count').textContent = state.alerts.length;
  document.getElementById('alerts-count-badge').textContent = `${state.alerts.length} ALERTS`;

  renderAlerts();
}

function renderAlerts() {
  const feed = document.getElementById('alerts-feed');
  if (state.alerts.length === 0) {
    feed.innerHTML = `<div class="empty-state">No active anomalies detected in current fleet route.</div>`;
    return;
  }

  let html = '';
  state.alerts.slice(0, 15).forEach((a) => {
    const isWarn = a.type === 'INTERFERENCE';
    const timeStr = a.time.toLocaleTimeString();

    html += `
      <div class="alert-item ${isWarn ? 'alert-warning' : ''}" onclick="flyToCoords(${a.lat}, ${a.lon})">
        <div class="alert-top">
          <span class="alert-title ${isWarn ? 'warn' : ''}">${a.title}</span>
          <span class="alert-time">${timeStr}</span>
        </div>
        <div class="alert-desc">${a.desc}</div>
      </div>
    `;
  });

  feed.innerHTML = html;
}

function flyToCoords(lat, lon) {
  map.flyTo([lat, lon], 16, { duration: 1.2 });
}

// ---------------------------------------------------------------------------
// Historical Session Playback
// ---------------------------------------------------------------------------
function fetchSessions() {
  const cleanUrl = state.firebaseUrl;
  if (!cleanUrl) return;

  const container = document.getElementById('session-list');
  if (container && state.playback.sessions.size === 0) {
    container.innerHTML = `
      <div class="session-loading-skeleton" aria-busy="true" aria-label="Loading sessions">
        <div class="skeleton-card"></div>
        <div class="skeleton-card"></div>
        <div class="skeleton-card"></div>
      </div>
    `;
  }

  const authParam = state.firebaseToken ? `?auth=${state.firebaseToken.trim()}` : '';

  // 1. First attempt: /fleet/sessions.json
  fetch(`${cleanUrl}/fleet/sessions.json${authParam}`)
    .then(res => res.json())
    .then(data => {
      if (data && typeof data === 'object' && Object.keys(data).length > 0) {
        populateSessions(data);
      } else {
        // 2. Fallback: query /fleet/trails.json
        fetchSessionsFromTrails(cleanUrl, authParam);
      }
    })
    .catch(err => {
      console.warn('Sessions endpoint fetch error, falling back to trails:', err);
      fetchSessionsFromTrails(cleanUrl, authParam);
    });
}

function fetchSessionsFromTrails(cleanUrl, authParam) {
  fetch(`${cleanUrl}/fleet/trails.json${authParam}`)
    .then(res => res.json())
    .then(trailsData => {
      if (!trailsData || typeof trailsData !== 'object') {
        renderSessionsArchive([]);
        return;
      }

      const synthesized = {};
      Object.entries(trailsData).forEach(([sessionId, trailVal]) => {
        if (!trailVal) return;
        const pts = Array.isArray(trailVal) ? trailVal.filter(Boolean) : Object.values(trailVal || {});
        if (pts.length === 0) return;

        const firstPt = pts[0] || {};
        const lastPt = pts[pts.length - 1] || {};
        const avgRsrp = Math.round(pts.reduce((sum, p) => sum + (Number(p.rsrp) || -100), 0) / pts.length);

        synthesized[sessionId] = {
          sessionId: sessionId,
          vehicleId: firstPt.vehicleId || 'DT-UNIT',
          startTime: firstPt.ts || firstPt.timestamp || Date.now(),
          updatedAt: lastPt.ts || lastPt.timestamp || Date.now(),
          sampleCount: pts.length,
          operator: firstPt.operator || 'Mobile',
          rat: firstPt.rat || '5G/LTE',
          avgRsrp: avgRsrp,
          isRecording: false
        };
      });

      populateSessions(synthesized);
    })
    .catch(e => {
      console.error('Failed to load sessions fallback:', e);
      renderSessionsArchive([]);
    });
}

function populateSessions(sessionsObj) {
  state.playback.sessions.clear();

  Object.values(sessionsObj).forEach(s => {
    if (s && s.sessionId) {
      state.playback.sessions.set(s.sessionId, s);
    }
  });

  const sortedList = Array.from(state.playback.sessions.values()).sort((a, b) => {
    const timeA = a.updatedAt || a.startTime || 0;
    const timeB = b.updatedAt || b.startTime || 0;
    return timeB - timeA;
  });

  populateVehicleFilterOptions(sortedList);
  renderSessionsArchive(sortedList);
  populateSessionDropdown(sortedList);
}

function populateVehicleFilterOptions(sessionList) {
  const filterEl = document.getElementById('session-filter-vehicle');
  if (!filterEl) return;

  const currentVal = filterEl.value;
  const vehicles = Array.from(new Set(sessionList.map(s => s.vehicleId || 'DT-UNIT'))).sort();
  filterEl.innerHTML = '<option value="">All Vehicles</option>';
  vehicles.forEach(v => {
    const opt = document.createElement('option');
    opt.value = v;
    opt.textContent = v;
    filterEl.appendChild(opt);
  });
  if (currentVal && vehicles.includes(currentVal)) {
    filterEl.value = currentVal;
  }
}

function resetSessionFilters() {
  const searchEl = document.getElementById('session-search-input');
  const clearBtn = document.getElementById('btn-clear-session-search');
  const vehEl = document.getElementById('session-filter-vehicle');
  const sortEl = document.getElementById('session-sort-select');
  if (searchEl) searchEl.value = '';
  if (clearBtn) clearBtn.style.display = 'none';
  if (vehEl) vehEl.value = '';
  if (sortEl) sortEl.value = 'date-desc';
  renderSessionsArchive(Array.from(state.playback.sessions.values()));
}

function renderSessionsArchive(sessionList) {
  const container = document.getElementById('session-list');
  const badge = document.getElementById('roster-badge');
  const summaryEl = document.getElementById('session-filter-summary');

  if (state.mode === 'PLAYBACK') {
    badge.textContent = `${sessionList.length} SESSIONS`;
    const mobCount = document.getElementById('mobile-fleet-count');
    if (mobCount) mobCount.textContent = sessionList.length;
  }

  if (sessionList.length === 0) {
    if (summaryEl) summaryEl.textContent = '';
    container.innerHTML = `
      <div class="empty-state">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#94a3b8" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" class="empty-state-icon" aria-hidden="true">
          <circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>
        </svg>
        <div class="empty-state-title">NO RECORDED SESSIONS</div>
        <div class="empty-state-desc">ยังไม่พบประวัติเซสชันใน Firebase</div>
      </div>
    `;
    return;
  }

  const escapeHtml = (str) => String(str || '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));

  const query = (document.getElementById('session-search-input')?.value || '').toLowerCase().trim();
  const clearBtn = document.getElementById('btn-clear-session-search');
  if (clearBtn) clearBtn.style.display = query ? 'inline-flex' : 'none';

  const selectedVeh = document.getElementById('session-filter-vehicle')?.value || '';
  const sortMode = document.getElementById('session-sort-select')?.value || 'date-desc';

  let filtered = sessionList.filter(s => {
    if (selectedVeh && (s.vehicleId || 'DT-UNIT') !== selectedVeh) return false;
    if (!query) return true;
    return (s.sessionName || '').toLowerCase().includes(query) ||
      (s.sessionId || '').toLowerCase().includes(query) ||
      (s.vehicleId || '').toLowerCase().includes(query) ||
      (s.operator || '').toLowerCase().includes(query) ||
      formatRatLabel(s.rat).toLowerCase().includes(query);
  });

  filtered = filtered.slice().sort((a, b) => {
    const timeA = a.updatedAt || a.startTime || 0;
    const timeB = b.updatedAt || b.startTime || 0;
    const rsrpA = (a.avgRsrp !== undefined && a.avgRsrp !== null && !isNaN(a.avgRsrp)) ? Number(a.avgRsrp) : -999;
    const rsrpB = (b.avgRsrp !== undefined && b.avgRsrp !== null && !isNaN(b.avgRsrp)) ? Number(b.avgRsrp) : -999;
    const ptsA = Number(a.sampleCount) || 0;
    const ptsB = Number(b.sampleCount) || 0;

    switch (sortMode) {
      case 'date-asc': return timeA - timeB;
      case 'rsrp-desc': return rsrpB - rsrpA;
      case 'rsrp-asc': return (rsrpA === -999 ? 999 : rsrpA) - (rsrpB === -999 ? 999 : rsrpB);
      case 'pts-desc': return ptsB - ptsA;
      case 'date-desc':
      default: return timeB - timeA;
    }
  });

  if (summaryEl) {
    if (query || selectedVeh) {
      summaryEl.textContent = `Showing ${filtered.length} of ${sessionList.length} sessions`;
    } else {
      summaryEl.textContent = `${sessionList.length} sessions archived`;
    }
  }

  if (filtered.length === 0) {
    container.innerHTML = `
      <div class="empty-state">
        <div class="empty-state-title">NO MATCHING SESSIONS</div>
        <div class="empty-state-desc">No sessions match your current search or vehicle filter.</div>
        <button type="button" class="btn-reset-filters" onclick="resetSessionFilters()">Reset Filters</button>
      </div>
    `;
    return;
  }

  let html = '';
  filtered.forEach(s => {
    const isSelected = state.playback.currentSessionId === s.sessionId;
    const rawTs = s.startTime || s.updatedAt;
    const dateStr = formatSessionDate(rawTs);
    const fullIso = rawTs ? new Date(Number(rawTs)).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'medium' }) : '';
    const hasRsrp = s.avgRsrp !== undefined && s.avgRsrp !== null && !isNaN(s.avgRsrp);
    const rsrpVal = hasRsrp ? `${Number(s.avgRsrp).toFixed(1)} dBm` : '—';
    const rsrpColor = hasRsrp ? getRsrpColor(s.avgRsrp) : '#94a3b8';
    const vehLabel = escapeHtml(s.vehicleId || 'DT-UNIT');
    const displayLabel = escapeHtml(s.sessionName || s.sessionId);
    const ratLabel = escapeHtml(formatRatLabel(s.rat));
    const cleanOp = String(s.operator || 'SIM 1').replace(/\s*\(SIM\s*\d+\)\s*$/i, (m) =>
      /^SIM\s*\d+:/i.test(String(s.operator || '')) ? '' : m
    );
    const simLabel = escapeHtml(cleanOp);
    const safeId = escapeHtml(s.sessionId);

    html += `
      <div
        class="session-card ${isSelected ? 'selected' : ''}"
        role="option"
        tabindex="0"
        aria-selected="${isSelected ? 'true' : 'false'}"
        onclick="loadSession('${safeId}')"
        onkeydown="if(event.key==='Enter'||event.key===' '){event.preventDefault();loadSession('${safeId}');}"
      >
        <div class="session-card-top">
          <span class="session-id" title="${vehLabel}">${vehLabel}</span>
          <span class="session-badge">${formatCount(s.sampleCount || 0)} PTS</span>
        </div>
        <div class="session-meta-row">
          <span class="session-subname" title="${displayLabel}">${displayLabel}</span>
          <span class="session-date" title="${escapeHtml(fullIso)}">${dateStr}</span>
        </div>
        <div class="session-metrics-chips">
          <span class="session-chip session-chip--rat">${ratLabel}</span>
          <span class="session-chip" style="color: ${rsrpColor};"><span class="session-chip-dot" style="background: ${rsrpColor};"></span>RSRP ${rsrpVal}</span>
          <span class="session-chip session-chip--sim" title="${simLabel}">${simLabel}</span>
        </div>
      </div>
    `;
  });

  container.innerHTML = html;
}

function populateSessionDropdown(sessionList) {
  const select = document.getElementById('playback-session-select');
  if (!select) return;

  const currentVal = select.value;
  select.innerHTML = '<option value="">-- Choose Session --</option>';

  sessionList.forEach(s => {
    const timeStr = formatTime24(s.startTime || s.updatedAt, false);
    const opt = document.createElement('option');
    opt.value = s.sessionId;
    opt.textContent = `${s.vehicleId || 'DT'} · ${timeStr} · ${formatCount(s.sampleCount || 0)}`;
    opt.title = `${s.vehicleId || 'DT-UNIT'} • ${s.sessionName || s.sessionId} (${formatCount(s.sampleCount || 0)} pts)`;
    select.appendChild(opt);
  });

  if (currentVal && state.playback.sessions.has(currentVal)) {
    select.value = currentVal;
  }
}

function loadSession(sessionId) {
  if (!sessionId) return;

  const cleanUrl = state.firebaseUrl;
  const authParam = state.firebaseToken ? `?auth=${state.firebaseToken.trim()}` : '';

  state.playback.currentSessionId = sessionId;
  pausePlayback();

  // Highlight in dropdown and list
  const select = document.getElementById('playback-session-select');
  if (select) select.value = sessionId;
  renderSessionsArchive(Array.from(state.playback.sessions.values()));

  const sessionNameEl = document.getElementById('scrubber-session-name');
  sessionNameEl.textContent = `LOADING ${sessionId}...`;

  fetch(`${cleanUrl}/fleet/trails/${sessionId}.json${authParam}`)
    .then(res => res.json())
    .then(data => {
      if (!data) {
        sessionNameEl.textContent = `SESSION EMPTY (${sessionId})`;
        return;
      }

      let points = Array.isArray(data) ? data.filter(Boolean) : Object.values(data);
      points = points.filter(p => p && p.lat && p.lon);
      points.sort((a, b) => (a.ts || a.timestamp || 0) - (b.ts || b.timestamp || 0));

      if (points.length === 0) {
        sessionNameEl.textContent = `NO POINTS (${sessionId})`;
        return;
      }

      state.playback.samples = points;
      state.playback.currentIndex = 0;

      const sessionMeta = state.playback.sessions.get(sessionId) || {};
      const sessionTitle = `${sessionMeta.vehicleId || 'DT-UNIT'} • ${sessionId}`;
      sessionNameEl.textContent = sessionTitle;
      sessionNameEl.title = `${sessionTitle} (${formatCount(points.length)} pts)`;

      // Setup Scrubber Slider
      const scrubber = document.getElementById('playback-scrubber');
      scrubber.min = 0;
      scrubber.max = Math.max(0, points.length - 1);
      scrubber.value = 0;
      scrubber.style.setProperty('--progress', '0%');
      scrubber.disabled = false;

      // Draw Full Route Background Polyline + High-Contrast Casing
      if (state.playback.fullPolyline) {
        map.removeLayer(state.playback.fullPolyline);
      }
      if (state.playback.playedCasing) {
        map.removeLayer(state.playback.playedCasing);
      }
      if (state.playback.playedPolyline) {
        map.removeLayer(state.playback.playedPolyline);
      }

      const allCoords = points.map(p => [p.lat, p.lon]);
      state.playback.fullPolyline = L.polyline(allCoords, {
        color: '#cbd5e1',
        weight: 3.5,
        opacity: 0.5,
        dashArray: '6, 8'
      }).addTo(map);

      state.playback.playedCasing = L.polyline([[points[0].lat, points[0].lon]], {
        color: '#070b14',
        weight: 8,
        opacity: 0.85,
        lineCap: 'round',
        lineJoin: 'round'
      }).addTo(map);

      state.playback.playedPolyline = L.polyline([[points[0].lat, points[0].lon]], {
        color: getRsrpColor(points[0].rsrp),
        weight: 4.5,
        opacity: 1,
        lineCap: 'round',
        lineJoin: 'round'
      }).addTo(map);

      // Fit Map to Trail
      map.fitBounds(L.latLngBounds(allCoords), { padding: [50, 50] });

      // Create / Move Playback Marker
      if (!state.playback.marker) {
        state.playback.marker = L.marker([points[0].lat, points[0].lon], {
          icon: createPlaybackMarkerIcon(sessionMeta.vehicleId || 'PLAYBACK', 0, points[0].rsrp),
          zIndexOffset: 1200
        }).addTo(map);
      } else {
        state.playback.marker.setLatLng([points[0].lat, points[0].lon]);
        state.playback.marker.setIcon(createPlaybackMarkerIcon(sessionMeta.vehicleId || 'PLAYBACK', 0, points[0].rsrp));
        if (!map.hasLayer(state.playback.marker)) {
          state.playback.marker.addTo(map);
        }
      }

      renderPlaybackStep(0);

      if (state.heatmap.enabled) {
        updateHeatmapData();
      }
    })
    .catch(err => {
      console.error('Failed to load session trail:', err);
      sessionNameEl.textContent = `ERROR LOADING ${sessionId}`;
    });
}

function renderPlaybackStep(index) {
  const points = state.playback.samples;
  if (!points || points.length === 0) return;

  const clampedIndex = Math.max(0, Math.min(points.length - 1, index));
  state.playback.currentIndex = clampedIndex;

  const scrubber = document.getElementById('playback-scrubber');
  if (scrubber) {
    scrubber.value = clampedIndex;
    const pct = points.length > 1 ? (clampedIndex / (points.length - 1)) * 100 : 100;
    scrubber.style.setProperty('--progress', `${pct.toFixed(2)}%`);
  }

  const pt = points[clampedIndex];
  if (!pt) return;

  const sessionMeta = state.playback.sessions.get(state.playback.currentSessionId) || {};
  const vehicleLabel = sessionMeta.vehicleId || 'PLAYBACK';

  // Compute bearing
  let bearing = pt.bearing;
  if (bearing === undefined || bearing === null) {
    if (clampedIndex > 0) {
      bearing = calculateBearing(points[clampedIndex - 1].lat, points[clampedIndex - 1].lon, pt.lat, pt.lon);
    } else {
      bearing = 0;
    }
  }

  // Move marker
  if (state.playback.marker) {
    state.playback.marker.setLatLng([pt.lat, pt.lon]);
    state.playback.marker.setIcon(createPlaybackMarkerIcon(vehicleLabel, bearing, pt.rsrp));
  }

  // Update played polyline + dark high-contrast casing
  const playedCoords = points.slice(0, clampedIndex + 1).map(p => [p.lat, p.lon]);
  if (state.playback.playedCasing) {
    state.playback.playedCasing.setLatLngs(playedCoords);
  }
  if (state.playback.playedPolyline) {
    state.playback.playedPolyline.setLatLngs(playedCoords);
    state.playback.playedPolyline.setStyle({ color: getRsrpColor(pt.rsrp) });
  }

  // Update counters & full 24h timestamp display
  const timeDisplay = document.getElementById('scrubber-time-display');
  const sampleCounter = document.getElementById('scrubber-sample-counter');
  if (timeDisplay) {
    const ts = pt.ts || pt.timestamp;
    timeDisplay.textContent = formatTime24(ts, true);
  }
  if (sampleCounter) {
    sampleCounter.textContent = `${formatCount(clampedIndex + 1)} / ${formatCount(points.length)}`;
  }

  // Drive Dual-SIM RF Inspector
  const inspectorPayload = {
    vehicleId: vehicleLabel,
    vehicleName: `Historical Session ${state.playback.currentSessionId || ''}`,
    operator: sessionMeta.operator || pt.operator || 'Playback SIM',
    lat: pt.lat,
    lon: pt.lon,
    speedKmh: (pt.speed !== undefined) ? pt.speed : (pt.speedKmh || 0),
    rat: pt.rat || sessionMeta.rat || '5G/LTE',
    band: pt.band || '',
    pci: pt.pci || '—',
    cellId: pt.cellId || '—',
    rsrp: pt.rsrp,
    sinr: pt.sinr,
    sim2Rsrp: pt.sim2Rsrp,
    sim2Sinr: pt.sim2Sinr,
    sim2Pci: pt.sim2Pci,
    sim2Rat: pt.sim2Rat,
    sim2Band: pt.sim2Band,
    sim2Operator: pt.sim2Operator
  };
  renderInspector(inspectorPayload);
}

function playPlayback() {
  const points = state.playback.samples;
  if (!points || points.length === 0) return;

  if (state.playback.currentIndex >= points.length - 1) {
    state.playback.currentIndex = 0;
  }

  state.playback.isPlaying = true;
  const playBtn = document.getElementById('btn-playback-play');
  if (playBtn) playBtn.setAttribute('aria-pressed', 'true');
  document.getElementById('playback-icon-play').style.display = 'none';
  document.getElementById('playback-icon-pause').style.display = 'block';

  const baseIntervalMs = 250;
  const tickInterval = Math.max(30, Math.round(baseIntervalMs / state.playback.speed));

  if (state.playback.playTimer) clearInterval(state.playback.playTimer);
  state.playback.playTimer = setInterval(() => {
    if (state.playback.currentIndex < points.length - 1) {
      renderPlaybackStep(state.playback.currentIndex + 1);
    } else {
      pausePlayback();
    }
  }, tickInterval);
}

function pausePlayback() {
  state.playback.isPlaying = false;
  if (state.playback.playTimer) {
    clearInterval(state.playback.playTimer);
    state.playback.playTimer = null;
  }
  const playBtn = document.getElementById('btn-playback-play');
  if (playBtn) playBtn.setAttribute('aria-pressed', 'false');
  const playIcon = document.getElementById('playback-icon-play');
  const pauseIcon = document.getElementById('playback-icon-pause');
  if (playIcon) playIcon.style.display = 'block';
  if (pauseIcon) pauseIcon.style.display = 'none';
}

function togglePlayPause() {
  if (state.playback.isPlaying) {
    pausePlayback();
  } else {
    playPlayback();
  }
}

function stepPlayback(delta) {
  pausePlayback();
  const nextIdx = state.playback.currentIndex + delta;
  renderPlaybackStep(nextIdx);
}

function setPlaybackSpeed(speed) {
  state.playback.speed = Number(speed);
  document.querySelectorAll('.speed-btn').forEach(btn => {
    const active = Number(btn.getAttribute('data-speed')) === state.playback.speed;
    btn.classList.toggle('active', active);
    btn.setAttribute('aria-pressed', active ? 'true' : 'false');
  });

  if (state.playback.isPlaying) {
    playPlayback(); // restart timer with new interval
  }
}

// ---------------------------------------------------------------------------
// Mode Switching: LIVE vs HISTORICAL PLAYBACK
// ---------------------------------------------------------------------------
function setMode(newMode) {
  if (state.mode === newMode) return;
  state.mode = newMode;
  document.body.setAttribute('data-mode', newMode);

  const btnLive = document.getElementById('btn-mode-live');
  const btnPlayback = document.getElementById('btn-mode-playback');
  const liveTab = document.getElementById('panel-tab-live');
  const playbackTab = document.getElementById('panel-tab-playback');
  const panelTitle = document.getElementById('left-panel-title');
  const scrubberBar = document.getElementById('playback-scrubber-bar');
  const mobFleetTab = document.getElementById('mobile-fleet-tab-label');

  if (newMode === 'LIVE') {
    btnLive.classList.add('active');
    btnLive.setAttribute('aria-selected', 'true');
    btnPlayback.classList.remove('active');
    btnPlayback.setAttribute('aria-selected', 'false');

    liveTab.style.display = 'flex';
    playbackTab.style.display = 'none';
    panelTitle.textContent = 'FLEET ROSTER';
    scrubberBar.style.display = 'none';

    if (mobFleetTab) {
      mobFleetTab.innerHTML = `FLEET (<span id="mobile-fleet-count">${state.vehicles.size}</span>)`;
    }

    pausePlayback();

    // Remove playback layers
    if (state.playback.marker && map.hasLayer(state.playback.marker)) {
      map.removeLayer(state.playback.marker);
    }
    if (state.playback.fullPolyline && map.hasLayer(state.playback.fullPolyline)) {
      map.removeLayer(state.playback.fullPolyline);
    }
    if (state.playback.playedCasing && map.hasLayer(state.playback.playedCasing)) {
      map.removeLayer(state.playback.playedCasing);
    }
    if (state.playback.playedPolyline && map.hasLayer(state.playback.playedPolyline)) {
      map.removeLayer(state.playback.playedPolyline);
    }

    // Restore live fleet markers
    state.vehicles.forEach(entry => {
      if (entry.marker && !map.hasLayer(entry.marker)) entry.marker.addTo(map);
      if (entry.polyline && !map.hasLayer(entry.polyline)) entry.polyline.addTo(map);
    });

    renderVehicleRoster();

    if (state.selectedVehicleId && state.vehicles.has(state.selectedVehicleId)) {
      renderInspector(state.vehicles.get(state.selectedVehicleId).data);
    } else {
      resetInspector();
    }
  } else if (newMode === 'PLAYBACK') {
    btnPlayback.classList.add('active');
    btnPlayback.setAttribute('aria-selected', 'true');
    btnLive.classList.remove('active');
    btnLive.setAttribute('aria-selected', 'false');

    liveTab.style.display = 'none';
    playbackTab.style.display = 'flex';
    panelTitle.textContent = 'SESSION ARCHIVE';
    scrubberBar.style.display = 'flex';

    if (mobFleetTab) {
      mobFleetTab.innerHTML = `SESSIONS (<span id="mobile-fleet-count">${state.playback.sessions.size}</span>)`;
    }

    // Hide live fleet markers to avoid map clutter
    state.vehicles.forEach(entry => {
      if (entry.marker && map.hasLayer(entry.marker)) map.removeLayer(entry.marker);
      if (entry.polyline && map.hasLayer(entry.polyline)) map.removeLayer(entry.polyline);
    });

    fetchSessions();

    if (state.playback.samples.length > 0) {
      renderPlaybackStep(state.playback.currentIndex);
    }
  }

  if (state.heatmap.enabled) {
    updateHeatmapData();
  }
}

// ---------------------------------------------------------------------------
// Firebase Sync Controller
// ---------------------------------------------------------------------------
function syncLiveFleet(liveData) {
  if (!liveData || typeof liveData !== 'object' || liveData.error) {
    clearFleet();
    return;
  }

  const now = Date.now();
  const HEARTBEAT_TIMEOUT_MS = 2 * 60 * 1000; // 2 minutes

  const activeIncomingMap = new Map();
  Object.values(liveData).forEach(veh => {
    if (veh && veh.vehicleId && veh.lat && veh.lon) {
      const isRecording = veh.isRecording === true;
      const isFresh = veh.updatedAt ? (now - veh.updatedAt < HEARTBEAT_TIMEOUT_MS) : false;

      if (isRecording && isFresh) {
        activeIncomingMap.set(veh.vehicleId, veh);
      }
    }
  });

  const toRemove = [];
  state.vehicles.forEach((_, vId) => {
    if (!activeIncomingMap.has(vId)) {
      toRemove.push(vId);
    }
  });
  toRemove.forEach(vId => removeVehicle(vId));

  activeIncomingMap.forEach(veh => {
    updateVehicle(veh);
  });

  renderVehicleRoster();

  if (state.vehicles.size === 0 && state.mode === 'LIVE') {
    document.getElementById('inspector-vehicle-badge').textContent = 'NO ACTIVE FLEET';
    resetInspector();
  }
}

function connectFirebase(url, token) {
  const cleanUrl = (url || '').trim().replace(/\/+$/, '').replace(/\.json$/, '');
  if (!cleanUrl.startsWith('http')) {
    console.warn('Invalid Firebase Database URL in env.js:', cleanUrl);
    return;
  }

  state.firebaseUrl = cleanUrl;
  state.firebaseToken = token || '';
  const authParam = token ? `?auth=${token.trim()}` : '';

  fetch(`${cleanUrl}/fleet/vehicles.json${authParam}`)
    .then(res => {
      if (!res.ok) throw new Error(`HTTP ${res.status}: ${res.statusText}`);
      return res.json();
    })
    .then(data => {
      if (data && data.error) throw new Error(data.error);

      document.getElementById('kpi-sync-mode').textContent = 'FIREBASE LIVE';
      document.getElementById('kpi-sync-mode').className = 'kpi-value text-emerald';

      syncLiveFleet(data);

      if (state.vehicles.size > 0 && state.mode === 'LIVE') {
        const first = Array.from(state.vehicles.values())[0];
        map.panTo([first.data.lat, first.data.lon]);
      }

      if (state.pollTimer) clearInterval(state.pollTimer);
      state.pollTimer = setInterval(() => {
        if (state.mode === 'LIVE') {
          fetch(`${cleanUrl}/fleet/vehicles.json${authParam}`)
            .then(res => res.json())
            .then(liveData => syncLiveFleet(liveData))
            .catch(e => console.warn('Firebase live poll warning:', e));
        }
      }, 1500);
    })
    .catch(err => {
      console.error('Firebase connection error:', err);
      document.getElementById('kpi-sync-mode').textContent = 'FIREBASE ERROR';
      document.getElementById('kpi-sync-mode').className = 'kpi-value text-rose';
      const listEl = document.getElementById('vehicle-list');
      listEl.innerHTML = `
        <div class="empty-state" style="padding: 20px 12px; color: #f87171;">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="margin-bottom: 6px;">
            <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>
          </svg>
          <div style="font-weight: 700; margin-bottom: 6px; font-size: 11px; letter-spacing: 0.5px;">FIREBASE CONNECTION ERROR</div>
          <div style="font-size: 10px; line-height: 1.5;">${err.message}<br>โปรดตรวจสอบ Rules ใน Firebase Console (.read: true)</div>
        </div>
      `;
    });
}

function purgeOfflineVehicles() {
  const cleanUrl = state.firebaseUrl;
  if (!cleanUrl) return;

  const authParam = state.firebaseToken ? `?auth=${state.firebaseToken.trim()}` : '';

  fetch(`${cleanUrl}/fleet/vehicles.json${authParam}`)
    .then(res => res.json())
    .then(data => {
      if (!data || typeof data !== 'object') return;
      const now = Date.now();
      const HEARTBEAT_TIMEOUT_MS = 2 * 60 * 1000;

      Object.entries(data).forEach(([vId, veh]) => {
        const isRecording = veh?.isRecording === true;
        const isFresh = veh?.updatedAt ? (now - veh.updatedAt < HEARTBEAT_TIMEOUT_MS) : false;

        if (!isRecording || !isFresh) {
          fetch(`${cleanUrl}/fleet/vehicles/${vId}.json${authParam}`, { method: 'DELETE' })
            .then(() => console.log(`Purged stale vehicle ${vId} from Firebase`))
            .catch(e => console.warn(`Failed to purge ${vId}:`, e));
        }
      });

      clearFleet();
    })
    .catch(e => console.warn('Purge failed:', e));
}

// ---------------------------------------------------------------------------
// Mobile Navigation Setup
// ---------------------------------------------------------------------------
function initMobileNav() {
  const tabButtons = document.querySelectorAll('.mobile-tab-btn');
  const panelLeft = document.querySelector('.panel-left');
  const panelRight = document.querySelector('.panel-right');
  const detailsBtn = document.getElementById('mob-btn-details');

  function switchTab(tab) {
    tabButtons.forEach(b => {
      const active = b.getAttribute('data-tab') === tab;
      b.classList.toggle('active', active);
      b.setAttribute('aria-selected', active ? 'true' : 'false');
    });

    if (tab === 'map') {
      if (panelLeft) panelLeft.classList.remove('active-view');
      if (panelRight) panelRight.classList.remove('active-view');
      if (map) {
        setTimeout(() => map.invalidateSize(), 150);
      }
    } else if (tab === 'fleet') {
      if (panelLeft) panelLeft.classList.add('active-view');
      if (panelRight) panelRight.classList.remove('active-view');
    } else if (tab === 'inspector') {
      if (panelRight) panelRight.classList.add('active-view');
      if (panelLeft) panelLeft.classList.remove('active-view');
    }
  }

  tabButtons.forEach(btn => {
    btn.addEventListener('click', () => {
      const targetTab = btn.getAttribute('data-tab');
      switchTab(targetTab);
    });
  });

  if (detailsBtn) {
    detailsBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      switchTab('inspector');
    });
  }

  window.switchMobileTab = switchTab;
}

// ---------------------------------------------------------------------------
// DOM Event Listeners & Bootstrapping
// ---------------------------------------------------------------------------
document.addEventListener('DOMContentLoaded', () => {
  initMap();
  initMobileNav();

  const firebaseUrl = window.ENV?.FIREBASE_DB_URL || 'https://drivetest-telemetry-default-rtdb.asia-southeast1.firebasedatabase.app';
  const firebaseToken = window.ENV?.FIREBASE_AUTH_TOKEN || '';

  connectFirebase(firebaseUrl, firebaseToken);

  // Mode Switchers
  document.getElementById('btn-mode-live').addEventListener('click', () => setMode('LIVE'));
  document.getElementById('btn-mode-playback').addEventListener('click', () => setMode('PLAYBACK'));

  // Map Controls
  document.getElementById('btn-center-fleet').addEventListener('click', () => {
    if (state.mode === 'LIVE') {
      const latLngs = [];
      state.vehicles.forEach(e => latLngs.push([e.data.lat, e.data.lon]));
      if (latLngs.length > 0) {
        map.fitBounds(L.latLngBounds(latLngs), { padding: [50, 50] });
      }
    } else if (state.mode === 'PLAYBACK') {
      if (state.playback.samples.length > 0) {
        const coords = state.playback.samples.map(p => [p.lat, p.lon]);
        map.fitBounds(L.latLngBounds(coords), { padding: [50, 50] });
      }
    }
  });

  document.getElementById('btn-clear-trails').addEventListener('click', () => {
    if (state.mode === 'LIVE') {
      state.vehicles.forEach(e => {
        e.trail = [];
        e.rfPoints = [];
        e.polyline.setLatLngs([]);
      });
      if (state.heatmap.enabled) updateHeatmapData();
    }
  });

  // Destructive Purge Offline with Confirmation Modal
  const purgeModal = document.getElementById('purge-confirm-modal');
  const btnPurgeCancel = document.getElementById('btn-purge-cancel');
  const btnPurgeConfirm = document.getElementById('btn-purge-confirm');
  const btnPurgeOpen = document.getElementById('btn-purge-offline');

  function openPurgeModal() {
    if (!purgeModal) {
      purgeOfflineVehicles();
      return;
    }
    purgeModal.style.display = 'flex';
    if (btnPurgeCancel) btnPurgeCancel.focus();
  }

  function closePurgeModal() {
    if (!purgeModal) return;
    purgeModal.style.display = 'none';
    if (btnPurgeOpen) btnPurgeOpen.focus();
  }

  btnPurgeOpen.addEventListener('click', openPurgeModal);
  if (btnPurgeCancel) btnPurgeCancel.addEventListener('click', closePurgeModal);
  if (btnPurgeConfirm) {
    btnPurgeConfirm.addEventListener('click', () => {
      closePurgeModal();
      purgeOfflineVehicles();
    });
  }
  if (purgeModal) {
    purgeModal.addEventListener('click', (e) => {
      if (e.target === purgeModal) closePurgeModal();
    });
  }

  // Heatmap Controls
  document.getElementById('btn-toggle-heatmap').addEventListener('click', () => {
    toggleHeatmap();
  });

  document.getElementById('btn-metric-rsrp').addEventListener('click', () => {
    setHeatmapMetric('RSRP');
  });

  document.getElementById('btn-metric-sinr').addEventListener('click', () => {
    setHeatmapMetric('SINR');
  });

  const opacitySlider = document.getElementById('heatmap-opacity-slider');
  if (opacitySlider) {
    opacitySlider.addEventListener('input', (e) => {
      setHeatmapOpacity(e.target.value);
    });
  }

  // Collapsible Map Legend
  const legendEl = document.getElementById('map-legend');
  const legendBtn = document.getElementById('btn-toggle-legend');
  if (legendEl && legendBtn) {
    // Default collapsed on small mobile screens for unobstructed map view
    if (window.innerWidth <= 768) {
      legendEl.classList.add('collapsed');
      legendBtn.setAttribute('aria-expanded', 'false');
    }
    legendBtn.addEventListener('click', () => {
      const collapsed = legendEl.classList.toggle('collapsed');
      legendBtn.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
    });
  }

  // Collapsible Desktop Sidebars
  const workspaceEl = document.getElementById('noc-workspace');
  const panelLeft = document.getElementById('panel-left');
  const panelRight = document.getElementById('panel-right');
  const btnCollapseLeft = document.getElementById('btn-collapse-left');
  const btnExpandLeft = document.getElementById('btn-expand-left');
  const btnCollapseRight = document.getElementById('btn-collapse-right');
  const btnExpandRight = document.getElementById('btn-expand-right');

  function setSidebarCollapsed(side, collapsed) {
    const panel = side === 'left' ? panelLeft : panelRight;
    const collapseBtn = side === 'left' ? btnCollapseLeft : btnCollapseRight;
    const expandBtn = side === 'left' ? btnExpandLeft : btnExpandRight;
    if (!panel || !workspaceEl) return;

    panel.classList.toggle('collapsed', collapsed);
    workspaceEl.classList.toggle(`${side}-collapsed`, collapsed);
    if (collapseBtn) collapseBtn.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
    if (expandBtn) expandBtn.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
    if (map) {
      setTimeout(() => map.invalidateSize(), 260);
    }
  }

  if (btnCollapseLeft) btnCollapseLeft.addEventListener('click', () => setSidebarCollapsed('left', true));
  if (btnExpandLeft) btnExpandLeft.addEventListener('click', () => setSidebarCollapsed('left', false));
  if (btnCollapseRight) btnCollapseRight.addEventListener('click', () => setSidebarCollapsed('right', true));
  if (btnExpandRight) btnExpandRight.addEventListener('click', () => setSidebarCollapsed('right', false));

  // Historical Playback Controls
  document.getElementById('btn-playback-play').addEventListener('click', () => {
    togglePlayPause();
  });

  document.getElementById('btn-playback-step-back').addEventListener('click', () => {
    stepPlayback(-1);
  });

  document.getElementById('btn-playback-step-fwd').addEventListener('click', () => {
    stepPlayback(1);
  });

  document.querySelectorAll('.speed-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const spd = btn.getAttribute('data-speed');
      setPlaybackSpeed(spd);
    });
  });

  const scrubber = document.getElementById('playback-scrubber');
  scrubber.addEventListener('input', (e) => {
    pausePlayback();
    renderPlaybackStep(parseInt(e.target.value, 10));
  });

  // Session selector dropdown in scrubber
  document.getElementById('playback-session-select').addEventListener('change', (e) => {
    const sId = e.target.value;
    if (sId) loadSession(sId);
  });

  // Session search, clear, vehicle filter, sort & refresh in left panel
  const sessionSearchInput = document.getElementById('session-search-input');
  const btnClearSearch = document.getElementById('btn-clear-session-search');
  const filterVehSelect = document.getElementById('session-filter-vehicle');
  const sortSelect = document.getElementById('session-sort-select');

  const rerenderSessions = () => renderSessionsArchive(Array.from(state.playback.sessions.values()));

  if (sessionSearchInput) sessionSearchInput.addEventListener('input', rerenderSessions);
  if (btnClearSearch) {
    btnClearSearch.addEventListener('click', () => {
      if (sessionSearchInput) {
        sessionSearchInput.value = '';
        sessionSearchInput.focus();
      }
      rerenderSessions();
    });
  }
  if (filterVehSelect) filterVehSelect.addEventListener('change', rerenderSessions);
  if (sortSelect) sortSelect.addEventListener('change', rerenderSessions);

  document.getElementById('btn-refresh-sessions-list').addEventListener('click', () => {
    fetchSessions();
  });

  // Keyboard Accessibility & Playback Shortcuts
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && purgeModal && purgeModal.style.display !== 'none') {
      closePurgeModal();
      return;
    }
    const tag = (e.target && e.target.tagName) ? e.target.tagName.toLowerCase() : '';
    if (tag === 'input' || tag === 'select' || tag === 'textarea' || (e.target && e.target.isContentEditable)) {
      return;
    }
    if (state.mode === 'PLAYBACK' && state.playback.samples.length > 0) {
      if (e.code === 'Space') {
        e.preventDefault();
        togglePlayPause();
      } else if (e.key === 'ArrowLeft') {
        e.preventDefault();
        stepPlayback(-1);
      } else if (e.key === 'ArrowRight') {
        e.preventDefault();
        stepPlayback(1);
      }
    }
  });
});
