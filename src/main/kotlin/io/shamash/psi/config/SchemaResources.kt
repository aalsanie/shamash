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

import java.io.InputStream

object SchemaResources {
    fun openSchemaJson(): InputStream = openRequired(ProjectLayout.SCHEMA_JSON)

    fun openReferenceYaml(): InputStream = openRequired(ProjectLayout.REFERENCE_YML)

    private fun openRequired(resourcePath: String): InputStream =
        requireNotNull(SchemaResources::class.java.getResourceAsStream(resourcePath)) {
            "Missing resource: $resourcePath"
        }

    fun openEmptyYaml(): InputStream = openRequired(ProjectLayout.EMPTY_YML)
}
