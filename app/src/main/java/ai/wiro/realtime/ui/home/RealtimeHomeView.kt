package ai.wiro.realtime.ui.home

import ai.wiro.realtime.ui.conversation.SpeechToSpeechScreen
import ai.wiro.realtime.ui.conversation.SpeechToSpeechViewModel
import ai.wiro.realtime.ui.speech.TextToSpeechScreen
import ai.wiro.realtime.ui.speech.TextToSpeechViewModel
import ai.wiro.realtime.ui.transcription.SpeechToTextScreen
import ai.wiro.realtime.ui.transcription.SpeechToTextViewModel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun RealtimeHomeView(
    home: RealtimeHomeViewModel,
    conversation: SpeechToSpeechViewModel,
    transcription: SpeechToTextViewModel,
    speech: TextToSpeechViewModel,
    modifier: Modifier = Modifier,
) {
    val homeState by home.uiState.collectAsStateWithLifecycle()
    EndSessionsWhenBackgrounded(
        home = home,
        conversation = conversation,
        transcription = transcription,
        speech = speech,
    )
    Scaffold(
        modifier = modifier.testTag("realtime-home"),
        containerColor = MaterialTheme.colorScheme.background,
        // Destinations own the status bar so the clock sits on the
        // TopAppBar, not a cream strip the system paints white.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            HomeNavigationBar(
                selected = homeState.destination,
                onSelect = home::select,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            MountedDestination(
                visible = homeState.isVisible(
                    RealtimeDestination.Conversation,
                ),
            ) {
                ConversationRoute(
                    viewModel = conversation,
                    isVisible = homeState.isVisible(
                        RealtimeDestination.Conversation,
                    ),
                )
            }
            MountedDestination(
                visible = homeState.isVisible(
                    RealtimeDestination.Transcription,
                ),
            ) {
                TranscriptionRoute(
                    viewModel = transcription,
                    isVisible = homeState.isVisible(
                        RealtimeDestination.Transcription,
                    ),
                )
            }
            MountedDestination(
                visible = homeState.isVisible(RealtimeDestination.Speech),
            ) {
                SpeechRoute(
                    viewModel = speech,
                    isVisible = homeState.isVisible(
                        RealtimeDestination.Speech,
                    ),
                )
            }
        }
    }
}

/**
 * Leaving the process is the same as leaving the tab: a billed
 * task must not keep the microphone or the speaker after the
 * speaker has gone elsewhere.
 *
 * [Lifecycle.Event.ON_STOP] is the Android stand-in for Swift's
 * `scenePhase == .background`. ON_PAUSE is Control Center and
 * permission dialogs — treating those as hang-up kills a Run
 * mid-flight.
 */
@Composable
private fun EndSessionsWhenBackgrounded(
    home: RealtimeHomeViewModel,
    conversation: SpeechToSpeechViewModel,
    transcription: SpeechToTextViewModel,
    speech: TextToSpeechViewModel,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(
        lifecycleOwner,
        home,
        conversation,
        transcription,
        speech,
    ) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    conversation.onVisibilityChanged(false)
                    transcription.onVisibilityChanged(false)
                    speech.onVisibilityChanged(false)
                    home.deactivateAudio()
                }

                Lifecycle.Event.ON_START -> {
                    if (home.isVisible(RealtimeDestination.Conversation)) {
                        conversation.onVisibilityChanged(true)
                    }
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun MountedDestination(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (visible) 1f else 0f)
            .alpha(if (visible) 1f else 0f),
    ) {
        content()
    }
}

@Composable
private fun ConversationRoute(
    viewModel: SpeechToSpeechViewModel,
    isVisible: Boolean,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(isVisible) {
        viewModel.onVisibilityChanged(isVisible)
    }
    val voiceActivity = viewModel.sessionService.voiceActivity
    SpeechToSpeechScreen(
        state = state,
        voiceOptions = viewModel.voiceOptions,
        settingsFields = viewModel.settingsFields,
        isVisible = isVisible,
        canStart = viewModel.canStart,
        canChangeVoice = viewModel.canChangeVoice,
        onStart = { scope.launch { viewModel.start() } },
        onStop = { scope.launch { viewModel.stop() } },
        onSelectVoice = { scope.launch { viewModel.selectVoice(it) } },
        onSetParameter = viewModel::setParameter,
        onSetMuted = viewModel::setMuted,
        onInterrupt = viewModel::interruptAssistant,
        onOpenSystemSettings = {
            scope.launch { viewModel.openSystemSettings() }
        },
        onDismissSettingsAlert = viewModel::dismissSystemSettingsAlert,
        voicePhase = { voiceActivity.phase },
        voiceLevel = {
            maxOf(voiceActivity.userLevel, voiceActivity.aiLevel)
        },
    )
}

@Composable
private fun TranscriptionRoute(
    viewModel: SpeechToTextViewModel,
    isVisible: Boolean,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(isVisible) {
        viewModel.onVisibilityChanged(isVisible)
    }
    SpeechToTextScreen(
        state = state,
        canStart = viewModel.canStart,
        accuracyOptions = viewModel.accuracyOptions,
        canChangeAccuracy = viewModel.canChangeAccuracy,
        isVisible = isVisible,
        onStart = { scope.launch { viewModel.start() } },
        onStop = { scope.launch { viewModel.stop() } },
        onSelectAccuracy = viewModel::selectAccuracy,
        onSetMuted = viewModel::setMuted,
        onClearTranscript = viewModel::clearTranscript,
        onOpenSystemSettings = {
            scope.launch { viewModel.openSystemSettings() }
        },
        onDismissSettingsAlert = viewModel::dismissSystemSettingsAlert,
    )
}

@Composable
private fun SpeechRoute(
    viewModel: TextToSpeechViewModel,
    isVisible: Boolean,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(isVisible) {
        viewModel.onVisibilityChanged(isVisible)
    }
    TextToSpeechScreen(
        state = state,
        canStart = viewModel.canStart,
        isVisible = isVisible,
        onPromptChange = viewModel::setPrompt,
        onPlay = { scope.launch { viewModel.start() } },
        onStop = { scope.launch { viewModel.stop() } },
        onSelectModel = viewModel::selectModel,
        onSetParameter = viewModel::setParameter,
    )
}

@Composable
private fun HomeNavigationBar(
    selected: RealtimeDestination,
    onSelect: (RealtimeDestination) -> Unit,
) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor =
        MaterialTheme.colorScheme.onSecondaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
        unselectedIconColor =
        MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor =
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        RealtimeDestination.entries.forEach { destination ->
            val isSelected = selected == destination
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        imageVector = if (isSelected) {
                            destination.selectedIcon
                        } else {
                            destination.unselectedIcon
                        },
                        contentDescription = destination.title,
                    )
                },
                label = { Text(destination.title) },
                colors = colors,
                modifier = Modifier.testTag(
                    "tab-${destination.name.lowercase()}",
                ),
            )
        }
    }
}

private val RealtimeDestination.title: String
    get() = when (this) {
        RealtimeDestination.Conversation -> "Conversation"
        RealtimeDestination.Transcription -> "Transcription"
        RealtimeDestination.Speech -> "Speech"
    }

private val RealtimeDestination.selectedIcon
    get() = when (this) {
        RealtimeDestination.Conversation ->
            Icons.AutoMirrored.Filled.Chat

        RealtimeDestination.Transcription -> Icons.Filled.Mic

        RealtimeDestination.Speech -> Icons.Filled.GraphicEq
    }

private val RealtimeDestination.unselectedIcon
    get() = when (this) {
        RealtimeDestination.Conversation ->
            Icons.AutoMirrored.Outlined.Chat

        RealtimeDestination.Transcription -> Icons.Outlined.Mic

        RealtimeDestination.Speech -> Icons.Outlined.GraphicEq
    }
