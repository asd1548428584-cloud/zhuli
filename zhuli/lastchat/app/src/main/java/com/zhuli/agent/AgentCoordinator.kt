package com.zhuli.agent

import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 批次 B：进程内的任务并发 / 取消协调器。
 *
 * 三条规则：
 * 1. **同一任务**同一时刻只能有一个执行路径（防止重复审批、重复 run 造成双份副作用）；
 * 2. **同一会话**的任务串行，**不同会话**可并行 —— 不用全局锁，避免一个卡住的任务拖垮整个 App；
 * 3. 取消既要写库（跨进程可信），也要在这里立标志并取消协程（让正在执行的工具尽快停下）。
 *
 * 注意：这里只管"当前进程"的并发，持久化状态仍以 [AgentStore] 为唯一事实源。
 * 进程被杀后这两张表自然清空，恢复时靠库里的 CANCELLING / PAUSED 判断。
 */
object AgentCoordinator {
    private val taskLocks = ConcurrentHashMap<String, Mutex>()
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val jobs = ConcurrentHashMap<String, Job>()

    private fun taskLock(taskId: String): Mutex = taskLocks.computeIfAbsent(taskId) { Mutex() }
    private fun sessionLock(sessionId: String): Mutex = sessionLocks.computeIfAbsent(sessionId) { Mutex() }

    /** 先会话、后任务，顺序固定，避免两把锁互相等待。 */
    suspend fun <T> withLocks(sessionId: String, taskId: String, block: suspend () -> T): T {
        val sLock = if (sessionId.isBlank()) null else sessionLock(sessionId)
        val tLock = taskLock(taskId)
        return if (sLock == null) {
            tLock.withLock { block() }
        } else {
            sLock.withLock { tLock.withLock { block() } }
        }
    }

    /** 是否已有别的执行路径持有该任务锁（用于快速返回"任务已在执行"而不是排队）。 */
    fun taskBusy(taskId: String): Boolean = taskLock(taskId).isLocked

    fun register(taskId: String, job: Job) {
        jobs[taskId] = job
        cancelFlags.computeIfAbsent(taskId) { AtomicBoolean(false) }
    }

    fun isCancelRequested(taskId: String): Boolean = cancelFlags[taskId]?.get() == true

    fun requestCancel(taskId: String) {
        cancelFlags.computeIfAbsent(taskId) { AtomicBoolean(false) }.set(true)
        jobs[taskId]?.cancel()
    }

    /** 任务进入终态后调用，防止标志泄漏到下一次同名任务。 */
    fun finish(taskId: String) {
        cancelFlags.remove(taskId)
        jobs.remove(taskId)
    }
}
