package com.moordrak.udmmonitor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.moordrak.udmmonitor.UiState
import com.moordrak.udmmonitor.data.AppConfig
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(st: UiState, onSave: (AppConfig) -> Unit, onResetPins: () -> Unit) {
    var c by remember(st.config) { mutableStateOf(st.config) }
    var sshPortText by remember(st.config) { mutableStateOf(st.config.sshPort.toString()) }
    var saved by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("UniFi Dream Machine", style = MaterialTheme.typography.titleMedium)
        Text(
            "Preporuka: u UniFi OS-u napravi zasebnog lokalnog korisnika s ulogom \"View Only\" " +
                "(Admins & Users → Restrict to local access only). Ubiquiti cloud račun s 2FA ovdje ne radi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = c.host, onValueChange = { c = c.copy(host = it) },
            label = { Text("Adresa (npr. 192.168.1.1)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = c.username, onValueChange = { c = c.copy(username = it) },
            label = { Text("Korisničko ime") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        PasswordField("Lozinka", c.password) { c = c.copy(password = it) }
        OutlinedTextField(
            value = c.site, onValueChange = { c = c.copy(site = it) },
            label = { Text("Site (obično default)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        Text("Osvježavanje svakih ${c.pollSeconds} s", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = c.pollSeconds.toFloat(),
            onValueChange = { c = c.copy(pollSeconds = it.roundToInt()) },
            valueRange = 1f..10f, steps = 8,
        )
        SwitchRow(
            "Zamijeni download/upload kod uređaja",
            "Uključi ako ti se smjer prometa uređaja čini obrnutim.",
            c.swapClientDirection,
        ) { c = c.copy(swapClientDirection = it) }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Text("Konekcije (SSH)", style = MaterialTheme.typography.titleMedium)
        SwitchRow(
            "Čitaj conntrack preko SSH-a",
            "Potrebno za popis dolaznih/odlaznih konekcija.",
            c.sshEnabled,
        ) { c = c.copy(sshEnabled = it) }
        if (c.sshEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = c.sshUser, onValueChange = { c = c.copy(sshUser = it) },
                    label = { Text("SSH korisnik") }, singleLine = true, modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = sshPortText,
                    onValueChange = { v -> sshPortText = v.filter { it.isDigit() }.take(5) },
                    label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            PasswordField("SSH lozinka", c.sshPassword) { c = c.copy(sshPassword = it) }
            SwitchRow(
                "Uključi brojanje bajtova po konekciji",
                "Postavlja nf_conntrack_acct=1 na UDM-u (vrijedi do restarta). Bez toga nema brzine po konekciji.",
                c.sshEnableAccounting,
            ) { c = c.copy(sshEnableAccounting = it) }
        }

        Button(
            onClick = {
                onSave(c.copy(sshPort = sshPortText.toIntOrNull()?.coerceIn(1, 65535) ?: 22))
                saved = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Spremi i spoji se") }
        if (saved && st.error == null && st.lastUpdate > 0) {
            Text("Spojeno ✓", color = MaterialTheme.colorScheme.primary)
        }
        st.error?.let { ErrorCard(it) }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Sigurnost", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pri prvom spajanju aplikacija zapamti otisak HTTPS certifikata i SSH ključa UDM-a te " +
                "odbija spajanje ako se promijene.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Fingerprint("HTTPS certifikat (SHA-256)", st.certPin)
        Fingerprint("SSH ključ", st.sshHostKey)
        OutlinedButton(onClick = onResetPins, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Refresh, null)
            Spacer(Modifier.width(8.dp))
            Text("Zaboravi spremljene otiske")
        }
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Prikaži lozinku")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Fingerprint(label: String, value: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value ?: "još nije spremljen",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
