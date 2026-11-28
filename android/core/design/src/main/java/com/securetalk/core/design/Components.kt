package com.securetalk.core.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas

// ============================================================================
// WakiTaki component library — every control reads tokens from the theme
// (LocalStExtended / Material scheme). No hardcoded colors outside Theme.kt.
// ============================================================================

/** Shortcut to the current extended tokens. */
@Composable
fun stTokens(): StExtended = LocalStExtended.current

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

enum class StButtonStyle { Primary, Ghost, Danger }

/** Custom button: filled emerald (Primary), ghost outline, or danger fill. */
@Composable
fun StButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: StButtonStyle = StButtonStyle.Primary,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val t = stTokens()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(StMotion.FastMs), label = "btnScale")
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        style == StButtonStyle.Primary -> MaterialTheme.colorScheme.primary
        style == StButtonStyle.Danger -> StColors.TxRed
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> t.greyText
        style == StButtonStyle.Primary -> MaterialTheme.colorScheme.onPrimary
        style == StButtonStyle.Danger -> Color.White
        else -> MaterialTheme.colorScheme.primary
    }
    val shape = MaterialTheme.shapes.small
    Box(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(bg)
            .then(
                if (style == StButtonStyle.Ghost) {
                    Modifier.border(1.dp, if (enabled) t.border else Color.Transparent, shape)
                } else Modifier
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = if (compact) 14.dp else 20.dp, vertical = if (compact) 9.dp else 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = fg,
        )
    }
}

/** Full-width convenience wrapper. */
@Composable
fun StButtonFull(label: String, onClick: () -> Unit, style: StButtonStyle = StButtonStyle.Primary, enabled: Boolean = true) =
    StButton(label, onClick, Modifier.fillMaxWidth(), style = style, enabled = enabled)

// ---------------------------------------------------------------------------
// Cards / panels
// ---------------------------------------------------------------------------

/** Custom card panel with layered border, optional accent edge. */
@Composable
fun StCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val t = stTokens()
    val shape = MaterialTheme.shapes.medium
    val base = Modifier
        .clip(shape)
        .background(MaterialTheme.colorScheme.surface)
        .border(1.dp, t.border, shape)
    Box(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base).then(modifier),
    ) {
        if (accent != null) {
            Box(
                Modifier
                    .padding(start = 0.dp)
                    .width(3.dp)
                    .matchParentSize()
                    .background(accent),
            )
        }
        Column(Modifier.padding(16.dp), content = content)
    }
}

// ---------------------------------------------------------------------------
// Text fields
// ---------------------------------------------------------------------------

/** Styled text input with token-driven colors and focus accent. */
@Composable
fun StTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        placeholder = placeholder?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = stTokens().border,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

// ---------------------------------------------------------------------------
// Status indicators / avatars / chips
// ---------------------------------------------------------------------------

/** Connection-quality pill used in headers (status LED + label). */
@Composable
fun ConnectionBadge(
    state: String,
    connected: Boolean,
    degraded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val t = stTokens()
    val ledColor = when {
        degraded -> t.amber
        connected -> t.emerald
        else -> t.txRed
    }
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, t.border, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StPulsingDot(color = ledColor, animate = degraded)
        Text(
            state.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = if (connected) t.emerald else t.greyText,
        )
    }
}

/** Small dot with optional soft pulse (used for degraded/reconnecting states). */
@Composable
fun StPulsingDot(color: Color, animate: Boolean = false, size: Dp = 8.dp) {
    if (!animate) {
        Box(Modifier.size(size).clip(CircleShape).background(color))
        return
    }
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(StMotion.SlowMs), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = alpha)))
}

/** Circle avatar with deterministic member color and initial(s). */
@Composable
fun StAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 40.dp, speaking: Boolean = false) {
    val t = stTokens()
    val palette = listOf(t.emerald, t.lime, t.teal, t.amber, Color(0xFF7C9EFF), Color(0xFFE38BD0))
    val hue = remember(name) { name.hashCode().let { if (it == Int.MIN_VALUE) 0 else it } .let { kotlin.math.abs(it) % palette.size } }
    val color = palette[hue]
    val scale by animateFloatAsState(if (speaking) 1.12f else 1f, tween(StMotion.FastMs), label = "avatarScale")
    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.22f))
            .border(if (speaking) 2.dp else 1.dp, if (speaking) color else t.border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(2).uppercase().ifBlank { "?" },
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/** Monospace chip for meta info (member count, epoch, codec…). */
@Composable
fun StChip(text: String, modifier: Modifier = Modifier, accent: Color? = null) {
    val t = stTokens()
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, t.border, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        accent?.let { Box(Modifier.size(6.dp).clip(CircleShape).background(it)) }
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = t.greyText)
    }
}

// ---------------------------------------------------------------------------
// Switch / slider
// ---------------------------------------------------------------------------

@Composable
fun StSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            uncheckedThumbColor = stTokens().greyText,
            uncheckedBorderColor = stTokens().border,
        ),
    )
}

// ---------------------------------------------------------------------------
// Waveform (live audio activity)
// ---------------------------------------------------------------------------

/**
 * Lightweight waveform: N bars driven by [level] (0..1) plus a gentle idle
 * shimmer when idle. Canvas-only — no bitmap allocation, cheap recomposition.
 */
@Composable
fun StWaveform(
    level: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 24,
    color: Color? = null,
) {
    val t = stTokens()
    val barColor = color ?: t.emerald
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "phase",
    )
    val lv = if (level.isNaN()) 0f else level.coerceIn(0f, 1f)
    Canvas(modifier = modifier.height(40.dp)) {
        val w = size.width / barCount
        val mid = size.height / 2
        for (i in 0 until barCount) {
            val p = i / barCount.toFloat()
            val wave = if (active) {
                val a = (Math.sin((phase + p * 6.0)).toFloat() + 1f) / 2f
                0.15f + a * 0.85f * (0.35f + lv * 0.65f)
            } else {
                0.10f + ((Math.sin(phase + p * 4.0).toFloat() + 1f) / 2f) * 0.08f
            }
            val h = size.height * wave.coerceIn(0.08f, 1f)
            drawRoundRect(
                color = barColor.copy(alpha = if (active) 0.95f else 0.35f),
                topLeft = androidx.compose.ui.geometry.Offset(i * w + w * 0.2f, mid - h / 2),
                size = androidx.compose.ui.geometry.Size(w * 0.45f, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.2f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Loading / empty / error states
// ---------------------------------------------------------------------------

/** Shimmering skeleton block. */
@Composable
fun StSkeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(StMotion.SlowMs), RepeatMode.Reverse),
        label = "shimmer",
    )
    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)),
    )
}

/** Custom state block: loading spinner, empty or error layout + optional action. */
@Composable
fun StateBlock(
    title: String,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    loading: Boolean = false,
) {
    val t = stTokens()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) {
            val transition = rememberInfiniteTransition(label = "loader")
            val rot by transition.animateFloat(
                initialValue = 0f, targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                label = "rot",
            )
            Canvas(Modifier.size(34.dp)) {
                drawArc(
                    color = t.emerald,
                    startAngle = rot,
                    sweepAngle = 100f,
                    useCenter = false,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        Spacer(Modifier.height(if (loading) 12.dp else 0.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        detail?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = t.greyText, textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            StButton(actionLabel, onAction, compact = true)
        }
    }
}

// ---------------------------------------------------------------------------
// Banners
// ---------------------------------------------------------------------------

@Composable
fun OfflineBanner(visible: Boolean, text: String = "OFFLINE — transmit unavailable") {
    androidx.compose.animation.AnimatedVisibility(visible) {
        val t = stTokens()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(t.txRed.copy(alpha = 0.14f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = t.txRed)
        }
    }
}

// ---------------------------------------------------------------------------
// Push To Talk (Command Center primary control)
// ---------------------------------------------------------------------------

/**
 * The PTT control: large circular button with animated state rings, waveform
 * and live label. States: idle / listening (queue) / transmitting / connecting
 * / error. Canvas rings — no blurs, low CPU, safe on weak devices.
 */
@Composable
fun PttButton(
    isSpeaking: Boolean,
    isQueueing: Boolean,
    queuePosition: Int?,
    connecting: Boolean,
    error: Boolean,
    enabled: Boolean,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 148.dp,
) {
    val t = stTokens()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val accent = when {
        error -> t.txRed
        isSpeaking -> t.txRed
        isQueueing -> t.amber
        connecting -> t.teal
        else -> t.lime
    }
    val targetScale = when {
        pressed && enabled -> 0.94f
        else -> 1f
    }
    val scale by animateFloatAsState(targetScale, tween(StMotion.FastMs), label = "pttScale")

    val ringTransition = rememberInfiniteTransition(label = "ring")
    val ringAlpha by ringTransition.animateFloat(
        initialValue = 0.7f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "ringAlpha",
    )
    val ringRadius by ringTransition.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "ringRadius",
    )

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            // Animated halo rings while transmitting / queued / connecting
            if (isSpeaking || isQueueing || connecting) {
                Canvas(Modifier.size(size * 1.55f)) {
                    drawCircle(
                        color = accent.copy(alpha = ringAlpha * 0.55f),
                        radius = this.size.minDimension / 2 * ringRadius,
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            }
            Canvas(Modifier.size(size)) {
                drawCircle(color = accent.copy(alpha = 0.10f))
                drawCircle(
                    color = accent,
                    radius = this.size.minDimension / 2 - 2.dp.toPx(),
                    style = Stroke(width = if (isSpeaking || pressed) 4.dp.toPx() else 2.5.dp.toPx()),
                )
            }
            Box(
                modifier = Modifier
                    .size(size * 0.8f)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, t.border, CircleShape)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        onClick = { /* press semantics handled below */ },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StWaveform(
                        level = if (isSpeaking) 0.9f else if (isQueueing) 0.4f else 0.1f,
                        active = isSpeaking || isQueueing,
                        barCount = 10,
                        color = accent,
                        modifier = Modifier.width(size * 0.55f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = when {
                            error -> "ERROR"
                            isSpeaking -> "ON AIR"
                            isQueueing -> "QUEUED ${queuePosition ?: "?"}"
                            connecting -> "CONNECTING"
                            enabled -> "HOLD"
                            else -> "OFFLINE"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (enabled || error) accent else t.greyText,
                    )
                }
            }
            // Press/release layer covering the whole control
            Box(
                Modifier
                    .size(size * 1.55f)
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            onPttDown()
                            waitForUpOrCancellation()
                            onPttUp()
                        }
                    },
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = when {
                error -> "TRANSMIT FAILED — RETRY"
                isSpeaking -> "RELEASE TO END TRANSMISSION"
                isQueueing -> "IN QUEUE — POSITION ${queuePosition ?: "?"}"
                connecting -> "ESTABLISHING SECURE CHANNEL..."
                enabled -> "HOLD TO TALK"
                else -> "CONNECT TO TRANSMIT"
            },
            style = MaterialTheme.typography.labelSmall,
            color = t.greyText,
            textAlign = TextAlign.Center,
        )
    }
}

/** Legacy bar-style PTT (kept for compatibility with smaller layouts). */
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
    val t = stTokens()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PttButton(
            isSpeaking = isSpeaking,
            isQueueing = isQueueing,
            queuePosition = queuePosition,
            connecting = false,
            error = false,
            enabled = enabled,
            onPttDown = onPttDown,
            onPttUp = onPttUp,
        )
    }
}

// ---------------------------------------------------------------------------
// Top bar / section headers
// ---------------------------------------------------------------------------

/** Custom top bar: title + optional trailing content, no default elevation. */
@Composable
fun StTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val t = stTokens()
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, t.border, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** Monospace section label with accent tick. */
@Composable
fun StSectionLabel(text: String, modifier: Modifier = Modifier) {
    val t = stTokens()
    Row(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(4.dp, 14.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = t.greyText)
    }
}
