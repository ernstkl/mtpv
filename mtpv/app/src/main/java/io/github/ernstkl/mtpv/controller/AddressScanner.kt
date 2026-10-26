package io.github.ernstkl.mtpv.controller

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.ernstkl.mtpv.core.AddressScan
import io.github.ernstkl.mtpv.net.Endpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Finds secondaries where mDNS does not: probes GET /v1/info on every address of the local networks
 * ([AddressScan]). Interfaces are read directly, because a Wi-Fi Direct group or the own hotspot is
 * not a network that ConnectivityManager lists. Takes about two seconds for a /24.
 */
class AddressScanner(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val probes = Dispatchers.IO.limitedParallelism(PARALLEL)

    /** Calls [onFound] for each secondary that answers on [port], from a background thread. */
    suspend fun scan(port: Int, onFound: suspend (SecondaryClient.Info, Endpoint) -> Unit) {
        val hosts = AddressScan.candidates(locals()).map(AddressScan::format)
        coroutineScope {
            hosts.forEach { host ->
                launch(probes) {
                    val ep = Endpoint(host, port)
                    val info = runCatching { SecondaryClient(ep).info(probe = true) }.getOrNull() ?: return@launch
                    onFound(info, ep)
                }
            }
        }
    }

    /** Own IPv4 addresses with their prefix length, on interfaces that are up and not mobile data. */
    private fun locals(): List<Pair<Int, Int>> {
        val mobile = mobileInterfaces()
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        return ifaces.filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) && it.name !in mobile }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { ia -> (ia.address as? Inet4Address)?.let { AddressScan.fromBytes(it.address) to ia.networkPrefixLength.toInt() } }
    }

    @Suppress("DEPRECATION") // allNetworks: the replacement needs a callback; one look is enough here.
    private fun mobileInterfaces(): Set<String> = runCatching {
        connectivity.allNetworks.filter { n ->
            connectivity.getNetworkCapabilities(n)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        }.mapNotNull { connectivity.getLinkProperties(it)?.interfaceName }.toSet()
    }.getOrDefault(emptySet())

    companion object {
        private const val PARALLEL = 64
    }
}
