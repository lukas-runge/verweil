package de.lukasrunge.verweil.tracking

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import de.lukasrunge.verweil.core.model.WifiScan
import java.security.MessageDigest

/**
 * Hands Wi-Fi scan results to the engine as salted BSSID hashes. Raw BSSIDs never leave this class.
 * Mostly reads the scans the system runs anyway; see [requestScanIfDue] for the rest.
 */
class WifiScanner(
    private val context: Context,
    private val salt: String,
    private val onScan: (WifiScan) -> Unit,
) {
    private val wifi = context.getSystemService(WifiManager::class.java)
    private var lastRequestMs = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // False when the scan failed or was throttled and the list only holds old results.
            if (intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)) deliver()
        }
    }

    fun start() {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun stop() {
        context.unregisterReceiver(receiver)
    }

    /**
     * Asks for a scan at most every 30 minutes while not moving: Android's own limit for background apps,
     * so this never uses more than the system allows anyway.
     */
    @Suppress("DEPRECATION") // Deprecated without replacement; still the only way to ask for a scan.
    fun requestScanIfDue(nowMs: Long) {
        if (nowMs - lastRequestMs < SCAN_INTERVAL_MS) return
        lastRequestMs = nowMs
        wifi.startScan()
    }

    @SuppressLint("MissingPermission") // Fine location is checked before tracking starts.
    private fun deliver() {
        val results = try {
            wifi.scanResults
        } catch (_: SecurityException) {
            return
        }
        // The list may still hold access points from earlier scans; only fresh ones describe where we are.
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1_000
        val bssids = results.filter { nowUs - it.timestamp <= FRESH_US }.mapNotNull { it.BSSID?.let(::hash) }.toSet()
        onScan(WifiScan(System.currentTimeMillis(), bssids))
    }

    private fun hash(bssid: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + bssid.lowercase()).toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SCAN_INTERVAL_MS = 30 * 60_000L
        const val FRESH_US = 2 * 60_000_000L
    }
}
