/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.facts.model

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

data class MethodRef(
    val owner: TypeRef,
    val name: String,
    val desc: String,
    val signature: String?,
    val access: Int,
    val isConstructor: Boolean,
    val returnType: TypeRef?,
    val parameterTypes: List<TypeRef>,
    val throwsTypes: List<TypeRef>,
    val annotationsFqns: Set<String>,
    val location: SourceLocation,
) {
    val fqName: String = "${owner.fqName}#${name}$desc"

    val visibility: Visibility =
        when {
            (access and Opcodes.ACC_PUBLIC) != 0 -> Visibility.PUBLIC
            (access and Opcodes.ACC_PROTECTED) != 0 -> Visibility.PROTECTED
            (access and Opcodes.ACC_PRIVATE) != 0 -> Visibility.PRIVATE
            else -> Visibility.PACKAGE_PRIVATE
        }

    val isStatic: Boolean get() = (access and Opcodes.ACC_STATIC) != 0
    val isAbstract: Boolean get() = (access and Opcodes.ACC_ABSTRACT) != 0

    companion object {
        fun isMain(
            access: Int,
            name: String,
            desc: String,
        ): Boolean {
            if (name != "main") return false
            val isPublic = (access and Opcodes.ACC_PUBLIC) != 0
            val isStatic = (access and Opcodes.ACC_STATIC) != 0
            if (!isPublic || !isStatic) return false

            val t = Type.getMethodType(desc)
            if (t.returnType.sort != Type.VOID) return false
            val args = t.argumentTypes
            if (args.size != 1) return false
            val a0 = args[0]
            return a0.sort == Type.ARRAY && a0.elementType.sort == Type.OBJECT && a0.elementType.internalName == "java/lang/String"
        }
    }
}
