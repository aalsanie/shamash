/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.config

object ProjectLayout {
    const val SRC_MAIN_RESOURCES = "src/main/resources"
    const val SRC_TEST_RESOURCES = "src/test/resources"

    const val ASM_CONFIG_DIR = "shamash/configs"
    const val ASM_CONFIG_FILE_YML = "asm.yml"
    const val ASM_CONFIG_FILE_YAML = "asm.yaml"

    const val RESOURCES = "/resources"

    const val BASE: String = "/shamash/asm/schema/v1"
    const val SCHEMA_JSON: String = "$BASE/shamash-asm.schema.json"
    const val REFERENCE_YML: String = "$BASE/shamash-asm.reference.yml"
    const val EMPTY_YML: String = "$BASE/empty.yaml"

    const val DISCOVERY_YML: String = "$BASE/shamash-asm.discovery.yml"

    const val STARTER_YML: String = "$BASE/shamash-asm.starter.yml"

    const val SPRING_YML: String = "$BASE/shamash-asm.spring.yml"

    const val ASM_CONFIG_RELATIVE_YML = "$ASM_CONFIG_DIR/$ASM_CONFIG_FILE_YML"
    const val ASM_CONFIG_RELATIVE_YAML = "$ASM_CONFIG_DIR/$ASM_CONFIG_FILE_YAML"

    val ASM_CONFIG_CANDIDATES = listOf(ASM_CONFIG_RELATIVE_YML, ASM_CONFIG_RELATIVE_YAML)
}
