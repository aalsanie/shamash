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
package io.shamash.asm.scan

import io.shamash.asm.model.AsmClassInfo
import io.shamash.asm.model.AsmMethodInfo
import io.shamash.asm.model.AsmOrigin
import io.shamash.asm.util.AsmTypeUtil
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor

/**
 * Collects:
 * - main search dashboard tab
 * - hierarchy (super + interfaces)
 * - methods
 * - referenced class internal names
 * - hotspot
 */
class CollectingClassVisitor : ClassVisitor(Opcodes.ASM9) {
    // hierarchy & search main dashboard tab
    private var name: String? = null
    private var access: Int = 0
    private var superName: String? = null
    private var interfaces: List<String> = emptyList()

    // hotspot tab
    private var fieldCount: Int = 0
    private var instructionCount: Int = 0

    private val methods = mutableListOf<AsmMethodInfo>()
    private val references = mutableSetOf<String>()

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
        this.name = name
        this.access = access
        this.superName = superName
        this.interfaces = interfaces?.toList() ?: emptyList()

        // Direct hierarchy references.
        superName?.let { references.add(it) }
        this.interfaces.forEach { references.add(it) }

        // Generic signature references.
        signature?.let { collectFromSignature(it) }
    }

    override fun visitOuterClass(
        owner: String?,
        name: String?,
        descriptor: String?,
    ) {
        owner?.let { references.add(it) }
        descriptor?.let { references.addAll(AsmTypeUtil.internalNamesFromMethodDescriptor(it)) }
    }

    override fun visitNestHost(nestHost: String?) {
        nestHost?.let { references.add(it) }
    }

    override fun visitNestMember(nestMember: String?) {
        nestMember?.let { references.add(it) }
    }

    override fun visitPermittedSubclass(permittedSubclass: String?) {
        permittedSubclass?.let { references.add(it) }
    }

    override fun visitAnnotation(
        descriptor: String?,
        visible: Boolean,
    ) = super.visitAnnotation(descriptor, visible)?.also {
        descriptor?.let { d -> references.addAll(AsmTypeUtil.internalNamesFromDescriptor(d)) }
    }

    override fun visitField(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        value: Any?,
    ) = super.visitField(access, name, descriptor, signature, value)?.also {
        fieldCount++ // NEW
        descriptor?.let { d -> references.addAll(AsmTypeUtil.internalNamesFromDescriptor(d)) }
        signature?.let { collectFromSignature(it) }
        when (value) {
            is Type -> references.add(value.internalName)
        }
    }

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null

        if (name != null && descriptor != null) {
            methods.add(AsmMethodInfo(name, descriptor, access))
            references.addAll(AsmTypeUtil.internalNamesFromMethodDescriptor(descriptor))
        }
        exceptions?.forEach { references.add(it) }
        signature?.let { collectFromSignature(it) }

        // wrap mv to count "instructions" deterministically and collect refs hidden in bytecode.
        return object : MethodVisitor(Opcodes.ASM9, mv) {
            private fun bump() {
                instructionCount++
            }

            override fun visitInsn(opcode: Int) {
                bump()
                super.visitInsn(opcode)
            }

            override fun visitIntInsn(
                opcode: Int,
                operand: Int,
            ) {
                bump()
                super.visitIntInsn(opcode, operand)
            }

            override fun visitVarInsn(
                opcode: Int,
                `var`: Int,
            ) {
                bump()
                super.visitVarInsn(opcode, `var`)
            }

            override fun visitJumpInsn(
                opcode: Int,
                label: Label?,
            ) {
                bump()
                super.visitJumpInsn(opcode, label)
            }

            override fun visitIincInsn(
                `var`: Int,
                increment: Int,
            ) {
                bump()
                super.visitIincInsn(`var`, increment)
            }

            override fun visitTableSwitchInsn(
                min: Int,
                max: Int,
                dflt: Label?,
                vararg labels: Label?,
            ) {
                bump()
                super.visitTableSwitchInsn(min, max, dflt, *labels)
            }

            override fun visitLookupSwitchInsn(
                dflt: Label?,
                keys: IntArray?,
                labels: Array<out Label>?,
            ) {
                bump()
                super.visitLookupSwitchInsn(dflt, keys, labels)
            }

            override fun visitTypeInsn(
                opcode: Int,
                type: String?,
            ) {
                bump()
                type?.let { references.add(it) }
                super.visitTypeInsn(opcode, type)
            }

            override fun visitFieldInsn(
                opcode: Int,
                owner: String?,
                name: String?,
                descriptor: String?,
            ) {
                bump()
                owner?.let { references.add(it) }
                descriptor?.let { references.addAll(AsmTypeUtil.internalNamesFromDescriptor(it)) }
                super.visitFieldInsn(opcode, owner, name, descriptor)
            }

            override fun visitMethodInsn(
                opcode: Int,
                owner: String?,
                name: String?,
                descriptor: String?,
                isInterface: Boolean,
            ) {
                bump()
                owner?.let { references.add(it) }
                descriptor?.let { references.addAll(AsmTypeUtil.internalNamesFromMethodDescriptor(it)) }
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            }

            override fun visitInvokeDynamicInsn(
                name: String?,
                descriptor: String?,
                bootstrapMethodHandle: Handle?,
                vararg bootstrapMethodArguments: Any?,
            ) {
                bump()
                descriptor?.let { references.addAll(AsmTypeUtil.internalNamesFromMethodDescriptor(it)) }
                bootstrapMethodHandle?.owner?.let { references.add(it) }
                bootstrapMethodArguments.forEach { arg ->
                    when (arg) {
                        is Type -> references.add(arg.internalName)
                        is Handle -> arg.owner?.let { references.add(it) }
                    }
                }
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
            }

            override fun visitLdcInsn(value: Any?) {
                bump()
                if (value is Type) references.add(value.internalName)
                super.visitLdcInsn(value)
            }

            override fun visitMultiANewArrayInsn(
                descriptor: String?,
                numDimensions: Int,
            ) {
                bump()
                descriptor?.let { references.addAll(AsmTypeUtil.internalNamesFromDescriptor(it)) }
                super.visitMultiANewArrayInsn(descriptor, numDimensions)
            }

            override fun visitTryCatchBlock(
                start: Label?,
                end: Label?,
                handler: Label?,
                type: String?,
            ) {
                type?.let { references.add(it) }
                super.visitTryCatchBlock(start, end, handler, type)
            }
        }
    }

    fun toClassInfo(
        originPath: String,
        originDisplayName: String,
        origin: AsmOrigin,
    ): AsmClassInfo? {
        val n = name ?: return null
        val cleanedRefs =
            references
                .filter { it.isNotBlank() }
                .filterNot { it == n }
                .toSet()

        // Don't dare and add module name here! asm scanner decorates it!
        return AsmClassInfo(
            internalName = n,
            access = access,
            superInternalName = superName,
            interfaceInternalNames = interfaces,
            methods = methods.toList(),
            referencedInternalNames = cleanedRefs,
            origin = origin,
            originPath = originPath,
            originDisplayName = originDisplayName,
            fieldCount = fieldCount,
            instructionCount = instructionCount,
        )
    }

    private fun collectFromSignature(signature: String) {
        try {
            SignatureReader(signature).accept(
                object : SignatureVisitor(Opcodes.ASM9) {
                    override fun visitClassType(name: String?) {
                        name?.let { references.add(it) }
                        super.visitClassType(name)
                    }
                },
            )
        } catch (_: Throwable) {
            // Ignore signature parsing failures.
        }
    }
}
