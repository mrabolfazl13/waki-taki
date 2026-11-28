package com.securetalk.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.securetalk.app.session.SessionController
import com.securetalk.app.service.PttSessionService
import com.securetalk.feature.authentication.DeviceVerificationScreen
import com.securetalk.feature.authentication.LoginScreen
import com.securetalk.feature.authentication.RegisterScreen
import com.securetalk.feature.channel.ActiveChannelScreen
import com.securetalk.feature.channel.ChannelSettingsScreen
import com.securetalk.feature.channels.ChannelInviteScreen
import com.securetalk.feature.channels.CreateChannelScreen
import com.securetalk.feature.channels.JoinChannelScreen
import com.securetalk.feature.devices.DevicesScreen
import com.securetalk.feature.diagnostics.DiagnosticsScreen
import com.securetalk.feature.home.HomeScreen
import com.securetalk.feature.local_network.HostSetupScreen
import com.securetalk.feature.local_network.HotspotGuideScreen
import com.securetalk.feature.local_network.LocalHostService
import com.securetalk.feature.local_network.LocalJoinScreen
import com.securetalk.feature.members.MembersScreen
import com.securetalk.feature.onboarding.ModeSelectScreen
import com.securetalk.feature.onboarding.PermissionsScreen
import com.securetalk.feature.onboarding.WelcomeScreen
import com.securetalk.feature.security.KeyVerificationScreen
import com.securetalk.feature.security.SecurityOverviewScreen
import com.securetalk.feature.settings.AudioSettingsScreen
import com.securetalk.feature.settings.SettingsScreen

object Routes {
    const val WELCOME = "onboarding/welcome"
    const val PERMISSIONS = "onboarding/permissions"
    const val MODE_SELECT = "onboarding/mode"
    const val LOGIN = "auth/login"
    const val REGISTER = "auth/register"
    const val DEVICE_VERIFY = "auth/device"
    const val HOME = "home"
    const val CREATE_CHANNEL = "channels/create"
    const val JOIN_CHANNEL = "channels/join"
    const val CHANNEL_INVITE = "channels/{channelId}/invite"
    const val ACTIVE_CHANNEL = "channel/{channelId}?name={name}"
    const val CHANNEL_SETTINGS = "channel/{channelId}/settings"
    const val MEMBERS = "channel/{channelId}/members"
    const val HOST_SETUP = "local/host"
    const val HOTSPOT_GUIDE = "local/hotspot"
    const val LOCAL_JOIN = "local/join"
    const val SECURITY = "security"
    const val KEY_VERIFY = "security/verify?fp={fp}"
    const val SETTINGS = "settings"
    const val AUDIO_SETTINGS = "settings/audio"
    const val DEVICES = "devices"
    const val DIAGNOSTICS = "diagnostics"
}

/**
 * Navigation covering the full screen set:
 * onboarding(3) auth(3) home(1) channels(3) channel(2) members(1)
 * local_network(3) security(2) settings(2) devices(1) diagnostics(1) = 22 screens.
 */
@Composable
fun SecureTalkApp(
    navController: NavHostController = rememberNavController(),
    startController: SessionController? = null,
) {
    val context = LocalContext.current

    NavHost(
        navController = navController,
        startDestination = Routes.WELCOME,
        enterTransition = {
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                androidx.compose.animation.slideInHorizontally(
                    androidx.compose.animation.core.tween(220),
                ) { it / 12 }
        },
        exitTransition = {
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))
        },
        popEnterTransition = {
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220))
        },
        popExitTransition = {
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) +
                androidx.compose.animation.slideOutHorizontally(
                    androidx.compose.animation.core.tween(220),
                ) { it / 12 }
        },
    ) {

        // ---- onboarding (3) ------------------------------------------------------------
        composable(Routes.WELCOME) {
            WelcomeScreen(onGetStarted = { navController.navigate(Routes.PERMISSIONS) })
        }
        composable(Routes.PERMISSIONS) {
            PermissionsScreen(onGranted = { navController.navigate(Routes.MODE_SELECT) })
        }
        composable(Routes.MODE_SELECT) {
            ModeSelectScreen(
                onPickInternet = { navController.navigate(Routes.LOGIN) },
                onPickLocal = { navController.navigate(Routes.HOST_SETUP) },
            )
        }

        // ---- authentication (3) ----------------------------------------------------------
        composable(Routes.LOGIN) {
            LoginScreen(
                onSuccess = { navController.navigate(Routes.DEVICE_VERIFY) },
                onGoRegister = { navController.navigate(Routes.REGISTER) },
            )
        }
        composable(Routes.REGISTER) {
            RegisterScreen(
                onSuccess = { navController.navigate(Routes.DEVICE_VERIFY) },
                onGoLogin = { navController.navigate(Routes.LOGIN) },
            )
        }
        composable(Routes.DEVICE_VERIFY) {
            DeviceVerificationScreen(onDone = { navController.navigate(Routes.HOME) { popUpTo(0) } })
        }

        // ---- home (1) ---------------------------------------------------------------------
        composable(Routes.HOME) {
            HomeScreen(
                onOpenChannel = { id, name ->
                    navController.navigate("channel/$id?name=" + android.net.Uri.encode(name))
                },
                onGoChannels = { navController.navigate(Routes.CREATE_CHANNEL) },
                onGoLocalNetwork = { navController.navigate(Routes.HOST_SETUP) },
                onGoSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        // ---- channels (3) -------------------------------------------------------------------
        composable(Routes.CREATE_CHANNEL) {
            CreateChannelScreen(
                onCreated = { id -> navController.navigate(Routes.CHANNEL_INVITE.replace("{channelId}", id)) },
            )
        }
        composable(Routes.JOIN_CHANNEL) {
            JoinChannelScreen(onJoined = { id -> navController.navigate(Routes.HOME) })
        }
        composable(Routes.CHANNEL_INVITE) { entry ->
            val channelId = entry.arguments?.getString("channelId") ?: ""
            ChannelInviteScreen(
                channelId = channelId,
                onDone = { navController.navigate(Routes.HOME) },
            )
        }

        // ---- channel (2) + members (1) --------------------------------------------------------
        composable(
            Routes.ACTIVE_CHANNEL,
            arguments = listOf(
                navArgument("channelId") { defaultValue = "" },
                navArgument("name") { defaultValue = "Channel" },
            ),
        ) { entry ->
            val channelId = entry.arguments?.getString("channelId") ?: ""
            val name = entry.arguments?.getString("name") ?: "Channel"
            ActiveChannelScreen(
                channelId = channelId,
                channelName = name,
                onLeave = {
                    PttSessionService.stop(context)
                    navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) }
                },
                onOpenSettings = { id ->
                    navController.navigate(Routes.CHANNEL_SETTINGS.replace("{channelId}", id))
                },
                onStartPttService = { runCatching { PttSessionService.start(context) } },
                onStopPttService = { runCatching { PttSessionService.stop(context) } },
            )
        }
        composable(Routes.CHANNEL_SETTINGS) { entry ->
            val channelId = entry.arguments?.getString("channelId") ?: ""
            ChannelSettingsScreen(
                channelId = channelId,
                onInvite = { id ->
                    navController.navigate(Routes.CHANNEL_INVITE.replace("{channelId}", id))
                },
            )
        }
        composable(Routes.MEMBERS) { entry ->
            val channelId = entry.arguments?.getString("channelId") ?: ""
            MembersScreen(
                channelId = channelId,
                onInvite = { id ->
                    navController.navigate(Routes.CHANNEL_INVITE.replace("{channelId}", id))
                },
            )
        }

        // ---- local network (3) ------------------------------------------------------------------
        composable(Routes.HOST_SETUP) {
            HostSetupScreen(
                onStartService = { LocalHostService.start(context) },
                onStopService = { LocalHostService.stop(context) },
                onGoHotspot = { navController.navigate(Routes.HOTSPOT_GUIDE) },
                onGoJoin = { navController.navigate(Routes.LOCAL_JOIN) },
            )
        }
        composable(Routes.HOTSPOT_GUIDE) {
            HotspotGuideScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.LOCAL_JOIN) {
            LocalJoinScreen(onJoined = { navController.navigate(Routes.HOME) })
        }

        // ---- security (2) -------------------------------------------------------------------------
        composable(Routes.SECURITY) {
            SecurityOverviewScreen(
                onVerifyKeys = { navController.navigate("security/verify?fp=") },
            )
        }
        composable(
            Routes.KEY_VERIFY,
            arguments = listOf(navArgument("fp") { defaultValue = "" }),
        ) { entry ->
            KeyVerificationScreen(remoteFingerprint = entry.arguments?.getString("fp") ?: "")
        }

        // ---- settings (2) -----------------------------------------------------------------------------
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onLogout = { navController.navigate(Routes.LOGIN) { popUpTo(0) } },
                onGoDevices = { navController.navigate(Routes.DEVICES) },
                onGoDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                onGoSecurity = { navController.navigate(Routes.SECURITY) },
            )
        }
        composable(Routes.AUDIO_SETTINGS) {
            AudioSettingsScreen()
        }

        // ---- devices (1) + diagnostics (1) ---------------------------------------------------------------
        composable(Routes.DEVICES) { DevicesScreen() }
        composable(Routes.DIAGNOSTICS) { DiagnosticsScreen() }
    }
}
