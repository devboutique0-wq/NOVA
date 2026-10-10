package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ImageIntentTest {
    private fun subj(t: String): String = ImageIntent.parse(t)?.subject ?: "NOT_IMAGE"

    @Test fun hindiRequests() {
        assertEquals("cat", subj("nova ek billi ki photo banao"))
        assertEquals("lion", subj("ek sher ki tasveer bana do"))
        assertEquals("beautiful flower", subj("nova sundar phool ki photo banao"))
        assertEquals("red car", subj("lal gaadi ki photo banao"))
    }

    @Test fun englishRequests() {
        assertEquals("cat on moon", subj("make a picture of a cat on the moon"))
        assertEquals("sunset over sea", subj("Create an image of sunset over the sea"))
    }

    @Test fun missingSubjectIsEmpty() {
        assertEquals("", subj("photo banao"))
        assertEquals("", subj("nova ek photo bana do please"))
    }

    @Test fun otherFeaturesAreNotTaken() {
        assertNull(ImageIntent.parse("photo kholo"))
        assertNull(ImageIntent.parse("camera kholo aur photo kheencho"))
        assertNull(ImageIntent.parse("kaam chatgpt kholo aur ek photo banao"))
        assertNull(ImageIntent.parse("gallery ki 5th photo select karo"))
        assertNull(ImageIntent.parse("is photo ko edit karo"))
        assertNull(ImageIntent.parse("chatgpt se photo banwao"))
        assertNull(ImageIntent.parse("battery kitni hai"))
        assertNull(ImageIntent.parse("whatsapp par photo bhejo"))
        assertNull(ImageIntent.parse(""))
    }

    @Test fun blockedWordsAreRefused() {
        val p = ImageIntent.parse("nova ek nude photo banao")
        assertNotNull(p)
        assertEquals(true, p?.blocked)
        assertEquals(false, ImageIntent.parse("ek billi ki photo banao")?.blocked)
    }

    @Test fun veryLongTextIsIgnored() {
        assertNull(ImageIntent.parse("a b ".repeat(30) + "photo banao"))
    }
}
