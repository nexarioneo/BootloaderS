package dev.bootloaderstudio.app.ui.unlock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.bootloaderstudio.app.R
import dev.bootloaderstudio.app.ui.components.AppScaffold
import dev.bootloaderstudio.app.ui.components.WarningCard
import dev.bootloaderstudio.app.ubl.UnlockPhase
import dev.bootloaderstudio.app.ubl.UblUiState
import kotlinx.coroutines.delay

@Composable
fun UnlockScreen(
    uiState: UblUiState,
    onFinished: () -> Unit
) {
    val view = LocalView.current

    BackHandler {
        // The unlock sequence must not be interrupted mid-flight.
    }

    DisposableEffect(Unit) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }

    LaunchedEffect(uiState.unlockPhase) {
        if (uiState.unlockPhase == UnlockPhase.Success ||
            uiState.unlockPhase == UnlockPhase.Error
        ) {
            delay(900)
            onFinished()
        }
    }

    val active = uiState.unlockPhase != UnlockPhase.Success &&
        uiState.unlockPhase != UnlockPhase.Error

    AppScaffold(
        title = stringResource(R.string.unlock_title),
        onBack = null
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (active) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Text(
                text = when (uiState.unlockPhase) {
                    UnlockPhase.Idle -> stringResource(R.string.unlock_phase_idle)
                    UnlockPhase.RequestingApproval -> stringResource(R.string.unlock_phase_requesting)
                    UnlockPhase.Staging -> stringResource(R.string.unlock_phase_staging)
                    UnlockPhase.Unlocking -> stringResource(R.string.unlock_phase_unlocking)
                    UnlockPhase.Success -> stringResource(R.string.unlock_phase_success)
                    UnlockPhase.Error -> stringResource(R.string.unlock_phase_error)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = uiState.unlockMessage
                    ?: uiState.unlockError
                    ?: stringResource(R.string.unlock_default_instruction),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            StepRow(
                number = 1,
                icon = { Icon(painterResource(R.drawable.ic_cloud), contentDescription = null) },
                title = stringResource(R.string.unlock_step_1_title),
                description = stringResource(R.string.unlock_step_1_desc),
                state = stepState(uiState.unlockPhase, 1)
            )
            StepRow(
                number = 2,
                icon = { Icon(painterResource(R.drawable.ic_usb), contentDescription = null) },
                title = stringResource(R.string.unlock_step_2_title),
                description = stringResource(R.string.unlock_step_2_desc),
                state = stepState(uiState.unlockPhase, 2)
            )
            StepRow(
                number = 3,
                icon = { Icon(painterResource(R.drawable.ic_lock_open), contentDescription = null) },
                title = stringResource(R.string.unlock_step_3_title),
                description = stringResource(R.string.unlock_step_3_desc),
                state = stepState(uiState.unlockPhase, 3)
            )

            WarningCard(
                text = stringResource(R.string.unlock_warning_disconnect)
            )

            if (uiState.unlockPhase == UnlockPhase.Unlocking) {
                WarningCard(
                    text = stringResource(R.string.unlock_confirm_on_device)
                )
            }

            if (uiState.unlockPhase == UnlockPhase.Error) {
                uiState.unlockError?.let { message ->
                    WarningCard(text = message)
                }
                if (uiState.unlockErrorCode != null) {
                    Text(
                        text = stringResource(R.string.unlock_error_code_format, uiState.unlockErrorCode),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    text = stringResource(R.string.unlock_error_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private enum class StepState { Pending, Active, Done }

private fun stepState(phase: UnlockPhase, step: Int): StepState {
    return when {
        phase == UnlockPhase.Success -> StepState.Done
        step == 1 -> when (phase) {
            UnlockPhase.Idle,
            UnlockPhase.RequestingApproval -> StepState.Active
            UnlockPhase.Staging,
            UnlockPhase.Unlocking,
            UnlockPhase.Error -> if (phase == UnlockPhase.Error) StepState.Pending else StepState.Done
            else -> StepState.Done
        }
        step == 2 -> when (phase) {
            UnlockPhase.Staging -> StepState.Active
            UnlockPhase.Unlocking -> StepState.Done
            UnlockPhase.Error -> StepState.Pending
            else -> StepState.Pending
        }
        else -> when (phase) {
            UnlockPhase.Unlocking -> StepState.Active
            UnlockPhase.Error -> StepState.Pending
            else -> StepState.Pending
        }
    }
}

@Composable
private fun StepRow(
    number: Int,
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    state: StepState
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                StepState.Active -> MaterialTheme.colorScheme.primaryContainer
                StepState.Done -> MaterialTheme.colorScheme.secondaryContainer
                StepState.Pending -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = when (state) {
                    StepState.Active -> MaterialTheme.colorScheme.primary
                    StepState.Done -> MaterialTheme.colorScheme.secondary
                    StepState.Pending -> MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Box(
                    modifier = Modifier.size(34.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when (state) {
                        StepState.Active -> CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        StepState.Done -> Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSecondary
                        )
                        StepState.Pending -> Text(
                            text = number.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
