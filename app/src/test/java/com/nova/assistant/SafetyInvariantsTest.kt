package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the hard rules. If a change (by a person or by the self-improve loop) breaks one of these,
 * the build fails and no pull request is opened. Do not weaken a test without the owner's explicit approval.
 */
class SafetyInvariantsTest {
    private val own = "com.nova.assistant"

    // ---- spoken yes/no: only a short, purely affirmative answer approves anything
    @Test fun unclearAnswersNeverApprove() {
        assertNull(Logic.parseAnswer("maybe"))
        assertNull(Logic.parseAnswer(""))
        assertNull(Logic.parseAnswer("[unk]"))
        assertNull(Logic.parseAnswer("send it to everyone now"))
        assertEquals(false, Logic.parseAnswer("yes no"))
        assertEquals(false, Logic.parseAnswer("no"))
        assertEquals(false, Logic.parseAnswer("yes but don't"))
        assertEquals(true, Logic.parseAnswer("yes"))
    }

    // ---- risky screen actions need a yes
    @Test fun riskyLabelsAreRisky() {
        for (w in Control.RISKY) assertTrue("label '$w' must be risky", Control.isRiskyLabel(w))
        for (w in listOf("send", "pay", "delete", "buy", "transfer", "uninstall", "submit", "confirm")) {
            assertTrue("'$w' must stay on the risky list", w in Control.RISKY)
        }
        assertTrue(Control.isRiskyLabel("send message"))
        assertFalse(Control.isRiskyLabel("search"))
    }

    // ---- tap/type never work in sensitive places
    @Test fun sensitiveAppsAreBlockedForTapAndType() {
        val sensitive = listOf(
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            "com.android.settings", "com.android.vending",
            "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp",
            "com.google.android.apps.nbu.paisa.user", "com.somebank.mobile", "com.example.wallet", own
        )
        for (p in sensitive) for (k in listOf("tap", "type")) {
            assertTrue("$k must be blocked in $p", Control.blocked(p, own, k))
        }
        assertTrue(Control.blocked(null, own, "tap"))
        assertTrue(Control.blocked("", own, "type"))
        assertFalse(Control.blocked("com.android.settings", own, "scroll"))
        assertFalse(Control.blocked("com.android.chrome", own, "tap"))
    }

    // ---- shortcuts and model suggestions can never contain tap / type / call / send
    @Test fun shortcutKindsContainNoScreenOrMessageActions() {
        for (k in listOf("tap", "type", "call", "whatsapp", "sms", "send", "analyze", "update_check", "monitor_on", "stop", "skill", "drive_reply", "drive_on", "drive_dictate")) {
            assertFalse("SAFE_KINDS must not contain $k", k in Brain.SAFE_KINDS)
        }
    }

    @Test fun knowledgeAndLocalSkillAreNotShortcutKinds() {
        assertFalse("knowledge" in Brain.SAFE_KINDS)
        assertFalse("local_skill" in Brain.SAFE_KINDS)
    }

    @Test fun skillsCannotHoldTapOrTypeSteps() {
        assertNotNull(Brain.validateSkill(Skill("bad one", listOf("do my thing"), listOf("tap send"), "user")))
        assertNotNull(Brain.validateSkill(Skill("bad two", listOf("do my thing"), listOf("type hello"), "user")))
        assertNotNull(Brain.validateSkill(Skill("hides battery", listOf("battery"), listOf("torch on"), "user")))
        assertNull(Brain.validateSkill(Skill("fine", listOf("lights please"), listOf("torch on"), "user")))
    }

    // ---- downloads: https + allow-listed host only
    @Test fun hostAllowListIsExactlyTheApprovedOne() {
        val expected = setOf(
            "raw.githubusercontent.com", "github.com", "objects.githubusercontent.com",
            "release-assets.githubusercontent.com", "huggingface.co", "cdn-lfs.huggingface.co",
            "cdn-lfs-us-1.huggingface.co", "cas-bridge.xethub.hf.co"
        )
        assertEquals(expected, Updater.HOSTS)
    }

    @Test fun hostChecksResistTricks() {
        assertTrue(Updater.hostAllowed("https://github.com/a/b/releases/download/v1/x.apk"))
        assertFalse(Updater.hostAllowed("http://github.com/a/b"))
        assertFalse(Updater.hostAllowed("https://evil.example.com/a"))
        assertFalse(Updater.hostAllowed("https://github.com@evil.example.com/a"))
        assertFalse(Updater.hostAllowed("https://raw.githubusercontent.com.evil.example.com/a"))
        assertFalse(Updater.hostAllowed("https://notgithub.com/a"))
        assertFalse(Updater.hostAllowed("ftp://github.com/a"))
    }

    private val sha = "a".repeat(64)
    private fun item(type: String = "skills", url: String = "https://raw.githubusercontent.com/a/b/main/x.json",
                     sha256: String = sha, size: Long = 100, version: Int = 7, minApp: Int = 0) =
        UpdateItem("some-item", type, "A title", url, sha256, size, version, minApp)

    @Test fun updateRulesStayStrict() {
        assertNull(Updater.validate(item(), 6, null))
        assertEquals("bad sha256", Updater.validate(item(sha256 = "abc"), 6, null))
        assertEquals("bad size", Updater.validate(item(size = 0), 6, null))
        assertEquals("bad size", Updater.validate(item(size = Updater.MAX_SKILLS_BYTES + 1), 6, null))
        assertEquals("download host not allowed", Updater.validate(item(url = "https://evil.example.com/x.json"), 6, null))
        assertEquals("download host not allowed", Updater.validate(item(url = "http://github.com/x.json"), 6, null))
        assertEquals("needs a newer app", Updater.validate(item(minApp = 99), 6, null))
        assertEquals("not newer than the installed app", Updater.validate(item(type = "apk", version = 6), 6, null))
        assertEquals("not newer than the installed app", Updater.validate(item(type = "apk", version = 5), 6, null))
        assertNull(Updater.validate(item(type = "apk", version = 7, url = "https://github.com/a/b/releases/download/v7/nova-vc7.apk"), 6, null))
        assertEquals("bad type", Updater.validate(item(type = "script"), 6, null))
    }

    @Test fun sizeCapsDidNotGrow() {
        assertTrue(Updater.MAX_APK_BYTES <= 150L * 1024 * 1024)
        assertTrue(Updater.MAX_SKILLS_BYTES <= 200L * 1024)
        assertTrue(Updater.MAX_MODEL_BYTES <= 2L * 1024 * 1024 * 1024)
    }

    // ---- model suggestions are translated, never trusted
    @Test fun modelCannotSneakInDangerousCommands() {
        assertNull(LocalBrainRules.parseOutput("type hello"))
        assertNull(LocalBrainRules.parseOutput("yes"))
        assertNull(LocalBrainRules.parseOutput("check updates"))
    }

    // ---- PART 2 personal memory: kinds are never shortcuts, skill steps or model suggestions; secrets are never stored
    @Test fun memoryKindsAreNeverSafeKindsOrAcceptedFromTheModel() {
        for (k in PersonalMemory.KINDS) {
            assertFalse("SAFE_KINDS must not contain $k", k in Brain.SAFE_KINDS)
            assertFalse("the offline model must not trigger $k", LocalBrainRules.accepted(Logic.Cmd(k, "x")))
        }
        assertNotNull(Brain.validateSkill(Skill("mem step", listOf("do memory thing"), listOf("yaad rakh mera naam shiv hai"), "user")))
    }

    @Test fun secretsAreNeverStoredInPersonalMemory() {
        for (s in listOf("my otp is 1234", "wifi password hunter2", "atm pin 4321", "card number 4111 1111 1111 1111", "aadhaar 1234 5678 9012")) {
            assertEquals("must refuse: $s", "refused", PersonalMemory.add(emptyList(), s).status)
        }
    }
}
