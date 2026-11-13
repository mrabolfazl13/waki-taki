package com.securetalk.core.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/** Runtime permissions used by SecureTalk. */
object StPermissions {
    val RECORD_AUDIO = Manifest.permission.RECORD_AUDIO
    val POST_NOTIFICATIONS: String? =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null

    val requiredList: List<String> = buildList {
        add(RECORD_AUDIO)
        POST_NOTIFICATIONS?.let { add(it) }
    }
}

fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Compose helper: requests [permissions] in order and reports grant status.
 * Uses only stable ActivityResult APIs (no accompanist).
 */
@Composable
fun RequestPermissions(
    permissions: List<String>,
    onAllGranted: () -> Unit = {},
    onDenied: () -> Unit = {},
) {
    var granted by remember { mutableStateOf(false) }
    var index by remember { mutableStateOf(0) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            if (index + 1 < permissions.size) index += 1 else {
                granted = true
                onAllGranted()
            }
        } else {
            onDenied()
        }
    }

    LaunchedEffect(Unit) {
        if (permissions.isEmpty()) {
            granted = true
            onAllGranted()
        }
    }

    LaunchedEffect(index) {
        val current = permissions.getOrNull(index)
        when {
            current == null -> if (!granted) {
                granted = true
                onAllGranted()
            }
            else -> launcher.launch(current)
        }
    }
}
