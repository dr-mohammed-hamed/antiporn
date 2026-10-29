package com.drmohammed.antiporn.ui.shields

import androidx.annotation.DrawableRes

/**
 * Immutable presentation model representing a single verified active protection shield.
 */
data class ActiveShieldInfo(
    val id: String,
    val title: String,
    val description: String,
    @field:DrawableRes val iconRes: Int,
    val badgeText: String
)
