package app.pagedrop.transfer.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** An SSH server found on the local network. */
data class DiscoveredDevice(
    val host: String,
    val port: Int,
    val banner: String,
    val looksLikeKindle: Boolean
)

sealed interface ScanEvent {
    data class Progress(val done: Int, val total: Int) : ScanEvent
    data class Found(val device: DiscoveredDevice) : ScanEvent
}

/**
 * "Find my Kindle": probes the phone's Wi-Fi subnet (capped at a /22) for the KOReader (2222) and USBNetwork (22)
 * SSH ports and reads the SSH banner. Short timeouts and bounded concurrency keep a full scan
 * to a few seconds without flooding the network.
 */
@Singleton
class KindleDiscovery @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** The phone's IPv4 address on Wi-Fi (or Ethernet), or null when not on such a network. */
    suspend fun localIpv4(): String? = withContext(Dispatchers.IO) { wifiNetwork()?.ip }

    fun scan(
        ports: List<Int> = DEFAULT_PORTS,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
        concurrency: Int = CONCURRENCY
    ): Flow<ScanEvent> = channelFlow {
        val found = wifiNetwork() ?: return@channelFlow
        val network = found.network
        val targets = subnetHosts(found.ip, found.prefixLength)
        val total = targets.size
        val done = AtomicInteger(0)
        val gate = Semaphore(concurrency)
        send(ScanEvent.Progress(0, total))
        for (host in targets) {
            launch {
                gate.withPermit {
                    for (port in ports) {
                        probe(network, host, port, connectTimeoutFor(port, connectTimeoutMs), readTimeoutMs)
                            ?.let { send(ScanEvent.Found(it)) }
                    }
                }
                val n = done.incrementAndGet()
                if (n % 8 == 0 || n == total) send(ScanEvent.Progress(n, total))
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun probe(network: Network?, host: String, port: Int, connectMs: Int, readMs: Int): DiscoveredDevice? {
        val socket = try {
            network?.socketFactory?.createSocket() ?: Socket()
        } catch (_: Exception) {
            Socket()
        }
        return try {
            socket.use {
                it.connect(InetSocketAddress(host, port), connectMs)
                it.soTimeout = readMs
                val banner = try {
                    it.getInputStream().bufferedReader().readLine().orEmpty().take(120)
                } catch (_: Exception) {
                    ""
                }
                DiscoveredDevice(host, port, banner, looksLikeKindle(banner))
            }
        } catch (_: Exception) {
            null
        }
    }

    private class LocalNetwork(val network: Network, val ip: String, val prefixLength: Int)

    /** Only Wi-Fi or Ethernet networks qualify; cellular/VPN are never scanned. */
    @Suppress("DEPRECATION")
    private fun wifiNetwork(): LocalNetwork? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return try {
            cm.allNetworks.filter {
                val caps = cm.getNetworkCapabilities(it)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            }.firstNotNullOfOrNull { net ->
                cm.getLinkProperties(net)?.linkAddresses
                    ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                    ?.let { la -> la.address.hostAddress?.let { LocalNetwork(net, it, la.prefixLength) } }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine Wi-Fi address: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "KindleDiscovery"
        val DEFAULT_PORTS = listOf(2222, 22)
        const val CONNECT_TIMEOUT_MS = 500
        /**
         * KOReader's port gets longer: a Kindle whose Wi-Fi has been idle took up to ~1.6 s to accept a
         * connection in testing, so 500 ms often missed it. Other ports keep the short timeout to keep scans quick.
         */
        const val KOREADER_CONNECT_TIMEOUT_MS = 2_500
        const val KOREADER_PORT = 2222

        /** Connect timeout for [port]: never shorter than [base], longer for KOReader's SSH port. */
        fun connectTimeoutFor(port: Int, base: Int = CONNECT_TIMEOUT_MS): Int =
            if (port == KOREADER_PORT) maxOf(base, KOREADER_CONNECT_TIMEOUT_MS) else base
        const val READ_TIMEOUT_MS = 700
        const val CONCURRENCY = 64

        /** KOReader's SSH server is dropbear, so either word suggests a Kindle running KOReader. */
        fun looksLikeKindle(banner: String): Boolean =
            banner.contains("koreader", ignoreCase = true) || banner.contains("dropbear", ignoreCase = true)

        /** Widest range scanned: a /22 is 1022 hosts. */
        const val MIN_PREFIX = 22

        /**
         * All other usable hosts in the subnet containing [ip] with the given [prefixLength],
         * capped at a /22 window around [ip]. Never smaller than the real subnet (except that
         * a subnet wider than /22 is clipped). Empty for a malformed address or /31-/32.
         */
        fun subnetHosts(ip: String, prefixLength: Int = 24): List<String> {
            val parts = ip.split(".")
            if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return emptyList()
            val prefix = prefixLength.coerceIn(0, 32).coerceAtLeast(MIN_PREFIX)
            if (prefix >= 31) return emptyList()
            val addr = parts.fold(0L) { acc, p -> (acc shl 8) or p.toLong() }
            val hostBits = 32 - prefix
            val network = addr and ((1L shl hostBits) - 1).inv() and 0xFFFFFFFFL
            val broadcast = network + (1L shl hostBits) - 1
            return (network + 1 until broadcast).filter { it != addr }.map {
                "${it shr 24 and 0xFF}.${it shr 16 and 0xFF}.${it shr 8 and 0xFF}.${it and 0xFF}"
            }
        }
    }
}
