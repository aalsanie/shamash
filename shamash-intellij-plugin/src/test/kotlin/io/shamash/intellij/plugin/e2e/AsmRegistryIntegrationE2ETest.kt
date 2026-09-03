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
package io.shamash.intellij.plugin.e2e

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import io.shamash.artifacts.contract.Finding
import io.shamash.asm.core.config.ValidationError
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.RuleKey
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.config.validation.v1.RuleSpec
import io.shamash.asm.core.engine.rules.DefaultRuleRegistry
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.RuleRegistry
import io.shamash.asm.core.engine.rules.spi.AsmRuleRegistryProvider
import io.shamash.asm.core.facts.query.FactIndex
import io.shamash.intellij.plugin.asm.registry.AsmRuleRegistryProviders
import io.shamash.intellij.plugin.asm.ui.actions.AsmExecution
import io.shamash.intellij.plugin.asm.ui.actions.ShamashAsmUiStateService
import io.shamash.intellij.plugin.asm.ui.actions.ValidateAsmConfigAction
import io.shamash.intellij.plugin.asm.ui.settings.ShamashAsmSettingsState
import kotlin.test.assertFailsWith

class AsmRegistryIntegrationE2ETest : ShamashPluginE2eTestBase() {
    private val extensionPoint =
        ExtensionPointName.create<AsmRuleRegistryProvider>("io.shamash.asmRuleRegistryProvider")

    fun testSelectedRegistrySuppliesConfigurationSpecs() {
        install(provider(" custom "))
        ShamashAsmSettingsState.getInstance(project).setRegistryId("custom")

        val result = AsmExecution.createEngine(project).validateConfig(config().reader())

        assertTrue(result.errors.toString(), result.ok)
        assertNotNull(result.config)
    }

    fun testUnknownSelectedRegistryDoesNotFallBackToBuiltins() {
        install(provider("custom"))
        ShamashAsmSettingsState.getInstance(project).setRegistryId("missing")

        val failure = assertFailsWith<IllegalStateException> { AsmExecution.createEngine(project) }

        assertTrue(failure.message.orEmpty().contains("missing"))
    }

    fun testDuplicateProviderIdsAreRejected() {
        install(provider("custom"), provider(" custom "))

        assertFailsWith<IllegalStateException> { AsmRuleRegistryProviders.findById("custom") }
    }

    fun testValidationActionUsesSelectedRegistryParameterValidation() {
        install(provider("custom"))
        val file =
            myFixture.addFileToProject(
                "shamash/configs/custom-registry.yml",
                config().replace("token: accepted", "token: rejected"),
            )
        val settings = ShamashAsmSettingsState.getInstance(project)
        settings.state.configPath = file.virtualFile.path
        settings.setRegistryId("custom")
        val state = ShamashAsmUiStateService.getInstance(project)
        state.clear()

        fire(ValidateAsmConfigAction())

        val deadline = System.currentTimeMillis() + 10_000
        while (state.getScanResult() == null && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        val result = requireNotNull(state.getScanResult()) { "Validation action did not publish its result" }
        assertTrue(result.configErrors.toString(), result.configErrors.any { it.path == "rules[0].params.token" })
        assertTrue(result.hasConfigErrors)
    }

    private fun config(): String =
        requireNotNull(javaClass.getResourceAsStream("/asm/custom-registry.yml")).bufferedReader().use { it.readText() }

    private fun install(vararg providers: AsmRuleRegistryProvider) {
        ExtensionTestUtil.maskExtensions(extensionPoint, providers.toList(), testRootDisposable)
    }

    private fun provider(providerId: String): AsmRuleRegistryProvider =
        object : AsmRuleRegistryProvider {
            override val id = providerId
            override val displayName = "Custom test registry"

            override fun create(): RuleRegistry {
                val rule =
                    object : Rule {
                        override val id = "custom.requiredParam"

                        override fun evaluate(
                            facts: FactIndex,
                            rule: RuleDef,
                            config: ShamashAsmConfigV1,
                        ): List<Finding> = emptyList()
                    }
                val spec =
                    object : RuleSpec {
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
                return DefaultRuleRegistry.create(listOf(rule), listOf(spec))
            }
        }
}
