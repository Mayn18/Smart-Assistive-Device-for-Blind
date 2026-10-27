package com.example.smartaiglasses.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPolicyTest {
    @Test
    fun rejectsTextBelowMinimumLength() {
        assertFalse(TextPolicy().shouldSpeak("ab", 1000L, false, false))
    }

    @Test
    fun normalizesWhitespaceAndNoise() {
        assertEquals("Hello world", TextPolicy().clean(" |Hello|  world\n"))
    }

    @Test
    fun suppressesRepeatedTextDuringCooldown() {
        val policy = TextPolicy()
        policy.markSpoken("hello world", 1000L)
        assertFalse(policy.shouldSpeak("hello world", 2000L, false, false))
    }

    @Test
    fun allowsRepeatedTextAfterCooldown() {
        val policy = TextPolicy()
        policy.markSpoken("hello world", 1000L)
        assertTrue(policy.shouldSpeak("hello world", 4000L, false, false))
    }

    @Test
    fun allowsChangedTextDuringCooldown() {
        val policy = TextPolicy()
        policy.markSpoken("hello world", 1000L)
        assertTrue(policy.shouldSpeak("different text", 2000L, false, false))
    }

    @Test
    fun readOnceBypassesTtsBusyState() {
        assertTrue(TextPolicy().shouldSpeak("hello world", 1000L, true, true))
    }

    @Test
    fun suppressesContinuousSpeechWhileTtsIsBusy() {
        assertFalse(TextPolicy().shouldSpeak("hello world", 1000L, false, true))
    }
}