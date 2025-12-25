package io.shamash.asm.scan

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import java.io.InputStream

object ProjectClassIndex {

    fun readInternalName(input: InputStream): String? {
        return try {
            val cr = ClassReader(input)
            var name: String? = null
            cr.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name0: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?
                ) {
                    name = name0
                }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            name
        } catch (_: Throwable) {
            null
        }
    }
}
