package io.shamash.asm.scan

import io.shamash.asm.model.AsmClassInfo
import io.shamash.asm.model.AsmMethodInfo
import io.shamash.asm.util.AsmTypeUtil
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor

/**
 * Collects:
 * - hierarchy (super + interfaces)
 * - methods
 * - referenced class internal names
 */
class CollectingClassVisitor : ClassVisitor(Opcodes.ASM9) {

    private var name: String? = null
    private var access: Int = 0
    private var superName: String? = null
    private var interfaces: List<String> = emptyList()

    private val methods = mutableListOf<AsmMethodInfo>()
    private val references = mutableSetOf<String>()

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
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

    override fun visitOuterClass(owner: String?, name: String?, descriptor: String?) {
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

    override fun visitAnnotation(descriptor: String?, visible: Boolean) =
        super.visitAnnotation(descriptor, visible)?.also {
            descriptor?.let { d -> references.addAll(AsmTypeUtil.internalNamesFromDescriptor(d)) }
        }

    override fun visitField(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        value: Any?
    ) = super.visitField(access, name, descriptor, signature, value)?.also {
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
        exceptions: Array<out String>?
    ) = super.visitMethod(access, name, descriptor, signature, exceptions)?.also { mv ->
        if (name != null && descriptor != null) {
            methods.add(AsmMethodInfo(name, descriptor, access))
            references.addAll(AsmTypeUtil.internalNamesFromMethodDescriptor(descriptor))
        }
        exceptions?.forEach { references.add(it) }
        signature?.let { collectFromSignature(it) }

        return object : org.objectweb.asm.MethodVisitor(Opcodes.ASM9, mv) {

            override fun visitTypeInsn(opcode: Int, type: String?) {
                type?.let { references.add(it) }
                super.visitTypeInsn(opcode, type)
            }

            override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) {
                owner?.let { references.add(it) }
                descriptor?.let { references.addAll(AsmTypeUtil.internalNamesFromDescriptor(it)) }
                super.visitFieldInsn(opcode, owner, name, descriptor)
            }

            override fun visitMethodInsn(
                opcode: Int,
                owner: String?,
                name: String?,
                descriptor: String?,
                isInterface: Boolean
            ) {
                owner?.let { references.add(it) }
                descriptor?.let { references.addAll(AsmTypeUtil.internalNamesFromMethodDescriptor(it)) }
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            }

            override fun visitInvokeDynamicInsn(
                name: String?,
                descriptor: String?,
                bootstrapMethodHandle: Handle?,
                vararg bootstrapMethodArguments: Any?
            ) {
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
                when (value) {
                    is Type -> references.add(value.internalName)
                }
                super.visitLdcInsn(value)
            }

            override fun visitMultiANewArrayInsn(descriptor: String?, numDimensions: Int) {
                descriptor?.let { references.addAll(AsmTypeUtil.internalNamesFromDescriptor(it)) }
                super.visitMultiANewArrayInsn(descriptor, numDimensions)
            }

            override fun visitTryCatchBlock(start: Label?, end: Label?, handler: Label?, type: String?) {
                type?.let { references.add(it) }
                super.visitTryCatchBlock(start, end, handler, type)
            }
        }
    }

    fun toClassInfo(
        originPath: String,
        originDisplayName: String,
        origin: io.shamash.asm.model.AsmOrigin
    ): AsmClassInfo? {
        val n = name ?: return null
        val cleanedRefs = references
            .filter { it.isNotBlank() }
            .filterNot { it == n }
            .toSet()

        return AsmClassInfo(
            internalName = n,
            access = access,
            superInternalName = superName,
            interfaceInternalNames = interfaces,
            methods = methods.toList(),
            referencedInternalNames = cleanedRefs,
            origin = origin,
            originPath = originPath,
            originDisplayName = originDisplayName
        )
    }

    private fun collectFromSignature(signature: String) {
        try {
            SignatureReader(signature).accept(object : SignatureVisitor(Opcodes.ASM9) {
                override fun visitClassType(name: String?) {
                    name?.let { references.add(it) }
                    super.visitClassType(name)
                }
            })
        } catch (_: Throwable) {
            // Ignore signature parsing failures.
        }
    }
}
