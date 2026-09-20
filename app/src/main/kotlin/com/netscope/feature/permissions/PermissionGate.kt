package com.netscope.feature.permissions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Asks for the runtime permissions NetScope uses, once, on first launch.
 *
 * Each permission is explained before the system dialog appears, because a bare
 * platform prompt gives the user no way to judge whether granting it is reasonable.
 * Denial is not a dead end: the app stays usable and every affected field is labelled
 * PERMISSION REQUIRED rather than left blank, so this is asked once and never nagged.
 */
@Composable
fun PermissionGate(viewModel: PermissionGateViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.onRequestCompleted() }

    // Nothing is requested until the user has read what each permission is for.
    LaunchedEffect(state.launchRequest) {
        val toRequest = state.launchRequest
        if (!toRequest.isNullOrEmpty()) {
            launcher.launch(toRequest.toTypedArray())
            viewModel.onRequestLaunched()
        }
    }

    if (!state.shouldPrompt) return

    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text("Before you start") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "NetScope needs a few permissions to see your network. Here is exactly what " +
                        "each one is for.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.requirements.forEach { requirement ->
                    Column {
                        Text(requirement.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            requirement.rationale,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "You can decline any of them. NetScope stays usable either way — anything a " +
                        "missing permission blocks is labelled PERMISSION REQUIRED instead of " +
                        "being left blank or silently empty.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::request) { Text("Continue") }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismiss) { Text("Not now") }
        },
    )
}
