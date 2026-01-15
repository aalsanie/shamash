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
package io.shamash.psi.core.config

import io.shamash.psi.core.config.schema.v1.model.RuleKey
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.config.validation.v1.ConfigSemanticValidatorV1

/**
 * Compatibility façade for config semantic validation.
 *
 * BOUNDARY:
 * - This object does NOT implement semantic validation.
 * - It delegates to the dedicated validation layer (io.shamash.psi.config.validation.v1.*).
 *
 * Rationale:
 * - Keeps a stable entrypoint for existing call sites in io.shamash.psi.config.
 * - Enforces the architecture separation: binding here, semantics in validation layer.
 */
object ConfigValidator {
    /**
     * Validate config semantics.
     *
     * Delegates to the validation layer.
     * This class is intentionally thin.
     */
    fun validateSemantic(
        config: ShamashPsiConfigV1,
        executableRuleKeys: Set<RuleKey>? = null,
    ): List<ValidationError> =
        ConfigSemanticValidatorV1
            .validateSemantic(config = config, executableRuleKeys = executableRuleKeys)
}
