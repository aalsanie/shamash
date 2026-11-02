/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.facts.model

import org.objectweb.asm.Opcodes

data class ClassFact(
    val type: TypeRef,
    val access: Int,
    val superType: TypeRef?,
    val interfaces: Set<TypeRef>,
    val annotationsFqns: Set<String>,
    val hasMainMethod: Boolean,
    val location: SourceLocation,
) {
    val fqName: String get() = type.fqName
    val packageName: String get() = type.packageName
    val simpleName: String get() = type.simpleName

    val visibility: Visibility =
        when {
            (access and Opcodes.ACC_PUBLIC) != 0 -> Visibility.PUBLIC
            (access and Opcodes.ACC_PROTECTED) != 0 -> Visibility.PROTECTED
            (access and Opcodes.ACC_PRIVATE) != 0 -> Visibility.PRIVATE
            else -> Visibility.PACKAGE_PRIVATE
        }

    val isInterface: Boolean get() = (access and Opcodes.ACC_INTERFACE) != 0
    val isAbstract: Boolean get() = (access and Opcodes.ACC_ABSTRACT) != 0
    val isEnum: Boolean get() = (access and Opcodes.ACC_ENUM) != 0
}

enum class Visibility { PUBLIC, PROTECTED, PACKAGE_PRIVATE, PRIVATE }
