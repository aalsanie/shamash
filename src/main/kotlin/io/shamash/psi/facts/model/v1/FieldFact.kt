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
package io.shamash.psi.facts.model.v1

import com.intellij.openapi.util.TextRange

data class FieldFact(
    val containingClassFqn: String,
    val name: String,
    val typeFqn: String,
    val visibility: Visibility,
    val isStatic: Boolean,
    val isFinal: Boolean,
    val annotationsFqns: Set<String>,
    val filePath: String,
    val textRange: TextRange? = null,
) {
    val fqName: String = "$containingClassFqn#$name"
}
