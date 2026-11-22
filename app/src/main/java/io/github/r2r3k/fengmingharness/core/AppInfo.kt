package io.github.r2r3k.fengmingharness.core

/**
 * 应用身份信息（都放这里，改名字只改这一处）。
 *
 * 注意：桌面显示名在 `res/values/strings.xml` 的 `app_name` 里，
 * 改的时候两处一起改（一个是清单/UI 用的资源，一个是代码里拼字符串用的）。
 */
object AppInfo {

    /** 应用名 */
    const val NAME = "奉明Harness"

    /** 开源仓库地址 */
    const val REPO = "https://github.com/2r3K/fengming-harness"

    /** 作者 */
    const val AUTHOR = "2r3K"

    /** 许可证 */
    const val LICENSE = "MIT"
}
