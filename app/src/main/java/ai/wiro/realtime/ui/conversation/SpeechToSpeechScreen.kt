package ai.wiro.realtime.ui.conversation

import ai.wiro.realtime.models.ModelFieldDefinition
import ai.wiro.realtime.services.conversation.ConversationVoicePhase
import ai.wiro.realtime.ui.shared.MicrophoneSettingsAlert
import ai.wiro.realtime.ui.shared.ModelParameterForm
import ai.wiro.realtime.ui.shared.StageConnectingState
import ai.wiro.realtime.ui.shared.StageEmptyState
import ai.wiro.realtime.ui.shared.StageLiveHeader
import ai.wiro.realtime.ui.shared.StageStartButton
import ai.wiro.realtime.ui.shared.SystemSettingsCopy
import ai.wiro.realtime.ui.shared.displayCatalogValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechToSpeechScreen(
    state: SpeechToSpeechUiState,
    voiceOptions: List<String>,
    settingsFields: List<ModelFieldDefinition>,
    isVisible: Boolean,
    canStart: Boolean,
    canChangeVoice: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSelectVoice: (String) -> Unit,
    onSetParameter: (String, String) -> Unit,
    onSetMuted: (Boolean) -> Unit,
    onInterrupt: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onDismissSettingsAlert: () -> Unit,
    modifier: Modifier = Modifier,
    voicePhase: () -> ConversationVoicePhase = {
        ConversationVoicePhase.Quiet
    },
    voiceLevel: () -> Double = { 0.0 },
) {
    var showSettings by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.testTag(
            if (isVisible) "conversation-visible" else "conversation-hidden",
        ),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                ConversationTopBar(
                    state = state,
                    voiceOptions = voiceOptions,
                    canChangeVoice = canChangeVoice,
                    onSelectVoice = onSelectVoice,
                    onOpenSettings = { showSettings = true },
                    onOpenSystemSettings = onOpenSystemSettings,
                )
                if (state.showsConnecting) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { innerPadding ->
        ConversationStageContent(
            state = state,
            startEnabled = canStart && !state.showsConnecting,
            voicePhase = voicePhase,
            voiceLevel = voiceLevel,
            onStart = onStart,
            onStop = onStop,
            onSetMuted = onSetMuted,
            onInterrupt = onInterrupt,
            onOpenSystemSettings = onOpenSystemSettings,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        )
    }
    if (showSettings) {
        ConversationSettingsSheet(
            state = state,
            settingsFields = settingsFields,
            onSetParameter = onSetParameter,
            onDismiss = { showSettings = false },
        )
    }
    if (state.showsSystemSettingsAlert) {
        MicrophoneSettingsAlert(
            message = state.errorMessage,
            onOpenSystemSettings = onOpenSystemSettings,
            onDismiss = onDismissSettingsAlert,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationTopBar(
    state: SpeechToSpeechUiState,
    voiceOptions: List<String>,
    canChangeVoice: Boolean,
    onSelectVoice: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = ConversationCopy.DESTINATION_TITLE,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        actions = {
            ConversationVoicePicker(
                voice = state.voice,
                options = voiceOptions,
                enabled = canChangeVoice,
                loading = state.isReconfiguring,
                onSelect = onSelectVoice,
            )
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = ConversationCopy.SETTINGS,
                )
            }
            if (state.canOpenSystemSettings) {
                TextButton(
                    onClick = onOpenSystemSettings,
                    modifier = Modifier.testTag("open-settings-control"),
                ) {
                    Text(SystemSettingsCopy.OPEN)
                }
            }
        },
    )
}

@Composable
private fun ConversationVoicePicker(
    voice: String,
    options: List<String>,
    enabled: Boolean,
    loading: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val menuOpen = expanded && !loading
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(
                onClick = { expanded = true },
                enabled = enabled && !loading,
                modifier = Modifier.testTag("field-voice"),
            ) {
                Text(
                    text = displayCatalogValue(voice),
                    maxLines = 1,
                    softWrap = false,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(displayCatalogValue(option)) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(16.dp)
                    .testTag("voice-reconfiguring")
                    .semantics {
                        contentDescription =
                            ConversationCopy.VOICE_CHANGING
                    },
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun ConversationStageContent(
    state: SpeechToSpeechUiState,
    startEnabled: Boolean,
    voicePhase: () -> ConversationVoicePhase,
    voiceLevel: () -> Double,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSetMuted: (Boolean) -> Unit,
    onInterrupt: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.showsConnecting -> {
            StageConnectingState(
                caption = connectingCaption(state),
                modifier = modifier,
                trailing = {
                    TextButton(
                        onClick = onStop,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .testTag("cancel-conversation"),
                    ) {
                        Text(ConversationCopy.CANCEL)
                    }
                },
            )
        }

        state.isLive || state.isEnding -> {
            ConversationLiveStage(
                state = state,
                voicePhase = voicePhase,
                voiceLevel = voiceLevel,
                onStop = onStop,
                onSetMuted = onSetMuted,
                onInterrupt = onInterrupt,
                modifier = modifier,
            )
        }

        state.displayedStage == ConversationStage.Failed &&
            state.errorMessage != null &&
            !state.hasTranscripts -> {
            StageEmptyState(
                icon = Icons.Outlined.ErrorOutline,
                headline = ConversationCopy.FAILURE_TITLE,
                supportingText = state.errorMessage,
                iconTint = MaterialTheme.colorScheme.error,
                modifier = modifier,
                trailing = {
                    StageStartButton(
                        label = ConversationCopy.TRY_AGAIN,
                        testTag = "start-conversation",
                        enabled = startEnabled,
                        onClick = onStart,
                        modifier = Modifier.padding(top = 28.dp),
                    )
                    if (state.canOpenSystemSettings) {
                        TextButton(
                            onClick = onOpenSystemSettings,
                            modifier = Modifier.testTag(
                                "open-settings-control",
                            ),
                        ) {
                            Text(SystemSettingsCopy.OPEN)
                        }
                    }
                },
            )
        }

        state.hasTranscripts -> {
            Column(modifier = modifier) {
                ConversationTranscripts(
                    state = state,
                    modifier = Modifier.weight(1f),
                )
                StageStartButton(
                    label = ConversationCopy.START_CTA,
                    testTag = "start-conversation",
                    enabled = startEnabled,
                    onClick = onStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        }

        else -> {
            StageEmptyState(
                icon = Icons.Outlined.Forum,
                headline = ConversationCopy.EMPTY_TITLE,
                supportingText = ConversationCopy.EMPTY_DETAIL,
                modifier = modifier,
                trailing = {
                    StageStartButton(
                        label = ConversationCopy.START_CTA,
                        testTag = "start-conversation",
                        enabled = startEnabled,
                        onClick = onStart,
                        modifier = Modifier.padding(top = 28.dp),
                    )
                },
            )
        }
    }
}

/**
 * The live call stage: who is talking, for how long, and the
 * Expressive wavy line whose amplitude is the actual voice level.
 * The controls float at the bottom, where a call keeps them.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConversationLiveStage(
    state: SpeechToSpeechUiState,
    voicePhase: () -> ConversationVoicePhase,
    voiceLevel: () -> Double,
    onStop: () -> Unit,
    onSetMuted: (Boolean) -> Unit,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sampled by produceState(
        initialValue = ConversationVoiceSample(),
        state.isLive,
        state.muted,
    ) {
        while (state.isLive) {
            value = ConversationVoiceSample(
                phase = voicePhase(),
                level = if (state.muted) {
                    0f
                } else {
                    voiceLevel().toFloat().coerceIn(0f, 1f)
                },
            )
            delay(SAMPLE_PERIOD_MILLIS)
        }
    }
    val amplitude by animateFloatAsState(
        targetValue = if (state.isLive) sampled.level else 0f,
        animationSpec = tween(durationMillis = 90),
        label = "voice-amplitude",
    )
    Box(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            StageLiveHeader(
                caption = liveCaption(state, sampled.phase),
                elapsed = state.elapsed,
                amplitude = { amplitude },
                waveTestTag = "voice-level",
            )
            ConversationTranscripts(
                state = state,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 16.dp, bottom = 88.dp),
            )
        }
        ConversationLiveToolbar(
            state = state,
            onStop = onStop,
            onSetMuted = onSetMuted,
            onInterrupt = onInterrupt,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConversationLiveToolbar(
    state: SpeechToSpeechUiState,
    onStop: () -> Unit,
    onSetMuted: (Boolean) -> Unit,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier,
    ) {
        FilledIconToggleButton(
            checked = state.muted,
            onCheckedChange = onSetMuted,
            modifier = Modifier.testTag("mute-conversation"),
        ) {
            Icon(
                imageVector = if (state.muted) {
                    Icons.Filled.MicOff
                } else {
                    Icons.Filled.Mic
                },
                contentDescription = if (state.muted) {
                    ConversationCopy.UNMUTE
                } else {
                    ConversationCopy.MUTE
                },
            )
        }
        IconButton(
            onClick = onInterrupt,
            modifier = Modifier.testTag("interrupt-conversation"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = ConversationCopy.INTERRUPT,
            )
        }
        FilledIconButton(
            onClick = onStop,
            enabled = !state.isEnding,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.testTag("stop-conversation"),
        ) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = ConversationCopy.STOP,
            )
        }
    }
}

private data class ConversationVoiceSample(
    val phase: ConversationVoicePhase = ConversationVoicePhase.Quiet,
    val level: Float = 0f,
)

private const val SAMPLE_PERIOD_MILLIS: Long = 50

private fun liveCaption(
    state: SpeechToSpeechUiState,
    phase: ConversationVoicePhase,
): String = when {
    state.isEnding -> ConversationCopy.ENDING

    state.muted -> ConversationCopy.MUTED_NOTE

    phase == ConversationVoicePhase.UserSpeaking ->
        ConversationCopy.HEARING_YOU

    phase == ConversationVoicePhase.AiSpeaking ->
        ConversationCopy.ASSISTANT_SPEAKING

    else -> ConversationCopy.LISTENING
}

@Composable
private fun ConversationTranscripts(
    state: SpeechToSpeechUiState,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(
        state.userTranscript,
        state.aiTranscript,
        scrollState.maxValue,
    ) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    SelectionContainer(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
        ) {
            if (
                state.displayedStage == ConversationStage.Failed &&
                state.errorMessage != null
            ) {
                Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            if (state.userTranscript.isNotEmpty()) {
                TranscriptBubble(
                    label = ConversationCopy.YOU,
                    text = state.userTranscript,
                    alignEnd = true,
                )
            }
            if (state.aiTranscript.isNotEmpty()) {
                TranscriptBubble(
                    label = ConversationCopy.ASSISTANT,
                    text = state.aiTranscript,
                    alignEnd = false,
                )
            }
        }
    }
}

@Composable
private fun TranscriptBubble(
    label: String,
    text: String,
    alignEnd: Boolean,
) {
    val shape = if (alignEnd) {
        RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        contentAlignment = if (alignEnd) {
            Alignment.CenterEnd
        } else {
            Alignment.CenterStart
        },
    ) {
        Column(
            horizontalAlignment = if (alignEnd) {
                Alignment.End
            } else {
                Alignment.Start
            },
            modifier = Modifier.fillMaxWidth(BUBBLE_MAX_WIDTH_FRACTION),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Surface(
                shape = shape,
                color = if (alignEnd) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(
                        horizontal = 16.dp,
                        vertical = 12.dp,
                    ),
                )
            }
        }
    }
}

private const val BUBBLE_MAX_WIDTH_FRACTION: Float = 0.88f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationSettingsSheet(
    state: SpeechToSpeechUiState,
    settingsFields: List<ModelFieldDefinition>,
    onSetParameter: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Positional: Hidden start, no partial expansion stop.
        sheetState = rememberBottomSheetState(
            SheetValue.Hidden,
            setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(
                text = ConversationCopy.SETTINGS,
                style = MaterialTheme.typography.titleLarge,
            )
            ModelParameterForm(
                fields = settingsFields,
                value = { id -> state.parameters.value(id).orEmpty() },
                onValueChange = onSetParameter,
                optionTitle = ::displayCatalogValue,
                enabled = state.canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            )
        }
    }
}

private fun connectingCaption(state: SpeechToSpeechUiState): String {
    val caption = ConversationConnectProgress.caption(
        state.sessionState,
    )
    return caption ?: ConversationCopy.CONNECTING
}
