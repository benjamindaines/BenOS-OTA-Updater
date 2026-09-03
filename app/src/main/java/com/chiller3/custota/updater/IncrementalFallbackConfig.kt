/*
 * SPDX-FileCopyrightText: 2026 Ben Daines
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.custota.updater

/**
 * Build-time configuration for the incremental-to-full OTA fallback.
 *
 * When an incremental (delta) payload fails in update_engine with one of the error codes listed
 * here, a one-shot preference is armed (see the failure handler in UpdaterThread.run() and
 * Preferences.forceFullOta). The next installation attempt then selects the full package from the
 * device JSON instead of the incremental, bypassing an incremental that update_engine has already
 * rejected and would reject identically on every subsequent retry.
 *
 * Only failures whose error code appears in this list trigger the fallback. Codes that are absent
 * leave the incremental path unchanged, so transient conditions unrelated to the payload itself
 * (for example a dropped connection, code 9 DOWNLOAD_TRANSFER_ERROR) continue to retry the smaller
 * incremental package as before. Full-package failures never arm the fallback regardless of code.
 *
 * This is the only file that normally needs editing to tune the feature. Values are the numeric
 * update_engine error codes enumerated in UpdateEngineError; the symbolic constants defined there
 * are used here so each entry is self-documenting.
 */
object IncrementalFallbackConfig {
    /**
     * update_engine error codes that, when returned for an incremental payload, arm the fallback to
     * the full package. The default entry is DOWNLOAD_STATE_INITIALIZATION_ERROR (20), which
     * update_engine returns when the source partitions on the running slot do not match the delta's
     * expected precondition, a state no number of incremental retries can resolve.
     */
    val FALLBACK_ERROR_CODES: List<Int> = listOf(
        UpdateEngineError.DOWNLOAD_STATE_INITIALIZATION_ERROR,
		UpdateEngineError.PAYLOAD_HASH_MISMATCH_ERROR,
		UpdateEngineError.DOWNLOAD_OPERATION_HASH_MISMATCH,
        // Additional codes for which the full package should be preferred may be added here, e.g.:
        // UpdateEngineError.PAYLOAD_HASH_MISMATCH_ERROR,
        // UpdateEngineError.DOWNLOAD_OPERATION_HASH_MISMATCH,
    )
}
