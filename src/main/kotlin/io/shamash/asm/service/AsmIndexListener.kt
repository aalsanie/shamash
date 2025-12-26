package io.shamash.asm.service

import com.intellij.util.messages.Topic
import io.shamash.asm.model.AsmIndex

interface AsmIndexListener {
    fun indexUpdated(index: AsmIndex)

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<AsmIndexListener> =
            Topic.create("Shamash.AsmIndexUpdated", AsmIndexListener::class.java)
    }
}
