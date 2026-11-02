import type {
  Campaign,
  CellularTechnology,
  ConnectionState,
  Measurement,
  SessionEvent,
  SpeedTestResult,
} from '../types/drivetest';

export const APK_CSV_HEADER =
  'timestamp,latitude,longitude,altitude,speed_ms,accuracy_m,bearing,gps_valid,technology,operator,mcc,mnc,cell_id,lac_tac,pci,band,earfcn,rssi_dbm,rsrp_dbm,rsrq_db,sinr_db,registered,connection_state';

function escapeCsvCell(val: string | number | boolean): string {
  const s = String(val);
  if (s.includes(',') || s.includes('"') || s.includes('\n')) {
    return `"${s.replace(/"/g, '""')}"`;
  }
  return s;
}

export function generateMeasurementsCsv(measurements: Measurement[]): string {
  const rows: string[] = [APK_CSV_HEADER];

  for (const m of measurements) {
    const row = [
      m.timestampMs,
      m.gps.latitude.toFixed(6),
      m.gps.longitude.toFixed(6),
      m.gps.altitude.toFixed(1),
      m.gps.speed.toFixed(2),
      m.gps.accuracy.toFixed(1),
      m.gps.bearing.toFixed(1),
      m.gps.isValid ? 1 : 0,
      escapeCsvCell(m.radio.technology),
      escapeCsvCell(m.radio.operatorName),
      escapeCsvCell(m.radio.mcc),
      escapeCsvCell(m.radio.mnc),
      m.radio.cellId,
      m.radio.lac,
      m.radio.pci,
      m.radio.band,
      m.radio.earfcn,
      m.radio.rssi,
      m.radio.rsrp,
      m.radio.rsrq,
      m.radio.sinr.toFixed(1),
      m.radio.isRegistered ? 1 : 0,
      escapeCsvCell(m.radio.connectionState),
    ];
    rows.push(row.join(','));
  }

  return rows.join('\n');
}

export function parseImportedMeasurementsCsv(
  csvText: string,
  targetCampaignId: number
): Measurement[] {
  const lines = csvText
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l.length > 0);
  if (lines.length < 2) return [];

  const results: Measurement[] = [];
  for (let i = 1; i < lines.length; i++) {
    const cols = lines[i].split(',');
    if (cols.length < 21) continue;

    const timestampMs = Number(cols[0]) || Date.now() + i * 1000;
    const latitude = Number(cols[1]) || 13.7563;
    const longitude = Number(cols[2]) || 100.5018;
    const altitude = Number(cols[3]) || 10;
    const speed = Number(cols[4]) || 0;
    const accuracy = Number(cols[5]) || 5;
    const bearing = Number(cols[6]) || 0;
    const isValid = cols[7] !== '0';
    const technology = (cols[8] as CellularTechnology) || 'LTE_4G';
    const operatorName = cols[9] || 'Imported Network';
    const mcc = cols[10] || '520';
    const mnc = cols[11] || '03';
    const cellId = Number(cols[12]) || 1001;
    const lac = Number(cols[13]) || 100;
    const pci = Number(cols[14]) || 1;
    const band = Number(cols[15]) || 3;
    const earfcn = Number(cols[16]) || 1800;
    const rssi = Number(cols[17]) || -70;
    const rsrp = Number(cols[18]) || -90;
    const rsrq = Number(cols[19]) || -10;
    const sinr = Number(cols[20]) || 12;
    const isRegistered = cols[21] !== '0';
    const connectionState = (cols[22] as ConnectionState) || 'CONNECTED';

    results.push({
      id: Date.now() + i,
      campaignId: targetCampaignId,
      timestampMs,
      radio: {
        technology,
        operatorName,
        mcc,
        mnc,
        simSlot: 0,
        cellId,
        lac,
        pci,
        band,
        earfcn,
        dlFrequencyKhz: technology === 'NR_5G' ? 3500000 : 1800000,
        dlBandwidthKhz: technology === 'NR_5G' ? 100000 : 20000,
        rssi,
        rsrp,
        rsrq,
        sinr,
        signalLevel: rsrp >= -85 ? 4 : rsrp >= -95 ? 3 : rsrp >= -105 ? 2 : 1,
        cqi: 12,
        timingAdvance: 2,
        csiRsrp: rsrp,
        csiRsrq: rsrq,
        csiSinr: Math.round(sinr),
        nrStatus: technology === 'NR_5G' ? 'SA' : 'NONE',
        aggregatedBands: [],
        aggregatedNrBands: technology === 'NR_5G' ? [band] : [],
        isRegistered,
        connectionState,
        neighborCells: [],
      },
      gps: {
        latitude,
        longitude,
        altitude,
        speed,
        accuracy,
        bearing,
        timestampMs,
        isValid,
      },
    });
  }

  return results;
}

function escapeXml(str: string): string {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function rsrpToKmlColor(rsrp: number): string {
  // KML color format is aabbggrr
  if (rsrp >= -90) return 'ff5ec522'; // Green
  if (rsrp >= -100) return 'ff16cc84'; // Lime
  if (rsrp >= -110) return 'ff0b9ef5'; // Amber/Orange
  return 'ff4444ef'; // Red
}

export function generateCampaignKml(
  campaign: Campaign,
  measurements: Measurement[]
): string {
  const coords = measurements
    .map((m) => `${m.gps.longitude.toFixed(6)},${m.gps.latitude.toFixed(6)},${m.gps.altitude.toFixed(1)}`)
    .join('\n      ');

  const placemarks = measurements
    .map((m) => {
      const kmlColor = rsrpToKmlColor(m.radio.rsrp);
      const desc = `RSRP: ${m.radio.rsrp} dBm | RSRQ: ${m.radio.rsrq} dB | SINR: ${m.radio.sinr.toFixed(
        1
      )} dB | PCI: ${m.radio.pci} | Band: ${m.radio.band} (${m.radio.technology})`;
      return `  <Placemark>
    <name>${m.radio.rsrp} dBm</name>
    <description>${escapeXml(desc)}</description>
    <Style><IconStyle><color>${kmlColor}</color><scale>0.8</scale><Icon><href>http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png</href></Icon></IconStyle></Style>
    <Point><coordinates>${m.gps.longitude.toFixed(6)},${m.gps.latitude.toFixed(6)},${m.gps.altitude.toFixed(1)}</coordinates></Point>
  </Placemark>`;
    })
    .join('\n');

  return `<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
<Document>
  <name>${escapeXml(campaign.name)}</name>
  <Placemark><name>Track</name><LineString><altitudeMode>clampToGround</altitudeMode>
    <coordinates>
      ${coords}
    </coordinates>
  </LineString></Placemark>
${placemarks}
</Document></kml>`;
}

export function generateCampaignGeoJson(
  campaign: Campaign,
  measurements: Measurement[]
): string {
  const features = measurements.map((m) => ({
    type: 'Feature',
    geometry: {
      type: 'Point',
      coordinates: [m.gps.longitude, m.gps.latitude, m.gps.altitude],
    },
    properties: {
      campaign_id: campaign.id,
      campaign_name: campaign.name,
      timestamp: m.timestampMs,
      technology: m.radio.technology,
      nrStatus: m.radio.nrStatus,
      operator: m.radio.operatorName,
      cellId: m.radio.cellId,
      pci: m.radio.pci,
      band: m.radio.band,
      earfcn: m.radio.earfcn,
      rsrp: m.radio.rsrp,
      rsrq: m.radio.rsrq,
      sinr: m.radio.sinr,
      rssi: m.radio.rssi,
      speed_ms: m.gps.speed,
    },
  }));

  return JSON.stringify(
    {
      type: 'FeatureCollection',
      name: campaign.name,
      features,
    },
    null,
    2
  );
}

export function generateAdvancedEventsCsv(
  campaign: Campaign,
  speedTests: SpeedTestResult[],
  sessionEvents: SessionEvent[]
): string {
  const rows: string[] = [
    'event_category,timestamp,latitude,longitude,technology,cell_id,ping_ms,jitter_ms,download_mbps,upload_mbps,loaded_ping_ms,status',
  ];

  for (const st of speedTests) {
    rows.push(
      [
        'SPEED_TEST',
        st.timestampMs,
        st.latitude.toFixed(6),
        st.longitude.toFixed(6),
        st.technology,
        st.cellId,
        st.pingMs.toFixed(1),
        st.jitterMs.toFixed(1),
        st.downloadMbps.toFixed(2),
        st.uploadMbps.toFixed(2),
        st.loadedPingMs.toFixed(1),
        st.status,
      ].join(',')
    );
  }

  for (const ev of sessionEvents) {
    rows.push(
      [
        ev.type,
        ev.timestampMs,
        ev.latitude.toFixed(6),
        ev.longitude.toFixed(6),
        ev.technology,
        ev.cellId,
        ev.pingMs.toFixed(1),
        ev.jitterMs.toFixed(1),
        0,
        0,
        0,
        ev.success ? 'SUCCESS' : 'PAUSE',
      ].join(',')
    );
  }

  return rows.join('\n');
}

export function triggerFileDownload(filename: string, content: string, mimeType: string): void {
  const blob = new Blob([content], { type: mimeType });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

