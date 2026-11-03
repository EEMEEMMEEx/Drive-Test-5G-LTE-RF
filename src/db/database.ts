import { createInitialSeedData, type SeedDatabaseState } from './seedData';

const STORAGE_KEY = 'rf_field_suite_db_v1';

export function loadDatabaseState(): SeedDatabaseState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as SeedDatabaseState;
      if (Array.isArray(parsed.campaigns)) {
        return parsed;
      }
    }
  } catch {
    // Fallback to seed data if storage is unavailable or corrupted
  }
  const initial = createInitialSeedData();
  saveDatabaseState(initial);
  return initial;
}

export function saveDatabaseState(state: SeedDatabaseState): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    // Ignore storage quota errors in restricted environments
  }
}

export function clearDatabaseState(): SeedDatabaseState {
  const empty: SeedDatabaseState = {
    campaigns: [],
    measurements: [],
    speedTests: [],
    sessionEvents: [],
  };
  saveDatabaseState(empty);
  return empty;
}

export function resetDatabaseToSeed(): SeedDatabaseState {
  const seed = createInitialSeedData();
  saveDatabaseState(seed);
  return seed;
}

