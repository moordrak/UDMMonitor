package com.moordrak.udmmonitor.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager

class UnifiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * "Trust on first use": UDM ima self-signed certifikat, pa pri prvom spajanju zapamtimo
 * SHA-256 otisak i ubuduće prihvaćamo samo taj certifikat (zaštita od MITM-a).
 */
class TofuTrustManager(
    private val getPin: () -> String?,
    private val savePin: (String) -> Unit,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Nije podržano")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Prazan lanac certifikata")
        val fp = sha256(leaf.encoded)
        val pin = getPin()
        if (pin == null) savePin(fp)
        else if (!pin.equals(fp, ignoreCase = true)) throw CertificateException(CERT_CHANGED)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        const val CERT_CHANGED = "CERT_CHANGED"
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }
    }
}

/** Klijent za UniFi OS (UDM / UDM Pro / UDR / UCG). Koristi lokalni korisnički račun. */
class UnifiClient(
    private val cfg: AppConfig,
    getPin: () -> String?,
    savePin: (String) -> Unit,
) {
    private val cookieStore = mutableMapOf<String, Cookie>()
    private var csrf: String? = null
    private var loggedIn = false

    private val http: OkHttpClient = run {
        val tm = TofuTrustManager(getPin, savePin)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            // Certifikat je prikovan (TOFU), pa ime hosta ne mora odgovarati (IP adresa).
            .hostnameVerifier { _, _ -> true }
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    synchronized(cookieStore) { cookies.forEach { cookieStore[it.name] = it } }
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> =
                    synchronized(cookieStore) { cookieStore.values.toList() }
            })
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val base = cfg.baseUrl
    private val site = cfg.site.ifBlank { "default" }

    @Synchronized
    private fun login() {
        val body = JSONObject()
            .put("username", cfg.username)
            .put("password", cfg.password)
            .put("remember", true)
            .toString()
            .toRequestBody(JSON)
        val req = Request.Builder().url("$base/api/auth/login").post(body).build()
        try {
            http.newCall(req).execute().use { r ->
                when {
                    r.code == 401 || r.code == 403 ->
                        throw UnifiException("Pogrešno korisničko ime ili lozinka (koristi lokalni račun bez 2FA)")
                    r.code == 429 -> throw UnifiException("Previše pokušaja prijave – pričekaj par minuta")
                    !r.isSuccessful -> throw UnifiException("Prijava nije uspjela: HTTP ${r.code}")
                }
                csrf = r.header("X-CSRF-Token") ?: r.header("X-Updated-CSRF-Token")
                loggedIn = true
            }
        } catch (e: SSLHandshakeException) {
            throw mapSsl(e)
        }
    }

    @Synchronized
    private fun get(path: String): JSONObject {
        if (!loggedIn) login()
        repeat(2) { attempt ->
            val req = Request.Builder()
                .url("$base$path")
                .apply { csrf?.let { header("X-CSRF-Token", it) } }
                .get()
                .build()
            try {
                http.newCall(req).execute().use { r ->
                    r.header("X-Updated-CSRF-Token")?.let { csrf = it }
                    if (r.code == 401 && attempt == 0) {
                        loggedIn = false
                        login()
                        return@repeat
                    }
                    if (!r.isSuccessful) throw UnifiException("HTTP ${r.code} za $path")
                    val text = r.body?.string() ?: throw UnifiException("Prazan odgovor")
                    val json = JSONObject(text)
                    val rc = json.optJSONObject("meta")?.optString("rc")
                    if (rc != null && rc.isNotEmpty() && rc != "ok") {
                        throw UnifiException("API greška: ${json.optJSONObject("meta")?.optString("msg")}")
                    }
                    return json
                }
            } catch (e: SSLHandshakeException) {
                throw mapSsl(e)
            } catch (e: java.io.IOException) {
                throw UnifiException("Ne mogu doći do ${cfg.host}: ${e.message}", e)
            }
        }
        throw UnifiException("Sesija je istekla i ponovna prijava nije uspjela")
    }

    private fun mapSsl(e: SSLHandshakeException): UnifiException {
        var c: Throwable? = e
        while (c != null) {
            if (c.message?.contains(TofuTrustManager.CERT_CHANGED) == true) {
                return UnifiException(
                    "Certifikat UDM-a se promijenio! Ako si nadogradio firmver ili promijenio certifikat, " +
                        "u Postavkama resetiraj spremljeni otisak. Inače netko možda presreće promet."
                )
            }
            c = c.cause
        }
        return UnifiException("TLS greška: ${e.message}", e)
    }

    /** Stanje gatewaya + WAN brzine (stat/device, s rezervom na stat/health). */
    fun fetchGateway(): GatewayStats {
        val devices = get("/proxy/network/api/s/$site/stat/device").optJSONArray("data") ?: JSONArray()
        var gw: JSONObject? = null
        for (i in 0 until devices.length()) {
            val d = devices.getJSONObject(i)
            val type = d.optString("type")
            if (type in GATEWAY_TYPES || d.has("wan1")) {
                gw = d; break
            }
        }

        val wans = mutableListOf<WanStats>()
        if (gw != null) {
            for (key in listOf("wan1", "wan2")) {
                val w = gw.optJSONObject(key) ?: continue
                if (!w.has("rx_bytes-r") && !w.has("rx_bytes")) continue
                wans += WanStats(
                    name = w.optString("name").ifBlank { key.uppercase() },
                    ip = w.optString("ip").ifBlank { null },
                    up = w.optBoolean("up", true),
                    downRate = w.optDouble("rx_bytes-r", 0.0).nz(),
                    upRate = w.optDouble("tx_bytes-r", 0.0).nz(),
                    downBytes = w.optLong("rx_bytes", 0),
                    upBytes = w.optLong("tx_bytes", 0),
                )
            }
            if (wans.isEmpty()) gw.optJSONObject("uplink")?.let { u ->
                wans += WanStats(
                    name = u.optString("name").ifBlank { "WAN" },
                    ip = u.optString("ip").ifBlank { null },
                    up = u.optBoolean("up", true),
                    downRate = u.optDouble("rx_bytes-r", 0.0).nz(),
                    upRate = u.optDouble("tx_bytes-r", 0.0).nz(),
                    downBytes = u.optLong("rx_bytes", 0),
                    upBytes = u.optLong("tx_bytes", 0),
                )
            }
        }

        if (wans.isEmpty()) {
            // Rezerva: stat/health, podsustav "wan"
            val health = get("/proxy/network/api/s/$site/stat/health").optJSONArray("data") ?: JSONArray()
            for (i in 0 until health.length()) {
                val h = health.getJSONObject(i)
                if (h.optString("subsystem") == "wan") {
                    wans += WanStats(
                        name = h.optString("isp_name").ifBlank { "WAN" },
                        ip = h.optString("wan_ip").ifBlank { null },
                        up = h.optString("status") == "ok",
                        downRate = h.optDouble("rx_bytes-r", 0.0).nz(),
                        upRate = h.optDouble("tx_bytes-r", 0.0).nz(),
                        downBytes = 0, upBytes = 0,
                    )
                }
            }
        }

        val sys = gw?.optJSONObject("system-stats")
        return GatewayStats(
            name = gw?.optString("name").orEmpty().ifBlank { "Gateway" },
            model = gw?.optString("model").orEmpty(),
            version = gw?.optString("version").orEmpty(),
            cpuPercent = sys?.optString("cpu")?.toDoubleOrNull(),
            memPercent = sys?.optString("mem")?.toDoubleOrNull(),
            temperature = gw?.let { readTemperature(it) },
            uptimeSec = gw?.optLong("uptime", 0) ?: 0,
            clientCount = gw?.optInt("num_sta", -1)?.takeIf { it >= 0 },
            wans = wans,
        )
    }

    private fun readTemperature(d: JSONObject): Double? {
        val arr = d.optJSONArray("temperatures")
        if (arr != null && arr.length() > 0) {
            var best: Double? = null
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val v = t.optDouble("value", Double.NaN)
                if (v.isNaN()) continue
                if (t.optString("name").contains("cpu", true)) return v
                if (best == null) best = v
            }
            return best
        }
        return d.optDouble("general_temperature", Double.NaN).takeIf { !it.isNaN() }
    }

    /** Aktivni klijenti (stat/sta). */
    fun fetchClients(): List<NetClient> {
        val arr = get("/proxy/network/api/s/$site/stat/sta").optJSONArray("data") ?: JSONArray()
        val out = ArrayList<NetClient>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val mac = o.optString("mac")
            // U UniFi API-ju tx = poslano PREMA klijentu (download), rx = primljeno OD klijenta (upload).
            var down = maxOf(o.optDouble("tx_bytes-r", 0.0).nz(), o.optDouble("wired-tx_bytes-r", 0.0).nz())
            var up = maxOf(o.optDouble("rx_bytes-r", 0.0).nz(), o.optDouble("wired-rx_bytes-r", 0.0).nz())
            var downB = maxOf(o.optLong("tx_bytes", 0), o.optLong("wired-tx_bytes", 0))
            var upB = maxOf(o.optLong("rx_bytes", 0), o.optLong("wired-rx_bytes", 0))
            if (cfg.swapClientDirection) {
                down = up.also { up = down }
                downB = upB.also { upB = downB }
            }
            val hostname = o.optString("hostname").ifBlank { null }
            val vendor = o.optString("oui").ifBlank { null }
            out += NetClient(
                mac = mac,
                ip = o.optString("ip").ifBlank { o.optString("last_ip").ifBlank { null } },
                name = o.optString("name").ifBlank { null } ?: hostname ?: vendor ?: mac,
                hostname = hostname,
                vendor = vendor,
                isWired = o.optBoolean("is_wired", false),
                network = o.optString("network").ifBlank { null },
                essid = o.optString("essid").ifBlank { null },
                signal = if (o.has("signal")) o.optInt("signal") else null,
                downRate = down,
                upRate = up,
                downBytes = downB,
                upBytes = upB,
                uptimeSec = o.optLong("uptime", 0),
            )
        }
        return out
    }

    private fun Double.nz() = if (isNaN() || this < 0) 0.0 else this

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val GATEWAY_TYPES = setOf("udm", "ugw", "uxg", "ucg")
    }
}
