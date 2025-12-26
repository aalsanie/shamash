package io.shamash.asm.model

/**
 * ASM-derived class metadata: source-independent.
 *
 * Names are stored in internal form: slash-separated to match ASM APIs.
 *
 */
// treat this class as a schema and be careful of backward incompatible changes
data class AsmClassInfo(
    val internalName: String,
    val access: Int,
    val superInternalName: String?,
    val interfaceInternalNames: List<String>,
    val methods: List<AsmMethodInfo>,
    val referencedInternalNames: Set<String>,

    //Tab hotspots
    val fieldCount: Int ,
    val instructionCount: Int,

    val origin: AsmOrigin,
    val originPath: String,
    val moduleName: String? = null,
    val originDisplayName: String
) {
    val fqcn: String = internalName.replace('/', '.')
}
