package io.shamash.psi.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "ShamashSettings",
    storages = [Storage("shamash.xml")]
)
class ShamashSettings : PersistentStateComponent<ShamashSettings.State> {

    data class State(
        var rootPackage: String = "io.shamash"
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): ShamashSettings =
            com.intellij.openapi.application.ApplicationManager
                .getApplication()
                .getService(ShamashSettings::class.java)
    }
}
