/*
 * SPDX-FileCopyrightText: 2026 Benjamin Daines
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.custota

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.chiller3.custota.updater.UpdaterService

/**
 * Receives the boot-time PIT installer's reboot-ready signal.
 *
 * The installer fires this broadcast once module deployment and any Virtual A/B snapshot merge have
 * both completed (the "double-complete" state). Rather than restarting autonomously, the installer
 * defers the decision to the user: this receiver raises a notification carrying a reboot action.
 *
 * Notification title and body are read from string extras so the wording is owned by the installer
 * script and can be changed without rebuilding the updater. The reboot action itself reuses
 * [UpdaterService]'s existing reboot path.
 *
 * Access is gated by the signature-level permission declared on this receiver in the manifest;
 * the root-context installer satisfies it via ActivityManager's ROOT_UID/SYSTEM_UID short-circuit.
 */
class PitRebootReadyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MODULE_REBOOT_READY) {
            Log.w(TAG, "Ignoring unexpected action: ${intent.action}")
            return
        }

        val title = intent.getStringExtra(EXTRA_TITLE)
            ?: context.getString(R.string.notification_update_ota_succeeded)
        val text = intent.getStringExtra(EXTRA_TEXT)

        Log.i(TAG, "PIT signalled module-install reboot-ready; posting prompt")

        val notifications = Notifications(context)
        // Ensure channels exist: a broadcast may cold-start the process, and posting to an
        // absent channel is silently dropped on API 26+. The call is idempotent.
        notifications.updateChannels()
        notifications.sendModuleRebootNotification(
            title,
            text,
            R.string.notification_action_reboot,
            UpdaterService.createRebootIntent(context),
        )
    }

    companion object {
        private val TAG = PitRebootReadyReceiver::class.java.simpleName

        const val ACTION_MODULE_REBOOT_READY = "ph.dgsd.benos.pit.ACTION_MODULE_REBOOT_READY"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
    }
}
