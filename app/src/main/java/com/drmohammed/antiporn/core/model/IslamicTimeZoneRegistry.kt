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
        val displayText: String
            get() = "$gmtOffsetLabel ($majorCities)"

        override fun toString(): String = displayText
    }

    private val OPTIONS: List<TimeZoneOption> = listOf(
        TimeZoneOption(
            id = "Asia/Riyadh",
            gmtOffsetLabel = "GMT+3",
            majorCities = "مكة المكرمة / المدينة المنورة / الرياض / إسطنبول / بغداد / صنعاء / الدوحة",
            ianaTimeZoneId = "Asia/Riyadh"
        ),
        TimeZoneOption(
            id = "Africa/Cairo",
            gmtOffsetLabel = "GMT+2",
            majorCities = "القاهرة / القدس الشريف / طرابلس / الخرطوم / عمّان / بيروت / دمشق",
            ianaTimeZoneId = "Africa/Cairo"
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

    fun isValidIanaId(ianaId: String?): Boolean {
        if (ianaId.isNullOrBlank()) return false
        if (OPTIONS.any { it.ianaTimeZoneId.equals(ianaId, ignoreCase = true) || it.id.equals(ianaId, ignoreCase = true) }) {
            return true
        }
        return AVAILABLE_ZONE_IDS.contains(ianaId)
    }
}
