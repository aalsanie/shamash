package io.shamash.asm.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Central place for Shamash icons used by actions and UI. */
object ShamashIcons {
    @JvmField
    val PLUGIN: Icon = IconLoader.getIcon("/icons/pluginIcon.svg", ShamashIcons::class.java)
}
