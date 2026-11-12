package com.drmohammed.antiporn.curfew

import java.util.Calendar

/**
 * Pure curfew schedule window arithmetic supporting same-day windows,
 * cross-midnight windows, and Thursday-evening weekend transitions.
 */
internal object CurfewScheduleExt {

    fun isMinuteInWindow(minuteOfDay: Int, startMinute: Int, endMinute: Int): Boolean {
        if (startMinute == endMinute) return false
        return if (startMinute < endMinute) {
            minuteOfDay in startMinute until endMinute
        } else {
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        }
    }

    fun isScheduleWindowActive(
        calendar: Calendar,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        isWeekendCurfewEnabled: Boolean,
        weekendStartHour: Int,
        weekendStartMinute: Int,
        weekendEndHour: Int,
        weekendEndMinute: Int
    ): Boolean {
        val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val weekdayStart = startHour * 60 + startMinute
        val weekdayEnd = endHour * 60 + endMinute

        if (!isWeekendCurfewEnabled) {
            return isMinuteInWindow(minuteOfDay, weekdayStart, weekdayEnd)
        }

        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val weekendStart = weekendStartHour * 60 + weekendStartMinute
        val weekendEnd = weekendEndHour * 60 + weekendEndMinute

        return when (dayOfWeek) {
            Calendar.FRIDAY, Calendar.SATURDAY -> {
                isMinuteInWindow(minuteOfDay, weekendStart, weekendEnd)
            }
            Calendar.THURSDAY -> {
                // Morning spill only exists when Wednesday night crossed midnight;
                // never re-apply a same-day weekday window ahead of weekendStart on Thursday afternoon.
                val inMorning = if (weekdayStart > weekdayEnd) {
                    minuteOfDay < weekdayEnd
                } else {
                    false
                }
                val inEvening = when {
                    weekendStart == weekendEnd -> false
                    weekendStart > weekendEnd -> minuteOfDay >= weekendStart
                    else -> minuteOfDay in weekendStart until weekendEnd
                }
                inMorning || inEvening
            }
            else -> {
                isMinuteInWindow(minuteOfDay, weekdayStart, weekdayEnd)
            }
        }
    }

    fun getCurfewEndTimesForDay(
        dayOfWeek: Int,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        isWeekendCurfewEnabled: Boolean,
        weekendStartHour: Int,
        weekendStartMinute: Int,
        weekendEndHour: Int,
        weekendEndMinute: Int
    ): List<Pair<Int, Int>> {
        if (!isWeekendCurfewEnabled) {
            return listOf(endHour to endMinute)
        }
        val weekdayStartMin = startHour * 60 + startMinute
        val weekdayEndMin = endHour * 60 + endMinute
        val weekendStartMin = weekendStartHour * 60 + weekendStartMinute
        val weekendEndMin = weekendEndHour * 60 + weekendEndMinute

        return when (dayOfWeek) {
            Calendar.FRIDAY, Calendar.SATURDAY -> listOf(weekendEndHour to weekendEndMinute)
            Calendar.THURSDAY -> buildList {
                if (weekdayStartMin > weekdayEndMin) {
                    add(endHour to endMinute)
                }
                if (weekendStartMin < weekendEndMin) {
                    add(weekendEndHour to weekendEndMinute)
                }
            }.distinct().sortedBy { it.first * 60 + it.second }
            else -> listOf(endHour to endMinute)
        }
    }

    fun resolveActiveCurfewEnd(
        calendar: Calendar,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        isWeekendCurfewEnabled: Boolean,
        weekendStartHour: Int,
        weekendStartMinute: Int,
        weekendEndHour: Int,
        weekendEndMinute: Int
    ): Pair<Int, Int> {
        if (!isWeekendCurfewEnabled) {
            return endHour to endMinute
        }
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val weekdayStartMin = startHour * 60 + startMinute
        val weekdayEndMin = endHour * 60 + endMinute
        val weekendStartMin = weekendStartHour * 60 + weekendStartMinute
        val weekendEndMin = weekendEndHour * 60 + weekendEndMinute

        return when (dayOfWeek) {
            Calendar.FRIDAY, Calendar.SATURDAY -> weekendEndHour to weekendEndMinute
            Calendar.THURSDAY -> {
                val inMorningWedSpill = weekdayStartMin > weekdayEndMin && minuteOfDay < weekdayEndMin
                when {
                    inMorningWedSpill -> endHour to endMinute
                    weekendStartMin < weekendEndMin -> weekendEndHour to weekendEndMinute
                    weekendStartMin > weekendEndMin && minuteOfDay >= weekendStartMin -> weekendEndHour to weekendEndMinute
                    else -> endHour to endMinute
                }
            }
            else -> endHour to endMinute
        }
    }
}
