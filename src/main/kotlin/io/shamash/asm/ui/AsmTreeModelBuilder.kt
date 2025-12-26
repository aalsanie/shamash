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

    fun buildExternalBucketsNode(index: AsmIndex): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode("External Buckets")

        val buckets: List<ExternalBucketResolver.Bucket> = index.externalBuckets
        if (buckets.isEmpty()) {
            root.add(DefaultMutableTreeNode("No external buckets captured yet"))
            return root
        }

        // Count how many edges point to each bucket id (bucket ids are stored as references).
        val countsByBucketId: Map<String, Int> =
            buckets.associate { bucket ->
                val count = index.references.values.count { refs -> refs.contains(bucket.id) }
                bucket.id to count
            }

        buckets
            .sortedByDescending { countsByBucketId[it.id] ?: 0 }
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
