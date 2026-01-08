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
package io.shamash.psi.fixes

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1

/**
 * Context passed from UI (dashboard / toolwindow / intentions) into fix providers.
 */
data class FixContext(
    val project: Project,
    /** config used to produce the findings (optional). */
    val config: ShamashPsiConfigV1? = null,
    /** If known, the schema file being used. Enables schema-edit fixes. */
    val configFile: VirtualFile? = null,
)
