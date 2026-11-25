package com.zhuli.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T-B11 确认桥：把纯逻辑闸门 [ActionGate] 和 UI 弹窗接起来。
 *
 * core 不依赖 android.*：UI 层注入 [requester]，core 只负责等待与超时。
 * **默认没有 requester 时一律拒绝**（安全档）——没界面问，就当没授权。
 *
 * 线程约定：[ask] 在后台线程调用（工具在 CloudClient 的 io 循环里执行）；
 * requester 自行切到主线程弹窗，结束后回调 [onChoice]。
 */
object ActionConfirm {

    /** 屏幕点击 + 工具写操作共用一轮，"本轮允许"在全 App 内一致。 */
    val gate = ActionGate()

    /** UI 注入：展示三档对话框，用户选完回调。 */
    @Volatile
    var requester: ((op: String, label: String, onChoice: (Grant) -> Unit) -> Unit)? = null

    /** 无人应答的兜底：超时按"取消"处理，不让后台线程永久挂住。 */
    const val DEFAULT_TIMEOUT_MS = 90_000L

    /**
     * 同步请求一次授权。返回 true 才可写。
     *
     * 流程与 [ActionGate.check] 一致：硬拦截 → 本轮已放行 → 否则问用户。
     */
    fun ask(
        op: String,
        label: String,
        targetText: String = "",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Boolean {
        when (val check = gate.check(op, targetText)) {
            is GateCheck.HardDeny -> {
                ZhuliLog.w("ActionGate", "hard deny ${check.reason}: op=$op")
                return false
            }
            GateCheck.AllowRound -> {
                ZhuliLog.i("ActionGate", "round grant: op=$op")
                return true
            }
            GateCheck.Ask -> Unit
        }
        val r = requester ?: return false      // 没 UI 就拒绝
        val latch = CountDownLatch(1)
        var result = Grant.CANCEL
        try {
            r(op, label) { choice ->
                result = choice
                latch.countDown()
            }
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return false
        } catch (_: Throwable) {
            return false
        }
        val allowed = gate.record(op, result)
        ZhuliLog.i("ActionGate", "op=$op choice=$result allowed=$allowed")
        return allowed
    }
}
