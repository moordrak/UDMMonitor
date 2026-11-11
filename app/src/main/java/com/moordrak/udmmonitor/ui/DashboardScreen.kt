package com.moordrak.udmmonitor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moordrak.udmmonitor.UiState
import com.moordrak.udmmonitor.data.NetClient

@Composable
fun DashboardScreen(st: UiState, onClientClick: (NetClient) -> Unit) {
    val gw = st.gateway
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        st.error?.let { item { ErrorCard(it) } }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Internet (WAN) uživo", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        BigRate("Download", gw?.totalDown, Icons.Filled.ArrowDownward, Modifier.weight(1f))
                        BigRate("Upload", gw?.totalUp, Icons.Filled.ArrowUpward, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    RateChart(st.history)
                    gw?.wans?.forEach { w ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${w.name}: ${w.ip ?: "bez IP-a"}" + (if (!w.up) "  •  NEDOSTUPAN" else "") +
                                (if (w.downBytes > 0) "  •  ukupno ↓ ${Fmt.bytes(w.downBytes)} ↑ ${Fmt.bytes(w.upBytes)}" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (gw != null) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Router, null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(gw.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOf(gw.model, gw.version).filter { it.isNotBlank() }.joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Stat("CPU", gw.cpuPercent?.let { "%.0f %%".format(it) } ?: "–", Modifier.weight(1f))
                        Stat("RAM", gw.memPercent?.let { "%.0f %%".format(it) } ?: "–", Modifier.weight(1f))
                        Stat("Temp.", gw.temperature?.let { "%.0f °C".format(it) } ?: "–", Modifier.weight(1f))
                        Stat("Uptime", Fmt.duration(gw.uptimeSec), Modifier.weight(1f))
                    }
                    gw.cpuPercent?.let {
                        LinearProgressIndicator(progress = { (it / 100).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                    }
                }
            }
        }

        val top = st.clients.sortedByDescending { it.totalRate }.take(6)
        if (top.isNotEmpty()) {
            item {
                Text(
                    "Najveći promet trenutno (${st.clients.size} uređaja na mreži)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(top, key = { it.mac }) { c -> ClientRow(c, onClick = { onClientClick(c) }) }
        }

        if (gw == null && st.error == null) item {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun BigRate(label: String, v: Double?, icon: ImageVector, modifier: Modifier) {
    val color = if (label == "Download") TrafficColors.down else TrafficColors.up
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
        Text(
            v?.let { Fmt.rate(it) } ?: "–",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
fun ErrorCard(msg: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(msg, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
