package ai.wiro.realtime.ui.home

import ai.wiro.realtime.app.theme.KotlinRealtimeTheme
import ai.wiro.realtime.audio.support.FakeRealtimeAudioSession
import ai.wiro.realtime.realtime.RealtimeSessionState
import ai.wiro.realtime.services.conversation.support.FakeConversationSessionController
import ai.wiro.realtime.services.speech.support.FakeSpeechSessionController
import ai.wiro.realtime.services.transcription.support.FakeTranscriptionSessionController
import ai.wiro.realtime.ui.conversation.SpeechToSpeechScreen
import ai.wiro.realtime.ui.conversation.SpeechToSpeechUiState
import ai.wiro.realtime.ui.conversation.SpeechToSpeechViewModel
import ai.wiro.realtime.ui.speech.TextToSpeechViewModel
import ai.wiro.realtime.ui.transcription.SpeechToTextScreen
import ai.wiro.realtime.ui.transcription.SpeechToTextUiState
import ai.wiro.realtime.ui.transcription.SpeechToTextViewModel
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
class RealtimeHomeViewTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun switchingTabsKeepsDestinationsMounted() {
        val home = RealtimeHomeViewModel()
        composeRule.setContent {
            KotlinRealtimeTheme {
                RealtimeHomeView(
                    home = home,
                    conversation = SpeechToSpeechViewModel(
                        FakeConversationSessionController(),
                    ),
                    transcription = SpeechToTextViewModel(
                        FakeTranscriptionSessionController(),
                    ),
                    speech = TextToSpeechViewModel(
                        FakeSpeechSessionController(),
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("conversation-visible")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("tab-transcription").performClick()
        composeRule.onNodeWithTag("transcription-visible")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("conversation-hidden").assertExists()
        composeRule.onNodeWithTag("tab-speech").performClick()
        composeRule.onNodeWithTag("speech-visible").assertIsDisplayed()
    }

    @Test
    fun idleStartIsEnabledWhenConnectingIsFalse() {
        composeRule.setContent {
            KotlinRealtimeTheme {
                SpeechToSpeechScreen(
                    state = SpeechToSpeechUiState(),
                    voiceOptions = listOf("marin"),
                    settingsFields = emptyList(),
                    isVisible = true,
                    canStart = true,
                    canChangeVoice = true,
                    onStart = {},
                    onStop = {},
                    onSelectVoice = {},
                    onSetParameter = { _, _ -> },
                    onSetMuted = {},
                    onInterrupt = {},
                    onOpenSystemSettings = {},
                    onDismissSettingsAlert = {},
                )
            }
        }

        composeRule.onNodeWithTag("start-conversation")
            .assertIsDisplayed()
            .assertIsEnabled()
        composeRule.onNodeWithTag("field-voice")
            .assertIsDisplayed()
            .assertIsEnabled()
        composeRule.onNodeWithText("Marin").assertIsDisplayed()
    }

    @Test
    fun connectingHidesTheIdleStartControl() {
        composeRule.setContent {
            KotlinRealtimeTheme {
                SpeechToSpeechScreen(
                    state = SpeechToSpeechUiState(
                        sessionState = RealtimeSessionState.ConnectingSocket,
                        reportsConnecting = true,
                    ),
                    voiceOptions = listOf("marin"),
                    settingsFields = emptyList(),
                    isVisible = true,
                    canStart = false,
                    canChangeVoice = false,
                    onStart = {},
                    onStop = {},
                    onSelectVoice = {},
                    onSetParameter = { _, _ -> },
                    onSetMuted = {},
                    onInterrupt = {},
                    onOpenSystemSettings = {},
                    onDismissSettingsAlert = {},
                )
            }
        }

        composeRule.onNodeWithTag("start-conversation").assertDoesNotExist()
    }

    @Test
    fun voiceChangeShowsALoadingIndicator() {
        composeRule.setContent {
            KotlinRealtimeTheme {
                SpeechToSpeechScreen(
                    state = SpeechToSpeechUiState(
                        sessionState = RealtimeSessionState.Streaming,
                        isReconfiguring = true,
                    ),
                    voiceOptions = listOf("marin"),
                    settingsFields = emptyList(),
                    isVisible = true,
                    canStart = false,
                    canChangeVoice = false,
                    onStart = {},
                    onStop = {},
                    onSelectVoice = {},
                    onSetParameter = { _, _ -> },
                    onSetMuted = {},
                    onInterrupt = {},
                    onOpenSystemSettings = {},
                    onDismissSettingsAlert = {},
                )
            }
        }

        composeRule.onNodeWithTag("voice-reconfiguring")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("field-voice").assertIsNotEnabled()
    }

    @Test
    fun copyingATranscriptShowsConfirmation() {
        composeRule.setContent {
            KotlinRealtimeTheme {
                SpeechToTextScreen(
                    state = SpeechToTextUiState(transcript = "hello"),
                    canStart = true,
                    accuracyOptions = listOf("balanced"),
                    canChangeAccuracy = true,
                    isVisible = true,
                    onStart = {},
                    onStop = {},
                    onSelectAccuracy = {},
                    onSetMuted = {},
                    onClearTranscript = {},
                    onOpenSystemSettings = {},
                    onDismissSettingsAlert = {},
                )
            }
        }

        composeRule.onNodeWithTag("copy-transcript").performClick()
        composeRule.onNodeWithText("Transcript copied")
            .assertIsDisplayed()
    }

    @Test
    fun idleTranscriptionShowsTheEmptyState() {
        composeRule.setContent {
            KotlinRealtimeTheme {
                SpeechToTextScreen(
                    state = SpeechToTextUiState(),
                    canStart = true,
                    accuracyOptions = listOf("balanced"),
                    canChangeAccuracy = true,
                    isVisible = true,
                    onStart = {},
                    onStop = {},
                    onSelectAccuracy = {},
                    onSetMuted = {},
                    onClearTranscript = {},
                    onOpenSystemSettings = {},
                    onDismissSettingsAlert = {},
                )
            }
        }

        composeRule.onNodeWithText("Ready to transcribe")
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "Speak, and your words appear here as you talk.",
        ).assertIsDisplayed()
    }

    @Test
    fun connectingTranscriptionNamesTheWait() {
        composeRule.setContent {
            KotlinRealtimeTheme {
                SpeechToTextScreen(
                    state = SpeechToTextUiState(
                        sessionState =
                        RealtimeSessionState.CreatingTask,
                    ),
                    canStart = false,
                    accuracyOptions = listOf("balanced"),
                    canChangeAccuracy = false,
                    isVisible = true,
                    onStart = {},
                    onStop = {},
                    onSelectAccuracy = {},
                    onSetMuted = {},
                    onClearTranscript = {},
                    onOpenSystemSettings = {},
                    onDismissSettingsAlert = {},
                )
            }
        }

        composeRule.onNodeWithText("Starting the task…")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("start-transcription")
            .assertDoesNotExist()
    }

    @Test
    fun speechPlayStaysDisabledWhileThePromptIsEmpty() {
        composeRule.setContent {
            KotlinRealtimeTheme {
                RealtimeHomeView(
                    home = RealtimeHomeViewModel(
                        destination = RealtimeDestination.Speech,
                    ),
                    conversation = SpeechToSpeechViewModel(
                        FakeConversationSessionController(),
                    ),
                    transcription = SpeechToTextViewModel(
                        FakeTranscriptionSessionController(),
                    ),
                    speech = TextToSpeechViewModel(
                        FakeSpeechSessionController(),
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("play-speech")
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun pausingDoesNotEndALiveConversation() {
        val conversation = FakeConversationSessionController()
        composeRule.setContent {
            homeContent(conversation = conversation)
        }
        composeRule.waitForIdle()
        val suspendBefore = conversation.suspendCalls

        composeRule.activityRule.scenario.moveToState(
            Lifecycle.State.STARTED,
        )
        composeRule.waitForIdle()

        assertEquals(suspendBefore, conversation.suspendCalls)
    }

    @Test
    fun backgroundingSuspendsConversationAndStopsTheOthers() {
        val conversation = FakeConversationSessionController()
        val transcription = FakeTranscriptionSessionController()
        val speech = FakeSpeechSessionController()
        val audioSession = FakeRealtimeAudioSession()
        val home = RealtimeHomeViewModel(audioSession = audioSession)
        composeRule.setContent {
            homeContent(
                conversation = conversation,
                transcription = transcription,
                speech = speech,
                home = home,
            )
        }
        composeRule.waitForIdle()
        val suspendBefore = conversation.suspendCalls
        val resumeBefore = conversation.resumeCalls
        val transcriptionStopBefore = transcription.stopCalls
        val speechStopBefore = speech.stopCalls

        composeRule.activityRule.scenario.moveToState(
            Lifecycle.State.CREATED,
        )
        composeRule.waitUntil {
            conversation.suspendCalls > suspendBefore &&
                transcription.stopCalls > transcriptionStopBefore &&
                speech.stopCalls > speechStopBefore &&
                audioSession.deactivateCalls >= 1
        }

        composeRule.activityRule.scenario.moveToState(
            Lifecycle.State.RESUMED,
        )
        composeRule.waitUntil {
            conversation.resumeCalls > resumeBefore
        }
        assertTrue(conversation.suspendCalls > suspendBefore)
    }

    @Composable
    private fun homeContent(
        conversation: FakeConversationSessionController =
            FakeConversationSessionController(),
        transcription: FakeTranscriptionSessionController =
            FakeTranscriptionSessionController(),
        speech: FakeSpeechSessionController =
            FakeSpeechSessionController(),
        home: RealtimeHomeViewModel = RealtimeHomeViewModel(),
    ) {
        KotlinRealtimeTheme {
            RealtimeHomeView(
                home = home,
                conversation = SpeechToSpeechViewModel(conversation),
                transcription = SpeechToTextViewModel(transcription),
                speech = TextToSpeechViewModel(speech),
            )
        }
    }
}
