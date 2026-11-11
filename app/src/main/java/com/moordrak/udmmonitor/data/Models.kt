package com.moordrak.udmmonitor.data

/** Jedan WAN uplink na gatewayu. Brzine su u bajtovima u sekundi. */
data class WanStats(
    val name: String,
    val ip: String?,
    val up: Boolean,
    val downRate: Double,
    val upRate: Double,
    val downBytes: Long,
    val upBytes: Long,
)

data class GatewayStats(
    val name: String,
    val model: String,
    val version: String,
    val cpuPercent: Double?,
    val memPercent: Double?,
    val temperature: Double?,
    val uptimeSec: Long,
    val clientCount: Int?,
    val wans: List<WanStats>,
) {
    val totalDown: Double get() = wans.sumOf { it.downRate }
    val totalUp: Double get() = wans.sumOf { it.upRate }
}

/** Uređaj na mreži. "down" = promet prema uređaju, "up" = promet od uređaja. */
data class NetClient(
    val mac: String,
    val ip: String?,
    val name: String,
    val hostname: String?,
    val vendor: String?,
    val isWired: Boolean,
    val network: String?,
    val essid: String?,
    val signal: Int?,
    val downRate: Double,
    val upRate: Double,
    val downBytes: Long,
    val upBytes: Long,
    val uptimeSec: Long,
) {
    val totalRate: Double get() = downRate + upRate
    val totalBytes: Long get() = downBytes + upBytes
}

data class RatePoint(val time: Long, val down: Double, val up: Double)

enum class Direction { OUTGOING, INCOMING, LOCAL }

/**
 * Jedna konekcija iz conntrack tablice.
 * "in"/"out" su gledano iz perspektive tvoje mreže (in = prema LAN-u, out = prema van).
 */
data class Connection(
    val key: String,
    val proto: String,
    val state: String?,
    val direction: Direction,
    val origSrc: String,
    val origDst: String,
    val origSport: Int?,
    val origDport: Int?,
    /** Lokalni uređaj (IP) koji sudjeluje u konekciji, ako ga znamo. */
    val lanIp: String?,
    val lanPort: Int?,
    /** Udaljena strana (IP). */
    val remoteIp: String,
    val remotePort: Int?,
    val bytesIn: Long?,
    val bytesOut: Long?,
    val rateIn: Double,
    val rateOut: Double,
    val ttlSec: Long?,
    val assured: Boolean,
    val unreplied: Boolean,
) {
    val totalRate: Double get() = rateIn + rateOut
    val totalBytes: Long get() = (bytesIn ?: 0) + (bytesOut ?: 0)
    val isActive: Boolean
        get() = state == null || state == "ESTABLISHED" || state == "SYN_SENT" || state == "SYN_RECV"
}
