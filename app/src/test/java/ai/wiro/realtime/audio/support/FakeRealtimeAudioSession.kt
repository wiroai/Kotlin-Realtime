package ai.wiro.realtime.audio.support

import ai.wiro.realtime.audio.AudioPreparationResult
import ai.wiro.realtime.audio.RealtimeAudioInterruption
import ai.wiro.realtime.audio.RealtimeAudioProfile
import ai.wiro.realtime.audio.RealtimeAudioSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class FakeRealtimeAudioSession(
    var preparationResult: AudioPreparationResult =
        AudioPreparationResult.Ready,
) : RealtimeAudioSession {
    private val interruptionEvents =
        MutableSharedFlow<RealtimeAudioInterruption>(
            extraBufferCapacity = 16,
        )
    private val noisyEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 16,
    )
    private val routeEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 16,
    )
    val preparedProfiles: MutableList<RealtimeAudioProfile> =
        mutableListOf()
    var deactivateCalls: Int = 0
    var disposeCalls: Int = 0
    var openSystemSettingsCalls: Int = 0
    var systemSettingsAvailable: Boolean = true

    override val interruptions: Flow<RealtimeAudioInterruption>
        get() = interruptionEvents.asSharedFlow()

    override val becomingNoisy: Flow<Unit>
        get() = noisyEvents.asSharedFlow()

    override val routeChanges: Flow<Unit>
        get() = routeEvents.asSharedFlow()

    fun emitInterruption(value: RealtimeAudioInterruption) {
        interruptionEvents.tryEmit(value)
    }

    fun emitBecomingNoisy() {
        noisyEvents.tryEmit(Unit)
    }

    fun emitRouteChange() {
        routeEvents.tryEmit(Unit)
    }

    override suspend fun prepare(
        profile: RealtimeAudioProfile,
    ): AudioPreparationResult {
        preparedProfiles += profile
        return preparationResult
    }

    override suspend fun deactivate() {
        deactivateCalls += 1
    }

    override suspend fun setSpeakerEnabled(enabled: Boolean): Boolean = true

    override suspend fun openSystemSettings(): Boolean {
        openSystemSettingsCalls += 1
        return systemSettingsAvailable
    }

    override suspend fun dispose() {
        disposeCalls += 1
    }
}
