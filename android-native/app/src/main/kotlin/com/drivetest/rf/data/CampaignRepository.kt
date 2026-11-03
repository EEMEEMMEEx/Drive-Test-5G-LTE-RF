package com.drivetest.rf.data

import com.drivetest.rf.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

object CampaignRepository {
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _campaigns = MutableStateFlow<List<Campaign>>(emptyList())
    val campaigns: StateFlow<List<Campaign>> = _campaigns.asStateFlow()

    private val _measurementsByCampaign = MutableStateFlow<Map<Long, List<Measurement>>>(emptyMap())
    val measurementsByCampaign: StateFlow<Map<Long, List<Measurement>>> = _measurementsByCampaign.asStateFlow()

    private val _speedTestsByCampaign = MutableStateFlow<Map<Long, List<SpeedTestResult>>>(emptyMap())
    val speedTestsByCampaign: StateFlow<Map<Long, List<SpeedTestResult>>> = _speedTestsByCampaign.asStateFlow()

    private val _selectedCampaignId = MutableStateFlow<Long>(1001L)
    val selectedCampaignId: StateFlow<Long> = _selectedCampaignId.asStateFlow()

    private val _compareCampaignId = MutableStateFlow<Long?>(1002L)
    val compareCampaignId: StateFlow<Long?> = _compareCampaignId.asStateFlow()

    private val _activeCampaignId = MutableStateFlow<Long?>(null)
    val activeCampaignId: StateFlow<Long?> = _activeCampaignId.asStateFlow()

    private val _lastFinishedSummary = MutableStateFlow<CampaignFinishedSummary?>(null)
    val lastFinishedSummary: StateFlow<CampaignFinishedSummary?> = _lastFinishedSummary.asStateFlow()

    private val _latestSpeedTest = MutableStateFlow(
        SpeedTestResult(
            id = 1L,
            campaignId = 1001L,
            downloadMbps = 412.6,
            uploadMbps = 68.4,
            pingMs = 14.2,
            jitterMs = 1.9,
            loadedLatencyMs = 28.5,
            serverLocation = "AIS SuperCORE Bangkok"
        )
    )
    val latestSpeedTest: StateFlow<SpeedTestResult> = _latestSpeedTest.asStateFlow()

    private val _speedTestProgress = MutableStateFlow(SpeedTestProgress())
    val speedTestProgress: StateFlow<SpeedTestProgress> = _speedTestProgress.asStateFlow()

    private val _latestProbe = MutableStateFlow(LightNetworkProbe(pingMs = 19, jitterMs = 2.1, success = true))
    val latestProbe: StateFlow<LightNetworkProbe> = _latestProbe.asStateFlow()

    private val _sessionEvents = MutableStateFlow<List<SessionEvent>>(emptyList())
    val sessionEvents: StateFlow<List<SessionEvent>> = _sessionEvents.asStateFlow()

    private val _canUndoDelete = MutableStateFlow(false)
    val canUndoDelete: StateFlow<Boolean> = _canUndoDelete.asStateFlow()

    private var deletedCampaignsBackup: List<Campaign> = emptyList()
    private var deletedMeasurementsBackup: Map<Long, List<Measurement>> = emptyMap()

    init {
        resetDemoData()
    }

    fun selectCampaign(id: Long) {
        _selectedCampaignId.value = id
    }

    fun setCompareCampaign(id: Long?) {
        _compareCampaignId.value = id
    }

    fun dismissFinishedSummary() {
        _lastFinishedSummary.value = null
    }

    fun startNewCampaign(name: String, preset: CampaignPreset): Campaign {
        val now = System.currentTimeMillis()
        val newCampaign = Campaign(
            id = now,
            name = name.ifBlank { "Drive_${now % 10000}" },
            preset = preset,
            startTimeMs = now,
            endTimeMs = null,
            isCompleted = false,
            measurementCount = 0,
            totalDistanceKm = 0.0,
            avgRsrp = -85,
            minRsrp = -85,
            maxRsrp = -85,
            handoverCount = 0,
            tags = listOf(preset.name, "5G/LTE")
        )
        _campaigns.update { listOf(newCampaign) + it }
        _measurementsByCampaign.update { it + (newCampaign.id to emptyList()) }
        _speedTestsByCampaign.update { it + (newCampaign.id to emptyList()) }
        _activeCampaignId.value = newCampaign.id
        _selectedCampaignId.value = newCampaign.id
        _lastFinishedSummary.value = null
        return newCampaign
    }

    fun appendMeasurement(m: Measurement) {
        val cid = _activeCampaignId.value ?: _selectedCampaignId.value
        val tagged = m.copy(campaignId = cid)
        val currentList = (_measurementsByCampaign.value[cid] ?: emptyList()) + tagged
        _measurementsByCampaign.update { it + (cid to currentList) }
        recalculateCampaignMetadata(cid, currentList, isCompleted = _activeCampaignId.value != cid)
    }

    fun stopActiveCampaign(): CampaignFinishedSummary? {
        val cid = _activeCampaignId.value ?: return null
        val list = _measurementsByCampaign.value[cid] ?: emptyList()
        val now = System.currentTimeMillis()
        recalculateCampaignMetadata(cid, list, isCompleted = true, endTimeMs = now)
        _activeCampaignId.value = null

        val updated = _campaigns.value.find { it.id == cid } ?: return null
        val durationSec = ((now - updated.startTimeMs) / 1000L).coerceAtLeast(1L)
        val summary = CampaignFinishedSummary(
            campaignId = updated.id,
            name = updated.name,
            preset = updated.preset,
            measurementCount = updated.measurementCount,
            distanceKm = updated.totalDistanceKm,
            durationSec = durationSec,
            avgRsrp = updated.avgRsrp,
            minRsrp = updated.minRsrp,
            maxRsrp = updated.maxRsrp,
            handoverCount = updated.handoverCount
        )
        _lastFinishedSummary.value = summary
        return summary
    }

    private fun recalculateCampaignMetadata(
        campaignId: Long,
        measurements: List<Measurement>,
        isCompleted: Boolean,
        endTimeMs: Long? = null
    ) {
        val avgRsrp = if (measurements.isNotEmpty()) measurements.map { it.radio.rsrp }.average().roundToInt() else -95
        val minRsrp = if (measurements.isNotEmpty()) measurements.minOf { it.radio.rsrp } else -115
        val maxRsrp = if (measurements.isNotEmpty()) measurements.maxOf { it.radio.rsrp } else -75
        val distKm = RfAnalyticsEngine.calculateTotalRouteDistanceKm(measurements)
        val hoCount = RfAnalyticsEngine.detectHandovers(measurements).size

        _campaigns.update { list ->
            list.map { c ->
                if (c.id == campaignId) {
                    c.copy(
                        isCompleted = isCompleted,
                        endTimeMs = endTimeMs ?: c.endTimeMs,
                        measurementCount = measurements.size,
                        totalDistanceKm = distKm,
                        avgRsrp = avgRsrp,
                        minRsrp = minRsrp,
                        maxRsrp = maxRsrp,
                        handoverCount = hoCount
                    )
                } else c
            }
        }
    }

    fun deleteCampaigns(ids: Set<Long>) {
        if (ids.isEmpty()) return
        deletedCampaignsBackup = _campaigns.value.filter { it.id in ids }
        deletedMeasurementsBackup = _measurementsByCampaign.value.filterKeys { it in ids }
        _canUndoDelete.value = deletedCampaignsBackup.isNotEmpty()

        _campaigns.update { list -> list.filterNot { it.id in ids } }
        _measurementsByCampaign.update { map -> map.filterKeys { it !in ids } }

        val remaining = _campaigns.value
        if (_selectedCampaignId.value in ids && remaining.isNotEmpty()) {
            _selectedCampaignId.value = remaining.first().id
        }
    }

    fun undoLastDelete() {
        if (!_canUndoDelete.value) return
        _campaigns.update { (deletedCampaignsBackup + it).distinctBy { c -> c.id } }
        _measurementsByCampaign.update { it + deletedMeasurementsBackup }
        deletedCampaignsBackup = emptyList()
        deletedMeasurementsBackup = emptyMap()
        _canUndoDelete.value = false
    }

    fun logSessionEvent(type: String, detail: String) {
        val cid = _activeCampaignId.value ?: _selectedCampaignId.value
        val ev = SessionEvent(
            id = System.currentTimeMillis(),
            campaignId = cid,
            timestampMs = System.currentTimeMillis(),
            type = type,
            detail = detail
        )
        _sessionEvents.update { (listOf(ev) + it).take(100) }
    }

    fun runActiveSpeedTest(currentRadio: RadioInfo) {
        if (_speedTestProgress.value.isRunning) return
        scope.launch {
            _speedTestProgress.value = SpeedTestProgress(isRunning = true, phase = "PING", progressPercent = 18)
            delay(350)

            // Calculate realistic RF-linked performance based on current RSRP & SINR & Tech
            val is5g = currentRadio.technology == CellTechnology.NR_5G
            val rsrpFactor = ((currentRadio.rsrp + 125).coerceIn(5, 65)) / 65.0
            val sinrFactor = ((currentRadio.sinr + 10).coerceIn(2, 40)) / 40.0
            val baseDl = if (is5g) 520.0 else 135.0
            val baseUl = if (is5g) 88.0 else 34.0

            val ping = (if (is5g) 13.0 else 24.0) + (1.0 - rsrpFactor) * 18.0 + Random.nextDouble(-2.0, 3.0)
            val jitter = (1.2 + (1.0 - sinrFactor) * 4.5).coerceAtLeast(0.5)

            _speedTestProgress.value = SpeedTestProgress(isRunning = true, phase = "DOWNLOAD", progressPercent = 56)
            delay(450)
            val dl = (baseDl * (0.45 * rsrpFactor + 0.55 * sinrFactor) + Random.nextDouble(8.0, 28.0)).coerceAtLeast(12.4)

            _speedTestProgress.value = SpeedTestProgress(isRunning = true, phase = "UPLOAD", progressPercent = 85)
            delay(400)
            val ul = (baseUl * (0.5 * rsrpFactor + 0.5 * sinrFactor) + Random.nextDouble(2.5, 9.5)).coerceAtLeast(4.2)
            val loadedLat = ping * (1.55 + (1.0 - sinrFactor) * 1.2)

            val cid = _activeCampaignId.value ?: _selectedCampaignId.value
            val result = SpeedTestResult(
                id = System.currentTimeMillis(),
                campaignId = cid,
                timestampMs = System.currentTimeMillis(),
                downloadMbps = (dl * 10.0).roundToInt() / 10.0,
                uploadMbps = (ul * 10.0).roundToInt() / 10.0,
                pingMs = (ping * 10.0).roundToInt() / 10.0,
                jitterMs = (jitter * 10.0).roundToInt() / 10.0,
                loadedLatencyMs = (loadedLat * 10.0).roundToInt() / 10.0,
                serverLocation = "Bangkok 5G Core"
            )
            _latestSpeedTest.value = result
            val list = (_speedTestsByCampaign.value[cid] ?: emptyList()) + result
            _speedTestsByCampaign.update { it + (cid to list) }
            _speedTestProgress.value = SpeedTestProgress(isRunning = false, phase = "COMPLETE", progressPercent = 100)
            logSessionEvent("SPEED_TEST", "DL ${result.downloadMbps} Mbps · UL ${result.uploadMbps} Mbps · Ping ${result.pingMs} ms")
        }
    }

    fun triggerLightProbe(currentRadio: RadioInfo): LightNetworkProbe {
        val basePing = if (currentRadio.technology == CellTechnology.NR_5G) 17 else 27
        val signalPenalty = ((-85 - currentRadio.rsrp).coerceAtLeast(0) / 4)
        val ping = (basePing + signalPenalty + Random.nextInt(-2, 4)).coerceIn(11, 140)
        val jitter = ((1.4 + signalPenalty * 0.25 + Random.nextDouble(0.0, 1.2)) * 10.0).roundToInt() / 10.0
        val probe = LightNetworkProbe(
            timestampMs = System.currentTimeMillis(),
            pingMs = ping,
            jitterMs = jitter,
            success = true
        )
        _latestProbe.value = probe
        return probe
    }

    fun importCsvCampaign(name: String, measurements: List<Measurement>): Campaign? {
        if (measurements.isEmpty()) return null
        val cid = System.currentTimeMillis()
        val tagged = measurements.mapIndexed { idx, m ->
            m.copy(id = cid + idx, campaignId = cid)
        }
        val avgRsrp = tagged.map { it.radio.rsrp }.average().roundToInt()
        val minRsrp = tagged.minOf { it.radio.rsrp }
        val maxRsrp = tagged.maxOf { it.radio.rsrp }
        val distKm = RfAnalyticsEngine.calculateTotalRouteDistanceKm(tagged)
        val hoCount = RfAnalyticsEngine.detectHandovers(tagged).size

        val campaign = Campaign(
            id = cid,
            name = name,
            preset = CampaignPreset.URBAN_ROUTE,
            startTimeMs = tagged.first().timestampMs,
            endTimeMs = tagged.last().timestampMs,
            isCompleted = true,
            measurementCount = tagged.size,
            totalDistanceKm = distKm,
            avgRsrp = avgRsrp,
            minRsrp = minRsrp,
            maxRsrp = maxRsrp,
            handoverCount = hoCount,
            tags = listOf("IMPORTED", "CSV")
        )
        _campaigns.update { listOf(campaign) + it }
        _measurementsByCampaign.update { it + (cid to tagged) }
        _selectedCampaignId.value = cid
        return campaign
    }

    fun resetDemoData() {
        val now = System.currentTimeMillis()
        val c1Id = 1001L
        val c2Id = 1002L
        val c3Id = 1003L

        val m1 = generateSeedMeasurements(
            campaignId = c1Id,
            baseLat = 13.7462,
            baseLon = 100.5347,
            count = 36,
            baseRsrp = -82,
            speedKmh = 42f,
            operator = "AIS 5G SA",
            startTimeMs = now - 3_600_000L
        )
        val m2 = generateSeedMeasurements(
            campaignId = c2Id,
            baseLat = 13.7295,
            baseLon = 100.7482,
            count = 44,
            baseRsrp = -93,
            speedKmh = 96f,
            operator = "TRUE-H 5G",
            startTimeMs = now - 14_400_000L
        )
        val m3 = generateSeedMeasurements(
            campaignId = c3Id,
            baseLat = 13.7466,
            baseLon = 100.5393,
            count = 28,
            baseRsrp = -98,
            speedKmh = 4.5f,
            operator = "AIS 5G NSA",
            startTimeMs = now - 43_200_000L
        )

        fun buildSeedCampaign(
            id: Long,
            name: String,
            preset: CampaignPreset,
            list: List<Measurement>,
            tags: List<String>
        ): Campaign {
            return Campaign(
                id = id,
                name = name,
                preset = preset,
                startTimeMs = list.first().timestampMs,
                endTimeMs = list.last().timestampMs,
                isCompleted = true,
                measurementCount = list.size,
                totalDistanceKm = RfAnalyticsEngine.calculateTotalRouteDistanceKm(list),
                avgRsrp = list.map { it.radio.rsrp }.average().roundToInt(),
                minRsrp = list.minOf { it.radio.rsrp },
                maxRsrp = list.maxOf { it.radio.rsrp },
                handoverCount = RfAnalyticsEngine.detectHandovers(list).size,
                tags = tags
            )
        }

        val c1 = buildSeedCampaign(c1Id, "Bangkok_Urban_Siam_Asok_5G", CampaignPreset.URBAN_ROUTE, m1, listOf("URBAN_ROUTE", "5G NR", "n78"))
        val c2 = buildSeedCampaign(c2Id, "Motorway7_Suvarnabhumi_Mobility", CampaignPreset.HIGHWAY, m2, listOf("HIGHWAY", "HANDOVER", "CA"))
        val c3 = buildSeedCampaign(c3Id, "CentralWorld_Indoor_Walk_Audit", CampaignPreset.INDOOR, m3, listOf("INDOOR", "SITE_AUDIT"))

        _campaigns.value = listOf(c1, c2, c3)
        _measurementsByCampaign.value = mapOf(
            c1Id to m1,
            c2Id to m2,
            c3Id to m3
        )
        _speedTestsByCampaign.value = mapOf(
            c1Id to listOf(
                SpeedTestResult(101L, c1Id, now - 3_500_000L, 485.4, 74.2, 13.5, 1.8, 26.4, "AIS SuperCORE"),
                SpeedTestResult(102L, c1Id, now - 3_300_000L, 362.1, 61.0, 16.2, 2.3, 34.1, "AIS SuperCORE")
            ),
            c2Id to listOf(
                SpeedTestResult(201L, c2Id, now - 14_200_000L, 218.5, 42.8, 21.0, 3.6, 58.0, "True IDC Edge"),
                SpeedTestResult(202L, c2Id, now - 14_000_000L, 19.4, 11.2, 34.5, 6.8, 96.0, "True IDC Edge")
            ),
            c3Id to listOf(
                SpeedTestResult(301L, c3Id, now - 43_000_000L, 164.0, 38.5, 19.2, 2.9, 44.0, "Bangkok IX")
            )
        )
        _selectedCampaignId.value = c1Id
        _compareCampaignId.value = c2Id
        _canUndoDelete.value = false
    }

    private fun generateSeedMeasurements(
        campaignId: Long,
        baseLat: Double,
        baseLon: Double,
        count: Int,
        baseRsrp: Int,
        speedKmh: Float,
        operator: String,
        startTimeMs: Long
    ): List<Measurement> {
        val list = mutableListOf<Measurement>()
        val pcis = listOf(104, 218, 312, 409, 218)
        val bands = listOf("n78 (3500 MHz)", "n41 (2600 MHz)", "B3 (1800 MHz)", "n78 (3500 MHz)", "B1 (2100 MHz)")
        val techs = listOf(CellTechnology.NR_5G, CellTechnology.NR_5G, CellTechnology.LTE_4G, CellTechnology.NR_5G, CellTechnology.LTE_4G)

        for (i in 0 until count) {
            val sectorIdx = (i / 8).coerceIn(0, pcis.lastIndex)
            val pci = pcis[sectorIdx]
            val band = bands[sectorIdx]
            val tech = techs[sectorIdx]
            val wave = (sin(i * 0.45) * 13.0 + cos(i * 0.25) * 6.0).roundToInt()
            // Inject coverage dip around index 18..21 for diagnostic detection
            val dip = if (i in 18..21) -14 else 0
            val rsrp = (baseRsrp + wave + dip).coerceIn(-122, -66)
            val rsrq = (-10 + (wave / 3) + (if (dip < 0) -5 else 0)).coerceIn(-19, -5)
            val sinr = (16 + wave + dip).coerceIn(-4, 30)
            val cqi = ((sinr + 6) / 2).coerceIn(3, 15)

            val lat = baseLat + (i * 0.00058) + sin(i * 0.22) * 0.00018
            val lon = baseLon + (i * 0.00064) + cos(i * 0.22) * 0.00015

            val neighborBestOffset = if (i in 15..17 || i in 29..31) 8 else -5
            val neighbors = listOf(
                NeighborCell(
                    technology = tech,
                    pci = pcis[(sectorIdx + 1) % pcis.size],
                    arfcn = if (tech == CellTechnology.NR_5G) 633334 else 1650,
                    rsrp = (rsrp + neighborBestOffset).coerceIn(-124, -68),
                    rsrq = (rsrq - 1).coerceIn(-20, -5),
                    cellId = 52003000L + ((sectorIdx + 1) * 11L)
                ),
                NeighborCell(
                    technology = CellTechnology.LTE_4G,
                    pci = 441,
                    arfcn = 1850,
                    rsrp = (rsrp - 9).coerceIn(-128, -75),
                    rsrq = -13,
                    cellId = 52003991L
                ),
                NeighborCell(
                    technology = CellTechnology.NR_5G,
                    pci = 508,
                    arfcn = 520000,
                    rsrp = (rsrp - 12).coerceIn(-130, -78),
                    rsrq = -14,
                    cellId = 52003882L
                )
            )

            val caList = if (tech == CellTechnology.NR_5G) {
                listOf(
                    CaComponent(band = 78, isNr = true, dlFrequencyKhz = 3500000, dlBandwidthKhz = 100000),
                    CaComponent(band = 41, isNr = true, dlFrequencyKhz = 2600000, dlBandwidthKhz = 60000),
                    CaComponent(band = 3, isNr = false, dlFrequencyKhz = 1800000, dlBandwidthKhz = 20000)
                )
            } else {
                listOf(
                    CaComponent(band = 3, isNr = false, dlFrequencyKhz = 1800000, dlBandwidthKhz = 20000),
                    CaComponent(band = 1, isNr = false, dlFrequencyKhz = 2100000, dlBandwidthKhz = 20000)
                )
            }

            list.add(
                Measurement(
                    id = campaignId * 1000L + i,
                    campaignId = campaignId,
                    timestampMs = startTimeMs + (i * 2000L),
                    radio = RadioInfo(
                        technology = tech,
                        operatorName = operator,
                        mcc = "520",
                        mnc = if (operator.contains("TRUE")) "04" else "03",
                        simSlot = 0,
                        cellId = 52003100L + (sectorIdx * 10L) + 1L,
                        tac = 10240 + sectorIdx,
                        pci = pci,
                        band = band,
                        arfcn = if (tech == CellTechnology.NR_5G) 633334 else 1650,
                        dlFrequencyKhz = if (tech == CellTechnology.NR_5G) 3500000 else 1800000,
                        dlBandwidthKhz = if (tech == CellTechnology.NR_5G) 100000 else 20000,
                        rsrp = rsrp,
                        rsrq = rsrq,
                        sinr = sinr,
                        rssi = (rsrp + 22).coerceIn(-115, -50),
                        csiRsrp = rsrp - 1,
                        csiRsrq = rsrq,
                        csiSinr = sinr + 1,
                        cqi = cqi,
                        timingAdvance = 2 + (i % 7),
                        is5gSa = tech == CellTechnology.NR_5G && !operator.contains("NSA"),
                        isConnected = true,
                        carrierAggregation = caList,
                        neighborCells = neighbors
                    ),
                    gps = GpsInfo(
                        latitude = lat,
                        longitude = lon,
                        altitude = 14.5 + sin(i * 0.3) * 2.2,
                        speedKmh = (speedKmh + sin(i * 0.4).toFloat() * 6.5f).coerceAtLeast(0f),
                        bearing = ((45f + i * 3.5f) % 360f),
                        accuracy = 3.4f,
                        satellites = 14,
                        timestampMs = startTimeMs + (i * 2000L),
                        isValid = true
                    )
                )
            )
        }
        return list
    }
}
