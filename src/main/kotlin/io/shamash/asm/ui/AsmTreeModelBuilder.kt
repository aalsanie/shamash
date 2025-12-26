package io.shamash.asm.ui

import io.shamash.asm.model.AsmIndex
import io.shamash.asm.scan.ExternalBucketResolver
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Builds the Tree model for ASM dashboard.
 *
 * Root:
 *  - Project
 *    - packages/classes
 *  - External Buckets
 *    - bucket (count)
 */
object AsmTreeModelBuilder {

    fun buildRoot(index: AsmIndex): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode("Shamash scan")
        root.add(buildProjectNode(index))
        root.add(buildExternalBucketsNode(index))
        return root
    }

    fun buildProjectNode(index: AsmIndex): DefaultMutableTreeNode {
        val projectNode = DefaultMutableTreeNode("Project")

        val classes = index.classes
        if (classes.isEmpty()) {
            projectNode.add(DefaultMutableTreeNode("No project classes indexed"))
            return projectNode
        }

        val packageRoot = DefaultMutableTreeNode("(packages)")
        projectNode.add(packageRoot)

        classes
            .values
            .sortedBy { it.internalName }
            .forEach { info ->
                addToPackageTree(packageRoot, info.internalName.replace('/', '.'))
            }

        return projectNode
    }

    //external libraries count including jdk, spring bla bla etc.. "I'm bored!" but we shouldn't be concerned
    //TODO: if we are? then we add it as a feature to Shamash 2.0
    fun buildExternalBucketsNode(index: AsmIndex): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode("External Buckets")

        val buckets: List<ExternalBucketResolver.Bucket> = index.externalBuckets
        if (buckets.isEmpty()) {
            root.add(DefaultMutableTreeNode("No external buckets captured yet"))
            return root
        }

        // Count edges from project classes -> bucket (based on internal names)
        val projectSet = index.classes.keys
        val countsByBucketId = linkedMapOf<String, Int>()

        for ((_, refs) in index.references) {
            for (ref in refs) {
                if (ref in projectSet) continue
                val bucket = ExternalBucketResolver.bucketForInternalName(ref)
                countsByBucketId[bucket.id] = (countsByBucketId[bucket.id] ?: 0) + 1
            }
        }

        // Ensure every known bucket appears even if its count is 0
        for (b in buckets) {
            countsByBucketId.putIfAbsent(b.id, 0)
        }

        buckets
            .sortedWith(
                compareByDescending<ExternalBucketResolver.Bucket> { countsByBucketId[it.id] ?: 0 }
                    .thenBy { it.displayName }
            )
            .forEach { bucket ->
                val c = countsByBucketId[bucket.id] ?: 0
                root.add(DefaultMutableTreeNode("${bucket.displayName} ($c)"))
            }

        return root
    }

    private fun addToPackageTree(root: DefaultMutableTreeNode, fqcn: String) {
        val parts = fqcn.split('.')
        if (parts.isEmpty()) return

        var current = root
        // packages
        for (i in 0 until parts.size - 1) {
            val pkg = parts[i]
            val next = findChild(current, pkg) ?: DefaultMutableTreeNode(pkg).also { current.add(it) }
            current = next
        }
        // class leaf
        current.add(DefaultMutableTreeNode(parts.last()))
    }

    private fun findChild(parent: DefaultMutableTreeNode, name: String): DefaultMutableTreeNode? {
        val e = parent.children()
        while (e.hasMoreElements()) {
            val n = e.nextElement() as? DefaultMutableTreeNode ?: continue
            if (n.userObject == name) return n
        }
        return null
    }
}
