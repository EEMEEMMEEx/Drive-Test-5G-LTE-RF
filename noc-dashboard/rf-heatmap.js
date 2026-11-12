/**
 * RF Coverage Heatmap Layer for Leaflet (IDW Spatial Interpolation)
 * Live Fleet NOC Dashboard — Drive Test LTE/5G
 *
 * Implements Inverse Distance Weighting (IDW) interpolation on an HTML5 Canvas,
 * synchronized with Leaflet map bounds, center, and zoom.
 * Zero external dependencies beyond Leaflet.
 */

(function (global) {
  'use strict';

  // ---------------------------------------------------------------------------
  // Standard 5-Tier RF Color Palettes
  // ---------------------------------------------------------------------------
  const RF_TIERS = {
    RSRP: [
      { tier: 1, min: -80, max: -55,  color: '#10b981', rgb: [16, 185, 129], label: '≥ -80 dBm',   name: 'Excellent' },
      { tier: 2, min: -90, max: -80,  color: '#38bdf8', rgb: [56, 189, 248], label: '-80 to -90 dBm', name: 'Good' },
      { tier: 3, min: -100, max: -90, color: '#f59e0b', rgb: [245, 158, 11], label: '-90 to -100 dBm', name: 'Fair' },
      { tier: 4, min: -110, max: -100, color: '#f97316', rgb: [249, 115, 22], label: '-100 to -110 dBm', name: 'Poor' },
      { tier: 5, min: -135, max: -110, color: '#ef4444', rgb: [239, 68, 68], label: '< -110 dBm',   name: 'Coverage Hole' }
    ],
    SINR: [
      { tier: 1, min: 20, max: 30,  color: '#10b981', rgb: [16, 185, 129], label: '≥ 20 dB',   name: 'Excellent' },
      { tier: 2, min: 13, max: 20,  color: '#38bdf8', rgb: [56, 189, 248], label: '13 to 20 dB', name: 'Good' },
      { tier: 3, min: 5,  max: 13,  color: '#f59e0b', rgb: [245, 158, 11], label: '5 to 13 dB',  name: 'Fair' },
      { tier: 4, min: 0,  max: 5,   color: '#f97316', rgb: [249, 115, 22], label: '0 to 5 dB',   name: 'Poor' },
      { tier: 5, min: -10, max: 0,  color: '#ef4444', rgb: [239, 68, 68], label: '< 0 dB',      name: 'Interference' }
    ]
  };

  /** Linear interpolation between two RGB colors */
  function lerpRgb(c1, c2, t) {
    const clampedT = Math.max(0, Math.min(1, t));
    return [
      Math.round(c1[0] + (c2[0] - c1[0]) * clampedT),
      Math.round(c1[1] + (c2[1] - c1[1]) * clampedT),
      Math.round(c1[2] + (c2[2] - c1[2]) * clampedT)
    ];
  }

  /**
   * Resolves RGB color for a given RF value and metric.
   * Supports 'gradient' (smooth continuous contour) and 'stepped' (discrete 5-tier zones).
   */
  function resolveColor(val, metric = 'RSRP', mode = 'gradient') {
    const isSinr = (metric === 'SINR');

    if (mode === 'stepped') {
      if (isSinr) {
        if (val >= 20) return [16, 185, 129];
        if (val >= 13) return [56, 189, 248];
        if (val >= 5)  return [245, 158, 11];
        if (val >= 0)  return [249, 115, 22];
        return [239, 68, 68];
      } else {
        if (val >= -80)  return [16, 185, 129];
        if (val >= -90)  return [56, 189, 248];
        if (val >= -100) return [245, 158, 11];
        if (val >= -110) return [249, 115, 22];
        return [239, 68, 68];
      }
    }

    // Continuous smooth gradient mode
    if (isSinr) {
      if (val >= 20) return [16, 185, 129];
      if (val >= 13) return lerpRgb([56, 189, 248], [16, 185, 129], (val - 13) / 7);
      if (val >= 5)  return lerpRgb([245, 158, 11], [56, 189, 248], (val - 5) / 8);
      if (val >= 0)  return lerpRgb([249, 115, 22], [245, 158, 11], val / 5);
      return lerpRgb([239, 68, 68], [249, 115, 22], Math.max(0, (val + 10) / 10));
    } else {
      if (val >= -80)  return [16, 185, 129];
      if (val >= -90)  return lerpRgb([56, 189, 248], [16, 185, 129], (val + 90) / 10);
      if (val >= -100) return lerpRgb([245, 158, 11], [56, 189, 248], (val + 100) / 10);
      if (val >= -110) return lerpRgb([249, 115, 22], [245, 158, 11], (val + 110) / 10);
      return lerpRgb([239, 68, 68], [249, 115, 22], Math.max(0, (val + 125) / 15));
    }
  }

  // ---------------------------------------------------------------------------
  // Base Layer Interop
  // ---------------------------------------------------------------------------
  const LeafletLayerClass = (typeof global.L !== 'undefined' && global.L && global.L.Layer)
    ? global.L.Layer
    : class {
        addTo(map) {
          if (map && typeof map.addLayer === 'function') map.addLayer(this);
          else this.onAdd(map);
          return this;
        }
        remove() {
          if (this._map) {
            if (typeof this._map.removeLayer === 'function') this._map.removeLayer(this);
            else this.onRemove(this._map);
          }
          return this;
        }
        removeFrom(map) {
          return this.remove();
        }
      };

  // ---------------------------------------------------------------------------
  // RfHeatmapLayer Class
  // ---------------------------------------------------------------------------
  class RfHeatmapLayer extends LeafletLayerClass {
    /**
     * @param {Object} [options]
     * @param {number} [options.radius=65] - Search/influence radius in screen pixels
     * @param {number} [options.cellSize=5] - Spatial grid evaluation step in pixels
     * @param {number} [options.power=2] - IDW distance weighting exponent
     * @param {number} [options.opacity=0.75] - Overall layer opacity (0.0 - 1.0)
     * @param {string} [options.metric='RSRP'] - Metric to interpolate ('RSRP' or 'SINR')
     * @param {boolean} [options.smooth=true] - Bilinear smoothing on canvas
     * @param {string} [options.colorMode='gradient'] - 'gradient' or 'stepped'
     * @param {boolean} [options.edgeFalloff=true] - Soft cosine edge feathering
     * @param {boolean} [options.visible=true] - Initial visibility
     * @param {string} [options.pane='overlayPane'] - Leaflet map pane
     */
    constructor(options = {}) {
      super();

      this.options = Object.assign({
        radius: 65,
        cellSize: 5,
        power: 2,
        opacity: 0.75,
        metric: 'RSRP',
        smooth: true,
        colorMode: 'gradient',
        edgeFalloff: true,
        visible: true,
        pane: 'overlayPane'
      }, options);

      this._data = [];
      this._map = null;
      this._canvas = null;
      this._ctx = null;
      this._offscreenCanvas = null;
      this._offscreenCtx = null;
      this._frameId = null;
      this._animPending = false;
      this._visible = this.options.visible !== false;
      this._metric = String(this.options.metric || 'RSRP').toUpperCase();

      // Bound map event listeners
      this._onMoveEnd = this._onMoveEnd.bind(this);
      this._onZoomEnd = this._onZoomEnd.bind(this);
      this._onResize = this._onResize.bind(this);
      this._onViewReset = this._onViewReset.bind(this);
    }

    // -------------------------------------------------------------------------
    // Leaflet Layer Lifecycle
    // -------------------------------------------------------------------------
    onAdd(map) {
      this._map = map;

      if (!this._canvas) {
        this._initCanvas();
      }

      const pane = (map.getPane && typeof map.getPane === 'function')
        ? map.getPane(this.options.pane || 'overlayPane')
        : (map.getPanes ? map.getPanes().overlayPane : null);

      if (pane && this._canvas.parentNode !== pane) {
        pane.appendChild(this._canvas);
      }

      map.on('moveend', this._onMoveEnd);
      map.on('zoomend', this._onZoomEnd);
      map.on('resize', this._onResize);
      map.on('viewreset', this._onViewReset);

      this._reset();
      this.redraw();
      return this;
    }

    onRemove(map) {
      if (this._frameId) {
        if (typeof cancelAnimationFrame === 'function') cancelAnimationFrame(this._frameId);
        this._frameId = null;
        this._animPending = false;
      }

      if (this._canvas && this._canvas.parentNode) {
        this._canvas.parentNode.removeChild(this._canvas);
      }

      if (map) {
        map.off('moveend', this._onMoveEnd);
        map.off('zoomend', this._onZoomEnd);
        map.off('resize', this._onResize);
        map.off('viewreset', this._onViewReset);
      }

      this._map = null;
      return this;
    }

    addTo(map) {
      if (map && typeof map.addLayer === 'function') {
        map.addLayer(this);
      } else {
        this.onAdd(map);
      }
      return this;
    }

    remove() {
      if (this._map) {
        if (typeof this._map.removeLayer === 'function') {
          this._map.removeLayer(this);
        } else {
          this.onRemove(this._map);
        }
      }
      return this;
    }

    removeFrom(map) {
      return this.remove();
    }

    // -------------------------------------------------------------------------
    // Canvas Initialization & Synchronization
    // -------------------------------------------------------------------------
    _initCanvas() {
      const doc = (typeof document !== 'undefined') ? document : null;
      if (!doc) return;

      this._canvas = doc.createElement('canvas');
      this._canvas.className = 'leaflet-rf-heatmap-layer';
      this._canvas.style.position = 'absolute';
      this._canvas.style.left = '0px';
      this._canvas.style.top = '0px';
      this._canvas.style.pointerEvents = 'none';
      this._canvas.style.zIndex = '300';
      this._canvas.style.display = this._visible ? 'block' : 'none';

      this._ctx = this._canvas.getContext('2d');

      this._offscreenCanvas = doc.createElement('canvas');
      this._offscreenCtx = this._offscreenCanvas.getContext('2d');
    }

    _reset() {
      if (!this._map || !this._canvas) return;

      const topLeft = this._map.containerPointToLayerPoint([0, 0]);
      if (global.L && global.L.DomUtil && typeof global.L.DomUtil.setPosition === 'function') {
        global.L.DomUtil.setPosition(this._canvas, topLeft);
      } else {
        this._canvas.style.transform = `translate3d(${topLeft.x}px, ${topLeft.y}px, 0px)`;
      }

      const size = this._map.getSize();
      if (this._canvas.width !== size.x || this._canvas.height !== size.y) {
        this._canvas.width = size.x;
        this._canvas.height = size.y;
        this._canvas.style.width = size.x + 'px';
        this._canvas.style.height = size.y + 'px';
      }
    }

    _onMoveEnd() {
      this._reset();
      this.redraw();
    }

    _onZoomEnd() {
      this._reset();
      this.redraw();
    }

    _onResize() {
      this._reset();
      this.redraw();
    }

    _onViewReset() {
      this._reset();
      this.redraw();
    }

    // -------------------------------------------------------------------------
    // Public API Methods
    // -------------------------------------------------------------------------

    /**
     * Replaces dataset with points: `[{ lat, lon, rsrp, sinr }, ...]`
     * Supports `latitude`, `longitude`, `lng`, numeric strings, and sub-metric fields.
     */
    setData(points) {
      this._data = Array.isArray(points) ? points : [];
      this.redraw();
      return this;
    }

    /**
     * Appends single point or array of points to dataset.
     */
    addData(points) {
      if (Array.isArray(points)) {
        this._data.push(...points);
      } else if (points && typeof points === 'object') {
        this._data.push(points);
      }
      this.redraw();
      return this;
    }

    /** Clears all telemetry data and canvas */
    clear() {
      this._data = [];
      if (this._ctx && this._canvas) {
        this._ctx.clearRect(0, 0, this._canvas.width, this._canvas.height);
      }
      return this;
    }

    /** Returns current raw data array */
    getData() {
      return this._data;
    }

    /**
     * Sets active interpolation metric ('RSRP' or 'SINR').
     */
    setMetric(metric) {
      const m = String(metric || 'RSRP').toUpperCase().trim();
      if (m === 'RSRP' || m === 'SINR') {
        this._metric = m;
      } else {
        console.warn(`[RfHeatmapLayer] Unsupported metric "${metric}". Defaulting to RSRP.`);
        this._metric = 'RSRP';
      }
      this.redraw();
      return this;
    }

    /** Returns current metric ('RSRP' or 'SINR') */
    getMetric() {
      return this._metric;
    }

    /** Toggles layer visibility */
    setVisible(visible) {
      this._visible = Boolean(visible);
      if (this._canvas) {
        this._canvas.style.display = this._visible ? 'block' : 'none';
      }
      if (this._visible) {
        this.redraw();
      }
      return this;
    }

    /** Returns current visibility boolean */
    isVisible() {
      return this._visible;
    }

    /** Updates configuration options */
    setOptions(newOptions = {}) {
      Object.assign(this.options, newOptions);
      if (newOptions.metric) {
        this.setMetric(newOptions.metric);
      } else {
        this.redraw();
      }
      return this;
    }

    /** Returns copy of current options */
    getOptions() {
      return Object.assign({}, this.options);
    }

    /**
     * Computes geographical bounds enclosing all valid sample points for active metric.
     */
    getBounds() {
      const isSinr = (this._metric === 'SINR');
      const pts = [];

      for (let i = 0; i < this._data.length; i++) {
        const p = this._data[i];
        if (!p) continue;
        const lat = p.lat ?? p.latitude;
        const lon = p.lon ?? p.lng ?? p.longitude;
        const val = isSinr ? (p.sinr ?? p.sim1Sinr ?? p.ssSinr ?? p.rssnr) : (p.rsrp ?? p.sim1Rsrp ?? p.ssRsrp);

        if (lat !== undefined && lon !== undefined && val !== undefined && val !== null) {
          const numLat = Number(lat);
          const numLon = Number(lon);
          const numVal = Number(val);
          if (isFinite(numLat) && isFinite(numLon) && isFinite(numVal)) {
            pts.push([numLat, numLon]);
          }
        }
      }

      if (pts.length === 0) return null;
      if (global.L && typeof global.L.latLngBounds === 'function') {
        return global.L.latLngBounds(pts);
      }

      let minLat = Infinity, maxLat = -Infinity, minLon = Infinity, maxLon = -Infinity;
      for (let i = 0; i < pts.length; i++) {
        const [lat, lon] = pts[i];
        if (lat < minLat) minLat = lat;
        if (lat > maxLat) maxLat = lat;
        if (lon < minLon) minLon = lon;
        if (lon > maxLon) maxLon = lon;
      }
      return { minLat, maxLat, minLon, maxLon };
    }

    /**
     * Returns legend structure, unit, and standard tier definitions.
     */
    getLegendInfo() {
      const isSinr = (this._metric === 'SINR');
      return {
        metric: this._metric,
        unit: isSinr ? 'dB' : 'dBm',
        minLimit: isSinr ? -10 : -135,
        maxLimit: isSinr ? 30 : -55,
        tiers: (RF_TIERS[this._metric] || RF_TIERS.RSRP).map(t => ({
          tier: t.tier,
          label: t.label,
          name: t.name,
          color: t.color,
          min: t.min,
          max: t.max
        }))
      };
    }

    /**
     * Resolves color hex or rgb string for a specific value under current metric.
     */
    getColorForValue(val, metric = this._metric, format = 'hex') {
      const rgb = resolveColor(Number(val), metric, this.options.colorMode);
      if (format === 'rgb') return `rgb(${rgb[0]}, ${rgb[1]}, ${rgb[2]})`;
      const toHex = (n) => n.toString(16).padStart(2, '0');
      return `#${toHex(rgb[0])}${toHex(rgb[1])}${toHex(rgb[2])}`;
    }

    /**
     * Requests canvas redraw (rAF debounced by default).
     * Pass immediate=true for synchronous execution.
     */
    redraw(immediate = false) {
      if (immediate) {
        this._render();
        return this;
      }

      if (!this._animPending) {
        this._animPending = true;
        const rAF = (typeof requestAnimationFrame === 'function')
          ? requestAnimationFrame
          : (cb) => setTimeout(cb, 16);

        this._frameId = rAF(() => {
          this._animPending = false;
          this._render();
        });
      }
      return this;
    }

    // -------------------------------------------------------------------------
    // Core IDW Interpolation Engine
    // -------------------------------------------------------------------------
    _render() {
      if (!this._map || !this._visible || !this._canvas || !this._ctx) return;

      const size = this._map.getSize();
      if (!size || size.x <= 0 || size.y <= 0) return;

      const width = size.x;
      const height = size.y;
      const radius = Math.max(10, Number(this.options.radius) || 65);
      const cellSize = Math.max(1, Number(this.options.cellSize) || 5);
      const power = Number(this.options.power) || 2;
      const opacity = Math.max(0, Math.min(1, Number(this.options.opacity) ?? 0.75));
      const metric = this._metric;
      const isSinr = (metric === 'SINR');
      const edgeFalloff = Boolean(this.options.edgeFalloff);
      const colorMode = this.options.colorMode || 'gradient';

      // Ensure canvas dimensions
      if (this._canvas.width !== width || this._canvas.height !== height) {
        this._canvas.width = width;
        this._canvas.height = height;
      }

      // Check for empty data
      if (!this._data || this._data.length === 0 || opacity <= 0) {
        this._ctx.clearRect(0, 0, width, height);
        return;
      }

      // Viewport bounds with padding for edge interpolation
      const mapBounds = (this._map.getBounds && typeof this._map.getBounds === 'function')
        ? this._map.getBounds().pad(0.15)
        : null;

      // Filter and project visible sample points to container space
      const projected = [];
      for (let i = 0; i < this._data.length; i++) {
        const p = this._data[i];
        if (!p) continue;

        let lat = p.lat ?? p.latitude;
        let lon = p.lon ?? p.lng ?? p.longitude;
        if (Array.isArray(p) && p.length >= 2) {
          lat = p[0];
          lon = p[1];
        }

        const rawVal = isSinr
          ? (p.sinr ?? p.sim1Sinr ?? p.ssSinr ?? p.rssnr)
          : (p.rsrp ?? p.sim1Rsrp ?? p.ssRsrp);

        if (lat === undefined || lon === undefined || rawVal === undefined || rawVal === null || rawVal === '') {
          continue;
        }

        const numLat = Number(lat);
        const numLon = Number(lon);
        const numVal = Number(rawVal);
        if (!isFinite(numLat) || !isFinite(numLon) || !isFinite(numVal)) {
          continue;
        }

        if (mapBounds && !mapBounds.contains([numLat, numLon])) {
          continue;
        }

        const pt = this._map.latLngToContainerPoint([numLat, numLon]);
        if (pt.x >= -radius && pt.x <= width + radius && pt.y >= -radius && pt.y <= height + radius) {
          projected.push({ x: pt.x, y: pt.y, val: numVal });
        }
      }

      if (projected.length === 0) {
        this._ctx.clearRect(0, 0, width, height);
        return;
      }

      // Spatial binning index (binSize = radius)
      const binSize = radius;
      const cols = Math.ceil(width / binSize) + 2;
      const rows = Math.ceil(height / binSize) + 2;
      const bins = new Array(cols * rows);

      for (let i = 0; i < projected.length; i++) {
        const p = projected[i];
        const bx = Math.floor(p.x / binSize) + 1;
        const by = Math.floor(p.y / binSize) + 1;
        if (bx >= 0 && bx < cols && by >= 0 && by < rows) {
          const idx = by * cols + bx;
          if (!bins[idx]) bins[idx] = [];
          bins[idx].push(p);
        }
      }

      // Prepare low-resolution offscreen grid
      const gridW = Math.ceil(width / cellSize);
      const gridH = Math.ceil(height / cellSize);

      if (!this._offscreenCanvas) {
        const doc = (typeof document !== 'undefined') ? document : null;
        if (doc) {
          this._offscreenCanvas = doc.createElement('canvas');
          this._offscreenCtx = this._offscreenCanvas.getContext('2d');
        }
      }

      if (this._offscreenCanvas.width !== gridW || this._offscreenCanvas.height !== gridH) {
        this._offscreenCanvas.width = gridW;
        this._offscreenCanvas.height = gridH;
      }

      const imgData = this._offscreenCtx.createImageData(gridW, gridH);
      const buf = imgData.data;

      const radius2 = radius * radius;
      const halfCell = cellSize / 2;
      const epsilon = 0.0001;

      // Evaluate IDW for each grid cell
      for (let gy = 0; gy < gridH; gy++) {
        const screenY = gy * cellSize + halfCell;
        const by = Math.floor(screenY / binSize) + 1;

        for (let gx = 0; gx < gridW; gx++) {
          const screenX = gx * cellSize + halfCell;
          const bx = Math.floor(screenX / binSize) + 1;

          let sumW = 0;
          let sumWV = 0;
          let minDist2 = Infinity;
          let exactVal = null;

          // Query 3x3 adjacent spatial bins
          for (let dyb = -1; dyb <= 1; dyb++) {
            const row = by + dyb;
            if (row < 0 || row >= rows) continue;
            const rowOffset = row * cols;

            for (let dxb = -1; dxb <= 1; dxb++) {
              const col = bx + dxb;
              if (col < 0 || col >= cols) continue;

              const bin = bins[rowOffset + col];
              if (!bin) continue;

              for (let k = 0; k < bin.length; k++) {
                const p = bin[k];
                const dx = screenX - p.x;
                const dy = screenY - p.y;
                const d2 = dx * dx + dy * dy;

                if (d2 <= radius2) {
                  if (d2 < 0.25) {
                    exactVal = p.val;
                    minDist2 = 0;
                    break;
                  }
                  if (d2 < minDist2) minDist2 = d2;

                  const w = (power === 2)
                    ? (1.0 / (d2 + epsilon))
                    : (1.0 / Math.pow(Math.sqrt(d2) + epsilon, power));

                  sumW += w;
                  sumWV += w * p.val;
                }
              }
              if (exactVal !== null) break;
            }
            if (exactVal !== null) break;
          }

          let cellVal = null;
          let cellAlphaFactor = 1.0;

          if (exactVal !== null) {
            cellVal = exactVal;
          } else if (sumW > 0) {
            cellVal = sumWV / sumW;
            if (edgeFalloff) {
              const normDist = Math.min(1.0, Math.sqrt(minDist2) / radius);
              // Cosine bell curve for seamless falloff
              cellAlphaFactor = 0.5 * (1.0 + Math.cos(normDist * Math.PI));
            }
          }

          if (cellVal !== null) {
            const alpha = Math.round(opacity * cellAlphaFactor * 255);
            if (alpha > 0) {
              const rgb = resolveColor(cellVal, metric, colorMode);
              const pixelIdx = (gy * gridW + gx) * 4;
              buf[pixelIdx]     = rgb[0];
              buf[pixelIdx + 1] = rgb[1];
              buf[pixelIdx + 2] = rgb[2];
              buf[pixelIdx + 3] = alpha;
            }
          }
        }
      }

      // Draw offscreen low-res buffer scaled up onto main display canvas
      this._offscreenCtx.putImageData(imgData, 0, 0);

      this._ctx.clearRect(0, 0, width, height);
      this._ctx.imageSmoothingEnabled = (this.options.smooth !== false);
      this._ctx.drawImage(this._offscreenCanvas, 0, 0, width, height);
    }
  }

  // ---------------------------------------------------------------------------
  // Factory Function & Module Exports
  // ---------------------------------------------------------------------------
  function rfHeatmapLayer(options) {
    return new RfHeatmapLayer(options);
  }

  // Leaflet namespace registration
  if (typeof global.L !== 'undefined' && global.L) {
    global.L.RfHeatmapLayer = RfHeatmapLayer;
    global.L.rfHeatmapLayer = rfHeatmapLayer;
  }

  // Global window registration
  global.RfHeatmapLayer = RfHeatmapLayer;
  global.rfHeatmapLayer = rfHeatmapLayer;

  // Node / CommonJS export
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
      RfHeatmapLayer,
      rfHeatmapLayer,
      RF_TIERS,
      resolveColor
    };
  }

})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : this));
