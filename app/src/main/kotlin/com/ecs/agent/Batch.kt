package com.ecs.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * 批量调模型时的并发闸门。
 *
 * 分析是「一题一次调用」，每次都在等网络——十道题串着跑就是十次等待相加，
 * 手机上看到的就是进度条一格一格地爬。这些调用相互独立（F7.2 的前提就是
 * 不共用上下文），所以可以同时发出去；限流是为了别把中转站打成 429。
 *
 * 结果按入参顺序原样返回，失败包在 Result 里——一条炸了不该带走整批。
 */
object Batch {

    /** 4 条是手机侧的折中：等待基本被填满，又不至于撞上中转站的并发限制。 */
    const val DEFAULT_CONCURRENCY = 4

    suspend fun <T, R> map(
        items: List<T>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        onProgress: (suspend (Int, Int) -> Unit)? = null,
        block: suspend (T) -> R,
    ): List<Result<R>> {
        if (items.isEmpty()) return emptyList()
        return coroutineScope {
            val gate = Semaphore(concurrency.coerceAtLeast(1))
            val lock = Mutex()
            var finished = 0
            items.map { item ->
                async {
                    val result = gate.withPermit { runCatching { block(item) } }
                    // runCatching 会连取消一起吞掉，那会让整批在退出时假装成功
                    (result.exceptionOrNull() as? CancellationException)?.let { throw it }
                    lock.withLock {
                        finished++
                        onProgress?.invoke(finished, items.size)
                    }
                    result
                }
            }.awaitAll()
        }
    }
}
