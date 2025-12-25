package io.shamash.asm.model

import io.shamash.asm.scan.ExternalBucketResolver

data class AsmIndex(
    val classes: Map<String, AsmClassInfo>,
    val externalBuckets: List<ExternalBucketResolver.Bucket> = emptyList()
) {
    val references: Map<String, Set<String>> =
        classes.mapValues { (_, v) -> v.referencedInternalNames }
}
