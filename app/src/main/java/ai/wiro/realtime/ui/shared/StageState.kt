package ai.wiro.realtime.ui.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.time.Duration

/**
 * The Material 3 Expressive empty / error stage: an icon on a
 * shaped tonal container, a headline, and one line of support,
 * centered where the content will be.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StageEmptyState(
    icon: ImageVector,
    headline: String,
    supportingText: String?,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = MaterialShapes.Cookie9Sided.toShape(),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(
                modifier = Modifier.size(96.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Text(
            text = headline,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp),
        )
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        trailing?.invoke()
    }
}

/**
 * The wait after a tap: the Expressive morphing loading shape and
 * the named step.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StageConnectingState(
    caption: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LoadingIndicator(
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp),
        )
        trailing?.invoke()
    }
}

/** The stage's one primary action: a microphone and a promise. */
@Composable
fun StageStartButton(
    label: String,
    testTag: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(56.dp)
            .testTag(testTag),
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/**
 * The head of a live stage: what is happening, for how long, and
 * the Expressive wave. The amplitude lambda keeps the wave redraw
 * out of this composable's recomposition.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StageLiveHeader(
    caption: String,
    elapsed: Duration,
    amplitude: () -> Float,
    waveTestTag: String,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = caption,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = formatElapsed(elapsed),
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        LinearWavyProgressIndicator(
            progress = { 1f },
            amplitude = { amplitude() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp)
                .testTag(waveTestTag),
        )
    }
}
