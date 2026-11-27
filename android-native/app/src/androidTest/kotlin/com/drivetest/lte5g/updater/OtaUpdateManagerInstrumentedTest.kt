package com.drivetest.lte5g.updater

import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OtaUpdateManagerInstrumentedTest {

    @Test
    fun installedAppVersionMatchesLivePackageInfo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager
        val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        val reported = OtaUpdateManager.getInstalledAppVersion(context)
        assertEquals(pkgInfo.versionName, reported.versionName)
        assertEquals(PackageInfoCompat.getLongVersionCode(pkgInfo), reported.versionCode)
    }

    @Test
    fun fileProviderBuildsValidContentUriForUpdatesCache() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val dummyApk = File(updatesDir, "update_test.apk")
        dummyApk.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        try {
            val uri = OtaUpdateManager.buildApkContentUri(context, dummyApk)
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.provider", uri.authority)
            assertTrue(uri.toString().endsWith("/update_test.apk"))
        } finally {
            dummyApk.delete()
        }
    }

    @Test
    fun verifyApkArchiveRejectsInvalidCorruptOrStaleApk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val badApk = File(updatesDir, "update_9.9.9.apk")
        badApk.writeText("not-a-valid-apk")
        try {
            val release = ReleaseInfo(
                tagName = "v9.9.9-android",
                versionName = "9.9.9",
                releaseTitle = "Test",
                releaseNotes = "",
                downloadUrl = "https://example.com/app.apk",
                apkSize = badApk.length(),
                expectedSha256 = "0".repeat(64)
            )
            val shaMismatch = OtaUpdateManager.verifyApkArchive(context, badApk, release)
            assertTrue(shaMismatch is ApkVerificationResult.Invalid)
            assertTrue((shaMismatch as ApkVerificationResult.Invalid).reason.contains("SHA-256"))

            val noShaRelease = release.copy(expectedSha256 = null)
            val corruptResult = OtaUpdateManager.verifyApkArchive(context, badApk, noShaRelease)
            assertTrue(corruptResult is ApkVerificationResult.Invalid)
            assertTrue((corruptResult as ApkVerificationResult.Invalid).reason.contains("valid Android APK"))

            // Copy currently installed APK and verify versionCode <= installed is rejected
            val installedApk = File(context.applicationInfo.sourceDir)
            val installedVer = OtaUpdateManager.getInstalledAppVersion(context)
            val sameVersionRelease = release.copy(
                versionName = installedVer.versionName,
                expectedSha256 = null
            )
            val downgradeResult = OtaUpdateManager.verifyApkArchive(context, installedApk, sameVersionRelease)
            assertTrue(downgradeResult is ApkVerificationResult.Invalid)
            assertTrue((downgradeResult as ApkVerificationResult.Invalid).reason.contains("not newer"))
        } finally {
            badApk.delete()
        }
    }

    @Test
    fun cleanupDownloadedApksRemovesApkAndPartFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updatesDir, "update_1.13.14.apk").apply { writeText("apk") }
        val partFile = File(updatesDir, "update_1.13.14.apk.part").apply { writeText("part") }
        assertTrue(apkFile.exists())
        assertTrue(partFile.exists())

        OtaUpdateManager.cleanupDownloadedApks(context)

        assertFalse(apkFile.exists())
        assertFalse(partFile.exists())
    }
}
