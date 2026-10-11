package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerGuideRulesTest {
    @Test fun knownBrandsHaveScreensAndUnknownDoesNot() {
        for (m in listOf("Xiaomi", "Redmi", "POCO", "OPPO", "realme", "OnePlus", "vivo", "iQOO", "HUAWEI", "HONOR", "samsung")) {
            assertTrue("$m needs a screen", PowerGuideRules.targets(m).isNotEmpty())
        }
        assertTrue(PowerGuideRules.targets("Google").isEmpty())
        assertTrue(PowerGuideRules.targets("").isEmpty())
    }

    @Test fun xiaomiPointsAtAutostart() {
        assertEquals("com.miui.securitycenter", PowerGuideRules.targets("Xiaomi")[0].pkg)
    }

    @Test fun everyBrandHasAnActionableHint() {
        for (m in listOf("Xiaomi", "OPPO", "vivo", "HUAWEI", "samsung", "Google", "")) {
            assertTrue("$m hint", PowerGuideRules.hint(m).contains("NOVA"))
        }
        assertTrue(PowerGuideRules.hint("Xiaomi").contains("Autostart"))
        assertTrue(PowerGuideRules.hint("samsung").contains("Never sleeping apps"))
    }
}
