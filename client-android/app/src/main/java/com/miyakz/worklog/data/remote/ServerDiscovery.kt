package com.miyakz.worklog.data.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "ServerDiscovery"
private const val SERVICE_TYPE = "_worklogapp._tcp."
private const val DISCOVERY_TIMEOUT_MS = 2500L

data class ServerAddress(val host: String, val port: Int)

/**
 * Finds the work-log-app server on the LAN via mDNS/NSD. The server may be
 * powered off or unreachable at any time (it's a home PC) — every call is
 * best-effort and simply returns null on timeout or failure so callers can
 * fall back to local-only operation without surfacing an error.
 */
// NsdServiceInfo.host and the single-address resolveService() overload were
// superseded by hostAddresses/registerServiceInfoCallback in API 34, but
// minSdk is 26 here, so the deprecated APIs are the only ones available.
@Suppress("DEPRECATION")
class ServerDiscovery(private val context: Context) {

    suspend fun discover(): ServerAddress? {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        // Some device/router combinations silently drop mDNS multicast
        // packets without this lock held during discovery.
        val multicastLock = wifiManager?.createMulticastLock("worklog-mdns")?.apply {
            setReferenceCounted(true)
            acquire()
        }

        return try {
            withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                discoverOnce(nsdManager)
            }
        } finally {
            runCatching { multicastLock?.release() }
        }
    }

    private suspend fun discoverOnce(nsdManager: NsdManager): ServerAddress? {
        val result = CompletableDeferred<ServerAddress?>()
        // NsdManager forbids reusing a ResolveListener for a second concurrent
        // resolveService() call, so guard against onServiceFound firing again
        // (e.g. a re-announcement) while the first resolve is still pending.
        val resolveInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve failed: $errorCode")
                result.complete(null)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress
                if (host != null) {
                    result.complete(ServerAddress(host, serviceInfo.port))
                } else {
                    result.complete(null)
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.startsWith(SERVICE_TYPE) &&
                    !result.isCompleted &&
                    resolveInFlight.compareAndSet(false, true)
                ) {
                    nsdManager.resolveService(serviceInfo, resolveListener)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "start discovery failed: $errorCode")
                result.complete(null)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { nsdManager.stopServiceDiscovery(this) }
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        try {
            return result.await()
        } finally {
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        }
    }
}
