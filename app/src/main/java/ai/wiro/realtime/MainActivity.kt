package ai.wiro.realtime

import ai.wiro.realtime.app.WiroEnvironment
import ai.wiro.realtime.app.theme.KotlinRealtimeTheme
import ai.wiro.realtime.audio.AndroidMicrophonePermission
import ai.wiro.realtime.audio.AndroidPcmGraph
import ai.wiro.realtime.audio.AndroidRealtimeAudioSession
import ai.wiro.realtime.audio.AndroidSystemSettingsLink
import ai.wiro.realtime.audio.BufferedPcmAudioRecorder
import ai.wiro.realtime.audio.PcmCaptureSource
import ai.wiro.realtime.audio.PcmPlaybackProfile
import ai.wiro.realtime.audio.PcmPlaybackSink
import ai.wiro.realtime.audio.QueuedPcmAudioPlayer
import ai.wiro.realtime.audio.RealtimeAudioSession
import ai.wiro.realtime.auth.WiroCredentials
import ai.wiro.realtime.realtime.RealtimeSessionGuard
import ai.wiro.realtime.ui.conversation.SpeechToSpeechViewModel
import ai.wiro.realtime.ui.home.MissingCredentialsScreen
import ai.wiro.realtime.ui.home.RealtimeHomeView
import ai.wiro.realtime.ui.home.RealtimeHomeViewModel
import ai.wiro.realtime.ui.speech.TextToSpeechViewModel
import ai.wiro.realtime.ui.transcription.SpeechToTextViewModel
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The stage follows the system appearance, so `auto` keeps
        // the clock legible on cream and on the dark surface alike.
        enableEdgeToEdge()
        val credentials = runCatching {
            WiroEnvironment.fromLaunchEnvironment(
                buildApiKey = BuildConfig.WIRO_API_KEY,
                buildApiSecret = BuildConfig.WIRO_API_SECRET,
            )
        }
        setContent {
            KotlinRealtimeTheme {
                credentials.fold(
                    onSuccess = {
                        val microphone = remember {
                            AndroidMicrophonePermission(this)
                        }
                        val settingsLink = remember {
                            AndroidSystemSettingsLink(this)
                        }
                        val audioSession = remember {
                            AndroidRealtimeAudioSession(
                                this,
                                microphone,
                                settingsLink,
                            )
                        }
                        val graph = remember { AndroidPcmGraph() }
                        KotlinRealtimeApp(
                            credentials = it,
                            audioSession = audioSession,
                            captureSource = graph.capture,
                            playbackSink = graph.playback,
                        )
                    },
                    onFailure = { error ->
                        MissingCredentialsScreen(
                            message = error.message.orEmpty(),
                        )
                    },
                )
            }
        }
    }
}

@Composable
fun KotlinRealtimeApp(
    credentials: WiroCredentials,
    audioSession: RealtimeAudioSession,
    captureSource: PcmCaptureSource,
    playbackSink: PcmPlaybackSink,
) {
    val home = viewModel {
        RealtimeHomeViewModel(
            audioSession = audioSession,
            sessionGuard = RealtimeSessionGuard(),
        )
    }
    val sessionGuard = home.sessionGuard
    val conversation = viewModel {
        SpeechToSpeechViewModel.live(
            credentials = credentials,
            audioSession = audioSession,
            sessionGuard = sessionGuard,
            recorder = BufferedPcmAudioRecorder(captureSource),
            player = QueuedPcmAudioPlayer(
                sink = playbackSink,
                // Voice-communication: the echo canceller's track.
                profile = PcmPlaybackProfile.Conversation,
                queueCapacityInChunks =
                SpeechToSpeechViewModel.PLAYBACK_QUEUE_CAPACITY_IN_CHUNKS,
            ),
        )
    }
    val transcription = viewModel {
        SpeechToTextViewModel.live(
            credentials = credentials,
            audioSession = audioSession,
            sessionGuard = sessionGuard,
            recorder = BufferedPcmAudioRecorder(captureSource),
        )
    }
    val speech = viewModel {
        TextToSpeechViewModel.live(
            credentials = credentials,
            audioSession = audioSession,
            sessionGuard = sessionGuard,
            player = QueuedPcmAudioPlayer(
                sink = playbackSink,
                // Playback-only: a media track, so the loudspeaker.
                profile = PcmPlaybackProfile.Media,
                queueCapacityInChunks =
                TextToSpeechViewModel.PLAYBACK_QUEUE_CAPACITY_IN_CHUNKS,
            ),
        )
    }
    RealtimeHomeView(
        home = home,
        conversation = conversation,
        transcription = transcription,
        speech = speech,
    )
}
