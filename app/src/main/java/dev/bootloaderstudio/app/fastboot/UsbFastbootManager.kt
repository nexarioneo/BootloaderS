package dev.bootloaderstudio.app.fastboot

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

class UsbFastbootManager(
    private val context: Context
) {

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun hasUsbHost(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
    }

    fun devices(): List<UsbDevice> {
        return usbManager.deviceList.values.toList()
    }

    fun findFastbootDevice(): UsbDevice? {
        return devices().firstOrNull { isFastbootDevice(it) }
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    fun isFastbootDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_HUB) return false

        for (index in 0 until device.interfaceCount) {
            val iface = device.getInterface(index)
            if (iface.isFastbootInterface() && findEndpoints(iface) != null) {
                return true
            }
        }
        return false
    }

    fun openDevice(device: UsbDevice): FastbootConnection? {
        val connection = usbManager.openDevice(device) ?: return null

        val selected = selectInterface(device) ?: run {
            connection.close()
            return null
        }

        if (!connection.claimInterface(selected.iface, true)) {
            connection.close()
            return null
        }

        return FastbootConnection(
            connection = connection,
            iface = selected.iface,
            endpointIn = selected.endpointIn,
            endpointOut = selected.endpointOut
        )
    }

    fun describe(device: UsbDevice): String {
        val interfaces = buildList {
            for (index in 0 until device.interfaceCount) {
                val iface = device.getInterface(index)
                add(
                    "if${iface.id}: class=${iface.interfaceClass} " +
                        "sub=${iface.interfaceSubclass} proto=${iface.interfaceProtocol}"
                )
            }
        }
        return buildString {
            append("VID=0x")
            append(device.vendorId.toString(16).padStart(4, '0'))
            append(" PID=0x")
            append(device.productId.toString(16).padStart(4, '0'))
            append("\n")
            append(interfaces.joinToString("\n").ifBlank { "No interfaces" })
            append("\nPermission: ")
            append(if (hasPermission(device)) "granted" else "not granted")
            append("\nFastboot: ")
            append(if (isFastbootDevice(device)) "yes" else "no")
        }
    }

    private fun selectInterface(device: UsbDevice): InterfaceSelection? {
        for (index in 0 until device.interfaceCount) {
            val iface = device.getInterface(index)
            if (!iface.isFastbootInterface()) continue
            val endpoints = findEndpoints(iface) ?: continue
            return InterfaceSelection(iface, endpoints.first, endpoints.second)
        }
        return null
    }

    private fun findEndpoints(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        var endpointIn: UsbEndpoint? = null
        var endpointOut: UsbEndpoint? = null

        for (index in 0 until iface.endpointCount) {
            val endpoint = iface.getEndpoint(index)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            when (endpoint.direction) {
                UsbConstants.USB_DIR_IN -> if (endpointIn == null) endpointIn = endpoint
                UsbConstants.USB_DIR_OUT -> if (endpointOut == null) endpointOut = endpoint
            }
        }

        return if (endpointIn != null && endpointOut != null) {
            endpointIn!! to endpointOut!!
        } else {
            null
        }
    }

    private fun UsbInterface.isFastbootInterface(): Boolean {
        return interfaceClass == 0xFF &&
            interfaceSubclass == 0x42 &&
            interfaceProtocol == 0x03
    }

    private data class InterfaceSelection(
        val iface: UsbInterface,
        val endpointIn: UsbEndpoint,
        val endpointOut: UsbEndpoint
    )
}
