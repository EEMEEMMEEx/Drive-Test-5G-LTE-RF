package com.drivetest.lte5g.cloud

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection

class CloudConfigRepositoryTest {

    private class FakeFleetRegistryTransport : FleetRegistryTransport {
        val nodes = mutableMapOf<String, JSONObject>()
        val etags = mutableMapOf<String, String>()
        var failWithIoException = false
        var failOnceWith412ForId: String? = null
        var on412ConcurrentOwnerId: String = "other-racer-device"
        var on412Timestamp: Long = 1_000_000L
        var putCount = 0

        private var versionCounter = 1

        fun putNode(id: String, json: JSONObject) {
            nodes[id] = json
            etags[id] = "etag-${versionCounter++}"
        }

        private fun extractVehicleId(urlString: String): String? {
            val match = Regex("""/fleet/vehicles/([^/.]+)\.json""").find(urlString)
            return match?.groupValues?.get(1)
        }

        override fun getWithEtag(urlString: String): FirebaseGetResponse {
            if (failWithIoException) throw IOException("Network unreachable")

            val id = extractVehicleId(urlString)
            if (id != null) {
                val node = nodes[id]
                return if (node == null) {
                    FirebaseGetResponse(200, "null", "null_etag")
                } else {
                    FirebaseGetResponse(200, node.toString(), etags[id] ?: "etag-0")
                }
            }

            // Fleet root query: /fleet/vehicles.json
            val root = JSONObject()
            nodes.forEach { (k, v) -> root.put(k, v) }
            return FirebaseGetResponse(200, root.toString(), "fleet-etag")
        }

        override fun putWithIfMatch(urlString: String, body: String, etag: String?): Int {
            if (failWithIoException) throw IOException("Network unreachable")
            putCount++

            val id = extractVehicleId(urlString) ?: return 400
            if (failOnceWith412ForId == id) {
                failOnceWith412ForId = null
                // Simulate another active device winning the race right before our PUT
                putNode(
                    id,
                    JSONObject().apply {
                        put("vehicleId", id)
                        put("deviceInstallId", on412ConcurrentOwnerId)
                        put("updatedAt", on412Timestamp)
                    }
                )
                return HttpURLConnection.HTTP_PRECON_FAILED
            }

            val expectedEtag = if (nodes.containsKey(id)) etags[id] else "null_etag"
            if (etag != null && etag != expectedEtag) {
                return HttpURLConnection.HTTP_PRECON_FAILED
            }

            putNode(id, JSONObject(body))
            return 200
        }
    }

    private lateinit var fakeTransport: FakeFleetRegistryTransport
    private val nowMs = 1_700_000_000_000L
    private val myDeviceId = "device-self-1111"

    @Before
    fun setUp() {
        CloudConfigRepository.resetForTesting()
        fakeTransport = FakeFleetRegistryTransport()
        CloudConfigRepository.transport = fakeTransport
        CloudConfigRepository.clock = { nowMs }
        CloudConfigRepository.setConfigForTesting(
            config = CloudConfig(
                vehicleId = "DT-01",
                firebaseDbUrl = "https://example.firebasedatabase.app",
                firebaseAuthToken = "",
                liveTelemetryEnabled = true,
                cloudUploadUrl = "",
                autoUploadOnWifi = true
            ),
            installId = myDeviceId
        )
    }

    @After
    fun tearDown() {
        CloudConfigRepository.resetForTesting()
    }

    @Test
    fun `same device owning DT-01 is treated as valid and preserved`() = runTest {
        fakeTransport.putNode(
            "DT-01",
            JSONObject().apply {
                put("vehicleId", "DT-01")
                put("deviceInstallId", myDeviceId)
                put("updatedAt", nowMs - 10_000L)
                put("rsrp", -85)
            }
        )

        val valid = CloudConfigRepository.validateVehicleId("DT-01").getOrThrow()
        assertTrue(valid)

        val claim = CloudConfigRepository.saveVehicleId("DT-01").getOrThrow()
        assertEquals("DT-01", claim.assignedId)
        assertFalse(claim.wasReassigned)
        assertNull(claim.reassignmentNotice)
        assertTrue(claim.syncedToCloud)
        assertEquals("DT-01", CloudConfigRepository.config.value.vehicleId)
        assertEquals(-85, fakeTransport.nodes["DT-01"]?.optInt("rsrp"))
    }

    @Test
    fun `stale vehicle owned by another device is reclaimed`() = runTest {
        fakeTransport.putNode(
            "DT-01",
            JSONObject().apply {
                put("vehicleId", "DT-01")
                put("deviceInstallId", "other-phone-9999")
                put("updatedAt", nowMs - CloudConfigRepository.VEHICLE_ACTIVE_TIMEOUT_MS - 60_000L)
            }
        )

        val valid = CloudConfigRepository.validateVehicleId("DT-01").getOrThrow()
        assertTrue(valid)

        val claim = CloudConfigRepository.saveVehicleId("DT-01").getOrThrow()
        assertEquals("DT-01", claim.assignedId)
        assertFalse(claim.wasReassigned)
        assertEquals(myDeviceId, fakeTransport.nodes["DT-01"]?.optString("deviceInstallId"))
    }

    @Test
    fun `active duplicate auto-assigns next free DT-NN and returns notice`() = runTest {
        for (i in 1..5) {
            val id = String.format("DT-%02d", i)
            fakeTransport.putNode(
                id,
                JSONObject().apply {
                    put("vehicleId", id)
                    put("deviceInstallId", "other-phone-$i")
                    put("updatedAt", nowMs - 30_000L)
                }
            )
        }

        val valid = CloudConfigRepository.validateVehicleId("DT-01").getOrThrow()
        assertFalse(valid)

        val claim = CloudConfigRepository.saveVehicleId("DT-01").getOrThrow()
        assertEquals("DT-01", claim.requestedId)
        assertEquals("DT-06", claim.assignedId)
        assertTrue(claim.wasReassigned)
        assertEquals("DT-01 was in use, assigned DT-06", claim.reassignmentNotice)
        assertTrue(claim.syncedToCloud)
        assertEquals("DT-06", CloudConfigRepository.config.value.vehicleId)
        assertEquals(myDeviceId, fakeTransport.nodes["DT-06"]?.optString("deviceInstallId"))
    }

    @Test
    fun `race condition 412 retries atomically and picks next free ID`() = runTest {
        fakeTransport.putNode(
            "DT-01",
            JSONObject().apply {
                put("vehicleId", "DT-01")
                put("deviceInstallId", "other-phone-1")
                put("updatedAt", nowMs - 5_000L)
            }
        )
        fakeTransport.failOnceWith412ForId = "DT-02"
        fakeTransport.on412Timestamp = nowMs - 1_000L

        val claim = CloudConfigRepository.saveVehicleId("DT-01").getOrThrow()
        assertEquals("DT-03", claim.assignedId)
        assertTrue(claim.wasReassigned)
        assertEquals("DT-01 was in use, assigned DT-03", claim.reassignmentNotice)
        assertEquals(myDeviceId, fakeTransport.nodes["DT-03"]?.optString("deviceInstallId"))
    }

    @Test
    fun `custom pattern preserves prefix and padding`() = runTest {
        fakeTransport.putNode(
            "CAR-001",
            JSONObject().apply {
                put("vehicleId", "CAR-001")
                put("deviceInstallId", "other-phone")
                put("updatedAt", nowMs - 5_000L)
            }
        )

        val claim = CloudConfigRepository.saveVehicleId("CAR-001").getOrThrow()
        assertEquals("CAR-002", claim.assignedId)
        assertEquals("CAR-001 was in use, assigned CAR-002", claim.reassignmentNotice)
    }

    @Test
    fun `offline network error persists locally without blocking`() = runTest {
        fakeTransport.failWithIoException = true

        val claim = CloudConfigRepository.saveVehicleId("DT-09").getOrThrow()
        assertEquals("DT-09", claim.assignedId)
        assertFalse(claim.wasReassigned)
        assertFalse(claim.syncedToCloud)
        assertEquals("DT-09", CloudConfigRepository.config.value.vehicleId)
    }

    @Test
    fun `blank vehicle ID returns failure`() = runTest {
        val result = CloudConfigRepository.saveVehicleId("   ")
        assertTrue(result.isFailure)
        assertEquals("Vehicle ID cannot be blank", result.exceptionOrNull()?.message)
    }

    @Test
    fun `invalid firebase key characters return failure`() = runTest {
        val result = CloudConfigRepository.saveVehicleId("DT.01")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("cannot contain") == true)
    }
}
