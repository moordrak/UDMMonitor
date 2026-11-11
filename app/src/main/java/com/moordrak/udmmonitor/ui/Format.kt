package com.moordrak.udmmonitor.ui

import java.util.Locale

object Fmt {
    /** Bajtovi u sekundi -> bitovi u sekundi (kako to prikazuje UniFi i ISP). */
    fun rate(bytesPerSec: Double): String {
        val bits = bytesPerSec * 8
        return when {
            bits >= 1e9 -> String.format(Locale.US, "%.2f Gbit/s", bits / 1e9)
            bits >= 1e6 -> String.format(Locale.US, "%.1f Mbit/s", bits / 1e6)
            bits >= 1e3 -> String.format(Locale.US, "%.0f kbit/s", bits / 1e3)
            else -> String.format(Locale.US, "%.0f bit/s", bits)
        }
    }

    fun bytes(b: Long): String {
        val v = b.toDouble()
        return when {
            v >= 1L shl 40 -> String.format(Locale.US, "%.2f TB", v / (1L shl 40))
            v >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", v / (1L shl 30))
            v >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", v / (1L shl 20))
            v >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", v / (1L shl 10))
            else -> "$b B"
        }
    }

    fun duration(sec: Long): String {
        if (sec <= 0) return "–"
        val d = sec / 86400
        val h = (sec % 86400) / 3600
        val m = (sec % 3600) / 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }

    fun hostPort(ip: String?, port: Int?): String {
        if (ip == null) return "?"
        val h = if (ip.contains(':')) "[$ip]" else ip
        return if (port != null) "$h:$port" else h
    }

    /** Za sortiranje IPv4 adresa numerički. */
    fun ipSortKey(ip: String?): Long {
        if (ip == null) return Long.MAX_VALUE
        val p = ip.split('.')
        if (p.size != 4) return Long.MAX_VALUE - 1
        return p.fold(0L) { acc, s -> acc * 256 + (s.toLongOrNull() ?: 0) }
    }

    private val PORTS = mapOf(
        22 to "SSH", 53 to "DNS", 80 to "HTTP", 123 to "NTP", 443 to "HTTPS", 853 to "DoT",
        993 to "IMAPS", 465 to "SMTPS", 587 to "SMTP", 1194 to "OpenVPN", 3478 to "STUN",
        51820 to "WireGuard", 8096 to "Jellyfin", 8920 to "Jellyfin", 32400 to "Plex",
        5223 to "APNs", 5228 to "Google Push", 1900 to "SSDP", 5353 to "mDNS", 67 to "DHCP",
    )

    fun service(port: Int?): String? = port?.let { PORTS[it] }
}
