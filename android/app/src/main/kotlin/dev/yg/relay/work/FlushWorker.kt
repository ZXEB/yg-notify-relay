package dev.yg.relay.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.yg.relay.data.DeviceSnapshot
import dev.yg.relay.data.LocalLogStore
import dev.yg.relay.data.OutboxStore
import dev.yg.relay.data.SettingsStore
import dev.yg.relay.model.Batch
import dev.yg.relay.net.UploadResult
import dev.yg.relay.net.Uploader
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 把本地队列刷到服务器。
 *
 * 省电的关键全在这里：
 * - 通知到达时只往文件里追加一行，立刻返回，不在广播回调里做网络；
 * - [kick] 用 `REPLACE` + 初始延迟做去抖：连续 10 条通知只触发一次上传；
 * - 交给 WorkManager 调度，带网络约束与指数退避，Doze 下由系统攒批唤醒；
 * - 没有常驻连接、没有前台服务、没有定时轮询。
 *
 * 失败语义是刻意分开的：
 * - 401/403 → 令牌错了，`failure()` 停止重试，等用户去改；
 * - 网络错误 / 5xx → `retry()`，WorkManager 自己退避（30s 起，指数）；
 * - 队列里还有剩余 → 本轮继续发，不必再等一次调度。
 */
class FlushWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val settings = SettingsStore(context)
        if (settings.paused || !settings.isConfigured) return Result.success()

        val outbox = OutboxStore(context)
        val logs = LocalLogStore(context)

        repeat(MAX_ROUNDS) {
            val events = outbox.take(BATCH_SIZE)
            if (events.isEmpty()) return Result.success()

            val batch = Batch(
                batchId = UUID.randomUUID().toString(),
                sentAt = System.currentTimeMillis(),
                device = DeviceSnapshot.of(context, settings),
                events = events,
            )
            val result = Uploader().upload(settings.serverUrl, settings.token, batch)
            val ids = events.map { it.id }.toSet()

            when (result) {
                UploadResult.Ok -> {
                    outbox.ack(ids)
                    logs.markSent(ids)
                    settings.lastSuccessAt = System.currentTimeMillis()
                    settings.lastError = ""
                }

                UploadResult.Unauthorized -> {
                    val message = "令牌无效（401），请到设置里重新填写服务器令牌"
                    settings.lastError = message
                    logs.markFailed(ids, message)
                    return Result.failure()
                }

                is UploadResult.Fatal -> {
                    settings.lastError = result.message
                    logs.markFailed(ids, result.message)
                    return Result.failure()
                }

                UploadResult.Retry -> {
                    settings.lastError = "服务器暂时不可达，稍后自动重试"
                    return Result.retry()
                }
            }
        }
        // 一轮跑满还有剩：说明积压很多，交给下一次调度（避免 worker 长时间占 CPU）。
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "yg-relay-flush"
        private const val BATCH_SIZE = 100
        private const val MAX_ROUNDS = 5

        /**
         * 触发一次刷盘（幂等）。
         *
         * @param delayMs 去抖延迟，通常传用户设置里的批量延迟（默认 2.5s）。
         */
        fun kick(context: Context, delayMs: Long = 0L) {
            val settings = SettingsStore(context)
            if (settings.paused || !settings.isConfigured) return

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .build()

            val request = OneTimeWorkRequestBuilder<FlushWorker>()
                .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
