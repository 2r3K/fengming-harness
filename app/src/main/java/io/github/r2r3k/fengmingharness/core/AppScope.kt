package io.github.r2r3k.fengmingharness.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * 应用级的作用域：流式请求挂在这里，而不是挂在界面上。
 * 这样界面被销毁（切换应用、旋转、甚至从最近任务划掉后重开）时，
 * 只要进程还活着（前台服务保着），请求和写库都会继续。
 */
object AppScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    var job: Job? = null

    @Volatile
    var runningConversationId: String? = null

    @Volatile
    var assistantRowId: String? = null

    fun isRunning(): Boolean = job?.isActive == true
}
