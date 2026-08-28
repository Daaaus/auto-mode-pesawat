package id.autoair.app.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import id.autoair.app.config.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Pemeriksaan kesehatan koneksi.
 *
 * Skrip asli hanya melakukan `ping`. Di sini digabung tiga pemeriksaan:
 *  - transport: ada jalur seluler yang hidup
 *  - reachability: internet benar-benar jalan (HTTP 204 / ICMP)
 *  - IP match: IP publik sisi SELULER sesuai hasil inject
 *
 * Penting soal VPN: koneksi biasa akan mengikuti jaringan default, dan saat VPN
 * aktif jaringan default adalah VPN. Membaca IP lewat jalur itu hanya
 * mengembalikan IP server VPN yang tidak pernah berubah, sehingga pergantian IP
 * oleh operator tidak akan pernah terdeteksi. Karena itu pengecekan IP diikat
 * langsung ke Network seluler agar menembus VPN.
 */
class ConnectivityProbe(
    private val context: Context,
    private val config: ConfigStore
) {

    sealed class Result {
        object Healthy : Result()
        data class NoInternet(val detail: String) : Result()
        data class IpMismatch(val actual: String, val expected: String) : Result()
        /** IP tidak terbaca, tetapi internet jalan. Tidak memicu refresh. */
        data class IpUnknown(val detail: String) : Result()
    }

    private val cm: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Network seluler mentah, terlepas dari VPN yang sedang aktif. */
    private fun cellularNetwork(): Network? {
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) return n
        }
        return null
    }

    private fun vpnActive(): Boolean = cm.allNetworks.any { n ->
        cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    /** Ada transport seluler atau VPN yang membawa internet. */
    fun hasUsableTransport(): Boolean {
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            val relevant =
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            if (relevant && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return true
            }
        }
        return false
    }

    fun dataStateSummary(): String {
        val active = cm.activeNetwork ?: return "tidak ada jaringan aktif"
        val caps = cm.getNetworkCapabilities(active) ?: return "kapabilitas tidak diketahui"
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "seluler"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            else -> "lain"
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val vpn = if (kind != "VPN" && vpnActive()) " + VPN" else ""
        return (if (validated) "$kind tervalidasi" else "$kind belum tervalidasi") + vpn
    }

    /** Satu siklus pemeriksaan penuh. */
    suspend fun check(): Result = withContext(Dispatchers.IO) {
        val timeoutMs = config.timeoutSec * 1000

        val cell = cellularNetwork()
        if (cell == null && !hasUsableTransport()) {
            return@withContext Result.NoInternet("tidak ada transport seluler/VPN")
        }

        // Reachability lewat jalur default (mewakili pemakaian nyata, termasuk VPN).
        val targets = config.pingTargets.split(" ", ",")
            .map { it.trim() }.filter { it.isNotEmpty() }
        var reachable = false
        var lastDetail = "tidak ada target"
        for (host in targets) {
            val err = reach(host, timeoutMs, null)
            if (err == null) {
                reachable = true
                break
            }
            lastDetail = "$host: $err"
        }

        if (!reachable) {
            // Bedakan: VPN putus tetapi seluler sehat, atau memang seluler mati.
            if (cell != null && reach(targets.firstOrNull() ?: "www.gstatic.com", timeoutMs, cell) == null) {
                return@withContext Result.NoInternet("jalur default mati walau seluler hidup ($lastDetail)")
            }
            return@withContext Result.NoInternet(lastDetail)
        }

        val expected = config.expectedIp
        if (expected.isBlank()) {
            return@withContext Result.Healthy
        }

        // Inti perbaikan: ukur IP di sisi seluler, bukan lewat terowongan VPN.
        val measureOn = if (config.checkIpOnCellular) cell else null
        if (config.checkIpOnCellular && cell == null) {
            return@withContext Result.IpUnknown("network seluler tidak ditemukan")
        }

        val actual = fetchPublicIp(timeoutMs, measureOn)
            ?: return@withContext Result.IpUnknown(
                if (measureOn != null) "gagal membaca IP seluler (VPN always-on memblokir?)"
                else "gagal membaca IP publik"
            )

        return@withContext if (ipMatches(actual, expected)) {
            Result.Healthy
        } else {
            Result.IpMismatch(actual, expected)
        }
    }

    /**
     * @param network jika diisi, koneksi diikat ke network itu (menembus VPN).
     * @return null jika sukses, atau pesan error.
     */
    private fun reach(host: String, timeoutMs: Int, network: Network?): String? {
        val isGenerate204 = !host.startsWith("http")
        return try {
            val url = if (isGenerate204) "https://$host/generate_204" else host
            val u = URL(url)
            val conn = (network?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            // Redirect TIDAK diikuti: pengalihan justru penanda captive portal.
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.useCaches = false

            val code = conn.responseCode
            // Endpoint generate_204 harus menjawab 204 tanpa isi. Operator yang
            // mengalihkan ke halaman kuota/isi-ulang akan membalas 200 atau 30x,
            // dan itu berarti internet TIDAK benar-benar jalan.
            val verdict: String? = if (isGenerate204) {
                when {
                    code == 204 -> {
                        val len = conn.contentLength
                        if (len > 0) "captive portal (204 tapi ada isi $len B)" else null
                    }
                    code in 300..399 -> {
                        val loc = conn.getHeaderField("Location") ?: "?"
                        "captive portal (HTTP $code -> ${loc.take(60)})"
                    }
                    code == 200 -> "captive portal (HTTP 200, harusnya 204)"
                    else -> "HTTP $code"
                }
            } else {
                if (code in 200..299) null else "HTTP $code"
            }
            conn.disconnect()
            verdict
        } catch (e: IOException) {
            e.message ?: "IO error"
        } catch (e: Exception) {
            e.message ?: "error"
        }
    }

    private fun fetchPublicIp(timeoutMs: Int, network: Network?): String? = try {
        val u = URL(config.ipEchoUrl)
        val conn = (network?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.useCaches = false
        val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
        conn.disconnect()
        body.takeIf { it.isNotEmpty() && it.length <= 45 && it.any { c -> c == '.' || c == ':' } }
    } catch (_: Exception) {
        null
    }

    /** Cocok jika sama persis atau termuat dalam salah satu prefix CIDR. */
    private fun ipMatches(actual: String, expectedSpec: String): Boolean {
        val entries = expectedSpec.split(" ", ",").map { it.trim() }.filter { it.isNotEmpty() }
        for (e in entries) {
            if (!e.contains("/")) {
                if (e.equals(actual, ignoreCase = true)) return true
                continue
            }
            if (cidrContains(e, actual)) return true
        }
        return false
    }

    private fun cidrContains(cidr: String, ip: String): Boolean = try {
        val (netPart, bitsPart) = cidr.split("/")
        val bits = bitsPart.toInt()
        val net = InetAddress.getByName(netPart).address
        val addr = InetAddress.getByName(ip).address
        if (net.size != addr.size) {
            false
        } else {
            var matched = true
            var remaining = bits
            for (i in net.indices) {
                if (remaining <= 0) break
                val take = minOf(8, remaining)
                val mask = (0xFF shl (8 - take)) and 0xFF
                if ((net[i].toInt() and mask) != (addr[i].toInt() and mask)) {
                    matched = false
                    break
                }
                remaining -= take
            }
            matched
        }
    } catch (_: Exception) {
        false
    }
}
