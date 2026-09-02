package ai.wiro.realtime.ui.transcription

import ai.wiro.realtime.realtime.RealtimeSessionState
import ai.wiro.realtime.ui.shared.MicrophoneSettingsAlert
import ai.wiro.realtime.ui.shared.StageConnectingState
import ai.wiro.realtime.ui.shared.StageEmptyState
import ai.wiro.realtime.ui.shared.StageLiveHeader
import ai.wiro.realtime.ui.shared.StageStartButton
import ai.wiro.realtime.ui.shared.SystemSettingsCopy
import ai.wiro.realtime.ui.shared.displayCatalogValue
import android.content.ClipData
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechToTextScreen(
    state: SpeechToTextUiState,
    canStart: Boolean,
    accuracyOptions: List<String>,
    canChangeAccuracy: Boolean,
    isVisible: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSelectAccuracy: (String) -> Unit,
    onSetMuted: (Boolean) -> Unit,
    onClearTranscript: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onDismissSettingsAlert: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAccuracy by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(
        modifier = modifier.testTag(
            if (isVisible) {
                "transcription-visible"
            } else {
                "transcription-hidden"
            },
        ),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = TranscriptionCopy.DESTINATION_TITLE,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor =
                        MaterialTheme.colorScheme.surface,
                    ),
                    actions = {
                        TranscriptionActions(
                            state = state,
                            canChangeAccuracy = canChangeAccuracy,
                            accuracyOptions = accuracyOptions,
                            showAccuracy = showAccuracy,
                            onShowAccuracy = { showAccuracy = it },
                            onSelectAccuracy = onSelectAccuracy,
                            onClearTranscript = onClearTranscript,
                            onCopy = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(
                                            ClipData.newPlainText(
                                                "transcript",
                                                state.transcript,
                                            ),
                                        ),
                                    )
                                    snackbarHostState.showSnackbar(
                                        TranscriptionCopy.COPIED,
                                    )
                                }
                            },
                            onOpenSystemSettings = onOpenSystemSettings,
                        )
                    },
                )
                if (state.showsConnecting) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { innerPadding ->
        TranscriptionStage(
            state = state,
            startEnabled = canStart && !state.showsConnecting,
            onStart = onStart,
            onStop = onStop,
            onSetMuted = onSetMuted,
            onOpenSystemSettings = onOpenSystemSettings,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
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

@Composable
private fun TranscriptionActions(
    state: SpeechToTextUiState,
    canChangeAccuracy: Boolean,
    accuracyOptions: List<String>,
    showAccuracy: Boolean,
    onShowAccuracy: (Boolean) -> Unit,
    onSelectAccuracy: (String) -> Unit,
    onClearTranscript: () -> Unit,
    onCopy: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    Box {
        TextButton(
            onClick = { onShowAccuracy(true) },
            enabled = canChangeAccuracy,
            modifier = Modifier.testTag("field-transcriptionDelay"),
        ) {
            Text(displayCatalogValue(state.accuracy))
        }
        DropdownMenu(
            expanded = showAccuracy,
            onDismissRequest = { onShowAccuracy(false) },
        ) {
            accuracyOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(displayCatalogValue(option)) },
                    onClick = {
                        onSelectAccuracy(option)
                        onShowAccuracy(false)
                    },
                )
            }
        }
    }
    if (state.transcript.isNotEmpty()) {
        TextButton(
            onClick = onCopy,
            modifier = Modifier.testTag("copy-transcript"),
        ) {
            Text(TranscriptionCopy.COPY)
        }
    }
    if (state.canClearTranscript) {
        TextButton(onClick = onClearTranscript) {
            Text(TranscriptionCopy.CLEAR)
        }
    }
    if (state.canOpenSystemSettings) {
        TextButton(
            onClick = onOpenSystemSettings,
            modifier = Modifier.testTag("open-settings-control"),
        ) {
            Text(SystemSettingsCopy.OPEN)
        }
    }
}

@Composable
private fun TranscriptionStage(
    state: SpeechToTextUiState,
    startEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSetMuted: (Boolean) -> Unit,
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
                            .testTag("cancel-transcription"),
                    ) {
                        Text(TranscriptionCopy.CANCEL)
                    }
                },
            )
        }

        state.isLive || state.isEnding -> {
            TranscriptionLiveStage(
                state = state,
                onStop = onStop,
                onSetMuted = onSetMuted,
                modifier = modifier,
            )
        }

        state.sessionState == RealtimeSessionState.Failure &&
            state.errorMessage != null &&
            state.transcript.isEmpty() -> {
            StageEmptyState(
                icon = Icons.Outlined.ErrorOutline,
                headline = TranscriptionCopy.FAILURE_TITLE,
                supportingText = state.errorMessage,
                iconTint = MaterialTheme.colorScheme.error,
                modifier = modifier,
                trailing = {
                    StageStartButton(
                        label = TranscriptionCopy.TRY_AGAIN,
                        testTag = "start-transcription",
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

        state.transcript.isNotEmpty() -> {
            Column(modifier = modifier) {
                TranscriptBody(
                    state = state,
                    modifier = Modifier.weight(1f),
                )
                StageStartButton(
                    label = TranscriptionCopy.START_CTA,
                    testTag = "start-transcription",
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
                icon = Icons.Outlined.Mic,
                headline = TranscriptionCopy.EMPTY_TITLE,
                supportingText = TranscriptionCopy.EMPTY_DETAIL,
                modifier = modifier,
                trailing = {
                    StageStartButton(
                        label = TranscriptionCopy.START_CTA,
                        testTag = "start-transcription",
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
 * The recording stage: elapsed time, a steady Expressive wave while
 * the microphone is open, the growing transcript, and the controls
 * floating at the bottom.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TranscriptionLiveStage(
    state: SpeechToTextUiState,
    onStop: () -> Unit,
    onSetMuted: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val amplitude by animateFloatAsState(
        targetValue = if (state.isLive && !state.muted) {
            RECORDING_WAVE_AMPLITUDE
        } else {
            0f
        },
        animationSpec = tween(durationMillis = 300),
        label = "recording-amplitude",
    )
    Box(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            StageLiveHeader(
                caption = when {
                    state.isEnding -> TranscriptionCopy.FINISHING
                    state.muted -> TranscriptionCopy.MUTED_NOTE
                    else -> TranscriptionCopy.LISTENING
                },
                elapsed = state.elapsed,
                amplitude = { amplitude },
                waveTestTag = "recording-wave",
            )
            TranscriptBody(
                state = state,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 16.dp, bottom = 88.dp),
            )
        }
        TranscriptionLiveToolbar(
            state = state,
            onStop = onStop,
            onSetMuted = onSetMuted,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TranscriptionLiveToolbar(
    state: SpeechToTextUiState,
    onStop: () -> Unit,
    onSetMuted: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier,
    ) {
        FilledIconToggleButton(
            checked = state.muted,
            onCheckedChange = onSetMuted,
            modifier = Modifier.testTag("mute-transcription"),
        ) {
            Icon(
                imageVector = if (state.muted) {
                    Icons.Filled.MicOff
                } else {
                    Icons.Filled.Mic
                },
                contentDescription = if (state.muted) {
                    TranscriptionCopy.UNMUTE
                } else {
                    TranscriptionCopy.MUTE
                },
            )
        }
        FilledIconButton(
            onClick = onStop,
            enabled = !state.isEnding,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.testTag("stop-transcription"),
        ) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = TranscriptionCopy.STOP,
            )
        }
    }
}

private const val RECORDING_WAVE_AMPLITUDE: Float = 0.55f

@Composable
private fun TranscriptBody(
    state: SpeechToTextUiState,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(state.transcript, scrollState.maxValue) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState),
    ) {
        if (
            state.sessionState == RealtimeSessionState.Failure &&
            state.errorMessage != null
        ) {
            Text(
                text = state.errorMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        if (state.transcript.isNotEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        text = state.transcript,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

private fun connectingCaption(state: SpeechToTextUiState): String {
    val caption =
        TranscriptionConnectProgress.caption(state.sessionState)
    return caption ?: TranscriptionCopy.CONNECTING
}
