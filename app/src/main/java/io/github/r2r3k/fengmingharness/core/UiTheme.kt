package io.github.r2r3k.fengmingharness.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 深色模式：跟随系统 / 浅色 / 深色。改了立刻生效。 */
object UiTheme {

    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    var mode by mutableStateOf(SYSTEM)
        private set

    val label: String
        get() = when (mode) {
            LIGHT -> "浅色"
            DARK -> "深色"
            else -> "跟随系统"
        }

    fun load(store: SettingsStore) {
        mode = when (store.themeMode()) {
            LIGHT -> LIGHT
            DARK -> DARK
            else -> SYSTEM
        }
    }

    fun set(store: SettingsStore, value: String) {
        mode = when (value) {
            LIGHT -> LIGHT
            DARK -> DARK
            else -> SYSTEM
        }
        store.saveThemeMode(mode)
    }
}
