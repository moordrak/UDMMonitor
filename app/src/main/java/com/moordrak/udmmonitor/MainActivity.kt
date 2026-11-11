package com.moordrak.udmmonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moordrak.udmmonitor.ui.*

class MainActivity : ComponentActivity() {
    private val vm: MonitorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { UdmTheme { App(vm) } }
    }

    override fun onStart() {
        super.onStart()
        vm.start()
    }

    override fun onStop() {
        vm.stop()
        super.onStop()
    }
}

enum class Screen(val label: String, val icon: ImageVector) {
    DASH("Pregled", Icons.Filled.Speed),
    CLIENTS("Uređaji", Icons.Filled.Devices),
    CONNS("Konekcije", Icons.Filled.SwapVert),
    SETTINGS("Postavke", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MonitorViewModel) {
    val st by vm.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(if (st.config.isConfigured) Screen.DASH else Screen.SETTINGS) }
    var connFilter by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(screen) { vm.setConnectionsVisible(screen == Screen.CONNS) }

    val openConnsFor: (String?) -> Unit = { ip ->
        connFilter = ip.orEmpty()
        screen = Screen.CONNS
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(st.gateway?.name ?: "UDM Monitor") },
                actions = { LiveIndicator(st) },
            )
        },
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { s ->
                    NavigationBarItem(
                        selected = screen == s,
                        onClick = { screen = s },
                        icon = { Icon(s.icon, null) },
                        label = { Text(s.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (screen) {
                Screen.DASH -> DashboardScreen(st) { openConnsFor(it.ip) }
                Screen.CLIENTS -> ClientsScreen(st) { openConnsFor(it.ip) }
                Screen.CONNS -> ConnectionsScreen(
                    st, connFilter, { connFilter = it }, onOpenSettings = { screen = Screen.SETTINGS },
                )
                Screen.SETTINGS -> SettingsScreen(st, onSave = vm::saveConfig, onResetPins = vm::resetPins)
            }
        }
    }
}

@Composable
private fun LiveIndicator(st: UiState) {
    val ok = st.error == null && st.lastUpdate > 0
    val color = when {
        st.error != null -> MaterialTheme.colorScheme.error
        ok -> Color(0xFF2E9E4F)
        else -> MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            when {
                st.error != null -> "Greška"
                ok -> "Uživo"
                else -> "Spajanje…"
            },
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
