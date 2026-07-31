package com.hippo.ehviewer.network

import android.util.Log
import com.hippo.ehviewer.Settings

/**
 * Centralized manager for network security features (DoH, Domain Fronting)
 * with VPN-aware behavior. Provides runtime checks that consider both user
 * settings and current VPN state.
 */
object NetworkSecurityManager {
    private const val TAG = "NetworkSecurityManager"

    /**
     * VPN aware modes - determines behavior when VPN is detected
     */
    const val VPN_MODE_AUTO_DISABLE = 0  // Automatically disable features when VPN active
    const val VPN_MODE_PROMPT = 1        // Show prompt but keep features enabled
    const val VPN_MODE_IGNORE = 2        // Ignore VPN, keep features based on user settings

    /**
     * Listener for VPN state changes that affect feature availability
     */
    interface VpnFeatureStateListener {
        fun onVpnFeatureStateChanged(dohEnabled: Boolean, dfEnabled: Boolean) {}
    }

    private val listeners = mutableListOf<VpnFeatureStateListener>()
    private var lastVpnState: Boolean = false

    /**
     * Initialize the manager and register for network state changes
     */
    fun init() {
        NetworkStateManager.addListener(object : NetworkStateManager.Listener {
            override fun onNetworkStateChanged(newState: NetworkStateManager.State) {
                checkAndNotifyVpnStateChange()
            }

            override fun onNetworkRecovered() {
                checkAndNotifyVpnStateChange()
            }
        })

        // Initialize with current state using active check
        lastVpnState = isVpnActiveCheck()
        Log.i(TAG, "Initialized with VPN state: $lastVpnState")
    }

    /**
     * Add a listener for VPN feature state changes
     */
    fun addListener(listener: VpnFeatureStateListener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    /**
     * Remove a listener
     */
    fun removeListener(listener: VpnFeatureStateListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    /**
     * Active VPN check - uses NetworkStateManager's comprehensive scanning
     */
    private fun isVpnActiveCheck(): Boolean {
        // Use NetworkStateManager's comprehensive VPN detection
        return NetworkStateManager.isVpnConnected()
    }

    /**
     * Check if DoH should be enabled considering VPN state
     */
    fun isDoHEnabled(): Boolean {
        // First check user setting
        if (!Settings.getDoH()) {
            return false
        }

        // Check VPN aware mode
        val vpnMode = Settings.getVpnAwareMode()
        if (vpnMode == VPN_MODE_IGNORE) {
            return true  // User chose to ignore VPN
        }

        // Active check VPN state - more reliable
        val isVpnActive = isVpnActiveCheck()
        if (isVpnActive && vpnMode == VPN_MODE_AUTO_DISABLE) {
            Log.d(TAG, "DoH disabled: VPN active and mode=auto_disable")
            return false  // Auto disable when VPN active
        }

        return true
    }

    /**
     * Check if Domain Fronting should be enabled considering VPN state
     */
    fun isDomainFrontingEnabled(): Boolean {
        // First check user setting
        if (!Settings.getDF()) {
            return false
        }

        // Check VPN aware mode
        val vpnMode = Settings.getVpnAwareMode()
        if (vpnMode == VPN_MODE_IGNORE) {
            return true  // User chose to ignore VPN
        }

        // Active check VPN state - more reliable
        val isVpnActive = isVpnActiveCheck()
        if (isVpnActive && vpnMode == VPN_MODE_AUTO_DISABLE) {
            Log.d(TAG, "Domain Fronting disabled: VPN active and mode=auto_disable")
            return false  // Auto disable when VPN active
        }

        return true
    }

    /**
     * Check if VPN is currently active
     */
    fun isVpnActive(): Boolean {
        return isVpnActiveCheck()
    }

    /**
     * Get current VPN aware mode
     */
    fun getVpnAwareMode(): Int {
        return Settings.getVpnAwareMode()
    }

    /**
     * Get a summary of current feature states for UI display
     */
    fun getFeatureStatesSummary(): FeatureStates {
        val isVpnActive = isVpnActiveCheck()
        val vpnMode = Settings.getVpnAwareMode()
        val dohUserEnabled = Settings.getDoH()
        val dfUserEnabled = Settings.getDF()

        val dohEffective = isDoHEnabled()
        val dfEffective = isDomainFrontingEnabled()

        return FeatureStates(
            isVpnActive = isVpnActive,
            vpnAwareMode = vpnMode,
            dohUserEnabled = dohUserEnabled,
            dfUserEnabled = dfUserEnabled,
            dohEffective = dohEffective,
            dfEffective = dfEffective,
            dohDisabledByVpn = dohUserEnabled && !dohEffective,
            dfDisabledByVpn = dfUserEnabled && !dfEffective
        )
    }

    /**
     * Check if VPN state changed and notify listeners
     */
    private fun checkAndNotifyVpnStateChange() {
        val currentVpnState = isVpnActiveCheck()
        if (currentVpnState != lastVpnState) {
            lastVpnState = currentVpnState
            Log.i(TAG, "VPN state changed: active=$currentVpnState")
            NetworkLogger.logBackground("NetworkSecurityManager: VPN state changed: active=$currentVpnState")
            notifyVpnFeatureStateChanged()
        }
    }

    /**
     * Notify listeners of feature state changes
     */
    private fun notifyVpnFeatureStateChanged() {
        val dohEnabled = isDoHEnabled()
        val dfEnabled = isDomainFrontingEnabled()

        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            try {
                listener.onVpnFeatureStateChanged(dohEnabled, dfEnabled)
            } catch (e: Exception) {
                Log.e(TAG, "Listener error", e)
            }
        }
    }

    /**
     * Data class holding feature states for UI display
     */
    data class FeatureStates(
        val isVpnActive: Boolean,
        val vpnAwareMode: Int,
        val dohUserEnabled: Boolean,
        val dfUserEnabled: Boolean,
        val dohEffective: Boolean,
        val dfEffective: Boolean,
        val dohDisabledByVpn: Boolean,
        val dfDisabledByVpn: Boolean
    )
}
