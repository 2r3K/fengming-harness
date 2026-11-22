package io.github.r2r3k.fengmingharness.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 全 APP 字号档位：1 小 / 2 标准 / 3 大。
 *
 * 做法是在最外层把「系统字号缩放」再乘一个系数，所以整屏文字（气泡、代码、按钮、
 * 设置里的说明）一起变，不用一处处改。Android 自己在系统设置里调字号也照样生效：
 * 我们是在它基础上再乘。
 */
object UiText {

    var level by mutableIntStateOf(STANDARD)
        private set

    val factor: Float
        get() = when (level) {
            SMALL -> 0.88f
            LARGE -> 1.2f
            else -> 1f
        }

    val label: String
        get() = when (level) {
            SMALL -> "小"
            LARGE -> "大"
            else -> "标准"
        }

    fun load(store: SettingsStore) {
        level = store.fontLevel()
    }

    fun set(store: SettingsStore, value: Int) {
        level = value.coerceIn(SMALL, LARGE)
        store.saveFontLevel(level)
    }

    const val SMALL = 1
    const val STANDARD = 2
    const val LARGE = 3
}
