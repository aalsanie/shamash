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
package io.shamash.psi.engine

import com.intellij.psi.PsiFile
import io.shamash.psi.config.schema.v1.model.RuleDef
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.facts.model.v1.FactsIndex

interface EngineRule {
    val id: String

    fun evaluate(
        file: PsiFile,
        facts: FactsIndex,
        rule: RuleDef,
        config: ShamashPsiConfigV1,
    ): List<Finding>
}

data class Finding(
    val ruleId: String,
    val message: String,
    val filePath: String,
    val severity: FindingSeverity,
    val classFqn: String? = null,
    val memberName: String? = null,
    val data: Map<String, String> = emptyMap(),
    val startOffset: Int? = null,
    val endOffset: Int? = null,
)

enum class FindingSeverity { ERROR, WARNING, INFO }
