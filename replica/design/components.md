# Component Specifications: GSM Drive Test: 5G LTE RF (`com.drivetest.app` v1.8.0)

All components consume semantic tokens from [`replica/design/tokens.json`](file:///d:/APP/Drive-Test-5G-LTE-RF/replica/design/tokens.json) and use Lucide vector icons (`16px`–`24px`, `stroke-width: 2`).

---

## 1. `RfSignalGauge`
```
RfSignalGauge
  variants  normal (220px arc), car-mode (320px oversized arc), compact (140px widget arc)
  sizes     sm 140px, md 220px, lg 320px
  states    excellent (>=-80 dBm, rf-excellent), good (-90..-80 dBm, rf-good), fair (-100..-90 dBm, rf-fair), poor (<-100 dBm, rf-poor), alert-pulsing (rsrp < alertThresholdRsrp)
  tokens    bg surface, border border, text text, readout font mono xl/display, arc stroke rf-excellent|rf-good|rf-fair|rf-poor
  a11y      role="meter", aria-valuemin="-140", aria-valuemax="-44", aria-valuenow="{rsrp}", aria-label="Serving cell RSRP in dBm"
  used on   S01, S05
```

## 2. `ServingCellCard`
```
ServingCellCard
  variants  5G-NR-SA, 5G-NR-NSA, LTE-CA, WCDMA, GSM, NO-SERVICE
  sizes     default (full width card), car-mode (2-column jumbo grid)
  states    registered (success badge), searching (warning badge), no-service (danger badge)
  tokens    bg surface, border border, tech badge tech-5g|tech-lte|tech-3g|tech-2g, metric values font mono lg/600
  a11y      semantic <section aria-labelledby="serving-cell-heading">, definition list <dl> for MCC-MNC, Cell ID, eNodeB ID, Sector, PCI, TAC, Band, EARFCN, DL Freq, Bandwidth, CSI-RSRP/RSRQ/SINR
  used on   S01
```

## 3. `CaComboBadge` & Carrier Aggregation Strip
```
CaComboBadge
  variants  single-carrier, 2CA, 3CA, 4CA, EN-DC (LTE + 5G NR)
  sizes     sm (inline pill), md (expanded carrier breakdown table)
  states    inactive, active-ca
  tokens    bg surface-elevated, border border, text accent, radius pill, font mono xs/500
  a11y      aria-label="Carrier aggregation active combination and total bandwidth"
  used on   S01, S04
```

## 4. `GpsTelemetryBar`
```
GpsTelemetryBar
  variants  compact-strip, expanded-grid, car-mode
  sizes     md (height 56px), lg (height 88px)
  states    gps-valid (success dot, live coordinates), gps-acquiring (warning dot), gps-invalid (danger dot)
  tokens    bg surface, border border, text text, muted labels text-muted, coordinates font mono sm
  a11y      aria-live="polite" status indicator for GPS fix validity and horizontal accuracy
  used on   S01, S02
```

## 5. `NeighborCellTable`
```
NeighborCellTable
  variants  compact (top 4 neighbors), expanded (all detected neighbors)
  sizes     row height 36px (compact), 44px (expanded)
  states    empty ("No neighbor cells reported"), populated, missed-ho-highlight (neighbor RSRP >= serving RSRP + 6 dB while serving <= -95 dBm, highlighted in warning/danger)
  tokens    bg surface, header text-muted, row border border, delta badge warning|success, font mono sm
  a11y      <table> with <thead> scopes (PCI, Band, EARFCN, Tech, RSRP, RSRQ, Delta dB)
  used on   S01, S02
```

## 6. `CampaignStartModal`
```
CampaignStartModal
  variants  preset-site-audit, preset-urban-route, preset-highway, preset-indoor
  sizes     modal max-w-lg (512px)
  states    default, custom-name, threshold-enabled, submitting
  tokens    bg surface-elevated, shadow pop, radius lg, preset active border accent, button bg accent text on-accent
  a11y      role="dialog", aria-modal="true", focus trap, Escape closes, radio groups for Preset (Site audit, Urban route, Highway, Indoor) and Cadence (Eco 5s, Standard 2s, Precise 1s)
  used on   S01
```

## 7. `SpeedTestRunner`
```
SpeedTestRunner
  variants  inline-card, modal-sheet
  sizes     md
  states    IDLE, PING, DOWNLOAD, UPLOAD, COMPLETED, ERROR
  tokens    bg surface, progress bar accent, metric readouts font mono lg, loaded ping warning if >150ms
  a11y      aria-live="polite" phase announcements, progressbar role with 0..100% completion
  used on   S01, S04
```

## 8. `RouteMapCanvas` & `MapControlsDrawer`
```
RouteMapCanvas
  variants  colorMode (BY_RSRP, BY_RSRQ, BY_SINR, BY_TECHNOLOGY, BY_SPEED), palette (CLASSIC, THERMIQUE, VIRIDIS, OCEAN)
  sizes     full-viewport Interactive Canvas / Map container
  states    empty, live-following, north-up, heading-up, point-selected, dark-map, light-map
  tokens    overlay panel bg surface-elevated/90, border border, legend stops mapped to selected palette
  a11y      keyboard pan/zoom buttons, accessible color mode & palette selectors, PointInspectorSheet announces selected measurement details
  used on   S02, S04
```

## 9. `CampaignCard` & `ExportFormatSheet`
```
CampaignCard
  variants  default, active-recording, multi-selected
  sizes     card height auto, sparkline 120x32px
  states    default, hover, selected, exporting, deleting-with-undo
  tokens    bg surface, border border, avg-rsrp badge rf-excellent|rf-good|rf-fair|rf-poor, sparkline stroke accent
  a11y      checkbox for multi-select, action buttons for Analytics, Compare, Export (CSV, KML, GeoJSON, PDF), and Delete
  used on   S03
```

## 10. `AnalyticsTabBar`, `CdfCurveChart` & `DiagnosisScorecard`
```
DiagnosisScorecard
  variants  grade-A (90-100), grade-B (80-89), grade-C (70-79), grade-D (60-69), grade-F (<60)
  sizes     full-width diagnostic dashboard
  states    healthy (0 issues), warning (1-2 issues), critical (3+ issues across WEAK_SIGNAL, POOR_QUALITY, FREQUENT_HANDOVERS, LOW_THROUGHPUT, HIGH_LOADED_LATENCY)
  tokens    bg surface, score ring success|warning|danger, issue card bg surface-elevated, font mono
  a11y      tablist/tabpanel roles for all 7 tabs (Signal, CDF, Network, Bands, Trace, Sites, Diagnosis), accessible data summaries for CDF P10/P50/P90 and handover tables
  used on   S04
```

## 11. `RouteTimelineScrubber` & `CampaignCompareView`
```
RouteTimelineScrubber
  variants  Map-subview, Timeline-subview, Cells-subview
  sizes     synchronized split view
  states    idle, scrubbing, hover-sample, pause-interval-highlighted, light-probe-spike
  tokens    bg surface, scrubber playhead accent, pause band warning, handover tick tech-5g
  a11y      slider role for timeline scrubber synced with map marker highlight and cell dominance bar
  used on   S04
```

## 12. `SpotlightTourOverlay` & `GlanceWidgetCard`
```
SpotlightTourOverlay & GlanceWidgetCard
  variants  Spotlight steps 1..5, SignalWidget (2x2 / 4x1), CampaignWidget (4x2)
  sizes     widget preview cards (320px width)
  states    step-active, completed, widget-idle, widget-recording
  tokens    bg surface-elevated, border accent, shadow pop, radius lg
  a11y      step counter (1 of 5..5 of 5), Next/Previous/Skip keyboard navigation
  used on   S05
```
