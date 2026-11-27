package com.drivetest.lte5g.cloud

import android.content.SharedPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection

class CloudConfigRepositoryTest {

    private class InMemorySharedPreferences(
        initial: Map<String, Any?> = emptyMap()
    ) : SharedPreferences {
        private val data = LinkedHashMap<String, Any?>(initial)

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
        override fun getString(key: String?, defValue: String?): String? =
            (data[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            (data[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = values
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) removals.add(key)
            }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) data.clear()
                removals.forEach { data.remove(it) }
                pending.forEach { (k, v) ->
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class FakeFleetRegistryTransport : FleetRegistryTransport {
        val claimNodes = mutableMapOf<String, JSONObject>()
        val claimEtags = mutableMapOf<String, String>()
        val vehicleNodes = mutableMapOf<String, JSONObject>()
        val vehicleEtags = mutableMapOf<String, String>()

        var failWithIoException = false
        var forceHttpStatus: Int? = null
        var failOnceWith412ForClaimKey: String? = null
        val failWith412Keys = mutableSetOf<String>()
        var on412ConcurrentOwnerId: String = "other-racer-device"
        var on412Timestamp: Long = 1_700_000_000_000L
        var omitEtagOnGet = false
        var lastClaimPutIfMatch: String? = null
        var deleteCallCount = 0

        private var versionCounter = 1

        fun putClaim(vehicleId: String, deviceInstallId: String, updatedAt: Long = 1_700_000_000_000L) {
            val key = VehicleIdGenerator.canonicalKey(vehicleId)
            claimNodes[key] = JSONObject().apply {
                put("vehicleId", vehicleId.trim().uppercase())
                put("canonicalId", key)
                put("deviceInstallId", deviceInstallId)
                put("claimedAt", updatedAt)
                put("updatedAt", updatedAt)
            }
            claimEtags[key] = "claim-etag-${versionCounter++}"
        }

        fun putVehicleNode(key: String, json: JSONObject) {
            vehicleNodes[key] = json
            vehicleEtags[key] = "veh-etag-${versionCounter++}"
        }

        private fun extractClaimKey(urlString: String): String? =
            Regex("""/fleet/vehicleClaims/([^/?]+)\.json""").find(urlString)?.groupValues?.get(1)

        private fun extractVehicleKey(urlString: String): String? =
            Regex("""/fleet/vehicles/([^/?]+)\.json""").find(urlString)?.groupValues?.get(1)

        override fun getWithEtag(urlString: String): FirebaseGetResponse = handleGet(urlString)
        override fun getJson(urlString: String): FirebaseGetResponse = handleGet(urlString)

        private fun handleGet(urlString: String): FirebaseGetResponse {
            if (failWithIoException) throw IOException("Network unreachable")
            forceHttpStatus?.let { return FirebaseGetResponse(it, """{"error":"Forced HTTP $it"}""", null) }

            val claimKey = extractClaimKey(urlString)
            if (claimKey != null) {
                val node = claimNodes[claimKey]
                return if (node == null) {
                    FirebaseGetResponse(200, "null", if (omitEtagOnGet) null else "null_etag")
                } else {
                    FirebaseGetResponse(
                        200,
                        node.toString(),
                        if (omitEtagOnGet) null else (claimEtags[claimKey] ?: "claim-etag-0")
                    )
                }
            }

            val vehicleKey = extractVehicleKey(urlString)
            if (vehicleKey != null) {
                val node = vehicleNodes[vehicleKey]
                return if (node == null) {
                    FirebaseGetResponse(200, "null", if (omitEtagOnGet) null else "null_etag")
                } else {
                    FirebaseGetResponse(
                        200,
                        node.toString(),
                        if (omitEtagOnGet) null else (vehicleEtags[vehicleKey] ?: "veh-etag-0")
                    )
                }
            }

            if (urlString.contains("/fleet/vehicleClaims.json")) {
                if (claimNodes.isEmpty()) return FirebaseGetResponse(200, "null", "claims-null")
                val obj = JSONObject()
                if (urlString.contains("shallow=true")) {
                    claimNodes.keys.forEach { obj.put(it, true) }
                } else {
                    claimNodes.forEach { (k, v) -> obj.put(k, v) }
                }
                return FirebaseGetResponse(200, obj.toString(), "claims-etag")
            }

            if (urlString.contains("/fleet/vehicles.json")) {
                if (vehicleNodes.isEmpty()) return FirebaseGetResponse(200, "null", "vehicles-null")
                val obj = JSONObject()
                if (urlString.contains("shallow=true")) {
                    vehicleNodes.keys.forEach { obj.put(it, true) }
                } else {
                    vehicleNodes.forEach { (k, v) -> obj.put(k, v) }
                }
                return FirebaseGetResponse(200, obj.toString(), "vehicles-etag")
            }

            return FirebaseGetResponse(404, "null", null)
        }

        override fun putWithIfMatch(urlString: String, body: String, etag: String?): Int {
            if (failWithIoException) throw IOException("Network unreachable")
            forceHttpStatus?.let { return it }

            val claimKey = extractClaimKey(urlString)
            if (claimKey != null) {
                lastClaimPutIfMatch = etag
                if (failOnceWith412ForClaimKey == claimKey || failWith412Keys.remove(claimKey)) {
                    if (failOnceWith412ForClaimKey == claimKey) {
                        failOnceWith412ForClaimKey = null
                    }
                    putClaim(claimKey.uppercase(), on412ConcurrentOwnerId, on412Timestamp)
                    return HttpURLConnection.HTTP_PRECON_FAILED
                }
                val expected = if (claimNodes.containsKey(claimKey)) claimEtags[claimKey] else "null_etag"
                if (etag != null && etag != expected) {
                    return HttpURLConnection.HTTP_PRECON_FAILED
                }
                claimNodes[claimKey] = JSONObject(body)
                claimEtags[claimKey] = "claim-etag-${versionCounter++}"
                return 200
            }

            val vehicleKey = extractVehicleKey(urlString)
            if (vehicleKey != null) {
                val expected = if (vehicleNodes.containsKey(vehicleKey)) vehicleEtags[vehicleKey] else "null_etag"
                if (etag != null && etag != expected) {
                    return HttpURLConnection.HTTP_PRECON_FAILED
                }
                vehicleNodes[vehicleKey] = JSONObject(body)
                vehicleEtags[vehicleKey] = "veh-etag-${versionCounter++}"
                return 200
            }

            return 400
        }

        override fun deleteWithIfMatch(urlString: String, etag: String?): Int {
            deleteCallCount++
            if (failWithIoException) throw IOException("Network unreachable")
            forceHttpStatus?.let { return it }

            val claimKey = extractClaimKey(urlString) ?: return 400
            val expected = claimEtags[claimKey] ?: "null_etag"
            if (etag != null && etag != expected) {
                return HttpURLConnection.HTTP_PRECON_FAILED
            }
            claimNodes.remove(claimKey)
            claimEtags.remove(claimKey)
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
        CloudConfigRepository.deviceDefaultIdProvider = { "PIXEL-8-A1B2" }
        CloudConfigRepository.setConfigForTesting(
            config = CloudConfig(
                vehicleId = "PIXEL-8-A1B2",
                firebaseDbUrl = "https://example.firebasedatabase.app",
                firebaseAuthToken = "",
                liveTelemetryEnabled = true,
                cloudUploadUrl = "",
                autoUploadOnWifi = true,
                vehicleIdSyncState = VehicleIdSyncState.UNCLAIMED
            ),
            installId = myDeviceId
        )
    }

    @After
    fun tearDown() {
        CloudConfigRepository.resetForTesting()
    }

    @Test
    fun `fresh install uses device-derived default and never saves DT-01`() {
        val prefs = InMemorySharedPreferences()
        CloudConfigRepository.init(prefs)

        val cfg = CloudConfigRepository.config.value
        assertEquals("PIXEL-8-A1B2", cfg.vehicleId)
        assertNotEquals("DT-01", cfg.vehicleId)
        assertFalse(cfg.isUserEditedVehicleId)
        assertEquals(VehicleIdSyncState.PENDING_SYNC, cfg.vehicleIdSyncState)
    }

    @Test
    fun `migration replaces stale pre-v2 DT-01 with device default but preserves custom user ID`() {
        val staleDefaultPrefs = InMemorySharedPreferences(
            mapOf("cloud_vehicle_id" to "DT-01")
        )
        CloudConfigRepository.init(staleDefaultPrefs)
        assertEquals("PIXEL-8-A1B2", CloudConfigRepository.config.value.vehicleId)
        assertFalse(CloudConfigRepository.config.value.isUserEditedVehicleId)

        val customUserPrefs = InMemorySharedPreferences(
            mapOf("cloud_vehicle_id" to "VAN-BKK-02")
        )
        CloudConfigRepository.init(customUserPrefs)
        assertEquals("VAN-BKK-02", CloudConfigRepository.config.value.vehicleId)
        assertTrue(CloudConfigRepository.config.value.isUserEditedVehicleId)
        assertEquals(VehicleIdSyncState.PENDING_SYNC, CloudConfigRepository.config.value.vehicleIdSyncState)
    }

    @Test
    fun `same device re-saving its own ID succeeds and preserves telemetry fields`() = runTest {
        fakeTransport.putClaim("VAN-01", myDeviceId, nowMs - 60_000L)
        fakeTransport.putVehicleNode(
            "VAN-01",
            JSONObject().apply {
                put("vehicleId", "VAN-01")
                put("deviceInstallId", myDeviceId)
                put("updatedAt", nowMs - 10_000L)
                put("rsrp", -85)
            }
        )

        val avail = CloudConfigRepository.checkVehicleIdAvailability("  van-01  ")
        assertEquals(VehicleIdAvailability.Available("VAN-01", ownedBySelf = true), avail)

        val outcome = CloudConfigRepository.saveVehicleId("  van-01 ")
        assertEquals(VehicleIdClaimOutcome.Claimed("VAN-01"), outcome)
        assertEquals("VAN-01", CloudConfigRepository.config.value.vehicleId)
        assertEquals(VehicleIdSyncState.SYNCED, CloudConfigRepository.config.value.vehicleIdSyncState)
        assertEquals(-85, fakeTransport.vehicleNodes["VAN-01"]?.optInt("rsrp"))
    }

    @Test
    fun `different device gets clear error and free alternative case-insensitively`() = runTest {
        fakeTransport.putClaim("CAR-01", "other-phone-9999", nowMs - 3_600_000L)
        fakeTransport.putClaim("CAR-02", "other-phone-8888", nowMs - 3_600_000L)

        val avail = CloudConfigRepository.checkVehicleIdAvailability("  car-01 ")
        assertTrue(avail is VehicleIdAvailability.Taken)
        avail as VehicleIdAvailability.Taken
        assertEquals("CAR-01", avail.normalizedId)
        assertEquals("CAR-03", avail.suggestedAlternative)
        assertEquals(CloudConfigRepository.DUPLICATE_ERROR_MESSAGE, avail.message)

        val outcome = CloudConfigRepository.saveVehicleId("car-01")
        assertTrue(outcome is VehicleIdClaimOutcome.TakenByOtherDevice)
        outcome as VehicleIdClaimOutcome.TakenByOtherDevice
        assertEquals("CAR-01", outcome.requestedId)
        assertEquals("CAR-03", outcome.suggestedAlternative)
        assertEquals(CloudConfigRepository.DUPLICATE_ERROR_MESSAGE, outcome.message)
        // Must not overwrite local ID or remote owner
        assertEquals("PIXEL-8-A1B2", CloudConfigRepository.config.value.vehicleId)
        assertEquals("other-phone-9999", fakeTransport.claimNodes["car-01"]?.optString("deviceInstallId"))
    }

    @Test
    fun `atomic 412 race condition rejects loser with clear error and free suggestion`() = runTest {
        fakeTransport.failOnceWith412ForClaimKey = "team-north"
        fakeTransport.on412ConcurrentOwnerId = "winning-device-7777"
        fakeTransport.on412Timestamp = nowMs

        val outcome = CloudConfigRepository.saveVehicleId("TEAM-NORTH")
        assertTrue(outcome is VehicleIdClaimOutcome.TakenByOtherDevice)
        outcome as VehicleIdClaimOutcome.TakenByOtherDevice
        assertEquals("TEAM-NORTH", outcome.requestedId)
        assertEquals("TEAM-NORTH-2", outcome.suggestedAlternative)
        assertEquals(CloudConfigRepository.DUPLICATE_ERROR_MESSAGE, outcome.message)
        assertEquals("winning-device-7777", fakeTransport.claimNodes["team-north"]?.optString("deviceInstallId"))
    }

    @Test
    fun `switching vehicle ID atomically claims new ID and releases previous owned claim`() = runTest {
        val first = CloudConfigRepository.saveVehicleId("UNIT-A")
        assertEquals(VehicleIdClaimOutcome.Claimed("UNIT-A"), first)
        assertTrue(fakeTransport.claimNodes.containsKey("unit-a"))

        val second = CloudConfigRepository.saveVehicleId("UNIT-B")
        assertEquals(VehicleIdClaimOutcome.Claimed("UNIT-B"), second)
        assertFalse(fakeTransport.claimNodes.containsKey("unit-a"))
        assertEquals(myDeviceId, fakeTransport.claimNodes["unit-b"]?.optString("deviceInstallId"))
    }

    @Test
    fun `legacy vehicle node owned by other device blocks claim case-insensitively`() = runTest {
        fakeTransport.putVehicleNode(
            "van-legacy",
            JSONObject().apply {
                put("vehicleId", "van-legacy")
                put("deviceInstallId", "legacy-phone-2222")
                put("updatedAt", nowMs - 86_400_000L)
            }
        )

        val outcome = CloudConfigRepository.saveVehicleId("VAN-LEGACY")
        assertTrue(outcome is VehicleIdClaimOutcome.TakenByOtherDevice)
        outcome as VehicleIdClaimOutcome.TakenByOtherDevice
        assertEquals("VAN-LEGACY-2", outcome.suggestedAlternative)
    }

    @Test
    fun `stale unowned legacy vehicle node is safely migrated on claim`() = runTest {
        fakeTransport.putVehicleNode(
            "LEGACY-01",
            JSONObject().apply {
                put("vehicleId", "LEGACY-01")
                put("updatedAt", nowMs - CloudConfigRepository.LEGACY_ACTIVE_TIMEOUT_MS - 60_000L)
                put("isRecording", false)
            }
        )

        val outcome = CloudConfigRepository.saveVehicleId("LEGACY-01")
        assertEquals(VehicleIdClaimOutcome.Claimed("LEGACY-01"), outcome)
        assertEquals(myDeviceId, fakeTransport.claimNodes["legacy-01"]?.optString("deviceInstallId"))
        assertEquals(myDeviceId, fakeTransport.vehicleNodes["LEGACY-01"]?.optString("deviceInstallId"))
    }

    @Test
    fun `offline save stores locally as pending sync and claims on reconnect`() = runTest {
        fakeTransport.failWithIoException = true

        val offlineAvail = CloudConfigRepository.checkVehicleIdAvailability("VAN-OFFLINE")
        assertEquals(VehicleIdAvailability.Offline("VAN-OFFLINE"), offlineAvail)

        val saved = CloudConfigRepository.saveVehicleId("VAN-OFFLINE")
        assertTrue(saved is VehicleIdClaimOutcome.SavedLocallyPendingSync)
        saved as VehicleIdClaimOutcome.SavedLocallyPendingSync
        assertEquals(VehicleIdSyncIssue.OFFLINE, saved.issue)
        assertEquals("VAN-OFFLINE", CloudConfigRepository.config.value.vehicleId)
        assertEquals(VehicleIdSyncState.PENDING_SYNC, CloudConfigRepository.config.value.vehicleIdSyncState)

        // Device reconnects -> pending sync claims atomically in Firebase
        fakeTransport.failWithIoException = false
        val synced = CloudConfigRepository.syncPendingVehicleIdClaim()
        assertEquals(VehicleIdClaimOutcome.Claimed("VAN-OFFLINE"), synced)
        assertEquals(VehicleIdSyncState.SYNCED, CloudConfigRepository.config.value.vehicleIdSyncState)
        assertEquals(VehicleIdSyncIssue.NONE, CloudConfigRepository.config.value.vehicleIdSyncIssue)
    }

    @Test
    fun `offline user-edited ID taken by another device flags CONFLICT on reconnect`() = runTest {
        fakeTransport.failWithIoException = true
        val saved = CloudConfigRepository.saveVehicleId("BKK-VAN-01", isUserEdit = true)
        assertTrue(saved is VehicleIdClaimOutcome.SavedLocallyPendingSync)

        // Another device claims BKK-VAN-01 while we were offline
        fakeTransport.failWithIoException = false
        fakeTransport.putClaim("BKK-VAN-01", "other-phone-5555", nowMs)

        val synced = CloudConfigRepository.syncPendingVehicleIdClaim()
        assertTrue(synced is VehicleIdClaimOutcome.TakenByOtherDevice)
        val cfg = CloudConfigRepository.config.value
        assertEquals("BKK-VAN-01", cfg.vehicleId)
        assertEquals(VehicleIdSyncState.CONFLICT, cfg.vehicleIdSyncState)
        assertEquals(VehicleIdSyncIssue.DUPLICATE_CONFLICT, cfg.vehicleIdSyncIssue)
        assertEquals("BKK-VAN-02", cfg.suggestedAlternativeId)
    }

    @Test
    fun `permission denied saves locally with PERMISSION_DENIED and retries on reconnect`() = runTest {
        fakeTransport.forceHttpStatus = HttpURLConnection.HTTP_FORBIDDEN

        val avail = CloudConfigRepository.checkVehicleIdAvailability("VAN-PERM")
        assertEquals(VehicleIdAvailability.PermissionDenied("VAN-PERM"), avail)

        val saved = CloudConfigRepository.saveVehicleId("VAN-PERM")
        assertTrue(saved is VehicleIdClaimOutcome.SavedLocallyPendingSync)
        saved as VehicleIdClaimOutcome.SavedLocallyPendingSync
        assertEquals(VehicleIdSyncIssue.PERMISSION_DENIED, saved.issue)
        assertEquals(VehicleIdSyncState.PENDING_SYNC, CloudConfigRepository.config.value.vehicleIdSyncState)
        assertEquals(VehicleIdSyncIssue.PERMISSION_DENIED, CloudConfigRepository.config.value.vehicleIdSyncIssue)

        // Rules deployed / permission restored -> sync succeeds
        fakeTransport.forceHttpStatus = null
        val synced = CloudConfigRepository.syncPendingVehicleIdClaim()
        assertEquals(VehicleIdClaimOutcome.Claimed("VAN-PERM"), synced)
        assertEquals(VehicleIdSyncState.SYNCED, CloudConfigRepository.config.value.vehicleIdSyncState)
    }

    @Test
    fun `invalid inputs are rejected without network calls`() = runTest {
        fakeTransport.failWithIoException = true

        assertTrue(CloudConfigRepository.saveVehicleId("   ") is VehicleIdClaimOutcome.InvalidInput)
        assertTrue(CloudConfigRepository.saveVehicleId("A") is VehicleIdClaimOutcome.InvalidInput)
        assertTrue(CloudConfigRepository.saveVehicleId("DT.01") is VehicleIdClaimOutcome.InvalidInput)
        assertTrue(CloudConfigRepository.saveVehicleId("A".repeat(33)) is VehicleIdClaimOutcome.InvalidInput)
        assertTrue(CloudConfigRepository.checkVehicleIdAvailability("bad id!") is VehicleIdAvailability.Invalid)
    }

    @Test
    fun `firebase database rules file is valid JSON with vehicleClaims ownership and indexes`() {
        val rulesFile = listOf(
            File("../firebase/database.rules.json"),
            File("../../firebase/database.rules.json"),
            File("firebase/database.rules.json")
        ).firstOrNull { it.exists() }

        assertTrue("Expected firebase/database.rules.json to exist", rulesFile != null)
        val root = JSONObject(rulesFile!!.readText())
        val fleet = root.getJSONObject("rules").getJSONObject("fleet")
        val claims = fleet.getJSONObject("vehicleClaims")
        val claimNode = claims.getJSONObject("\$canonicalId")
        val vehicles = fleet.getJSONObject("vehicles")

        assertTrue(claims.getJSONArray(".indexOn").toString().contains("deviceInstallId"))
        assertTrue(vehicles.getJSONArray(".indexOn").toString().contains("deviceInstallId"))
        assertTrue(claimNode.getString(".write").contains("deviceInstallId"))
        assertTrue(claimNode.getString(".validate").contains("canonicalId"))
        assertTrue(claimNode.getString(".validate").contains("toLowerCase()"))
        assertTrue(vehicles.getJSONObject("\$vehicleId").getString(".write").contains("claimReleased"))
    }

    @Test
    fun `switching vehicle ID releases claim and vehicle node so another device can immediately claim old ID`() = runTest {
        val first = CloudConfigRepository.saveVehicleId("UNIT-A")
        assertEquals(VehicleIdClaimOutcome.Claimed("UNIT-A"), first)
        assertEquals(myDeviceId, fakeTransport.vehicleNodes["UNIT-A"]?.optString("deviceInstallId"))
        // Simulate telemetry field present on UNIT-A before switch
        fakeTransport.vehicleNodes["UNIT-A"]?.put("rsrp", -91)

        val second = CloudConfigRepository.saveVehicleId("UNIT-B")
        assertEquals(VehicleIdClaimOutcome.Claimed("UNIT-B"), second)
        assertFalse(fakeTransport.claimNodes.containsKey("unit-a"))
        assertTrue(fakeTransport.vehicleNodes["UNIT-A"]?.optBoolean("claimReleased", false) == true)
        assertEquals(-91, fakeTransport.vehicleNodes["UNIT-A"]?.optInt("rsrp"))

        // Second device checks & claims the released UNIT-A immediately
        CloudConfigRepository.setConfigForTesting(
            config = CloudConfig(
                vehicleId = "OTHER-DEFAULT",
                firebaseDbUrl = "https://example.firebasedatabase.app",
                firebaseAuthToken = "",
                liveTelemetryEnabled = true,
                cloudUploadUrl = "",
                autoUploadOnWifi = true,
                vehicleIdSyncState = VehicleIdSyncState.UNCLAIMED
            ),
            installId = "device-other-2222"
        )

        val avail = CloudConfigRepository.checkVehicleIdAvailability("UNIT-A")
        assertEquals(VehicleIdAvailability.Available("UNIT-A", ownedBySelf = false), avail)

        val otherClaim = CloudConfigRepository.saveVehicleId("UNIT-A")
        assertEquals(VehicleIdClaimOutcome.Claimed("UNIT-A"), otherClaim)
        assertEquals("device-other-2222", fakeTransport.claimNodes["unit-a"]?.optString("deviceInstallId"))
        assertEquals("device-other-2222", fakeTransport.vehicleNodes["UNIT-A"]?.optString("deviceInstallId"))
        assertFalse(fakeTransport.vehicleNodes["UNIT-A"]?.optBoolean("claimReleased", false) == true)
    }

    @Test
    fun `missing ETag enforces null_etag on new claim and refuses unconditional overwrite or delete`() = runTest {
        fakeTransport.omitEtagOnGet = true

        // New claim with omitted GET ETag must still send If-Match: null_etag
        val created = CloudConfigRepository.saveVehicleId("ETAG-TEST")
        assertEquals(VehicleIdClaimOutcome.Claimed("ETAG-TEST"), created)
        assertEquals(CloudConfigRepository.NULL_ETAG, fakeTransport.lastClaimPutIfMatch)

        // Updating an existing claim when GET omits ETag must refuse unconditional PUT
        val updateWithoutEtag = CloudConfigRepository.saveVehicleId("ETAG-TEST")
        assertTrue(updateWithoutEtag is VehicleIdClaimOutcome.SavedLocallyPendingSync)
        updateWithoutEtag as VehicleIdClaimOutcome.SavedLocallyPendingSync
        assertEquals(VehicleIdSyncIssue.SERVER_ERROR, updateWithoutEtag.issue)

        // Switching to a new ID while previous claim GET omits ETag must not call unconditional DELETE
        fakeTransport.deleteCallCount = 0
        val switched = CloudConfigRepository.saveVehicleId("ETAG-TEST-2")
        assertEquals(VehicleIdClaimOutcome.Claimed("ETAG-TEST-2"), switched)
        assertEquals(0, fakeTransport.deleteCallCount)
    }

    @Test
    fun `migration preserves truncated overlong custom ID and resets unsanitizable garbage to non-user default`() {
        val overlongPrefs = InMemorySharedPreferences(
            mapOf("cloud_vehicle_id" to "NORTH-SECTOR-DRIVE-TEST-VEHICLE-VAN-01")
        )
        CloudConfigRepository.init(overlongPrefs)
        val overlongCfg = CloudConfigRepository.config.value
        assertTrue(overlongCfg.vehicleId.length <= VehicleIdGenerator.MAX_VEHICLE_ID_LENGTH)
        assertTrue(overlongCfg.vehicleId.startsWith("NORTH-SECTOR-DRIVE-TEST-VEHICLE"))
        assertTrue(overlongCfg.isUserEditedVehicleId)

        val garbagePrefs = InMemorySharedPreferences(
            mapOf("cloud_vehicle_id" to "!!!")
        )
        CloudConfigRepository.init(garbagePrefs)
        val garbageCfg = CloudConfigRepository.config.value
        assertEquals("PIXEL-8-A1B2", garbageCfg.vehicleId)
        assertFalse(garbageCfg.isUserEditedVehicleId)
    }

    @Test
    fun `untouched auto default resolves multi-step collision on reconnect`() = runTest {
        CloudConfigRepository.setConfigForTesting(
            config = CloudConfig(
                vehicleId = "PIXEL-8-A1B2",
                firebaseDbUrl = "https://example.firebasedatabase.app",
                firebaseAuthToken = "",
                liveTelemetryEnabled = true,
                cloudUploadUrl = "",
                autoUploadOnWifi = true,
                vehicleIdSyncState = VehicleIdSyncState.PENDING_SYNC,
                isUserEditedVehicleId = false
            ),
            installId = myDeviceId
        )
        fakeTransport.putClaim("PIXEL-8-A1B2", "other-phone-1", nowMs)
        fakeTransport.failWith412Keys.add("pixel-8-a1b2-2")

        val outcome = CloudConfigRepository.syncPendingVehicleIdClaim()
        assertEquals(VehicleIdClaimOutcome.Claimed("PIXEL-8-A1B2-3"), outcome)
        assertEquals("PIXEL-8-A1B2-3", CloudConfigRepository.config.value.vehicleId)
        assertEquals(VehicleIdSyncState.SYNCED, CloudConfigRepository.config.value.vehicleIdSyncState)
        assertFalse(CloudConfigRepository.config.value.isUserEditedVehicleId)
    }

    @Test
    fun `concurrent saveVehicleId and syncPendingVehicleIdClaim serialize cleanly without clobbering user edit`() = runTest {
        CloudConfigRepository.setConfigForTesting(
            config = CloudConfig(
                vehicleId = "PIXEL-8-A1B2",
                firebaseDbUrl = "https://example.firebasedatabase.app",
                firebaseAuthToken = "",
                liveTelemetryEnabled = true,
                cloudUploadUrl = "",
                autoUploadOnWifi = true,
                vehicleIdSyncState = VehicleIdSyncState.PENDING_SYNC,
                isUserEditedVehicleId = false
            ),
            installId = myDeviceId
        )

        val pendingJob = async { CloudConfigRepository.syncPendingVehicleIdClaim() }
        val userSaveJob = async { CloudConfigRepository.saveVehicleId("CUSTOM-99", isUserEdit = true) }

        pendingJob.await()
        val userOutcome = userSaveJob.await()

        assertEquals(VehicleIdClaimOutcome.Claimed("CUSTOM-99"), userOutcome)
        assertEquals("CUSTOM-99", CloudConfigRepository.config.value.vehicleId)
        assertEquals(VehicleIdSyncState.SYNCED, CloudConfigRepository.config.value.vehicleIdSyncState)
        assertTrue(CloudConfigRepository.config.value.isUserEditedVehicleId)
        assertEquals(myDeviceId, fakeTransport.claimNodes["custom-99"]?.optString("deviceInstallId"))
    }
}
