package com.securetalk.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Connection-quality pill used in headers (radio-style status LED + label). */
@Composable
fun ConnectionBadge(
    state: String,
    connected: Boolean,
    degraded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ledColor = when {
        degraded -> StColors.Amber
        connected -> StColors.Green
        else -> StColors.TxRed
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(StColors.SurfaceHigh)
            .border(1.dp, StColors.Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(ledColor),
        )
        Text(
            state.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = StColors.GreyText,
        )
    }
}

/** Full-state helper: loading / error / empty / offline states used by all screens. */
@Composable
fun StateBlock(
    title: String,
    detail: String? = null,
    loading: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) {
            CircularProgressIndicator(color = StColors.Amber)
            Spacer(Modifier.height(16.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        detail?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = StColors.GreyText,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun OfflineBanner(visible: Boolean, text: String = "OFFLINE — transmit unavailable") {
    if (!visible) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(StColors.TxRed.copy(alpha = 0.15f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = StColors.TxRed,
        )
    }
}

/**
 * The PTT bar: large press-and-hold control. Visual only here — press/release
 * callbacks come from the Active Channel ViewModel wired to PushToTalkController.
 */
@Composable
fun PttBar(
    isSpeaking: Boolean,
    isQueueing: Boolean,
    queuePosition: Int?,
    enabled: Boolean,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = when {
        isSpeaking -> StColors.TxRed
        isQueueing -> StColors.AmberDim
        else -> StColors.SurfaceHigh
    }
    val fg = when {
        isSpeaking -> Color.White
        isQueueing -> StColors.Amber
        else -> StColors.GreyText
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(bg)
                .border(2.dp, if (isSpeaking) Color.White.copy(alpha = 0.4f) else StColors.Border, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Surface(
                color = Color.Transparent,
                modifier = Modifier.fillMaxSize(),
                onClick = { /* handled by pointer input below via parent */ },
                enabled = false,
            ) {}
            Text(
                text = when {
                    isSpeaking -> "ON AIR — RELEASE TO END"
                    isQueueing -> "QUEUED — POSITION ${queuePosition ?: "?"}"
                    enabled -> "HOLD TO TALK"
                    else -> "CONNECT TO TRANSMIT"
                },
                style = MaterialTheme.typography.titleMedium,
                color = fg,
            )
        }
    }
}

@Composable
fun StOutlinedButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled) { Text(label) }
}
