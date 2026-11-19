package dev.bootloaderstudio.app.ui.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dev.bootloaderstudio.app.R
import dev.bootloaderstudio.app.ui.components.AppScaffold
import dev.bootloaderstudio.app.ui.components.InfoRow
import dev.bootloaderstudio.app.ui.components.UsbDiagnosticsDialog
import dev.bootloaderstudio.app.ui.components.WarningCard
import dev.bootloaderstudio.app.ui.theme.SuccessGreen
import dev.bootloaderstudio.app.ubl.DeviceState
import dev.bootloaderstudio.app.ubl.UblUiState

@Composable
fun DeviceScreen(
    uiState: UblUiState,
    onStartDetection: () -> Unit,
    onRefresh: () -> Unit,
    onStopDetection: () -> Unit,
    onDeviceAttached: () -> Unit,
    onDeviceDetached: () -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    var showDiagnostics by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val activeDeviceId by rememberUpdatedState(uiState.device?.handleId)
    val attachedCallback by rememberUpdatedState(onDeviceAttached)
    val detachedCallback by rememberUpdatedState(onDeviceDetached)

    LaunchedEffect(Unit) {
        // Always re-read the device when this screen is opened. A cached
        // DeviceInfo from a previous visit may point at a detached handle.
        onRefresh()
    }
    DisposableEffect(context) {
        val appContext = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> attachedCallback()
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val detachedId = IntentCompat.getParcelableExtra(
                            intent,
                            UsbManager.EXTRA_DEVICE,
                            UsbDevice::class.java
                        )?.deviceId
                        val currentId = activeDeviceId
                        if (currentId == null || detachedId == null || detachedId == currentId) {
                            detachedCallback()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        onDispose {
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered.
            }
            onStopDetection()
        }
    }

    AppScaffold(
        title = stringResource(R.string.device_title),
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_usb),
                            contentDescription = null,
                            modifier = Modifier.padding(12.dp).size(32.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Text(
                        text = stringResource(R.string.device_fastboot_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = stringResource(R.string.device_fastboot_card_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            when (uiState.deviceState) {
                DeviceState.Idle -> {
                    Text(
                        text = stringResource(R.string.device_idle_text),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = onStartDetection, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.device_start_detection))
                    }
                }

                DeviceState.Waiting,
                DeviceState.RequestingPermission,
                DeviceState.Connecting -> {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = when (uiState.deviceState) {
                                        DeviceState.Waiting -> stringResource(R.string.device_state_waiting)
                                        DeviceState.RequestingPermission -> stringResource(R.string.device_state_permission)
                                        else -> stringResource(R.string.device_state_reading)
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = uiState.deviceMessage
                                        ?: stringResource(R.string.device_keep_cable),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (uiState.deviceState == DeviceState.Connecting) {
                                    Text(
                                        text = stringResource(R.string.device_slow_read_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    if (uiState.deviceState == DeviceState.Waiting) {
                        TextButton(
                            onClick = onRefresh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Text(
                                stringResource(R.string.device_btn_refresh),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }

                DeviceState.Ready -> {
                    val device = uiState.device
                    if (device != null) {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_phone_android),
                                        contentDescription = null,
                                        tint = SuccessGreen
                                    )
                                    Text(
                                        text = stringResource(R.string.device_detected_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(Modifier.size(4.dp))
                                InfoRow(stringResource(R.string.device_codename), device.product, monospace = true)
                                InfoRow(
                                    stringResource(R.string.device_bootloader),
                                    when (device.unlocked) {
                                        true -> stringResource(R.string.device_already_unlocked)
                                        false -> stringResource(R.string.device_locked)
                                        null -> stringResource(R.string.unknown)
                                    }
                                )
                                InfoRow(stringResource(R.string.device_token), device.tokenMasked, monospace = true)
                            }
                        }

                        TextButton(
                            onClick = onRefresh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Text(
                                stringResource(R.string.device_btn_refresh),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }

                        if (device.unlocked == true) {
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = stringResource(R.string.device_already_unlocked_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        } else {
                            Button(
                                onClick = onContinue,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.device_btn_continue))
                            }
                        }
                    }
                }

                DeviceState.PermissionDenied -> {
                    WarningCard(
                        text = stringResource(R.string.device_permission_denied)
                    )
                    Button(
                        onClick = onStartDetection,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Text(stringResource(R.string.device_btn_retry), modifier = Modifier.padding(start = 8.dp))
                    }
                }

                DeviceState.Error -> {
                    WarningCard(
                        text = uiState.deviceMessage
                            ?: stringResource(R.string.device_error_default)
                    )
                    Button(
                        onClick = onStartDetection,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Text(stringResource(R.string.device_btn_retry), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            TextButton(
                onClick = { showDiagnostics = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(painterResource(R.drawable.ic_bug_report), contentDescription = null)
                Text(stringResource(R.string.device_btn_diagnostics), modifier = Modifier.padding(start = 8.dp))
            }

            Text(
                text = stringResource(R.string.device_hub_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showDiagnostics) {
        UsbDiagnosticsDialog(onDismiss = { showDiagnostics = false })
    }
}
