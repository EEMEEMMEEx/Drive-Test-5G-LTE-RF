package com.drivetest.lte5g.cloud

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

sealed class VehicleIdValidationResult {
    data class Valid(val normalized: String) : VehicleIdValidationResult()
    data class Invalid(val reason: String) : VehicleIdValidationResult()
}

/**
 * Generates, sanitizes, validates, and suggests collision-free Vehicle IDs.
 *
 * Privacy guarantee: never requests READ_PHONE_STATE and never reads IMEI, IMSI,
 * hardware serial number, or Bluetooth runtime permissions.
 */
object VehicleIdGenerator {
    const val MIN_VEHICLE_ID_LENGTH = 2
    const val MAX_VEHICLE_ID_LENGTH = 32
    const val PLACEHOLDER_EXAMPLE_ID = "DT-01"
    internal const val KNOWN_EMULATOR_ANDROID_ID = "9774d56d682e549c"
    private const val FALLBACK_PREFIX = "UNIT"
    private const val SUFFIX_LENGTH = 4

    private val COMBINING_MARKS = Regex("""\p{M}+""")
    private val DISALLOWED_CHARS = Regex("""[^A-Z0-9_-]+""")
    private val MULTIPLE_HYPHENS = Regex("""-{2,}""")
    private val MULTIPLE_UNDERSCORES = Regex("""_{2,}""")
    private val MIXED_SEPARATORS = Regex("""[-_]{2,}""")
    private val ALLOWED_ID_REGEX = Regex("""^[A-Z0-9_-]+$""")
    private val HAS_ALPHANUMERIC = Regex("""[A-Z0-9]""")
    private val ZERO_PADDED_SUFFIX_PATTERN = Regex("""^(.+)-(\d{2,})$""")

    /**
     * Canonical case-insensitive Firebase key for Vehicle ID claims.
     */
    fun canonicalKey(id: String): String = id.trim().lowercase(Locale.US)

    /**
     * Validate user-entered Vehicle ID (trimmed, case-insensitive normalized to A-Z, 0-9, -, _).
     */
    fun validate(raw: String): VehicleIdValidationResult {
        val normalized = raw.trim().uppercase(Locale.US)
        if (normalized.isEmpty()) {
            return VehicleIdValidationResult.Invalid("Vehicle ID cannot be empty")
        }
        if (normalized.length < MIN_VEHICLE_ID_LENGTH) {
            return VehicleIdValidationResult.Invalid(
                "Vehicle ID must be at least $MIN_VEHICLE_ID_LENGTH characters"
            )
        }
        if (normalized.length > MAX_VEHICLE_ID_LENGTH) {
            return VehicleIdValidationResult.Invalid(
                "Vehicle ID must be at most $MAX_VEHICLE_ID_LENGTH characters"
            )
        }
        if (!ALLOWED_ID_REGEX.matches(normalized)) {
            return VehicleIdValidationResult.Invalid(
                "Only A–Z, 0–9, hyphen (-), and underscore (_) are allowed"
            )
        }
        if (!HAS_ALPHANUMERIC.containsMatchIn(normalized)) {
            return VehicleIdValidationResult.Invalid(
                "Vehicle ID must contain at least one letter or number"
            )
        }
        return VehicleIdValidationResult.Valid(normalized)
    }

    /**
     * Sanitize an arbitrary label (device name, manufacturer/model, etc.) into uppercase
     * allowed Vehicle ID characters (A-Z, 0-9, '-', '_').
     */
    fun sanitizeFragment(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKD)
        val asciiUpper = COMBINING_MARKS.replace(decomposed, "").uppercase(Locale.US)
        val replaced = DISALLOWED_CHARS.replace(asciiUpper, "-")
        val collapsed = MIXED_SEPARATORS.replace(
            MULTIPLE_UNDERSCORES.replace(
                MULTIPLE_HYPHENS.replace(replaced, "-"),
                "_"
            ),
            "-"
        )
        return collapsed.trim('-', '_')
    }

    /**
     * Derive a deterministic 4-character uppercase hex suffix from a hashed ANDROID_ID
     * (falling back to the app-generated installation UUID).
     */
    fun shortStableSuffix(androidId: String?, installId: String): String {
        val cleanAndroidId = androidId?.trim().orEmpty()
        val seed = if (
            cleanAndroidId.isNotEmpty() &&
            !cleanAndroidId.equals(KNOWN_EMULATOR_ANDROID_ID, ignoreCase = true)
        ) {
            "dt-aid-v1:$cleanAndroidId"
        } else {
            val cleanInstallId = installId.trim().ifEmpty { "fallback-install" }
            "dt-iid-v1:$cleanInstallId"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString(separator = "") { b ->
            String.format(Locale.US, "%02X", b.toInt() and 0xFF)
        }
        return hex.takeLast(SUFFIX_LENGTH)
    }

    /**
     * Combine Build.MANUFACTURER and Build.MODEL without duplicating the manufacturer prefix
     * (e.g. "Google" + "Google Pixel 8" -> "Google Pixel 8").
     */
    fun combineManufacturerAndModel(manufacturer: String?, model: String?): String {
        val cleanMfg = manufacturer?.trim().orEmpty()
        val cleanModel = model?.trim().orEmpty()
        if (cleanMfg.isEmpty()) return cleanModel
        if (cleanModel.isEmpty()) return cleanMfg
        return if (cleanModel.startsWith(cleanMfg, ignoreCase = true)) {
            cleanModel
        } else {
            "$cleanMfg $cleanModel"
        }
    }

    /**
     * Generate a deterministic, sanitized default Vehicle ID derived from device info plus a
     * short stable 4-char suffix. Never returns the placeholder example "DT-01".
     */
    fun generateDefaultId(
        deviceName: String?,
        bluetoothName: String?,
        manufacturer: String?,
        model: String?,
        androidId: String?,
        installId: String
    ): String {
        val suffix = shortStableSuffix(androidId, installId)
        val candidates = listOf(
            sanitizeFragment(deviceName),
            sanitizeFragment(bluetoothName),
            sanitizeFragment(combineManufacturerAndModel(manufacturer, model))
        )
        val base = candidates.firstOrNull { isUsableBase(it) } ?: FALLBACK_PREFIX
        return appendSuffixWithinLimit(base, suffix)
    }

    /**
     * Generate the device-derived default Vehicle ID using only permission-free Android APIs.
     * ANDROID_ID is only SHA-256 hashed locally (last 4 hex chars) as a collision-avoidance suffix
     * and is never stored or transmitted raw.
     */
    @SuppressLint("HardwareIds")
    fun generateFromContext(context: Context, installId: String): String {
        val resolver = context.contentResolver
        val deviceName = runCatching {
            Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        val bluetoothName = runCatching {
            Settings.Secure.getString(resolver, "bluetooth_name")
        }.getOrNull()
        val androidId = runCatching {
            Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()

        return generateDefaultId(
            deviceName = deviceName,
            bluetoothName = bluetoothName,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidId = androidId,
            installId = installId
        )
    }

    /**
     * Suggest a free alternative ID when [requestedId] is already taken in Firebase.
     *
     * - For zero-padded numbered IDs (e.g. "CAR-01", "VAN-001"), increments sequentially ("CAR-02").
     * - Otherwise appends "-2", "-3", ... (e.g. "PIXEL-8" -> "PIXEL-8-2").
     * - Falls back to appending [stableSuffix] if numeric suffixes are exhausted.
     * - Always respects [MAX_VEHICLE_ID_LENGTH] and checks [takenCanonicalKeys] case-insensitively.
     */
    fun suggestFreeAlternative(
        requestedId: String,
        takenCanonicalKeys: Set<String>,
        stableSuffix: String? = null
    ): String {
        val normalizedTaken = takenCanonicalKeys.mapTo(HashSet()) { canonicalKey(it) }
        val base = sanitizeFragment(requestedId).ifBlank { FALLBACK_PREFIX }
        normalizedTaken.add(canonicalKey(base))

        val zeroPaddedMatch = ZERO_PADDED_SUFFIX_PATTERN.matchEntire(base)
        if (zeroPaddedMatch != null) {
            val prefix = zeroPaddedMatch.groupValues[1].trim('-', '_').ifBlank { FALLBACK_PREFIX }
            val numStr = zeroPaddedMatch.groupValues[2]
            val startNum = ((numStr.toIntOrNull() ?: 1) + 1).coerceAtLeast(1)
            val padWidth = numStr.length
            for (n in startNum..9999) {
                val numPart = String.format(Locale.US, "%0${padWidth}d", n)
                val candidate = appendSuffixWithinLimit(prefix, numPart)
                if (canonicalKey(candidate) !in normalizedTaken) {
                    return candidate
                }
            }
        }

        for (n in 2..99) {
            val candidate = appendSuffixWithinLimit(base, n.toString())
            if (canonicalKey(candidate) !in normalizedTaken) {
                return candidate
            }
        }

        val cleanSuffix = sanitizeFragment(stableSuffix).take(SUFFIX_LENGTH).ifBlank { "ALT" }
        val withSuffix = appendSuffixWithinLimit(base, cleanSuffix)
        if (canonicalKey(withSuffix) !in normalizedTaken) {
            return withSuffix
        }

        for (n in 2..999) {
            val candidate = appendSuffixWithinLimit(withSuffix, n.toString())
            if (canonicalKey(candidate) !in normalizedTaken) {
                return candidate
            }
        }
        return withSuffix
    }

    private fun isUsableBase(sanitized: String): Boolean {
        return sanitized.length >= MIN_VEHICLE_ID_LENGTH && HAS_ALPHANUMERIC.containsMatchIn(sanitized)
    }

    private fun appendSuffixWithinLimit(base: String, suffix: String): String {
        val cleanSuffix = suffix.trim('-', '_').uppercase(Locale.US)
        if (cleanSuffix.isEmpty()) {
            return base.take(MAX_VEHICLE_ID_LENGTH).trim('-', '_').ifBlank { FALLBACK_PREFIX }
        }
        val suffixToken = "-$cleanSuffix"
        if (base.endsWith(suffixToken, ignoreCase = true) && base.length <= MAX_VEHICLE_ID_LENGTH) {
            return base
        }
        val maxBaseLen = (MAX_VEHICLE_ID_LENGTH - suffixToken.length).coerceAtLeast(1)
        val trimmedBase = base.take(maxBaseLen).trimEnd('-', '_')
        val safeBase = if (trimmedBase.isEmpty() || !HAS_ALPHANUMERIC.containsMatchIn(trimmedBase)) {
            FALLBACK_PREFIX.take(maxBaseLen)
        } else {
            trimmedBase
        }
        return "$safeBase$suffixToken"
    }
}
