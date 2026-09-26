package com.xinjiyuan.oridesk

import android.os.Handler
import android.os.Looper

/**
 * 极简后台执行工具。
 *
 * 刻意不引 kotlinx-coroutines：本工程要发的都是"一次请求 + 一个回调"，
 * 用线程 + 主线程 Handler 足够，且能少一个依赖（见 app/build.gradle.kts）。
 */
object Async {

    private val main = Handler(Looper.getMainLooper())

    /** 在后台线程执行 [work]，完成后把结果或异常投回主线程交给 [done]。 */
    fun <T> run(work: () -> T, done: (value: T?, error: Throwable?) -> Unit = { _, _ -> }) {
        Thread {
            var value: T? = null
            var error: Throwable? = null
            try {
                value = work()
            } catch (t: Throwable) {
                error = t
            }
            val v = value
            val e = error
            main.post { done(v, e) }
        }.start()
    }

    /** 把一段代码投回主线程执行。 */
    fun onMain(block: () -> Unit) {
        main.post(block)
    }
}
