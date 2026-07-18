/*
 * SPDX-FileCopyrightText: 2026 Benjamin Daines
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.custota.updater

import android.net.Uri
import androidx.core.net.toUri
import com.chiller3.custota.Preferences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Runtime resolution of the compile-time beta expiry configuration declared in [Preferences].
 *
 * The deadline is stored as a calendar date and resolved against a time zone at evaluation time
 * rather than as a fixed instant. Trigger instants therefore shift with the device's active zone: a
 * device that changes zones observes the deadline at the configured local hour on the configured
 * date, not at a single global moment.
 *
 * The deadline is a floor rather than a hard trigger. The cross-grade job additionally requires an
 * unmetered network, an idle device, and adequate battery, so the operation runs at the first
 * opportunity at or after the deadline that satisfies those conditions.
 *
 * All predicates are inert when [Preferences.BETA_BUILD] is false.
 */
object BetaExpiry {
    /** Whether the running build is a time-limited beta. */
    val isBetaBuild: Boolean
        get() = Preferences.BETA_BUILD

    /** Configured expiry date. Zone-independent; conversion to an instant occurs below. */
    val expiryDate: LocalDate
        get() = LocalDate.of(
            Preferences.BETA_EXPIRY_YEAR,
            Preferences.BETA_EXPIRY_MONTH,
            Preferences.BETA_EXPIRY_DAY,
        )

    /** Update source for the automatic cross-grade: the stable branch. */
    val fallbackOtaUri: Uri
        get() = Preferences.BETA_FALLBACK_OTA_URL.toUri()

    /**
     * Instant at which the cross-grade becomes eligible: the expiry date at
     * [Preferences.BETA_EXPIRY_INSTALL_HOUR], local time.
     */
    fun installTriggerAt(zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
        expiryDate
            .atTime(Preferences.BETA_EXPIRY_INSTALL_HOUR, 0)
            .atZone(zone)

    /** Instant at which the day-before warning is posted. */
    fun warningTriggerAt(zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
        expiryDate
            .minusDays(1)
            .atTime(Preferences.BETA_EXPIRY_INSTALL_HOUR, 0)
            .atZone(zone)

    /**
     * Instant at which a device whose cross-grade has not completed is considered overdue, being
     * [Preferences.BETA_ESCALATION_GRACE_DAYS] after the deadline.
     */
    fun escalationTriggerAt(zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
        expiryDate
            .plusDays(Preferences.BETA_ESCALATION_GRACE_DAYS)
            .atTime(Preferences.BETA_EXPIRY_INSTALL_HOUR, 0)
            .atZone(zone)

    private fun millisUntil(target: ZonedDateTime, now: Instant): Long =
        (target.toInstant().toEpochMilli() - now.toEpochMilli()).coerceAtLeast(0)

    /** Milliseconds from [now] until the day-before warning, clamped to zero. */
    fun millisUntilWarning(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Long =
        millisUntil(warningTriggerAt(zone), now)

    /** Milliseconds from [now] until the overdue escalation, clamped to zero. */
    fun millisUntilEscalation(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Long =
        millisUntil(escalationTriggerAt(zone), now)

    /** Whether [now] is at or past the cross-grade deadline. */
    fun isInstallDue(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Boolean =
        !now.isBefore(installTriggerAt(zone).toInstant())

    /** Whether [now] is at or past the overdue escalation instant. */
    fun isOverdue(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Boolean =
        !now.isBefore(escalationTriggerAt(zone).toInstant())

    /** Expiry date rendered using the supplied [locale]'s long date format. */
    fun formattedExpiryDate(locale: Locale = Locale.getDefault()): String =
        expiryDate.format(
            DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
        )
}
