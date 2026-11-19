package dev.bootloaderstudio.app.fastboot

import java.io.Closeable

/**
 * Opaque handle for a USB device that exposes a fastboot interface. The
 * production implementation wraps [android.hardware.usb.UsbDevice]; unit tests
 * can use a simple fake.
 */
interface FastbootDeviceHandle {
    val id: Int
}

/**
 * Minimal fastboot transport used by the unlock flow.
 */
interface FastbootTransport : Closeable {
    fun getVar(name: String, timeoutMs: Int = 5_000): String?

    fun oemGetToken(timeoutMs: Int = 5_000): String?

    fun download(data: ByteArray, timeoutMs: Int = 10_000): FastbootResponse

    fun oemUnlock(timeoutMs: Int = 60_000): FastbootResponse
}

/**
 * USB host + permission + fastboot transport contract. Keeping the Android USB
 * classes behind this boundary makes the detection and permission state machine
 * unit-testable.
 */
interface FastbootUsbService {
    fun hasUsbHost(): Boolean

    fun findFastbootDevice(): FastbootDeviceHandle?

    fun hasPermission(device: FastbootDeviceHandle): Boolean

    suspend fun requestPermission(device: FastbootDeviceHandle): Boolean

    fun openDevice(device: FastbootDeviceHandle): FastbootTransport?
}
