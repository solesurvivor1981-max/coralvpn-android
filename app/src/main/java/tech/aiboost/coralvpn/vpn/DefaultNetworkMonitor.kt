package tech.aiboost.coralvpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.system.OsConstants
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import java.net.Inet6Address
import java.net.InterfaceAddress
import java.net.NetworkInterface

/**
 * Minimal default-network monitor for libbox's PlatformInterface. Feeds the core the
 * current underlying interface (so outbound sockets bind correctly) and enumerates
 * interfaces. Trimmed from SFA's DefaultNetworkMonitor. Call [init] before the service starts.
 */
object DefaultNetworkMonitor {

    private lateinit var connectivity: ConnectivityManager

    @Volatile
    var defaultNetwork: Network? = null
        private set

    private var listener: InterfaceUpdateListener? = null

    fun init(context: Context) {
        connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            defaultNetwork = network
            notifyListener()
        }

        override fun onLost(network: Network) {
            if (defaultNetwork == network) {
                defaultNetwork = null
                notifyListener()
            }
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivity.requestNetwork(request, callback) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            defaultNetwork = connectivity.activeNetwork
        }
    }

    fun stop() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    fun setListener(l: InterfaceUpdateListener?) {
        listener = l
        notifyListener()
    }

    private fun notifyListener() {
        val l = listener ?: return
        val net = defaultNetwork
        val name = net?.let { connectivity.getLinkProperties(it)?.interfaceName }
        if (name == null) {
            l.updateDefaultInterface("", -1, false, false)
            return
        }
        val index = runCatching { NetworkInterface.getByName(name).index }.getOrDefault(-1)
        l.updateDefaultInterface(name, index, false, false)
    }

    fun getInterfaces(): NetworkInterfaceIterator {
        val result = mutableListOf<LibboxNetworkInterface>()
        val javaIfaces = runCatching { NetworkInterface.getNetworkInterfaces().toList() }
            .getOrDefault(emptyList())
        @Suppress("DEPRECATION")
        for (network in connectivity.allNetworks) {
            val lp = connectivity.getLinkProperties(network) ?: continue
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            val name = lp.interfaceName ?: continue
            val jIface = javaIfaces.find { it.name == name } ?: continue
            val bi = LibboxNetworkInterface()
            bi.name = name
            bi.index = jIface.index
            runCatching { bi.mtu = jIface.mtu }
            bi.type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            bi.dnsServer = StringArray(lp.dnsServers.mapNotNull { it.hostAddress }.iterator())
            bi.addresses = StringArray(jIface.interfaceAddresses.map { it.toPrefix() }.iterator())
            var flags = 0
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (jIface.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
            if (jIface.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
            if (jIface.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
            bi.flags = flags
            bi.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            result.add(bi)
        }
        return InterfaceArray(result.iterator())
    }

    private fun InterfaceAddress.toPrefix(): String =
        if (address is Inet6Address) {
            "${(address as Inet6Address).hostAddress}/$networkPrefixLength"
        } else {
            "${address.hostAddress}/$networkPrefixLength"
        }

    /** StringIterator implementation for handing string lists into libbox. */
    class StringArray(private val it: Iterator<String>) : StringIterator {
        override fun len(): Int = 0
        override fun hasNext(): Boolean = it.hasNext()
        override fun next(): String = it.next()
    }

    private class InterfaceArray(
        private val it: Iterator<LibboxNetworkInterface>,
    ) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = it.hasNext()
        override fun next(): LibboxNetworkInterface = it.next()
    }
}
