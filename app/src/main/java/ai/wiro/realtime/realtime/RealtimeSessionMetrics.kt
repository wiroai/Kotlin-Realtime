package ai.wiro.realtime.realtime

import kotlin.math.ceil

data class RealtimeSessionMetrics(
    val framesProcessed: Int,
    val binaryFrames: Int,
    val malformedFrames: Int,
    val maximumProcessingMicros: Int,
    val p95ProcessingMicros: Int,
) {
    companion object {
        val Empty = RealtimeSessionMetrics(
            framesProcessed = 0,
            binaryFrames = 0,
            malformedFrames = 0,
            maximumProcessingMicros = 0,
            p95ProcessingMicros = 0,
        )
    }
}

class RealtimeSessionMetricsCollector {
    private val lock = Any()
    private val processingSamples = ArrayDeque<Int>()
    private var framesProcessed: Int = 0
    private var binaryFrames: Int = 0
    private var malformedFrames: Int = 0
    private var maximumProcessingMicros: Int = 0

    fun snapshot(): RealtimeSessionMetrics = synchronized(lock) {
        val sorted = processingSamples.sorted()
        val p95 = if (sorted.isEmpty()) {
            0
        } else {
            val index = ceil((sorted.size - 1) * 0.95).toInt()
            sorted[index]
        }
        RealtimeSessionMetrics(
            framesProcessed = framesProcessed,
            binaryFrames = binaryFrames,
            malformedFrames = malformedFrames,
            maximumProcessingMicros = maximumProcessingMicros,
            p95ProcessingMicros = p95,
        )
    }

    fun record(event: RealtimeEvent, processingMicros: Int) {
        synchronized(lock) {
            framesProcessed += 1
            when (event) {
                is RealtimeEvent.Binary -> binaryFrames += 1
                RealtimeEvent.Malformed -> malformedFrames += 1
                else -> Unit
            }
            if (processingMicros > maximumProcessingMicros) {
                maximumProcessingMicros = processingMicros
            }
            processingSamples.addLast(processingMicros)
            if (processingSamples.size > MAXIMUM_SAMPLES) {
                processingSamples.removeFirst()
            }
        }
    }

    private companion object {
        const val MAXIMUM_SAMPLES: Int = 256
    }
}
