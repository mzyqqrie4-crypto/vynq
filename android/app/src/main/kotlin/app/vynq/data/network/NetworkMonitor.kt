package app.vynq.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

enum class Transport { WiFi, Cellular, Ethernet, Vpn, Other }

/** One active network as the OS reports it. All values are live, nothing estimated by vynq. */
data class NetworkSnapshot(
    val id: String,
    val transport: Transport,
    val isDefault: Boolean,
    val validated: Boolean,
    val metered: Boolean,
    val downstreamKbps: Int,
    val upstreamKbps: Int,
    val interfaceName: String?,
)

/**
 * Observes every Internet-capable network, not only the default one. This is the
 * foundation for diagnostics now and for Fusion Mode path selection in Phase 6.
 * The callback is unregistered as soon as the collector goes away, so it does no background work.
 */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    fun observe(): Flow<List<NetworkSnapshot>> = callbackFlow {
        val caps = mutableMapOf<Network, NetworkCapabilities>()
        val links = mutableMapOf<Network, LinkProperties>()
        var default: Network? = null

        fun emit() {
            trySend(caps.map { (net, nc) -> nc.toSnapshot(net, net == default, links[net]) }
                .sortedWith(compareByDescending<NetworkSnapshot> { it.isDefault }.thenBy { it.transport }))
        }

        val all = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) { caps[network] = nc; emit() }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { links[network] = lp; emit() }
            override fun onLost(network: Network) { caps.remove(network); links.remove(network); emit() }
        }
        val def = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { default = network; emit() }
            override fun onLost(network: Network) { if (default == network) default = null; emit() }
        }
        // Callbacks share one ConnectivityThread, so the maps are confined to it. Seed
        // with an empty list; existing networks are replayed right after registration.
        trySend(emptyList())
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(request, all)
        cm.registerDefaultNetworkCallback(def)
        awaitClose {
            cm.unregisterNetworkCallback(all)
            cm.unregisterNetworkCallback(def)
        }
    }.conflate()

    private fun NetworkCapabilities.toSnapshot(net: Network, isDefault: Boolean, lp: LinkProperties?) = NetworkSnapshot(
        id = net.toString(),
        transport = when {
            hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Transport.Vpn
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WiFi
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.Cellular
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.Ethernet
            else -> Transport.Other
        },
        isDefault = isDefault,
        validated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        metered = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        downstreamKbps = linkDownstreamBandwidthKbps,
        upstreamKbps = linkUpstreamBandwidthKbps,
        interfaceName = lp?.interfaceName,
    )
}
