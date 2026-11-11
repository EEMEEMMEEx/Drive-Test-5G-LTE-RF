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
        CloudConfigRepository.setVehicleIdImmediate("dt-bkk-02")
        assertEquals("DT-BKK-02", CloudConfigRepository.config.value.vehicleId)
        assertTrue(CloudConfigRepository.config.value.liveTelemetryEnabled)
        assertTrue(CloudConfigRepository.config.value.autoUploadOnWifi)
    }

    @Test
    fun vehicleId_normalizesTrimAndUppercase() {
        CloudConfigRepository.setVehicleIdImmediate("  dt-07  ")
        assertEquals("DT-07", CloudConfigRepository.config.value.vehicleId)
    }
}
