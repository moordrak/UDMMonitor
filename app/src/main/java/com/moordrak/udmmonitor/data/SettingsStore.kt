package com.moordrak.udmmonitor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class AppConfig(
    val host: String = "",
    val site: String = "default",
    val username: String = "",
    val password: String = "",
    val pollSeconds: Int = 2,
    /** Ako se pokaže da su download/upload klijenata obrnuti na tvojoj verziji firmvera. */
    val swapClientDirection: Boolean = false,
    val sshEnabled: Boolean = false,
    val sshPort: Int = 22,
    val sshUser: String = "root",
    val sshPassword: String = "",
    /** Uključi brojanje bajtova po konekciji (sysctl nf_conntrack_acct=1 na UDM-u). */
    val sshEnableAccounting: Boolean = true,
) {
    val isConfigured: Boolean get() = host.isNotBlank() && username.isNotBlank() && password.isNotBlank()
    val sshConfigured: Boolean get() = sshEnabled && sshUser.isNotBlank() && sshPassword.isNotBlank()

    val baseUrl: String
        get() {
            val h = host.trim().trimEnd('/')
            return if (h.startsWith("http://") || h.startsWith("https://")) h else "https://$h"
        }

    val sshHost: String
        get() = baseUrl.removePrefix("https://").removePrefix("http://").substringBefore('/').let { hp ->
            // makni port ako je naveden uz host (npr. 192.168.1.1:443), ali ne diraj IPv6 u []
            if (hp.startsWith("[")) hp.substringAfter('[').substringBefore(']')
            else hp.substringBefore(':')
        }
}

/** Sprema postavke šifrirano (Android Keystore). Pamti i TOFU otiske certifikata/SSH ključa. */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "udm_secure_prefs",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // Na rijetkim uređajima s pokvarenim Keystoreom padamo na obične preference.
        context.getSharedPreferences("udm_prefs_fallback", Context.MODE_PRIVATE)
    }

    fun load(): AppConfig = AppConfig(
        host = prefs.getString("host", "") ?: "",
        site = prefs.getString("site", "default") ?: "default",
        username = prefs.getString("username", "") ?: "",
        password = prefs.getString("password", "") ?: "",
        pollSeconds = prefs.getInt("poll", 2),
        swapClientDirection = prefs.getBoolean("swap", false),
        sshEnabled = prefs.getBoolean("ssh_enabled", false),
        sshPort = prefs.getInt("ssh_port", 22),
        sshUser = prefs.getString("ssh_user", "root") ?: "root",
        sshPassword = prefs.getString("ssh_password", "") ?: "",
        sshEnableAccounting = prefs.getBoolean("ssh_acct", true),
    )

    fun save(c: AppConfig) {
        prefs.edit()
            .putString("host", c.host.trim())
            .putString("site", c.site.trim().ifBlank { "default" })
            .putString("username", c.username.trim())
            .putString("password", c.password)
            .putInt("poll", c.pollSeconds)
            .putBoolean("swap", c.swapClientDirection)
            .putBoolean("ssh_enabled", c.sshEnabled)
            .putInt("ssh_port", c.sshPort)
            .putString("ssh_user", c.sshUser.trim())
            .putString("ssh_password", c.sshPassword)
            .putBoolean("ssh_acct", c.sshEnableAccounting)
            .apply()
    }

    var certPin: String?
        get() = prefs.getString("cert_pin", null)
        set(v) = prefs.edit().putString("cert_pin", v).apply()

    var sshHostKey: String?
        get() = prefs.getString("ssh_hostkey", null)
        set(v) = prefs.edit().putString("ssh_hostkey", v).apply()
}
