import { createInitialSeedData, type SeedDatabaseState } from './seedData';

const STORAGE_KEY = 'rf_field_suite_db_v2';

export function getEmptyDatabaseState(): SeedDatabaseState {
  return {
    campaigns: [],
    measurements: [],
    speedTests: [],
    sessionEvents: [],
  };
}

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
    // Fallback to empty clean state if storage is unavailable or corrupted
  }
  const empty = getEmptyDatabaseState();
  saveDatabaseState(empty);
  return empty;
}

export function saveDatabaseState(state: SeedDatabaseState): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    // Ignore storage quota errors in restricted environments
  }
}

export function clearDatabaseState(): SeedDatabaseState {
  const empty = getEmptyDatabaseState();
  saveDatabaseState(empty);
  return empty;
}

export function resetDatabaseToSeed(): SeedDatabaseState {
  const seed = createInitialSeedData();
  saveDatabaseState(seed);
  return seed;
}
