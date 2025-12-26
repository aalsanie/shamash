package io.shamash.asm.scan

import com.intellij.openapi.project.Project
import io.shamash.asm.model.AsmClassInfo
import io.shamash.asm.model.AsmIndex
import org.objectweb.asm.ClassReader
import java.io.File
import java.io.InputStream
import java.util.jar.JarFile

/**
 * ASM:
 * - PROJECT_WITH_EXTERNAL_BUCKETS (default): scan only module output dirs, collapse external deps into buckets.
 * - PROJECT_ONLY: scan only module output dirs, no bucketing.
 * - ALL_SOURCES: scan outputs + dependency jars (can be huge).
 */
object AsmScanner {

    fun scan(project: Project, scope: ScanScope = ScanScope.PROJECT_WITH_EXTERNAL_BUCKETS): AsmIndex {
        val sources = ClassFileLocator.collectSources(project)
        return scan(sources, scope)
    }

    fun scan(sources: List<ClassFileSource>, scope: ScanScope = ScanScope.PROJECT_WITH_EXTERNAL_BUCKETS): AsmIndex {
        val out = linkedMapOf<String, AsmClassInfo>()

        val dirs = sources.filterIsInstance<ClassFileSource.Directory>()
        val jars = sources.filterIsInstance<ClassFileSource.Jar>()

        // Always scan project outputs first.
        dirs.forEach { scanDirectory(it, out) }

        // Optional: scan jars too (debug mode only).
        if (scope == ScanScope.ALL_SOURCES) {
            jars.forEach { scanJar(it, out) }
        }

        // Build external buckets from references (without scanning jar classes).
        val externalBuckets =
            if (scope == ScanScope.PROJECT_WITH_EXTERNAL_BUCKETS) {
                collapseExternalBuckets(out)
            } else {
                emptyList()
            }

        return AsmIndex(
            classes = out.toMap(),
            externalBuckets = externalBuckets
        )
    }

    private fun collapseExternalBuckets(out: Map<String, AsmClassInfo>): List<ExternalBucketResolver.Bucket> {
        val projectSet = out.keys

        val buckets = linkedMapOf<String, ExternalBucketResolver.Bucket>()
        for (info in out.values) {
            for (ref in info.referencedInternalNames) {
                if (ref in projectSet) continue
                val bucket = ExternalBucketResolver.bucketForInternalName(ref)
                buckets.putIfAbsent(bucket.id, bucket)
            }
        }
        return buckets.values.toList()
    }

    private fun scanDirectory(
        source: ClassFileSource.Directory,
        out: MutableMap<String, AsmClassInfo>
    ) {
        val root = File(source.path)
        if (!root.isDirectory) return

        root.walkTopDown()
            .filter { it.isFile && it.extension.equals("class", ignoreCase = true) }
            .forEach { file ->
                if (file.name == "module-info.class" || file.name == "package-info.class") return@forEach
                file.inputStream().use { ins ->
                    parseClass(ins, source, out)
                }
            }
    }

    private fun scanJar(
        source: ClassFileSource.Jar,
        out: MutableMap<String, AsmClassInfo>
    ) {
        val jarFile = File(source.path)
        if (!jarFile.isFile) return

        try {
            JarFile(jarFile).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    if (!e.name.endsWith(".class")) continue
                    if (e.name.endsWith("module-info.class") || e.name.endsWith("package-info.class")) continue

                    jar.getInputStream(e).use { ins ->
                        parseClass(ins, source, out)
                    }
                }
            }
        } catch (_: Throwable) {
            // Be resilient: corrupt jars or locked files should not crash scanning.
        }
    }

    private fun parseClass(
        input: InputStream,
        source: ClassFileSource,
        out: MutableMap<String, AsmClassInfo>
    ) {
        try {
            val reader = ClassReader(input)
            val visitor = CollectingClassVisitor()
            reader.accept(visitor, ClassReader.SKIP_FRAMES)

            val info = visitor.toClassInfo(
                originPath = source.path,
                originDisplayName = source.displayName,
                origin = source.origin
            ) ?: return
            val moduleName =
                if (source.origin == io.shamash.asm.model.AsmOrigin.MODULE_OUTPUT) {
                    source.displayName.substringBefore(":").trim().ifBlank { null }
                } else null

            val decorated = if (moduleName != null && info.moduleName != moduleName) {
                info.copy(moduleName = moduleName)
            } else info

            val existing = out[decorated.internalName]
            if (existing == null || existing.origin != io.shamash.asm.model.AsmOrigin.MODULE_OUTPUT) {
                out[info.internalName] = info
            }
        } catch (_: Throwable) {
            // Ignore class parse errors.
        }
    }
}
