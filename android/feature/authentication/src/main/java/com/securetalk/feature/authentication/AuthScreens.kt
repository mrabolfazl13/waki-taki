package com.securetalk.feature.authentication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.securetalk.core.design.StateBlock
import com.securetalk.core.design.StColors

@Composable
fun LoginScreen(
    onSuccess: () -> Unit,
    onGoRegister: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    AuthForm(title = "Log in", submitLabel = "LOG IN", needsPassword = true, onGoOther = onGoRegister, onSuccess = onSuccess, viewModel = viewModel, submit = { n, p, _ -> viewModel.login(n, p) })
}

@Composable
fun RegisterScreen(
    onSuccess: () -> Unit,
    onGoLogin: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    AuthForm(title = "Create account", submitLabel = "REGISTER", needsPassword = true, onGoOther = onGoLogin, onSuccess = onSuccess, viewModel = viewModel, submit = viewModel::register)
}

@Composable
private fun AuthForm(
    title: String,
    submitLabel: String,
    needsPassword: Boolean,
    onGoOther: () -> Unit,
    onSuccess: () -> Unit,
    viewModel: AuthViewModel,
    submit: (String, String?, String?) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    val isRegister = needsPassword

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("Display name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        if (needsPassword) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = inviteCode,
                onValueChange = { inviteCode = it.uppercase() },
                label = { Text("Invite code (from an admin)") },
                placeholder = { Text("WT-XXXX-XXXX") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        Spacer(Modifier.height(20.dp))
        when (val state = uiState) {
            is AuthUiState.Error -> {
                Text(state.message, color = StColors.TxRed, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            AuthUiState.Loading -> {
                StateBlock(title = "Contacting server...", loading = true)
            }
            else -> Unit
        }
        Button(
            onClick = {
                submit(displayName, password.takeIf { it.isNotBlank() }, inviteCode.takeIf { it.isNotBlank() })
            },
            enabled = uiState !is AuthUiState.Loading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(submitLabel) }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onGoOther, modifier = Modifier.fillMaxWidth()) {
            Text(if (title == "Log in") "CREATE ACCOUNT INSTEAD" else "LOG IN INSTEAD")
        }
    }

    androidx.compose.runtime.LaunchedEffect(uiState) {
        if (uiState is AuthUiState.Done) onSuccess()
    }
}

/**
 * Device verification screen: shows this device's identity fingerprint so the
 * user can compare it out-of-band with other members.
 */
@Composable
fun DeviceVerificationScreen(
    onDone: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Device verified", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("SIGNED IN AS", style = MaterialTheme.typography.labelLarge)
                Text(session.displayName.ifBlank { "unknown" }, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Text("DEVICE ID", style = MaterialTheme.typography.labelLarge)
                Text(session.deviceId.ifBlank { "pending registration" }, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("CONTINUE") }
    }
}
