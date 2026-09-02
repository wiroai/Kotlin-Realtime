package ai.wiro.realtime.auth

import java.util.concurrent.atomic.AtomicLong

fun interface WiroNonceGenerator {
    fun next(): String
}

class MonotonicWiroNonceGenerator(
    private val microsecondsSinceEpoch: () -> Long = {
        System.currentTimeMillis() * 1_000
    },
) : WiroNonceGenerator {
    private val lastValue = AtomicLong(0)

    override fun next(): String {
        while (true) {
            val timestamp = microsecondsSinceEpoch()
            val previous = lastValue.get()
            val next = if (timestamp > previous) timestamp else previous + 1
            if (lastValue.compareAndSet(previous, next)) {
                return next.toString()
            }
        }
    }
}
