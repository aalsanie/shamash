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
package io.shamash.intellij.plugin.asm.registry

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName
import io.shamash.asm.core.engine.rules.spi.AsmRuleRegistryProvider

/**
 * IntelliJ extension point facade for ASM rule registries.
 *
 * Other plugins can contribute registries by registering implementations of
 * [AsmRuleRegistryProvider] under the extension point:
 *
 *   io.shamash.asmRuleRegistryProvider
 */
object AsmRuleRegistryProviders {
    private val log = Logger.getInstance(AsmRuleRegistryProviders::class.java)

    private val EP: ExtensionPointName<AsmRuleRegistryProvider> =
        ExtensionPointName.create("io.shamash.asmRuleRegistryProvider")

    fun list(): List<AsmRuleRegistryProvider> {
        val seen = LinkedHashMap<String, AsmRuleRegistryProvider>()
        for (p in EP.extensionList) {
            val id = p.id.trim()
            if (id.isEmpty()) {
                log.warn("Ignoring AsmRuleRegistryProvider with blank id: ${p.javaClass.name}")
                continue
            }
            val existing = seen.putIfAbsent(id, p)
            if (existing != null) {
                log.warn(
                    "Duplicate AsmRuleRegistryProvider id '$id'. Keeping first: ${existing.javaClass.name}, ignoring: ${p.javaClass.name}",
                )
            }
        }

        return seen.values.sortedWith(compareBy<AsmRuleRegistryProvider>({ it.displayName.lowercase() }, { it.id }))
    }

    fun findById(id: String): AsmRuleRegistryProvider? {
        val needle = id.trim()
        if (needle.isEmpty()) return null
        return list().firstOrNull { it.id == needle }
    }
}
