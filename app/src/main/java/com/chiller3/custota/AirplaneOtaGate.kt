/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Airplane-mode-across-OTA gate (BenOS).
 *
 * When a user is updating from a build (especially stock) where they have working
 * play integrity / wallet, there is a gap between first boot and the BenOS-installed
 * modules taking affect.  This may not be an issue with the new install process vs
 * when users were told to uninstall modules to avoid conflicts.... but idk... whatever, 
 * this a safety net more than anything.  Even if it ends up being pointless, less people
 * complaining is always worth the work lmao. 
 *
 */
package com.chiller3.custota.updater

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.chiller3.custota.backup.PrivilegedFs

object AirplaneOtaGate {
    private val TAG = AirplaneOtaGate::class.java.simpleName

    private val PROTECTED_DISPLAY_IDS: Set<String> = setOf(
        // "BenOS-Q25-2025.11.03",
        // "BenOS-Q25-2025.12.14",
   	   "BenOS-Valarie_stockInstaller",
	   "BenOS-Valarie_umaInstaller",
    )

    private const val ABORT_REBOOT_ON_TIMEOUT = true

    private const val POLL_INTERVAL_MS = 100L
    private const val POLL_TIMEOUT_MS = 8_000L
    private const val WAKELOCK_TIMEOUT_MS = 15_000L

    // SettingsProvider serialises each row as a self-closing <setting .../> element with
    // attribute order id, name, value, ... so name precedes value within one element and
    // [^>]* cannot cross the element boundary. airplane_mode_on is always a short int, so
    // it is always stored as the value attribute (never as child text).
    private val AIRPLANE_ON_REGEX =
        Regex("""name="airplane_mode_on"[^>]*\bvalue="1"""")

    fun armForRebootIfProtected(context: Context): Boolean {
        val sourceId = Build.DISPLAY
        if (sourceId !in PROTECTED_DISPLAY_IDS) {
            Log.d(TAG, "Source build \"$sourceId\" not in protected set; radios untouched")
            return true
        }

        Log.i(TAG, "Source build \"$sourceId\" is protected; arming airplane before OTA reboot")

        val pm = context.getSystemService(PowerManager::class.java)
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:arm")
        wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
        try {
            val wrote = try {
                Settings.Global.putInt(
                    context.contentResolver,
                    Settings.Global.AIRPLANE_MODE_ON,
                    1,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Settings.Global.putInt(AIRPLANE_MODE_ON, 1) threw", e)
                false
            }
            if (!wrote) {
                Log.e(TAG, "SettingsProvider rejected the airplane write")
                return !ABORT_REBOOT_ON_TIMEOUT
            }

            if (pollUntilPersisted(context)) {
                Log.i(TAG, "airplane_mode_on=1 confirmed on disk; reboot is safe")
                return true
            }

            Log.e(TAG, "airplane_mode_on flush NOT confirmed within ${POLL_TIMEOUT_MS}ms")
            return !ABORT_REBOOT_ON_TIMEOUT
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }

    // Verify that setting has actually been written before reboot 
    private fun pollUntilPersisted(context: Context): Boolean {
        val fs = PrivilegedFs()
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            val xml = try {
                fs.readSettingsGlobal()
            } catch (e: Exception) {
                Log.w(TAG, "benbackupd settings read failed (attempt $attempt): ${e.message}")
                null
            }
            if (xml != null && AIRPLANE_ON_REGEX.containsMatchIn(xml)) {
                Log.d(TAG, "Flush confirmed after $attempt read(s)")
                return true
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }
}
