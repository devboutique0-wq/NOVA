package com.nova.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Parser-only tests: these never contact Wikipedia or send user data. */
class PublicKnowledgeFallbackTest {
    @Test fun explicitGeneralKnowledgeQuestionsExtractOnlyTheTopic() {
        assertEquals("artificial intelligence", PublicKnowledgeFallback.topicOf("what is artificial intelligence"))
        assertEquals("ai", PublicKnowledgeFallback.topicOf("what is AI"))
        assertEquals("quantum computing", PublicKnowledgeFallback.topicOf("tell me about quantum computing"))
        assertEquals("solar panel", PublicKnowledgeFallback.topicOf("nova solar panel ke baare mein batao"))
        assertEquals("photosynthesis", PublicKnowledgeFallback.topicOf("mujhe photosynthesis kya hai"))
    }

    @Test fun currentPersonalAndActionRequestsNeverUsePublicKnowledgeLookup() {
        assertNull(PublicKnowledgeFallback.topicOf("what is today's weather"))
        assertNull(PublicKnowledgeFallback.topicOf("what is current bitcoin price"))
        assertNull(PublicKnowledgeFallback.topicOf("who is my doctor"))
        assertNull(PublicKnowledgeFallback.topicOf("what is my bank account"))
        assertNull(PublicKnowledgeFallback.topicOf("open YouTube"))
        assertNull(PublicKnowledgeFallback.topicOf("send a message"))
    }
}
