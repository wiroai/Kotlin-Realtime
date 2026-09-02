package ai.wiro.realtime.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Owns the audio subscriptions that can force a realtime session to
 * end, and reports them as intent rather than raw platform events.
 *
 * An interruption and an unplugged output mean the same thing to a
 * caller — stop now — so they collapse into [onShouldStop]. Whether
 * the session can act on that stays with the caller.
 */
class RealtimeAudioRouteWatcher(
    audioSession: RealtimeAudioSession,
    playbackFailures: Flow<AudioFailure>,
    onShouldStop: () -> Unit,
    onRouteChanged: () -> Unit,
    onPlaybackFailure: (AudioFailure) -> Unit,
    scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default,
    ),
) {
    private val lock = Any()
    private val jobs: List<Job>

    init {
        jobs = listOf(
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                audioSession.interruptions.collect { interruption ->
                    if (interruption == RealtimeAudioInterruption.Began) {
                        onShouldStop()
                    }
                }
            },
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                audioSession.becomingNoisy.collect { onShouldStop() }
            },
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                audioSession.routeChanges.collect { onRouteChanged() }
            },
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                playbackFailures.collect { failure ->
                    onPlaybackFailure(failure)
                }
            },
        )
    }

    suspend fun dispose() {
        val running = synchronized(lock) { jobs.toList() }
        running.forEach { it.cancel() }
        running.forEach { it.join() }
    }
}
