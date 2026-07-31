package com.hippo.ehviewer.network

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.Settings

/**
 * Centralized network state manager that monitors connectivity changes
 * and notifies registered components. Coordinates connection pool eviction,
 * download pause/resume, and auto-recovery after network restoration.
 */
@SuppressLint("StaticFieldLeak")
object NetworkStateManager {
    private const val TAG = "NetworkStateManager"

    enum class State {
        ONLINE_WIFI,
        ONLINE_METERED,
        OFFLINE,
        TRANSITIONING
    }

    interface Listener {
        fun onNetworkStateChanged(newState: State) {}
        fun onNetworkLost() {}
        fun onNetworkRecovered() {}
    }

    @Volatile
    var currentState: State = State.ONLINE_WIFI
        private set

    @Volatile
    var isVpnActive: Boolean = false
        private set

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetwork: Network? = null
    private var appContext: Application? = null
    private val listeners = mutableListOf<Listener>()
    private val handler = Handler(Looper.getMainLooper())
    private var initialized = false
    private var firstNetworkDetected = false
    private val pendingRecovery = Runnable { checkRecovery() }

    fun isOnline(): Boolean = currentState != State.OFFLINE

    fun isMetered(): Boolean = currentState == State.ONLINE_METERED

    /**
     * Actively scan ALL networks for VPN transport.
     * This is more reliable than just checking isVpnActive flag.
     */
    fun isVpnConnected(): Boolean {
        val cm = connectivityManager ?: return false
        return try {
            // Check all networks for VPN
            @Suppress("DEPRECATION")
            val networks = cm.allNetworks
            for (network in networks) {
                val capabilities = cm.getNetworkCapabilities(network)
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return true
                }
            }
            // Also check active network as fallback
            val activeNetwork = cm.activeNetwork
            if (activeNetwork != null) {
                val capabilities = cm.getNetworkCapabilities(activeNetwork)
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "VPN check failed", e)
            false
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun init(application: Application) {
        // Double guard: prevent re-registration even if initialized flag corrupts
        if (initialized || connectivityManager != null) return
        initialized = true
        appContext = application

        connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.i(TAG, "Network available: $network")
                handleNetworkAvailable(network)

                // Check if this new network is VPN
                try {
                    val capabilities = connectivityManager?.getNetworkCapabilities(network)
                    if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        if (!isVpnActive) {
                            isVpnActive = true
                            Log.i(TAG, "VPN network detected onAvailable")
                            NetworkLogger.logBackground("NetworkStateManager: VPN network detected")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking VPN on available", e)
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.w(TAG, "Network lost: $network")
                handleNetworkLost(network)

                // Rescan all networks for VPN after network loss
                val vpnStillActive = isVpnConnected()
                if (isVpnActive != vpnStillActive) {
                    isVpnActive = vpnStillActive
                    Log.i(TAG, "VPN state after network lost: $vpnStillActive")
                    NetworkLogger.logBackground("NetworkStateManager: VPN state after network lost: $vpnStillActive")
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                super.onCapabilitiesChanged(network, capabilities)
                val isUnmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val hasVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                Log.i(TAG, "Network caps: network=$network, unmetered=$isUnmetered, internet=$hasInternet, validated=$isValidated, vpn=$hasVpn")

                // Detect VPN state change and update proxy selector
                val currentVpnState = isVpnConnected()
                if (currentVpnState != isVpnActive) {
                    isVpnActive = currentVpnState
                    Log.i(TAG, "VPN state changed: active=$currentVpnState")
                    NetworkLogger.logBackground("NetworkStateManager: VPN state changed: active=$currentVpnState")
                }

                // Only transition when capabilities are fully validated,
                // and skip the initial discovery phase to avoid false transitions
                if (hasInternet && isValidated) {
                    val newState = if (isUnmetered) State.ONLINE_WIFI else State.ONLINE_METERED

                    // Only trigger network switch logic if we already had a stable network
                    if (firstNetworkDetected && lastNetwork != null && lastNetwork != network) {
                        Log.i(TAG, "Real network switch detected, flushing connections")
                        NetworkLogger.logBackground("NetworkStateManager: real network switch, flushing connections")
                        flushConnections()
                    }

                    transitionTo(newState)
                    firstNetworkDetected = true
                } else if (!hasInternet && firstNetworkDetected) {
                    transitionTo(State.OFFLINE)
                }
                lastNetwork = network
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
            Log.i(TAG, "Network callback registered")
            NetworkLogger.logBackground("NetworkStateManager: callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    fun destroy() {
        initialized = false
        firstNetworkDetected = false
        handler.removeCallbacksAndMessages(null)
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network callback", e)
        }
        networkCallback = null
        connectivityManager = null
        lastNetwork = null
        appContext = null
        isVpnActive = false
        synchronized(listeners) { listeners.clear() }
    }

    private fun handleNetworkAvailable(network: Network) {
        // Only trigger a switch if we already had a stable network and this is a new one
        if (firstNetworkDetected && lastNetwork != null && lastNetwork != network) {
            Log.i(TAG, "New network available after switch, flushing connections")
            flushConnections()
        }

        lastNetwork = network
    }

    private fun handleNetworkLost(network: Network) {
        if (lastNetwork == network) {
            lastNetwork = null
        }
        handler.removeCallbacks(pendingRecovery)
        handler.postDelayed(pendingRecovery, 500L)
    }

    private fun checkRecovery() {
        val activeNetwork = connectivityManager?.activeNetwork
        if (activeNetwork == null) {
            transitionTo(State.OFFLINE)
            notifyNetworkLost()
        }
    }

    private fun flushConnections() {
        try {
            EhApplication.onNetworkChanged()
            Log.i(TAG, "Connections flushed for network switch")
            NetworkLogger.logBackground("NetworkStateManager: connections flushed after network switch")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush connections", e)
        }
    }

    private fun transitionTo(newState: State) {
        if (currentState == newState) return
        val oldState = currentState
        currentState = newState
        Log.i(TAG, "State transition: $oldState -> $newState")
        NetworkLogger.logBackground("NetworkStateManager: state $oldState -> $newState")

        handler.post {
            synchronized(listeners) {
                listeners.toList()
            }.forEach { listener ->
                try {
                    listener.onNetworkStateChanged(newState)
                } catch (e: Exception) {
                    Log.e(TAG, "Listener error", e)
                }
            }
        }
    }

    private fun notifyNetworkLost() {
        handler.post {
            Log.i(TAG, "Network lost, notifying listeners")
            NetworkLogger.logBackground("NetworkStateManager: network lost")
            synchronized(listeners) {
                listeners.toList()
            }.forEach { listener ->
                try {
                    listener.onNetworkLost()
                } catch (e: Exception) {
                    Log.e(TAG, "Listener onNetworkLost error", e)
                }
            }
        }
    }

    private fun notifyNetworkRecovered() {
        handler.post {
            Log.i(TAG, "Network recovered, notifying listeners")
            NetworkLogger.logBackground("NetworkStateManager: network recovered")
            synchronized(listeners) {
                listeners.toList()
            }.forEach { listener ->
                try {
                    listener.onNetworkRecovered()
                } catch (e: Exception) {
                    Log.e(TAG, "Listener onNetworkRecovered error", e)
                }
            }
        }
    }

    /**
     * Determine whether downloads should pause based on current network
     * state and the user's metered-network policy setting.
     */
    fun shouldPauseDownloads(): Boolean {
        return when (currentState) {
            State.OFFLINE -> true
            State.ONLINE_METERED -> {
                Settings.getMeteredNetworkPolicy() == Settings.METERED_POLICY_PAUSE
            }
            State.ONLINE_WIFI -> false
            State.TRANSITIONING -> true
        }
    }

    fun getPauseReason(): String? {
        return when {
            currentState == State.OFFLINE -> "Network offline"
            currentState == State.TRANSITIONING -> "Network transitioning"
            currentState == State.ONLINE_METERED &&
                Settings.getMeteredNetworkPolicy() == Settings.METERED_POLICY_PAUSE -> "Metered network (paused per settings)"
            else -> null
        }
    }
}
