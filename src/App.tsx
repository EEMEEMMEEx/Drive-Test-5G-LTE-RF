import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Activity,
  BarChart3,
  Compass,
  FolderKanban,
  Globe,
  HelpCircle,
  LayoutGrid,
  Moon,
  Radio,
  Sun,
} from 'lucide-react';
import { APP_VERSION } from './version';
import { TRANSLATIONS, type SupportedLocale } from './i18n/translations';
import { clearDatabaseState, loadDatabaseState, resetDatabaseToSeed, saveDatabaseState } from './db/database';
import type { SeedDatabaseState } from './db/seedData';
import type {
  Campaign,
  CampaignFinishedSummary,
  CampaignPreset,
  DiagnosticThresholds,
  GpsInfo,
  LightNetworkProbe,
  MapOptions,
  Measurement,
  MeasurementCadence,
  SimInfo,
  SpeedTestProgress,
  SpeedTestResult,
} from './types/drivetest';
import {
  BLANK_RADIO_INFO,
  DEFAULT_SIM_CARDS,
  generateLightProbeSample,
  generateSimulatedMeasurement,
  getCadenceIntervalMs,
  runSimulatedSpeedTestStep,
} from './services/driveSimulator';
import {
  calculateSignalStats,
  DEFAULT_DIAGNOSTIC_THRESHOLDS,
  detectMobilityEvents,
  haversineDistanceMeters,
} from './services/rfAnalyticsEngine';
import { parseImportedMeasurementsCsv } from './services/exportService';
import { DriveTestDashboard } from './components/dashboard/DriveTestDashboard';
import { RouteMapView } from './components/map/RouteMapView';
import { CampaignListView } from './components/campaigns/CampaignListView';
import { AnalyticsView } from './components/analytics/AnalyticsView';
import { WidgetsPreviewModal } from './components/widgets/WidgetsPreviewModal';
import { SpotlightTutorial } from './components/tutorial/SpotlightTutorial';

export type MainNavTab = 'DASHBOARD' | 'MAP' | 'CAMPAIGNS' | 'ANALYTICS';

export function App() {
  const [locale, setLocale] = useState<SupportedLocale>('en');
  const [lightTheme, setLightTheme] = useState(false);
  const [activeTab, setActiveTab] = useState<MainNavTab>('DASHBOARD');

  const [db, setDb] = useState<SeedDatabaseState>(() => loadDatabaseState());
  const [selectedCampaignId, setSelectedCampaignId] = useState<number>(() => {
    const initial = loadDatabaseState();
    return initial.campaigns[0]?.id ?? 1;
  });

  // Live Drive Test Recording State
  const [isRunning, setIsRunning] = useState(false);
  const [isPaused, setIsPaused] = useState(false);
  const [activeCampaignId, setActiveCampaignId] = useState<number | null>(null);
  const [activeCampaignName, setActiveCampaignName] = useState('Bangkok_5G_NR_Drive');
  const [activePreset, setActivePreset] = useState<CampaignPreset>('URBAN_ROUTE');
  const [elapsedMs, setElapsedMs] = useState(0);
  const [stepIndex, setStepIndex] = useState(0);

  const [selectedSim, setSelectedSim] = useState<SimInfo>(DEFAULT_SIM_CARDS[0]);
  const [cadence, setCadence] = useState<MeasurementCadence>('STANDARD');
  const [alertThresholdRsrp, setAlertThresholdRsrp] = useState<number | null>(-110);
  const [lightProbeEnabled, setLightProbeEnabled] = useState(true);
  const [lastLightProbe, setLastLightProbe] = useState<LightNetworkProbe | null>(null);
  const [useRealBrowserGps, setUseRealBrowserGps] = useState(false);
  const [browserGpsOverride, setBrowserGpsOverride] = useState<Partial<GpsInfo> | undefined>(
    undefined
  );

  // Speed Test State
  const [isSpeedTestRunning, setIsSpeedTestRunning] = useState(false);
  const [speedTestProgress, setSpeedTestProgress] = useState<SpeedTestProgress | null>(null);
  const [finishedSummary, setFinishedSummary] = useState<CampaignFinishedSummary | null>(null);

  // Undo Delete State
  const [deletedBackup, setDeletedBackup] = useState<{
    state: SeedDatabaseState;
    name: string;
  } | null>(null);

  // Map & Analytics Options
  const [mapOptions, setMapOptions] = useState<MapOptions>({
    colorMode: 'BY_RSRP',
    darkMap: true,
    mapType: 'NORMAL',
    signalPalette: 'CLASSIC',
    showCurrentCell: true,
    showNeighborCells: false,
    showHandovers: true,
    showMissedHandovers: true,
    dotSize: 8,
    rsrpGood: -90,
    rsrpPoor: -110,
    followMyPosition: true,
    headingUp: false,
  });

  const [diagnosticThresholds, setDiagnosticThresholds] = useState<DiagnosticThresholds>(
    DEFAULT_DIAGNOSTIC_THRESHOLDS
  );

  // Modals
  const [isWidgetsOpen, setIsWidgetsOpen] = useState(false);
  const [isTutorialOpen, setIsTutorialOpen] = useState(false);

  // Live idle/standby sample so gauge works even before starting a campaign
  const [standbyTick, setStandbyTick] = useState(0);
  const [isTelemetryCleared, setIsTelemetryCleared] = useState(false);
  const liveSample = useMemo(
    () =>
      generateSimulatedMeasurement({
        campaignId: activeCampaignId ?? selectedCampaignId,
        stepIndex: isRunning ? stepIndex : standbyTick,
        preset: activePreset,
        sim: selectedSim,
        timestampMs: Date.now(),
        overrideGps: useRealBrowserGps ? browserGpsOverride : undefined,
      }),
    [
      activeCampaignId,
      selectedCampaignId,
      isRunning,
      stepIndex,
      standbyTick,
      activePreset,
      selectedSim,
      useRealBrowserGps,
      browserGpsOverride,
    ]
  );

  // Persist DB changes
  useEffect(() => {
    saveDatabaseState(db);
  }, [db]);

  // Optional Browser Geolocation watcher
  useEffect(() => {
    if (!useRealBrowserGps || typeof navigator === 'undefined' || !navigator.geolocation) {
      setBrowserGpsOverride(undefined);
      return;
    }
    const watchId = navigator.geolocation.watchPosition(
      (pos) => {
        setBrowserGpsOverride({
          latitude: pos.coords.latitude,
          longitude: pos.coords.longitude,
          altitude: pos.coords.altitude ?? 12,
          speed: pos.coords.speed ?? 0,
          accuracy: pos.coords.accuracy ?? 5,
          bearing: pos.coords.heading ?? 0,
          isValid: true,
        });
      },
      () => {
        setBrowserGpsOverride(undefined);
      },
      { enableHighAccuracy: true }
    );
    return () => navigator.geolocation.clearWatch(watchId);
  }, [useRealBrowserGps]);

  // Group measurements by campaign
  const measurementsByCampaign = useMemo(() => {
    const map: Record<number, Measurement[]> = {};
    for (const c of db.campaigns) {
      map[c.id] = [];
    }
    for (const m of db.measurements) {
      if (!map[m.campaignId]) map[m.campaignId] = [];
      map[m.campaignId].push(m);
    }
    return map;
  }, [db.campaigns, db.measurements]);

  const activeOrSelectedId = activeCampaignId ?? selectedCampaignId;
  const currentMeasurements = useMemo(
    () => measurementsByCampaign[activeOrSelectedId] ?? [],
    [measurementsByCampaign, activeOrSelectedId]
  );

  const currentCampaign = useMemo(
    () => db.campaigns.find((c) => c.id === activeOrSelectedId) ?? db.campaigns[0],
    [db.campaigns, activeOrSelectedId]
  );

  const currentMobility = useMemo(
    () => detectMobilityEvents(currentMeasurements),
    [currentMeasurements]
  );

  const currentSpeedTests = useMemo(
    () => db.speedTests.filter((s) => s.campaignId === activeOrSelectedId),
    [db.speedTests, activeOrSelectedId]
  );

  const currentSessionEvents = useMemo(
    () => db.sessionEvents.filter((e) => e.campaignId === activeOrSelectedId),
    [db.sessionEvents, activeOrSelectedId]
  );

  // Recording Interval Loop
  const stepRef = useRef(0);
  stepRef.current = stepIndex;

  useEffect(() => {
    if (!isRunning || isPaused || activeCampaignId === null) return;

    const intervalMs = getCadenceIntervalMs(cadence);
    const timer = window.setInterval(() => {
      const nextStep = stepRef.current + 1;
      setIsTelemetryCleared(false);
      const now = Date.now();
      setStepIndex(nextStep);
      setElapsedMs((prev) => prev + intervalMs);

      const newSample = generateSimulatedMeasurement({
        campaignId: activeCampaignId,
        stepIndex: nextStep,
        preset: activePreset,
        sim: selectedSim,
        timestampMs: now,
        overrideGps: useRealBrowserGps ? browserGpsOverride : undefined,
      });

      if (lightProbeEnabled) {
        const probe = generateLightProbeSample(
          newSample.radio.technology,
          newSample.radio.rsrp,
          now
        );
        setLastLightProbe(probe);
      }

      setDb((prev) => {
        const campaignMeasurements = prev.measurements.filter(
          (m) => m.campaignId === activeCampaignId
        );
        const lastMeasurement = campaignMeasurements[campaignMeasurements.length - 1];
        const addedDist = lastMeasurement
          ? haversineDistanceMeters(
              lastMeasurement.gps.latitude,
              lastMeasurement.gps.longitude,
              newSample.gps.latitude,
              newSample.gps.longitude
            )
          : 0;

        const nextCampaigns = prev.campaigns.map((c) =>
          c.id === activeCampaignId
            ? {
                ...c,
                measurementCount: c.measurementCount + 1,
                distanceMeters: Math.round(c.distanceMeters + addedDist),
              }
            : c
        );

        const nextSessionEvents =
          lightProbeEnabled && nextStep % 3 === 0
            ? [
                ...prev.sessionEvents,
                {
                  id: now + nextStep,
                  campaignId: activeCampaignId,
                  type: 'LIGHT_PROBE' as const,
                  timestampMs: now,
                  durationMs: 180,
                  pingMs: newSample.radio.rsrp < -105 ? 175 : 21,
                  jitterMs: newSample.radio.rsrp < -105 ? 24 : 3.1,
                  success: true,
                  latitude: newSample.gps.latitude,
                  longitude: newSample.gps.longitude,
                  technology: newSample.radio.technology,
                  cellId: newSample.radio.cellId,
                },
              ]
            : prev.sessionEvents;

        return {
          ...prev,
          campaigns: nextCampaigns,
          measurements: [...prev.measurements, newSample],
          sessionEvents: nextSessionEvents,
        };
      });
    }, intervalMs);

    return () => window.clearInterval(timer);
  }, [
    isRunning,
    isPaused,
    activeCampaignId,
    cadence,
    activePreset,
    selectedSim,
    lightProbeEnabled,
    useRealBrowserGps,
    browserGpsOverride,
  ]);

  const handleStartCampaign = useCallback(
    (name: string, preset: CampaignPreset, alertThreshold: number | null) => {
      setIsTelemetryCleared(false);
      const now = Date.now();
      const newId =
        db.campaigns.reduce((max, c) => (c.id > max ? c.id : max), 0) + 1;

      const firstMeasurement = generateSimulatedMeasurement({
        campaignId: newId,
        stepIndex: 0,
        preset,
        sim: selectedSim,
        timestampMs: now,
        overrideGps: useRealBrowserGps ? browserGpsOverride : undefined,
      });

      const newCampaign: Campaign = {
        id: newId,
        name,
        preset,
        startTime: now,
        endTime: null,
        measurementCount: 1,
        distanceMeters: 0,
        isActive: true,
        speedTestStatus: 'NOT_RUN',
        speedDownloadMbps: 0,
        speedUploadMbps: 0,
        speedPingMs: 0,
        speedJitterMs: 0,
        speedTestTimestampMs: 0,
        speedTestLat: firstMeasurement.gps.latitude,
        speedTestLon: firstMeasurement.gps.longitude,
        alertThresholdRsrp: alertThreshold,
      };

      setDb((prev) => ({
        ...prev,
        campaigns: [
          newCampaign,
          ...prev.campaigns.map((c) => ({ ...c, isActive: false })),
        ],
        measurements: [...prev.measurements, firstMeasurement],
      }));

      setSelectedCampaignId(newId);
      setActiveCampaignId(newId);
      setActiveCampaignName(name);
      setActivePreset(preset);
      setAlertThresholdRsrp(alertThreshold);
      setStepIndex(0);
      setElapsedMs(0);
      setIsPaused(false);
      setIsRunning(true);
      setFinishedSummary(null);
    },
    [db.campaigns, selectedSim, useRealBrowserGps, browserGpsOverride]
  );

  const handlePauseResumeCampaign = useCallback(() => {
    setIsPaused((prev) => {
      const nextPaused = !prev;
      if (nextPaused && activeCampaignId !== null) {
        setIsTelemetryCleared(false);
      const now = Date.now();
        setDb((old) => ({
          ...old,
          sessionEvents: [
            ...old.sessionEvents,
            {
              id: now,
              campaignId: activeCampaignId,
              type: 'PAUSE',
              timestampMs: now,
              durationMs: 4000,
              pingMs: 0,
              jitterMs: 0,
              success: true,
              latitude: liveSample.gps.latitude,
              longitude: liveSample.gps.longitude,
              technology: liveSample.radio.technology,
              cellId: liveSample.radio.cellId,
            },
          ],
        }));
      }
      return nextPaused;
    });
  }, [activeCampaignId, liveSample]);

  const handleStopCampaign = useCallback(() => {
    if (activeCampaignId === null) {
      setIsRunning(false);
      return;
    }

    const now = Date.now();
    const samples = measurementsByCampaign[activeCampaignId] ?? [];
    const stats = calculateSignalStats(samples);
    const mobility = detectMobilityEvents(samples);
    const targetCampaign = db.campaigns.find((c) => c.id === activeCampaignId);

    setDb((prev) => ({
      ...prev,
      campaigns: prev.campaigns.map((c) =>
        c.id === activeCampaignId ? { ...c, isActive: false, endTime: now } : c
      ),
    }));

    setFinishedSummary({
      campaignId: activeCampaignId,
      campaignName: targetCampaign?.name ?? activeCampaignName,
      durationMs: Math.max(1000, elapsedMs),
      distanceMeters: targetCampaign?.distanceMeters ?? 0,
      measurementCount: samples.length,
      avgRsrp: stats.avgRsrp,
      minRsrp: stats.minRsrp,
      maxRsrp: stats.maxRsrp,
      handoverCount: mobility.handovers,
    });

    setIsRunning(false);
    setIsPaused(false);
    setActiveCampaignId(null);
  }, [activeCampaignId, activeCampaignName, db.campaigns, elapsedMs, measurementsByCampaign]);

  const handleRunSpeedTest = useCallback(() => {
    if (isSpeedTestRunning) return;
    setIsSpeedTestRunning(true);

    const metrics = runSimulatedSpeedTestStep(
      liveSample.radio.technology,
      liveSample.radio.rsrp
    );

    setSpeedTestProgress({
      phase: 'PING',
      pingMs: metrics.pingMs,
      downloadMbps: 0,
      uploadMbps: 0,
      loadedPingMs: metrics.loadedPingMs,
    });

    window.setTimeout(() => {
      setSpeedTestProgress({
        phase: 'DOWNLOAD',
        pingMs: metrics.pingMs,
        downloadMbps: metrics.downloadMbps,
        uploadMbps: 0,
        loadedPingMs: metrics.loadedPingMs,
      });
    }, 250);

    window.setTimeout(() => {
      setSpeedTestProgress({
        phase: 'UPLOAD',
        pingMs: metrics.pingMs,
        downloadMbps: metrics.downloadMbps,
        uploadMbps: metrics.uploadMbps,
        loadedPingMs: metrics.loadedPingMs,
      });
    }, 500);

    window.setTimeout(() => {
      setIsTelemetryCleared(false);
      const now = Date.now();
      const targetId = activeCampaignId ?? selectedCampaignId;
      const result: SpeedTestResult = {
        id: now,
        campaignId: targetId,
        timestampMs: now,
        status: 'SUCCESS',
        downloadMbps: metrics.downloadMbps,
        uploadMbps: metrics.uploadMbps,
        pingMs: metrics.pingMs,
        jitterMs: metrics.jitterMs,
        loadedPingMs: metrics.loadedPingMs,
        latitude: liveSample.gps.latitude,
        longitude: liveSample.gps.longitude,
        technology: liveSample.radio.technology,
        band: liveSample.radio.band,
        cellId: liveSample.radio.cellId,
        pci: liveSample.radio.pci,
        rsrp: liveSample.radio.rsrp,
        transport: 'MOBILE',
        dataSubscriptionId: selectedSim.subscriptionId,
      };

      setDb((prev) => ({
        ...prev,
        campaigns: prev.campaigns.map((c) =>
          c.id === targetId
            ? {
                ...c,
                speedTestStatus: 'SUCCESS',
                speedDownloadMbps: metrics.downloadMbps,
                speedUploadMbps: metrics.uploadMbps,
                speedPingMs: metrics.pingMs,
                speedJitterMs: metrics.jitterMs,
                speedTestTimestampMs: now,
                speedTestLat: liveSample.gps.latitude,
                speedTestLon: liveSample.gps.longitude,
              }
            : c
        ),
        speedTests: [...prev.speedTests, result],
      }));

      setSpeedTestProgress(null);
      setIsSpeedTestRunning(false);
    }, 750);
  }, [isSpeedTestRunning, liveSample, activeCampaignId, selectedCampaignId, selectedSim]);

  const handleDeleteCampaigns = useCallback(
    (ids: number[]) => {
      if (ids.length === 0) return;
      const idSet = new Set(ids);
      const firstDeleted = db.campaigns.find((c) => idSet.has(c.id));
      setDeletedBackup({
        state: db,
        name:
          ids.length === 1
            ? firstDeleted?.name ?? 'Campaign'
            : `${ids.length} campaigns`,
      });

      setDb((prev) => {
        const remainingCampaigns = prev.campaigns.filter((c) => !idSet.has(c.id));
        if (
          remainingCampaigns.length > 0 &&
          idSet.has(selectedCampaignId)
        ) {
          setSelectedCampaignId(remainingCampaigns[0].id);
        }
        return {
          campaigns: remainingCampaigns,
          measurements: prev.measurements.filter((m) => !idSet.has(m.campaignId)),
          speedTests: prev.speedTests.filter((s) => !idSet.has(s.campaignId)),
          sessionEvents: prev.sessionEvents.filter((e) => !idSet.has(e.campaignId)),
        };
      });
    },
    [db, selectedCampaignId]
  );

  const handleUndoDelete = useCallback(() => {
    if (!deletedBackup) return;
    setDb(deletedBackup.state);
    setDeletedBackup(null);
  }, [deletedBackup]);

  const handleImportCampaignCsv = useCallback(
    (name: string, csvText: string) => {
      const newId =
        db.campaigns.reduce((max, c) => (c.id > max ? c.id : max), 0) + 1;
      const parsedMeasurements = parseImportedMeasurementsCsv(csvText, newId);
      if (parsedMeasurements.length === 0) return;

      let dist = 0;
      for (let i = 1; i < parsedMeasurements.length; i++) {
        dist += haversineDistanceMeters(
          parsedMeasurements[i - 1].gps.latitude,
          parsedMeasurements[i - 1].gps.longitude,
          parsedMeasurements[i].gps.latitude,
          parsedMeasurements[i].gps.longitude
        );
      }

      const importedCampaign: Campaign = {
        id: newId,
        name: name.replace(/\.csv$/i, '') || `Imported_Drive_${newId}`,
        preset: 'URBAN_ROUTE',
        startTime: parsedMeasurements[0].timestampMs,
        endTime: parsedMeasurements[parsedMeasurements.length - 1].timestampMs,
        measurementCount: parsedMeasurements.length,
        distanceMeters: Math.round(dist),
        isActive: false,
        speedTestStatus: 'NOT_RUN',
        speedDownloadMbps: 0,
        speedUploadMbps: 0,
        speedPingMs: 0,
        speedJitterMs: 0,
        speedTestTimestampMs: 0,
        speedTestLat: parsedMeasurements[0].gps.latitude,
        speedTestLon: parsedMeasurements[0].gps.longitude,
        alertThresholdRsrp: -110,
      };

      setDb((prev) => ({
        ...prev,
        campaigns: [importedCampaign, ...prev.campaigns],
        measurements: [...prev.measurements, ...parsedMeasurements],
      }));
      setSelectedCampaignId(newId);
    },
    [db.campaigns]
  );

  const handleResetDemoData = useCallback(() => {
    const fresh = resetDatabaseToSeed();
    setDb(fresh);
    setSelectedCampaignId(fresh.campaigns[0]?.id ?? 1);
    setDeletedBackup(null);
  }, []);

  const t = TRANSLATIONS[locale];
  const latestMeasurement =
    currentMeasurements[currentMeasurements.length - 1] ?? liveSample;
  const handleClearAllData = useCallback(() => {
    const empty = clearDatabaseState();
    setDb(empty);
    setSelectedCampaignId(0);
    setDeletedBackup(null);
    setIsTelemetryCleared(true);
  }, []);

  const displayRadio = isRunning
    ? latestMeasurement.radio
    : isTelemetryCleared
    ? BLANK_RADIO_INFO
    : liveSample.radio;
  const displayGps = isRunning ? latestMeasurement.gps : liveSample.gps;

  return (
    <div className={lightTheme ? 'light-theme min-h-screen bg-bg text-text' : 'min-h-screen bg-bg text-text'}>
      {/* Top App Header & Navigation Shell */}
      <header className="sticky top-0 z-30 bg-surface/95 backdrop-blur border-b border-border">
        <div className="max-w-7xl mx-auto px-4 py-3 flex flex-wrap items-center justify-between gap-3">
          {/* Brand Title & Version */}
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-accent/15 border border-accent/40 flex items-center justify-center text-accent">
              <Radio className="w-5 h-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h1 className="text-base font-bold tracking-tight text-text">
                  {t.appTitle}
                </h1>
                <span className="text-[11px] font-mono px-2 py-0.5 rounded bg-surface-elevated border border-border text-accent">
                  v{APP_VERSION}
                </span>
              </div>
              <p className="text-xs text-text-muted hidden sm:block">
                {t.appSubtitle}
              </p>
            </div>
          </div>

          {/* Primary Screen Navigation Tabs */}
          <nav aria-label="Main navigation" className="flex items-center gap-1 bg-surface-elevated border border-border rounded-xl p-1">
            {(
              [
                { id: 'DASHBOARD', label: t.navDashboard, icon: Activity },
                { id: 'MAP', label: t.navMap, icon: Compass },
                { id: 'CAMPAIGNS', label: t.navCampaigns, icon: FolderKanban },
                { id: 'ANALYTICS', label: t.navAnalytics, icon: BarChart3 },
              ] as const
            ).map((item) => {
              const Icon = item.icon;
              const isActive = activeTab === item.id;
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => setActiveTab(item.id)}
                  className={`px-3.5 py-1.5 rounded-lg text-xs font-semibold flex items-center gap-1.5 transition ${
                    isActive
                      ? 'bg-accent text-on-accent shadow-sm'
                      : 'text-text-muted hover:text-text'
                  }`}
                >
                  <Icon className="w-4 h-4" />
                  <span>{item.label}</span>
                </button>
              );
            })}
          </nav>

          {/* Utility Controls: Widgets, Tutorial, Language, Theme */}
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setIsWidgetsOpen(true)}
              className="px-2.5 py-1.5 rounded-lg bg-surface-elevated border border-border text-xs font-medium text-text hover:border-accent flex items-center gap-1.5"
            >
              <LayoutGrid className="w-3.5 h-3.5 text-accent" />
              <span className="hidden md:inline">{t.widgetsPreview}</span>
            </button>

            <button
              type="button"
              onClick={() => setIsTutorialOpen(true)}
              className="px-2.5 py-1.5 rounded-lg bg-surface-elevated border border-border text-xs font-medium text-text hover:border-accent flex items-center gap-1.5"
            >
              <HelpCircle className="w-3.5 h-3.5 text-accent" />
              <span className="hidden md:inline">{t.replayTutorial}</span>
            </button>

            <div className="flex items-center gap-1 bg-surface-elevated border border-border rounded-lg px-2 py-1">
              <Globe className="w-3.5 h-3.5 text-text-muted" />
              <select
                aria-label="Select interface language"
                value={locale}
                onChange={(e) => setLocale(e.target.value as SupportedLocale)}
                className="bg-transparent text-xs font-medium text-text focus:outline-none"
              >
                <option value="en">EN</option>
                <option value="th">TH</option>
                <option value="fr">FR</option>
                <option value="de">DE</option>
                <option value="es">ES</option>
              </select>
            </div>

            <button
              type="button"
              aria-label="Toggle color theme"
              onClick={() => setLightTheme((v) => !v)}
              className="p-1.5 rounded-lg bg-surface-elevated border border-border text-text-muted hover:text-text"
            >
              {lightTheme ? <Moon className="w-4 h-4" /> : <Sun className="w-4 h-4" />}
            </button>
          </div>
        </div>
      </header>

      {/* Main Screen Workspace */}
      <main className="max-w-7xl mx-auto px-4 py-5">
        {activeTab === 'DASHBOARD' && (
          <DriveTestDashboard
            isRunning={isRunning}
            isPaused={isPaused}
            activeCampaignName={activeCampaignName}
            activePreset={activePreset}
            elapsedMs={elapsedMs}
            measurementCount={currentCampaign?.measurementCount ?? currentMeasurements.length}
            distanceMeters={currentCampaign?.distanceMeters ?? 0}
            handoverCount={currentMobility.handovers}
            currentRadio={displayRadio}
            currentGps={displayGps}
            recentMeasurements={currentMeasurements}
            availableSims={DEFAULT_SIM_CARDS}
            selectedSim={selectedSim}
            onSelectSim={setSelectedSim}
            cadence={cadence}
            onChangeCadence={setCadence}
            alertThresholdRsrp={alertThresholdRsrp}
            onChangeAlertThreshold={setAlertThresholdRsrp}
            lightProbeEnabled={lightProbeEnabled}
            onToggleLightProbe={setLightProbeEnabled}
            lastLightProbe={lastLightProbe}
            isSpeedTestRunning={isSpeedTestRunning}
            speedTestProgress={speedTestProgress}
            lastSpeedTestStatus={currentCampaign?.speedTestStatus ?? 'NOT_RUN'}
            lastSpeedDownloadMbps={currentCampaign?.speedDownloadMbps ?? 0}
            lastSpeedUploadMbps={currentCampaign?.speedUploadMbps ?? 0}
            lastSpeedPingMs={currentCampaign?.speedPingMs ?? 0}
            lastSpeedJitterMs={currentCampaign?.speedJitterMs ?? 0}
            onRunSpeedTest={handleRunSpeedTest}
            onStartCampaign={handleStartCampaign}
            onPauseResumeCampaign={handlePauseResumeCampaign}
            onStopCampaign={handleStopCampaign}
            finishedSummary={finishedSummary}
            onDismissFinishedSummary={() => setFinishedSummary(null)}
            onViewFinishedAnalysis={(campaignId) => {
              setSelectedCampaignId(campaignId);
              setFinishedSummary(null);
              setActiveTab('ANALYTICS');
            }}
            onSwitchToLiveMap={() => setActiveTab('MAP')}
            useRealBrowserGps={useRealBrowserGps}
            onToggleRealBrowserGps={setUseRealBrowserGps}
            isTelemetryCleared={isTelemetryCleared}
            onClearTelemetry={() => setIsTelemetryCleared(true)}
            t={t}
          />
        )}

        {activeTab === 'MAP' && (
          <RouteMapView
            campaigns={db.campaigns}
            selectedCampaignId={activeOrSelectedId}
            onSelectCampaign={setSelectedCampaignId}
            measurements={currentMeasurements}
            isRunning={isRunning}
            currentLocation={displayGps}
            options={mapOptions}
            onUpdateOptions={setMapOptions}
            onNavigateToAnalytics={() => setActiveTab('ANALYTICS')}
            t={t}
          />
        )}

        {activeTab === 'CAMPAIGNS' && (
          <CampaignListView
            campaigns={db.campaigns}
            measurementsByCampaign={measurementsByCampaign}
            selectedCampaignId={selectedCampaignId}
            onSelectCampaign={setSelectedCampaignId}
            onOpenMapForCampaign={(id) => {
              setSelectedCampaignId(id);
              setActiveTab('MAP');
            }}
            onOpenAnalyticsForCampaign={(id) => {
              setSelectedCampaignId(id);
              setActiveTab('ANALYTICS');
            }}
            onDeleteCampaigns={handleDeleteCampaigns}
            onUndoDelete={handleUndoDelete}
            canUndoDelete={deletedBackup !== null}
            lastDeletedName={deletedBackup?.name ?? null}
            onImportCampaignCsv={handleImportCampaignCsv}
            onResetDemoData={handleResetDemoData}
            onClearAllData={handleClearAllData}
            t={t}
          />
        )}

        {activeTab === 'ANALYTICS' && (
          <AnalyticsView
            campaigns={db.campaigns}
            selectedCampaignId={selectedCampaignId}
            onSelectCampaign={setSelectedCampaignId}
            measurements={measurementsByCampaign[selectedCampaignId] ?? []}
            allMeasurements={db.measurements}
            speedTests={currentSpeedTests}
            sessionEvents={currentSessionEvents}
            thresholds={diagnosticThresholds}
            onUpdateThresholds={setDiagnosticThresholds}
            t={t}
          />
        )}
      </main>

      {/* Home Widgets Preview Modal */}
      <WidgetsPreviewModal
        isOpen={isWidgetsOpen}
        onClose={() => setIsWidgetsOpen(false)}
        currentRadio={displayRadio}
        isRunning={isRunning}
        activeCampaignName={activeCampaignName}
        measurementCount={currentCampaign?.measurementCount ?? currentMeasurements.length}
        distanceMeters={currentCampaign?.distanceMeters ?? 0}
        onStartQuickCampaign={() =>
          handleStartCampaign(
            `Widget_Drive_${Date.now().toString().slice(-4)}`,
            activePreset,
            alertThresholdRsrp
          )
        }
        onStopCampaign={handleStopCampaign}
        onRefreshSignal={() => setStandbyTick((n) => n + 1)}
      />

      {/* 5-Step Spotlight Tutorial Modal */}
      <SpotlightTutorial
        isOpen={isTutorialOpen}
        onClose={() => setIsTutorialOpen(false)}
        onNavigateTab={setActiveTab}
        onOpenWidgets={() => setIsWidgetsOpen(true)}
      />
    </div>
  );
}
export default App;

