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
package io.shamash.asm.core.facts.model

import org.objectweb.asm.Type

/**
 * Canonical JVM type identity.
 *
 * - For arrays, identity is normalized to the element type with [isArray]=true.
 * - For primitives, [internalName] is the JVM primitive name (e.g. "int").
 */
data class TypeRef(
    val internalName: String,
    val fqName: String,
    val packageName: String,
    val simpleName: String,
    val isArray: Boolean = false,
    val isPrimitive: Boolean = false,
) {
    companion object {
        fun fromInternalName(internalName: String): TypeRef {
            val fqn = internalName.replace('/', '.')
            val pkg = fqn.substringBeforeLast('.', "")
            val simple = fqn.substringAfterLast('.')
            return TypeRef(
                internalName = internalName,
                fqName = fqn,
                packageName = pkg,
                simpleName = simple,
                isArray = false,
                isPrimitive = false,
            )
        }

        fun fromDescriptor(descriptor: String): TypeRef? = fromAsmType(Type.getType(descriptor))

        fun fromAsmType(type: Type): TypeRef? {
            return when (type.sort) {
                Type.VOID -> null
                Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT, Type.FLOAT, Type.LONG, Type.DOUBLE -> {
                    val name = type.className // "int", "boolean", ...
                    TypeRef(
                        internalName = name,
                        fqName = name,
                        packageName = "",
                        simpleName = name,
                        isArray = false,
                        isPrimitive = true,
                    )
                }
                Type.ARRAY -> {
                    val elem = type.elementType
                    val base = fromAsmType(elem) ?: return null
                    base.copy(isArray = true)
                }
                Type.OBJECT -> fromInternalName(type.internalName)
                Type.METHOD -> null
                else -> null
            }
        }

        /**
         * Convert a JVM internal name or descriptor into a dependency target.
         *
         * - primitives and void are excluded (return null)
         * - arrays are normalized to element type (isArray=true)
         */
        fun dependencyTargetFromType(type: Type): TypeRef? {
            val ref = fromAsmType(type) ?: return null
            return if (ref.isPrimitive) null else ref
        }
    }
}
