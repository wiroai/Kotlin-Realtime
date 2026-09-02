package ai.wiro.realtime.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shared capture and playback graph.
 *
 * Conversation uses [MediaRecorder.AudioSource.VOICE_COMMUNICATION]
 * and a voice-communication track so the platform echo canceller can
 * see both directions. Playback-only features ask for a media track
 * instead ([PcmPlaybackProfile.Media]); a voice-communication track
 * without a call route would play from the earpiece. The capture
 * session also binds an AcousticEchoCanceler when the device exposes
 * one. Features talk to this type through [PcmCaptureSource] and
 * [PcmPlaybackSink]; they never start the devices themselves.
 */
class AndroidPcmGraph(
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO,
    ),
) {
    private val echoCanceler = SessionEchoCanceler.platform()
    private val lock = Any()
    private val underrunEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 16,
    )
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var trackProfile: PcmPlaybackProfile? = null
    private var captureJob: Job? = null
    private var recording: Boolean = false
    private var disposed: Boolean = false
    private var volume: Float = 1f
    private var writtenFrames: Long = 0

    val isRecording: Boolean
        get() = synchronized(lock) { recording }

    val underruns: Flow<Unit>
        get() = underrunEvents.asSharedFlow()

    val capture: PcmCaptureSource = CaptureAdapter()
    val playback: PcmPlaybackSink = PlaybackAdapter()

    suspend fun startCapture(
        profile: PcmCaptureProfile,
    ): Flow<ByteArray> {
        if (synchronized(lock) { disposed || recording }) {
            throw AudioFailure.Capture
        }
        val created = openRecorder(profile)
        synchronized(lock) {
            recorder = created
            recording = true
        }
        return callbackFlow {
            val job = scope.launch {
                val buffer = ByteArray(AudioConstants.PCM_BYTES_PER_CHUNK)
                while (isActive && synchronized(lock) { recording }) {
                    val read = created.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        trySend(buffer.copyOf(read))
                    } else if (read < 0) {
                        close(AudioFailure.Capture)
                        return@launch
                    }
                }
                close()
            }
            synchronized(lock) { captureJob = job }
            awaitClose {
                job.cancel()
                stopCaptureLocked()
            }
        }
    }

    suspend fun stopCapture() {
        stopCaptureLocked()
    }

    suspend fun initializePlayback(profile: PcmPlaybackProfile) {
        if (synchronized(lock) { disposed }) {
            throw AudioFailure.Playback
        }
        // The screens share one graph, so a track opened for the
        // other profile carries the wrong route. Rebuild it.
        val stale = synchronized(lock) {
            if (track != null && trackProfile != profile) {
                val old = track
                track = null
                trackProfile = null
                writtenFrames = 0
                old
            } else {
                null
            }
        }
        stale?.release()
        synchronized(lock) {
            if (track == null) {
                track = openTrack(profile)
                trackProfile = profile
            }
        }
    }

    suspend fun play() {
        synchronized(lock) { track }?.play()
    }

    suspend fun feed(chunk: ByteArray) {
        val output = synchronized(lock) { track }
            ?: throw AudioFailure.Playback
        val written = output.write(chunk, 0, chunk.size)
        if (written < 0) {
            throw AudioFailure.Playback
        }
        // A finished turn flushes with pause(); the next answer writes
        // into that paused track. Dart resumes on every chunk. Without
        // this the first reply plays and every later one stays silent.
        if (output.playState != AudioTrack.PLAYSTATE_PLAYING) {
            output.play()
        }
        if (written > 0) {
            synchronized(lock) {
                writtenFrames += written / AudioConstants.BYTES_PER_SAMPLE
            }
        }
    }

    suspend fun flush() {
        synchronized(lock) {
            track?.pause()
            track?.flush()
            writtenFrames = 0
        }
    }

    suspend fun bufferedDuration(): Duration {
        val snapshot = synchronized(lock) { track to writtenFrames }
        val output = snapshot.first ?: return Duration.ZERO
        val head = output.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        if (head == 0L && snapshot.second > output.bufferSizeInFrames) {
            return Duration.ZERO
        }
        return pcmBufferedDuration(
            writtenFrames = snapshot.second,
            playbackHeadFrames = head,
        )
    }

    suspend fun setVolume(volume: Double) {
        val clamped = volume.toFloat().coerceIn(0f, 1f)
        synchronized(lock) {
            this.volume = clamped
            track?.setVolume(clamped)
        }
    }

    suspend fun endOfStream() {
        while (bufferedDuration() > Duration.ZERO) {
            delay(10.milliseconds)
        }
    }

    suspend fun stopPlayback() {
        synchronized(lock) {
            track?.pause()
            track?.flush()
            writtenFrames = 0
        }
    }

    suspend fun dispose() {
        if (synchronized(lock) { disposed }) {
            return
        }
        synchronized(lock) { disposed = true }
        stopCaptureLocked()
        synchronized(lock) {
            track?.release()
            track = null
            trackProfile = null
        }
    }

    private fun stopCaptureLocked() {
        val current = synchronized(lock) {
            if (!recording) {
                return
            }
            recording = false
            val rec = recorder
            recorder = null
            captureJob?.cancel()
            captureJob = null
            rec
        }
        echoCanceler.release()
        current?.stop()
        current?.release()
    }

    private fun openRecorder(profile: PcmCaptureProfile): AudioRecord {
        val source = when (profile) {
            PcmCaptureProfile.Conversation ->
                MediaRecorder.AudioSource.VOICE_COMMUNICATION

            PcmCaptureProfile.Transcription ->
                MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            AudioConstants.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            throw AudioFailure.Capture
        }
        val recorder = AudioRecord(
            source,
            AudioConstants.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, AudioConstants.PCM_BYTES_PER_CHUNK * 4),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw AudioFailure.Capture
        }
        if (profile == PcmCaptureProfile.Conversation) {
            echoCanceler.attach(recorder.audioSessionId)
        }
        recorder.startRecording()
        return recorder
    }

    private fun openTrack(profile: PcmPlaybackProfile): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            AudioConstants.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            throw AudioFailure.Playback
        }
        val usage = when (profile) {
            PcmPlaybackProfile.Conversation ->
                AudioAttributes.USAGE_VOICE_COMMUNICATION

            PcmPlaybackProfile.Media ->
                AudioAttributes.USAGE_MEDIA
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(AudioConstants.SAMPLE_RATE_HZ)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(
                maxOf(minBuffer, AudioConstants.PCM_BYTES_PER_CHUNK * 4),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw AudioFailure.Playback
        }
        track.setVolume(volume)
        return track
    }

    private inner class CaptureAdapter : PcmCaptureSource {
        override val isRecording: Boolean
            get() = this@AndroidPcmGraph.isRecording

        override suspend fun start(
            profile: PcmCaptureProfile,
        ): Flow<ByteArray> = startCapture(profile)

        override suspend fun stop() = stopCapture()

        override suspend fun dispose() = this@AndroidPcmGraph.dispose()
    }

    private inner class PlaybackAdapter : PcmPlaybackSink {
        override val underruns: Flow<Unit>
            get() = this@AndroidPcmGraph.underruns

        override suspend fun initialize(profile: PcmPlaybackProfile) = initializePlayback(profile)

        override suspend fun play() = this@AndroidPcmGraph.play()

        override suspend fun feed(chunk: ByteArray) = this@AndroidPcmGraph.feed(chunk)

        override suspend fun flush() = this@AndroidPcmGraph.flush()

        override suspend fun bufferedDuration(): Duration = this@AndroidPcmGraph.bufferedDuration()

        override suspend fun setVolume(volume: Double) = this@AndroidPcmGraph.setVolume(volume)

        override suspend fun endOfStream() = this@AndroidPcmGraph.endOfStream()

        override suspend fun stop() = stopPlayback()

        override suspend fun dispose() = this@AndroidPcmGraph.dispose()
    }
}
