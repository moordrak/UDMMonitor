package com.moordrak.udmmonitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.moordrak.udmmonitor.UiState
import com.moordrak.udmmonitor.data.NetClient

enum class ClientSort(val label: String) { RATE("Promet uživo"), TOTAL("Ukupno"), NAME("Ime"), IP("IP") }

@Composable
fun ClientsScreen(st: UiState, onClientClick: (NetClient) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(ClientSort.RATE) }

    val list = remember(st.clients, query, sort) {
        val q = query.trim().lowercase()
        st.clients
            .filter {
                q.isEmpty() || it.name.lowercase().contains(q) || (it.ip ?: "").contains(q) ||
                    it.mac.lowercase().contains(q) || (it.vendor ?: "").lowercase().contains(q)
            }
            .let { l ->
                when (sort) {
                    ClientSort.RATE -> l.sortedByDescending { it.totalRate }
                    ClientSort.TOTAL -> l.sortedByDescending { it.totalBytes }
                    ClientSort.NAME -> l.sortedBy { it.name.lowercase() }
                    ClientSort.IP -> l.sortedBy { Fmt.ipSortKey(it.ip) }
                }
            }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text("Traži ime, IP, MAC, proizvođača") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ClientSort.entries.forEach { s ->
                FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) })
            }
        }
        val wired = st.clients.count { it.isWired }
        Text(
            "${st.clients.size} uređaja • $wired žično • ${st.clients.size - wired} Wi-Fi",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        st.error?.let { Box(Modifier.padding(horizontal = 16.dp)) { ErrorCard(it) } }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(list, key = { it.mac }) { c -> ClientRow(c, onClick = { onClientClick(c) }) }
        }
    }
}

@Composable
fun ClientRow(c: NetClient, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (c.isWired) Icons.Filled.SettingsEthernet else Icons.Filled.Wifi,
                    contentDescription = if (c.isWired) "Žično" else "Wi-Fi",
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(c.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                Text("↓ ${Fmt.rate(c.downRate)}", style = MaterialTheme.typography.labelMedium, color = TrafficColors.down)
                Spacer(Modifier.width(8.dp))
                Text("↑ ${Fmt.rate(c.upRate)}", style = MaterialTheme.typography.labelMedium, color = TrafficColors.up)
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Text(
                    c.ip ?: "bez IP-a",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    c.mac,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val extra = buildList {
                add("ukupno ↓ ${Fmt.bytes(c.downBytes)} ↑ ${Fmt.bytes(c.upBytes)}")
                c.essid?.let { add(it) }
                c.signal?.let { add("$it dBm") }
                c.network?.let { add(it) }
                if (c.uptimeSec > 0) add("online ${Fmt.duration(c.uptimeSec)}")
                c.vendor?.takeIf { it != c.name }?.let { add(it) }
            }
            Text(
                extra.joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
