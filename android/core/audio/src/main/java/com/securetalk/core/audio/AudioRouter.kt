package com.securetalk.core.audio

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Audio output routing (speaker / earpiece / wired / Bluetooth) via AudioManager
 * communication-device APIs, with device-change monitoring so route options stay
 * current when headsets are plugged or Bluetooth connects/disconnects.
 */
class AudioRouter(private val audioManager: AudioManager) {

    fun setRoute(route: com.securetalk.core.common.model.AudioRoute) {
        if (Build.VERSION.SDK_INT >= 31) {
            val target = audioManager.availableCommunicationDevices.firstOrNull { matches(it, route) }
            if (target != null) {
                audioManager.setCommunicationDevice(target)
            }
        } else {
            @Suppress("DEPRECATION")
            when (route) {
                com.securetalk.core.common.model.AudioRoute.SPEAKER -> audioManager.isSpeakerphoneOn = true
                com.securetalk.core.common.model.AudioRoute.EARPIECE -> {
                    audioManager.isSpeakerphoneOn = false
                }
                com.securetalk.core.common.model.AudioRoute.WIRED -> audioManager.isSpeakerphoneOn = false
                com.securetalk.core.common.model.AudioRoute.BLUETOOTH -> {
                    @Suppress("DEPRECATION")
                    audioManager.startBluetoothSco()
                }
            }
        }
    }

    fun currentRoute(): com.securetalk.core.common.model.AudioRoute {
        if (Build.VERSION.SDK_INT >= 31) {
            return audioManager.communicationDevice?.let { deviceTypeToRoute(it.type) }
                ?: com.securetalk.core.common.model.AudioRoute.EARPIECE
        }
        return when {
            audioManager.isBluetoothScoOn -> com.securetalk.core.common.model.AudioRoute.BLUETOOTH
            audioManager.isWiredHeadsetOn -> com.securetalk.core.common.model.AudioRoute.WIRED
            audioManager.isSpeakerphoneOn -> com.securetalk.core.common.model.AudioRoute.SPEAKER
            else -> com.securetalk.core.common.model.AudioRoute.EARPIECE
        }
    }

    fun availableRoutes(): List<com.securetalk.core.common.model.AudioRoute> {
        val routes = mutableSetOf(com.securetalk.core.common.model.AudioRoute.SPEAKER, com.securetalk.core.common.model.AudioRoute.EARPIECE)
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET ->
                    routes.add(com.securetalk.core.common.model.AudioRoute.WIRED)
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ->
                    routes.add(com.securetalk.core.common.model.AudioRoute.BLUETOOTH)
            }
        }
        return routes.toList()
    }

    /** Registers a callback that fires on device add/remove; returns the unregister handle. */
    fun registerDeviceChangedListener(onChanged: () -> Unit): () -> Unit {
        val listener = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onChanged()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onChanged()
        }
        audioManager.registerAudioDeviceCallback(listener, null)
        return { audioManager.unregisterAudioDeviceCallback(listener) }
    }

    private fun matches(device: AudioDeviceInfo, route: com.securetalk.core.common.model.AudioRoute): Boolean =
        deviceTypeToRoute(device.type) == route

    private fun deviceTypeToRoute(type: Int): com.securetalk.core.common.model.AudioRoute = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> com.securetalk.core.common.model.AudioRoute.SPEAKER
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET -> com.securetalk.core.common.model.AudioRoute.WIRED
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> com.securetalk.core.common.model.AudioRoute.BLUETOOTH
        else -> com.securetalk.core.common.model.AudioRoute.EARPIECE
    }
}
