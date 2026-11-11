package com.moordrak.udmmonitor.data

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.net.InetAddress

class SshException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Adrese samog UDM-a i LAN podmreže (sučelja br*), za razvrstavanje dolazno/odlazno. */
class LanInfo(
    private val lanNets: List<Cidr>,
    private val selfAddrs: Set<String>,
) {
    fun isSelf(ip: String) = ip in selfAddrs

    /** "Unutra" = LAN, sam router, privatni/link-local/multicast adrese. */
    fun isInside(ip: String): Boolean {
        if (ip in selfAddrs) return true
        val a = parseIp(ip) ?: return false
        if (a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress ||
            a.isMulticastAddress || a.isAnyLocalAddress
        ) return true
        val b = a.address
        if (b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC) return true // IPv6 ULA fc00::/7
        if (b.size == 4 && b.all { it == 0xFF.toByte() }) return true // broadcast
        return lanNets.any { it.contains(b) }
    }

    companion object {
        /** Parsira izlaz `ip -o addr show`. */
        fun parse(ipAddrOutput: String): LanInfo {
            val nets = mutableListOf<Cidr>()
            val self = mutableSetOf<String>()
            for (line in ipAddrOutput.lineSequence()) {
                val t = line.trim().split(WS)
                if (t.size < 4) continue
                val iface = t[1].substringBefore('@')
                val i = t.indexOfFirst { it == "inet" || it == "inet6" }
                if (i < 0 || i + 1 >= t.size) continue
                val addr = t[i + 1].substringBefore('/')
                val prefix = t[i + 1].substringAfter('/', "").toIntOrNull()
                parseIp(addr)?.let { self += it.hostAddress ?: addr; self += addr }
                if (iface.startsWith("br") && prefix != null) {
                    parseIp(addr)?.let { nets += Cidr(it.address, prefix) }
                }
            }
            return LanInfo(nets, self)
        }
    }
}

class Cidr(private val net: ByteArray, private val prefix: Int) {
    fun contains(addr: ByteArray): Boolean {
        if (addr.size != net.size) return false
        var bits = prefix
        var i = 0
        while (bits > 0 && i < addr.size) {
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((addr[i].toInt() and mask) != (net[i].toInt() and mask)) return false
            bits -= 8; i++
        }
        return true
    }
}

private val WS = Regex("\\s+")
private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

/** Pretvara samo numeričke IP literale (nikad ne radi DNS upit). */
fun parseIp(s: String): InetAddress? {
    if (!(IPV4.matches(s) || (s.contains(':') && s.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }))) return null
    return try { InetAddress.getByName(s) } catch (e: Exception) { null }
}

/** Sirovi zapis iz /proc/net/nf_conntrack ili `conntrack -L`. */
data class RawConn(
    val proto: String,
    val ttl: Long?,
    val state: String?,
    val src: String, val dst: String, val sport: Int?, val dport: Int?,
    val rSrc: String?, val rDst: String?, val rSport: Int?, val rDport: Int?,
    val bytes: Long?, val rBytes: Long?,
    val assured: Boolean, val unreplied: Boolean,
) {
    val key: String get() = "$proto|$src|$sport|$dst|$dport"
}

object ConntrackParser {
    private val PROTOS = setOf("tcp", "udp", "icmp", "icmpv6", "sctp", "gre", "udplite", "dccp", "unknown")

    fun parseLine(line: String): RawConn? {
        val t = line.trim().split(WS)
        val pi = t.indexOfFirst { it in PROTOS }
        if (pi < 0) return null
        val proto = t[pi]
        val ttl = t.getOrNull(pi + 2)?.toLongOrNull()
        var state: String? = null
        val first = HashMap<String, String>()
        val second = HashMap<String, String>()
        var assured = false
        var unreplied = false
        for (j in pi + 1 until t.size) {
            val tok = t[j]
            when {
                tok == "[ASSURED]" -> assured = true
                tok == "[UNREPLIED]" -> unreplied = true
                tok.contains('=') -> {
                    val k = tok.substringBefore('=')
                    val v = tok.substringAfter('=')
                    if (k !in first) first[k] = v
                    else if (k !in second) second[k] = v
                }
                state == null && tok.length > 2 && tok.all { it.isUpperCase() || it == '_' } -> state = tok
            }
        }
        val src = first["src"] ?: return null
        val dst = first["dst"] ?: return null
        return RawConn(
            proto = proto, ttl = ttl, state = state,
            src = src, dst = dst,
            sport = first["sport"]?.toIntOrNull(), dport = first["dport"]?.toIntOrNull(),
            rSrc = second["src"], rDst = second["dst"],
            rSport = second["sport"]?.toIntOrNull(), rDport = second["dport"]?.toIntOrNull(),
            bytes = first["bytes"]?.toLongOrNull(), rBytes = second["bytes"]?.toLongOrNull(),
            assured = assured, unreplied = unreplied,
        )
    }
}

/** Pamti prethodne brojače bajtova po konekciji i iz razlike računa brzinu. */
class ConnRateTracker {
    private class Sample(val orig: Long, val reply: Long, val time: Long)
    private var prev = HashMap<String, Sample>()

    fun build(raw: List<RawConn>, lan: LanInfo, now: Long): List<Connection> {
        val next = HashMap<String, Sample>(raw.size * 2)
        val out = ArrayList<Connection>(raw.size)
        for (r in raw) {
            val p = prev[r.key]
            var rateOrig = 0.0
            var rateReply = 0.0
            if (r.bytes != null && r.rBytes != null) {
                if (p != null && now > p.time) {
                    val dt = (now - p.time) / 1000.0
                    rateOrig = ((r.bytes - p.orig).coerceAtLeast(0)) / dt
                    rateReply = ((r.rBytes - p.reply).coerceAtLeast(0)) / dt
                }
                next[r.key] = Sample(r.bytes, r.rBytes, now)
            }

            val srcIn = lan.isInside(r.src)
            val dstIn = lan.isInside(r.dst)
            val dir = when {
                srcIn && !dstIn -> Direction.OUTGOING
                !srcIn -> Direction.INCOMING
                else -> Direction.LOCAL
            }
            out += when (dir) {
                Direction.INCOMING -> {
                    // Port forward: reply src je stvarni LAN uređaj; inače je cilj sam UDM.
                    val target = r.rSrc?.takeIf { it != r.dst && lan.isInside(it) } ?: r.dst
                    Connection(
                        key = r.key, proto = r.proto, state = r.state, direction = dir,
                        origSrc = r.src, origDst = r.dst, origSport = r.sport, origDport = r.dport,
                        lanIp = target, lanPort = if (target == r.rSrc) r.rSport else r.dport,
                        remoteIp = r.src, remotePort = r.sport,
                        bytesIn = r.bytes, bytesOut = r.rBytes,
                        rateIn = rateOrig, rateOut = rateReply,
                        ttlSec = r.ttl, assured = r.assured, unreplied = r.unreplied,
                    )
                }
                else -> Connection(
                    key = r.key, proto = r.proto, state = r.state, direction = dir,
                    origSrc = r.src, origDst = r.dst, origSport = r.sport, origDport = r.dport,
                    lanIp = r.src, lanPort = r.sport,
                    remoteIp = r.dst, remotePort = r.dport,
                    bytesIn = r.rBytes, bytesOut = r.bytes,
                    rateIn = rateReply, rateOut = rateOrig,
                    ttlSec = r.ttl, assured = r.assured, unreplied = r.unreplied,
                )
            }
        }
        prev = next
        return out
    }

    fun reset() { prev = HashMap() }
}

/**
 * SSH na UDM (uključi u UniFi OS → Postavke konzole → SSH) i čitanje conntrack tablice.
 * Host ključ se pamti pri prvom spajanju (TOFU).
 */
class ConntrackClient(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String,
    private val enableAccounting: Boolean,
    private val getHostKey: () -> String?,
    private val saveHostKey: (String) -> Unit,
) {
    private val jsch = JSch()
    private var session: Session? = null

    data class Snapshot(val raw: List<RawConn>, val lan: LanInfo)

    @Synchronized
    fun fetch(): Snapshot {
        val text = exec(CMD)
        val conntrackPart = text.substringBefore(MARKER)
        val addrPart = text.substringAfter(MARKER, "")
        val raw = conntrackPart.lineSequence().mapNotNull { ConntrackParser.parseLine(it) }.toList()
        if (raw.isEmpty() && conntrackPart.isBlank()) {
            throw SshException("Conntrack tablica je prazna ili nedostupna (treba root pristup)")
        }
        return Snapshot(raw, LanInfo.parse(addrPart))
    }

    private fun exec(cmd: String): String {
        val s = ensureSession()
        val ch = s.openChannel("exec") as ChannelExec
        try {
            ch.setCommand(cmd)
            val input = ch.inputStream
            ch.connect(10_000)
            return input.readBytes().toString(Charsets.UTF_8)
        } catch (e: Exception) {
            session?.disconnect(); session = null
            throw SshException("SSH naredba nije uspjela: ${e.message}", e)
        } finally {
            ch.disconnect()
        }
    }

    private fun ensureSession(): Session {
        session?.let { if (it.isConnected) return it }
        val s = jsch.getSession(user, host, port)
        s.setPassword(password)
        s.setConfig("StrictHostKeyChecking", "no") // provjeru radimo sami (TOFU) odmah ispod
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        try {
            s.connect(10_000)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            throw SshException(
                when {
                    msg.contains("Auth fail", true) -> "SSH: pogrešno korisničko ime ili lozinka"
                    msg.contains("timeout", true) || msg.contains("refused", true) ->
                        "SSH nije dostupan na $host:$port – je li SSH uključen na UDM-u?"
                    else -> "SSH greška: $msg"
                }, e
            )
        }
        val fp = s.hostKey.getFingerPrint(jsch)
        val known = getHostKey()
        if (known == null) saveHostKey(fp)
        else if (known != fp) {
            s.disconnect()
            throw SshException("SSH ključ UDM-a se promijenio! Ako je to očekivano (reset/firmver), resetiraj ga u Postavkama.")
        }
        s.setServerAliveInterval(15_000)
        session = s
        if (enableAccounting) {
            // Bez ovoga conntrack ne broji bajtove po konekciji. Vrijedi do reboota UDM-a.
            runCatching { exec("echo 1 > /proc/sys/net/netfilter/nf_conntrack_acct 2>/dev/null; true") }
        }
        return s
    }

    @Synchronized
    fun close() {
        session?.disconnect()
        session = null
    }

    companion object {
        private const val MARKER = "@@UDM_ADDR@@"
        private const val CMD =
            "if [ -r /proc/net/nf_conntrack ]; then cat /proc/net/nf_conntrack; else conntrack -L 2>/dev/null; fi; " +
                "echo '$MARKER'; ip -o addr show 2>/dev/null"
    }
}
