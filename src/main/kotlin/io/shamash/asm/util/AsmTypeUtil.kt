package io.shamash.asm.util

import org.objectweb.asm.Type

object AsmTypeUtil {

    fun internalNameToFqcn(internalName: String): String =
        internalName.replace('/', '.')

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

    private fun collect(type: Type, out: MutableSet<String>) {
        when (type.sort) {
            Type.ARRAY -> collect(type.elementType, out)
            Type.OBJECT -> out.add(type.internalName)
        }
    }
}
