package com.mojahid.watchcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {
    @Test fun recognizesExactWakeWords() {
        listOf("Tiffy", "Hey Tiffy", "hello tiffy", "hi TIFFY", " Tiffy! ").forEach {
            assertTrue(it, WakePhrase.parse(it).detected)
        }
    }

    @Test fun extractsSpokenCommandWithoutWakePrefix() {
        assertEquals("what is the weather?", WakePhrase.parse("Hey Tiffy, what is the weather?").command)
        assertEquals("set a task", WakePhrase.parse("Tiffy: set a task").command)
    }

    @Test fun rejectsSimilarNamesAndEmbeddedWords() {
        listOf("Tiffyko", "Hey Tiffyko", "tiffyring", "Call Tiffy", "Tiffy123", "Hi tiffys").forEach {
            assertFalse(it, WakePhrase.parse(it).detected)
        }
    }

    @Test fun nonWakeSpeechIsPreservedForFollowUp() {
        val text = "What is the capital of Bangladesh?"
        assertEquals(text, WakePhrase.parse(text).command)
    }
    @Test fun choosesExactWakeFromAlternateRecognitionHypotheses() {
        val options = listOf("Hey Tiffyko what time is it", "Hey Tiffy what time is it")
        assertEquals("Hey Tiffy what time is it", WakePhrase.selectHypothesis(options))
    }

    @Test fun preservesBestGuessForAwaitedFollowUp() {
        val options = listOf("What's the time", "What's that time")
        assertEquals("What's the time", WakePhrase.selectHypothesis(options))
        assertEquals("", WakePhrase.selectHypothesis(emptyList()))
    }

}
