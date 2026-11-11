package com.moordrak.udmmonitor.ui

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
import com.moordrak.udmmonitor.data.Connection
import com.moordrak.udmmonitor.data.Direction

private const val MAX_SHOWN = 800

@Composable
fun ConnectionsScreen(
    st: UiState,
    filter: String,
    onFilterChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (!st.config.sshConfigured) {
        SshHelp(onOpenSettings)
        return
    }

    var dir by rememberSaveable { mutableStateOf(Direction.OUTGOING) }
    var onlyActive by rememberSaveable { mutableStateOf(true) }
    var proto by rememberSaveable { mutableStateOf("sve") }

    // IP -> ime uređaja, za čitljiviji prikaz
    val names = remember(st.clients) { st.clients.filter { it.ip != null }.associate { it.ip!! to it.name } }

    val base = remember(st.connections, onlyActive, proto) {
        st.connections.filter { c ->
            (!onlyActive || c.isActive) && (proto == "sve" || c.proto == proto)
        }
    }
    val counts = remember(base) { base.groupingBy { it.direction }.eachCount() }
    val shown = remember(base, dir, filter, names) {
        val q = filter.trim().lowercase()
        base.asSequence()
            .filter { it.direction == dir }
            .filter { c ->
                q.isEmpty() || c.remoteIp.contains(q) || (c.lanIp ?: "").contains(q) ||
                    c.remotePort?.toString() == q || c.lanPort?.toString() == q ||
                    (c.lanIp?.let { names[it] }?.lowercase()?.contains(q) == true) ||
                    (Fmt.service(c.remotePort ?: c.lanPort)?.lowercase()?.contains(q) == true)
            }
            .sortedWith(compareByDescending<Connection> { it.totalRate }.thenByDescending { it.totalBytes })
            .toList()
    }
    val hasBytes = st.connections.any { it.bytesIn != null }

    Column(Modifier.fillMaxSize()) {
        val tabs = listOf(Direction.OUTGOING to "Odlazne", Direction.INCOMING to "Dolazne", Direction.LOCAL to "Lokalne")
        TabRow(selectedTabIndex = tabs.indexOfFirst { it.first == dir }) {
            tabs.forEach { (d, label) ->
                Tab(selected = dir == d, onClick = { dir = d }, text = { Text("$label (${counts[d] ?: 0})") })
            }
        }
        OutlinedTextField(
            value = filter,
            onValueChange = onFilterChange,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (filter.isNotEmpty()) IconButton(onClick = { onFilterChange("") }) { Icon(Icons.Filled.Clear, "Očisti") }
            },
            placeholder = { Text("Filtriraj po IP-u, portu, uređaju, servisu") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = onlyActive, onClick = { onlyActive = !onlyActive }, label = { Text("Samo aktivne") })
            listOf("sve", "tcp", "udp", "icmp").forEach { p ->
                FilterChip(selected = proto == p, onClick = { proto = p }, label = { Text(p.uppercase()) })
            }
        }
        val dirRate = shown.sumOf { it.rateIn } to shown.sumOf { it.rateOut }
        Text(
            buildString {
                append("${shown.size} konekcija")
                if (hasBytes) append(" • ↓ ${Fmt.rate(dirRate.first)} ↑ ${Fmt.rate(dirRate.second)}")
                else append(" • brojanje bajtova nije uključeno")
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        st.connError?.let { Box(Modifier.padding(horizontal = 16.dp)) { ErrorCard(it) } }
        if (st.connLoading && st.connections.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(shown.take(MAX_SHOWN), key = { it.key }) { c -> ConnRow(c, names, hasBytes) }
            if (shown.size > MAX_SHOWN) item {
                Text(
                    "Prikazano prvih $MAX_SHOWN od ${shown.size}. Suzi filter.",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun ConnRow(c: Connection, names: Map<String, String>, hasBytes: Boolean) {
    val lanName = c.lanIp?.let { names[it] }
    val service = Fmt.service(if (c.direction == Direction.INCOMING) c.lanPort ?: c.origDport else c.remotePort)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChipLike(c.proto.uppercase())
                Spacer(Modifier.width(6.dp))
                if (service != null) { AssistChipLike(service); Spacer(Modifier.width(6.dp)) }
                Text(
                    c.state ?: if (c.unreplied) "BEZ ODGOVORA" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (hasBytes) {
                    Text("↓ ${Fmt.rate(c.rateIn)}", style = MaterialTheme.typography.labelSmall, color = TrafficColors.down)
                    Spacer(Modifier.width(6.dp))
                    Text("↑ ${Fmt.rate(c.rateOut)}", style = MaterialTheme.typography.labelSmall, color = TrafficColors.up)
                }
            }
            Spacer(Modifier.height(4.dp))
            val lanText = (lanName?.let { "$it  " } ?: "") + Fmt.hostPort(c.lanIp, c.lanPort)
            val remoteText = Fmt.hostPort(c.remoteIp, c.remotePort)
            val arrow = when (c.direction) {
                Direction.OUTGOING -> "→"
                Direction.INCOMING -> "←"
                Direction.LOCAL -> "↔"
            }
            Text(lanText, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text("$arrow $remoteText", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            if (hasBytes && (c.bytesIn != null)) {
                Text(
                    "ukupno ↓ ${Fmt.bytes(c.bytesIn)} ↑ ${Fmt.bytes(c.bytesOut ?: 0)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AssistChipLike(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SshHelp(onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.SwapVert, null, modifier = Modifier.size(48.dp))
        Text("Dolazne i odlazne konekcije", style = MaterialTheme.typography.titleLarge)
        Text(
            "UniFi API ne daje popis pojedinačnih konekcija, pa ih aplikacija čita izravno iz " +
                "conntrack tablice UDM-a preko SSH-a (samo čitanje).\n\n" +
                "1. U UniFi OS-u otvori Settings → Control Plane → Console → SSH i uključi ga te postavi lozinku.\n" +
                "2. Ovdje u Postavkama uključi SSH i upiši korisnika root i tu lozinku.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onOpenSettings) { Text("Otvori postavke") }
    }
}
