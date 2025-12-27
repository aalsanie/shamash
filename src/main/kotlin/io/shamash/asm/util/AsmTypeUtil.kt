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
package io.shamash.asm.util

import org.objectweb.asm.Type

object AsmTypeUtil {
    fun internalNameToFqcn(internalName: String): String = internalName.replace('/', '.')

    /**
     * Extracts internal class names from a descriptor.
     */
    fun internalNamesFromDescriptor(descriptor: String): Set<String> {
        val result = mutableSetOf<String>()
        try {
            val type = Type.getType(descriptor)
            collect(type, result)
        } catch (_: Throwable) {
            // Keep scanner resilient; malformed descriptors should not crash the scan.
        }
        return result
    }

    fun internalNamesFromMethodDescriptor(methodDescriptor: String): Set<String> {
        val result = mutableSetOf<String>()
        try {
            Type.getArgumentTypes(methodDescriptor).forEach { collect(it, result) }
            collect(Type.getReturnType(methodDescriptor), result)
        } catch (_: Throwable) {
        }
        return result
    }

    private fun collect(
        type: Type,
        out: MutableSet<String>,
    ) {
        when (type.sort) {
            Type.ARRAY -> collect(type.elementType, out)
            Type.OBJECT -> out.add(type.internalName)
        }
    }
}
