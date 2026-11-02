# Test Plan: RF Field Suite (`v1.0.0`)

Build: `v1.0.0` · Date: `2026-11-02` · Env: Local Vite + Vitest + Seed Data

| Case | Flow | Type | Steps | Expected | Auto | Result |
| --- | --- | --- | --- | --- | --- | --- |
| `F01-H1` | Record Live Drive Test | Happy | Click `Quick Start (URBAN ROUTE)` or open `Start Drive Test`, enter name, select preset, start, pause, stop | Live RSRP gauge updates, sample count increments, stopping shows `Test Finished` summary | Vitest UI + Unit | `PASS` |
| `F01-E1` | Weak Signal Alert | Edge | Set RSRP alert threshold to `-70 dBm` while drive test is active | `Weak Signal Alert` banner appears with serving Cell ID and PCI | Vitest UI | `PASS` |
| `F02-H1` | Route Map & Overlays | Happy | Open `Route Map`, switch color mode (`BY_RSRP` -> `BY_SINR`), toggle `Handover Events` & `Missed Handovers`, click point | Map markers recolor, point inspector displays full RF + Neighbor Cell metrics | Vitest UI | `PASS` |
| `F03-H1` | Campaign Export & Import | Happy | Open `Campaigns`, export CSV / KML / GeoJSON, import CSV log, delete campaign and click `Undo` | Valid CSV (23 columns), OpenGIS KML 2.2, and GeoJSON generated; deleted campaign restored on Undo | Vitest Unit + UI | `PASS` |
| `F04-H1` | 8-Tab Analytics & Diagnostics | Happy | Open `Analytics`, switch through `Signal`, `CDF`, `Network`, `Bands`, `Trace`, `Sites & Mobility`, `Diagnosis`, `Advanced Correlation` | Accurate SignalStats, CDF percentiles, BandUsage, Handover/Ping-Pong (<30s) counts, and 0–100 Route Diagnosis score | Vitest Unit + UI | `PASS` |
| `F04-E1` | Campaign Comparison | Edge | Select Reference Campaign on overlapping route vs non-overlapping route | Overlapping routes show spatial RSRP/RSRQ/SINR delta & improved %; non-overlapping routes show `NO_OVERLAP` warning | Vitest Unit + UI | `PASS` |
| `F05-H1` | Widgets HUD & Spotlight Guide | Happy | Open `Widgets HUD` and `Interactive Guide`, step through all 5 tutorial steps | Both `GSM Signal Widget` and `GSM Campaign Widget` render live state and trigger campaign start/stop | Vitest UI | `PASS` |

