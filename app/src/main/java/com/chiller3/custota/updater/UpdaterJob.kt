/*
 * SPDX-FileCopyrightText: 2023-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.custota.updater

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PersistableBundle
import android.util.Log
import com.chiller3.custota.Notifications
import com.chiller3.custota.Preferences
import com.chiller3.custota.R
import com.chiller3.custota.extension.isGuaranteedLocalFile

class UpdaterJob: JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val prefs = Preferences(this)
        val isPeriodic = params.jobId == ID_PERIODIC

        // The beta expiry notification jobs carry no action extra and perform no update, so they are
        // handled before the generic action path below.
        if (params.jobId == ID_BETA_WARNING || params.jobId == ID_BETA_ESCALATION) {
            handleBetaNotification(params.jobId, prefs)
            return false
        }

        if (isPeriodic && skipNextRun) {
            Log.i(TAG, "Skipped this run of the periodic job")
            skipNextRun = false
            return false
        }

        val actionIndex = params.extras.getInt(EXTRA_ACTION, -1)
        var action = UpdaterThread.Action.entries[actionIndex]

        // Once the deadline has passed, every periodic run becomes a cross-grade attempt until the
        // installation is staged. The override is applied at execution time rather than at
        // scheduling time so that the deadline takes effect without waiting for a reboot or app
        // start to reschedule the job. User-initiated runs are not overridden, leaving manual update
        // operations unaffected.
        if (isPeriodic && betaCrossGradeDue(prefs)) {
            Log.i(TAG, "Beta deadline reached; overriding $action with INSTALL_BETA")
            action = UpdaterThread.Action.INSTALL_BETA
        }

        if (action == UpdaterThread.Action.INSTALL_BETA) {
            // The periodic job is scheduled with this action ahead of the deadline when automatic
            // checks are disabled, so that a retry vehicle exists. Such runs are inert until due.
            if (!betaCrossGradeDue(prefs)) {
                Log.i(TAG, "Beta cross-grade is not due")
                return false
            }

            // The cross-grade is imposed rather than user-initiated, so an unmetered network is
            // required irrespective of prefs.requireUnmetered. The check is repeated here because
            // the periodic job may have been scheduled under a different action whose network
            // constraint permits metered connections.
            val connectivityManager = getSystemService(ConnectivityManager::class.java)
            val betaNetwork = params.network ?: connectivityManager.activeNetwork
            val capabilities = betaNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

            if (capabilities == null ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                Log.w(TAG, "Deferring cross-grade until an unmetered network is available")
                return false
            }

            // Not silent: the operation is not user-initiated, so its progress is surfaced.
            startForegroundService(UpdaterService.createStartIntent(
                applicationContext, betaNetwork, action, false))
            return false
        }

        var network = params.network
        if (prefs.effectiveOtaSource?.isGuaranteedLocalFile != true && action.requiresNetwork && network == null) {
            // Ever since the Android 15 betas, Android sometimes invokes this job with a null
            // Network instance, even though the network requirement is set and a sufficient network
            // is available. We'll try to work around this by manually querying the active network.
            // If the active network is insufficient, we'll just abort and wait for the next
            // scheduled run.

            Log.w(TAG, "Job parameters contain a null network instance")

            val connectivityManager = getSystemService(ConnectivityManager::class.java)
            network = connectivityManager.activeNetwork
            if (network == null) {
                Log.w(TAG, "Aborting due to active network also being null")
                return false
            }

            if (prefs.requireUnmetered) {
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities == null) {
                    Log.w(TAG, "Aborting due to the network capabilities being null for: $network")
                    return false
                }

                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                    Log.w(TAG, "Aborting due to active network being metered: $capabilities")
                    return false
                }
            }
        }

        startForegroundService(UpdaterService.createStartIntent(
            applicationContext, network, action, isPeriodic))
        return false
    }

    /**
     * Post the beta expiry warning or overdue notification, if still applicable. Both are suppressed
     * once the cross-grade has been staged, and each re-verifies its own timing because a job whose
     * trigger instant has already passed is dispatched immediately.
     */
    private fun handleBetaNotification(jobId: Int, prefs: Preferences) {
        if (!BetaExpiry.isBetaBuild || prefs.betaCrossGradeStaged) {
            return
        }

        val notifications = Notifications(this)
        notifications.updateChannels()

        when (jobId) {
            ID_BETA_WARNING -> {
                if (!BetaExpiry.isInstallDue()) {
                    Log.i(TAG, "Posting beta expiry warning")
                    notifications.sendBetaAlert(
                        getString(R.string.notification_beta_expiry_warning_title),
                        getString(
                            R.string.notification_beta_expiry_warning_message,
                            BetaExpiry.formattedExpiryDate(),
                        ),
                    )
                }
            }

            ID_BETA_ESCALATION -> {
                if (BetaExpiry.isOverdue()) {
                    Log.i(TAG, "Posting beta expiry overdue notice")
                    notifications.sendBetaAlert(
                        getString(R.string.notification_beta_expiry_overdue_title),
                        getString(
                            R.string.notification_beta_expiry_overdue_message,
                            BetaExpiry.formattedExpiryDate(),
                        ),
                    )
                }
            }
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        return false
    }

    companion object {
        private val TAG = UpdaterJob::class.java.simpleName

        private const val ID_IMMEDIATE = 1
        private const val ID_PERIODIC = 2
        private const val ID_BETA_WARNING = 3
        private const val ID_BETA_ESCALATION = 4

        private const val EXTRA_ACTION = "action"
        // Checks in 6 hour intervals 
        private const val PERIODIC_INTERVAL_MS = 6L * 60 * 60 * 1000

        // Delivery window for the beta expiry notifications. A bounded deadline is applied so they
        // surface near their intended time rather than being deferred indefinitely by Doze.
        private const val BETA_NOTIFICATION_SLACK_MS = 6L * 60 * 60 * 1000

        /**
         * Whether an automatic cross-grade should be attempted now: the running build is a
         * time-limited beta, the deadline has passed, and no installation has yet been staged.
         */
        private fun betaCrossGradeDue(prefs: Preferences): Boolean =
            BetaExpiry.isBetaBuild && !prefs.betaCrossGradeStaged && BetaExpiry.isInstallDue()

        // Scheduling a periodic job usually makes the first iteration run immediately. We'll
        // sometimes skip this to avoid unexpected operations while the user is configuring
        // settings in the UI.
        private var skipNextRun = false

        private fun createJobBuilder(
            context: Context,
            jobId: Int,
            action: UpdaterThread.Action,
        ): JobInfo.Builder {
            val prefs = Preferences(context)

            var networkType = JobInfo.NETWORK_TYPE_NONE
            if (action == UpdaterThread.Action.INSTALL_BETA) {
                // The cross-grade always reads from the fallback URL rather than the configured
                // source, so the local-file exemption does not apply. An unmetered network is
                // required irrespective of prefs.requireUnmetered: the preference governs updates
                // the user opted into, whereas this operation is imposed, and a full OTA over a
                // metered connection would consume a substantial data allowance without consent.
                networkType = JobInfo.NETWORK_TYPE_UNMETERED
            } else if (prefs.effectiveOtaSource?.isGuaranteedLocalFile != true) {
                if (action.performsLargeDownloads && prefs.requireUnmetered) {
                    networkType = JobInfo.NETWORK_TYPE_UNMETERED
                } else if (action.requiresNetwork) {
                    networkType = JobInfo.NETWORK_TYPE_ANY
                }
            }

            // Battery state is likewise enforced unconditionally for the cross-grade, for the same
            // reason: an imposed installation should not run a device down to depletion.
            val requiresBatteryNotLow = action == UpdaterThread.Action.INSTALL_BETA ||
                    (action.usesSignificantBattery && prefs.requireBatteryNotLow)

            val extras = PersistableBundle().apply {
                putInt(EXTRA_ACTION, action.ordinal)
            }

            return JobInfo.Builder(jobId, ComponentName(context, UpdaterJob::class.java))
                .setRequiredNetworkType(networkType)
                .setRequiresBatteryNotLow(requiresBatteryNotLow)
                .setExtras(extras)
        }

        private fun bundlesEqual(bundle1: PersistableBundle, bundle2: PersistableBundle): Boolean {
            if (bundle1.keySet() != bundle2.keySet()) {
                return false
            }

            for (key in bundle1.keySet()) {
                // There's no other API for getting an arbitrary value, regardless of type.
                @Suppress("DEPRECATION") val object1 = bundle1.get(key)!!
                @Suppress("DEPRECATION") val object2 = bundle2.get(key)!!

                if (object1 is PersistableBundle && object2 is PersistableBundle) {
                    if (!bundlesEqual(object1, object2)) {
                        return false
                    }
                } else if (object1 is Array<*> && object2 is Array<*>) {
                    if (!object1.contentEquals(object2)) {
                        return false
                    }
                } else if (object1 != object2) {
                    return false
                }
            }

            return true
        }

        private fun JobInfo.toLongString() = buildString {
            append(this)
            append(" {requiredNetwork=")
            append(requiredNetwork)
            append(", isRequiredBatteryNotLow=")
            append(isRequireBatteryNotLow)
            append(", isPersisted=")
            append(isPersisted)
            append(", intervalMillis=")
            append(intervalMillis)
            append(", extras=")
            append(extras)
            append("}")
        }

        private fun scheduleIfUnchanged(jobScheduler: JobScheduler, jobInfo: JobInfo) {
            val oldJobInfo = jobScheduler.getPendingJob(jobInfo.id)

            // JobInfo.equals() is unreliable (and the comments in its implementation say so), so
            // just compare the fields that we set.
            if (oldJobInfo != null &&
                oldJobInfo.requiredNetwork == jobInfo.requiredNetwork &&
                oldJobInfo.isRequireBatteryNotLow == jobInfo.isRequireBatteryNotLow &&
                oldJobInfo.isPersisted == jobInfo.isPersisted &&
                oldJobInfo.intervalMillis == jobInfo.intervalMillis &&
                bundlesEqual(oldJobInfo.extras, jobInfo.extras)) {
                Log.i(TAG, "Job already exists and is unchanged: ${jobInfo.toLongString()}")
                return
            }

            Log.d(TAG, "Scheduling job: ${jobInfo.toLongString()}")

            when (val result = jobScheduler.schedule(jobInfo)) {
                JobScheduler.RESULT_SUCCESS ->
                    Log.d(TAG, "Scheduled job: ${jobInfo.toLongString()}")
                JobScheduler.RESULT_FAILURE ->
                    Log.w(TAG, "Failed to schedule job: ${jobInfo.toLongString()}")
                else -> throw IllegalStateException("Unexpected scheduler error: $result")
            }
        }

        private fun scheduleBetaNotification(
            context: Context,
            jobScheduler: JobScheduler,
            jobId: Int,
            latency: Long,
        ) {
            val jobInfo = JobInfo.Builder(jobId, ComponentName(context, UpdaterJob::class.java))
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .setMinimumLatency(latency)
                .setOverrideDeadline(latency + BETA_NOTIFICATION_SLACK_MS)
                .build()

            Log.d(TAG, "Scheduling beta notification job $jobId (latency=${latency}ms)")
            jobScheduler.schedule(jobInfo)
        }

        /**
         * Arm the beta expiry notification jobs, or cancel them once the running build is not a beta
         * or the cross-grade has been staged.
         *
         * The cross-grade installation itself is not scheduled here; it is driven by the periodic
         * job (see [schedulePeriodic] and the override in `onStartJob`), which supplies retry
         * behavior for the network, battery, and time constraints. A dedicated one-shot job would be
         * consumed on its first dispatch and would not retry if a constraint failed at that moment.
         *
         * Invoked on boot and app start via [com.chiller3.custota.PostUnlockInit], which re-derives
         * the latencies against the current clock and time zone. A trigger instant already in the
         * past yields a zero latency, causing immediate dispatch; each handler re-verifies its own
         * timing, so the overdue notice is re-posted while the cross-grade remains outstanding.
         */
        fun scheduleBeta(context: Context) {
            val jobScheduler = context.getSystemService(JobScheduler::class.java)
            val prefs = Preferences(context)

            if (!BetaExpiry.isBetaBuild || prefs.betaCrossGradeStaged) {
                jobScheduler.cancel(ID_BETA_WARNING)
                jobScheduler.cancel(ID_BETA_ESCALATION)
                return
            }

            if (!BetaExpiry.isInstallDue()) {
                scheduleBetaNotification(
                    context, jobScheduler, ID_BETA_WARNING, BetaExpiry.millisUntilWarning())
            } else {
                jobScheduler.cancel(ID_BETA_WARNING)
            }

            scheduleBetaNotification(
                context, jobScheduler, ID_BETA_ESCALATION, BetaExpiry.millisUntilEscalation())
        }

        fun scheduleImmediate(context: Context, action: UpdaterThread.Action) {
            val jobScheduler = context.getSystemService(JobScheduler::class.java)
            val jobInfo = createJobBuilder(context, ID_IMMEDIATE, action).build()

            scheduleIfUnchanged(jobScheduler, jobInfo)
        }

        fun schedulePeriodic(context: Context, skipFirstRun: Boolean) {
            val jobScheduler = context.getSystemService(JobScheduler::class.java)
            val prefs = Preferences(context)

            // The cross-grade reuses the periodic job as its retry vehicle, which requires the job
            // to exist for the duration of the beta period even when the user has disabled
            // automatic checks. In that configuration the job is scheduled with the cross-grade
            // action directly, which is inert until the deadline passes, so no update or update
            // notification occurs earlier than intended.
            val crossGradePending = BetaExpiry.isBetaBuild && !prefs.betaCrossGradeStaged

            val action = if (crossGradePending && !prefs.automaticCheck) {
                UpdaterThread.Action.INSTALL_BETA
            } else if (prefs.automaticInstall) {
                UpdaterThread.Action.INSTALL
            } else {
                UpdaterThread.Action.CHECK
            }

            val jobInfo = createJobBuilder(context, ID_PERIODIC, action)
                .setPersisted(true)
                .setPeriodic(PERIODIC_INTERVAL_MS)
                .build()

            if (!prefs.automaticCheck && !crossGradePending) {
                Log.d(TAG, "Cancelling job: ${jobInfo.toLongString()}")
                jobScheduler.cancel(ID_PERIODIC)
                return
            }

            skipNextRun = skipFirstRun

            scheduleIfUnchanged(jobScheduler, jobInfo)
        }
    }
}
