package com.netscope.feature.access

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netscope.core.network.FtpEntryType
import com.netscope.core.ui.MonoSmallTextStyle
import com.netscope.core.ui.NoticeBanner
import com.netscope.core.ui.NoticeTone
import com.netscope.core.ui.SectionCard
import com.netscope.feature.common.NetScopeScreen

@Composable
fun FtpBrowserScreen(
    onBack: () -> Unit,
    viewModel: FtpBrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    NetScopeScreen(
        title = "FTP Browser",
        subtitle = state.host + ":" + state.port,
        onBack = onBack,
    ) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                NoticeBanner(
                    text = "Plain FTP does not encrypt usernames, passwords or file names. " +
                        "Use it only on a network you trust. Prefer SFTP or FTPS when the server supports them.",
                    tone = NoticeTone.WARNING,
                )
            }

            item {
                SectionCard(
                    title = "Login",
                    subtitle = "Credentials stay in memory and are never saved",
                ) {
                    Column {
                        OutlinedTextField(
                            value = state.username,
                            onValueChange = viewModel::setUsername,
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = viewModel::setPassword,
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = viewModel::connect,
                                enabled = !state.isLoading,
                            ) { Text(if (state.entries.isEmpty()) "Connect & list" else "Refresh") }
                            OutlinedButton(
                                onClick = { openExternalFtp(context, state.host, state.port, state.path) },
                            ) { Text("Open externally") }
                        }
                    }
                }
            }

            if (state.isLoading) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                        Text("Reading directory…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            state.error?.let { error ->
                item { NoticeBanner(text = error, tone = NoticeTone.WARNING) }
            }

            state.greeting?.let { greeting ->
                item {
                    SectionCard(title = "Server") {
                        Text(greeting, style = MonoSmallTextStyle)
                    }
                }
            }

            if (state.greeting != null || state.entries.isNotEmpty()) {
                item {
                    SectionCard(
                        title = "Directory",
                        subtitle = state.path,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = viewModel::up,
                                enabled = !state.isLoading && state.path != "/",
                            ) { Text("Up") }
                            TextButton(
                                onClick = {
                                    copyText(context, buildFtpUri(state.host, state.port, state.path))
                                },
                            ) { Text("Copy URI") }
                        }
                    }
                }

                state.entries.forEach { entry ->
                    item(key = entry.type.name + ":" + entry.name) {
                        SectionCard(
                            title = when (entry.type) {
                                FtpEntryType.DIRECTORY -> "DIR  " + entry.name
                                FtpEntryType.FILE -> "FILE  " + entry.name
                                FtpEntryType.OTHER -> entry.name
                            },
                            subtitle = entry.sizeBytes?.let { formatBytes(it) },
                        ) {
                            Column {
                                if (entry.type == FtpEntryType.DIRECTORY) {
                                    Button(
                                        onClick = { viewModel.openDirectory(entry.name) },
                                        enabled = !state.isLoading,
                                    ) { Text("Open directory") }
                                } else {
                                    Text(
                                        "Read-only browser: NetScope lists this entry but does not " +
                                            "upload, delete or rename remote files.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    entry.raw,
                                    style = MonoSmallTextStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildFtpUri(host: String, port: Int, path: String): String {
    val authority = if (port == 21) host else host + ":" + port
    val normalPath = if (path.startsWith("/")) path else "/" + path
    return "ftp://" + authority + normalPath
}

private fun openExternalFtp(context: Context, host: String, port: Int, path: String) {
    val uri = buildFtpUri(host, port, path)
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    } else {
        Toast.makeText(context, "No installed app handles FTP links.", Toast.LENGTH_LONG).show()
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("NetScope FTP URI", text))
    Toast.makeText(context, "FTP URI copied", Toast.LENGTH_SHORT).show()
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> bytes.toString() + " B"
    bytes < 1024 * 1024 -> String.format("%.1f KiB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MiB", bytes / (1024.0 * 1024.0))
    else -> String.format("%.1f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
}
