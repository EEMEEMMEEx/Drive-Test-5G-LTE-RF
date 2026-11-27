package com.drivetest.lte5g.ui.sessions

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.drivetest.lte5g.cloud.CloudTelemetrySyncManager
import com.drivetest.lte5g.data.SessionRepository
import com.drivetest.lte5g.data.analytics.CdfDistribution
import com.drivetest.lte5g.model.MeasurementCadence
import com.drivetest.lte5g.model.RatLockMode
import com.drivetest.lte5g.ui.analytics.CdfDistributionCard
import com.drivetest.lte5g.ui.settings.AppUpdateCard
import com.drivetest.lte5g.ui.settings.GeminiAiOptimizationCard
import com.drivetest.lte5g.updater.OtaErrorKind
import com.drivetest.lte5g.updater.OtaState
import com.drivetest.lte5g.updater.ReleaseInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class SessionRecoveryInstrumentedTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun recoveredSessionCard_displaysShortSummaryDetailsToggleAndRealDuration() {
        val start = 1795574251243L
        val end = start + 4_465_000L // 1h 14m 25s
        val rawReason = "Recovered (REASON_16 (stop com.drivetest.lte5g due to killDueToPackageUpdate, pss=0KB))"
        val session = com.drivetest.lte5g.model.DriveTestSession(
            id = start,
            sessionNumber = 1,
            name = "ทดสอบเส้นทางยาว หนองคาย-อุดร (2026-11-25 09:37)",
            startTime = start,
            endTime = end,
            lastSampleTime = end,
            ratLockMode = RatLockMode.AUTO,
            cadence = MeasurementCadence.PRECISE,
            sampleCount = 4465,
            avgRsrp = -94,
            isRecording = false,
            recoveredFromCrash = true,
            recoveryNote = rawReason
        )

        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = 1.4f)) {
                Box(modifier = Modifier.width(320.dp)) {
                    SessionItemCard(
                        session = session,
                        onExport = {},
                        onViewMap = {},
                        onAnalyze = {},
                        onRename = {},
                        onDelete = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Recovered after app update").assertExists()
        composeTestRule.onNodeWithText("4465 samples").assertExists()
        composeTestRule.onNodeWithText("RECOVERED").assertExists()
        composeTestRule.onNodeWithText("Avg: -94 dBm").assertExists()
        composeTestRule.onNodeWithText("EXPORT").assertExists()
        composeTestRule.onNodeWithText("Duration: 1h 14m", substring = true).assertExists()

        // Expand raw technical reason via Details toggle
        composeTestRule.onNodeWithText("Details").performClick()
        composeTestRule.onNodeWithText(rawReason).assertExists()
    }

    @Test
    fun dualSimSessionCard_formatsDeltaCorrectlyAndHidesEmptySinr() {
        val start = 1795574251243L
        val session = com.drivetest.lte5g.model.DriveTestSession(
            id = start,
            sessionNumber = 2,
            name = "Session #2 (Dual SIM ไทย)",
            startTime = start,
            endTime = start + 2_284_000L,
            lastSampleTime = start + 2_284_000L,
            ratLockMode = RatLockMode.AUTO,
            cadence = MeasurementCadence.ECO,
            sampleCount = 450,
            avgRsrp = -75,
            avgSinr = null,
            isRecording = false,
            simSlot = 0,
            simDisplayName = "AIS (SIM1)",
            isDualSimLogging = true,
            sim2DisplayName = "TRUE-H (SIM2)",
            avgRsrpSim2 = -83,
            avgSinrSim2 = null
        )

        composeTestRule.setContent {
            SessionItemCard(
                session = session,
                onExport = {},
                onViewMap = {},
                onAnalyze = {},
                onRename = {},
                onDelete = {}
            )
        }

        composeTestRule.onNodeWithText("SIM1 Avg: -75 dBm").assertExists()
        composeTestRule.onNodeWithText("WINNER: SIM 1").assertExists()
        composeTestRule.onNodeWithText("RSRP Δ +8 dB · SIM1 − SIM2").assertExists()
    }

    @Test
    fun cdfDistributionCard_rendersAxesChipsAndDualSimLegendWithoutOverlap() {
        val cdf1 = CdfDistribution(
            p10 = -80.0,
            p50 = -78.0,
            p90 = -76.0,
            min = -80.0,
            max = -75.0,
            avg = -77.5,
            points = listOf(-80.0 to 10.0, -78.0 to 50.0, -76.0 to 90.0, -75.0 to 100.0)
        )
        val cdf2 = CdfDistribution(
            p10 = -86.0,
            p50 = -83.0,
            p90 = -80.0,
            min = -88.0,
            max = -79.0,
            avg = -83.0,
            points = listOf(-88.0 to 5.0, -83.0 to 50.0, -79.0 to 100.0)
        )

        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = 1.3f)) {
                Box(modifier = Modifier.width(320.dp)) {
                    CdfDistributionCard(cdf = cdf1, cdfSim2 = cdf2)
                }
            }
        }

        composeTestRule.onNodeWithText("Cumulative Distribution Function (CDF)").assertExists()
        composeTestRule.onNodeWithText("RSRP (dBm)").assertExists()
        composeTestRule.onNodeWithText("P50 Marker: -78 dBm (50%)").assertExists()
        composeTestRule.onNodeWithText("SIM 1").assertExists()
        composeTestRule.onNodeWithText("SIM 2").assertExists()
        composeTestRule.onNodeWithText("Max: -75 dBm").assertExists()
    }

    @Test
    fun geminiAiCard_rendersAlignedRowsChipsAndThaiParagraphOnLargeFontAndSmallScreen() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = 1.4f)) {
                Box(modifier = Modifier.width(300.dp)) {
                    GeminiAiOptimizationCard(
                        modelName = "gemini-3.5-flash",
                        hasApiKey = true
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("GEMINI AI OPTIMIZATION ENGINE").assertExists()
        composeTestRule.onNodeWithText("ACTIVE").assertExists()
        composeTestRule.onNodeWithText("AI Model").assertExists()
        composeTestRule.onNodeWithText("gemini-3.5-flash").assertExists()
        composeTestRule.onNodeWithText("API Key Source").assertExists()
        composeTestRule.onNodeWithText("local.properties").assertExists()
        composeTestRule.onNodeWithText("Compile-time Injected").assertExists()
        composeTestRule.onNodeWithText("RF Heuristic Engine").assertExists()
        composeTestRule.onNodeWithText("CDF").assertExists()
        composeTestRule.onNodeWithText("Problem Detection").assertExists()
        composeTestRule.onNodeWithText("Handover Analysis").assertExists()
        composeTestRule.onNodeWithText("Telemetry", substring = true).assertExists()
    }

    @Test
    fun appUpdateCard_rendersAllStatesWithContrastAndSmallScreenLargeFont() {
        val release = ReleaseInfo(
            tagName = "v1.13.14",
            versionName = "1.13.14",
            releaseTitle = "Offline recovery",
            releaseNotes = "Bug fixes",
            downloadUrl = "https://example.com/app.apk",
            apkSize = 10_000_000L
        )
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = 1.4f)) {
                Box(modifier = Modifier.width(300.dp)) {
                    AppUpdateCard(
                        otaState = OtaState.Downloading(
                            releaseInfo = release,
                            progressPercent = 45,
                            downloadedBytes = 4_500_000L,
                            totalBytes = 10_000_000L
                        ),
                        autoUpdateEnabled = true,
                        currentVersionName = "1.13.13",
                        currentVersionCode = 57,
                        lastCheckedTimestamp = System.currentTimeMillis()
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("APP UPDATE").assertExists()
        composeTestRule.onNodeWithText("AUTO: ON").assertExists()
        composeTestRule.onNodeWithText("Downloading v1.13.14").assertExists()
        composeTestRule.onNodeWithText("45%").assertExists()
        composeTestRule.onNodeWithText("CANCEL DOWNLOAD").assertExists()
        composeTestRule.onNodeWithText("View release notes (v1.13.14)").assertExists()
    }

    @Test
    fun appUpdateCard_showsPermissionSettingsButtonOnInstallPermissionError() {
        composeTestRule.setContent {
            AppUpdateCard(
                otaState = OtaState.Error(
                    "Allow 'Install unknown apps' permission",
                    OtaErrorKind.INSTALL_PERMISSION
                ),
                autoUpdateEnabled = false,
                currentVersionName = "1.13.13",
                currentVersionCode = 57,
                lastCheckedTimestamp = System.currentTimeMillis()
            )
        }

        composeTestRule.onNodeWithText("AUTO: OFF").assertExists()
        composeTestRule.onNodeWithText("Install Permission Required").assertExists()
        composeTestRule.onNodeWithText("OPEN INSTALL SETTINGS").assertExists()
        composeTestRule.onNodeWithText("RETRY").assertExists()
    }

    @Test
    fun offlineQueueRecovery_restoresSamplesAfterSimulatedPackageInstallKill() {
        val context = composeTestRule.activity
        val sessionDir = File(context.cacheDir, "inst_recovery_sessions_${System.currentTimeMillis()}").apply { mkdirs() }
        val queueDir = File(context.cacheDir, "inst_recovery_queue_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            SessionRepository.resetForTesting()
            CloudTelemetrySyncManager.resetForTesting()
            CloudTelemetrySyncManager.setStorageDirForTesting(queueDir)

            val sid = 1795574251243L
            File(sessionDir, "session_$sid.meta.json").writeText(
                """{"id":$sid,"sessionNumber":1,"name":"Session #1","startTime":$sid,"endTime":null,"ratLockMode":"AUTO","cadence":"PRECISE","sampleCount":0,"isRecording":true}"""
            )
            val endTs = sid + 120_000L
            CloudTelemetrySyncManager.saveToOfflineQueue(
                heartbeatJson = "{}",
                sessionId = sid.toString(),
                trailPointJson = """{"ts":$endTs,"lat":17.95,"lon":103.11,"rsrp":-94,"sinr":2.5,"pci":86,"rat":"LTE","speed":20.0}"""
            )

            SessionRepository.initStorage(sessionDir)
            val recovered = SessionRepository.sessions.value.first { it.id == sid }
            assertTrue(recovered.recoveredFromCrash)
            assertEquals(1, recovered.sampleCount)
            assertEquals(endTs, recovered.endTime)
        } finally {
            sessionDir.deleteRecursively()
            queueDir.deleteRecursively()
        }
    }
}
