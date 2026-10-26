package com.mojahid.watchcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {
    @Test fun recognizesExactWakeWords() {
        listOf("Momo", "Hey Momo", "hello momo", "hi MOMO", " Momo! ").forEach {
            assertTrue(it, WakePhrase.parse(it).detected)
        }
    }

    @Test fun extractsSpokenCommandWithoutWakePrefix() {
        assertEquals("what is the weather?", WakePhrase.parse("Hey Momo, what is the weather?").command)
        assertEquals("set a task", WakePhrase.parse("Momo: set a task").command)
    }

    @Test fun rejectsSimilarNamesAndEmbeddedWords() {
        listOf("Momoko", "Hey Momoko", "momoring", "Call Momo", "Momo123", "Hi momos").forEach {
            assertFalse(it, WakePhrase.parse(it).detected)
        }
    }

    @Test fun nonWakeSpeechIsPreservedForFollowUp() {
        val text = "What is the capital of Bangladesh?"
        assertEquals(text, WakePhrase.parse(text).command)
    }
}
