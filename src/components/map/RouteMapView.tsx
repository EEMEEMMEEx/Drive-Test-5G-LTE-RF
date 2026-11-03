import { useEffect, useMemo, useRef, useState } from 'react';
import L from 'leaflet';
import {
  Activity,
  AlertTriangle,
  ArrowLeft,
  ArrowRight,
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
import { OpenUiCard } from '../openui/OpenUiCard';
import { OpenUiChips, type OpenUiChipItem } from '../openui/OpenUiChips';
import { OpenUiMetric } from '../openui/OpenUiMetric';

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

interface TileConfig {
  url: string;
  attribution: string;
  subdomains?: string[];
  maxZoom?: number;
}

const TILE_URLS: Record<MapBaseType, TileConfig> = {
  GOOGLE_HYBRID: {
    url: 'https://mt{s}.google.com/vt/lyrs=y&x={x}&y={y}&z={z}',
    attribution: '&copy; Google Maps',
    subdomains: ['0', '1', '2', '3'],
    maxZoom: 20,
  },
  GOOGLE_SATELLITE: {
    url: 'https://mt{s}.google.com/vt/lyrs=s&x={x}&y={y}&z={z}',
    attribution: '&copy; Google Maps',
    subdomains: ['0', '1', '2', '3'],
    maxZoom: 20,
  },
  GOOGLE_ROADMAP: {
    url: 'https://mt{s}.google.com/vt/lyrs=m&x={x}&y={y}&z={z}',
    attribution: '&copy; Google Maps',
    subdomains: ['0', '1', '2', '3'],
    maxZoom: 20,
  },
  GOOGLE_TERRAIN: {
    url: 'https://mt{s}.google.com/vt/lyrs=p&x={x}&y={y}&z={z}',
    attribution: '&copy; Google Maps',
    subdomains: ['0', '1', '2', '3'],
    maxZoom: 20,
  },
  CARTO_DARK: {
    url: 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',
    attribution: '&copy; CARTO &copy; OpenStreetMap contributors',
    subdomains: ['a', 'b', 'c', 'd'],
    maxZoom: 19,
  },
  NORMAL: {
    url: 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
    attribution: '&copy; OpenStreetMap contributors',
    subdomains: ['a', 'b', 'c'],
    maxZoom: 19,
  },
  SATELLITE: {
    url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
    attribution: '&copy; Esri World Imagery',
    maxZoom: 19,
  },
  TERRAIN: {
    url: 'https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png',
    attribution: '&copy; OpenTopoMap',
    subdomains: ['a', 'b', 'c'],
    maxZoom: 17,
  },
  HYBRID: {
    url: 'https://mt{s}.google.com/vt/lyrs=y&x={x}&y={y}&z={z}',
    attribution: '&copy; Google Maps',
    subdomains: ['0', '1', '2', '3'],
    maxZoom: 20,
  },
};

const MAP_TYPE_CHIPS: OpenUiChipItem<MapBaseType>[] = [
  { id: 'GOOGLE_HYBRID', label: 'Google Hybrid' },
  { id: 'GOOGLE_SATELLITE', label: 'Google Sat' },
  { id: 'GOOGLE_ROADMAP', label: 'Google Road' },
  { id: 'CARTO_DARK', label: 'Dark Map' },
  { id: 'NORMAL', label: 'OSM' },
];

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

  const selectedMeasurementIndex = useMemo(() => {
    if (!selectedMeasurement) return -1;
    return sortedMeasurements.findIndex((m) => m.id === selectedMeasurement.id);
  }, [sortedMeasurements, selectedMeasurement]);

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

      const tileCfg = TILE_URLS[options.mapType] ?? TILE_URLS.GOOGLE_HYBRID;
      const tileLayer = L.tileLayer(tileCfg.url, {
        attribution: tileCfg.attribution,
        subdomains: tileCfg.subdomains ?? ['0', '1', '2', '3'],
        maxZoom: tileCfg.maxZoom ?? 20,
      }).addTo(map);

      const group = L.layerGroup().addTo(map);

      mapInstanceRef.current = map;
      tileLayerRef.current = tileLayer;
      layerGroupRef.current = group;

      setTimeout(() => {
        try {
          map.invalidateSize();
        } catch {
          // safe
        }
      }, 150);
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
      const tileCfg = TILE_URLS[options.mapType] ?? TILE_URLS.GOOGLE_HYBRID;
      tileLayerRef.current = L.tileLayer(tileCfg.url, {
        attribution: tileCfg.attribution,
        subdomains: tileCfg.subdomains ?? ['0', '1', '2', '3'],
        maxZoom: tileCfg.maxZoom ?? 20,
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
        color: '#0284c7',
        weight: 3,
        opacity: 0.65,
      }).addTo(group);

      // Serving cell site markers and sector lines
      if (options.showCurrentCell) {
        for (const site of cellSites) {
          const siteMarker = L.circleMarker([site.latitude, site.longitude], {
            radius: 8,
            color: '#f8fafc',
            weight: 2,
            fillColor: getTechnologyColor(site.technology),
            fillOpacity: 0.95,
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
                color: '#06b6d4',
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

  // Compute SVG bounding box for Vector Route Overlay
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

  const stepToAdjacentSample = (delta: number) => {
    if (selectedMeasurementIndex < 0 || sortedMeasurements.length === 0) return;
    const nextIdx = Math.max(
      0,
      Math.min(sortedMeasurements.length - 1, selectedMeasurementIndex + delta)
    );
    setSelectedPointId(sortedMeasurements[nextIdx].id);
  };

  return (
    <div className="space-y-4">
      {/* Top HUD Toolbar wrapped in OpenUiCard */}
      <OpenUiCard
        className="shadow-sm"
        bodyClassName="!p-3 sm:!p-4 flex flex-wrap items-center justify-between gap-3"
      >
        <div className="flex flex-wrap items-center gap-2.5">
          {/* Campaign Selector */}
          <div className="flex items-center gap-2 bg-surface-elevated border border-border/80 rounded-xl px-3 py-1.5 shadow-xs">
            <MapPin className="w-4 h-4 text-accent shrink-0" />
            <label htmlFor="map-campaign-select" className="sr-only">
              {t.campaignName}
            </label>
            <select
              id="map-campaign-select"
              aria-label={t.campaignName}
              value={selectedCampaignId}
              onChange={(e) => onSelectCampaign(Number(e.target.value))}
              className="bg-transparent text-xs sm:text-sm font-semibold text-text focus:outline-none cursor-pointer pr-1"
            >
              {campaigns.length === 0 && (
                <option value={0} className="bg-surface text-text-muted">
                  No Campaigns Available
                </option>
              )}
              {campaigns.map((c) => (
                <option key={c.id} value={c.id} className="bg-surface text-text font-medium">
                  {c.name} ({c.measurementCount} pts)
                </option>
              ))}
            </select>
          </div>

          {/* Live Status Badge */}
          <div
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-bold border transition-all ${
              isRunning
                ? 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-300 border-emerald-500/30 animate-pulse'
                : 'bg-surface-elevated text-text-muted border-border/80'
            }`}
          >
            <Radio className={`w-3.5 h-3.5 ${isRunning ? 'text-emerald-500' : 'text-text-muted'}`} />
            <span>{isRunning ? 'LIVE TRACKING' : 'REPLAY MODE'}</span>
          </div>

          {/* Follow My Position Toggle */}
          <button
            type="button"
            onClick={() => updateOption('followMyPosition', !options.followMyPosition)}
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-semibold border transition-all ${
              options.followMyPosition
                ? 'bg-accent/15 text-accent border-accent/40 shadow-xs'
                : 'bg-surface-elevated text-text-muted border-border/80 hover:text-text'
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
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-semibold border transition-all ${
              options.headingUp
                ? 'bg-accent/15 text-accent border-accent/40 shadow-xs'
                : 'bg-surface-elevated text-text-muted border-border/80 hover:text-text'
            }`}
            title="Toggle Orientation (Heading Up / North Up)"
          >
            <Compass className="w-3.5 h-3.5" />
            <span>{options.headingUp ? `Heading Up (${Math.round(latestBearing)}°)` : 'North Up'}</span>
          </button>
        </div>

        {/* Right Controls: Map Engine Chips, Dark Toggle, Drawer Toggle, Analytics */}
        <div className="flex flex-wrap items-center gap-2">
          {/* Map Base Engine Chips */}
          <OpenUiChips
            items={MAP_TYPE_CHIPS}
            selectedId={options.mapType}
            onChange={(id) => updateOption('mapType', id)}
            size="sm"
            ariaLabel="Map Layer Engine"
          />

          {/* Dark / Light Base Map Toggle */}
          <button
            type="button"
            onClick={() => {
              const nextDark = !options.darkMap;
              onUpdateOptions({
                ...options,
                darkMap: nextDark,
                mapType: nextDark ? 'CARTO_DARK' : 'GOOGLE_HYBRID',
              });
            }}
            className="inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-xl text-xs font-semibold bg-surface-elevated text-text border border-border/80 hover:border-accent/40 transition-all"
            aria-label="Toggle Dark Map"
          >
            {options.darkMap ? (
              <>
                <Moon className="w-3.5 h-3.5 text-accent" />
                <span className="hidden sm:inline">Dark</span>
              </>
            ) : (
              <>
                <Sun className="w-3.5 h-3.5 text-amber-500" />
                <span className="hidden sm:inline">Light</span>
              </>
            )}
          </button>

          {/* Map Layer Options Drawer Button */}
          <button
            type="button"
            onClick={() => setDrawerOpen((prev) => !prev)}
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-semibold border transition-all ${
              drawerOpen
                ? 'bg-accent text-on-accent border-accent shadow-sm'
                : 'bg-surface-elevated text-text border-border/80 hover:border-accent/40'
            }`}
          >
            <SlidersHorizontal className="w-3.5 h-3.5" />
            <span>{t.mapOptions}</span>
          </button>

          {/* Navigate to Analytics */}
          <button
            type="button"
            onClick={onNavigateToAnalytics}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-semibold bg-indigo-600 hover:bg-indigo-500 text-white shadow-xs transition-all"
          >
            <BarChart3 className="w-3.5 h-3.5" />
            <span>{t.navAnalytics}</span>
          </button>
        </div>
      </OpenUiCard>

      {/* Collapsible Map Layer Controls Drawer */}
      {drawerOpen && (
        <OpenUiCard
          data-testid="map-options-drawer"
          title={t.mapOptions}
          subtitle="Configure RF render palettes, dot sizes, and active cell topology overlays"
          icon={<Layers className="w-4 h-4 text-accent" />}
          actions={
            <button
              type="button"
              onClick={() => setDrawerOpen(false)}
              className="p-1.5 text-text-muted hover:text-text rounded-lg hover:bg-surface-elevated transition"
              aria-label="Close Map Options"
            >
              <X className="w-4 h-4" />
            </button>
          }
          className="shadow-md animate-in fade-in slide-in-from-top-2 duration-150"
        >
          <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
            {/* Column 1: Color Metric & Palette */}
            <div className="space-y-4">
              <div>
                <label className="block text-xs font-bold uppercase tracking-wider text-text-muted mb-2">
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
                      className={`px-3 py-2 rounded-xl text-xs font-semibold border text-left transition-all ${
                        options.colorMode === item.id
                          ? 'bg-accent/15 border-accent text-accent font-bold shadow-xs'
                          : 'bg-surface-elevated border-border/80 text-text-muted hover:text-text hover:border-border'
                      }`}
                    >
                      {item.label}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <label className="block text-xs font-bold uppercase tracking-wider text-text-muted mb-2">
                  <span className="inline-flex items-center gap-1.5">
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
                        className={`px-3 py-2 rounded-xl text-xs font-semibold border transition-all ${
                          options.signalPalette === pal
                            ? 'bg-indigo-500/20 border-indigo-500 text-indigo-500 dark:text-indigo-300 font-bold'
                            : 'bg-surface-elevated border-border/80 text-text-muted hover:text-text'
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
            <div className="space-y-4 bg-surface-elevated/70 border border-border/80 rounded-2xl p-4">
              <div>
                <div className="flex justify-between text-xs font-semibold mb-1.5">
                  <span className="text-text-muted">{t.pointSize}</span>
                  <span className="font-mono font-bold text-accent">{options.dotSize} px</span>
                </div>
                <input
                  type="range"
                  aria-label={t.pointSize}
                  min={4}
                  max={14}
                  step={1}
                  value={options.dotSize}
                  onChange={(e) => updateOption('dotSize', Number(e.target.value))}
                  className="w-full accent-cyan-500 cursor-pointer"
                />
              </div>

              <div>
                <div className="flex justify-between text-xs font-semibold mb-1.5">
                  <span className="text-text-muted">RSRP Good Threshold</span>
                  <span className="font-mono font-bold text-emerald-500">{options.rsrpGood} dBm</span>
                </div>
                <input
                  type="range"
                  aria-label="RSRP Good Threshold"
                  min={-105}
                  max={-70}
                  step={1}
                  value={options.rsrpGood}
                  onChange={(e) => updateOption('rsrpGood', Number(e.target.value))}
                  className="w-full accent-emerald-500 cursor-pointer"
                />
              </div>

              <div>
                <div className="flex justify-between text-xs font-semibold mb-1.5">
                  <span className="text-text-muted">RSRP Poor Threshold</span>
                  <span className="font-mono font-bold text-rose-500">{options.rsrpPoor} dBm</span>
                </div>
                <input
                  type="range"
                  aria-label="RSRP Poor Threshold"
                  min={-125}
                  max={-95}
                  step={1}
                  value={options.rsrpPoor}
                  onChange={(e) => updateOption('rsrpPoor', Number(e.target.value))}
                  className="w-full accent-rose-500 cursor-pointer"
                />
              </div>
            </div>

            {/* Column 3: Map Layer Switches */}
            <div className="space-y-2 bg-surface-elevated/70 border border-border/80 rounded-2xl p-4">
              <div className="text-xs font-bold text-text-muted uppercase tracking-wider mb-2">
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
                  className="py-1 px-1 rounded-xl hover:bg-surface transition"
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
        </OpenUiCard>
      )}

      {/* Main Map Layout: Left (Google Map + Vector Trace) & Right (Inspector) */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-4 items-start">
        {/* Left Column (8 cols): Map Canvas & Vector Trace */}
        <div className="lg:col-span-8 space-y-4">
          {/* Leaflet / Google Map Canvas Card */}
          <div className="relative rounded-2xl overflow-hidden border border-border bg-surface shadow-sm h-[480px] sm:h-[540px] lg:h-[580px]">
            <div
              ref={mapContainerRef}
              className="w-full h-full transition-transform duration-300"
              style={{
                transform: options.headingUp ? `rotate(${-latestBearing}deg)` : undefined,
              }}
            />

            {/* Floating Glassmorphic Route Summary Ribbon */}
            <div className="absolute top-3 left-3 z-[400] flex flex-wrap items-center gap-2 bg-surface/90 backdrop-blur-md border border-border/80 shadow-md rounded-xl px-3.5 py-2 text-xs">
              <div className="flex items-center gap-1.5">
                <span className="text-text-muted font-medium">Samples:</span>
                <span className="font-mono font-bold text-text">
                  {sortedMeasurements.length}
                </span>
              </div>
              <span className="text-border">|</span>
              <div className="flex items-center gap-1.5">
                <span className="text-text-muted font-medium">Avg RSRP:</span>
                <span className="font-mono font-bold text-accent">
                  {signalStats.avgRsrp} dBm
                </span>
              </div>
              <span className="text-border">|</span>
              <div className="flex items-center gap-1.5">
                <span className="text-text-muted font-medium">Handovers:</span>
                <span className="font-mono font-bold text-amber-500">
                  {mobility.handovers}
                </span>
              </div>
              {mobility.missedHandovers > 0 && (
                <>
                  <span className="text-border">|</span>
                  <div className="flex items-center gap-1.5 text-rose-500 font-semibold">
                    <span>Missed HO: {mobility.missedHandovers}</span>
                  </div>
                </>
              )}
            </div>
          </div>

          {/* Interactive Vector Route Trace & Cell Overlay (Works Offline & in Tests) */}
          <OpenUiCard
            title={`Interactive Vector RF Trace (${options.colorMode.replace('BY_', '')})`}
            subtitle="High-precision Cartesian coordinate route projection"
            icon={<Navigation className="w-4 h-4 text-accent" />}
            badge={
              <div className="flex flex-wrap items-center gap-2.5 text-[11px] font-medium text-text-muted">
                <span className="inline-flex items-center gap-1 bg-surface-elevated px-2 py-0.5 rounded-full border border-border">
                  <span className="w-2 h-2 rounded-full bg-emerald-500 inline-block" />
                  Good (&ge; {options.rsrpGood} dBm)
                </span>
                <span className="inline-flex items-center gap-1 bg-surface-elevated px-2 py-0.5 rounded-full border border-border">
                  <span className="w-2 h-2 rounded-full bg-amber-500 inline-block" />
                  Fair
                </span>
                <span className="inline-flex items-center gap-1 bg-surface-elevated px-2 py-0.5 rounded-full border border-border">
                  <span className="w-2 h-2 rounded-full bg-rose-500 inline-block" />
                  Poor (&lt; {options.rsrpPoor} dBm)
                </span>
              </div>
            }
          >
            <svg
              viewBox="0 0 640 280"
              className="w-full h-56 bg-surface-elevated rounded-xl border border-border/80 shadow-inner"
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

              {/* Serving Cell Lines & Site Pins */}
              {options.showCurrentCell &&
                cellSites.map((site) => {
                  const sPt = projectToSvg(site.latitude, site.longitude);
                  return (
                    <g key={`site-${site.cellId}`}>
                      <circle
                        cx={sPt.x}
                        cy={sPt.y}
                        r={7}
                        fill={getTechnologyColor(site.technology)}
                        stroke="#ffffff"
                        strokeWidth={2}
                      />
                      <text
                        x={sPt.x}
                        y={sPt.y - 10}
                        fontSize="10"
                        fill="var(--color-text)"
                        textAnchor="middle"
                        fontWeight="600"
                      >
                        PCI {site.pci}
                      </text>
                    </g>
                  );
                })}

              {/* Serving cell line to selected point */}
              {options.showCurrentCell && selectedMeasurement && (
                (() => {
                  const sSite = cellSiteById.get(selectedMeasurement.radio.cellId);
                  if (!sSite) return null;
                  const pt = projectToSvg(
                    selectedMeasurement.gps.latitude,
                    selectedMeasurement.gps.longitude
                  );
                  const sitePt = projectToSvg(sSite.latitude, sSite.longitude);
                  return (
                    <line
                      x1={pt.x}
                      y1={pt.y}
                      x2={sitePt.x}
                      y2={sitePt.y}
                      stroke="#06b6d4"
                      strokeWidth={2}
                      strokeDasharray="4 4"
                    />
                  );
                })()
              )}

              {/* Handover Halo Rings */}
              {options.showHandovers &&
                mobility.handoverEvents.map((ho, idx) => {
                  const pt = projectToSvg(
                    ho.measurement.gps.latitude,
                    ho.measurement.gps.longitude
                  );
                  return (
                    <circle
                      key={`ho-halo-${idx}`}
                      cx={pt.x}
                      cy={pt.y}
                      r={options.dotSize + 6}
                      fill="none"
                      stroke={ho.isPingPong ? '#ef4444' : '#f59e0b'}
                      strokeWidth={2}
                    />
                  );
                })}

              {/* Missed Handover Halo Rings */}
              {options.showMissedHandovers &&
                mobility.missedHandoverEvents.map((mho, idx) => {
                  const pt = projectToSvg(
                    mho.measurement.gps.latitude,
                    mho.measurement.gps.longitude
                  );
                  return (
                    <circle
                      key={`mho-halo-${idx}`}
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
          </OpenUiCard>
        </div>

        {/* Right Column (4 cols): Selected Point Inspector Card */}
        <div className="lg:col-span-4">
          <OpenUiCard
            title="Point Telemetry Inspector"
            subtitle={
              selectedMeasurement
                ? `Sample #${selectedMeasurementIndex + 1} of ${sortedMeasurements.length}`
                : 'Selected GPS sample RF parameters'
            }
            icon={<Activity className="w-4 h-4 text-accent" />}
            badge={
              selectedMeasurement && (
                <span
                  className="px-2.5 py-1 rounded-full text-xs font-bold text-white shadow-xs"
                  style={{
                    backgroundColor: getTechnologyColor(
                      selectedMeasurement.radio.technology
                    ),
                  }}
                >
                  {selectedMeasurement.radio.technology.replace('_', ' ')}
                </span>
              )
            }
          >
            {selectedMeasurement ? (
              <div className="space-y-4">
                {/* Cell Header Identity */}
                <div className="bg-surface-elevated border border-border/80 rounded-xl p-3 flex items-center justify-between">
                  <div>
                    <div className="text-[11px] font-semibold uppercase tracking-wider text-text-muted">
                      Serving Cell
                    </div>
                    <div className="text-sm font-bold text-text mt-0.5">
                      PCI {selectedMeasurement.radio.pci} · Cell ID {selectedMeasurement.radio.cellId}
                    </div>
                  </div>
                  <div className="text-right">
                    <div className="text-[11px] font-semibold text-text-muted">NR State</div>
                    <div className="text-xs font-mono font-bold text-accent">
                      {selectedMeasurement.radio.nrStatus}
                    </div>
                  </div>
                </div>

                {/* RF Key Metrics Grid using OpenUiMetric */}
                <div className="grid grid-cols-2 gap-2.5">
                  <OpenUiMetric
                    label="RSRP"
                    value={selectedMeasurement.radio.rsrp}
                    unit="dBm"
                    variant={
                      selectedMeasurement.radio.rsrp >= options.rsrpGood
                        ? 'success'
                        : selectedMeasurement.radio.rsrp <= options.rsrpPoor
                        ? 'danger'
                        : 'warning'
                    }
                  />
                  <OpenUiMetric
                    label="RSRQ"
                    value={selectedMeasurement.radio.rsrq}
                    unit="dB"
                    variant={selectedMeasurement.radio.rsrq >= -12 ? 'success' : 'default'}
                  />
                  <OpenUiMetric
                    label="SINR"
                    value={selectedMeasurement.radio.sinr.toFixed(1)}
                    unit="dB"
                    variant="accent"
                  />
                  <OpenUiMetric
                    label="RSSI"
                    value={selectedMeasurement.radio.rssi}
                    unit="dBm"
                    variant="default"
                  />
                </div>

                {/* Serving Cell & GPS Details Card */}
                <div className="bg-surface-elevated/70 border border-border/80 rounded-xl p-3 space-y-2 text-xs">
                  <div className="flex justify-between items-center">
                    <span className="text-text-muted font-medium">Operator</span>
                    <span className="font-mono font-semibold text-text">
                      {selectedMeasurement.radio.operatorName} ({selectedMeasurement.radio.mcc}-
                      {selectedMeasurement.radio.mnc})
                    </span>
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-text-muted font-medium">Band / EARFCN</span>
                    <span className="font-mono font-semibold text-text">
                      Band {selectedMeasurement.radio.band} · {selectedMeasurement.radio.earfcn}
                    </span>
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-text-muted font-medium">Vehicle Speed</span>
                    <span className="font-mono font-semibold text-text">
                      {(selectedMeasurement.gps.speed * 3.6).toFixed(1)} km/h
                    </span>
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-text-muted font-medium">GPS Coordinates</span>
                    <span className="font-mono text-text-muted text-[11px]">
                      {selectedMeasurement.gps.latitude.toFixed(5)},{' '}
                      {selectedMeasurement.gps.longitude.toFixed(5)}
                    </span>
                  </div>
                </div>

                {/* Handover / Missed Handover Alerts */}
                {selectedHandover && (
                  <div className="bg-amber-500/15 border border-amber-500/30 rounded-xl p-3 text-xs flex items-start gap-2.5 shadow-xs">
                    <RefreshCw className="w-4 h-4 text-amber-500 shrink-0 mt-0.5 animate-spin-slow" />
                    <div>
                      <div className="font-bold text-amber-600 dark:text-amber-400">
                        {selectedHandover.isPingPong
                          ? 'Ping-Pong Handover Detected'
                          : 'Cell Handover Event'}
                      </div>
                      <div className="text-text-muted mt-1">
                        PCI {selectedHandover.previousPci} (CID {selectedHandover.previousCellId})
                        {' -> '}PCI {selectedHandover.newPci} (CID {selectedHandover.newCellId})
                      </div>
                    </div>
                  </div>
                )}

                {selectedMissedHo && (
                  <div className="bg-rose-500/15 border border-rose-500/30 rounded-xl p-3 text-xs flex items-start gap-2.5 shadow-xs">
                    <AlertTriangle className="w-4 h-4 text-rose-500 shrink-0 mt-0.5" />
                    <div>
                      <div className="font-bold text-rose-600 dark:text-rose-400">
                        Missed Handover (+{selectedMissedHo.marginDb} dB Neighbor)
                      </div>
                      <div className="text-text-muted mt-1">
                        Neighbor PCI {selectedMissedHo.neighborPci} at{' '}
                        {selectedMissedHo.neighborRsrp} dBm vs Serving {selectedMissedHo.servingRsrp}{' '}
                        dBm
                      </div>
                    </div>
                  </div>
                )}

                {/* Neighbor Cells List */}
                {options.showNeighborCells && (
                  <div className="space-y-2">
                    <div className="text-xs font-bold text-text-muted uppercase tracking-wider flex items-center justify-between">
                      <span>{t.neighborCells}</span>
                      <span className="font-mono text-[11px] font-semibold text-accent">
                        {selectedMeasurement.radio.neighborCells.length} visible
                      </span>
                    </div>
                    {selectedMeasurement.radio.neighborCells.length > 0 ? (
                      <div className="space-y-1.5 max-h-36 overflow-y-auto pr-1">
                        {selectedMeasurement.radio.neighborCells.map((nb, idx) => {
                          const delta = nb.rsrp - selectedMeasurement.radio.rsrp;
                          return (
                            <div
                              key={`${nb.pci}-${idx}`}
                              className="flex items-center justify-between bg-surface-elevated border border-border/80 rounded-xl px-3 py-1.5 text-xs"
                            >
                              <div className="flex items-center gap-2">
                                <span className="font-mono font-bold text-text">
                                  PCI {nb.pci}
                                </span>
                                <span className="text-[11px] text-text-muted">
                                  {nb.technology.replace('_', ' ')} · EARFCN {nb.earfcn}
                                </span>
                              </div>
                              <div className="font-mono">
                                <span className="font-bold text-text">{nb.rsrp} dBm</span>
                                <span
                                  className={`ml-1.5 text-[11px] font-semibold ${
                                    delta > 0 ? 'text-rose-500' : 'text-text-muted'
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
                      <div className="text-xs text-text-muted italic bg-surface-elevated/50 p-2 rounded-xl text-center">
                        No neighbor cells reported at this sample.
                      </div>
                    )}
                  </div>
                )}

                {/* Point Stepper Controls */}
                <div className="pt-2 border-t border-border flex items-center justify-between gap-2">
                  <button
                    type="button"
                    onClick={() => stepToAdjacentSample(-1)}
                    disabled={selectedMeasurementIndex <= 0}
                    className="inline-flex items-center gap-1 px-3 py-1.5 rounded-xl text-xs font-semibold bg-surface-elevated border border-border text-text hover:border-accent disabled:opacity-40 disabled:cursor-not-allowed transition"
                  >
                    <ArrowLeft className="w-3.5 h-3.5" />
                    <span>Prev</span>
                  </button>

                  <div className="text-center">
                    <div className="text-[11px] font-mono text-text-muted">
                      #{selectedMeasurementIndex + 1} / {sortedMeasurements.length}
                    </div>
                  </div>

                  <button
                    type="button"
                    onClick={() => stepToAdjacentSample(1)}
                    disabled={selectedMeasurementIndex >= sortedMeasurements.length - 1}
                    className="inline-flex items-center gap-1 px-3 py-1.5 rounded-xl text-xs font-semibold bg-surface-elevated border border-border text-text hover:border-accent disabled:opacity-40 disabled:cursor-not-allowed transition"
                  >
                    <span>Next</span>
                    <ArrowRight className="w-3.5 h-3.5" />
                  </button>
                </div>
              </div>
            ) : (
              <div className="flex flex-col items-center justify-center py-16 text-center text-text-muted">
                <div className="w-12 h-12 rounded-2xl bg-surface-elevated border border-border flex items-center justify-center text-text-muted mb-3">
                  <Eye className="w-6 h-6" />
                </div>
                <p className="text-sm font-semibold text-text">No Route Measurements</p>
                <p className="text-xs text-text-muted mt-1 max-w-xs">
                  Start a drive test session or select a recorded campaign.
                </p>
              </div>
            )}
          </OpenUiCard>
        </div>
      </div>
    </div>
  );
}
