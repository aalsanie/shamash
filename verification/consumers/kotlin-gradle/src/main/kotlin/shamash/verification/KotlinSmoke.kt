/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
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
package shamash.verification

import io.shamash.artifacts.contract.Finding
import io.shamash.asm.core.config.ValidationError
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.RuleKey
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.config.validation.v1.RuleSpec
import io.shamash.asm.core.engine.ShamashAsmEngine
import io.shamash.asm.core.engine.rules.DefaultRuleRegistry
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.facts.query.FactIndex
import io.shamash.asm.core.scan.ScanOptions
import io.shamash.asm.core.scan.ShamashAsmScanRunner
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    val project = Path.of(args[2])
    JavaSmoke.run(args[0], Path.of(args[1]), project.resolve("java"))
    JavaSmoke.createBytecode(project)
    var executed = false
    val rule =
        object : Rule {
            override val id = "custom.requiredParam"

            override fun evaluate(facts: FactIndex, rule: RuleDef, config: ShamashAsmConfigV1): List<Finding> {
                executed = true
                return emptyList()
            }
        }
    val spec =
        object : RuleSpec {
            override val key = RuleKey(type = "custom", name = "requiredParam")

            override fun validate(rulePath: String, rule: RuleDef, config: ShamashAsmConfigV1): List<ValidationError> =
                if (rule.params["token"] == "accepted") {
                    emptyList()
                } else {
                    listOf(ValidationError(path = "$rulePath.params.token", message = "token must be accepted"))
                }
        }
    val engine = ShamashAsmEngine(registry = DefaultRuleRegistry.create(listOf(rule), listOf(spec)))
    val yaml = JavaSmoke.config()
    check(engine.validateConfig(yaml.reader()).ok)
    val configuration = project.resolve("kotlin.yml")
    Files.writeString(configuration, yaml)
    val result = ShamashAsmScanRunner(engine).run(ScanOptions(projectBasePath = project, configPath = configuration))
    check(result.isSuccess && result.classUnits == 1 && executed) { "Published Kotlin API failed: $result" }
    println("Published Kotlin API and default arguments passed.")
}
