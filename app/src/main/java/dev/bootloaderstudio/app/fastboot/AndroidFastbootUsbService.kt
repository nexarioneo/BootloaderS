package dev.bootloaderstudio.app.fastboot

import android.content.Context
import android.hardware.usb.UsbDevice

/**
 * Production [FastbootUsbService] that keeps Android USB types behind the
 * service boundary. Unit tests use a fake implementation instead.
 */
class AndroidFastbootUsbService(
    context: Context
) : FastbootUsbService {

    private val manager = UsbFastbootManager(context.applicationContext)
    private val permissionBroker = UsbPermissionBroker(context.applicationContext)

    override fun hasUsbHost(): Boolean = manager.hasUsbHost()

    override fun findFastbootDevice(): FastbootDeviceHandle? {
        return manager.findFastbootDevice()?.let(::AndroidFastbootDevice)
    }

    override fun hasPermission(device: FastbootDeviceHandle): Boolean {
        val androidDevice = device as? AndroidFastbootDevice ?: return false
        return manager.hasPermission(androidDevice.device)
    }

    override suspend fun requestPermission(device: FastbootDeviceHandle): Boolean {
        val androidDevice = device as? AndroidFastbootDevice ?: return false
        return permissionBroker.requestPermission(androidDevice.device)
    }

    override fun openDevice(device: FastbootDeviceHandle): FastbootTransport? {
        val androidDevice = device as? AndroidFastbootDevice ?: return null
        return manager.openDevice(androidDevice.device)
    }

    private class AndroidFastbootDevice(
        val device: UsbDevice
    ) : FastbootDeviceHandle {
        override val id: Int = device.deviceId
    }
}
