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
package io.shamash.asm.core

import io.shamash.artifacts.contract.Finding
import io.shamash.asm.core.config.ConfigValidation
import io.shamash.asm.core.config.ConfigValidator
import io.shamash.asm.core.config.SchemaValidator
import io.shamash.asm.core.config.ValidationError
import io.shamash.asm.core.config.ValidationSeverity
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.RuleKey
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.config.validation.v1.RuleSpec
import io.shamash.asm.core.engine.rules.DefaultRuleRegistry
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.RuleRegistry
import io.shamash.asm.core.facts.FactsError
import io.shamash.asm.core.facts.query.FactIndex
import io.shamash.asm.core.scan.ScanError
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RegistryLibraryApiTest {
    private class RecordingRule(
        override val id: String = "custom.requiredParam",
    ) : Rule {
        var executions = 0

        override fun evaluate(
            facts: FactIndex,
            rule: RuleDef,
            config: ShamashAsmConfigV1,
        ): List<Finding> {
            executions++
            return emptyList()
        }
    }

    private open class RequiredParamSpec : RuleSpec {
        override val key = RuleKey("custom", "requiredParam")

        override fun validate(
            rulePath: String,
            rule: RuleDef,
            config: ShamashAsmConfigV1,
        ): List<ValidationError> =
            if (rule.params["token"] == "accepted") {
                emptyList()
            } else {
                listOf(ValidationError("$rulePath.params.token", "token must be accepted"))
            }
    }

    @Test
    fun `custom registry validates parameters and executes through Java API`() {
        withProject { project ->
            val rule = RecordingRule()
            val registry = JavaApiConsumer.registry(listOf(rule), listOf(RequiredParamSpec()))
            val validation = JavaApiConsumer.validate(StringReader(configYaml()), registry)

            assertTrue(validation.ok, validation.errors.toString())
            val config = assertNotNull(validation.config)
            assertTrue(ConfigValidator.validateSemantic(config, registry).isEmpty())
            assertSame(rule, registry.resolve(config.rules.single().copy(type = " custom ", name = " requiredParam ")))
            assertFalse(ConfigValidation.loadAndValidateV1(StringReader(configYaml())).ok)

            val result = JavaApiConsumer.run(project, registry)

            assertTrue(result.isSuccess, result.toString())
            assertEquals(1, result.classUnits)
            assertEquals(1, rule.executions)
        }
    }

    @Test
    fun `custom parameter errors stop execution before bytecode scanning`() {
        withProject(configYaml(token = "rejected")) { project ->
            val rule = RecordingRule()
            val registry = JavaApiConsumer.registry(listOf(rule), listOf(RequiredParamSpec()))

            val result = JavaApiConsumer.run(project, registry)

            assertTrue(result.hasConfigErrors)
            assertTrue(result.configErrors.any { it.path == "rules[0].params.token" })
            assertFalse(result.isSuccess)
            assertNull(result.engine)
            assertEquals(0, result.classUnits)
            assertEquals(0, rule.executions)
        }
    }

    @Test
    fun `a spec alone or a rule with a longer id does not prove executability`() {
        val candidates = listOf(emptyList(), listOf(RecordingRule("custom.requiredParam.child")))
        for (rules in candidates) {
            val registry =
                object : RuleRegistry {
                    override fun all(): List<Rule> = rules

                    override fun byId(ruleId: String): Rule? = rules.find { it.id == ruleId }

                    override fun findSpec(
                        type: String,
                        name: String,
                    ): RuleSpec = RequiredParamSpec()
                }

            val result = JavaApiConsumer.validate(StringReader(configYaml()), registry)

            assertFalse(result.ok)
            assertTrue(result.errors.any { it.severity == ValidationSeverity.ERROR && it.message.contains("not implemented") })
        }
    }

    @Test
    fun `custom spec exceptions become validation errors even under WARN`() {
        val broken =
            object : RequiredParamSpec() {
                override fun validate(
                    rulePath: String,
                    rule: RuleDef,
                    config: ShamashAsmConfigV1,
                ): List<ValidationError> = error("broken custom validator")
            }
        val registry = JavaApiConsumer.registry(listOf(RecordingRule()), listOf(broken))

        val result = JavaApiConsumer.validate(StringReader(configYaml(policy = "WARN")), registry)

        assertFalse(result.ok)
        assertTrue(result.errors.any { it.path == "rules[0]" && it.message.contains("broken custom validator") })
    }

    @Test
    fun `custom spec lookup exceptions become validation errors`() {
        val delegate = DefaultRuleRegistry.create(listOf(RecordingRule()))
        val registry =
            object : RuleRegistry by delegate {
                override fun findSpec(
                    type: String,
                    name: String,
                ): RuleSpec? = error("broken spec lookup")
            }

        val result = JavaApiConsumer.validate(StringReader(configYaml(policy = "WARN")), registry)

        assertFalse(result.ok)
        assertTrue(result.errors.any { it.path == "rules[0]" && it.message.contains("broken spec lookup") })
    }

    @Test
    fun `registry overload still applies structural validation`() {
        val validator =
            object : SchemaValidator {
                override fun validate(raw: Any?): List<ValidationError> = listOf(ValidationError("version", "schema rejected input"))
            }
        val registry = JavaApiConsumer.registry(listOf(RecordingRule()), listOf(RequiredParamSpec()))

        val result = ConfigValidation.loadAndValidateV1(StringReader(configYaml()), registry, validator)

        assertFalse(result.ok)
        assertNull(result.config)
        assertEquals(listOf(ValidationError("version", "schema rejected input")), result.errors)
    }

    @Test
    fun `warnings do not mark a complete scan as failed`() {
        withProject(configYaml(policy = "WARN")) { project ->
            val rule = RecordingRule()
            val registry = DefaultRuleRegistry.create(listOf(rule))

            val result = JavaApiConsumer.run(project, registry)

            assertTrue(result.hasConfigWarnings)
            assertFalse(result.hasConfigErrors)
            assertTrue(result.isSuccess, result.toString())
            assertEquals(1, rule.executions)
        }
    }

    @Test
    fun `partial or failed scan stages cannot report success`() {
        withProject { project ->
            val registry = JavaApiConsumer.registry(listOf(RecordingRule()), listOf(RequiredParamSpec()))
            val result = JavaApiConsumer.run(project, registry)
            assertTrue(result.isSuccess, result.toString())

            assertFalse(result.copy(truncated = true).isSuccess)
            assertFalse(result.copy(scanErrors = listOf(ScanError(ScanError.Phase.BYTECODE_SCAN, "unreadable class"))).isSuccess)
            assertFalse(result.copy(factsErrors = listOf(FactsError("fixture", "class:visit", "invalid bytecode"))).isSuccess)
            assertFalse(result.copy(configErrors = listOf(ValidationError("rules", "invalid rules"))).isSuccess)
            assertFalse(result.copy(engine = null).isSuccess)
        }
    }

    @Test
    fun `existing Java registry implementations inherit built in specs`() {
        val registry = JavaApiConsumer.legacyRegistry()

        assertNotNull(registry.findSpec("graph", "noCycles"))
        assertNotNull(registry.byId("graph.noCycles"))
        assertEquals(registry.all().map { it.id }.sorted(), registry.all().map { it.id })
    }

    private fun withProject(
        yaml: String = configYaml(),
        action: (Path) -> Unit,
    ) {
        val project = Files.createTempDirectory("shamash-library-api")
        try {
            val output = project.resolve("build/classes/java/main/com/example/App.class")
            Files.createDirectories(output.parent)
            val writer = ClassWriter(0)
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/App", null, "java/lang/Object", null)
            writer.visitEnd()
            Files.write(output, writer.toByteArray())
            val config = project.resolve("shamash/configs/asm.yml")
            Files.createDirectories(config.parent)
            Files.writeString(config, yaml)
            action(project)
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    private fun configYaml(
        policy: String = "ERROR",
        token: String = "accepted",
    ): String =
        """
        version: 1
        project:
          bytecode:
            roots: ["build/classes/java/main"]
            outputsGlobs:
              include: ["**"]
              exclude: []
            jarGlobs:
              include: ["**/*.jar"]
              exclude: ["**/*"]
          scan:
            scope: PROJECT_ONLY
            followSymlinks: false
            maxClasses: null
            maxJarBytes: null
            maxClassBytes: null
          validation:
            unknownRule: $policy
        roles: {}
        analysis:
          graphs:
            enabled: false
            granularity: PACKAGE
            includeExternalBuckets: false
          hotspots:
            enabled: false
            topN: 10
            includeExternal: false
          scoring:
            enabled: false
            model: V1
            godClass:
              enabled: false
              weights: null
              thresholds: null
            overall:
              enabled: false
              weights: null
              thresholds: null
        rules:
          - type: custom
            name: requiredParam
            roles: null
            enabled: true
            severity: ERROR
            params:
              token: $token
        exceptions: []
        baseline:
          mode: NONE
          path: .shamash/baseline.json
        export:
          enabled: false
          outputDir: .shamash/reports/asm
          formats: [JSON]
          overwrite: true
        """.trimIndent()
}
