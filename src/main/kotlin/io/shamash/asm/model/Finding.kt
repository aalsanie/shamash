package io.shamash.asm.model

data class Finding(
    val id: String,
    val title: String,
    val severity: Severity,
    val fqcn: String?,
    val module: String?,
    val message: String,
    val evidence: Map<String, Any> = emptyMap()
)
