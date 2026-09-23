package com.llzx373.foldreader.core.ai

import kotlinx.coroutines.flow.Flow

/**
 * AI 服务商抽象。[chat] 返回 SSE 文本增量流（只含正文增量，不含推理过程）。
 *
 * 流在 IO 调度器上执行阻塞请求；收集端取消会中断底层 HTTP 调用。
 */
interface AiProvider {
    fun chat(messages: List<AiMessage>, model: String): Flow<String>
}
