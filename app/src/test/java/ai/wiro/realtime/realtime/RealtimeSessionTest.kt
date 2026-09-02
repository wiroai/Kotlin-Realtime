package ai.wiro.realtime.realtime

import ai.wiro.realtime.networking.WiroFailure
import ai.wiro.realtime.realtime.support.FakeRealtimeSocketClient
import ai.wiro.realtime.realtime.support.waitUntil
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RealtimeSessionTest {
    @Test
    fun subscribesExactlyOnceAndWaitsForStreamReadiness() = runTest {
        val socket = FakeRealtimeSocketClient()
        val session = makeSession(socket)
        val connecting = backgroundScope.async { session.connect() }
        waitUntil { socket.sentText.size == 1 }

        assertEquals(1, socket.connectCalls)
        assertEquals(1, socket.sentText.size)
        val payload = decodeJson(socket.sentText[0])
        assertEquals("task_info", payload["type"])
        assertEquals("task-token", payload["tasktoken"])

        socket.emitText("{\"type\":\"task_stream_ready\"}")
        connecting.await()
        session.connect()

        assertTrue(session.isReady)
        assertEquals(1, socket.connectCalls)
        assertEquals(1, socket.sentText.size)
        session.close()
    }

    @Test
    fun emitsOutputAndUnknownEventsAfterReady() = runTest {
        val socket = FakeRealtimeSocketClient()
        val session = makeSession(socket)
        val recorder = EventRecorder()
        session.addEventListener { recorder.append(it) }

        val connecting = backgroundScope.async { session.connect() }
        waitUntil { socket.sentText.size == 1 }
        socket.emitText("{\"type\":\"task_stream_ready\"}")
        socket.emitText(
            "{\"type\":\"task_output\"," +
                "\"message\":\"TRANSCRIPT_USER:Hi\"}",
        )
        socket.emitText("{\"type\":\"task_future_event\"}")
        connecting.await()

        assertTrue(
            recorder.events.contains(
                RealtimeEvent.Output("TRANSCRIPT_USER:Hi"),
            ),
        )
        assertTrue(
            recorder.events.contains(
                RealtimeEvent.Unknown("task_future_event"),
            ),
        )
        session.close()
    }

    @Test
    fun prefixesPcmAudioWithTheTaskToken() = runTest {
        val socket = FakeRealtimeSocketClient()
        val session = makeSession(socket)
        connectReady(session, socket)

        val sent = session.sendAudio(byteArrayOf(1, 2, 3))

        assertTrue(sent)
        assertArrayEquals(
            "task-token|".toByteArray() + byteArrayOf(1, 2, 3),
            socket.sentBinary.first(),
        )
        session.close()
    }

    @Test
    fun stripsAndValidatesTheTokenFromServerAudio() = runTest {
        val socket = FakeRealtimeSocketClient()
        val session = makeSession(socket)
        val recorder = EventRecorder()
        session.addEventListener { recorder.append(it) }
        connectReady(session, socket)

        socket.emit(
            RealtimeSocketFrame.Binary(
                "task-token|".toByteArray() + byteArrayOf(1, 2, 3),
            ),
        )

        val binary = recorder.events.mapNotNull { event ->
            (event as? RealtimeEvent.Binary)?.bytes
        }
        assertEquals(1, binary.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), binary[0])
        assertEquals(2, session.metrics.framesProcessed)
        assertEquals(1, session.metrics.binaryFrames)
        assertEquals(0, session.metrics.malformedFrames)
        session.close()
    }

    @Test
    fun acceptsServerAudioPrefixedWithTheTaskId() = runTest {
        val socket = FakeRealtimeSocketClient()
        val session = makeSession(socket)
        val recorder = EventRecorder()
        session.addEventListener { recorder.append(it) }
        connectReady(session, socket)

        socket.emit(
            RealtimeSocketFrame.Binary(
                "2743770|".toByteArray() + byteArrayOf(4, 5, 6),
            ),
        )

        val binary = recorder.events.mapNotNull { event ->
            (event as? RealtimeEvent.Binary)?.bytes
        }
        assertEquals(1, binary.size)
        assertArrayEquals(byteArrayOf(4, 5, 6), binary[0])
        session.close()
    }

    @Test
    fun closesIdempotentlyOnPostprocessCompletion() = runTest {
        val socket = FakeRealtimeSocketClient()
        val session = makeSession(socket)
        connectReady(session, socket)

        socket.emitText("{\"type\":\"task_postprocess_end\"}")
        waitUntil { session.isClosed }
        session.close()

        assertTrue(session.isClosed)
        assertEquals(1, socket.closeCalls)
        session.close()
    }

    @Test
    fun aTaskErrorLogFrameKeepsTheSessionAlive() = runTest {
        val socket = FakeRealtimeSocketClient()
        val session = makeSession(socket)
        val recorder = EventRecorder()
        session.addEventListener { recorder.append(it) }
        connectReady(session, socket)

        socket.emitText(
            "{\"type\":\"task_error\"," +
                "\"message\":\"private server detail\"}",
        )

        assertTrue(session.isReady)
        assertFalse(session.isClosed)
        assertTrue(
            recorder.events.contains(RealtimeEvent.Error("task_error")),
        )
        session.close()
    }

    @Test
    fun mapsServerCancellationBeforeStreamReadiness() {
        val socket = FakeRealtimeSocketClient()
        val session = makeSession(socket)
        val error = CompletableFuture<Throwable?>()
        val thread = Thread {
            try {
                runBlocking { session.connect() }
                error.complete(null)
            } catch (thrown: Throwable) {
                error.complete(thrown)
            }
        }
        thread.start()
        runBlocking { waitUntil { socket.sentText.size == 1 } }
        socket.emitText("{\"type\":\"task_cancel\"}")
        val thrown = error.get()
        assertEquals(WiroFailure.Cancelled, thrown)
        runBlocking { waitUntil { session.isClosed } }
        assertTrue(session.isClosed)
        assertEquals(1, socket.closeCalls)
        thread.join()
    }

    @Test
    fun sendsTaskSessionEndOnceDuringEarlyStop() = runTest {
        val socket = FakeRealtimeSocketClient()
        val session = makeSession(socket)
        connectReady(session, socket)

        session.stop()
        session.stop()

        val frames = socket.sentText.map(::decodeJson)
        val ends = frames.filter { it["type"] == "task_session_end" }
        assertEquals(1, ends.size)
        assertEquals("task_session_end", frames.last()["type"])
        assertEquals("task-token", frames.last()["tasktoken"])
    }

    @Test
    fun closesWhenStreamReadinessTimesOut() = runTest {
        val socket = FakeRealtimeSocketClient()
        val session = makeSession(
            socket,
            readyTimeout = 20.milliseconds,
        )

        val error = runCatching { session.connect() }.exceptionOrNull()
        assertEquals(WiroFailure.StreamReadyTimeout, error)
        assertTrue(session.isClosed)
        assertEquals(1, socket.closeCalls)
    }

    @Test
    fun doesNotReconnectAfterRemoteDisconnect() = runTest {
        val socket = FakeRealtimeSocketClient()
        val session = makeSession(socket)
        connectReady(session, socket)

        socket.finish()
        waitUntil { session.isClosed }

        session.connect()

        assertEquals(1, socket.connectCalls)
        assertTrue(session.isClosed)
    }

    private suspend fun connectReady(
        session: RealtimeSession,
        socket: FakeRealtimeSocketClient,
    ) = coroutineScope {
        val connecting = async { session.connect() }
        waitUntil { socket.sentText.size == 1 }
        socket.emitText("{\"type\":\"task_stream_ready\"}")
        connecting.await()
        Unit
    }

    private fun makeSession(
        socket: FakeRealtimeSocketClient,
        readyTimeout: kotlin.time.Duration = 1.seconds,
    ): RealtimeSession = RealtimeSession(
        socketClient = socket,
        taskId = "2743770",
        taskToken = "task-token",
        readyTimeout = readyTimeout,
    )

    private fun decodeJson(text: String): Map<String, String> = Json.parseToJsonElement(text).jsonObject.mapValues {
        it.value.jsonPrimitive.content
    }
}

private class EventRecorder {
    val events: MutableList<RealtimeEvent> = mutableListOf()

    fun append(event: RealtimeEvent) {
        events += event
    }
}
