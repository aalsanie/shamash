/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.export.facts

/** [SCHEMA_ID] and [SCHEMA_VERSION] together identify the facts format compatibility contract. */
object FactsExportSchema {
    const val SCHEMA_ID: String = "io.shamash.asm.facts"
    const val SCHEMA_VERSION: Int = 1

    const val RECORD_META: String = "meta"
    const val RECORD_CLASS: String = "class"
    const val RECORD_EDGE: String = "edge"
}
