package io.shamash.asm.scan

/**
 * Collapses external references into readable buckets without scanning all dependency classes.
 */
object ExternalBucketResolver {

    data class Bucket(
        val id: String,
        val displayName: String
    )

    fun bucketForInternalName(internalName: String): Bucket {
        val n = internalName.trim()
        if (n.isBlank()) return Bucket("ext:Unknown", "Unknown")

        fun starts(prefix: String) = n.startsWith(prefix)

        return when {
            starts("java/") || starts("javax/") || starts("jdk/") -> Bucket("ext:JDK", "JDK")
            starts("jakarta/") -> Bucket("ext:Jakarta", "Jakarta")
            starts("kotlin/") -> Bucket("ext:Kotlin", "Kotlin")
            starts("scala/") -> Bucket("ext:Scala", "Scala")
            starts("org/springframework/") -> Bucket("ext:Spring", "Spring")
            starts("org/jetbrains/") -> Bucket("ext:JetBrains", "JetBrains")
            starts("com/intellij/") -> Bucket("ext:IntelliJ", "IntelliJ Platform")
            starts("org/apache/") -> Bucket("ext:Apache", "Apache")
            starts("com/google/") -> Bucket("ext:Google", "Google")
            else -> {
                // fallback: first 1–2 package segments (org/foo, com/bar)
                val parts = n.split('/')
                val head = parts.firstOrNull().orEmpty()
                val second = parts.getOrNull(1)
                val label = if (second != null) "$head/$second" else head
                Bucket("ext:$label", label)
            }
        }
    }
}
