package com.securetalk.feature.onboarding

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securetalk.core.database.data.SettingsRepository
import com.securetalk.core.design.StButton
import com.securetalk.core.design.StButtonStyle
import com.securetalk.core.design.StCard
import com.securetalk.core.design.StColors
import com.securetalk.core.design.stTokens
import com.securetalk.core.permissions.RequestPermissions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val onboarded = settings.settings

    fun completeOnboarding() {
        viewModelScope.launch { settings.setOnboarded(true) }
    }

    private val _step = MutableStateFlow(0)
    val step: StateFlow<Int> = _step.asStateFlow()

    fun nextStep() {
        _step.value = (_step.value + 1).coerceAtMost(2)
    }
}

/** Screen 1/3: Welcome — brand hero, security pitch, single primary action. */
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val t = stTokens()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            // Brand mark: concentric secure rings
            val transition = rememberInfiniteTransition(label = "brand")
            val rot by transition.animateFloat(
                initialValue = 0f, targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(4000)),
                label = "rot",
            )
            Canvas(Modifier.size(96.dp)) {
                drawCircle(color = t.emerald.copy(alpha = 0.12f))
                drawCircle(
                    color = t.emerald,
                    radius = this.size.minDimension / 2 - 10.dp.toPx(),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    color = t.lime,
                    startAngle = rot,
                    sweepAngle = 70f,
                    useCenter = false,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text("WakiTaki", style = MaterialTheme.typography.displaySmall)
            Text(
                "Encrypted push-to-talk for teams",
                style = MaterialTheme.typography.bodyLarge,
                color = t.greyText,
            )
        }
        Spacer(Modifier.height(28.dp))
        StCard(Modifier.fillMaxWidth(), accent = t.emerald) {
            Text("END-TO-END ENCRYPTED VOICE", style = MaterialTheme.typography.labelLarge, color = t.emerald)
            Spacer(Modifier.height(6.dp))
            Text(
                "Sender-key group encryption with pairwise key distribution. " +
                    "Relays and hosts relay opaque packets only — they cannot decrypt your audio.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(12.dp))
        StCard(Modifier.fillMaxWidth(), accent = t.teal) {
            Text("TWO MODES", style = MaterialTheme.typography.labelLarge, color = t.teal)
            Spacer(Modifier.height(6.dp))
            Text(
                "Internet: connect to a SecureTalk server. Offline: one device acts as a " +
                    "local host on Wi-Fi/hotspot and relays encrypted packets. Switching modes " +
                    "always re-establishes security sessions.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(32.dp))
        StButton("GET STARTED", onGetStarted, Modifier.fillMaxWidth())
    }
}

/** Screen 2/3: Permissions — microphone, notifications, foreground service. */
@Composable
fun PermissionsScreen(
    onGranted: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val t = stTokens()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Permissions", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        StCard(accent = t.amber) {
            Text("MICROPHONE", style = MaterialTheme.typography.labelLarge, color = t.amber)
            Text(
                "Push-to-talk capture (VOICE_COMMUNICATION audio path with echo cancellation and noise suppression).",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(10.dp))
        StCard(accent = t.emerald) {
            Text("NOTIFICATIONS", style = MaterialTheme.typography.labelLarge, color = t.emerald)
            Text(
                "The PTT session and local host run as foreground services so calls survive backgrounding.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(28.dp))
        RequestPermissions(
            permissions = com.securetalk.core.permissions.StPermissions.requiredList,
            onAllGranted = onGranted,
            onDenied = onGranted, // allow proceeding; PTT will re-request
        )
        StButton("ALLOW AND CONTINUE", onGranted, Modifier.fillMaxWidth())
    }
}

/** Screen 3/3: Mode selection — internet vs local. */
@Composable
fun ModeSelectScreen(
    onPickInternet: () -> Unit,
    onPickLocal: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val t = stTokens()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Choose mode", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))
        StCard(Modifier.fillMaxWidth(), accent = t.lime, onClick = onPickInternet) {
            Text("INTERNET MODE", style = MaterialTheme.typography.labelLarge, color = t.lime)
            Spacer(Modifier.height(6.dp))
            Text(
                "Connect to a SecureTalk server over HTTPS/WSS. Server coordinates floors and relays encrypted media.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(12.dp))
        StCard(Modifier.fillMaxWidth(), accent = t.teal, onClick = onPickLocal) {
            Text("OFFLINE MODE", style = MaterialTheme.typography.labelLarge, color = t.teal)
            Spacer(Modifier.height(6.dp))
            Text(
                "Same Wi-Fi or hotspot. One device hosts; peers discover it via NSD or QR. No server, no internet.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(24.dp))
        viewModel // touch to keep VM alive for onboarding completion
        StButton(
            "DECIDE LATER",
            { viewModel.completeOnboarding() },
            Modifier.fillMaxWidth(),
            style = StButtonStyle.Ghost,
        )
    }
}
