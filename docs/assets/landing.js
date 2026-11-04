/* ==========================================================================
   RF Field Suite — Landing Page Interactive Controller
   ========================================================================== */

document.addEventListener('DOMContentLoaded', () => {
  initMobileNav();
  initRfGaugeSimulator();
  initScreenGallery();
  resolveLatestGitHubRelease();
});

/**
 * 1. Mobile Navigation Drawer
 */
function initMobileNav() {
  const navToggle = document.getElementById('navToggle');
  const navMenu = document.getElementById('navMenu');

  if (!navToggle || !navMenu) return;

  navToggle.addEventListener('click', () => {
    const isExpanded = navToggle.getAttribute('aria-expanded') === 'true';
    navToggle.setAttribute('aria-expanded', String(!isExpanded));
    navMenu.classList.toggle('open');
  });

  document.addEventListener('click', (e) => {
    if (!navToggle.contains(e.target) && !navMenu.contains(e.target)) {
      navMenu.classList.remove('open');
      navToggle.setAttribute('aria-expanded', 'false');
    }
  });

  navMenu.querySelectorAll('a').forEach((link) => {
    link.addEventListener('click', () => {
      navMenu.classList.remove('open');
      navToggle.setAttribute('aria-expanded', 'false');
    });
  });
}

/**
 * 2. Interactive 5G/LTE RF Signal Gauge Simulator
 */
function initRfGaugeSimulator() {
  const slider = document.getElementById('rsrpSlider');
  const sliderReadout = document.getElementById('simSliderReadout');
  const gaugeArc = document.getElementById('simGaugeArc');
  const rsrpValue = document.getElementById('simRsrpValue');
  const qualityBadge = document.getElementById('simQualityBadge');
  const rsrqValue = document.getElementById('simRsrqValue');
  const sinrValue = document.getElementById('simSinrValue');
  const cqiValue = document.getElementById('simCqiValue');
  const dlValue = document.getElementById('simDlValue');
  const diagNote = document.getElementById('simDiagnosticNote');
  const presetBtns = document.querySelectorAll('.preset-btn');

  if (!slider || !gaugeArc || !rsrpValue) return;

  const MIN_RSRP = -135;
  const MAX_RSRP = -50;
  const ARC_LENGTH = 330;

  function updateSimulator(rsrp) {
    const clamped = Math.max(MIN_RSRP, Math.min(MAX_RSRP, Number(rsrp)));
    const ratio = (clamped - MIN_RSRP) / (MAX_RSRP - MIN_RSRP);
    const dashOffset = Math.round(ARC_LENGTH * (1 - ratio));

    gaugeArc.setAttribute('stroke-dashoffset', String(dashOffset));
    rsrpValue.textContent = String(clamped);
    if (sliderReadout) sliderReadout.textContent = `${clamped} dBm`;

    let quality = 'EXCELLENT';
    let badgeClass = 'quality-badge quality-excellent';
    let color = '#10b981';
    let rsrq = '-7.5 dB';
    let sinr = '+24.5 dB';
    let cqi = '15 · 256QAM';
    let dl = '780 Mbps';
    let note =
      'Dominant serving sector with high SINR headroom. Ideal for 5G NR 256QAM modulation and 4x4 MIMO spatial multiplexing with zero handover pressure.';

    if (clamped >= -80) {
      quality = 'EXCELLENT';
      badgeClass = 'quality-badge quality-excellent';
      color = '#10b981';
      rsrq = `${(-6 - ( -50 - clamped ) * 0.08).toFixed(1)} dB`;
      sinr = `+${(18 + (clamped + 80) * 0.35).toFixed(1)} dB`;
      cqi = '15 · 256QAM';
      dl = `${Math.round(580 + (clamped + 80) * 10)} Mbps`;
      note =
        'Dominant serving sector with high SINR headroom. Ideal for 5G NR 256QAM modulation and 4x4 MIMO spatial multiplexing with zero handover pressure.';
    } else if (clamped >= -95) {
      quality = 'GOOD';
      badgeClass = 'quality-badge quality-good';
      color = '#3b82f6';
      rsrq = `${(-9.5 - (-80 - clamped) * 0.12).toFixed(1)} dB`;
      sinr = `+${(10 + (clamped + 95) * 0.5).toFixed(1)} dB`;
      cqi = '12 · 64QAM';
      dl = `${Math.round(240 + (clamped + 95) * 18)} Mbps`;
      note =
        'Stable urban macro coverage. Reliable 64QAM/256QAM downlink bearer with normal A3 neighbor event monitoring.';
    } else if (clamped >= -110) {
      quality = 'FAIR';
      badgeClass = 'quality-badge quality-fair';
      color = '#f59e0b';
      rsrq = `${(-12.5 - (-95 - clamped) * 0.18).toFixed(1)} dB`;
      sinr = `+${(2 + (clamped + 110) * 0.45).toFixed(1)} dB`;
      cqi = '7 · 16QAM';
      dl = `${Math.round(45 + (clamped + 110) * 11)} Mbps`;
      note =
        'Attenuated signal region (indoor penetration or sector boundary). Monitor neighbor PCI delta for A3/A5 handover execution.';
    } else {
      quality = 'CELL EDGE';
      badgeClass = 'quality-badge quality-poor';
      color = '#ef4444';
      rsrq = `${(-16.0 - (-110 - clamped) * 0.16).toFixed(1)} dB`;
      sinr = `${(-4 + (clamped + 135) * 0.22).toFixed(1)} dB`;
      cqi = '3 · QPSK';
      dl = `${Math.max(4, Math.round(8 + (clamped + 135) * 1.2))} Mbps`;
      note =
        'Critical weak coverage alert triggered! High risk of RRC drop, missed handover, or LTE fallback (B2 event). Inspect antenna tilt and neighbor relations.';
    }

    rsrpValue.setAttribute('fill', color);
    if (qualityBadge) {
      qualityBadge.className = badgeClass;
      qualityBadge.textContent = quality;
    }
    if (rsrqValue) rsrqValue.textContent = rsrq;
    if (sinrValue) sinrValue.textContent = sinr;
    if (cqiValue) cqiValue.textContent = cqi;
    if (dlValue) dlValue.textContent = dl;
    if (diagNote) diagNote.textContent = note;
  }

  slider.addEventListener('input', (e) => {
    const val = Number(e.target.value);
    presetBtns.forEach((btn) => {
      btn.classList.toggle('active', Number(btn.dataset.rsrp) === val);
    });
    updateSimulator(val);
  });

  presetBtns.forEach((btn) => {
    btn.addEventListener('click', () => {
      const val = Number(btn.dataset.rsrp);
      slider.value = String(val);
      presetBtns.forEach((b) => b.classList.remove('active'));
      btn.classList.add('active');
      updateSimulator(val);
    });
  });

  updateSimulator(Number(slider.value));
}

/**
 * 3. FigCraft 4-Screen App Gallery Switcher
 */
function initScreenGallery() {
  const SCREENS = {
    dashboard: {
      img: 'assets/images/screen_dashboard.png',
      alt: '01 Live RF Dashboard screen capture',
      badge: 'SCREEN 01 · FIGCRAFT FRAME 412×915',
      title: 'Live 5G/LTE Drive Test Dashboard',
      desc: 'Real-time RSRP semi-circular gauge with live RSRQ, SINR, RSSI, CQI, and Timing Advance. Includes RAT lock controls (Auto, LTE Lock, NR Lock), GPS sampling cadence chips, and Serving + Neighbor cell component monitoring.',
      bullets: [
        'Real-time RSRP (-140 to -44 dBm), RSRQ, SINR, and RSSI telemetry cards',
        'Serving Cell & 5G NR (SA/NSA) + LTE Carrier Aggregation component breakdown',
        'Instant RAT Lock bar (Auto · LTE Lock · NR Lock) and Cadence control (1s / 2s / 5s)',
        'Neighboring cells monitor with PCI, EARFCN/NR-ARFCN, and RSRP delta'
      ],
      tags: ['TelephonyCallback API', 'CellSignalStrengthNr', 'CellIdentityLte', 'GNSS 3D Fix']
    },
    routemap: {
      img: 'assets/images/screen_route_map.png',
      alt: '02 Interactive Route Map screen capture',
      badge: 'SCREEN 02 · FIGCRAFT FRAME 412×915',
      title: 'Interactive Route Map & Sector Inspector',
      desc: 'Spatial route visualization powered by Google Maps SDK & Leaflet. Color-code GPS drive traces by RSRP, RSRQ, SINR, Technology, or Speed with real-time serving cell sector lines.',
      bullets: [
        '5 telemetry color modes and 4 scientific palettes (Classic, Thermique, Viridis, Ocean)',
        'Dynamic serving-cell vector lines, azimuth sector wedges, and neighbor candidates',
        'Automated detection of ping-pong handovers (<30s) and missed handovers (≥6 dB margin)',
        'Offline tile caching for uninterrupted rural and highway field surveys'
      ],
      tags: ['Google Maps SDK', 'Leaflet Vector Canvas', 'Handover Inspector', 'Offline Tile Cache']
    },
    sessions: {
      img: 'assets/images/screen_sessions.png',
      alt: '03 Campaigns & Multi-Format Export screen capture',
      badge: 'SCREEN 03 · FIGCRAFT FRAME 412×915',
      title: 'Campaigns Manager & 3D KMZ Exporter',
      desc: 'Organize field measurement sessions in local SQLite/Room storage and export to industry-standard post-processing formats with one tap.',
      bullets: [
        '3D Google Earth KMZ export with altitude-extruded RSRP signal walls and cell pins',
        'Exact 23-column CSV schema, OpenGIS KML 2.2, and GeoJSON feature collections',
        'Automated printable PDF Engineering Report with CDF curves and route score',
        'Batch session selection, search filtering, and external CSV log import'
      ],
      tags: ['3D Google Earth KMZ', '23-Column CSV', 'OpenGIS KML 2.2', 'PDF Engineering Report']
    },
    settings: {
      img: 'assets/images/screen_settings.png',
      alt: '04 OEM Radio & Settings screen capture',
      badge: 'SCREEN 04 · FIGCRAFT FRAME 412×915',
      title: 'OEM Radio Controls & Diagnostic Thresholds',
      desc: 'Configure cellular radio collection backends, Dual-SIM subscription targets, diagnostic alert thresholds, and direct Android engineering menu shortcuts.',
      bullets: [
        'Select between Android TelephonyManager API and Root / Qualcomm Diag modes',
        'Dual-SIM slot selector for simultaneous carrier benchmarking (SIM 1 / SIM 2)',
        'Customizable RSRP weak-signal alert thresholds and handover margin parameters',
        'One-tap launcher for Android hidden Radio Info (*#*#4636#*#*) band locking'
      ],
      tags: ['Dual-SIM Benchmarking', 'Radio Info *#*#4636#*#*', 'Foreground WakeLock', 'Custom Thresholds']
    }
  };

  const tabs = document.querySelectorAll('.screen-tab');
  const imgEl = document.getElementById('activeScreenImg');
  const badgeEl = document.getElementById('activeScreenBadge');
  const titleEl = document.getElementById('activeScreenTitle');
  const descEl = document.getElementById('activeScreenDesc');
  const bulletsEl = document.getElementById('activeScreenBullets');
  const tagsEl = document.getElementById('activeScreenTags');

  if (!tabs.length || !imgEl) return;

  tabs.forEach((tab) => {
    tab.addEventListener('click', () => {
      const key = tab.dataset.screen;
      const data = SCREENS[key];
      if (!data) return;

      tabs.forEach((t) => {
        t.classList.remove('active');
        t.setAttribute('aria-selected', 'false');
      });
      tab.classList.add('active');
      tab.setAttribute('aria-selected', 'true');

      imgEl.src = data.img;
      imgEl.alt = data.alt;
      if (badgeEl) badgeEl.textContent = data.badge;
      if (titleEl) titleEl.textContent = data.title;
      if (descEl) descEl.textContent = data.desc;

      if (bulletsEl) {
        bulletsEl.innerHTML = data.bullets.map((b) => `<li>${b}</li>`).join('');
      }
      if (tagsEl) {
        tagsEl.innerHTML = data.tags.map((t) => `<span class="tech-pill">${t}</span>`).join('');
      }
    });
  });
}

/**
 * 4. GitHub Releases API Resolver for "Download App" APK Links
 */
async function resolveLatestGitHubRelease() {
  const REPO_API = 'https://api.github.com/repos/EEMEEMMEEx/Drive-Test-5G-LTE-RF/releases/latest';
  const FALLBACK_URL = 'https://github.com/EEMEEMMEEx/Drive-Test-5G-LTE-RF/releases/latest';

  try {
    const res = await fetch(REPO_API, {
      headers: { Accept: 'application/vnd.github+json' }
    });
    if (!res.ok) return;

    const release = await res.json();
    const tag = release.tag_name || 'v1.4.2-android';
    const assets = Array.isArray(release.assets) ? release.assets : [];
    const apkAsset =
      assets.find((a) => typeof a.name === 'string' && a.name.toLowerCase().endsWith('.apk')) ||
      assets[0];

    const downloadUrl = (apkAsset && apkAsset.browser_download_url) || release.html_url || FALLBACK_URL;
    const sizeMb =
      apkAsset && typeof apkAsset.size === 'number'
        ? `${(apkAsset.size / (1024 * 1024)).toFixed(1)} MB`
        : '64.9 MB';
    const assetName = (apkAsset && apkAsset.name) || 'app-debug.apk';
    const shortTag = tag.replace(/-android$/i, '');

    document.querySelectorAll('[data-apk-download]').forEach((el) => {
      el.setAttribute('href', downloadUrl);
    });

    document.querySelectorAll('[data-apk-tag]').forEach((el) => {
      el.textContent = shortTag;
    });

    document.querySelectorAll('[data-apk-meta]').forEach((el) => {
      el.textContent = `Latest APK · ${tag} · ${sizeMb}`;
    });

    const releaseTagEl = document.getElementById('releaseTag');
    const releaseAssetNameEl = document.getElementById('releaseAssetName');
    const releaseAssetSizeEl = document.getElementById('releaseAssetSize');
    const releaseNotesLinkEl = document.getElementById('releaseNotesLink');

    if (releaseTagEl) releaseTagEl.textContent = tag;
    if (releaseAssetNameEl) releaseAssetNameEl.textContent = assetName;
    if (releaseAssetSizeEl) releaseAssetSizeEl.textContent = sizeMb;
    if (releaseNotesLinkEl && release.html_url) {
      releaseNotesLinkEl.setAttribute('href', release.html_url);
    }
  } catch {
    // Graceful static fallback to /releases/latest already present in HTML
  }
}
