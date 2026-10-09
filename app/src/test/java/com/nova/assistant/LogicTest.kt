package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for Logic.kt. Keys used here are obviously fake ("x" repeated), never real. */
class LogicTest {

    private fun cmd(kind: String, arg: String = "", num: Int = 0) = Logic.Cmd(kind, arg, num)

    // ------------------------------------------------------------ wake word / models / clamps

    @Test fun cleanWake_acceptsOneEnglishWord() {
        assertEquals("nova", Logic.cleanWake(" Nova "))
        assertEquals("abcdefghijkl", Logic.cleanWake("abcdefghijkl"))
        assertNull(Logic.cleanWake("ab"))
        assertNull(Logic.cleanWake("nova1"))
        assertNull(Logic.cleanWake("hey nova"))
        assertNull(Logic.cleanWake("abcdefghijklm"))
        assertNull(Logic.cleanWake(null))
    }

    @Test fun wakeOrDefault_fallsBackToNova() {
        assertEquals("nova", Logic.wakeOrDefault(null))
        assertEquals("nova", Logic.wakeOrDefault("x"))
        assertEquals("jarvis", Logic.wakeOrDefault("Jarvis"))
    }

    @Test fun cleanModel_onlyKnownIds() {
        assertEquals(Logic.DEFAULT_MODEL, Logic.cleanModel(null))
        assertEquals(Logic.DEFAULT_MODEL, Logic.cleanModel("gemini-3.8-flash"))
        assertEquals(Logic.DEFAULT_MODEL, Logic.cleanModel("gemini-2.5-flash"))
        for (m in Logic.MODELS) assertEquals(m, Logic.cleanModel(m))
        assertTrue(Logic.DEFAULT_MODEL in Logic.MODELS)
        assertTrue(Logic.MODELS.none { it.contains("2.5") })
    }

    @Test fun clamps() {
        assertEquals(1.6f, Logic.clampRate(5f), 0.0001f)
        assertEquals(0.6f, Logic.clampRate(0f), 0.0001f)
        assertEquals(1.0f, Logic.clampRate(1.0f), 0.0001f)
        assertEquals(1.4f, Logic.clampPitch(9f), 0.0001f)
        assertEquals(0.7f, Logic.clampPitch(0.1f), 0.0001f)
    }

    @Test fun wakeGrammar_containsWordAndUnk() {
        val g = Logic.wakeGrammar("nova")
        assertTrue(g.contains("\"nova\""))
        assertTrue(g.contains("\"hey nova\""))
        assertTrue(g.contains("[unk]"))
    }

    @Test fun extractTextAndHeardWake() {
        assertEquals("hello world", Logic.extractText("""{"text" : "hello world"}"""))
        assertEquals("ni", Logic.extractText("""{"partial" : "ni"}"""))
        assertEquals("", Logic.extractText("{}"))
        assertTrue(Logic.heardWake("""{"text": "hey nova"}""", "nova"))
        assertFalse(Logic.heardWake("""{"text": "innovate"}""", "nova"))
    }

    // ------------------------------------------------------------ yes / no

    @Test fun parseAnswer_clearYes() {
        assertEquals(true, Logic.parseAnswer("yes"))
        assertEquals(true, Logic.parseAnswer("Yeah okay"))
        assertEquals(true, Logic.parseAnswer("haan"))
        assertEquals(true, Logic.parseAnswer("okay sure ji"))
    }

    @Test fun parseAnswer_noWins() {
        assertEquals(false, Logic.parseAnswer("no"))
        assertEquals(false, Logic.parseAnswer("yes no"))
        assertEquals(false, Logic.parseAnswer("wait yes"))
        assertEquals(false, Logic.parseAnswer("don't"))
        assertEquals(false, Logic.parseAnswer("stop"))
        assertEquals(false, Logic.parseAnswer("nahi"))
    }

    @Test fun parseAnswer_unclearIsNeverTrue() {
        assertNull(Logic.parseAnswer(""))
        assertNull(Logic.parseAnswer("[unk]"))
        assertNull(Logic.parseAnswer("maybe"))
        assertNull(Logic.parseAnswer("yes please"))
        assertNull(Logic.parseAnswer("yes call him now"))
        assertNull(Logic.parseAnswer("yes yes yes yes"))
    }

    @Test fun isExpired() {
        assertFalse(Logic.isExpired(1000L, 1500L, 30000L))
        assertFalse(Logic.isExpired(1000L, 31000L, 30000L))
        assertTrue(Logic.isExpired(1000L, 31001L, 30000L))
        assertTrue(Logic.isExpired(5000L, 1000L, 30000L))   // clock went backwards -> dead
    }

    // ------------------------------------------------------------ classify: no-cloud commands

    @Test fun classify_stop() {
        assertEquals(cmd("stop"), Logic.classify("stop"))
        assertEquals(cmd("stop"), Logic.classify("please stop"))
        assertEquals(cmd("stop"), Logic.classify("be quiet"))
        assertEquals(cmd("stop"), Logic.classify("chup"))
    }

    @Test fun classify_battery() {
        assertEquals(cmd("battery"), Logic.classify("battery"))
        assertEquals(cmd("battery"), Logic.classify("battery status please"))
        assertEquals(cmd("settings", "battery"), Logic.classify("battery settings"))
        assertNull(Logic.classify("my phone battery has been draining very fast since the update yesterday evening"))
    }

    @Test fun classify_timeVsTimeZone() {
        assertEquals(cmd("time"), Logic.classify("what time is it"))
        assertEquals(cmd("time"), Logic.classify("nova what time is it"))
        assertNull(Logic.classify("time zone"))
        assertNull(Logic.classify("set a timer for five minutes"))
    }

    @Test fun classify_date() {
        assertEquals(cmd("date"), Logic.classify("date"))
        assertEquals(cmd("date"), Logic.classify("aaj ki tarikh"))
    }

    @Test fun classify_torch() {
        assertEquals(cmd("torch_on"), Logic.classify("turn on the torch"))
        assertEquals(cmd("torch_on"), Logic.classify("flash light on"))
        assertEquals(cmd("torch_on"), Logic.classify("[unk] torch on"))
        assertEquals(cmd("torch_off"), Logic.classify("torch off"))
        assertEquals(cmd("torch_off"), Logic.classify("torch band karo"))
        assertNull(Logic.classify("torch"))
    }

    @Test fun classify_volume() {
        assertEquals(cmd("volume", "up"), Logic.classify("volume up"))
        assertEquals(cmd("volume", "up"), Logic.classify("increase the volume"))
        assertEquals(cmd("volume", "down"), Logic.classify("volume down"))
        assertEquals(cmd("volume", "mute"), Logic.classify("volume mute"))
        assertEquals(cmd("volume", "max"), Logic.classify("volume full"))
        // "set" must be a real set, never the "raise" branch
        assertEquals(cmd("volume", "set", 50), Logic.classify("volume fifty"))
        assertEquals(cmd("volume", "set", 75), Logic.classify("set volume to seventy five"))
        assertEquals(cmd("volume", "set", 30), Logic.classify("volume 30"))
        assertEquals(cmd("volume", "set", 100), Logic.classify("volume five hundred"))
        assertNull(Logic.classify("volume"))
    }

    @Test fun classify_brightness() {
        assertEquals(cmd("brightness_set", "", 50), Logic.classify("brightness fifty percent"))
        assertEquals(cmd("brightness_set", "", 100), Logic.classify("brightness max"))
        assertEquals(cmd("brightness_set", "", 5), Logic.classify("brightness minimum"))
        assertEquals(cmd("brightness_step", "", 10), Logic.classify("brightness up"))
        assertEquals(cmd("brightness_step", "", 10), Logic.classify("increase brightness"))
        assertEquals(cmd("brightness_step", "", -10), Logic.classify("brightness down"))
        assertNull(Logic.classify("brightness"))
    }

    @Test fun classify_media() {
        assertEquals(cmd("media", "pause"), Logic.classify("pause"))
        assertEquals(cmd("media", "pause"), Logic.classify("pause music"))
        assertEquals(cmd("media", "pause"), Logic.classify("stop music"))
        assertEquals(cmd("media", "play"), Logic.classify("play music"))
        assertEquals(cmd("media", "play"), Logic.classify("resume"))
        assertEquals(cmd("media", "next"), Logic.classify("next song"))
        assertEquals(cmd("media", "previous"), Logic.classify("previous"))
        assertEquals(cmd("media", "previous"), Logic.classify("last song"))
        assertNull(Logic.classify("play"))
    }

    @Test fun classify_navigation() {
        assertEquals(cmd("global", "home"), Logic.classify("go home"))
        assertEquals(cmd("global", "home"), Logic.classify("home"))
        assertEquals(cmd("global", "back"), Logic.classify("back"))
        assertEquals(cmd("global", "recents"), Logic.classify("recent apps"))
        assertEquals(cmd("global", "recents"), Logic.classify("open recents"))
        assertEquals(cmd("global", "notifications"), Logic.classify("show notifications"))
        assertEquals(cmd("global", "quick_settings"), Logic.classify("quick settings"))
        assertEquals(cmd("global", "lock"), Logic.classify("lock"))
        assertEquals(cmd("global", "lock"), Logic.classify("lock the phone"))
    }

    @Test fun classify_settingsPages() {
        assertEquals(cmd("settings", "main"), Logic.classify("open settings"))
        assertEquals(cmd("settings", "wifi"), Logic.classify("open wifi settings"))
        assertEquals(cmd("settings", "wifi", 2), Logic.classify("wifi on"))
        assertEquals(cmd("settings", "bluetooth", 2), Logic.classify("turn off bluetooth"))
        assertEquals(cmd("settings", "wifi", 2), Logic.classify("turn on wi-fi"))
    }

    @Test fun classify_openApps() {
        assertEquals(cmd("open_app", "whatsapp"), Logic.classify("open whatsapp"))
        assertEquals(cmd("open_app", "whatsapp"), Logic.classify("Nova, open WhatsApp."))
        assertEquals(cmd("open_app", "whatsapp"), Logic.classify("open whatsapp app"))
        assertEquals(cmd("open_app", "whatsapp"), Logic.classify("whatsapp kholo"))
        assertEquals(cmd("open_app", "youtube"), Logic.classify("open you tube"))
        assertEquals(cmd("camera"), Logic.classify("open the camera"))
        assertEquals(cmd("camera"), Logic.classify("camera"))
    }

    @Test fun classify_monitorAndAnalyze() {
        assertEquals(cmd("monitor_on"), Logic.classify("monitor on"))
        assertEquals(cmd("monitor_on"), Logic.classify("screen monitoring on"))
        assertEquals(cmd("monitor_off"), Logic.classify("monitor off"))
        assertEquals(cmd("monitor_off"), Logic.classify("screen monitoring band"))
        assertEquals(cmd("analyze"), Logic.classify("analyze screen"))
        assertEquals(cmd("analyze"), Logic.classify("what is on my screen"))
        assertEquals(cmd("analyze"), Logic.classify("read the screen"))
    }

    @Test fun classify_longOrUnknownGoesToCloudPath() {
        assertNull(Logic.classify(""))
        assertNull(Logic.classify("[unk]"))
        assertNull(Logic.classify("what is the capital of france"))
        assertNull(Logic.classify("tell me a story about a dragon who loves music and flies over mountains every morning"))
        assertNull(Logic.classify("open " + "a".repeat(200)))
        assertNull(Logic.classify("set an alarm for seven"))
    }

    @Test fun norm_makesVoskTextComparable() {
        assertEquals("wifi on", Logic.norm("Wi-Fi ON!"))
        assertEquals("open youtube", Logic.norm("open you tube"))
        assertEquals("torch on", Logic.norm("[unk] torch   on"))
    }

    // ------------------------------------------------------------ numbers

    @Test fun parseNumber() {
        assertEquals(50, Logic.parseNumber(listOf("fifty")))
        assertEquals(25, Logic.parseNumber(listOf("twenty", "five")))
        assertEquals(100, Logic.parseNumber(listOf("one", "hundred")))
        assertEquals(100, Logic.parseNumber(listOf("hundred")))
        assertEquals(11, Logic.parseNumber(listOf("eleven")))
        assertEquals(50, Logic.parseNumber(listOf("50%")))
        assertEquals(30, Logic.parseNumber(listOf("volume", "30")))
        assertNull(Logic.parseNumber(listOf("hello")))
        assertNull(Logic.parseNumber(emptyList()))
    }

    // ------------------------------------------------------------ cloud failover

    @Test fun nextStep_rules() {
        assertEquals(Logic.Next.SUCCESS, Logic.nextStep(200, ""))
        assertEquals(Logic.Next.SUCCESS, Logic.nextStep(204, ""))
        assertEquals(Logic.Next.STOP, Logic.nextStep(598, ""))
        assertEquals(Logic.Next.NEXT_KEY, Logic.nextStep(599, ""))
        assertEquals(Logic.Next.NEXT_KEY, Logic.nextStep(401, ""))
        assertEquals(Logic.Next.NEXT_KEY, Logic.nextStep(403, ""))
        assertEquals(Logic.Next.NEXT_KEY, Logic.nextStep(429, ""))
        assertEquals(Logic.Next.NEXT_KEY, Logic.nextStep(500, ""))
        assertEquals(Logic.Next.NEXT_KEY, Logic.nextStep(503, ""))
        assertEquals(Logic.Next.NEXT_MODEL, Logic.nextStep(404, ""))
        assertEquals(Logic.Next.NEXT_KEY, Logic.nextStep(400, "API key not valid"))
        assertEquals(Logic.Next.NEXT_KEY, Logic.nextStep(400, "reason: API_KEY_INVALID"))
        assertEquals(Logic.Next.STOP, Logic.nextStep(400, "bad request"))
        assertEquals(Logic.Next.STOP, Logic.nextStep(418, ""))
        assertEquals(Logic.Next.STOP, Logic.nextStep(302, ""))
    }

    @Test fun failureKind() {
        assertEquals("nokey", Logic.failureKind(0))
        assertEquals("network", Logic.failureKind(598))
        assertEquals("network", Logic.failureKind(599))
        assertEquals("key", Logic.failureKind(400))
        assertEquals("key", Logic.failureKind(401))
        assertEquals("key", Logic.failureKind(403))
        assertEquals("quota", Logic.failureKind(429))
        assertEquals("model", Logic.failureKind(404))
        assertEquals("server", Logic.failureKind(500))
        assertEquals("server", Logic.failureKind(503))
        assertEquals("other", Logic.failureKind(418))
    }

    @Test fun parseKeys_splitsDedupesAndBounds() {
        val a = "x".repeat(30)
        val b = "y".repeat(25)
        val c = "z".repeat(40)
        assertEquals(listOf(a, b, c), Logic.parseKeys("  $a \r\n$b, $c;$a\n"))
        assertTrue(Logic.parseKeys("").isEmpty())
        assertTrue(Logic.parseKeys("short").isEmpty())
        assertTrue(Logic.parseKeys("x".repeat(15) + " " + "y".repeat(15)).isEmpty())   // inner space -> not a key
        assertTrue(Logic.parseKeys("q".repeat(201)).isEmpty())
        val many = (0 until 12).joinToString("\n") { "k" + it.toString().padStart(2, '0') + "x".repeat(20) }
        val parsed = Logic.parseKeys(many)
        assertEquals(Logic.MAX_KEYS, parsed.size)
        assertEquals("k00" + "x".repeat(20), parsed[0])
    }

    @Test fun boundHistory_dropsPairs() {
        val even = (1..8).toMutableList()
        Logic.boundHistory(even, 6)
        assertEquals(listOf(3, 4, 5, 6, 7, 8), even)
        val odd = (1..7).toMutableList()
        Logic.boundHistory(odd, 6)
        assertEquals(listOf(3, 4, 5, 6, 7), odd)
        val small = mutableListOf(1, 2)
        Logic.boundHistory(small, 6)
        assertEquals(listOf(1, 2), small)
        val none = mutableListOf<Int>()
        Logic.boundHistory(none, 6)
        assertTrue(none.isEmpty())
    }

    // ------------------------------------------------------------ apps / contacts / messages

    @Test fun bestAppIndex() {
        val labels = listOf("WhatsApp", "WhatsApp Business", "Chrome", "YouTube", "YouTube Music")
        assertEquals(0, Logic.bestAppIndex("whatsapp", labels))
        assertEquals(3, Logic.bestAppIndex("youtube", labels))
        assertEquals(3, Logic.bestAppIndex("you tube", labels))
        assertEquals(1, Logic.bestAppIndex("business", labels))
        assertEquals(2, Logic.bestAppIndex("chro", labels))
        assertEquals(4, Logic.bestAppIndex("music", labels))
        assertEquals(-1, Logic.bestAppIndex("calculator", labels))
        assertEquals(-1, Logic.bestAppIndex("", labels))
        assertEquals(-1, Logic.bestAppIndex("chrome", emptyList()))
        assertEquals(0, Logic.bestAppIndex("camera", listOf("Camera Pro", "Camera Lite X")))
        assertEquals(1, Logic.bestAppIndex("maps", listOf("Google Maps", "Maps")))
    }

    @Test fun pickContact() {
        val names = listOf("Rahul Sharma", "Rahul", "Amit")
        assertEquals(1, Logic.pickContact("rahul", names))
        assertEquals(0, Logic.pickContact("sharma", names))
        assertEquals(2, Logic.pickContact("Amit", names))
        assertEquals(-1, Logic.pickContact("rahul", emptyList()))
    }

    @Test fun waNumber() {
        assertEquals("919876543210", Logic.waNumber("9876543210"))
        assertEquals("919876543210", Logic.waNumber("09876543210"))
        assertEquals("919876543210", Logic.waNumber("+91 98765 43210"))
    }

    @Test fun sameMessage() {
        assertTrue(Logic.sameMessage("Hello  World", " hello world "))
        assertFalse(Logic.sameMessage("hello world", "hello there"))
    }

    @Test fun shorten() {
        assertEquals("abc", Logic.shorten("abc", 5))
        assertEquals("xxxxxxxxxx...", Logic.shorten("x".repeat(100), 10))
    }

    // ------------------------------------------------------------ screen helpers

    @Test fun topLines() {
        assertEquals(listOf("Hello", "World"), Logic.topLines(listOf(" Hello ", "Hello", "a", "World"), 5))
        assertEquals(listOf("Hello"), Logic.topLines(listOf(" Hello ", "Hello", "a", "World"), 1))
        assertEquals(listOf("xxxxxxxxxx..."), Logic.topLines(listOf("x".repeat(100)), 3, 10))
    }

    @Test fun detectAlert() {
        assertEquals("payment failed", Logic.detectAlert(listOf("Home", "Payment failed")))
        assertEquals("error", Logic.detectAlert(listOf("Error 404")))
        assertEquals("could not", Logic.detectAlert(listOf("Could not connect")))
        assertEquals("blocked", Logic.detectAlert(listOf("Network blocked")))
        assertEquals("no internet connection", Logic.detectAlert(listOf("No internet connection")))
        assertNull(Logic.detectAlert(listOf("Everything is fine")))
        assertNull(Logic.detectAlert(listOf("Terror alert")))                    // whole words only
        assertNull(Logic.detectAlert(listOf("x".repeat(101) + " error")))        // long chat text ignored
        assertNull(Logic.detectAlert(emptyList()))
    }

    @Test fun shouldAnnounce_spamGuard() {
        assertTrue(Logic.shouldAnnounce(1000L, -1L, "", "error"))
        assertFalse(Logic.shouldAnnounce(1000L + Logic.ALERT_SAME_GAP_MS - 1, 1000L, "error", "error"))
        assertTrue(Logic.shouldAnnounce(1000L + Logic.ALERT_SAME_GAP_MS, 1000L, "error", "error"))
        assertFalse(Logic.shouldAnnounce(1000L + Logic.ALERT_MIN_GAP_MS - 1, 1000L, "error", "blocked"))
        assertTrue(Logic.shouldAnnounce(1000L + Logic.ALERT_MIN_GAP_MS, 1000L, "error", "blocked"))
    }

    @Test fun constants_areSane() {
        assertTrue(Logic.END_SILENCE_MS in 300..6000)
        assertTrue(Logic.MAX_COMMAND_MS > Logic.NO_SPEECH_COMMAND_MS)
        assertTrue(Logic.MAX_ANSWER_MS > 0)
        assertTrue(Logic.MAX_CALLS >= Logic.MODELS.size)
        assertTrue(Logic.HISTORY_MAX % 2 == 0)
        assertNotNull(Logic.DEFAULT_WAKE)
    }
}
