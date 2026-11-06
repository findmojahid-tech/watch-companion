package com.mojahid.watchcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenAiClientTest {
    @Test fun extractsOnlySpeakableMessageText() {
        val payload = """
            {
              "output":[
                {"type":"reasoning","summary":[]},
                {"type":"message","content":[
                  {"type":"output_text","text":"Hello, I'm Tiffy."}
                ]}
              ]
            }
        """.trimIndent()
        assertEquals("Hello, I'm Tiffy.", OpenAiClient.extractReply(payload))
    }

    @Test fun errorTextDoesNotLeakProviderResponse() {
        val secret = "sk-proj-super-secret-do-not-echo"
        val reply = OpenAiClient.messageForFailure(401, """{"error":"$secret"}""")
        assertTrue(reply.contains("401"))
        assertTrue(!reply.contains(secret))
    }
}
