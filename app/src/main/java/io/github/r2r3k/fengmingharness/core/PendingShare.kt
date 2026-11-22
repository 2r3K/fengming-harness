package io.github.r2r3k.fengmingharness.core

import android.net.Uri

/**
 * 别的 App「分享 / 用其他应用打开」过来的文件，先放这里，
 * 界面恢复时取走并上传。
 */
object PendingShare {
    @Volatile
    var uris: List<Uri> = emptyList()
}
