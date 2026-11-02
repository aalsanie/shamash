/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.config

object ProjectLayout {
    const val SRC_MAIN_RESOURCES = "src/main/resources"
    const val SRC_TEST_RESOURCES = "src/test/resources"

    const val PSI_CONFIG_DIR = "shamash/configs"
    const val PSI_CONFIG_FILE_YML = "psi.yml"
    const val PSI_CONFIG_FILE_YAML = "psi.yaml"
    const val RESOURCES = "/resources"
    const val BASE: String = "/shamash/psi/schema/v1"
    const val SCHEMA_JSON: String = "$BASE/shamash-psi.schema.json"
    const val REFERENCE_YML: String = "$BASE/shamash-psi.reference.yml"
    const val EMPTY_YML: String = "$BASE/empty.yaml"

    const val PSI_CONFIG_RELATIVE_YML = "$PSI_CONFIG_DIR/$PSI_CONFIG_FILE_YML"
    const val PSI_CONFIG_RELATIVE_YAML = "$PSI_CONFIG_DIR/$PSI_CONFIG_FILE_YAML"

    val PSI_CONFIG_CANDIDATES = listOf(PSI_CONFIG_RELATIVE_YML, PSI_CONFIG_RELATIVE_YAML)
}
