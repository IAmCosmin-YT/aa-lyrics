package dev.lyricdrive.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.car.app.connection.CarConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where audio is currently going. Each route lags differently, so each keeps its own delay. */
enum class OutputRoute { ANDROID_AUTO, BLUETOOTH, WIRED, SPEAKER }

class OutputRouteMonitor(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var projecting = false

    private val _route = MutableStateFlow(OutputRoute.SPEAKER)
    val route: StateFlow<OutputRoute> = _route.asStateFlow()

    init {
        // Android Auto (wired or wireless) is a "projection" connection.
        CarConnection(context).type.observeForever { type ->
            projecting = type == CarConnection.CONNECTION_TYPE_PROJECTION
            update()
        }
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = update()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = update()
        }, handler)
        update()
    }

    private fun update() {
        _route.value = if (projecting) OutputRoute.ANDROID_AUTO else classify(mediaDevices())
    }

    private fun mediaDevices(): List<AudioDeviceInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The devices media is actually routed to right now.
            audioManager.getAudioDevicesForAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }

    private fun classify(devices: List<AudioDeviceInfo>): OutputRoute {
        val types = devices.map { it.type }.toSet()
        return when {
            types.any { it in BLUETOOTH } -> OutputRoute.BLUETOOTH
            types.any { it in WIRED } -> OutputRoute.WIRED
            else -> OutputRoute.SPEAKER
        }
    }

    private companion object {
        val BLUETOOTH = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(AudioDeviceInfo.TYPE_BLE_BROADCAST)
        }
        val WIRED = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_AUX_LINE,
        )
    }
}
