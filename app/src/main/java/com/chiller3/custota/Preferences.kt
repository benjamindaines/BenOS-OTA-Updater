/*
 * SPDX-FileCopyrightText: 2023-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.custota

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.UserManager
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class Preferences(initialContext: Context) {
    companion object {
        private val TAG = Preferences::class.java.simpleName

        const val DEFAULT_OTA_SOURCE = "https://ota.dgsd.ph/anish"

        // URL opened by the "Open BenOS Website" button on the update message
        // screen. Edit this to point at the real BenOS website. It can also be
        // overridden at runtime via the [benosWebsiteUrl] setter.
        const val DEFAULT_BENOS_WEBSITE_URL = "https://ds.dgsd.ph/benos"
        const val DEFAULT_BENOS_KOFI_URL = "https://ko-fi.com/ben9412345"

        // --- Beta build expiry configuration ---------------------------------
        //
        // Compile-time constants set when building a time-limited beta (debug)
        // ROM. When [BETA_BUILD] is false the entire mechanism is inert: no
        // banner, no notifications, and no scheduled cross-grade. Runtime
        // resolution lives in [com.chiller3.custota.updater.BetaExpiry].

        // Marks the running build as a time-limited beta. The stable build to
        // which the device is cross-graded ships this as false, which is what
        // makes the operation one-shot: after reboot nothing re-arms.
        const val BETA_BUILD = true

        // Expiry deadline expressed as a local calendar date rather than an
        // absolute Unix timestamp. Resolution against the device's active time
        // zone means the deadline is observed at local time on the given date
        // regardless of the zone the device currently reports.
        const val BETA_EXPIRY_YEAR = 2026
        const val BETA_EXPIRY_MONTH = 9    // 1-12
        const val BETA_EXPIRY_DAY = 11

        // Local hour of day (0-23) at which the cross-grade becomes eligible on
        // the expiry date, and at which the day-before warning is posted. 03:00
        // is selected as a low-activity window: devices are predominantly idle
        // and connected to unmetered networks overnight, which minimizes both
        // the probability of a user-initiated reboot during installation and
        // the risk of consuming a metered data allowance. This value defines
        // only the earliest eligible instant; the job constraints in
        // [com.chiller3.custota.updater.UpdaterJob.scheduleBeta] provide the
        // actual guarantees.
        const val BETA_EXPIRY_INSTALL_HOUR = 3

        // Update source used for the automatic cross-grade. This is the stable
        // (non-debug) branch, and is independent of whatever source the user has
        // configured. A full OTA is served here deliberately: an incremental is
        // keyed to one exact source build, whereas the cross-grade must succeed
        // from any beta respin. Publishing no incremental entry matching a beta's
        // vbmeta digest causes the updater to select the full package.
        const val BETA_FALLBACK_OTA_URL = "https://ota.dgsd.ph/mainline"

        // Days after the expiry deadline before the device is considered overdue
        // and the user is notified that the cross-grade has not yet completed.
        // The cross-grade requires an unmetered network, so a device kept on
        // cellular alone will stall indefinitely without this escalation.
        const val BETA_ESCALATION_GRACE_DAYS = 3L

        // Keep in the same order as the helper functions below.
        private const val PREF_ALREADY_MIGRATED = "already_migrated"
        private const val PREF_DEBUG_MODE = "debug_mode"
        private const val PREF_OTA_SOURCE = "ota_source"
        private const val PREF_ALLOW_CUSTOM_OTA_SOURCE = "allow_custom_ota_source"
        private const val PREF_AUTOMATIC_CHECK = "automatic_check"
        private const val PREF_AUTOMATIC_INSTALL = "automatic_install"
        private const val PREF_UNMETERED_ONLY = "unmetered_only"
        private const val PREF_BATTERY_NOT_LOW = "battery_not_low"
        private const val PREF_SKIP_POSTINSTALL = "skip_postinstall"
        private const val PREF_ALLOW_REINSTALL = "allow_reinstall"
        private const val PREF_CSIG_CERTS = "csig_certs"
        private const val PREF_PIN_NETWORK_ID = "pin_network_id"
        private const val PREF_BENOS_WEBSITE_URL = "benos_website_url"
        private const val PREF_BENOS_KOFI_URL = "benos_kofi_url"
        private const val PREF_OTA_SERVER_URL = "ota_server_url"
        private const val PREF_BETA_CROSSGRADE_STAGED = "beta_crossgrade_staged"
        private const val PREF_CONFLICT_RESOLUTION_COMPLETE = "conflict_resolution_complete"

        private fun migrateToDeviceProtectedStorage(context: Context) {
            synchronized(this) {
                if (context.isDeviceProtectedStorage) {
                    Log.w(TAG, "Cannot migrate without credential-protected storage context")
                    return
                }

                val userManager = context.getSystemService(UserManager::class.java)
                if (!userManager.isUserUnlocked) {
                    Log.w(TAG, "Cannot migrate preferences in BFU state")
                    return
                }

                val deviceContext = context.createDeviceProtectedStorageContext()
                var devicePrefs = PreferenceManager.getDefaultSharedPreferences(deviceContext)

                // getDefaultSharedPreferencesName() is not public, but realistically, Android can't
                // ever change the default shared preferences name without breaking nearly every app.
                val sharedPreferencesName = context.packageName + "_preferences"

                if (devicePrefs.getBoolean(PREF_ALREADY_MIGRATED, false)) {
                    val oldPrefsFile =
                        File(File(context.dataDir, "shared_prefs"), "$sharedPreferencesName.xml")
                    if (!oldPrefsFile.exists()) {
                        Log.i(TAG, "Already migrated preferences to device protected storage")
                        return
                    } else if (devicePrefs.getString(PREF_OTA_SOURCE, null) != null) {
                        Log.i(TAG, "User already reconfigured app following botched migration")
                        context.deleteSharedPreferences(sharedPreferencesName)
                        return
                    } else {
                        Log.i(TAG, "Reattempting migration after regression")
                    }
                }

                Log.i(TAG, "Migrating preferences to device-protected storage")

                // This returns true if the shared preferences didn't exist.
                if (!deviceContext.moveSharedPreferencesFrom(context, sharedPreferencesName)) {
                    Log.e(TAG, "Failed to migrate preferences to device protected storage")
                    return
                }

                devicePrefs = PreferenceManager.getDefaultSharedPreferences(deviceContext)
                devicePrefs.edit { putBoolean(PREF_ALREADY_MIGRATED, true) }
            }
        }
    }

    init {
        migrateToDeviceProtectedStorage(initialContext)
    }

    private val context = if (initialContext.isDeviceProtectedStorage) {
        initialContext
    } else {
        initialContext.createDeviceProtectedStorageContext()
    }
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    init {
        migrate()
    }

    var isDebugMode: Boolean
        get() = prefs.getBoolean(PREF_DEBUG_MODE, false)
        set(enabled) = prefs.edit { putBoolean(PREF_DEBUG_MODE, enabled) }

    /** Base URI to fetch OTA updates. This is either an HTTP/HTTPS URL or a SAF URI. */
    var otaSource: Uri?
        get() = prefs.getString(PREF_OTA_SOURCE, null)?.toUri()
        set(uri) {
            val oldUri = otaSource
            if (oldUri == uri) {
                // URI is the same as before or both are null
                return
            }

            prefs.edit {
                if (uri != null) {
                    if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                        // Persist permissions for the new URI first
                        context.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    putString(PREF_OTA_SOURCE, uri.toString())
                } else {
                    remove(PREF_OTA_SOURCE)
                }
            }

            // Release persisted permissions on the old directory only after the new URI is set to
            // guarantee atomicity
            if (oldUri != null && oldUri.scheme == ContentResolver.SCHEME_CONTENT) {
                // It's not documented, but this can throw an exception when trying to release a
                // previously persisted URI that's associated with an app that's no longer installed
                try {
                    context.contentResolver.releasePersistableUriPermission(
                        oldUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    Log.w(TAG, "Error when releasing persisted URI permission for: $oldUri", e)
                }
            }
        }

    val defaultOtaSource: Uri?
        get() = DEFAULT_OTA_SOURCE.takeIf { it.isNotBlank() }?.toUri()

    var allowCustomOtaSource: Boolean
        get() = prefs.getBoolean(PREF_ALLOW_CUSTOM_OTA_SOURCE, false)
        set(enabled) = prefs.edit { putBoolean(PREF_ALLOW_CUSTOM_OTA_SOURCE, enabled) }

    val effectiveOtaSource: Uri?
        get() = if (allowCustomOtaSource) {
            otaSource ?: defaultOtaSource
        } else {
            defaultOtaSource ?: otaSource
        }

    /**
     * Whether the automatic beta cross-grade has completed and its result is staged for reboot.
     *
     * Set when an [com.chiller3.custota.updater.UpdaterThread.Action.INSTALL_BETA] run succeeds.
     * Read by the escalation job to distinguish a device awaiting reboot from one whose cross-grade
     * has not yet run, since the latter warrants notifying the user.
     */
    var betaCrossGradeStaged: Boolean
        get() = prefs.getBoolean(PREF_BETA_CROSSGRADE_STAGED, false)
        set(staged) = prefs.edit { putBoolean(PREF_BETA_CROSSGRADE_STAGED, staged) }

    /**
     * Whether post-payload conflict resolution completed for the currently staged update.
     *
     * update_engine enters [com.chiller3.custota.updater.UpdateEngineStatus.UPDATED_NEED_REBOOT]
     * autonomously the moment a slot is staged, which is before Custota runs backup and
     * conflict resolution. The engine state therefore cannot be used as evidence that the
     * post-payload work finished. This flag records that evidence explicitly: it is cleared
     * when a new payload begins staging and set only after conflict resolution succeeds, so the
     * reboot prompt is gated on completion of Custota's own work rather than on the engine state.
     * A run that observes a staged update with this flag unset treats the resolution as
     * incomplete and re-runs it before offering reboot.
     */
    var conflictResolutionComplete: Boolean
        get() = prefs.getBoolean(PREF_CONFLICT_RESOLUTION_COMPLETE, false)
        set(complete) = prefs.edit { putBoolean(PREF_CONFLICT_RESOLUTION_COMPLETE, complete) }

    /** Whether to check for updates periodically. */
    var automaticCheck: Boolean
        get() = prefs.getBoolean(PREF_AUTOMATIC_CHECK, true)
        set(enabled) = prefs.edit { putBoolean(PREF_AUTOMATIC_CHECK, enabled) }

    /** Whether to install updates in the periodic job or just check for them. */
    var automaticInstall: Boolean
        get() = prefs.getBoolean(PREF_AUTOMATIC_INSTALL, false)
        set(enabled) = prefs.edit { putBoolean(PREF_AUTOMATIC_INSTALL, enabled) }

    /** Whether to only allow running when connected to an unmetered network. */
    var requireUnmetered: Boolean
        get() = prefs.getBoolean(PREF_UNMETERED_ONLY, true)
        set(enabled) = prefs.edit { putBoolean(PREF_UNMETERED_ONLY, enabled) }

    /** Whether to only allow running when battery is above the critical threshold. */
    var requireBatteryNotLow: Boolean
        get() = prefs.getBoolean(PREF_BATTERY_NOT_LOW, true)
        set(enabled) = prefs.edit { putBoolean(PREF_BATTERY_NOT_LOW, enabled) }

    /** Whether to skip optional post-install scripts in the OTA. */
    var skipPostInstall: Boolean
        get() = prefs.getBoolean(PREF_SKIP_POSTINSTALL, false)
        set(enabled) = prefs.edit { putBoolean(PREF_SKIP_POSTINSTALL, enabled) }

    /** Whether to treat an equal fingerprint as an update. */
    var allowReinstall: Boolean
        get() = prefs.getBoolean(PREF_ALLOW_REINSTALL, false)
        set(enabled) = prefs.edit { putBoolean(PREF_ALLOW_REINSTALL, enabled) }

    var csigCerts: Set<X509Certificate>
        get() {
            val encoded = prefs.getStringSet(PREF_CSIG_CERTS, emptySet())!!
            val factory = CertificateFactory.getInstance("X.509")

            return encoded
                .asSequence()
                .map { base64 ->
                    val der = Base64.decode(base64, Base64.DEFAULT)

                    ByteArrayInputStream(der).use {
                        factory.generateCertificate(it) as X509Certificate
                    }
                }
                .toSet()
        }
        set(certs) {
            val encoded = certs
                .asSequence()
                .map {
                    Base64.encodeToString(it.encoded, Base64.NO_WRAP)
                }
                .toSet()

            prefs.edit { putStringSet(PREF_CSIG_CERTS, encoded) }
        }

    /** Whether to pin all connections to a specific network ID. */
    var pinNetworkId: Boolean
        get() = prefs.getBoolean(PREF_PIN_NETWORK_ID, true)
        set(enabled) = prefs.edit { putBoolean(PREF_PIN_NETWORK_ID, enabled) }

    /**
     * URL opened by the "Open BenOS Website" button on the update message screen.
     * Falls back to [DEFAULT_BENOS_WEBSITE_URL] when unset or blank.
     */
    var benosWebsiteUrl: String
        get() = prefs.getString(PREF_BENOS_WEBSITE_URL, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_BENOS_WEBSITE_URL
        set(value) = prefs.edit { putString(PREF_BENOS_WEBSITE_URL, value) }

    var benosKofiUrl: String
        get() = prefs.getString(PREF_BENOS_KOFI_URL, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_BENOS_KOFI_URL
        set(value) = prefs.edit { putString(PREF_BENOS_KOFI_URL, value) }


    /** Migrate legacy preferences to current preferences. */
    private fun migrate() {
        if (prefs.contains(PREF_OTA_SERVER_URL)) {
            otaSource = prefs.getString(PREF_OTA_SERVER_URL, null)?.toUri()
            prefs.edit { remove(PREF_OTA_SERVER_URL) }
        }
    }
}
