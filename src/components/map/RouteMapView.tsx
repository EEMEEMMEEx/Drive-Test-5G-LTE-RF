import { useEffect, useMemo, useRef, useState } from 'react';
import L from 'leaflet';
import {
  Activity,
  AlertTriangle,
  BarChart3,
  CheckCircle2,
  Compass,
  Crosshair,
  Eye,
  Layers,
  MapPin,
  Moon,
  Navigation,
  Palette,
  Radio,
  RefreshCw,
  SlidersHorizontal,
  Sun,
  X,
} from 'lucide-react';
import type {
  Campaign,
  CellSite,
  GpsInfo,
  HandoverEvent,
  MapBaseType,
  MapColorMode,
  MapOptions,
  Measurement,
  MissedHandoverEvent,
  SignalPalette,
} from '../../types/drivetest';
import {
  calculateSignalStats,
  detectMobilityEvents,
  extractCellSites,
  getSignalColor,
  getTechnologyColor,
} from '../../services/rfAnalyticsEngine';
import type { TranslationDictionary } from '../../i18n/translations';
import { RfToggleSwitch } from '../common/RfToggleSwitch';

export interface RouteMapViewProps {
  campaigns: Campaign[];
  selectedCampaignId: number;
  onSelectCampaign: (id: number) => void;
  measurements: Measurement[];
  isRunning: boolean;
  currentLocation: GpsInfo | null;
  options: MapOptions;
  onUpdateOptions: (next: MapOptions) => void;
  onNavigateToAnalytics: () => void;
  t: TranslationDictionary;
}

const TILE_URLS: Record<MapBaseType, { url: string; attribution: string }> = {
  NORMAL: {
    url: 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
    attribution: '&copy; OpenStreetMap contributors',
  },
  SATELLITE: {
    url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
    attribution: '&copy; Esri World Imagery',
  },
  TERRAIN: {
    url: 'https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png',
    attribution: '&copy; OpenTopoMap',
  },
  HYBRID: {
    url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
    attribution: '&copy; Esri Hybrid',
  },
};

function getPointColor(m: Measurement, options: MapOptions): string {
  if (options.colorMode === 'BY_TECHNOLOGY') {
    return getTechnologyColor(m.radio.technology);
  }
  let val = m.radio.rsrp;
  if (options.colorMode === 'BY_RSRQ') val = m.radio.rsrq;
  else if (options.colorMode === 'BY_SINR') val = m.radio.sinr;
  else if (options.colorMode === 'BY_SPEED') val = m.gps.speed;

  return getSignalColor(
    val,
    options.colorMode,
    options.signalPalette,
    options.rsrpGood,
    options.rsrpPoor
  );
}

export function RouteMapView({
  campaigns,
  selectedCampaignId,
  onSelectCampaign,
  measurements,
  isRunning,
  currentLocation,
  options,
  onUpdateOptions,
  onNavigateToAnalytics,
  t,
}: RouteMapViewProps) {
  const mapContainerRef = useRef<HTMLDivElement | null>(null);
  const mapInstanceRef = useRef<L.Map | null>(null);
  const layerGroupRef = useRef<L.LayerGroup | null>(null);
  const tileLayerRef = useRef<L.TileLayer | null>(null);

  const [drawerOpen, setDrawerOpen] = useState<boolean>(false);
  const [selectedPointId, setSelectedPointId] = useState<number | null>(
    measurements.length > 0 ? measurements[measurements.length - 1].id : null
  );

  const sortedMeasurements = useMemo(
    () => [...measurements].sort((a, b) => a.timestampMs - b.timestampMs),
    [measurements]
  );

  const signalStats = useMemo(
    () => calculateSignalStats(sortedMeasurements, options.rsrpPoor),
    [sortedMeasurements, options.rsrpPoor]
  );

  const mobility = useMemo(
    () => detectMobilityEvents(sortedMeasurements),
    [sortedMeasurements]
  );

  const cellSites = useMemo(
    () => extractCellSites(sortedMeasurements),
    [sortedMeasurements]
  );

  const cellSiteById = useMemo(() => {
    const map = new Map<number, CellSite>();
    for (const site of cellSites) {
      map.set(site.cellId, site);
    }
    return map;
  }, [cellSites]);

  const selectedMeasurement = useMemo(() => {
    if (sortedMeasurements.length === 0) return null;
    if (selectedPointId !== null) {
      const found = sortedMeasurements.find((m) => m.id === selectedPointId);
      if (found) return found;
    }
    return sortedMeasurements[sortedMeasurements.length - 1];
  }, [sortedMeasurements, selectedPointId]);

  const selectedHandover = useMemo<HandoverEvent | undefined>(() => {
    if (!selectedMeasurement) return undefined;
    return mobility.handoverEvents.find(
      (h) => h.measurement.id === selectedMeasurement.id
    );
  }, [mobility.handoverEvents, selectedMeasurement]);

  const selectedMissedHo = useMemo<MissedHandoverEvent | undefined>(() => {
    if (!selectedMeasurement) return undefined;
    return mobility.missedHandoverEvents.find(
      (mh) => mh.measurement.id === selectedMeasurement.id
    );
  }, [mobility.missedHandoverEvents, selectedMeasurement]);

  // Initialize Leaflet map safely (guarded for jsdom environments)
  useEffect(() => {
    const container = mapContainerRef.current;
    if (!container || mapInstanceRef.current) return;

    try {
      const initialCenter: [number, number] =
        sortedMeasurements.length > 0
          ? [
              sortedMeasurements[sortedMeasurements.length - 1].gps.latitude,
              sortedMeasurements[sortedMeasurements.length - 1].gps.longitude,
            ]
          : [13.7563, 100.5018];

      const map = L.map(container, {
        center: initialCenter,
        zoom: 15,
        zoomControl: true,
        preferCanvas: false,
      });

      const tileCfg = TILE_URLS[options.mapType] ?? TILE_URLS.NORMAL;
      const tileLayer = L.tileLayer(tileCfg.url, {
        attribution: tileCfg.attribution,
        maxZoom: 19,
      }).addTo(map);

      const group = L.layerGroup().addTo(map);

      mapInstanceRef.current = map;
      tileLayerRef.current = tileLayer;
      layerGroupRef.current = group;
    } catch {
      // Fallback gracefully when running in headless jsdom or offline
    }

    return () => {
      try {
        if (mapInstanceRef.current) {
          mapInstanceRef.current.remove();
          mapInstanceRef.current = null;
          layerGroupRef.current = null;
          tileLayerRef.current = null;
        }
      } catch {
        // Ignore cleanup errors in headless environment
      }
    };
  }, []);

  // Update base tile layer when mapType changes
  useEffect(() => {
    const map = mapInstanceRef.current;
    if (!map) return;
    try {
      if (tileLayerRef.current) {
        map.removeLayer(tileLayerRef.current);
      }
      const tileCfg = TILE_URLS[options.mapType] ?? TILE_URLS.NORMAL;
      tileLayerRef.current = L.tileLayer(tileCfg.url, {
        attribution: tileCfg.attribution,
        maxZoom: 19,
      }).addTo(map);
    } catch {
      // Ignore tile update error in jsdom
    }
  }, [options.mapType]);

  // Render Leaflet markers, serving cell lines, handovers, and missed handovers
  useEffect(() => {
    const map = mapInstanceRef.current;
    const group = layerGroupRef.current;
    if (!map || !group) return;

    try {
      group.clearLayers();

      if (sortedMeasurements.length === 0) return;

      // Draw route polyline
      const latLngs: [number, number][] = sortedMeasurements.map((m) => [
        m.gps.latitude,
        m.gps.longitude,
      ]);
      L.polyline(latLngs, {
        color: '#38bdf8',
        weight: 2.5,
        opacity: 0.55,
      }).addTo(group);

      // Serving cell site markers and sector lines
      if (options.showCurrentCell) {
        for (const site of cellSites) {
          const siteMarker = L.circleMarker([site.latitude, site.longitude], {
            radius: 7,
            color: '#f8fafc',
            weight: 2,
            fillColor: getTechnologyColor(site.technology),
            fillOpacity: 0.9,
          });
          siteMarker.bindTooltip(
            `Site PCI ${site.pci} · CID ${site.cellId} (${site.technology})`,
            { direction: 'top' }
          );
          siteMarker.addTo(group);
        }

        if (selectedMeasurement) {
          const servingSite = cellSiteById.get(selectedMeasurement.radio.cellId);
          if (servingSite) {
            L.polyline(
              [
                [selectedMeasurement.gps.latitude, selectedMeasurement.gps.longitude],
                [servingSite.latitude, servingSite.longitude],
              ],
              {
                color: '#22d3ee',
                weight: 2,
                dashArray: '5, 5',
                opacity: 0.85,
              }
            ).addTo(group);
          }
        }
      }

      // Draw measurement points
      for (const m of sortedMeasurements) {
        const ptColor = getPointColor(m, options);
        const isSelected = selectedMeasurement?.id === m.id;
        const marker = L.circleMarker([m.gps.latitude, m.gps.longitude], {
          radius: isSelected ? options.dotSize + 3 : options.dotSize,
          color: isSelected ? '#ffffff' : '#0f172a',
          weight: isSelected ? 2.5 : 1,
          fillColor: ptColor,
          fillOpacity: 0.92,
        });

        marker.on('click', () => {
          setSelectedPointId(m.id);
        });
        marker.addTo(group);
      }

      // Draw Handover markers
      if (options.showHandovers) {
        for (const ho of mobility.handoverEvents) {
          const hoMarker = L.circleMarker(
            [ho.measurement.gps.latitude, ho.measurement.gps.longitude],
            {
              radius: options.dotSize + 5,
              color: ho.isPingPong ? '#ef4444' : '#f59e0b',
              weight: 2.5,
              fillOpacity: 0.15,
            }
          );
          hoMarker.on('click', () => setSelectedPointId(ho.measurement.id));
          hoMarker.addTo(group);
        }
      }

      // Draw Missed Handover markers
      if (options.showMissedHandovers) {
        for (const mho of mobility.missedHandoverEvents) {
          const mhoMarker = L.circleMarker(
            [mho.measurement.gps.latitude, mho.measurement.gps.longitude],
            {
              radius: options.dotSize + 6,
              color: '#ec4899',
              weight: 2,
              dashArray: '3, 3',
              fillOpacity: 0.1,
            }
          );
          mhoMarker.on('click', () => setSelectedPointId(mho.measurement.id));
          mhoMarker.addTo(group);
        }
      }

      // Follow position or center on latest point
      if (options.followMyPosition) {
        const target = currentLocation
          ? ([currentLocation.latitude, currentLocation.longitude] as [number, number])
          : ([
              sortedMeasurements[sortedMeasurements.length - 1].gps.latitude,
              sortedMeasurements[sortedMeasurements.length - 1].gps.longitude,
            ] as [number, number]);
        map.panTo(target);
      }
    } catch {
      // Safe fallback in headless tests
    }
  }, [
    sortedMeasurements,
    options,
    cellSites,
    cellSiteById,
    selectedMeasurement,
    mobility,
    currentLocation,
  ]);

  // Compute SVG bounding box for Vector Route Overlay (interactive in both browser & offline/test)
  const vectorBounds = useMemo(() => {
    if (sortedMeasurements.length === 0) {
      return { minLat: 13.75, maxLat: 13.76, minLon: 100.5, maxLon: 100.51 };
    }
    let minLat = sortedMeasurements[0].gps.latitude;
    let maxLat = sortedMeasurements[0].gps.latitude;
    let minLon = sortedMeasurements[0].gps.longitude;
    let maxLon = sortedMeasurements[0].gps.longitude;
    for (const m of sortedMeasurements) {
      if (m.gps.latitude < minLat) minLat = m.gps.latitude;
      if (m.gps.latitude > maxLat) maxLat = m.gps.latitude;
      if (m.gps.longitude < minLon) minLon = m.gps.longitude;
      if (m.gps.longitude > maxLon) maxLon = m.gps.longitude;
    }
    const latPad = Math.max(0.0008, (maxLat - minLat) * 0.15);
    const lonPad = Math.max(0.0008, (maxLon - minLon) * 0.15);
    return {
      minLat: minLat - latPad,
      maxLat: maxLat + latPad,
      minLon: minLon - lonPad,
      maxLon: maxLon + lonPad,
    };
  }, [sortedMeasurements]);

  const projectToSvg = (lat: number, lon: number): { x: number; y: number } => {
    const width = 640;
    const height = 280;
    const xRatio =
      (lon - vectorBounds.minLon) /
      Math.max(0.00001, vectorBounds.maxLon - vectorBounds.minLon);
    const yRatio =
      1 -
      (lat - vectorBounds.minLat) /
        Math.max(0.00001, vectorBounds.maxLat - vectorBounds.minLat);
    return {
      x: Number((xRatio * width).toFixed(1)),
      y: Number((yRatio * height).toFixed(1)),
    };
  };

  const updateOption = <K extends keyof MapOptions>(key: K, value: MapOptions[K]) => {
    onUpdateOptions({
      ...options,
      [key]: value,
    });
  };

  const latestBearing =
    currentLocation?.bearing ??
    (selectedMeasurement ? selectedMeasurement.gps.bearing : 0);

  return (
    <div className="space-y-4">
      {/* Top Toolbar */}
      <div className="bg-surface border border-border rounded-xl p-3 flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2.5">
          <div className="flex items-center gap-2 bg-surface-elevated border border-border rounded-lg px-3 py-1.5">
            <MapPin className="w-4 h-4 text-accent" />
            <label htmlFor="map-campaign-select" className="sr-only">
              {t.campaignName}
            </label>
            <select
              id="map-campaign-select"
              aria-label={t.campaignName}
              value={selectedCampaignId}
              onChange={(e) => onSelectCampaign(Number(e.target.value))}
              className="bg-transparent text-sm font-medium text-text focus:outline-none"
            >
              {campaigns.length === 0 && (
                <option value={0} className="bg-surface text-text-muted">
                  No Campaigns Available
                </option>
              )}
              {campaigns.map((c) => (
                <option key={c.id} value={c.id} className="bg-surface text-text">
                  {c.name} ({c.measurementCount} pts)
                </option>
              ))}
            </select>
          </div>

          {/* Live Status Badge */}
          <div
            className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold border ${
              isRunning
                ? 'bg-emerald-500/15 text-emerald-300 border-emerald-500/40'
                : 'bg-surface-elevated text-text-muted border-border'
            }`}
          >
            <Radio className={`w-3.5 h-3.5 ${isRunning ? 'animate-pulse text-emerald-400' : 'text-text-muted'}`} />
            <span>{isRunning ? 'LIVE TRACKING' : 'REPLAY MODE'}</span>
          </div>

          {/* Follow My Position */}
          <button
            type="button"
            onClick={() => updateOption('followMyPosition', !options.followMyPosition)}
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium border transition ${
              options.followMyPosition
                ? 'bg-accent/20 text-accent border-accent/40 shadow-[0_0_8px_rgba(56,189,248,0.25)]'
                : 'bg-surface-elevated text-text-muted border-border hover:bg-surface-elevated'
            }`}
            title="Follow My Position"
          >
            <Crosshair className="w-3.5 h-3.5" />
            <span>Follow GPS</span>
          </button>

          {/* Orientation Toggle: Heading Up vs North Up */}
          <button
            type="button"
            onClick={() => updateOption('headingUp', !options.headingUp)}
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium border transition ${
              options.headingUp
                ? 'bg-accent/20 text-accent border-accent/40 shadow-[0_0_8px_rgba(56,189,248,0.25)]'
                : 'bg-surface-elevated text-text-muted border-border hover:bg-surface-elevated'
            }`}
            title="Toggle Orientation (Heading Up / North Up)"
          >
            <Compass className="w-3.5 h-3.5" />
            <span>{options.headingUp ? `Heading Up (${Math.round(latestBearing)}°)` : 'North Up'}</span>
          </button>
        </div>

        {/* Right Controls: Map Type, Dark/Light, Options Drawer, Analytics */}
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex items-center bg-surface-elevated border border-border rounded-lg p-0.5">
            {(['NORMAL', 'SATELLITE', 'TERRAIN', 'HYBRID'] as MapBaseType[]).map((type) => (
              <button
                key={type}
                type="button"
                onClick={() => updateOption('mapType', type)}
                className={`px-2.5 py-1 rounded-md text-xs font-medium transition ${
                  options.mapType === type
                    ? 'bg-accent text-on-accent font-semibold'
                    : 'text-text-muted hover:text-text'
                }`}
              >
                {type}
              </button>
            ))}
          </div>

          <button
            type="button"
            onClick={() => updateOption('darkMap', !options.darkMap)}
            className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium bg-surface-elevated text-text border border-border hover:bg-surface-elevated"
            aria-label="Toggle Dark Map"
          >
            {options.darkMap ? (
              <>
                <Moon className="w-3.5 h-3.5 text-accent" />
                <span>Dark</span>
              </>
            ) : (
              <>
                <Sun className="w-3.5 h-3.5 text-amber-400" />
                <span>Light</span>
              </>
            )}
          </button>

          <button
            type="button"
            onClick={() => setDrawerOpen((prev) => !prev)}
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold border transition ${
              drawerOpen
                ? 'bg-accent text-on-accent border-accent'
                : 'bg-surface-elevated text-text border-border hover:bg-surface-elevated'
            }`}
          >
            <SlidersHorizontal className="w-3.5 h-3.5" />
            <span>{t.mapOptions}</span>
          </button>

          <button
            type="button"
            onClick={onNavigateToAnalytics}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-indigo-600/90 hover:bg-indigo-500 text-white transition"
          >
            <BarChart3 className="w-3.5 h-3.5" />
            <span>{t.navAnalytics}</span>
          </button>
        </div>
      </div>

      {/* Collapsible Map Layer Controls Drawer */}
      {drawerOpen && (
        <div
          data-testid="map-options-drawer"
          className="bg-surface border border-border rounded-xl p-4 space-y-4 shadow-xl"
        >
          <div className="flex items-center justify-between border-b border-border pb-2.5">
            <div className="flex items-center gap-2">
              <Layers className="w-4 h-4 text-accent" />
              <h3 className="text-sm font-semibold text-text">{t.mapOptions}</h3>
            </div>
            <button
              type="button"
              onClick={() => setDrawerOpen(false)}
              className="text-text-muted hover:text-text p-1"
              aria-label="Close Map Options"
            >
              <X className="w-4 h-4" />
            </button>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {/* Column 1: Color Metric & Palette */}
            <div className="space-y-3">
              <div>
                <label className="block text-xs font-medium text-text-muted mb-1.5">
                  {t.markerColorMode}
                </label>
                <div className="grid grid-cols-2 gap-1.5">
                  {(
                    [
                      { id: 'BY_RSRP', label: 'RSRP (dBm)' },
                      { id: 'BY_RSRQ', label: 'RSRQ (dB)' },
                      { id: 'BY_SINR', label: 'SINR (dB)' },
                      { id: 'BY_TECHNOLOGY', label: 'Technology' },
                      { id: 'BY_SPEED', label: 'Speed (km/h)' },
                    ] as { id: MapColorMode; label: string }[]
                  ).map((item) => (
                    <button
                      key={item.id}
                      type="button"
                      onClick={() => updateOption('colorMode', item.id)}
                      className={`px-2.5 py-1.5 rounded-lg text-xs font-medium border text-left transition ${
                        options.colorMode === item.id
                          ? 'bg-cyan-500/20 border-accent text-accent'
                          : 'bg-surface-elevated border-border text-text-muted hover:border-border'
                      }`}
                    >
                      {item.label}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <label className="block text-xs font-medium text-text-muted mb-1.5">
                  <span className="inline-flex items-center gap-1">
                    <Palette className="w-3.5 h-3.5 text-accent" />
                    {t.signalPalette}
                  </span>
                </label>
                <div className="grid grid-cols-2 gap-1.5">
                  {(['CLASSIC', 'THERMIQUE', 'VIRIDIS', 'OCEAN'] as SignalPalette[]).map(
                    (pal) => (
                      <button
                        key={pal}
                        type="button"
                        onClick={() => updateOption('signalPalette', pal)}
                        className={`px-2.5 py-1.5 rounded-lg text-xs font-medium border transition ${
                          options.signalPalette === pal
                            ? 'bg-indigo-500/25 border-indigo-400 text-indigo-200'
                            : 'bg-surface-elevated border-border text-text-muted hover:text-text'
                        }`}
                      >
                        {pal}
                      </button>
                    )
                  )}
                </div>
              </div>
            </div>

            {/* Column 2: Sliders (Point Size & Custom RSRP Thresholds) */}
            <div className="space-y-3 bg-surface-elevated border border-border rounded-lg p-3">
              <div>
                <div className="flex justify-between text-xs mb-1">
                  <span className="text-text-muted">{t.pointSize}</span>
                  <span className="font-mono text-accent">{options.dotSize} px</span>
                </div>
                <input
                  type="range"
                  aria-label={t.pointSize}
                  min={4}
                  max={14}
                  step={1}
                  value={options.dotSize}
                  onChange={(e) => updateOption('dotSize', Number(e.target.value))}
                  className="w-full accent-cyan-400"
                />
              </div>

              <div>
                <div className="flex justify-between text-xs mb-1">
                  <span className="text-text-muted">RSRP Good Threshold</span>
                  <span className="font-mono text-emerald-400">{options.rsrpGood} dBm</span>
                </div>
                <input
                  type="range"
                  aria-label="RSRP Good Threshold"
                  min={-105}
                  max={-70}
                  step={1}
                  value={options.rsrpGood}
                  onChange={(e) => updateOption('rsrpGood', Number(e.target.value))}
                  className="w-full accent-emerald-400"
                />
              </div>

              <div>
                <div className="flex justify-between text-xs mb-1">
                  <span className="text-text-muted">RSRP Poor Threshold</span>
                  <span className="font-mono text-rose-400">{options.rsrpPoor} dBm</span>
                </div>
                <input
                  type="range"
                  aria-label="RSRP Poor Threshold"
                  min={-125}
                  max={-95}
                  step={1}
                  value={options.rsrpPoor}
                  onChange={(e) => updateOption('rsrpPoor', Number(e.target.value))}
                  className="w-full accent-rose-400"
                />
              </div>
            </div>

            {/* Column 3: Map Layer Switches */}
            <div className="space-y-2 bg-surface-elevated border border-border rounded-lg p-3">
              <div className="text-xs font-semibold text-text-muted uppercase tracking-wider mb-1">
                Active RF Overlays
              </div>

              {(
                [
                  {
                    key: 'showCurrentCell',
                    label: t.showCurrentCell,
                    desc: `${cellSites.length} estimated sites`,
                  },
                  {
                    key: 'showNeighborCells',
                    label: t.showNeighborCells,
                    desc: 'Candidate PCI vectors',
                  },
                  {
                    key: 'showHandovers',
                    label: t.showHandovers,
                    desc: `${mobility.handovers} events (${mobility.pingPong} ping-pong)`,
                  },
                  {
                    key: 'showMissedHandovers',
                    label: t.showMissedHandovers,
                    desc: `${mobility.missedHandovers} stronger neighbor margins`,
                  },
                ] as {
                  key:
                    | 'showCurrentCell'
                    | 'showNeighborCells'
                    | 'showHandovers'
                    | 'showMissedHandovers';
                  label: string;
                  desc: string;
                }[]
              ).map((layer) => (
                <div
                  key={layer.key}
                  className="py-1.5 px-2 rounded-lg hover:bg-surface-elevated transition"
                >
                  <RfToggleSwitch
                    id={`map-layer-${layer.key}`}
                    checked={options[layer.key]}
                    onChange={(val) => updateOption(layer.key, val)}
                    label={layer.label}
                    description={layer.desc}
                    size="sm"
                    variant="accent"
                    className="w-full"
                  />
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* Main Map Area + Selected Point Inspector */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {/* Left 2 Columns: Leaflet Canvas + Interactive Vector Route Overlay */}
        <div className="lg:col-span-2 space-y-3">
          {/* Leaflet Container */}
          <div className="relative rounded-xl overflow-hidden border border-border bg-surface-elevated h-[340px]">
            <div
              ref={mapContainerRef}
              className={`w-full h-full transition-transform duration-300 ${
                options.darkMap ? 'brightness-90 contrast-125 hue-rotate-180 invert' : ''
              }`}
              style={{
                transform: options.headingUp ? `rotate(${-latestBearing}deg)` : 'none',
              }}
            />

            {/* Floating Route Summary Ribbon */}
            <div className="absolute top-3 left-3 z-[400] flex flex-wrap items-center gap-2 bg-surface-elevated backdrop-blur-md border border-border rounded-lg px-3 py-1.5 text-xs">
              <span className="text-text-muted">Samples:</span>
              <span className="font-mono font-semibold text-text">
                {sortedMeasurements.length}
              </span>
              <span className="text-border">|</span>
              <span className="text-text-muted">Avg RSRP:</span>
              <span className="font-mono font-semibold text-accent">
                {signalStats.avgRsrp} dBm
              </span>
              <span className="text-border">|</span>
              <span className="text-text-muted">Handovers:</span>
              <span className="font-mono font-semibold text-amber-300">
                {mobility.handovers}
              </span>
              {mobility.missedHandovers > 0 && (
                <>
                  <span className="text-border">|</span>
                  <span className="text-rose-400 font-medium">
                    Missed HO: {mobility.missedHandovers}
                  </span>
                </>
              )}
            </div>
          </div>

          {/* Interactive Vector Route Trace & Cell Overlay (Works Offline & in Tests) */}
          <div className="bg-surface border border-border rounded-xl p-3">
            <div className="flex flex-wrap items-center justify-between gap-2 mb-2">
              <div className="flex items-center gap-2">
                <Navigation className="w-4 h-4 text-accent" />
                <span className="text-xs font-semibold uppercase tracking-wider text-text-muted">
                  Interactive Vector RF Trace ({options.colorMode.replace('BY_', '')})
                </span>
              </div>
              <div className="flex flex-wrap items-center gap-3 text-[11px] text-text-muted">
                <span className="inline-flex items-center gap-1">
                  <span className="w-2.5 h-2.5 rounded-full bg-emerald-400 inline-block" />
                  Good (&ge; {options.rsrpGood} dBm)
                </span>
                <span className="inline-flex items-center gap-1">
                  <span className="w-2.5 h-2.5 rounded-full bg-amber-400 inline-block" />
                  Fair
                </span>
                <span className="inline-flex items-center gap-1">
                  <span className="w-2.5 h-2.5 rounded-full bg-rose-500 inline-block" />
                  Poor (&lt; {options.rsrpPoor} dBm)
                </span>
              </div>
            </div>

            <svg
              viewBox="0 0 640 280"
              className="w-full h-56 bg-surface-elevated rounded-lg border border-border"
              role="img"
              aria-label="Interactive RF Route Trace Map"
            >
              {/* Grid lines */}
              {[70, 140, 210].map((y) => (
                <line
                  key={`gy-${y}`}
                  x1={0}
                  y1={y}
                  x2={640}
                  y2={y}
                  stroke="var(--color-border)"
                  strokeDasharray="3 3"
                />
              ))}
              {[160, 320, 480].map((x) => (
                <line
                  key={`gx-${x}`}
                  x1={x}
                  y1={0}
                  x2={x}
                  y2={280}
                  stroke="var(--color-border)"
                  strokeDasharray="3 3"
                />
              ))}

              {/* Route polyline */}
              {sortedMeasurements.length > 1 && (
                <polyline
                  fill="none"
                  stroke="var(--color-border-input)"
                  strokeWidth={2.5}
                  points={sortedMeasurements
                    .map((m) => {
                      const pt = projectToSvg(m.gps.latitude, m.gps.longitude);
                      return `${pt.x},${pt.y}`;
                    })
                    .join(' ')}
                />
              )}

              {/* Serving Cell Vector Lines */}
              {options.showCurrentCell &&
                selectedMeasurement &&
                (() => {
                  const site = cellSiteById.get(selectedMeasurement.radio.cellId);
                  if (!site) return null;
                  const from = projectToSvg(
                    selectedMeasurement.gps.latitude,
                    selectedMeasurement.gps.longitude
                  );
                  const to = projectToSvg(site.latitude, site.longitude);
                  return (
                    <g>
                      <line
                        x1={from.x}
                        y1={from.y}
                        x2={to.x}
                        y2={to.y}
                        stroke="#22d3ee"
                        strokeWidth={1.75}
                        strokeDasharray="4 4"
                      />
                      <rect
                        x={to.x - 6}
                        y={to.y - 6}
                        width={12}
                        height={12}
                        rx={3}
                        fill={getTechnologyColor(site.technology)}
                        stroke="#f8fafc"
                        strokeWidth={1.5}
                      />
                    </g>
                  );
                })()}

              {/* Handover rings */}
              {options.showHandovers &&
                mobility.handoverEvents.map((ho) => {
                  const pt = projectToSvg(
                    ho.measurement.gps.latitude,
                    ho.measurement.gps.longitude
                  );
                  return (
                    <circle
                      key={ho.id}
                      cx={pt.x}
                      cy={pt.y}
                      r={options.dotSize + 5}
                      fill="none"
                      stroke={ho.isPingPong ? '#ef4444' : '#f59e0b'}
                      strokeWidth={2}
                    />
                  );
                })}

              {/* Missed Handover rings */}
              {options.showMissedHandovers &&
                mobility.missedHandoverEvents.map((mho) => {
                  const pt = projectToSvg(
                    mho.measurement.gps.latitude,
                    mho.measurement.gps.longitude
                  );
                  return (
                    <circle
                      key={mho.id}
                      cx={pt.x}
                      cy={pt.y}
                      r={options.dotSize + 7}
                      fill="none"
                      stroke="#ec4899"
                      strokeWidth={1.75}
                      strokeDasharray="3 2"
                    />
                  );
                })}

              {/* Measurement Dots */}
              {sortedMeasurements.map((m) => {
                const pt = projectToSvg(m.gps.latitude, m.gps.longitude);
                const color = getPointColor(m, options);
                const isSelected = selectedMeasurement?.id === m.id;
                return (
                  <circle
                    key={m.id}
                    data-testid={`map-point-${m.id}`}
                    cx={pt.x}
                    cy={pt.y}
                    r={isSelected ? options.dotSize + 2.5 : options.dotSize}
                    fill={color}
                    stroke={isSelected ? '#ffffff' : '#0f172a'}
                    strokeWidth={isSelected ? 2.5 : 1}
                    className="cursor-pointer transition-all"
                    onClick={() => setSelectedPointId(m.id)}
                  />
                );
              })}
            </svg>
          </div>
        </div>

        {/* Right Column: Selected Point Inspector Card */}
        <div className="bg-surface border border-border rounded-xl p-4 flex flex-col justify-between space-y-4">
          {selectedMeasurement ? (
            <div className="space-y-4">
              <div className="flex items-center justify-between border-b border-border pb-3">
                <div>
                  <span className="text-[11px] font-semibold uppercase tracking-wider text-accent">
                    Point Telemetry Inspector
                  </span>
                  <h3 className="text-base font-bold text-text flex items-center gap-2 mt-0.5">
                    <span>
                      PCI {selectedMeasurement.radio.pci} · Cell {selectedMeasurement.radio.cellId}
                    </span>
                  </h3>
                </div>
                <span
                  className="px-2.5 py-1 rounded-full text-xs font-bold text-slate-950"
                  style={{
                    backgroundColor: getTechnologyColor(
                      selectedMeasurement.radio.technology
                    ),
                  }}
                >
                  {selectedMeasurement.radio.technology.replace('_', ' ')} (
                  {selectedMeasurement.radio.nrStatus})
                </span>
              </div>

              {/* RF Key Metrics Grid */}
              <div className="grid grid-cols-2 gap-2.5">
                <div className="bg-surface-elevated border border-border rounded-lg p-2.5">
                  <div className="text-[11px] text-text-muted">RSRP</div>
                  <div
                    className="text-lg font-mono font-bold"
                    style={{
                      color: getSignalColor(
                        selectedMeasurement.radio.rsrp,
                        'BY_RSRP',
                        options.signalPalette,
                        options.rsrpGood,
                        options.rsrpPoor
                      ),
                    }}
                  >
                    {selectedMeasurement.radio.rsrp} dBm
                  </div>
                </div>

                <div className="bg-surface-elevated border border-border rounded-lg p-2.5">
                  <div className="text-[11px] text-text-muted">RSRQ</div>
                  <div className="text-lg font-mono font-bold text-text">
                    {selectedMeasurement.radio.rsrq} dB
                  </div>
                </div>

                <div className="bg-surface-elevated border border-border rounded-lg p-2.5">
                  <div className="text-[11px] text-text-muted">SINR</div>
                  <div className="text-lg font-mono font-bold text-accent">
                    {selectedMeasurement.radio.sinr.toFixed(1)} dB
                  </div>
                </div>

                <div className="bg-surface-elevated border border-border rounded-lg p-2.5">
                  <div className="text-[11px] text-text-muted">RSSI</div>
                  <div className="text-lg font-mono font-bold text-text">
                    {selectedMeasurement.radio.rssi} dBm
                  </div>
                </div>
              </div>

              {/* Serving Cell & GPS Details */}
              <div className="bg-surface-elevated border border-border rounded-lg p-3 space-y-1.5 text-xs">
                <div className="flex justify-between">
                  <span className="text-text-muted">Operator / MCC-MNC</span>
                  <span className="font-mono text-text">
                    {selectedMeasurement.radio.operatorName} ({selectedMeasurement.radio.mcc}-
                    {selectedMeasurement.radio.mnc})
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-text-muted">Band / EARFCN</span>
                  <span className="font-mono text-text">
                    Band {selectedMeasurement.radio.band} · {selectedMeasurement.radio.earfcn}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-text-muted">Speed</span>
                  <span className="font-mono text-text">
                    {(selectedMeasurement.gps.speed * 3.6).toFixed(1)} km/h (
                    {selectedMeasurement.gps.speed.toFixed(1)} m/s)
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-text-muted">Coordinates</span>
                  <span className="font-mono text-text-muted">
                    {selectedMeasurement.gps.latitude.toFixed(5)},{' '}
                    {selectedMeasurement.gps.longitude.toFixed(5)}
                  </span>
                </div>
              </div>

              {/* Handover / Missed Handover Alerts for Selected Point */}
              {selectedHandover && (
                <div className="bg-amber-500/15 border border-amber-500/40 rounded-lg p-2.5 text-xs flex items-start gap-2">
                  <RefreshCw className="w-4 h-4 text-amber-400 shrink-0 mt-0.5" />
                  <div>
                    <div className="font-semibold text-amber-300">
                      {selectedHandover.isPingPong
                        ? 'Ping-Pong Handover Detected'
                        : 'Cell Handover Event'}
                    </div>
                    <div className="text-text-muted mt-0.5">
                      PCI {selectedHandover.previousPci} (CID {selectedHandover.previousCellId})
                      {' -> '}PCI {selectedHandover.newPci} (CID {selectedHandover.newCellId})
                    </div>
                  </div>
                </div>
              )}

              {selectedMissedHo && (
                <div className="bg-rose-500/15 border border-rose-500/40 rounded-lg p-2.5 text-xs flex items-start gap-2">
                  <AlertTriangle className="w-4 h-4 text-rose-400 shrink-0 mt-0.5" />
                  <div>
                    <div className="font-semibold text-rose-300">
                      Missed Handover (+{selectedMissedHo.marginDb} dB Neighbor)
                    </div>
                    <div className="text-text-muted mt-0.5">
                      Neighbor PCI {selectedMissedHo.neighborPci} at{' '}
                      {selectedMissedHo.neighborRsrp} dBm vs Serving {selectedMissedHo.servingRsrp}{' '}
                      dBm
                    </div>
                  </div>
                </div>
              )}

              {/* Neighbor Cells List */}
              {options.showNeighborCells && (
                <div className="space-y-1.5">
                  <div className="text-xs font-semibold text-text-muted flex items-center justify-between">
                    <span>{t.neighborCells}</span>
                    <span className="font-mono text-[11px]">
                      {selectedMeasurement.radio.neighborCells.length} visible
                    </span>
                  </div>
                  {selectedMeasurement.radio.neighborCells.length > 0 ? (
                    <div className="space-y-1 max-h-36 overflow-y-auto pr-1">
                      {selectedMeasurement.radio.neighborCells.map((nb, idx) => {
                        const delta = nb.rsrp - selectedMeasurement.radio.rsrp;
                        return (
                          <div
                            key={`${nb.pci}-${idx}`}
                            className="flex items-center justify-between bg-surface-elevated border border-border rounded px-2.5 py-1.5 text-xs"
                          >
                            <div className="flex items-center gap-2">
                              <span className="font-mono font-semibold text-text">
                                PCI {nb.pci}
                              </span>
                              <span className="text-[11px] text-text-muted">
                                {nb.technology.replace('_', ' ')} · EARFCN {nb.earfcn}
                              </span>
                            </div>
                            <div className="font-mono">
                              <span className="text-text">{nb.rsrp} dBm</span>
                              <span
                                className={`ml-1.5 text-[11px] ${
                                  delta > 0 ? 'text-rose-400 font-semibold' : 'text-text-muted'
                                }`}
                              >
                                ({delta > 0 ? `+${delta}` : delta} dB)
                              </span>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  ) : (
                    <div className="text-xs text-text-muted italic">
                      No neighbor cells reported at this sample.
                    </div>
                  )}
                </div>
              )}
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center h-64 text-center text-text-muted">
              <Eye className="w-8 h-8 text-text-muted mb-2" />
              <p className="text-sm font-medium">No Route Measurements</p>
              <p className="text-xs text-text-muted mt-1">
                Start a drive test session or select a recorded campaign.
              </p>
            </div>
          )}

          {/* Quick Point Stepper Footer */}
          {sortedMeasurements.length > 0 && (
            <div className="pt-3 border-t border-border flex items-center justify-between text-xs text-text-muted">
              <span className="inline-flex items-center gap-1">
                <Activity className="w-3.5 h-3.5 text-accent" />
                Sample #{sortedMeasurements.findIndex((m) => m.id === selectedMeasurement?.id) + 1}{' '}
                of {sortedMeasurements.length}
              </span>
              <span className="inline-flex items-center gap-1 text-emerald-400">
                <CheckCircle2 className="w-3.5 h-3.5" />
                Cov {signalStats.coveragePercent}%
              </span>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
