package com.drivetest.lte5g.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleIdGeneratorTest {

    @Test
    fun `generateDefaultId prefers deviceName over bluetoothName and manufacturer model`() {
        val id = VehicleIdGenerator.generateDefaultId(
            deviceName = "Team BKK Van 3",
            bluetoothName = "BT-Phone",
            manufacturer = "Samsung",
            model = "SM-S918B",
            androidId = "a1b2c3d4e5f60708",
            installId = "11111111-2222-3333-4444-555555555555"
        )
        val suffix = VehicleIdGenerator.shortStableSuffix(
            androidId = "a1b2c3d4e5f60708",
            installId = "11111111-2222-3333-4444-555555555555"
        )
        assertEquals("TEAM-BKK-VAN-3-$suffix", id)
        assertTrue(VehicleIdGenerator.validate(id) is VehicleIdValidationResult.Valid)
    }

    @Test
    fun `generateDefaultId falls back to bluetoothName when deviceName is blank or non-ASCII`() {
        val id = VehicleIdGenerator.generateDefaultId(
            deviceName = "มือถือทดสอบ",
            bluetoothName = "Oppo Find X7",
            manufacturer = "OPPO",
            model = "PHZ110",
            androidId = "deadbeef12345678",
            installId = "install-uuid-1"
        )
        val suffix = VehicleIdGenerator.shortStableSuffix("deadbeef12345678", "install-uuid-1")
        assertEquals("OPPO-FIND-X7-$suffix", id)
    }

    @Test
    fun `generateDefaultId falls back to manufacturer plus model and deduplicates prefix`() {
        val dedupId = VehicleIdGenerator.generateDefaultId(
            deviceName = null,
            bluetoothName = "   ",
            manufacturer = "Google",
            model = "Google Pixel 8 Pro",
            androidId = null,
            installId = "install-uuid-google"
        )
        val suffix = VehicleIdGenerator.shortStableSuffix(null, "install-uuid-google")
        assertEquals("GOOGLE-PIXEL-8-PRO-$suffix", dedupId)

        val combinedId = VehicleIdGenerator.generateDefaultId(
            deviceName = null,
            bluetoothName = null,
            manufacturer = "samsung",
            model = "SM-S918B",
            androidId = null,
            installId = "install-uuid-samsung"
        )
        val samsungSuffix = VehicleIdGenerator.shortStableSuffix(null, "install-uuid-samsung")
        assertEquals("SAMSUNG-SM-S918B-$samsungSuffix", combinedId)
    }

    @Test
    fun `generateDefaultId never returns DT-01 even when all inputs blank or deviceName is DT-01`() {
        val fromBlank = VehicleIdGenerator.generateDefaultId(
            deviceName = null,
            bluetoothName = null,
            manufacturer = null,
            model = null,
            androidId = null,
            installId = "install-blank"
        )
        assertNotEquals("DT-01", fromBlank)
        assertTrue(fromBlank.startsWith("UNIT-"))
        assertTrue(VehicleIdGenerator.validate(fromBlank) is VehicleIdValidationResult.Valid)

        val fromExample = VehicleIdGenerator.generateDefaultId(
            deviceName = "DT-01",
            bluetoothName = null,
            manufacturer = null,
            model = null,
            androidId = null,
            installId = "install-example"
        )
        assertNotEquals("DT-01", fromExample)
        assertTrue(fromExample.startsWith("DT-01-"))
        assertTrue(VehicleIdGenerator.validate(fromExample) is VehicleIdValidationResult.Valid)
    }

    @Test
    fun `generateDefaultId enforces max length and strips diacritics and punctuation`() {
        val id = VehicleIdGenerator.generateDefaultId(
            deviceName = "Équipe's Super-Long Drive Test Vehicle Phone 2026!!!",
            bluetoothName = null,
            manufacturer = "Acme",
            model = "Ultra99",
            androidId = "1234567890abcdef",
            installId = "install-long"
        )
        assertTrue("Expected <= 32 chars, got ${id.length}: $id", id.length <= VehicleIdGenerator.MAX_VEHICLE_ID_LENGTH)
        assertTrue(id.startsWith("EQUIPE-S-SUPER-LONG"))
        assertTrue(!id.contains("--"))
        assertTrue(VehicleIdGenerator.validate(id) is VehicleIdValidationResult.Valid)
    }

    @Test
    fun `shortStableSuffix ignores known emulator androidId and falls back to installId`() {
        val fromEmulator = VehicleIdGenerator.shortStableSuffix(
            androidId = VehicleIdGenerator.KNOWN_EMULATOR_ANDROID_ID,
            installId = "unique-install-abc"
        )
        val fromNull = VehicleIdGenerator.shortStableSuffix(
            androidId = null,
            installId = "unique-install-abc"
        )
        assertEquals(fromNull, fromEmulator)
        assertEquals(4, fromEmulator.length)
        assertTrue(Regex("^[0-9A-F]{4}$").matches(fromEmulator))

        val fromRealAndroidId = VehicleIdGenerator.shortStableSuffix(
            androidId = "abcdef0123456789",
            installId = "unique-install-abc"
        )
        assertNotEquals(fromNull, fromRealAndroidId)
    }

    @Test
    fun `validate enforces trim, case normalization, length, and allowed character set`() {
        assertEquals(
            VehicleIdValidationResult.Valid("VAN_BKK-02"),
            VehicleIdGenerator.validate("  van_bkk-02  ")
        )
        assertTrue(VehicleIdGenerator.validate("") is VehicleIdValidationResult.Invalid)
        assertTrue(VehicleIdGenerator.validate("   ") is VehicleIdValidationResult.Invalid)
        assertTrue(VehicleIdGenerator.validate("A") is VehicleIdValidationResult.Invalid)
        assertTrue(VehicleIdGenerator.validate("A".repeat(33)) is VehicleIdValidationResult.Invalid)
        assertTrue(VehicleIdGenerator.validate("A".repeat(32)) is VehicleIdValidationResult.Valid)
        assertTrue(VehicleIdGenerator.validate("DT 01") is VehicleIdValidationResult.Invalid)
        assertTrue(VehicleIdGenerator.validate("DT.01") is VehicleIdValidationResult.Invalid)
        assertTrue(VehicleIdGenerator.validate("DT#01") is VehicleIdValidationResult.Invalid)
        assertTrue(VehicleIdGenerator.validate("DT\$01") is VehicleIdValidationResult.Invalid)
        assertTrue(VehicleIdGenerator.validate("DT/01") is VehicleIdValidationResult.Invalid)
        assertTrue(VehicleIdGenerator.validate("รถทดสอบ") is VehicleIdValidationResult.Invalid)
        assertTrue(VehicleIdGenerator.validate("---") is VehicleIdValidationResult.Invalid)
        assertTrue(VehicleIdGenerator.validate("___") is VehicleIdValidationResult.Invalid)
    }

    @Test
    fun `suggestFreeAlternative appends -2 or increments zero-padded sequence within max length`() {
        assertEquals(
            "PIXEL-8-2",
            VehicleIdGenerator.suggestFreeAlternative(
                requestedId = "PIXEL-8",
                takenCanonicalKeys = setOf("pixel-8")
            )
        )
        assertEquals(
            "PIXEL-8-3",
            VehicleIdGenerator.suggestFreeAlternative(
                requestedId = "PIXEL-8",
                takenCanonicalKeys = setOf("PIXEL-8", "pixel-8-2")
            )
        )
        assertEquals(
            "VAN-01",
            VehicleIdGenerator.suggestFreeAlternative(
                requestedId = "VAN-00",
                takenCanonicalKeys = setOf("van-00")
            )
        )
        assertEquals(
            "CAR-02",
            VehicleIdGenerator.suggestFreeAlternative(
                requestedId = "CAR-01",
                takenCanonicalKeys = setOf("car-01")
            )
        )
        assertEquals(
            "CAR-04",
            VehicleIdGenerator.suggestFreeAlternative(
                requestedId = "CAR-01",
                takenCanonicalKeys = setOf("car-01", "CAR-02", "car-03")
            )
        )
        val maxLenId = "A".repeat(VehicleIdGenerator.MAX_VEHICLE_ID_LENGTH)
        val suggestion = VehicleIdGenerator.suggestFreeAlternative(
            requestedId = maxLenId,
            takenCanonicalKeys = setOf(maxLenId.lowercase())
        )
        assertTrue(suggestion.length <= VehicleIdGenerator.MAX_VEHICLE_ID_LENGTH)
        assertTrue(suggestion.endsWith("-2"))
        assertTrue(VehicleIdGenerator.validate(suggestion) is VehicleIdValidationResult.Valid)
    }
}
