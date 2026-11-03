export type SupportedLocale = 'en' | 'th';

export interface TranslationDictionary {
  appTitle: string;
  appSubtitle: string;
  navDashboard: string;
  navMap: string;
  navCampaigns: string;
  navAnalytics: string;
  startDriveTest: string;
  stopDriveTest: string;
  pauseDriveTest: string;
  resumeDriveTest: string;
  newDriveTest: string;
  campaignName: string;
  campaignType: string;
  presetUrbanRoute: string;
  presetHighway: string;
  presetSiteAudit: string;
  presetIndoor: string;
  simSelector: string;
  measurementCadence: string;
  cadenceEco: string;
  cadenceStandard: string;
  cadencePrecise: string;
  ratSelector: string;
  ratModeAuto: string;
  ratMode5gOnly: string;
  ratMode4gOnly: string;
  weakSignalAlert: string;
  rsrpThresholdLabel: string;
  servingCell: string;
  carrierAggregation: string;
  neighborCells: string;
  gpsTelemetry: string;
  speedTestTitle: string;
  runSpeedTest: string;
  speedTestRunning: string;
  lightProbeTitle: string;
  carMode: string;
  exitCarMode: string;
  mapOptions: string;
  markerColorMode: string;
  signalPalette: string;
  pointSize: string;
  showCurrentCell: string;
  showNeighborCells: string;
  showHandovers: string;
  showMissedHandovers: string;
  searchCampaigns: string;
  exportCsv: string;
  exportKml: string;
  exportGeoJson: string;
  exportPdf: string;
  importCsv: string;
  deleteCampaign: string;
  undoDelete: string;
  clearAllData: string;
  clearTelemetry: string;
  tabSignal: string;
  tabCdf: string;
  tabNetwork: string;
  tabBands: string;
  tabTrace: string;
  tabSites: string;
  tabDiagnostic: string;
  tabAdvanced: string;
  compareCampaigns: string;
  widgetsPreview: string;
  replayTutorial: string;
}

export const TRANSLATIONS: Record<SupportedLocale, TranslationDictionary> = {
  en: {
    appTitle: 'RF Field Suite',
    appSubtitle: '5G NR / LTE Drive Test & Route Diagnostics',
    navDashboard: 'Live Monitor',
    navMap: 'Route Map',
    navCampaigns: 'Campaigns',
    navAnalytics: 'Analytics',
    startDriveTest: 'Start Drive Test',
    stopDriveTest: 'Stop Session',
    pauseDriveTest: 'Pause',
    resumeDriveTest: 'Resume',
    newDriveTest: 'New Drive Test Session',
    campaignName: 'Campaign Name',
    campaignType: 'Route Profile',
    presetUrbanRoute: 'Urban Route',
    presetHighway: 'Highway Corridor',
    presetSiteAudit: 'Sector Site Audit',
    presetIndoor: 'Indoor Walk Test',
    simSelector: 'Active SIM Slot',
    measurementCadence: 'Sampling Interval',
    cadenceEco: 'Eco · 5 s',
    cadenceStandard: 'Standard · 2 s',
    cadencePrecise: 'Precise · 1 s',
    ratSelector: 'RAT Lock',
    ratModeAuto: 'Auto (5G/4G)',
    ratMode5gOnly: '5G NR Only',
    ratMode4gOnly: '4G LTE Only',
    weakSignalAlert: 'Weak Signal Alert',
    rsrpThresholdLabel: 'RSRP Alert Threshold (dBm)',
    servingCell: 'Serving Cell & Radio Parameters',
    carrierAggregation: 'Carrier Aggregation (5G NR / LTE CA)',
    neighborCells: 'Neighboring Cells',
    gpsTelemetry: 'GNSS / GPS Telemetry',
    speedTestTitle: 'Active Throughput & Latency Test',
    runSpeedTest: 'Run Speed Test',
    speedTestRunning: 'Testing Throughput…',
    lightProbeTitle: 'Continuous Latency Probe',
    carMode: 'Car HUD Mode',
    exitCarMode: 'Exit HUD',
    mapOptions: 'Map Layer Controls',
    markerColorMode: 'Color Metric',
    signalPalette: 'Color Palette',
    pointSize: 'Marker Radius',
    showCurrentCell: 'Serving Sector Lines',
    showNeighborCells: 'Neighbor Candidates',
    showHandovers: 'Handover Events',
    showMissedHandovers: 'Missed Handovers',
    searchCampaigns: 'Filter campaigns by name or preset…',
    exportCsv: 'Export CSV',
    exportKml: 'Export KML',
    exportGeoJson: 'Export GeoJSON',
    exportPdf: 'Print / PDF Report',
    importCsv: 'Import CSV Log',
    deleteCampaign: 'Delete',
    undoDelete: 'Undo Delete',
    clearAllData: 'Clear All Data',
    clearTelemetry: 'Clear Live Telemetry',
    tabSignal: 'Signal',
    tabCdf: 'CDF',
    tabNetwork: 'Network',
    tabBands: 'Bands',
    tabTrace: 'Trace',
    tabSites: 'Sites & Mobility',
    tabDiagnostic: 'Diagnosis',
    tabAdvanced: 'Advanced Correlation',
    compareCampaigns: 'Campaign Comparison',
    widgetsPreview: 'Widgets HUD',
    replayTutorial: 'Interactive Guide',
  },
  th: {
    appTitle: 'RF Field Suite',
    appSubtitle: 'ระบบทดสอบสัญญาณ 5G NR / LTE Drive Test และวิเคราะห์เส้นทาง',
    navDashboard: 'มอนิเตอร์สด',
    navMap: 'แผนที่เส้นทาง',
    navCampaigns: 'แคมเปญ',
    navAnalytics: 'วิเคราะห์ผล',
    startDriveTest: 'เริ่ม Drive Test',
    stopDriveTest: 'จบการบันทึก',
    pauseDriveTest: 'พักชั่วคราว',
    resumeDriveTest: 'บันทึกต่อ',
    newDriveTest: 'สร้างแคมเปญ Drive Test ใหม่',
    campaignName: 'ชื่อแคมเปญ',
    campaignType: 'โปรไฟล์เส้นทาง',
    presetUrbanRoute: 'เส้นทางในเมือง (Urban)',
    presetHighway: 'ทางหลวง (Highway)',
    presetSiteAudit: 'ตรวจสอบสถานีฐาน (Site Audit)',
    presetIndoor: 'ภายในอาคาร (Indoor)',
    simSelector: 'ซิมการ์ดที่ใช้ทดสอบ',
    measurementCadence: 'ความถี่การเก็บข้อมูล',
    cadenceEco: 'ประหยัด · 5 วินาที',
    cadenceStandard: 'มาตรฐาน · 2 วินาที',
    cadencePrecise: 'ละเอียด · 1 วินาที',
    ratSelector: 'ล็อคเทคโนโลยี (RAT)',
    ratModeAuto: 'อัตโนมัติ (5G/4G)',
    ratMode5gOnly: 'เฉพาะ 5G NR',
    ratMode4gOnly: 'เฉพาะ 4G LTE',
    weakSignalAlert: 'แจ้งเตือนสัญญาณอ่อน',
    rsrpThresholdLabel: 'เกณฑ์แจ้งเตือน RSRP (dBm)',
    servingCell: 'พารามิเตอร์สถานีฐานหลัก (Serving Cell)',
    carrierAggregation: 'การรวมคลื่นความถี่ (Carrier Aggregation)',
    neighborCells: 'สถานีฐานข้างเคียง (Neighbor Cells)',
    gpsTelemetry: 'ข้อมูลพิกัดและการเคลื่อนที่ (GPS)',
    speedTestTitle: 'ทดสอบความเร็วและค่าความหน่วง',
    runSpeedTest: 'รัน Speed Test',
    speedTestRunning: 'กำลังทดสอบความเร็ว…',
    lightProbeTitle: 'ตรวจสอบความหน่วงต่อเนื่อง (Light Probe)',
    carMode: 'โหมดหน้าปัดรถยนต์ (HUD)',
    exitCarMode: 'ออกจากโหมดรถยนต์',
    mapOptions: 'ตั้งค่าชั้นข้อมูลแผนที่',
    markerColorMode: 'โหมดสีตามพารามิเตอร์',
    signalPalette: 'ชุดสีสัญญาณ',
    pointSize: 'ขนาดจุดวัด',
    showCurrentCell: 'แสดงเส้นเชื่อมสถานีฐานหลัก',
    showNeighborCells: 'แสดงสถานีฐานข้างเคียง',
    showHandovers: 'แสดงจุดเปลี่ยนสถานี (Handover)',
    showMissedHandovers: 'แสดงจุดพลาด Handover',
    searchCampaigns: 'ค้นหาชื่อแคมเปญ…',
    exportCsv: 'ส่งออก CSV',
    exportKml: 'ส่งออก KML',
    exportGeoJson: 'ส่งออก GeoJSON',
    exportPdf: 'พิมพ์รายงาน PDF',
    importCsv: 'นำเข้าไฟล์ CSV',
    deleteCampaign: 'ลบแคมเปญ',
    undoDelete: 'เลิกทำ',
    clearAllData: 'ล้างค่าทั้งหมด',
    clearTelemetry: 'ล้างค่ามาตรวัด',
    tabSignal: 'สัญญาณ',
    tabCdf: 'การแจกแจง CDF',
    tabNetwork: 'เทคโนโลยี',
    tabBands: 'คลื่นความถี่',
    tabTrace: 'แผนภาพเส้นทาง',
    tabSites: 'สถานีฐาน & Handover',
    tabDiagnostic: 'วินิจฉัยเส้นทาง',
    tabAdvanced: 'วิเคราะห์เชิงลึก',
    compareCampaigns: 'เปรียบเทียบแคมเปญ',
    widgetsPreview: 'วิดเจ็ตหน้าจอหลัก',
    replayTutorial: 'คู่มือแนะนำ',
  },
};

export function getTranslation(locale: string): TranslationDictionary {
  return TRANSLATIONS[(locale as SupportedLocale)] ?? TRANSLATIONS.en;
}
