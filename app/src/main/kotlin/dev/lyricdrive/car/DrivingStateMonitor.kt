package dev.lyricdrive.car

import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarInfo
import androidx.car.app.hardware.info.Speed
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.lyricdrive.BuildConfig
import kotlin.math.abs

/**
 * Tracks whether the vehicle is moving, so the car UI can switch to its glanceable layout.
 * When speed is unavailable we assume the car is moving (fail safe); debug builds assume parked
 * instead so the full UI can be exercised on the Desktop Head Unit.
 */
class DrivingStateMonitor(private val carContext: CarContext) : DefaultLifecycleObserver {
    private val unknownMeansMoving = !BuildConfig.DEBUG

    var isMoving: Boolean = unknownMeansMoving
        private set

    private var carInfo: CarInfo? = null

    private val speedListener = OnCarDataAvailableListener<Speed> { speed ->
        val value = speed.rawSpeedMetersPerSecond.takeIf { it.status == CarValue.STATUS_SUCCESS }?.value
        isMoving = value?.let { abs(it) > MOVING_THRESHOLD_MPS } ?: unknownMeansMoving
    }

    override fun onCreate(owner: LifecycleOwner) {
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_3) return
        try {
            val info = carContext.getCarService(CarHardwareManager::class.java).carInfo
            info.addSpeedListener(ContextCompat.getMainExecutor(carContext), speedListener)
            carInfo = info
        } catch (e: SecurityException) {
            // CAR_SPEED not granted by the host: keep the fail-safe default.
        } catch (e: IllegalStateException) {
            // No hardware manager on this host.
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        carInfo?.removeSpeedListener(speedListener)
        carInfo = null
    }

    private companion object {
        const val MOVING_THRESHOLD_MPS = 0.5f
    }
}
