/*
 * Copyright © 2025-2026 | Shamash
 *
 * Shamash is a JVM architecture enforcement tool that helps teams
 * define, validate, and continuously enforce architectural boundaries.
 *
 * Author: @aalsanie
 *
 * Plugin: https://plugins.jetbrains.com/plugin/29504-shamash
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.shamash.asm.core.config

/**
 * ASM project layout and embedded resource paths.
 */
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

    const val ASM_CONFIG_RELATIVE_YML = "$ASM_CONFIG_DIR/$ASM_CONFIG_FILE_YML"
    const val ASM_CONFIG_RELATIVE_YAML = "$ASM_CONFIG_DIR/$ASM_CONFIG_FILE_YAML"

    val ASM_CONFIG_CANDIDATES = listOf(ASM_CONFIG_RELATIVE_YML, ASM_CONFIG_RELATIVE_YAML)
}
