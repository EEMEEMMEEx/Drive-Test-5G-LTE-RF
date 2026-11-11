/**
 * NOC Live Fleet Telemetry & GIS Dashboard — Drive Test LTE/5G v1.12.0
 */

// Signal Color Palette
const COLORS = {
  tier1: '#10b981', // >= -80 dBm
  tier2: '#38bdf8', // -80 to -90 dBm
  tier3: '#f59e0b', // -90 to -100 dBm
  tier4: '#f97316', // -100 to -110 dBm
  tier5: '#ef4444'  // < -110 dBm
};

function getRsrpColor(rsrp) {
  if (rsrp === null || rsrp === undefined) return '#94a3b8';
  if (rsrp >= -80) return COLORS.tier1;
  if (rsrp >= -90) return COLORS.tier2;
  if (rsrp >= -100) return COLORS.tier3;
  if (rsrp >= -110) return COLORS.tier4;
  return COLORS.tier5;
}

// Global NOC State
const state = {
  vehicles: new Map(), // vehicleId -> { data, marker, polyline, trail: [] }
  selectedVehicleId: null,
  alerts: [],
  totalSamples: 0,
  isDemoMode: true,
  demoTimer: null,
  firebaseUrl: '',
  firebaseToken: '',
  sseSource: null,
  pollTimer: null
};

// Initialize Leaflet Map
let map;
function initMap() {
  map = L.map('map', {
    center: [13.7563, 100.5018], // Bangkok Center
    zoom: 12,
    zoomControl: true
  });

  // OpenStreetMap with Dark NOC Styling (100% Free, No Watermark, No API Key Required)
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    maxZoom: 19,
    className: 'dark-noc-tile'
  }).addTo(map);
}

// Create Custom Car Icon with Heading Rotation & Glowing Aura
function createVehicleIcon(vehicleId, bearing = 0, alertState = 'NORMAL') {
  const isAlert = alertState === 'COVERAGE_HOLE' || alertState === 'INTERFERENCE';
  const glowColor = isAlert ? '#ef4444' : '#38bdf8';
  
  const html = `
    <div class="custom-vehicle-marker" style="transform: rotate(${bearing}deg);">
      <div class="vehicle-halo" style="box-shadow: 0 0 14px ${glowColor}; border-color: ${glowColor};"></div>
      <svg width="28" height="28" viewBox="0 0 24 24" fill="${glowColor}" stroke="#0b1120" stroke-width="1.5">
        <path d="M12 2L4.5 20.29l.71.71L12 18l6.79 3 .71-.71z"/>
      </svg>
      <div class="vehicle-marker-tag" style="transform: rotate(-${bearing}deg);">${vehicleId}</div>
    </div>
  `;
  return L.divIcon({
    html: html,
    className: 'leaflet-vehicle-icon',
    iconSize: [32, 32],
    iconAnchor: [16, 16]
  });
}

// Update or Create Vehicle on Map & Roster
function updateVehicle(data) {
  const vId = data.vehicleId;
  if (!vId) return;

  state.totalSamples++;
  document.getElementById('kpi-sample-count').textContent = state.totalSamples.toLocaleString();

  let entry = state.vehicles.get(vId);
  const latLng = [data.lat, data.lon];

  if (!entry) {
    // New vehicle
    const marker = L.marker(latLng, {
      icon: createVehicleIcon(vId, data.bearing || 0, data.alertState)
    }).addTo(map);

    marker.on('click', () => selectVehicle(vId));

    const polyline = L.polyline([], {
      color: getRsrpColor(data.rsrp),
      weight: 4,
      opacity: 0.85
    }).addTo(map);

    entry = {
      data: data,
      marker: marker,
      polyline: polyline,
      trail: [latLng]
    };
    state.vehicles.set(vId, entry);

    if (!state.selectedVehicleId) {
      selectVehicle(vId);
    }
  } else {
    // Existing vehicle
    entry.data = data;
    entry.marker.setLatLng(latLng);
    entry.marker.setIcon(createVehicleIcon(vId, data.bearing || 0, data.alertState));

    entry.trail.push(latLng);
    if (entry.trail.length > 500) entry.trail.shift();

    // Draw multi-color breadcrumb segments
    entry.polyline.setLatLngs(entry.trail);
    entry.polyline.setStyle({ color: getRsrpColor(data.rsrp) });
  }

  // Check Anomaly Alert
  if (data.alertState === 'COVERAGE_HOLE') {
    addAlert({
      type: 'COVERAGE_HOLE',
      title: `Coverage Hole Detected (${data.rsrp} dBm)`,
      desc: `${vId} (${data.vehicleName}) RSRP dropped below -110 dBm at PCI ${data.pci || 'N/A'}`,
      vehicleId: vId,
      lat: data.lat,
      lon: data.lon,
      time: new Date()
    });
  } else if (data.alertState === 'INTERFERENCE') {
    addAlert({
      type: 'INTERFERENCE',
      title: `High Interference Area (SINR ${data.sinr} dB)`,
      desc: `${vId} Strong RSRP (${data.rsrp} dBm) but poor SINR, potential pilot pollution / cross-feeder`,
      vehicleId: vId,
      lat: data.lat,
      lon: data.lon,
      time: new Date()
    });
  }

  renderVehicleRoster();

  // If this vehicle is currently selected in inspector, refresh inspector
  if (state.selectedVehicleId === vId) {
    renderInspector(data);
  }
}

// Render Fleet Roster Sidebar
function renderVehicleRoster() {
  const listEl = document.getElementById('vehicle-list');
  const countEl = document.getElementById('roster-badge');
  const kpiActive = document.getElementById('kpi-active-count');

  countEl.textContent = `${state.vehicles.size} VEHICLES`;
  kpiActive.textContent = state.vehicles.size;

  if (state.vehicles.size === 0) {
    listEl.innerHTML = `<div class="empty-state">No vehicles online. Start demo simulator or configure Firebase.</div>`;
    return;
  }

  let html = '';
  state.vehicles.forEach((entry, vId) => {
    const d = entry.data;
    const isSelected = state.selectedVehicleId === vId;
    const isRecording = d.isRecording !== false;
    const rsrpColor = getRsrpColor(d.rsrp);

    html += `
      <div class="vehicle-card ${isSelected ? 'selected' : ''}" onclick="selectVehicle('${vId}')">
        <div class="veh-top">
          <span class="veh-id">${d.vehicleId}</span>
          <span class="veh-status ${isRecording ? 'status-recording' : 'status-idle'}">
            ${isRecording ? '● STREAMING' : 'IDLE'}
          </span>
        </div>
        <div class="veh-name">${d.vehicleName || 'Field Drive Unit'}</div>
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

// Select Vehicle & Follow on Map
function selectVehicle(vehicleId) {
  state.selectedVehicleId = vehicleId;
  renderVehicleRoster();

  const entry = state.vehicles.get(vehicleId);
  if (entry) {
    renderInspector(entry.data);
    map.panTo([entry.data.lat, entry.data.lon]);
  }
}

// Render Dual-SIM Inspector
function renderInspector(d) {
  document.getElementById('inspector-vehicle-badge').textContent = `${d.vehicleId} • ${d.vehicleName || ''}`;

  // SIM 1 (Primary)
  document.getElementById('sim1-op').textContent = d.operator || 'AIS 5G';
  document.getElementById('sim1-rat').textContent = `${d.rat || 'NR_SA'} (${d.band || 'n78'})`;
  document.getElementById('sim1-pci').textContent = `${d.pci || '—'} / ${d.cellId || '—'}`;
  
  const rsrp1 = d.rsrp !== undefined ? d.rsrp : -95;
  document.getElementById('sim1-rsrp').textContent = `${rsrp1} dBm`;
  document.getElementById('sim1-rsrp').style.color = getRsrpColor(rsrp1);
  const rsrpPercent1 = Math.max(5, Math.min(100, Math.round(((rsrp1 + 140) / 100) * 100)));
  const bar1 = document.getElementById('sim1-rsrp-bar');
  bar1.style.width = `${rsrpPercent1}%`;
  bar1.style.backgroundColor = getRsrpColor(rsrp1);

  const sinr1 = d.sinr !== undefined ? Number(d.sinr) : 15.0;
  document.getElementById('sim1-sinr').textContent = `${sinr1.toFixed(1)} dB`;
  const sinrPercent1 = Math.max(5, Math.min(100, Math.round(((sinr1 + 10) / 40) * 100)));
  document.getElementById('sim1-sinr-bar').style.width = `${sinrPercent1}%`;

  // SIM 2 (Secondary)
  const sim2Rsrp = d.sim2Rsrp !== undefined ? Math.round(d.sim2Rsrp) : (rsrp1 - 6);
  const sim2Sinr = d.sim2Sinr !== undefined ? Number(d.sim2Sinr) : (sinr1 - 4.5);
  document.getElementById('sim2-op').textContent = d.sim2Operator || 'TRUE-H';
  document.getElementById('sim2-rat').textContent = `${d.sim2Rat || 'LTE'} (${d.sim2Band || 'B3'})`;
  document.getElementById('sim2-pci').textContent = `${d.sim2Pci || '144'}`;
  
  document.getElementById('sim2-rsrp').textContent = `${sim2Rsrp} dBm`;
  document.getElementById('sim2-rsrp').style.color = getRsrpColor(sim2Rsrp);
  const rsrpPercent2 = Math.max(5, Math.min(100, Math.round(((sim2Rsrp + 140) / 100) * 100)));
  const bar2 = document.getElementById('sim2-rsrp-bar');
  bar2.style.width = `${rsrpPercent2}%`;
  bar2.style.backgroundColor = getRsrpColor(sim2Rsrp);

  document.getElementById('sim2-sinr').textContent = `${sim2Sinr.toFixed(1)} dB`;
  const sinrPercent2 = Math.max(5, Math.min(100, Math.round(((sim2Sinr + 10) / 40) * 100)));
  document.getElementById('sim2-sinr-bar').style.width = `${sinrPercent2}%`;

  // Delta
  const delta = rsrp1 - sim2Rsrp;
  const deltaEl = document.getElementById('delta-val');
  if (delta >= 0) {
    deltaEl.textContent = `SIM 1 +${delta.toFixed(1)} dB (Dominant)`;
    deltaEl.className = 'delta-value text-emerald';
  } else {
    deltaEl.textContent = `SIM 2 +${Math.abs(delta).toFixed(1)} dB`;
    deltaEl.className = 'delta-value text-amber';
  }
}

// Add Anomaly Alert
function addAlert(alert) {
  // Deduplicate recent alerts for same vehicle & type within 15 seconds
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

// --- BANGKOK MULTI-VEHICLE DEMO SIMULATOR ---
const DEMO_ROUTES = [
  {
    vehicleId: 'DT-01',
    vehicleName: 'Unit 01 (Don Mueang / Vibhavadi)',
    lat: 13.8850,
    lon: 100.5900,
    bearing: 195,
    speed: 72,
    rat: 'NR_SA',
    band: 'n78',
    pci: 420,
    operator: 'AIS 5G',
    deltaLat: -0.0006,
    deltaLon: -0.0002
  },
  {
    vehicleId: 'DT-02',
    vehicleName: 'Unit 02 (Siam / Silom / Rama IV)',
    lat: 13.7460,
    lon: 100.5340,
    bearing: 110,
    speed: 34,
    rat: 'NR_SA',
    band: 'n78',
    pci: 312,
    operator: 'AIS 5G',
    deltaLat: -0.0002,
    deltaLon: 0.0005
  },
  {
    vehicleId: 'DT-03',
    vehicleName: 'Unit 03 (Sukhumvit / Bangna Highway)',
    lat: 13.6820,
    lon: 100.6120,
    bearing: 135,
    speed: 68,
    rat: 'LTE',
    band: 'B3',
    pci: 104,
    operator: 'TRUE-H',
    deltaLat: -0.0004,
    deltaLon: 0.0006
  }
];

function runDemoSimulationStep() {
  if (!state.isDemoMode) return;

  DEMO_ROUTES.forEach((r, idx) => {
    r.lat += r.deltaLat + (Math.random() - 0.5) * 0.0001;
    r.lon += r.deltaLon + (Math.random() - 0.5) * 0.0001;

    // Boundary bounces around Bangkok
    if (r.lat < 13.60 || r.lat > 13.95) r.deltaLat = -r.deltaLat;
    if (r.lon < 100.45 || r.lon > 100.75) r.deltaLon = -r.deltaLon;

    // Simulate fluctuating RF metrics
    let baseRsrp = idx === 0 ? -78 : (idx === 1 ? -92 : -86);
    let noise = Math.floor((Math.random() - 0.48) * 12);
    let currentRsrp = baseRsrp + noise;

    // Periodic Coverage Hole or Interference simulation
    let alertState = 'NORMAL';
    if (Math.random() < 0.04) {
      currentRsrp = -115;
      alertState = 'COVERAGE_HOLE';
    } else if (Math.random() < 0.03) {
      alertState = 'INTERFERENCE';
    }

    let sinr = alertState === 'INTERFERENCE' ? -2.5 : +(10 + Math.random() * 12).toFixed(1);

    const payload = {
      vehicleId: r.vehicleId,
      vehicleName: r.vehicleName,
      sessionId: 'demo_session',
      isRecording: true,
      updatedAt: Date.now(),
      lat: r.lat,
      lon: r.lon,
      bearing: r.bearing + Math.floor((Math.random() - 0.5) * 15),
      speedKmh: Math.max(15, r.speed + Math.floor((Math.random() - 0.5) * 10)),
      rat: r.rat,
      band: r.band,
      pci: r.pci,
      cellId: '6543' + idx,
      operator: r.operator,
      rsrp: currentRsrp,
      sinr: sinr,
      sim2Operator: 'TRUE-H',
      sim2Rat: 'LTE',
      sim2Band: 'B3',
      sim2Pci: 150 + idx * 2,
      sim2Rsrp: currentRsrp - 7,
      sim2Sinr: Math.max(1, sinr - 5),
      alertState: alertState
    };

    updateVehicle(payload);
  });
}

function startDemoSimulator() {
  state.isDemoMode = true;
  document.getElementById('kpi-sync-mode').textContent = 'DEMO SIMULATOR';
  document.getElementById('btn-toggle-demo').innerHTML = `
    <span class="dot-indicator dot-active"></span>
    <span>SIMULATOR ACTIVE</span>
  `;
  if (state.demoTimer) clearInterval(state.demoTimer);
  state.demoTimer = setInterval(runDemoSimulationStep, 1500);
}

function stopDemoSimulator() {
  state.isDemoMode = false;
  document.getElementById('btn-toggle-demo').innerHTML = `
    <span class="dot-indicator"></span>
    <span>SIMULATOR PAUSED</span>
  `;
  if (state.demoTimer) {
    clearInterval(state.demoTimer);
    state.demoTimer = null;
  }
}

// --- FIREBASE SYNC INTEGRATION ---
function connectFirebase(url, token) {
  const cleanUrl = url.trim().replace(/\/+$/, '');
  if (!cleanUrl.startsWith('http')) {
    alert('Please enter a valid https:// Firebase Database URL');
    return;
  }

  stopDemoSimulator();
  document.getElementById('kpi-sync-mode').textContent = 'FIREBASE LIVE';

  const authParam = token ? `?auth=${token.trim()}` : '';

  // Initial fetch of current fleet vehicles
  fetch(`${cleanUrl}/fleet/vehicles.json${authParam}`)
    .then(res => res.json())
    .then(data => {
      if (data && typeof data === 'object') {
        Object.values(data).forEach(veh => {
          if (veh && veh.vehicleId && veh.lat && veh.lon) {
            updateVehicle(veh);
          }
        });
      }
    })
    .catch(err => {
      console.warn('Firebase initial fetch error:', err);
    });

  // Setup periodic polling for vehicles
  if (state.pollTimer) clearInterval(state.pollTimer);
  state.pollTimer = setInterval(() => {
    fetch(`${cleanUrl}/fleet/vehicles.json${authParam}`)
      .then(res => res.json())
      .then(data => {
        if (data && typeof data === 'object') {
          Object.values(data).forEach(veh => {
            if (veh && veh.vehicleId && veh.lat && veh.lon) {
              updateVehicle(veh);
            }
          });
        }
      })
      .catch(e => console.warn('Sync poll error', e));
  }, 2000);
}

// Setup Event Listeners
document.addEventListener('DOMContentLoaded', () => {
  initMap();
  startDemoSimulator();

  // Demo toggle
  document.getElementById('btn-toggle-demo').addEventListener('click', () => {
    if (state.isDemoMode) {
      stopDemoSimulator();
    } else {
      startDemoSimulator();
    }
  });

  // Map Controls
  document.getElementById('btn-center-fleet').addEventListener('click', () => {
    const latLngs = [];
    state.vehicles.forEach(e => latLngs.push([e.data.lat, e.data.lon]));
    if (latLngs.length > 0) {
      map.fitBounds(L.latLngBounds(latLngs), { padding: [50, 50] });
    }
  });

  document.getElementById('btn-clear-trails').addEventListener('click', () => {
    state.vehicles.forEach(e => {
      e.trail = [];
      e.polyline.setLatLngs([]);
    });
  });

  // Modal Controls
  const modal = document.getElementById('firebase-modal');
  document.getElementById('btn-firebase-modal').addEventListener('click', () => {
    modal.classList.add('active');
  });

  document.getElementById('btn-close-modal').addEventListener('click', () => {
    modal.classList.remove('active');
  });

  document.getElementById('btn-cancel-fb').addEventListener('click', () => {
    modal.classList.remove('active');
  });

  document.getElementById('btn-connect-fb').addEventListener('click', () => {
    const url = document.getElementById('input-fb-url').value;
    const token = document.getElementById('input-fb-token').value;
    if (url.trim()) {
      connectFirebase(url, token);
      modal.classList.remove('active');
    }
  });
});
