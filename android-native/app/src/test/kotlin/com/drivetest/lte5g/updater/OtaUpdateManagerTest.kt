package com.drivetest.lte5g.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaUpdateManagerTest {

    @Test
    fun testCleanVersionString() {
        assertEquals("1.4.2", OtaUpdateManager.cleanVersionString("v1.4.2-android"))
        assertEquals("1.5.0", OtaUpdateManager.cleanVersionString("v1.5.0"))
        assertEquals("1.9.3", OtaUpdateManager.cleanVersionString("v1.9.3-android"))
        assertEquals("2.0.0", OtaUpdateManager.cleanVersionString("2.0.0-rc1"))
        assertEquals("1.0.0", OtaUpdateManager.cleanVersionString("  v1.0.0  "))
    }

    @Test
    fun testCompareVersions() {
        // Newer versions
        assertTrue(OtaUpdateManager.compareVersions("1.5.0", "1.4.2") > 0)
        assertTrue(OtaUpdateManager.compareVersions("v1.5.0", "1.4.2") > 0)
        assertTrue(OtaUpdateManager.compareVersions("1.9.3", "1.9.0") > 0)
        assertTrue(OtaUpdateManager.compareVersions("v1.9.3-android", "1.9.0") > 0)
        assertTrue(OtaUpdateManager.compareVersions("2.0.0", "1.9.9") > 0)
        assertTrue(OtaUpdateManager.compareVersions("1.4.10", "1.4.2") > 0)
        assertTrue(OtaUpdateManager.compareVersions("1.13.3", "1.13.2") > 0)

        // Older versions
        assertTrue(OtaUpdateManager.compareVersions("1.4.2", "1.5.0") < 0)
        assertTrue(OtaUpdateManager.compareVersions("1.9.0", "1.9.3") < 0)
        assertTrue(OtaUpdateManager.compareVersions("1.4.1", "v1.4.2-android") < 0)

        // Equal versions
        assertEquals(0, OtaUpdateManager.compareVersions("1.4.2", "1.4.2"))
        assertEquals(0, OtaUpdateManager.compareVersions("v1.4.2-android", "1.4.2"))
        assertEquals(0, OtaUpdateManager.compareVersions("v1.9.3-android", "1.9.3"))
        assertEquals(0, OtaUpdateManager.compareVersions("1.5.0", "1.5.0"))
    }

    @Test
    fun testParseReleaseJson_validReleaseWithApk() {
        val sampleJson = """
        {
          "tag_name": "v1.5.0-android",
          "name": "Drive Test LTE 5G v1.5.0 (OTA Updates)",
          "body": "### Release Highlights\n- Added In-App OTA Update system.",
          "assets": [
            {
              "name": "drive-test-lte-5g-v1.5.0.apk",
              "browser_download_url": "https://github.com/EEMEEMMEEx/Drive-Test-5G-LTE-RF/releases/download/v1.5.0-android/drive-test-lte-5g-v1.5.0.apk",
              "size": 65123456
            },
            {
              "name": "checksums.txt",
              "browser_download_url": "https://github.com/.../checksums.txt",
              "size": 128
            }
          ]
        }
        """.trimIndent()

        val release = OtaUpdateManager.parseReleaseJson(sampleJson)
        assertNotNull(release)
        assertEquals("v1.5.0-android", release!!.tagName)
        assertEquals("1.5.0", release.versionName)
        assertEquals("Drive Test LTE 5G v1.5.0 (OTA Updates)", release.releaseTitle)
        assertEquals(65123456L, release.apkSize)
        assertEquals(
            "https://github.com/EEMEEMMEEx/Drive-Test-5G-LTE-RF/releases/download/v1.5.0-android/drive-test-lte-5g-v1.5.0.apk",
            release.downloadUrl
        )
    }

    @Test
    fun testParseReleaseJson_noApkAssetReturnsNull() {
        val sampleJson = """
        {
          "tag_name": "v1.5.0-web",
          "name": "Web Release",
          "body": "Web only update",
          "assets": [
            {
              "name": "source.zip",
              "browser_download_url": "https://github.com/.../source.zip",
              "size": 1024
            }
          ]
        }
        """.trimIndent()

        val release = OtaUpdateManager.parseReleaseJson(sampleJson)
        assertNull(release)
    }

    @Test
    fun testDecideUpdateAction_immediateWinsOverFlexible() {
        assertEquals(
            PlayUpdateAction.START_IMMEDIATE,
            PlayUpdateManager.decide(RemoteUpdateStatus.AVAILABLE, immediateAllowed = true, flexibleAllowed = true)
        )
        assertEquals(
            PlayUpdateAction.START_IMMEDIATE,
            PlayUpdateManager.decide(RemoteUpdateStatus.AVAILABLE, immediateAllowed = true, flexibleAllowed = false)
        )
    }

    @Test
    fun testDecideUpdateAction_flexibleAndResume() {
        assertEquals(
            PlayUpdateAction.START_FLEXIBLE,
            PlayUpdateManager.decide(RemoteUpdateStatus.AVAILABLE, immediateAllowed = false, flexibleAllowed = true)
        )
        // A flow already in progress must be resumed, not restarted
        assertEquals(
            PlayUpdateAction.COMPLETE_PENDING,
            PlayUpdateManager.decide(RemoteUpdateStatus.IN_PROGRESS, immediateAllowed = true, flexibleAllowed = true)
        )
    }

    @Test
    fun testDecideUpdateAction_noUpdateOrNotFromPlay() {
        assertEquals(
            PlayUpdateAction.UP_TO_DATE,
            PlayUpdateManager.decide(RemoteUpdateStatus.NOT_AVAILABLE, immediateAllowed = false, flexibleAllowed = false)
        )
        // Update available but Play allows neither flow: only possible for non-Play installs
        assertEquals(
            PlayUpdateAction.NOT_FROM_PLAY,
            PlayUpdateManager.decide(RemoteUpdateStatus.AVAILABLE, immediateAllowed = false, flexibleAllowed = false)
        )
    }

    @Test
    fun testDecideUpdateAction_sideloadedInstallIsNotReportedAsUpToDate() {
        // Play says nothing is pending, but the app was not installed by Play:
        // the user must be told to install once from Play, not shown "up to date".
        assertEquals(
            PlayUpdateAction.NOT_FROM_PLAY,
            PlayUpdateManager.decide(
                RemoteUpdateStatus.NOT_AVAILABLE,
                immediateAllowed = false,
                flexibleAllowed = false,
                installedFromPlay = false
            )
        )
        // Same status for a Play install stays up to date
        assertEquals(
            PlayUpdateAction.UP_TO_DATE,
            PlayUpdateManager.decide(
                RemoteUpdateStatus.NOT_AVAILABLE,
                immediateAllowed = false,
                flexibleAllowed = false,
                installedFromPlay = true
            )
        )
        // A running flow is resumed even for a sideloaded install
        assertEquals(
            PlayUpdateAction.COMPLETE_PENDING,
            PlayUpdateManager.decide(
                RemoteUpdateStatus.IN_PROGRESS,
                immediateAllowed = false,
                flexibleAllowed = false,
                installedFromPlay = false
            )
        )
    }

    @Test
    fun testIsInstalledFromPlay_onlyAcceptsPlayStoreInstaller() {
        assertTrue(PlayUpdateManager.isInstalledFromPlay("com.android.vending"))
        assertFalse(PlayUpdateManager.isInstalledFromPlay("com.google.android.packageinstaller"))
        assertFalse(PlayUpdateManager.isInstalledFromPlay(null))
    }

    @Test
    fun testPlayStoreUrlBuiltFromPackageName() {
        val url = PlayUpdateManager.playStoreUrl("com.drivetest.lte5g")
        assertEquals("https://play.google.com/store/apps/details?id=com.drivetest.lte5g", url)
        assertTrue(url.startsWith("https://"))
        assertFalse(url.contains("null"))
    }
}