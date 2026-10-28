package com.haoai.agent.agent.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call

/**
 * 阻塞 socket 读的「取消即断链」看门狗（2026-10-28 真机「停止键没反应」的修复件）。
 *
 * ## 为什么需要它
 *
 * SSE 流式响应的读循环用的是 `source.readUtf8Line()`——**阻塞 IO**。协程取消
 * 不会自动打断它：取消只是给 job 打了个标记，协程要等当前阻塞调用返回才会观察到。
 * 唯一能立刻打断的手段是 `call.cancel()` 断链，让阻塞读抛 IOException("Canceled")。
 *
 * ## 旧实现为什么失效（真机实锤）
 *
 * 旧代码把 `call.cancel()` 挂在 `Job.invokeOnCompletion { }` 上——那是「协程**彻底结束**」
 * 语义，而结束的前提恰恰是**这个阻塞读读完了**。于是形成死锁：
 *
 * ```
 * 取消请求发出 → 读还阻塞 → 协程不结束 → 钩子不触发 → call.cancel() 不执行
 *             → 一直卡到 readTimeout（120s）才抛异常收尾
 * ```
 *
 * 真机实测（2026-10-28）：用户按下输入框右侧停止键后，引擎又跑了 **120.5 秒**
 * 才落盘收尾（= OkHttp readTimeout 120s），期间 UI 状态胶囊一直转圈、停止键
 * 看着"没反应"；落盘时刻与 `readTimeout` 精确吻合。
 *
 * ## 本看门狗怎么解决
 *
 * 用一个**子协程**把「父协程被取消」翻译成「立刻 call.cancel()」：子协程随父
 * 协程一起被取消（取消是立即投递的，不等阻塞调用返回），它在
 * `suspendCancellableCoroutine` 的 `invokeOnCancellation` 回调里执行
 * `call.cancel()`，阻塞读随即被打断。
 *
 * 用的是 `CancellableContinuation.invokeOnCancellation`——**公开 API**，
 * 与本包 `awaitResponse` 里断链响应头等待用的是同一手法。
 *
 * @param scope  当前协程的 scope（传 `currentCoroutineContext()`）。看门狗**必须**
 *               是父协程的子协程，否则父协程被取消时它收不到通知。
 */
internal class StreamCancelWatchdog(
    private val call: Call,
    scope: CoroutineScope
) {
    /** 正常读完流后置位：此时不该再 call.cancel()（免得关掉已读完的连接池连接）。 */
    @Volatile
    private var finishedNormally = false

    private val watcher: Job = scope.launch {
        try {
            // 永不返回：只在被取消时走出
            suspendCancellableCoroutine<Unit> { cont ->
                cont.invokeOnCancellation { call.cancel() }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            if (!finishedNormally) call.cancel()
            throw ce
        }
    }

    /**
     * 正常读完流后调用：停看门狗，且不再断链。
     * 异常路径（取消/出错）不需要调——父协程结束会自动带上它。
     */
    fun dispose() {
        finishedNormally = true
        watcher.cancel()
    }
}
