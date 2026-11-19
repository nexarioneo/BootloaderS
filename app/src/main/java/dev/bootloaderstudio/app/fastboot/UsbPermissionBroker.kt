package dev.bootloaderstudio.app.fastboot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class UsbPermissionBroker(
    private val context: Context
) {

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    suspend fun requestPermission(device: UsbDevice): Boolean = withContext(Dispatchers.Main) {
        if (usbManager.hasPermission(device)) return@withContext true

        val deferred = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val granted = intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED,
                    false
                )
                if (!deferred.isCompleted) {
                    deferred.complete(granted)
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        try {
            val intent = Intent(ACTION_USB_PERMISSION)
                .setPackage(context.packageName)
                .putExtra(UsbManager.EXTRA_DEVICE, device)

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                device.deviceId,
                intent,
                flags
            )
            usbManager.requestPermission(device, pendingIntent)
            withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { deferred.await() }
                ?: usbManager.hasPermission(device)
        } catch (_: Exception) {
            false
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered.
            }
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION =
            "dev.bootloaderstudio.app.USB_PERMISSION"
        private const val PERMISSION_TIMEOUT_MS = 60_000L
    }
}
