package ai.wiro.realtime.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Configures the shared [AudioManager] communication path and
 * publishes route / interruption events. Features never touch the
 * manager directly.
 */
class AndroidRealtimeAudioSession(
    context: Context,
    private val microphone: MicrophonePermission,
    private val settingsLink: SystemSettingsLink,
) : RealtimeAudioSession {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(
        Context.AUDIO_SERVICE,
    ) as AudioManager
    private val lock = Any()
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
    private var disposed: Boolean = false
    private var receiversBound: Boolean = false
    private var focusRequest: AudioFocusRequest? = null
    private var activeProfile: RealtimeAudioProfile? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                noisyEvents.tryEmit(Unit)
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(
            addedDevices: Array<out AudioDeviceInfo>,
        ) {
            rerouteActiveProfile()
            routeEvents.tryEmit(Unit)
        }

        override fun onAudioDevicesRemoved(
            removedDevices: Array<out AudioDeviceInfo>,
        ) {
            rerouteActiveProfile()
            routeEvents.tryEmit(Unit)
            noisyEvents.tryEmit(Unit)
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> interruptionEvents.tryEmit(RealtimeAudioInterruption.Began)

            AudioManager.AUDIOFOCUS_GAIN ->
                interruptionEvents.tryEmit(RealtimeAudioInterruption.Ended)
        }
    }

    override val interruptions: Flow<RealtimeAudioInterruption>
        get() = interruptionEvents.asSharedFlow()

    override val becomingNoisy: Flow<Unit>
        get() = noisyEvents.asSharedFlow()

    override val routeChanges: Flow<Unit>
        get() = routeEvents.asSharedFlow()

    override suspend fun prepare(
        profile: RealtimeAudioProfile,
    ): AudioPreparationResult {
        if (synchronized(lock) { disposed }) {
            return AudioPreparationResult.Unavailable
        }
        if (profile.requiresMicrophone) {
            when (microphone.request()) {
                MicrophonePermissionStatus.Granted -> Unit

                MicrophonePermissionStatus.Denied ->
                    return AudioPreparationResult.PermissionDenied

                MicrophonePermissionStatus.PermanentlyDenied ->
                    return AudioPreparationResult.PermissionPermanentlyDenied
            }
        }
        return try {
            bindReceivers()
            when (profile) {
                RealtimeAudioProfile.Conversation ->
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

                RealtimeAudioProfile.Transcription,
                RealtimeAudioProfile.Speech,
                -> audioManager.mode = AudioManager.MODE_NORMAL
            }
            val request = audioFocusRequest(profile)
            val granted = audioManager.requestAudioFocus(request)
            if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                audioManager.mode = AudioManager.MODE_NORMAL
                return AudioPreparationResult.ActivationDenied
            }
            synchronized(lock) {
                focusRequest = request
                activeProfile = profile
            }
            CommunicationRoute.apply(
                audioManager,
                voiceCall = profile.requiresMicrophone,
            )
            AudioPreparationResult.Ready
        } catch (_: Exception) {
            AudioPreparationResult.ActivationDenied
        }
    }

    override suspend fun deactivate() {
        val request = synchronized(lock) {
            activeProfile = null
            focusRequest.also { focusRequest = null }
        }
        CommunicationRoute.clear(audioManager)
        if (request != null) {
            audioManager.abandonAudioFocusRequest(request)
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    override suspend fun setSpeakerEnabled(enabled: Boolean): Boolean = try {
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = enabled
        true
    } catch (_: Exception) {
        false
    }

    override suspend fun openSystemSettings(): Boolean = try {
        settingsLink.open()
        true
    } catch (_: Exception) {
        false
    }

    override suspend fun dispose() {
        if (synchronized(lock) { disposed }) {
            return
        }
        synchronized(lock) { disposed = true }
        deactivate()
        unbindReceivers()
    }

    private fun audioFocusRequest(
        profile: RealtimeAudioProfile,
    ): AudioFocusRequest {
        val usage = if (profile == RealtimeAudioProfile.Conversation) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        val content = if (profile == RealtimeAudioProfile.Conversation) {
            AudioAttributes.CONTENT_TYPE_SPEECH
        } else {
            AudioAttributes.CONTENT_TYPE_MUSIC
        }
        return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(content)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
    }

    private fun bindReceivers() {
        if (synchronized(lock) { receiversBound }) {
            return
        }
        appContext.registerReceiver(
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
        )
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        synchronized(lock) { receiversBound = true }
    }

    private fun rerouteActiveProfile() {
        val profile = synchronized(lock) { activeProfile } ?: return
        CommunicationRoute.apply(
            audioManager,
            voiceCall = profile.requiresMicrophone,
        )
    }

    private fun unbindReceivers() {
        if (!synchronized(lock) { receiversBound }) {
            return
        }
        runCatching { appContext.unregisterReceiver(noisyReceiver) }
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        synchronized(lock) { receiversBound = false }
    }
}
