package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v32 loose understanding: long / casual sentences become the short command Logic.classify knows; anything unclear stays null (the AI decides). */
class UnderstandTest {

    private fun ok(said: String, want: String) = assertEquals(said, want, Understand.simplify(said))

    @Test fun longCasualSentences_becomeShortCommands() {
        ok("mere mobile lock kro turant", "lock phone")
        ok("nova mere mobile ko turant lock kar do", "lock phone")
        ok("please phone lock kar do yaar", "lock phone")
        ok("zara screen lock kar dena", "lock phone")
        ok("mera torch jala do", "torch on")
        ok("yaar torch jaldi se band kar de", "torch off")
        ok("flash light on kar do abhi", "torch on")
        ok("awaaz thodi kam kar do", "volume down")
        ok("volume thoda badha do", "volume up")
        ok("phone ki awaaz poori kar do", "volume max")
        ok("phone silent kar do", "volume mute")
        ok("awaaz band kar do", "volume mute")
        ok("brightness thodi zyada kar do", "brightness up")
        ok("roshni kam kar do", "brightness down")
        ok("brightness full kar do", "brightness max")
        ok("mujhe batao battery kitni hai", "battery")
        ok("abhi time kya hua hai", "time")
        ok("aaj ki date batao", "date")
        ok("gana band kar do", "pause music")
        ok("gana chalao", "play music")
        ok("agla gana chalao", "next song")
        ok("mera camera kholo", "camera")
        ok("wifi on kar do", "wifi on")
        ok("home screen par jao", "home")
        ok("peeche jao", "back")
        ok("notification dikhao", "notifications")
        ok("recent apps dikhao", "recents")
    }

    @Test fun unclearOrRiskySentences_stayWithTheAi() {
        assertNull("agla gana laga do", Understand.simplify("agla gana laga do"))
        assertNull("mere mobile ko lock mat karo", Understand.simplify("mere mobile ko lock mat karo"))
        assertNull("lock kaise karte hain", Understand.simplify("lock kaise karte hain"))
        assertNull("lock aur torch on kar do", Understand.simplify("lock aur torch on kar do"))
        assertNull("whatsapp pe rahul ko message bhej do ki main aa raha hoon", Understand.simplify("whatsapp pe rahul ko message bhej do ki main aa raha hoon"))
        assertNull("torch jala do aur awaaz kam kar do", Understand.simplify("torch jala do aur awaaz kam kar do"))
        assertNull("volume up volume down", Understand.simplify("volume up volume down"))
        assertNull("phone unlock kar do", Understand.simplify("phone unlock kar do"))
        assertNull("sandeep ko call karo", Understand.simplify("sandeep ko call karo"))
        assertNull("youtube par gana chalao", Understand.simplify("youtube par gana chalao"))
        assertNull("battery saver on kar do", Understand.simplify("battery saver on kar do"))
        assertNull("camera back wala kholo", Understand.simplify("camera back wala kholo"))
        assertNull("", Understand.simplify(""))
        assertNull("kar do", Understand.simplify("kar do"))
    }

    @Test fun theShortCommandsAreRealCommands() {
        // every canonical phrase must be accepted by the strict classifier, otherwise the loose path would do nothing
        val cases = mapOf(
            "mere mobile lock kro turant" to Logic.Cmd("global", "lock"),
            "mera torch jala do" to Logic.Cmd("torch_on"),
            "yaar torch jaldi se band kar de" to Logic.Cmd("torch_off"),
            "awaaz thodi kam kar do" to Logic.Cmd("volume", "down"),
            "brightness thodi zyada kar do" to Logic.Cmd("brightness_step", "", 10),
            "mujhe batao battery kitni hai" to Logic.Cmd("battery"),
            "gana band kar do" to Logic.Cmd("media", "pause"),
            "agla gana chalao" to Logic.Cmd("media", "next"),
            "mera camera kholo" to Logic.Cmd("camera")
        )
        for ((said, want) in cases) assertEquals(said, want, Understand.classify(said))
    }

    @Test fun strictCommandsKeepWorkingExactlyAsBefore() {
        assertEquals(Logic.classify("lock phone"), Understand.classify("lock phone"))
        assertEquals(Logic.classify("torch on"), Understand.classify("torch on"))
        assertTrue(Understand.classify("nova mere mobile ko lock mat karo") == null)
    }

    @Test fun unlockIsNeverLoose() {
        assertNull(Understand.simplify("phone unlock kar do"))
        assertNull(Understand.simplify("mera phone unlock kro turant"))
    }
}
