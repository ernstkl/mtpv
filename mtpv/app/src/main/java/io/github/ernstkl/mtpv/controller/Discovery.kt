package io.github.ernstkl.mtpv.controller

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import io.github.ernstkl.mtpv.net.Endpoint
import io.github.ernstkl.mtpv.net.Protocol
import java.net.InetAddress
import java.net.Inet4Address

/**
 * Finds secondaries via mDNS/DNS-SD. [onFound] runs on the main thread, also when an address changes.
 * Android 14 watches each service and reports address changes by itself. Older versions resolve one
 * service at a time, once; [refresh] resolves them all again, e.g. when a secondary stops answering.
 */
class Discovery(
    context: Context,
    private val onFound: (uuid: String, name: String, endpoint: Endpoint) -> Unit,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val executor = context.mainExecutor
    private var running = false

    /** Android 14: one callback per service, by service name. */
    private val callbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()

    /** Older versions: the services seen, and those waiting to be resolved (one at a time). */
    private val found = mutableMapOf<String, NsdServiceInfo>()
    private val queue = ArrayDeque<String>()
    private var resolving = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, code: Int) {
            Log.w(TAG, "discovery failed: $code")
        }
        override fun onStopDiscoveryFailed(serviceType: String, code: Int) = Unit

        override fun onServiceFound(info: NsdServiceInfo) {
            executor.execute { if (watching) watch(info) else enqueue(info) }
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            executor.execute {
                if (watching) unwatch(info.serviceName) else found.remove(info.serviceName)
            }
        }
    }

    fun start() {
        nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        running = true
    }

    fun stop() {
        if (running) runCatching { nsd.stopServiceDiscovery(discovery) }
        running = false
        if (watching) callbacks.keys.toList().forEach(::unwatch)
        found.clear()
        queue.clear()
    }

    /** Resolves all services again (before Android 14; there addresses are kept up to date anyway). */
    fun refresh() {
        if (!running || watching) return
        found.values.toList().forEach(::enqueue)
    }

    private fun report(info: NsdServiceInfo, addr: InetAddress?) {
        val uuid = info.attributes["uuid"]?.decodeToString() ?: return
        val name = info.attributes["name"]?.decodeToString() ?: info.serviceName
        val host = addr?.hostAddress ?: return
        onFound(uuid, name, Endpoint(host, info.port))
    }

    // --- Android 14: watch each service ---

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun watch(info: NsdServiceInfo) {
        if (!running || info.serviceName in callbacks) return
        val cb = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(si: NsdServiceInfo) {
                report(si, si.hostAddresses.firstOrNull { it is Inet4Address } ?: si.hostAddresses.firstOrNull())
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

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun unwatch(serviceName: String) {
        callbacks.remove(serviceName)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
    }

    // --- older versions: resolve once, one service at a time ---

    private fun enqueue(info: NsdServiceInfo) {
        if (!running) return
        found[info.serviceName] = info
        if (info.serviceName !in queue) queue.addLast(info.serviceName)
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || !running) return
        var info: NsdServiceInfo? = null
        while (info == null) info = found[queue.removeFirstOrNull() ?: return]
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onServiceResolved(si: NsdServiceInfo) {
                executor.execute {
                    if (running && si.serviceName in found) report(si, si.host)
                    done()
                }
            }

            override fun onResolveFailed(si: NsdServiceInfo, code: Int) {
                Log.w(TAG, "resolve failed: $code")
                executor.execute(::done)
            }
        })
    }

    private fun done() {
        resolving = false
        resolveNext()
    }

    companion object {
        private const val TAG = "MtpvDiscovery"

        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private val watching = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }
}
