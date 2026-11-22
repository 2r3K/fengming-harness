package io.github.r2r3k.fengmingharness.core.provider

import io.github.r2r3k.fengmingharness.core.model.ChatMsg
import io.github.r2r3k.fengmingharness.core.model.ChatOptions
import io.github.r2r3k.fengmingharness.core.model.StreamEvent
import io.github.r2r3k.fengmingharness.core.model.ToolSpec
import kotlinx.coroutines.flow.Flow

/**
 * 模型提供方抽象。
 * 以后接 Anthropic、接自建 vLLM、接手机本地 llama.cpp，都只实现这个接口，UI 一行不用改。
 */
interface ChatProvider {
    /**
     * 发起一次流式对话；异常统一转成 StreamEvent.Failed，不往上抛。
     * tools 为空 = 这次请求不带函数工具（联网工具由 options.webSearch 单独控制）。
     */
    fun stream(
        messages: List<ChatMsg>,
        options: ChatOptions,
        tools: List<ToolSpec> = emptyList(),
    ): Flow<StreamEvent>

    /** 用户点"停止"：取消正在进行的网络请求 */
    fun cancel()
}
