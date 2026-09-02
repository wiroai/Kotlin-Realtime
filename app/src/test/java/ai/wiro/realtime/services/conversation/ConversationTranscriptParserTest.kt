package ai.wiro.realtime.services.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationTranscriptParserTest {
    private val parser = ConversationTranscriptParser()

    @Test
    fun parsesUserAndAiPrefixes() {
        assertEquals(
            ConversationTranscript.User("hello"),
            parser.parse("TRANSCRIPT_USER:hello"),
        )
        assertEquals(
            ConversationTranscript.Ai("hi there"),
            parser.parse("TRANSCRIPT_AI:hi there"),
        )
    }

    @Test
    fun ignoresUnprefixedOutput() {
        assertNull(parser.parse("plain text"))
        assertNull(parser.parse("USER:no"))
    }
}
