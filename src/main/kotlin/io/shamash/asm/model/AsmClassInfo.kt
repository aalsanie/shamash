package io.shamash.asm.model

/**
 * ASM-derived class metadata (source-independent).
 *
 * Names are stored in internal form (slash-separated) to match ASM APIs.
 */
data class AsmClassInfo(
    val internalName: String,
    val access: Int,
    val superInternalName: String?,
    val interfaceInternalNames: List<String>,
    val methods: List<AsmMethodInfo>,
    val referencedInternalNames: Set<String>,
    val origin: AsmOrigin,
    val originPath: String,
    val moduleName: String? = null,
    val originDisplayName: String
) {
    val fqcn: String = internalName.replace('/', '.')
}
