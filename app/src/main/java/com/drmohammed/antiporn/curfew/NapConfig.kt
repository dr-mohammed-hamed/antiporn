package com.drmohammed.antiporn.curfew

/**
 * ثوابت وضع القيلولة Nap Mode.
 *
 * Fixed 60-minute total blackout (same enforcement engine as the night curfew),
 * optional unlimited +60m extends, and NO in-app cancel/shorten API by design.
 *
 * ساعة ثابتة غير قابلة للتقليل، تمديد غير محدود، بلا زر إلغاء نهائياً.
 */
object NapConfig {
    const val PREFS_NAME = "antiporn_nap_prefs"
    const val KEY_NAP_START_ELAPSED = "key_nap_start_elapsed"
    const val KEY_NAP_END_ELAPSED = "key_nap_end_elapsed"
    const val KEY_NAP_END_WALL_MS = "key_nap_end_wall_ms"
    const val KEY_NAP_ACTIVE = "key_nap_active"
    const val NAP_DURATION_MS: Long = 60 * 60 * 1000L
    const val EXTEND_STEP_MS: Long = 60 * 60 * 1000L
    const val ACTION_NAP_END = "com.drmohammed.antiporn.ACTION_NAP_END"
    const val REQUEST_CODE_NAP_END = 3003
    const val REQUEST_CODE_NAP_END_RTC = 3003
    const val REQUEST_CODE_NAP_END_ELAPSED = 3004
}
