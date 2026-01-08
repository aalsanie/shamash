/*
 * Copyright © 2025-2026 | Shamash is a refactoring tool that enforces clean architecture.
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
package io.shamash.psi.config

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
