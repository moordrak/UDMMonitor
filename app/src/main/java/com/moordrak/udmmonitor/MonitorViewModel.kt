package com.moordrak.udmmonitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moordrak.udmmonitor.data.AppConfig
import com.moordrak.udmmonitor.data.ConnRateTracker
import com.moordrak.udmmonitor.data.Connection
import com.moordrak.udmmonitor.data.ConntrackClient
import com.moordrak.udmmonitor.data.GatewayStats
import com.moordrak.udmmonitor.data.NetClient
import com.moordrak.udmmonitor.data.RatePoint
import com.moordrak.udmmonitor.data.SettingsStore
import com.moordrak.udmmonitor.data.UnifiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class UiState(
    val config: AppConfig,
    val gateway: GatewayStats? = null,
    val clients: List<NetClient> = emptyList(),
    val history: List<RatePoint> = emptyList(),
    val lastUpdate: Long = 0,
    val error: String? = null,
    val connections: List<Connection> = emptyList(),
    val connLastUpdate: Long = 0,
    val connError: String? = null,
    val connLoading: Boolean = false,
    val certPin: String? = null,
    val sshHostKey: String? = null,
)

class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private val _state = MutableStateFlow(
        UiState(config = store.load(), certPin = store.certPin, sshHostKey = store.sshHostKey)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var api: UnifiClient? = null
    private var ssh: ConntrackClient? = null
    private val rateTracker = ConnRateTracker()

    private var started = false
    private var connVisible = false
    private var pollJob: Job? = null
    private var connJob: Job? = null

    fun start() {
        started = true
        restart()
    }

    fun stop() {
        started = false
        pollJob?.cancel(); pollJob = null
        connJob?.cancel(); connJob = null
    }

    fun setConnectionsVisible(visible: Boolean) {
        connVisible = visible
        updateConnJob()
    }

    fun saveConfig(newCfg: AppConfig) {
        val old = _state.value.config
        if (old.host.trim() != newCfg.host.trim()) {
            store.certPin = null
            store.sshHostKey = null
        }
        store.save(newCfg)
        resetClients()
        _state.update {
            it.copy(
                config = store.load(), error = null, connError = null,
                history = emptyList(), certPin = store.certPin, sshHostKey = store.sshHostKey,
            )
        }
        restart()
    }

    fun resetPins() {
        store.certPin = null
        store.sshHostKey = null
        resetClients()
        _state.update { it.copy(certPin = null, sshHostKey = null, error = null, connError = null) }
        restart()
    }

    private fun resetClients() {
        api = null
        ssh?.let { s -> viewModelScope.launch(Dispatchers.IO) { s.close() } }
        ssh = null
        rateTracker.reset()
    }

    private fun restart() {
        pollJob?.cancel()
        connJob?.cancel(); connJob = null
        val cfg = _state.value.config
        if (!started || !cfg.isConfigured) return

        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val client = api ?: UnifiClient(
                        cfg,
                        getPin = { store.certPin },
                        savePin = { pin ->
                            store.certPin = pin
                            _state.update { s -> s.copy(certPin = pin) }
                        },
                    ).also { api = it }
                    val gw = client.fetchGateway()
                    val clients = client.fetchClients()
                    val now = System.currentTimeMillis()
                    _state.update { s ->
                        s.copy(
                            gateway = gw,
                            clients = clients,
                            history = (s.history + RatePoint(now, gw.totalDown, gw.totalUp)).takeLast(MAX_POINTS),
                            lastUpdate = now,
                            error = null,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message ?: e.toString()) }
                }
                delay(cfg.pollSeconds.coerceIn(1, 30) * 1000L)
            }
        }
        updateConnJob()
    }

    private fun updateConnJob() {
        val cfg = _state.value.config
        val shouldRun = started && connVisible && cfg.sshConfigured
        if (!shouldRun) {
            connJob?.cancel(); connJob = null
            return
        }
        if (connJob?.isActive == true) return

        connJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(connLoading = true) }
            while (isActive) {
                try {
                    val c = ssh ?: ConntrackClient(
                        host = cfg.sshHost,
                        port = cfg.sshPort,
                        user = cfg.sshUser,
                        password = cfg.sshPassword,
                        enableAccounting = cfg.sshEnableAccounting,
                        getHostKey = { store.sshHostKey },
                        saveHostKey = { k ->
                            store.sshHostKey = k
                            _state.update { s -> s.copy(sshHostKey = k) }
                        },
                    ).also { ssh = it }
                    val snap = c.fetch()
                    val now = System.currentTimeMillis()
                    val conns = rateTracker.build(snap.raw, snap.lan, now)
                    _state.update {
                        it.copy(connections = conns, connLastUpdate = now, connError = null, connLoading = false)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(connError = e.message ?: e.toString(), connLoading = false) }
                }
                delay(maxOf(cfg.pollSeconds, 2) * 1000L)
            }
        }
    }

    override fun onCleared() {
        ssh?.close()
        super.onCleared()
    }

    companion object {
        const val MAX_POINTS = 150
    }
}
