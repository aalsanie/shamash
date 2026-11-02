/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.artifacts.baseline

/**
 * Baseline execution mode for Shamash scans/exports.
 *
 * Modes:
 * - [OFF]: No baseline is loaded or written.
 * - [USE]: Load an existing baseline and suppress matching findings.
 * - [GENERATE]: Export findings and write/update the baseline from the current scan output.
 */
enum class BaselineMode {
    OFF,
    USE,
    GENERATE,
}
