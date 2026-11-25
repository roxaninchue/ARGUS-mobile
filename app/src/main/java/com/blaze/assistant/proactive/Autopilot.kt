package com.blaze.assistant.proactive

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import androidx.work.WorkManager

/**
 * v0.33.0 Autopilot Routines: more trigger events, so one sentence ("when I join the campus Wi-Fi, open my timetable")
 * becomes a trigger whose prompt Argus runs on its own:
 * - wifi: the phone joins a Wi-Fi network (match = part of the network name; blank = any). Android hides the network's
 *   name from apps without location access, so on most phones only a blank match (any network) fires.
 * 2026-11-25 permission trim: place_arrive / place_leave (location) and bluetooth (BLUETOOTH_CONNECT) left the app.
 */
object Autopilot {
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        // The places check (a periodic worker) went with location: drop it on phones that still have it queued.
        try { WorkManager.getInstance(app).cancelUniqueWork("autopilot_places") } catch (_: Throwable) { }
        try {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            var lastSsid = ""
            cm?.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val ssid = try { @Suppress("DEPRECATION") app.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid?.trim('"')
                        ?.takeIf { it != "<unknown ssid>" } } catch (_: Exception) { null }.orEmpty()
                    if (ssid.isNotEmpty() && ssid == lastSsid) return
                    lastSsid = ssid
                    Triggers.fireEvent("wifi", ssid, "Joined Wi-Fi ${ssid.ifBlank { "(name hidden by Android)" }}.")
                }
                override fun onLost(network: Network) { lastSsid = "" }
            })
        } catch (_: Exception) { }
    }
}
