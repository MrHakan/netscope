package com.netscope.feature.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.netscope.feature.common.NetScopeScreen

private data class MoreEntry(val title: String, val description: String, val onClick: () -> Unit)

@Composable
fun MoreScreen(
    onOpenNetworks: () -> Unit,
    onOpenSubnets: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val entries = listOf(
        MoreEntry(
            "Networks & routes",
            "Every Network object Android exposes, with its addresses, DNS and route table.",
            onOpenNetworks,
        ),
        MoreEntry(
            "Subnet analyzer",
            "Work out whether another subnet is reachable, and see the evidence for the answer.",
            onOpenSubnets,
        ),
        MoreEntry(
            "History",
            "Stored scans and the network profiles they belong to.",
            onOpenHistory,
        ),
        MoreEntry(
            "Settings",
            "Scan performance, privacy controls and capability level.",
            onOpenSettings,
        ),
    )

    NetScopeScreen(title = "More") { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries) { entry ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = entry.onClick),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            entry.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
