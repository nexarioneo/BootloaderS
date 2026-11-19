package dev.bootloaderstudio.app.ui.account

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
fun AccountScreen(
    uiState: UblUiState,
    onLoadAccount: () -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onLogout: (() -> Unit)? = null
) {
    LaunchedEffect(Unit) {
        onLoadAccount()
    }

    AppScaffold(
        title = stringResource(R.string.account_title),
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
            when {
                uiState.accountLoading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        Text(
                            text = stringResource(R.string.account_loading),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                uiState.accountError != null -> {
                    WarningCard(text = uiState.accountError)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (onLogout != null) {
                            OutlinedButton(
                                onClick = onLogout,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.account_btn_switch))
                            }
                        }
                        Button(
                            onClick = onLoadAccount,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.btn_retry))
                        }
                    }
                }

                uiState.account != null -> {
                    val account = uiState.account
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = stringResource(R.string.account_session_ready),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            InfoRow(stringResource(R.string.account_id), account.userId)
                            InfoRow(stringResource(R.string.account_region), account.region)
                            InfoRow(stringResource(R.string.account_unlock_server), account.host)
                        }
                    }

                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.account_security_desc),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = onContinue,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.account_btn_continue))
                    }

                    if (onLogout != null) {
                        OutlinedButton(
                            onClick = onLogout,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.account_btn_switch))
                        }
                    }
                }

                else -> {
                    Text(
                        text = stringResource(R.string.account_no_session),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (onLogout != null) {
                        Button(
                            onClick = onLogout,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.account_btn_relogin))
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.account_server_decision_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
