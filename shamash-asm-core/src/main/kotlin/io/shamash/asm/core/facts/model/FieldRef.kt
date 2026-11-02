/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.facts.model

import org.objectweb.asm.Opcodes

data class FieldRef(
    val owner: TypeRef,
    val name: String,
    val desc: String,
    val signature: String?,
    val access: Int,
    val fieldType: TypeRef?,
    val annotationsFqns: Set<String>,
    val location: SourceLocation,
) {
    val fqName: String = "${owner.fqName}#$name:$desc"

    val visibility: Visibility =
        when {
            (access and Opcodes.ACC_PUBLIC) != 0 -> Visibility.PUBLIC
            (access and Opcodes.ACC_PROTECTED) != 0 -> Visibility.PROTECTED
            (access and Opcodes.ACC_PRIVATE) != 0 -> Visibility.PRIVATE
            else -> Visibility.PACKAGE_PRIVATE
        }

    val isStatic: Boolean get() = (access and Opcodes.ACC_STATIC) != 0
    val isFinal: Boolean get() = (access and Opcodes.ACC_FINAL) != 0
}
