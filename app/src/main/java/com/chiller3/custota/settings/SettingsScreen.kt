/*
 * SPDX-FileCopyrightText: 2022-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.custota.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chiller3.custota.BuildConfig
import com.chiller3.custota.Permissions
import com.chiller3.custota.Preferences
import com.chiller3.custota.R
import com.chiller3.custota.extension.EXTERNAL_STORAGE_AUTHORITY
import com.chiller3.custota.extension.formattedString
import com.chiller3.custota.extension.isGuaranteedLocalFile
import com.chiller3.custota.ui.AppScreen
import com.chiller3.custota.ui.BetterSegmentedShapes
import com.chiller3.custota.ui.Preference
import com.chiller3.custota.ui.PreferenceCategory
import com.chiller3.custota.ui.PreferenceColumn
import com.chiller3.custota.ui.SwitchPreference
import com.chiller3.custota.ui.betterSegmentedShapes
import com.chiller3.custota.ui.theme.AppTheme
import com.chiller3.custota.updater.OtaPaths
import com.chiller3.custota.updater.UpdaterJob
import com.chiller3.custota.updater.UpdaterThread
import com.chiller3.custota.wrapper.SystemPropertiesProxy

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val resources = LocalResources.current

    val prefs = remember { Preferences(context) }
    var reloadPrefs by remember { mutableIntStateOf(0) }
    val allowCustomOtaSource = remember(reloadPrefs) { prefs.allowCustomOtaSource }
    val effectiveOtaSource = remember(reloadPrefs) { prefs.effectiveOtaSource }
    val automaticCheck = remember(reloadPrefs) { prefs.automaticCheck }
    val automaticInstall = remember(reloadPrefs) { prefs.automaticInstall }
    val requireUnmetered = remember(reloadPrefs) { prefs.requireUnmetered }
    val requireBatteryNotLow = remember(reloadPrefs) { prefs.requireBatteryNotLow }
    val isDebugMode = remember(reloadPrefs) { prefs.isDebugMode }
    val skipPostInstall = remember(reloadPrefs) { prefs.skipPostInstall }
    val allowReinstall = remember(reloadPrefs) { prefs.allowReinstall }
    val pinNetworkId = remember(reloadPrefs) { prefs.pinNetworkId }

    val bootloaderStatus by viewModel.bootloaderStatus.collectAsStateWithLifecycle()

    var scheduledAction by rememberSaveable { mutableStateOf<UpdaterThread.Action?>(null) }

    val requestPermissionRequired = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        // Call recording can still be enabled if optional permissions were not granted.
        if (granted.all { it.key !in Permissions.REQUIRED || it.value }) {
            UpdaterJob.scheduleImmediate(context, scheduledAction!!)
            scheduledAction = null
        } else {
            context.startActivity(Permissions.getAppInfoIntent(context))
        }
    }

    val performAction = {
        if (Permissions.haveRequired(context)) {
            UpdaterJob.scheduleImmediate(context, scheduledAction!!)
            scheduledAction = null
        } else {
            requestPermissionRequired.launch(Permissions.REQUIRED)
        }
    }

    var showErrorDialog by rememberSaveable { mutableStateOf<String?>(null) }

    AppScreen(
        title = { Text(text = stringResource(R.string.app_name)) },
    ) { params ->
        LaunchedEffect(Unit) {
            viewModel.alerts.collect { alerts ->
                val alert = alerts.firstOrNull() ?: return@collect
                val msg = when (alert) {
                    is Alert.SystemCertLoadFailure ->
                        resources.getString(R.string.alert_system_cert_load_failure)
                    is Alert.CsigCertLoadFailure ->
                        resources.getString(R.string.alert_csig_cert_load_failure)
                    Alert.BrowserNotFound ->
                        resources.getString(R.string.alert_browser_not_found)
                    Alert.DocumentsUINotFound ->
                        resources.getString(R.string.alert_documentsui_not_found)
                }
                val details = when (alert) {
                    is Alert.SystemCertLoadFailure -> alert.error
                    is Alert.CsigCertLoadFailure -> alert.error
                    Alert.BrowserNotFound -> null
                    Alert.DocumentsUINotFound -> null
                }

                val result = params.snackbarHostState.showSnackbar(
                    message = msg,
                    details?.let { resources.getString(R.string.action_details) },
                    withDismissAction = true,
                )
                viewModel.acknowledgeFirstAlert()

                when (result) {
                    SnackbarResult.Dismissed -> {}
                    SnackbarResult.ActionPerformed -> { showErrorDialog = details }
                }
            }
        }

        showErrorDialog?.let { message ->
            ErrorDetailsDialog(
                message = message,
                onDismiss = { showErrorDialog = null },
            )
        }

        SettingsContent(
            allowCustomOtaSource = allowCustomOtaSource,
            effectiveOtaSource = effectiveOtaSource,
            automaticCheck = automaticCheck,
            automaticInstall = automaticInstall,
            requireUnmetered = requireUnmetered,
            requireBatteryNotLow = requireBatteryNotLow,
            skipPostInstall = skipPostInstall,
            androidVersion = Build.VERSION.RELEASE,
            securityPatchLevel = SystemPropertiesProxy.get(UpdaterThread.PROP_SECURITY_PATCH),
            fingerprint = Build.FINGERPRINT,
            bootSlot = SystemPropertiesProxy.get("ro.boot.slot_suffix")
                .removePrefix("_").uppercase(),
            bootloaderStatus = bootloaderStatus,
            isDebugMode = isDebugMode,
            vbmetaDigest = SystemPropertiesProxy.get(UpdaterThread.PROP_VBMETA_DIGEST),
            allowReinstall = allowReinstall,
            pinNetworkId = pinNetworkId,
            onCheckForUpdates = {
                scheduledAction = UpdaterThread.Action.CHECK
                performAction()
            },
            onOtaSourceChange = { uri ->
                prefs.otaSource = uri
                reloadPrefs++
                UpdaterJob.schedulePeriodic(context, true)
            },
            onOtaSourceReset = {
                prefs.otaSource = null
                reloadPrefs++
                UpdaterJob.schedulePeriodic(context, true)
            },
            onAllowCustomOtaSourceChange = { enabled ->
                prefs.allowCustomOtaSource = enabled
                reloadPrefs++
                UpdaterJob.schedulePeriodic(context, true)
            },
            onAutomaticCheckChange = { enabled ->
                prefs.automaticCheck = enabled
                reloadPrefs++
                UpdaterJob.schedulePeriodic(context, true)
            },
            onAutomaticInstallChange = { enabled ->
                prefs.automaticInstall = enabled
                reloadPrefs++
                UpdaterJob.schedulePeriodic(context, true)
            },
            onRequireUnmeteredChange = { enabled ->
                prefs.requireUnmetered = enabled
                reloadPrefs++
                UpdaterJob.schedulePeriodic(context, true)
            },
            onRequireBatteryNotLowChange = { enabled ->
                prefs.requireBatteryNotLow = enabled
                reloadPrefs++
                UpdaterJob.schedulePeriodic(context, true)
            },
            onSkipPostInstallChange = { enabled ->
                prefs.skipPostInstall = enabled
                reloadPrefs++
            },
        
            onSourceRepoOpen = {
                val uri = BuildConfig.PROJECT_URL_AT_COMMIT.toUri()
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                    viewModel.addAlert(Alert.BrowserNotFound)
                }
            },
            onDebugModeChange = { enabled ->
                prefs.isDebugMode = enabled
                reloadPrefs++
            },
            onOpenLogDir = {
                val externalDir = Environment.getExternalStorageDirectory()
                val filesDir = context.getExternalFilesDir(null)!!
                val relPath = filesDir.relativeTo(externalDir)
                val uri = DocumentsContract.buildDocumentUri(
                    EXTERNAL_STORAGE_AUTHORITY, "primary:$relPath")
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "vnd.android.document/directory")
                }

                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    viewModel.addAlert(Alert.DocumentsUINotFound)
                }
            },
            onAllowReinstallChange = { enabled ->
                prefs.allowReinstall = enabled
                reloadPrefs++
            },
            onRevertCompleted = {
                scheduledAction = UpdaterThread.Action.REVERT
                performAction()
            },
            onPinNetworkIdChange = { enabled ->
                prefs.pinNetworkId = enabled
                reloadPrefs++
            },
            contentPadding = params.contentPadding,
        )
    }

    LaunchedEffect(Unit) {
        // Make sure we refresh this every time the user switches back to the app.
        viewModel.refreshBootloaderStatus()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsContent(
    allowCustomOtaSource: Boolean,
    effectiveOtaSource: Uri?,
    automaticCheck: Boolean,
    automaticInstall: Boolean,
    requireUnmetered: Boolean,
    requireBatteryNotLow: Boolean,
    skipPostInstall: Boolean,
    androidVersion: String,
    securityPatchLevel: String,
    fingerprint: String,
    bootSlot: String,
    bootloaderStatus: SettingsViewModel.BootloaderStatus?,
    isDebugMode: Boolean,
    vbmetaDigest: String,
    allowReinstall: Boolean,
    pinNetworkId: Boolean,
    onCheckForUpdates: () -> Unit,
    onOtaSourceChange: (Uri) -> Unit,
    onOtaSourceReset: () -> Unit,
    onAllowCustomOtaSourceChange: (Boolean) -> Unit,
    onAutomaticCheckChange: (Boolean) -> Unit,
    onAutomaticInstallChange: (Boolean) -> Unit,
    onRequireUnmeteredChange: (Boolean) -> Unit,
    onRequireBatteryNotLowChange: (Boolean) -> Unit,
    onSkipPostInstallChange: (Boolean) -> Unit,
    onSourceRepoOpen: () -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
    onOpenLogDir: () -> Unit,
    onAllowReinstallChange: (Boolean) -> Unit,
    onRevertCompleted: () -> Unit,
    onPinNetworkIdChange: (Boolean) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var showOtaSourceDialog by rememberSaveable { mutableStateOf(false) }

    PreferenceColumn(contentPadding = contentPadding) {
        item(key = "general") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_general)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "check_for_updates") {
            Preference(
                onClick = onCheckForUpdates,
                enabled = effectiveOtaSource != null,
                shapes = BetterSegmentedShapes.top(),
                title = { Text(text = stringResource(R.string.pref_check_for_updates_name)) },
                summary = { Text(text = stringResource(R.string.pref_check_for_updates_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "ota_source") {
            Preference(
                onClick = { showOtaSourceDialog = true },
                onLongClick = onOtaSourceReset,
                enabled = allowCustomOtaSource,
                shapes = BetterSegmentedShapes.bottom(),
                title = { Text(text = stringResource(R.string.pref_ota_source_name)) },
                summary = { Text(text = otaSourceSummary(effectiveOtaSource)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "behavior") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_behavior)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "automatic_check") {
            SwitchPreference(
                checked = automaticCheck,
                onCheckedChange = onAutomaticCheckChange,
                shapes = BetterSegmentedShapes.top(),
                title = { Text(text = stringResource(R.string.pref_automatic_check_name)) },
                summary = { Text(text = stringResource(R.string.pref_automatic_check_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "automatic_install") {
            SwitchPreference(
                checked = automaticInstall,
                onCheckedChange = onAutomaticInstallChange,
                enabled = automaticCheck,
                shapes = BetterSegmentedShapes.middle(),
                title = { Text(text = stringResource(R.string.pref_automatic_install_name)) },
                summary = { Text(text = stringResource(R.string.pref_automatic_install_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        if (effectiveOtaSource?.isGuaranteedLocalFile != true) {
            item(key = "unmetered_only") {
                SwitchPreference(
                    checked = requireUnmetered,
                    onCheckedChange = onRequireUnmeteredChange,
                    shapes = BetterSegmentedShapes.middle(),
                    title = { Text(text = stringResource(R.string.pref_unmetered_only_name)) },
                    summary = { Text(text = stringResource(R.string.pref_unmetered_only_desc)) },
                    modifier = Modifier.animateItem(),
                )
            }
        }

        item(key = "battery_not_low") {
            SwitchPreference(
                checked = requireBatteryNotLow,
                onCheckedChange = onRequireBatteryNotLowChange,
                shapes = BetterSegmentedShapes.middle(),
                title = { Text(text = stringResource(R.string.pref_battery_not_low_name)) },
                summary = { Text(text = stringResource(R.string.pref_battery_not_low_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "os") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_os)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "android_version") {
            Preference(
                onClick = {},
                shapes = BetterSegmentedShapes.top(),
                title = { Text(text = stringResource(R.string.pref_android_version_name)) },
                summary = { Text(text = androidVersion) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "security_patch_level") {
            Preference(
                onClick = {},
                shapes = BetterSegmentedShapes.middle(),
                title = { Text(text = stringResource(R.string.pref_security_patch_level_name)) },
                summary = { Text(text = securityPatchLevel) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "boot_slot") {
            Preference(
                onClick = {},
                shapes = BetterSegmentedShapes.middle(),
                title = { Text(text = stringResource(R.string.pref_boot_slot_name)) },
                summary = { Text(text = bootSlot) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "bootloader_status") {
            Preference(
                onClick = {},
                shapes = BetterSegmentedShapes.bottom(),
                title = { Text(text = stringResource(R.string.pref_bootloader_status_name)) },
                summary = { bootloaderStatus?.let { Text(text = bootloaderStatusSummary(it)) } },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "about") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_about)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "version") {
            Preference(
                onClick = onSourceRepoOpen,
                onLongClick = { onDebugModeChange(!isDebugMode) },
                shapes = BetterSegmentedShapes.single(),
                title = { Text(text = stringResource(R.string.pref_version_name)) },
                summary = { Text(text = versionSummary(isDebugMode)) },
                modifier = Modifier.animateItem(),
            )
        }

        if (isDebugMode) {
            item(key = "debug") {
                PreferenceCategory(
                    title = { Text(text = stringResource(R.string.pref_header_debug)) },
                    modifier = Modifier.animateItem(),
                )
            }
            
            item(key = "fingerprint") {
                Preference(
                    onClick = {},
                    shapes = BetterSegmentedShapes.top(),
                    title = { Text(text = stringResource(R.string.pref_fingerprint_name)) },
                    summary = { Text(text = fingerprint) },
                    modifier = Modifier.animateItem(),
                )
        }

            item(key = "vbmeta_digest") {
                Preference(
                    onClick = {},
                    shapes = BetterSegmentedShapes.middle(),
                    title = { Text(text = stringResource(R.string.pref_vbmeta_digest_name)) },
                    summary = { Text(text = vbmetaDigest) },
                    modifier = Modifier.animateItem(),
                )
        }

            item(key = "open_log_dir") {
                Preference(
                    onClick = onOpenLogDir,
                    shapes = BetterSegmentedShapes.middle(),
                    title = { Text(text = stringResource(R.string.pref_open_log_dir_name)) },
                    summary = { Text(text = stringResource(R.string.pref_open_log_dir_desc)) },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "allow_reinstall") {
                SwitchPreference(
                    checked = allowReinstall,
                    onCheckedChange = onAllowReinstallChange,
                    shapes = BetterSegmentedShapes.middle(),
                    title = { Text(text = stringResource(R.string.pref_allow_reinstall_name)) },
                    summary = { Text(text = stringResource(R.string.pref_allow_reinstall_desc)) },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "skip_postinstall") {
                SwitchPreference(
                    checked = skipPostInstall,
                    onCheckedChange = onSkipPostInstallChange,
                    shapes = BetterSegmentedShapes.middle(),
                    title = { Text(text = stringResource(R.string.pref_skip_postinstall_name)) },
                    summary = { Text(text = stringResource(R.string.pref_skip_postinstall_desc)) },
                    modifier = Modifier.animateItem(),
            )
        }

            item(key = "revert_completed") {
                Preference(
                    onClick = onRevertCompleted,
                    shapes = BetterSegmentedShapes.middle(),
                    title = { Text(text = stringResource(R.string.pref_revert_completed_name)) },
                    summary = { Text(text = stringResource(R.string.pref_revert_completed_desc)) },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "pin_network_id") {
                SwitchPreference(
                    checked = pinNetworkId,
                    onCheckedChange = onPinNetworkIdChange,
                    shapes = BetterSegmentedShapes.middle(),
                    title = { Text(text = stringResource(R.string.pref_pin_network_id_name)) },
                    summary = { Text(text = stringResource(R.string.pref_pin_network_id_desc)) },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "allow_custom_ota_source") {
                SwitchPreference(
                    checked = allowCustomOtaSource,
                    onCheckedChange = onAllowCustomOtaSourceChange,
                    shapes = BetterSegmentedShapes.bottom(),
                    title = { Text(text = stringResource(R.string.pref_allow_custom_ota_source_name)) },
                    summary = { Text(text = stringResource(R.string.pref_allow_custom_ota_source_desc)) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    if (showOtaSourceDialog) {
        OtaSourceDialog(
            initialUri = effectiveOtaSource,
            onSelect = { uri ->
                onOtaSourceChange(uri)
                @Suppress("AssignedValueIsNeverRead")
                showOtaSourceDialog = false
            },
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showOtaSourceDialog = false
            },
        )
    }
}

@Composable
private fun otaSourceSummary(otaSource: Uri?) = otaSource?.formattedString
    ?: stringResource(R.string.pref_ota_source_none)

@Composable
private fun bootloaderStatusSummary(status: SettingsViewModel.BootloaderStatus) = buildString {
    when (status) {
        is SettingsViewModel.BootloaderStatus.Success -> {
            if (status.unlocked) {
                append(stringResource(R.string.pref_bootloader_status_unlocked))
            } else {
                append(stringResource(R.string.pref_bootloader_status_locked))
            }
            append('\n')
            if (status.allowedByCarrier) {
                append(stringResource(R.string.pref_bootloader_status_oemlock_carrier_allowed))
            } else {
                append(stringResource(R.string.pref_bootloader_status_oemlock_carrier_blocked))
            }
            append('\n')
            if (status.allowedByUser) {
                append(stringResource(R.string.pref_bootloader_status_oemlock_user_allowed))
            } else {
                append(stringResource(R.string.pref_bootloader_status_oemlock_user_blocked))
            }
        }
        is SettingsViewModel.BootloaderStatus.Failure -> {
            append(stringResource(R.string.pref_bootloader_status_unknown))
            append('\n')
            append(status.errorMsg)
        }
    }
}

@Composable
private fun versionSummary(isDebugMode: Boolean): String {
    val suffix = if (isDebugMode) "+debugmode" else ""

    return "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}${suffix})"
}

@Preview(
    name = "Light Mode",
    showBackground = true,
)
@Preview(
    name = "Dark Mode",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
private fun PreviewSettingsScreen() {
    val uri = DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:OTA")

    AppTheme {
        AppScreen(
            title = { Text(text = stringResource(R.string.app_name)) },
        ) { params ->
            SettingsContent(
                allowCustomOtaSource = false,
                effectiveOtaSource = uri,
                automaticCheck = true,
                automaticInstall = true,
                requireUnmetered = true,
                requireBatteryNotLow = true,
                skipPostInstall = false,
                androidVersion = "16",
                securityPatchLevel = "2026-05-05",
                bootSlot = "A",
                bootloaderStatus = SettingsViewModel.BootloaderStatus.Success(
                    unlocked = false,
                    allowedByCarrier = true,
                    allowedByUser = true,
                ),
                isDebugMode = true,
                fingerprint = Build.FINGERPRINT,
                vbmetaDigest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                allowReinstall = false,
                pinNetworkId = true,
                onCheckForUpdates = {},
                onOtaSourceChange = {},
                onOtaSourceReset = {},
                onAllowCustomOtaSourceChange = {},
                onAutomaticCheckChange = {},
                onAutomaticInstallChange = {},
                onRequireUnmeteredChange = {},
                onRequireBatteryNotLowChange = {},
                onSkipPostInstallChange = {},
                onSourceRepoOpen = {},
                onDebugModeChange = {},
                onOpenLogDir = {},
                onAllowReinstallChange = {},
                onRevertCompleted = {},
                onPinNetworkIdChange = {},
                contentPadding = params.contentPadding,
            )
        }
    }
}
