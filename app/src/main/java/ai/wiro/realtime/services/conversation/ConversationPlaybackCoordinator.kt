package ai.wiro.realtime.services.conversation

import ai.wiro.realtime.audio.AudioConstants
import ai.wiro.realtime.audio.PcmAudioPlayer

/**
 * What happened to a chunk of assistant audio on its way to the
 * speaker.
 *
 * The two outcomes are independent: a chunk can clear an interrupted
 * turn and still be dropped by a full queue.
 */
data class PlaybackEnqueueResult(
    val flushedPreviousTurn: Boolean,
    val droppedChunk: Boolean,
    val silencedChunk: Boolean = false,
    val heldChunk: Boolean = false,
)

/**
 * Feeds assistant audio to the player and decides what happens to
 * audio left over from a turn the user interrupted.
 *
 * A finished turn keeps playing its queued tail so speech stays
 * gapless. A suspected interruption silences the speaker while
 * keeping the retained audio; a confirmed one discards the rest.
 */
class ConversationPlaybackCoordinator(
    private val player: PcmAudioPlayer,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val retained = mutableListOf<RetainedChunk>()
    private var playheadUntil = EPOCH
    private var heldAt: Long? = null
    private var heldTurnWasFinished = false
    private var previousTurnPending = false
    private var turnInterrupted = false

    val isHolding: Boolean
        get() = synchronized(lock) { heldAt != null }

    /**
     * Marks the assistant's turn as finished while its queued tail
     * keeps playing, so the next turn knows it has to clear that tail.
     */
    fun markTurnFinished() {
        synchronized(lock) {
            if (turnInterrupted) {
                turnInterrupted = false
                return
            }
            if (heldAt != null) {
                heldTurnWasFinished = true
                return
            }
            previousTurnPending = true
        }
    }

    /**
     * Silences the turn on a suspected interruption while keeping
     * its unplayed audio.
     */
    fun holdTurn(): Boolean = synchronized(lock) {
        if (turnInterrupted || heldAt != null) {
            return false
        }
        val now = nowMillis()
        pruneRetained(now)
        if (retained.isEmpty()) {
            return false
        }
        heldAt = now
        heldTurnWasFinished = previousTurnPending
        previousTurnPending = false
        player.flush()
        true
    }

    /**
     * The suspicion proved to be noise: puts the held audio back on
     * the player, trimmed to the point the hold silenced it.
     */
    fun resumeHeldTurn(): List<ByteArray> = synchronized(lock) {
        val held = heldAt ?: return emptyList()
        heldAt = null
        if (heldTurnWasFinished) {
            previousTurnPending = true
            heldTurnWasFinished = false
        }
        val chunks = retained.toList()
        retained.clear()
        val resumed = mutableListOf<ByteArray>()
        var start = nowMillis()
        for (chunk in chunks) {
            val bytes = if (chunk.start < held) {
                trimHeard(chunk, held)
            } else {
                chunk.bytes
            }
            if (bytes.isEmpty()) {
                continue
            }
            val end = start + durationOf(bytes)
            retained += RetainedChunk(bytes = bytes, start = start, end = end)
            start = end
            if (player.tryEnqueue(bytes)) {
                resumed += bytes
            }
        }
        playheadUntil = start
        resumed
    }

    /**
     * Cuts the assistant off: queued, buffered or held audio is
     * discarded, and further chunks of the same turn are dropped.
     */
    fun interruptTurn(): Boolean = synchronized(lock) {
        if (turnInterrupted) {
            return false
        }
        if (heldAt != null) {
            heldAt = null
            if (heldTurnWasFinished) {
                heldTurnWasFinished = false
            } else {
                turnInterrupted = true
            }
            discardRetained()
            return true
        }
        if (previousTurnPending) {
            previousTurnPending = false
        } else {
            turnInterrupted = true
        }
        discardRetained()
        player.flush()
        true
    }

    fun reset() {
        synchronized(lock) {
            previousTurnPending = false
            turnInterrupted = false
            heldAt = null
            heldTurnWasFinished = false
            discardRetained()
        }
    }

    fun enqueue(bytes: ByteArray): PlaybackEnqueueResult = synchronized(lock) {
        if (turnInterrupted) {
            return PlaybackEnqueueResult(
                flushedPreviousTurn = false,
                droppedChunk = false,
                silencedChunk = true,
            )
        }
        if (heldAt != null) {
            if (!heldTurnWasFinished) {
                retainDuringHold(bytes)
                return PlaybackEnqueueResult(
                    flushedPreviousTurn = false,
                    droppedChunk = false,
                    heldChunk = true,
                )
            }
            heldAt = null
            heldTurnWasFinished = false
            discardRetained()
            return PlaybackEnqueueResult(
                flushedPreviousTurn = true,
                droppedChunk = !enqueueAndRetain(bytes),
            )
        }
        var flushedPreviousTurn = false
        if (previousTurnPending) {
            previousTurnPending = false
            discardRetained()
            player.flush()
            flushedPreviousTurn = true
        }
        PlaybackEnqueueResult(
            flushedPreviousTurn = flushedPreviousTurn,
            droppedChunk = !enqueueAndRetain(bytes),
        )
    }

    private fun enqueueAndRetain(bytes: ByteArray): Boolean {
        val now = nowMillis()
        pruneRetained(now)
        if (!player.tryEnqueue(bytes)) {
            return false
        }
        val start = if (playheadUntil > now) playheadUntil else now
        val end = start + durationOf(bytes)
        retained += RetainedChunk(bytes = bytes, start = start, end = end)
        playheadUntil = end
        return true
    }

    private fun retainDuringHold(bytes: ByteArray) {
        val start = retained.lastOrNull()?.end ?: nowMillis()
        retained += RetainedChunk(
            bytes = bytes,
            start = start,
            end = start + durationOf(bytes),
        )
    }

    /**
     * A hold freezes pruning: the clock keeps running while the
     * speaker is silent, and pruning against it would count silence
     * as playback.
     */
    private fun pruneRetained(now: Long) {
        if (heldAt != null) {
            return
        }
        while (retained.isNotEmpty() && retained.first().end <= now) {
            retained.removeAt(0)
        }
    }

    private fun discardRetained() {
        retained.clear()
        playheadUntil = EPOCH
    }

    private fun trimHeard(chunk: RetainedChunk, heldAt: Long): ByteArray {
        val heardMillis = heldAt - chunk.start
        var offset = (heardMillis * BYTES_PER_SECOND / 1000).toInt()
        offset -= offset % BYTES_PER_FRAME
        return when {
            offset <= 0 -> chunk.bytes
            offset >= chunk.bytes.size -> ByteArray(0)
            else -> chunk.bytes.copyOfRange(offset, chunk.bytes.size)
        }
    }

    private fun durationOf(bytes: ByteArray): Long = bytes.size * 1000L / BYTES_PER_SECOND

    private data class RetainedChunk(
        val bytes: ByteArray,
        val start: Long,
        val end: Long,
    )

    private companion object {
        const val EPOCH: Long = 0L
        const val BYTES_PER_SECOND: Int =
            AudioConstants.SAMPLE_RATE_HZ *
                AudioConstants.CHANNEL_COUNT *
                AudioConstants.BITS_PER_SAMPLE / 8
        const val BYTES_PER_FRAME: Int =
            AudioConstants.CHANNEL_COUNT *
                AudioConstants.BITS_PER_SAMPLE / 8
    }
}
