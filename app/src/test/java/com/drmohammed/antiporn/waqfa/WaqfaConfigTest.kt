package com.drmohammed.antiporn.waqfa

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.waqfa.model.WaqfaConfig
import com.drmohammed.antiporn.waqfa.model.WaqfaTimeoutReaction
import com.drmohammed.antiporn.waqfa.model.WaqfaTimeoutReactionType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaqfaConfigTest {

    @Test
    fun `test default WaqfaConfig values`() {
        val config = WaqfaConfig()
        assertFalse(config.isEnabled)
        assertTrue(config.targetPackages.isEmpty())
        assertEquals(listOf(10, 15, 30), config.quickDurationsMinutes)
        assertFalse(config.isReasonRequired)
        assertEquals(WaqfaTimeoutReactionType.EXIT_ONLY, config.timeoutReactionType)
        assertEquals(3, config.cooldownMinutes)
    }

    @Test
    fun `test WaqfaConfig toMap and fromMap serialization roundtrip`() {
        val original = WaqfaConfig(
            isEnabled = true,
            targetPackages = setOf("com.google.android.youtube", "org.telegram.messenger"),
            quickDurationsMinutes = listOf(5, 15, 45),
            isReasonRequired = true,
            timeoutReactionType = WaqfaTimeoutReactionType.EXIT_AND_FREEZE,
            cooldownMinutes = 5
        )

        val map = original.toMap()
        val restored = WaqfaConfig.fromMap(map)

        assertEquals(original.isEnabled, restored.isEnabled)
        assertEquals(original.targetPackages, restored.targetPackages)
        assertEquals(original.quickDurationsMinutes, restored.quickDurationsMinutes)
        assertEquals(original.isReasonRequired, restored.isReasonRequired)
        assertEquals(original.timeoutReactionType, restored.timeoutReactionType)
        assertEquals(original.cooldownMinutes, restored.cooldownMinutes)
    }

    @Test
    fun `test WaqfaConfig toJson and fromJson serialization roundtrip`() {
        val original = WaqfaConfig(
            isEnabled = true,
            targetPackages = setOf("com.instagram.android"),
            quickDurationsMinutes = listOf(10, 20),
            isReasonRequired = true,
            timeoutReactionType = WaqfaTimeoutReactionType.NOTIFY_ONLY,
            cooldownMinutes = 2
        )

        val json = original.toJson()
        val restored = WaqfaConfig.fromJson(json)

        assertEquals(original.isEnabled, restored.isEnabled)
        assertEquals(original.targetPackages, restored.targetPackages)
        assertEquals(original.quickDurationsMinutes, restored.quickDurationsMinutes)
        assertEquals(original.isReasonRequired, restored.isReasonRequired)
        assertEquals(original.timeoutReactionType, restored.timeoutReactionType)
        assertEquals(original.cooldownMinutes, restored.cooldownMinutes)
    }

    @Test
    fun `test WaqfaConfig backward compatibility with null or empty inputs`() {
        val fromNullMap = WaqfaConfig.fromMap(null)
        assertFalse(fromNullMap.isEnabled)

        val fromNullJson = WaqfaConfig.fromJson(null)
        assertFalse(fromNullJson.isEnabled)

        val fromEmptyJson = WaqfaConfig.fromJson(JSONObject())
        assertFalse(fromEmptyJson.isEnabled)
        assertEquals(WaqfaTimeoutReactionType.EXIT_ONLY, fromEmptyJson.timeoutReactionType)
    }

    @Test
    fun `test DevicePolicyBundle roundtrip with WaqfaConfig`() {
        val waqfa = WaqfaConfig(
            isEnabled = true,
            targetPackages = setOf("com.example.test"),
            timeoutReactionType = WaqfaTimeoutReactionType.EXIT_AND_FREEZE,
            cooldownMinutes = 4
        )
        val bundle = DevicePolicyBundle.defaultPolicy().copy(waqfaConfig = waqfa)

        val jsonStr = bundle.toJson()
        val restored = DevicePolicyBundle.fromJson(jsonStr)

        assertEquals(waqfa.isEnabled, restored.waqfaConfig.isEnabled)
        assertEquals(waqfa.targetPackages, restored.waqfaConfig.targetPackages)
        assertEquals(waqfa.timeoutReactionType, restored.waqfaConfig.timeoutReactionType)
        assertEquals(waqfa.cooldownMinutes, restored.waqfaConfig.cooldownMinutes)
    }

    @Test
    fun `test WaqfaTimeoutReaction strategy factory`() {
        val r1 = WaqfaTimeoutReaction.fromType(WaqfaTimeoutReactionType.EXIT_ONLY)
        assertTrue(r1 is WaqfaTimeoutReaction.ExitOnly)

        val r2 = WaqfaTimeoutReaction.fromType(WaqfaTimeoutReactionType.EXIT_AND_FREEZE, 7)
        assertTrue(r2 is WaqfaTimeoutReaction.ExitAndFreeze)
        assertEquals(7, (r2 as WaqfaTimeoutReaction.ExitAndFreeze).cooldownMinutes)

        val r3 = WaqfaTimeoutReaction.fromType(WaqfaTimeoutReactionType.NOTIFY_ONLY)
        assertTrue(r3 is WaqfaTimeoutReaction.NotifyOnly)
    }
}
