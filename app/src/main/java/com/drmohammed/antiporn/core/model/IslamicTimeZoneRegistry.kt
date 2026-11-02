package com.drmohammed.antiporn.core.model

import java.util.TimeZone

/**
 * Curated registry of GMT time zones mapped to major Islamic and Arab cities.
 * Enables the guardian to easily adjust ward devices to their local time zone
 * without risking manual time-skipping or tampering with security timers.
 */
object IslamicTimeZoneRegistry {

    data class TimeZoneOption(
        val id: String,
        val gmtOffsetLabel: String,
        val majorCities: String,
        val ianaTimeZoneId: String
    ) {
        fun getLiveOffsetLabel(): String {
            return try {
                val tz = TimeZone.getTimeZone(ianaTimeZoneId)
                val now = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis()
                val offsetMs = tz.getOffset(now)
                val offsetHours = offsetMs / 3_600_000
                val offsetMins = kotlin.math.abs((offsetMs % 3_600_000) / 60_000)
                if (offsetMins == 0) {
                    val sign = if (offsetHours >= 0) "+$offsetHours" else "$offsetHours"
                    "GMT$sign"
                } else {
                    val sign = if (offsetHours >= 0) "+$offsetHours" else "$offsetHours"
                    String.format(java.util.Locale.US, "GMT%s:%02d", sign, offsetMins)
                }
            } catch (_: Exception) {
                gmtOffsetLabel
            }
        }

        val displayText: String
            get() = "${getLiveOffsetLabel()} ($majorCities)"

        override fun toString(): String = displayText
    }

    private val OPTIONS: List<TimeZoneOption> = listOf(
        TimeZoneOption(
            id = "Africa/Cairo",
            gmtOffsetLabel = "GMT+2",
            majorCities = "القاهرة / القدس الشريف / طرابلس / الخرطوم / عمّان / بيروت / دمشق",
            ianaTimeZoneId = "Africa/Cairo"
        ),
        TimeZoneOption(
            id = "Asia/Riyadh",
            gmtOffsetLabel = "GMT+3",
            majorCities = "مكة المكرمة / المدينة المنورة / الرياض / إسطنبول / بغداد / صنعاء / الدوحة",
            ianaTimeZoneId = "Asia/Riyadh"
        ),
        TimeZoneOption(
            id = "Africa/Algiers",
            gmtOffsetLabel = "GMT+1",
            majorCities = "الجزائر / تونس / الرباط",
            ianaTimeZoneId = "Africa/Algiers"
        ),
        TimeZoneOption(
            id = "Africa/Casablanca",
            gmtOffsetLabel = "GMT+0",
            majorCities = "الدار البيضاء / نواكشوط / لندن",
            ianaTimeZoneId = "Africa/Casablanca"
        ),
        TimeZoneOption(
            id = "Asia/Dubai",
            gmtOffsetLabel = "GMT+4",
            majorCities = "دبي / أبوظبي / مسقط / باكو",
            ianaTimeZoneId = "Asia/Dubai"
        ),
        TimeZoneOption(
            id = "Asia/Karachi",
            gmtOffsetLabel = "GMT+5",
            majorCities = "إسلام آباد / كراتشي / طشقند",
            ianaTimeZoneId = "Asia/Karachi"
        ),
        TimeZoneOption(
            id = "Asia/Dhaka",
            gmtOffsetLabel = "GMT+6",
            majorCities = "دكا / ألماتي",
            ianaTimeZoneId = "Asia/Dhaka"
        ),
        TimeZoneOption(
            id = "Asia/Jakarta",
            gmtOffsetLabel = "GMT+7",
            majorCities = "جاكرتا / سومطرة",
            ianaTimeZoneId = "Asia/Jakarta"
        ),
        TimeZoneOption(
            id = "Asia/Kuala_Lumpur",
            gmtOffsetLabel = "GMT+8",
            majorCities = "كوالالمبور / بروناي",
            ianaTimeZoneId = "Asia/Kuala_Lumpur"
        ),
        TimeZoneOption(
            id = "America/New_York",
            gmtOffsetLabel = "GMT-5",
            majorCities = "نيويورك / مونتريال",
            ianaTimeZoneId = "America/New_York"
        ),
        TimeZoneOption(
            id = "America/Los_Angeles",
            gmtOffsetLabel = "GMT-8",
            majorCities = "كاليفورنيا / فانكوفر",
            ianaTimeZoneId = "America/Los_Angeles"
        )
    )

    private val AVAILABLE_ZONE_IDS: Set<String> by lazy {
        TimeZone.getAvailableIDs().toSet()
    }

    fun getAllOptions(): List<TimeZoneOption> = OPTIONS

    fun getDefaultOption(): TimeZoneOption = OPTIONS[0]

    fun findByIanaId(ianaId: String?): TimeZoneOption {
        if (ianaId.isNullOrBlank()) return getDefaultOption()
        return OPTIONS.firstOrNull {
            it.ianaTimeZoneId.equals(ianaId, ignoreCase = true) ||
                it.id.equals(ianaId, ignoreCase = true) ||
                it.gmtOffsetLabel.equals(ianaId, ignoreCase = true)
        } ?: getDefaultOption()
    }

    fun findById(id: String?): TimeZoneOption = findByIanaId(id)

    fun detectCurrentDeviceOption(): TimeZoneOption {
        return try {
            val currentTz = TimeZone.getDefault()
            val localId = currentTz.id
            val exact = OPTIONS.firstOrNull {
                it.ianaTimeZoneId.equals(localId, ignoreCase = true) || it.id.equals(localId, ignoreCase = true)
            }
            if (exact != null) return exact

            val now = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis()
            val currentOffset = currentTz.getOffset(now)
            OPTIONS.firstOrNull {
                TimeZone.getTimeZone(it.ianaTimeZoneId).getOffset(now) == currentOffset
            } ?: getDefaultOption()
        } catch (_: Exception) {
            getDefaultOption()
        }
    }

    fun isValidIanaId(ianaId: String?): Boolean {
        if (ianaId.isNullOrBlank()) return false
        if (OPTIONS.any { it.ianaTimeZoneId.equals(ianaId, ignoreCase = true) || it.id.equals(ianaId, ignoreCase = true) }) {
            return true
        }
        return AVAILABLE_ZONE_IDS.contains(ianaId)
    }
}
