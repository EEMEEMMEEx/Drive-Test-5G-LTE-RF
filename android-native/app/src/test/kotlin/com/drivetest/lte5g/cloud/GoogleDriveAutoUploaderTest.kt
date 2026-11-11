package com.drivetest.lte5g.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GoogleDriveAutoUploaderTest {

    @Before
    fun setUp() {
        CloudConfigRepository.resetForTesting()
    }

    @Test
    fun cloudUploadStatus_defaultsToNone() {
        assertEquals(CloudUploadStatus.NONE, CloudConfigRepository.getSessionUploadStatus(12345L))
    }

    @Test
    fun setSessionUploadStatus_updatesState() {
        CloudConfigRepository.setSessionUploadStatus(9999L, CloudUploadStatus.PENDING_WIFI)
        assertEquals(CloudUploadStatus.PENDING_WIFI, CloudConfigRepository.getSessionUploadStatus(9999L))

        CloudConfigRepository.setSessionUploadStatus(9999L, CloudUploadStatus.UPLOADING)
        assertEquals(CloudUploadStatus.UPLOADING, CloudConfigRepository.getSessionUploadStatus(9999L))

        CloudConfigRepository.setSessionUploadStatus(9999L, CloudUploadStatus.UPLOADED)
        assertEquals(CloudUploadStatus.UPLOADED, CloudConfigRepository.getSessionUploadStatus(9999L))
    }

    @Test
    fun cloudConfig_updatesCorrectly() {
        CloudConfigRepository.updateVehicleInfo("DT-BKK-02", "Unit BKK North")
        assertEquals("DT-BKK-02", CloudConfigRepository.config.value.vehicleId)
        assertEquals("Unit BKK North", CloudConfigRepository.config.value.vehicleName)

        CloudConfigRepository.updateFirebaseConfig("https://my-rtdb.firebaseio.com", "secret456", true)
        assertEquals("https://my-rtdb.firebaseio.com", CloudConfigRepository.config.value.firebaseDbUrl)
        assertEquals("secret456", CloudConfigRepository.config.value.firebaseAuthToken)
        assertTrue(CloudConfigRepository.config.value.liveTelemetryEnabled)

        CloudConfigRepository.updateGoogleDriveConfig("ya29.test", "folder_xyz", false)
        assertEquals("ya29.test", CloudConfigRepository.config.value.googleDriveAccessToken)
        assertEquals("folder_xyz", CloudConfigRepository.config.value.googleDriveFolderId)
        assertEquals(false, CloudConfigRepository.config.value.autoUploadOnWifi)
    }
}
