package app.droidnux.desktop

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.FileObserver
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import app.droidnux.container.DesktopEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address

/**
 * Android's default network in the desktop's System Monitor
 * ([DesktopEnvironment.writeNetwork]): written when it changes, and with fresh
 * byte counters whenever ksystemstats' network plugin asks, which it does only
 * while something shows them.
 */
class NetworkReporter(private val context: Context, private val rootfs: File, private val scope: CoroutineScope) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    // One write at a time: writeFile goes through one temporary file.
    private val writer = Dispatchers.IO.limitedParallelism(1)

    @Volatile private var network: Network? = null
    @Volatile private var capabilities: NetworkCapabilities? = null
    @Volatile private var link: LinkProperties? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            network = n
            capabilities = caps
            write()
        }

        override fun onLinkPropertiesChanged(n: Network, properties: LinkProperties) {
            network = n
            link = properties
            write()
        }

        override fun onLost(n: Network) {
            if (n != network) return
            network = null
            capabilities = null
            link = null
            write()
        }
    }

    private val requests = object : FileObserver(DesktopEnvironment.networkDir(rootfs), CLOSE_WRITE) {
        override fun onEvent(event: Int, path: String?) {
            if (path == DesktopEnvironment.NETWORK_REQUEST) write()
        }
    }

    fun start() {
        write()
        connectivity.registerDefaultNetworkCallback(callback)
        requests.startWatching()
    }

    fun stop() {
        requests.stopWatching()
        connectivity.unregisterNetworkCallback(callback)
    }

    private fun write() {
        scope.launch(writer) {
            runCatching { DesktopEnvironment.writeNetwork(rootfs, fields()) }.onFailure { Log.w(TAG, "network: $it") }
        }
    }

    private fun fields(): Map<String, Any> {
        val properties = link ?: return emptyMap()
        val name = properties.interfaceName ?: return emptyMap()
        val caps = capabilities
        fun address(v4: Boolean) =
            properties.linkAddresses.firstOrNull { (it.address is Inet4Address) == v4 && !it.address.isLinkLocalAddress }
                ?.toString().orEmpty()
        fun gateway(v4: Boolean) =
            properties.routes.firstOrNull { it.isDefaultRoute && it.hasGateway() && (it.gateway is Inet4Address) == v4 }
                ?.gateway?.hostAddress.orEmpty()
        fun dns(v4: Boolean) =
            properties.dnsServers.filter { (it is Inet4Address) == v4 }.joinToString(" ") { it.hostAddress.orEmpty() }
        val rssi = caps?.signalStrength ?: NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        return linkedMapOf(
            "interface" to name,
            "name" to networkName(caps, name),
            // As NetworkManager shows it: -100 dBm is 0%, -50 dBm and above 100%.
            "signal" to if (wifi && rssi != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) ((rssi + 100) * 2).coerceIn(0, 100) else 0,
            "ipv4" to address(true),
            "ipv6" to address(false),
            "ipv4gateway" to gateway(true),
            "ipv6gateway" to gateway(false),
            "ipv4dns" to dns(true),
            "ipv6dns" to dns(false),
            "received" to (if (Build.VERSION.SDK_INT >= 33) TrafficStats.getRxBytes(name) else TrafficStats.getTotalRxBytes()).coerceAtLeast(0),
            "sent" to (if (Build.VERSION.SDK_INT >= 33) TrafficStats.getTxBytes(name) else TrafficStats.getTotalTxBytes()).coerceAtLeast(0),
            "time" to SystemClock.elapsedRealtime(),
        )
    }

    // The Wi-Fi's name needs the location permission: without it Android gives UNKNOWN_SSID.
    private fun networkName(caps: NetworkCapabilities?, interfaceName: String): String = when {
        caps == null -> interfaceName
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
            (caps.transportInfo as? WifiInfo)?.ssid?.takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID }
                ?.removeSurrounding("\"") ?: "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
            context.getSystemService(TelephonyManager::class.java)?.networkOperatorName?.takeIf { it.isNotEmpty() } ?: interfaceName
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> interfaceName
    }

    private companion object {
        const val TAG = "DroidNux.Network"
    }
}
