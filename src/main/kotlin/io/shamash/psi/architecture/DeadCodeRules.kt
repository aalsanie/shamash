/*
 * Copyright © 2025-2026 | Shamash is a refactoring tool that enforces clean architecture.
 *
 * Author: @aalsanie
 *
 * Plugin: https://plugins.jetbrains.com/plugin/29504-shamash
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.shamash.psi.architecture

import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.search.searches.ReferencesSearch
import io.shamash.psi.util.EntryPointUtil

object DeadCodeRules {
    // Framework / test / lifecycle annotations that often mean "used indirectly".
    // TODO: add lombok and other known frameworks if needed
    private val indirectUseMethodAnnotations =
        setOf(
            // JUnit 4 & 5
            "org.junit.Test",
            "org.junit.Before",
            "org.junit.After",
            "org.junit.BeforeClass",
            "org.junit.AfterClass",
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.api.BeforeEach",
            "org.junit.jupiter.api.AfterEach",
            "org.junit.jupiter.api.BeforeAll",
            "org.junit.jupiter.api.AfterAll",
            "org.junit.jupiter.api.Disabled",
            "org.junit.jupiter.params.ParameterizedTest",
            // Spring web endpoints indirect invocation
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping",
            // Common DI
            "javax.annotation.PostConstruct",
            "javax.annotation.PreDestroy",
            "jakarta.annotation.PostConstruct",
            "jakarta.annotation.PreDestroy",
        )

    private val indirectUseClassAnnotations =
        setOf(
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Component",
            "org.springframework.boot.autoconfigure.SpringBootApplication",
            "org.springframework.context.annotation.Configuration",
        )

    // fields are the easiest to delete incorrectly, be extra conservative.
    private val indirectUseFieldAnnotations =
        setOf(
            // Spring DI
            "org.springframework.beans.factory.annotation.Autowired",
            "org.springframework.beans.factory.annotation.Value",
            "org.springframework.beans.factory.annotation.Qualifier",
            // Jakarta DI
            "javax.inject.Inject",
            "javax.annotation.Resource",
            "jakarta.inject.Inject",
            "jakarta.annotation.Resource",
            // JPA
            "javax.persistence.Id",
            "javax.persistence.Column",
            "javax.persistence.OneToOne",
            "javax.persistence.OneToMany",
            "javax.persistence.ManyToOne",
            "javax.persistence.ManyToMany",
            "javax.persistence.Embedded",
            "javax.persistence.EmbeddedId",
            "javax.persistence.Transient",
            "jakarta.persistence.Id",
            "jakarta.persistence.Column",
            "jakarta.persistence.OneToOne",
            "jakarta.persistence.OneToMany",
            "jakarta.persistence.ManyToOne",
            "jakarta.persistence.ManyToMany",
            "jakarta.persistence.Embedded",
            "jakarta.persistence.EmbeddedId",
            "jakarta.persistence.Transient",
            // Jackson
            "com.fasterxml.jackson.annotation.JsonProperty",
            "com.fasterxml.jackson.annotation.JsonIgnore",
            "com.fasterxml.jackson.annotation.JsonCreator",
            "com.fasterxml.jackson.annotation.JsonAlias",
            "com.fasterxml.jackson.annotation.JsonUnwrapped",
            "com.fasterxml.jackson.annotation.JsonValue",
            // Lombok
            // TODO: fix annotations usage in lobmbok "known bug: currently we detect annotated fields as unused"
            "lombok.Getter",
            "lombok.Setter",
            "lombok.Data",
            "lombok.Value",
            "lombok.Builder",
        )

    fun isUnusedClass(psiClass: PsiClass): Boolean {
        if (!psiClass.isValid) return false
        if (psiClass is PsiAnonymousClass) return false
        if (psiClass is PsiTypeParameter) return false
        if (psiClass.containingClass != null && psiClass.hasModifierProperty(PsiModifier.PUBLIC)) {
            return false
        }

        val layer = LayerDetector.detect(psiClass)
        if (layer == Layer.CONTROLLER || layer == Layer.CLI) return false

        if (EntryPointUtil.isEntryPoint(psiClass)) return false
        if (hasAnyAnnotation(psiClass, indirectUseClassAnnotations)) return false
        if (hasMainMethod(psiClass)) return false

        val isPublic = psiClass.hasModifierProperty(PsiModifier.PUBLIC)

        // Default: do NOT report public top-level classes (could be API/reflection/library use),
        // except for util classes that live in project source.
        if (isPublic && layer != Layer.UTIL) return false
        if (isPublic && !isInProjectSource(psiClass)) return false

        return ReferencesSearch.search(psiClass).findFirst() == null
    }

    private fun isInProjectSource(psiClass: PsiClass): Boolean {
        val vFile = psiClass.containingFile?.virtualFile ?: return false
        val index = ProjectRootManager.getInstance(psiClass.project).fileIndex

        // "Source content" includes main + test sources for dead-code detection.
        // If you want to exclude tests, we can tighten this later.
        // This can be scaled to non-java codebases TODO: stick to java format scale later
        if (!index.isInSourceContent(vFile)) return false

        // avoid flagging library source/classes.
        if (index.isInLibrary(vFile)) return false

        return true
    }

    fun isUnusedMethod(method: PsiMethod): Boolean {
        if (!method.isValid) return false
        if (method.isConstructor) return false
        if (method.containingClass == null) return false
        if (method.name == "main" && method.hasModifierProperty(PsiModifier.STATIC)) return false

        val containing = method.containingClass ?: return false
        val layer = LayerDetector.detect(containing)
        if (layer == Layer.CONTROLLER || layer == Layer.CLI) return false

        if (!method.hasModifierProperty(PsiModifier.PRIVATE)) return false

        if (method.findSuperMethods().isNotEmpty()) return false

        if (hasAnyAnnotation(method, indirectUseMethodAnnotations)) return false

        if (EntryPointUtil.isEntryPoint(method)) return false

        return ReferencesSearch.search(method).findFirst() == null
    }

    /**
     * Very conservative:
     * - only private fields
     * - skips DI/persistence/serialization/lombok annotated fields
     *   TODO:FIX LOMBOK & double check annotations
     * - skips controllers/cli layers
     * - skips constants / serialVersionUID
     */
    fun isUnusedField(field: PsiField): Boolean {
        if (!field.isValid) return false
        if (field is PsiEnumConstant) return false

        val containing = field.containingClass ?: return false
        val layer = LayerDetector.detect(containing)
        if (layer == Layer.CONTROLLER || layer == Layer.CLI) return false

        if (!field.hasModifierProperty(PsiModifier.PRIVATE)) return false

        if (field.name == "serialVersionUID") return false
        if (field.hasModifierProperty(PsiModifier.STATIC) && field.hasModifierProperty(PsiModifier.FINAL)) {
            // constants can be used externally/reflection; be conservative
            return false
        }

        if (hasAnyAnnotation(field, indirectUseFieldAnnotations)) return false

        if (EntryPointUtil.isEntryPoint(field)) return false

        return ReferencesSearch.search(field).findFirst() == null
    }

    private fun hasMainMethod(psiClass: PsiClass): Boolean =
        psiClass.methods.any { it.name == "main" && it.hasModifierProperty(PsiModifier.STATIC) }

    private fun hasAnyAnnotation(
        owner: PsiModifierListOwner,
        fqns: Set<String>,
    ): Boolean {
        val modifierList = owner.modifierList ?: return false
        val annotations = modifierList.annotations
        return annotations.any { ann ->
            val q = ann.qualifiedName
            q != null && q in fqns
        }
    }
}
