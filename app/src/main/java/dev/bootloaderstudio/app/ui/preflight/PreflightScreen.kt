package dev.bootloaderstudio.app.ui.preflight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.bootloaderstudio.app.R
import dev.bootloaderstudio.app.ui.components.AppScaffold
import dev.bootloaderstudio.app.ui.components.InfoRow
import dev.bootloaderstudio.app.ui.components.WarningCard
import dev.bootloaderstudio.app.ubl.UblUiState

@Composable
fun PreflightScreen(
    uiState: UblUiState,
    onPrepare: () -> Unit,
    onBack: () -> Unit,
    onConfirmUnlock: () -> Unit,
    onStartUnlock: () -> Unit
) {
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var requested by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onPrepare()
    }

    val unknownStr = stringResource(R.string.unknown)

    AppScaffold(
        title = stringResource(R.string.preflight_title),
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
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = stringResource(R.string.preflight_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    InfoRow(stringResource(R.string.preflight_account), uiState.account?.userId ?: unknownStr)
                    InfoRow(stringResource(R.string.preflight_region), uiState.account?.region ?: unknownStr)
                    InfoRow(stringResource(R.string.preflight_device), uiState.device?.product ?: unknownStr, monospace = true)
                    InfoRow(stringResource(R.string.preflight_server), uiState.account?.host ?: unknownStr)
                }
            }

            WarningCard(
                text = stringResource(R.string.preflight_warning)
            )

            when {
                uiState.preflightLoading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        Text(
                            text = stringResource(R.string.preflight_checking),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                uiState.preflightError != null -> {
                    WarningCard(text = uiState.preflightError)
                    TextButton(onClick = onPrepare) {
                        Text(stringResource(R.string.preflight_retry_check))
                    }
                }

                uiState.preflightNotice != null -> {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.preflight_notice_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = uiState.preflightNotice,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = when (uiState.preflightCleanOrNot) {
                                    1 -> stringResource(R.string.preflight_data_will_erase)
                                    0 -> stringResource(R.string.preflight_data_no_erase)
                                    else -> stringResource(R.string.preflight_data_unknown)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = confirmed,
                    onCheckedChange = { confirmed = it }
                )
                Text(
                    text = stringResource(R.string.preflight_confirm_checkbox),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Button(
                onClick = {
                    requested = true
                    onConfirmUnlock()
                    onStartUnlock()
                },
                enabled = uiState.preflightNotice != null &&
                    !uiState.preflightLoading &&
                    uiState.preflightError == null &&
                    confirmed &&
                    !requested,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.preflight_btn_request))
            }

            Text(
                text = stringResource(R.string.preflight_footer_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
