/**
 * NOC Live Fleet Telemetry & GIS Dashboard — Drive Test LTE/5G v1.12.2
 * 100% Real-Time Live Telemetry (No Demo)
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
  firebaseUrl: '',
  firebaseToken: '',
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

  countEl.textContent = `${state.vehicles.size} ACTIVE`;
  kpiActive.textContent = state.vehicles.size;

  if (state.vehicles.size === 0) {
    listEl.innerHTML = `
      <div class="empty-state" style="padding: 24px 12px; text-align: center;">
        <div style="font-size: 28px; margin-bottom: 8px;">📡</div>
        <div style="font-weight: 700; color: #38bdf8; margin-bottom: 4px;">WAITING FOR FIELD VEHICLES</div>
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
          <span class="veh-status status-recording">
            ● STREAMING
          </span>
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

// Reset Dual-SIM Inspector
function resetInspector() {
  document.getElementById('sim1-op').textContent = '—';
  document.getElementById('sim1-rat').textContent = '—';
  document.getElementById('sim1-pci').textContent = '—';
  document.getElementById('sim1-rsrp').textContent = '— dBm';
  document.getElementById('sim1-rsrp').style.color = '#94a3b8';
  document.getElementById('sim1-rsrp-bar').style.width = '0%';
  document.getElementById('sim1-sinr').textContent = '— dB';
  document.getElementById('sim1-sinr-bar').style.width = '0%';

  document.getElementById('sim2-op').textContent = '—';
  document.getElementById('sim2-rat').textContent = '—';
  document.getElementById('sim2-pci').textContent = '—';
  document.getElementById('sim2-rsrp').textContent = '— dBm';
  document.getElementById('sim2-rsrp').style.color = '#94a3b8';
  document.getElementById('sim2-rsrp-bar').style.width = '0%';
  document.getElementById('sim2-sinr').textContent = '— dB';
  document.getElementById('sim2-sinr-bar').style.width = '0%';

  const deltaEl = document.getElementById('delta-val');
  deltaEl.textContent = '—';
  deltaEl.className = 'delta-value text-slate';
}

// Remove Single Vehicle From Map & State
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

// Render Dual-SIM Inspector
function renderInspector(d) {
  document.getElementById('inspector-vehicle-badge').textContent = `${d.vehicleId} • ${d.vehicleName || 'Vehicle'}`;

  // SIM 1 (Primary)
  document.getElementById('sim1-op').textContent = d.operator || 'Primary SIM';
  document.getElementById('sim1-rat').textContent = d.rat ? `${d.rat} (${d.band || '—'})` : '—';
  document.getElementById('sim1-pci').textContent = `${d.pci || '—'} / ${d.cellId || '—'}`;
  
  if (d.rsrp !== undefined && d.rsrp !== null) {
    const rsrp1 = Number(d.rsrp);
    document.getElementById('sim1-rsrp').textContent = `${rsrp1} dBm`;
    document.getElementById('sim1-rsrp').style.color = getRsrpColor(rsrp1);
    const rsrpPercent1 = Math.max(5, Math.min(100, Math.round(((rsrp1 + 140) / 100) * 100)));
    const bar1 = document.getElementById('sim1-rsrp-bar');
    bar1.style.width = `${rsrpPercent1}%`;
    bar1.style.backgroundColor = getRsrpColor(rsrp1);
  } else {
    document.getElementById('sim1-rsrp').textContent = '— dBm';
    document.getElementById('sim1-rsrp-bar').style.width = '0%';
  }

  if (d.sinr !== undefined && d.sinr !== null) {
    const sinr1 = Number(d.sinr);
    document.getElementById('sim1-sinr').textContent = `${sinr1.toFixed(1)} dB`;
    const sinrPercent1 = Math.max(5, Math.min(100, Math.round(((sinr1 + 10) / 40) * 100)));
    document.getElementById('sim1-sinr-bar').style.width = `${sinrPercent1}%`;
  } else {
    document.getElementById('sim1-sinr').textContent = '— dB';
    document.getElementById('sim1-sinr-bar').style.width = '0%';
  }

  // SIM 2 (Secondary / Benchmark)
  if (d.sim2Rsrp !== undefined && d.sim2Rsrp !== null) {
    const sim2Rsrp = Math.round(Number(d.sim2Rsrp));
    document.getElementById('sim2-op').textContent = d.sim2Operator || 'SIM 2 (Secondary)';
    document.getElementById('sim2-rat').textContent = d.sim2Rat ? `${d.sim2Rat} (${d.sim2Band || '—'})` : (d.sim2Band ? `LTE (${d.sim2Band})` : '—');
    document.getElementById('sim2-pci').textContent = `${d.sim2Pci || '—'}`;
    
    document.getElementById('sim2-rsrp').textContent = `${sim2Rsrp} dBm`;
    document.getElementById('sim2-rsrp').style.color = getRsrpColor(sim2Rsrp);
    const rsrpPercent2 = Math.max(5, Math.min(100, Math.round(((sim2Rsrp + 140) / 100) * 100)));
    const bar2 = document.getElementById('sim2-rsrp-bar');
    bar2.style.width = `${rsrpPercent2}%`;
    bar2.style.backgroundColor = getRsrpColor(sim2Rsrp);

    if (d.sim2Sinr !== undefined && d.sim2Sinr !== null) {
      const sim2Sinr = Number(d.sim2Sinr);
      document.getElementById('sim2-sinr').textContent = `${sim2Sinr.toFixed(1)} dB`;
      const sinrPercent2 = Math.max(5, Math.min(100, Math.round(((sim2Sinr + 10) / 40) * 100)));
      document.getElementById('sim2-sinr-bar').style.width = `${sinrPercent2}%`;
    } else {
      document.getElementById('sim2-sinr').textContent = '— dB';
      document.getElementById('sim2-sinr-bar').style.width = '0%';
    }

    // Delta if both SIMs exist
    if (d.rsrp !== undefined && d.rsrp !== null) {
      const delta = Number(d.rsrp) - sim2Rsrp;
      const deltaEl = document.getElementById('delta-val');
      if (delta >= 0) {
        deltaEl.textContent = `SIM 1 +${delta.toFixed(1)} dB (Dominant)`;
        deltaEl.className = 'delta-value text-emerald';
      } else {
        deltaEl.textContent = `SIM 2 +${Math.abs(delta).toFixed(1)} dB (Dominant)`;
        deltaEl.className = 'delta-value text-amber';
      }
    }
  } else {
    // Single SIM active (no SIM 2 telemetry reported)
    document.getElementById('sim2-op').textContent = 'N/A';
    document.getElementById('sim2-rat').textContent = 'Single SIM Mode';
    document.getElementById('sim2-pci').textContent = '—';
    document.getElementById('sim2-rsrp').textContent = '— dBm';
    document.getElementById('sim2-rsrp').style.color = '#64748b';
    document.getElementById('sim2-rsrp-bar').style.width = '0%';
    document.getElementById('sim2-sinr').textContent = '— dB';
    document.getElementById('sim2-sinr-bar').style.width = '0%';
    
    const deltaEl = document.getElementById('delta-val');
    deltaEl.textContent = 'Single SIM Mode (No Benchmark)';
    deltaEl.className = 'delta-value text-slate';
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

// Clear All Fleet Markers and Trails
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
  renderVehicleRoster();
  resetInspector();
}

// Sync Live Fleet (Strict filter: Only actively recording vehicles with fresh heartbeat)
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
      // Must be actively recording (isRecording === true) AND have recent heartbeat
      const isRecording = veh.isRecording === true;
      const isFresh = veh.updatedAt ? (now - veh.updatedAt < HEARTBEAT_TIMEOUT_MS) : false;

      if (isRecording && isFresh) {
        activeIncomingMap.set(veh.vehicleId, veh);
      }
    }
  });

  // Remove stale or stopped vehicles from the map
  const toRemove = [];
  state.vehicles.forEach((_, vId) => {
    if (!activeIncomingMap.has(vId)) {
      toRemove.push(vId);
    }
  });
  toRemove.forEach(vId => removeVehicle(vId));

  // Add or update active streaming vehicles
  activeIncomingMap.forEach(veh => {
    updateVehicle(veh);
  });

  renderVehicleRoster();

  if (state.vehicles.size === 0) {
    document.getElementById('inspector-vehicle-badge').textContent = 'NO ACTIVE FLEET';
    resetInspector();
  }
}

// --- FIREBASE LIVE TELEMETRY ---
function connectFirebase(url, token) {
  const cleanUrl = (url || '').trim().replace(/\/+$/, '').replace(/\.json$/, '');
  if (!cleanUrl.startsWith('http')) {
    console.warn('Invalid Firebase Database URL in env.js:', cleanUrl);
    return;
  }

  state.firebaseUrl = cleanUrl;
  state.firebaseToken = token || '';
  const authParam = token ? `?auth=${token.trim()}` : '';

  // Initial fetch of current fleet vehicles
  fetch(`${cleanUrl}/fleet/vehicles.json${authParam}`)
    .then(res => {
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}: ${res.statusText}`);
      }
      return res.json();
    })
    .then(data => {
      if (data && data.error) {
        throw new Error(data.error);
      }

      document.getElementById('kpi-sync-mode').textContent = 'FIREBASE LIVE';
      document.getElementById('kpi-sync-mode').className = 'kpi-value text-emerald';

      syncLiveFleet(data);

      if (state.vehicles.size > 0) {
        const first = Array.from(state.vehicles.values())[0];
        map.panTo([first.data.lat, first.data.lon]);
      }

      // Setup continuous live polling (every 1.5 seconds)
      if (state.pollTimer) clearInterval(state.pollTimer);
      state.pollTimer = setInterval(() => {
        fetch(`${cleanUrl}/fleet/vehicles.json${authParam}`)
          .then(res => res.json())
          .then(liveData => {
            syncLiveFleet(liveData);
          })
          .catch(e => console.warn('Firebase live poll warning:', e));
      }, 1500);
    })
    .catch(err => {
      console.error('Firebase connection error:', err);
      document.getElementById('kpi-sync-mode').textContent = 'FIREBASE ERROR';
      document.getElementById('kpi-sync-mode').className = 'kpi-value text-rose';
      const listEl = document.getElementById('vehicle-list');
      listEl.innerHTML = `
        <div class="empty-state" style="padding: 20px 12px; color: #f87171;">
          <div style="font-weight: 700; margin-bottom: 6px;">❌ FIREBASE ERROR</div>
          <div style="font-size: 10px; line-height: 1.5;">${err.message}<br>โปรดตรวจสอบ Rules ใน Firebase Console (.read: true)</div>
        </div>
      `;
    });
}

// Purge Offline / Stopped Vehicles from Firebase
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

        // If stopped or stale, delete from Firebase
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

// Setup Event Listeners
document.addEventListener('DOMContentLoaded', () => {
  initMap();

  const firebaseUrl = window.ENV?.FIREBASE_DB_URL || 'https://drivetest-telemetry-default-rtdb.asia-southeast1.firebasedatabase.app';
  const firebaseToken = window.ENV?.FIREBASE_AUTH_TOKEN || '';

  // Directly connect to Firebase live telemetries on startup
  connectFirebase(firebaseUrl, firebaseToken);

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

  document.getElementById('btn-purge-offline').addEventListener('click', () => {
    purgeOfflineVehicles();
  });
});
