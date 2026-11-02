/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.facts.model

import org.objectweb.asm.Type

/**
 * Arrays use their element type identity with [isArray] set.
 * Primitive [internalName] values are JVM names such as `int`.
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
                Type.VOID -> {
                    null
                }

                Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT, Type.FLOAT, Type.LONG, Type.DOUBLE -> {
                    val name = type.className
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

                Type.OBJECT -> {
                    fromInternalName(type.internalName)
                }

                Type.METHOD -> {
                    null
                }

                else -> {
                    null
                }
            }
        }

        /** Returns null for primitives/void; reference arrays use their element type with isArray=true. */
        fun dependencyTargetFromType(type: Type): TypeRef? {
            val ref = fromAsmType(type) ?: return null
            return if (ref.isPrimitive) null else ref
        }
    }
}
