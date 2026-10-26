package dev.yg.relay.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.yg.relay.data.DeviceSnapshot
import dev.yg.relay.data.SettingsStore
import dev.yg.relay.model.Batch
import dev.yg.relay.net.UploadResult
import dev.yg.relay.net.Uploader
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 每日心跳：发一个空事件的批次，只为刷新设备最后在线时间与电量。
 *
 * 为什么需要它：手机可能一整天没有通知（或者用户关了转发），网页上的设备列表
 * 就会一直是几天前的状态，看不出「这台机还活着吗」。
 * 24 小时的周期对电量影响可以忽略，且同样受网络约束与 Doze 调度。
 */
class HeartbeatWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val settings = SettingsStore(context)
        if (!settings.isConfigured || settings.paused) return Result.success()

        val batch = Batch(
            batchId = UUID.randomUUID().toString(),
            sentAt = System.currentTimeMillis(),
            device = DeviceSnapshot.of(context, settings),
            events = emptyList(),
        )
        return when (Uploader().upload(settings.serverUrl, settings.token, batch)) {
            UploadResult.Ok -> {
                settings.lastSuccessAt = System.currentTimeMillis()
                settings.lastError = ""
                Result.success()
            }
            UploadResult.Unauthorized -> Result.failure()
            is UploadResult.Fatal -> Result.failure()
            UploadResult.Retry -> Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "yg-relay-heartbeat"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
