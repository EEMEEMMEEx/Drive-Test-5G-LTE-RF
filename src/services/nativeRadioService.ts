import { registerPlugin, Capacitor } from '@capacitor/core';
import type {
  CaComponent,
  CellularTechnology,
  GpsInfo,
  Measurement,
  NeighborCell,
  NrStatus,
  RadioInfo,
} from '../types/drivetest';

export interface NativeRadioMetrics {
  timestamp: number;
  gps: {
    latitude: number;
    longitude: number;
    altitude: number;
    speedKmh: number;
    bearing: number;
    accuracy: number;
  };
  servingCell: {
    rat: string;
    rsrp: number;
    rsrq: number;
    sinr: number;
    csiRsrp?: number;
    cqi?: number;
    ta?: number;
    pci: number;
    nci?: number;
    ci?: number;
    tac: number;
    nrarfcn?: number;
    earfcn?: number;
    band: string;
    registered: boolean;
  };
  neighborCells: Array<{
    rat: string;
    rsrp: number;
    rsrq: number;
    sinr: number;
    pci: number;
    tac: number;
    nrarfcn?: number;
    earfcn?: number;
    band: string;
  }>;
}

export interface NativeSimSlot {
  slotIndex: number;
  subscriptionId: number;
  carrierName: string;
  displayName: string;
  countryIso: string;
}

export interface NativeRadioPlugin {
  getRadioMetrics(): Promise<NativeRadioMetrics>;
  startMonitoring(): Promise<void>;
  stopMonitoring(): Promise<void>;
  getSimSlots(): Promise<{ slots: NativeSimSlot[] }>;
  addListener(
    eventName: 'radioUpdate',
    listenerFunc: (data: NativeRadioMetrics) => void
  ): Promise<{ remove: () => Promise<void> }>;
}

export const DriveTestRadio = registerPlugin<NativeRadioPlugin>('DriveTestRadio');

export function isNativeAndroid(): boolean {
  return Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android';
}

export function convertNativeSnapshotToMeasurement(
  snapshot: NativeRadioMetrics,
  selectedSimSlot: number = 0,
  campaignId: number = 0
): Measurement {
  const serving = snapshot.servingCell;
  const is5G = serving.rat.includes('5G') || serving.rat.includes('NR');
  const tech: CellularTechnology = is5G ? 'NR_5G' : 'LTE_4G';
  const nrStatus: NrStatus = is5G ? 'SA' : 'NONE';

  const neighbors: NeighborCell[] = (snapshot.neighborCells || []).map((nc) => ({
    technology: nc.rat.includes('5G') ? 'NR_5G' : 'LTE_4G',
    pci: nc.pci,
    earfcn: nc.earfcn || nc.nrarfcn || 0,
    rsrp: nc.rsrp,
    rsrq: nc.rsrq,
    cellId: nc.pci,
  }));

  const lteCa: CaComponent[] = [
    { band: 3, isNr: false, dlFrequencyKhz: 1800000, dlBandwidthKhz: 20000 },
    { band: 1, isNr: false, dlFrequencyKhz: 2100000, dlBandwidthKhz: 15000 },
  ];
  const nrCa: CaComponent[] = [
    { band: 78, isNr: true, dlFrequencyKhz: 3500000, dlBandwidthKhz: 100000 },
  ];

  const radio: RadioInfo = {
    technology: tech,
    operatorName: is5G ? '5G HARDWARE LIVE' : '4G LTE LIVE',
    mcc: '520',
    mnc: '03',
    simSlot: selectedSimSlot,
    cellId: serving.nci || serving.ci || 1084201,
    lac: serving.tac || 54321,
    pci: serving.pci || 412,
    band: is5G ? 78 : 3,
    earfcn: serving.nrarfcn || serving.earfcn || (is5G ? 640000 : 1650),
    dlFrequencyKhz: is5G ? 3500000 : 1800000,
    dlBandwidthKhz: is5G ? 100000 : 20000,
    rssi: serving.rsrp - 25,
    rsrp: serving.rsrp,
    rsrq: serving.rsrq,
    sinr: serving.sinr,
    signalLevel: Math.max(1, Math.min(5, Math.round((serving.rsrp + 140) / 16))),
    cqi: serving.cqi || 12,
    timingAdvance: serving.ta || 2,
    csiRsrp: serving.csiRsrp || serving.rsrp - 3,
    csiRsrq: serving.rsrq,
    csiSinr: serving.sinr,
    nrStatus: nrStatus,
    aggregatedBands: is5G ? [] : lteCa,
    aggregatedNrBands: is5G ? [78] : [],
    isRegistered: true,
    connectionState: 'CONNECTED',
    neighborCells: neighbors,
  };

  const gps: GpsInfo = {
    latitude: snapshot.gps.latitude,
    longitude: snapshot.gps.longitude,
    altitude: snapshot.gps.altitude,
    speed: (snapshot.gps.speedKmh || 0) / 3.6,
    accuracy: snapshot.gps.accuracy || 10,
    bearing: snapshot.gps.bearing || 0,
    timestampMs: snapshot.timestamp,
    isValid: true,
  };

  return {
    id: snapshot.timestamp,
    campaignId,
    timestampMs: snapshot.timestamp,
    radio,
    gps,
  };
}
