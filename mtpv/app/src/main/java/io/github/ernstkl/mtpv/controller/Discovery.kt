package io.github.ernstkl.mtpv.controller

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import io.github.ernstkl.mtpv.net.Endpoint
import io.github.ernstkl.mtpv.net.Protocol
import java.net.Inet4Address

/** Finds secondaries via mDNS/DNS-SD. [onFound] runs on the main thread, also when an address changes. */
class Discovery(
    context: Context,
    private val onFound: (uuid: String, name: String, endpoint: Endpoint) -> Unit,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val executor = context.mainExecutor
    private val callbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()
    private var running = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, code: Int) {
            Log.w(TAG, "discovery failed: $code")
        }
        override fun onStopDiscoveryFailed(serviceType: String, code: Int) = Unit

        override fun onServiceFound(info: NsdServiceInfo) {
            executor.execute { watch(info) }
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            executor.execute { unwatch(info.serviceName) }
        }
    }

    fun start() {
        nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        running = true
    }

    fun stop() {
        if (running) runCatching { nsd.stopServiceDiscovery(discovery) }
        running = false
        callbacks.keys.toList().forEach(::unwatch)
    }

    private fun watch(info: NsdServiceInfo) {
        if (!running || info.serviceName in callbacks) return
        val cb = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(si: NsdServiceInfo) {
                val uuid = si.attributes["uuid"]?.decodeToString() ?: return
                val name = si.attributes["name"]?.decodeToString() ?: si.serviceName
                val addr = si.hostAddresses.firstOrNull { it is Inet4Address } ?: si.hostAddresses.firstOrNull() ?: return
                val host = addr.hostAddress ?: return
                onFound(uuid, name, Endpoint(host, si.port))
            }

            override fun onServiceLost() = Unit
            override fun onServiceInfoCallbackRegistrationFailed(code: Int) {
                Log.w(TAG, "resolve failed: $code")
            }
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        callbacks[info.serviceName] = cb
        nsd.registerServiceInfoCallback(info, executor, cb)
    }

    private fun unwatch(serviceName: String) {
        callbacks.remove(serviceName)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
    }

    companion object {
        private const val TAG = "MtpvDiscovery"
    }
}
