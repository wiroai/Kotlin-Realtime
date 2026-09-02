package ai.wiro.realtime.ui.speech

import ai.wiro.realtime.models.ModelDefinition
import ai.wiro.realtime.services.speech.SpeechFields
import ai.wiro.realtime.ui.shared.ModelParameterForm
import ai.wiro.realtime.ui.shared.displayCatalogValue
import ai.wiro.realtime.ui.shared.formatElapsed
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextToSpeechScreen(
    state: TextToSpeechUiState,
    canStart: Boolean,
    isVisible: Boolean,
    onPromptChange: (String) -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onSelectModel: (ModelDefinition) -> Unit,
    onSetParameter: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSettings by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    val settingsFields = SpeechFields.settingsFields(state.selectedModel)
    val playEnabled = canStart && !state.showsConnecting

    Scaffold(
        modifier = modifier.testTag(
            if (isVisible) "speech-visible" else "speech-hidden",
        ),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                SpeechTopBar(
                    state = state,
                    showModelMenu = showModelMenu,
                    hasSettings = settingsFields.isNotEmpty(),
                    onShowModelMenu = { showModelMenu = it },
                    onSelectModel = onSelectModel,
                    onOpenSettings = { showSettings = true },
                )
                if (state.showsConnecting) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            stageNote(state)?.let { caption ->
                SpeechLiveStatus(state = state, caption = caption)
            }
            state.errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            OutlinedTextField(
                value = state.prompt,
                onValueChange = onPromptChange,
                enabled = state.canEditPrompt,
                label = { Text(SpeechCopy.PROMPT_HINT) },
                minLines = 8,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("tts-prompt"),
            )
            if (state.prompt.isNotEmpty()) {
                Text(
                    text = SpeechCopy.characterCount(state.prompt.count()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.End)
                        .testTag("tts-character-count")
                        .padding(top = 8.dp),
                )
            }
            SpeechPrimaryAction(
                state = state,
                playEnabled = playEnabled,
                onPlay = onPlay,
                onStop = onStop,
            )
        }
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            // Positional: Hidden start, no partial expansion stop.
            sheetState = rememberBottomSheetState(
                SheetValue.Hidden,
                setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    text = SpeechCopy.SETTINGS,
                    style = MaterialTheme.typography.titleLarge,
                )
                ModelParameterForm(
                    fields = settingsFields,
                    value = { id -> state.parameters.value(id).orEmpty() },
                    onValueChange = onSetParameter,
                    optionTitle = ::displayCatalogValue,
                    enabled = state.canChangeModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpeechLiveStatus(
    state: TextToSpeechUiState,
    caption: String,
) {
    val amplitude by animateFloatAsState(
        targetValue = if (state.isPlaying) SPEAKING_WAVE_AMPLITUDE else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "speaking-amplitude",
    )
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = caption,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (state.isLive || state.isEnding) {
                Text(
                    text = formatElapsed(state.elapsed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LinearWavyProgressIndicator(
            progress = { 1f },
            amplitude = { amplitude },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .testTag("speaking-wave"),
        )
    }
}

private const val SPEAKING_WAVE_AMPLITUDE: Float = 0.55f

/**
 * Play is the point of the screen, so it is a full-width button
 * under the prompt rather than a toolbar icon. A button, unlike a
 * FAB, can sit disabled while the prompt is empty.
 */
@Composable
private fun SpeechPrimaryAction(
    state: TextToSpeechUiState,
    playEnabled: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    val stops = state.showsConnecting || state.isLive || state.isEnding
    if (stops) {
        Button(
            onClick = onStop,
            enabled = !state.isEnding,
            colors = ButtonDefaults.buttonColors(
                containerColor =
                MaterialTheme.colorScheme.errorContainer,
                contentColor =
                MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .height(52.dp)
                .testTag("stop-speech"),
        ) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = if (state.showsConnecting) {
                    SpeechCopy.CANCEL
                } else {
                    SpeechCopy.STOP
                },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    } else {
        Button(
            onClick = onPlay,
            enabled = playEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .height(52.dp)
                .testTag("play-speech"),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = SpeechCopy.PLAY,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeechTopBar(
    state: TextToSpeechUiState,
    showModelMenu: Boolean,
    hasSettings: Boolean,
    onShowModelMenu: (Boolean) -> Unit,
    onSelectModel: (ModelDefinition) -> Unit,
    onOpenSettings: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = SpeechCopy.DESTINATION_TITLE,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        actions = {
            Box {
                TextButton(
                    onClick = { onShowModelMenu(true) },
                    enabled = state.canChangeModel,
                    modifier = Modifier.testTag("tts-model-selector"),
                ) {
                    Text(state.selectedModel.displayName)
                }
                DropdownMenu(
                    expanded = showModelMenu,
                    onDismissRequest = { onShowModelMenu(false) },
                ) {
                    SpeechFields.models.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.displayName) },
                            onClick = {
                                onSelectModel(option)
                                onShowModelMenu(false)
                            },
                        )
                    }
                }
            }
            if (hasSettings) {
                IconButton(
                    onClick = onOpenSettings,
                    enabled = state.canChangeModel,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = SpeechCopy.SETTINGS,
                    )
                }
            }
        },
    )
}

private fun stageNote(state: TextToSpeechUiState): String? {
    if (
        !state.showsConnecting &&
        !state.isLive &&
        !state.isEnding
    ) {
        return null
    }
    return SpeechConnectProgress.caption(
        state = state.sessionState,
        isPlaying = state.isPlaying,
    )
}
