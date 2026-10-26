package dev.yg.relay

import android.app.Application
import dev.yg.relay.data.OutboxStore
import dev.yg.relay.work.FlushWorker
import dev.yg.relay.work.HeartbeatWorker

class RelayApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 心跳是周期任务，登记一次即可（KEEP 策略，重复启动不会重置周期）。
        runCatching { HeartbeatWorker.schedule(this) }
        // 冷启动时如果队列里还有没发出去的东西（上次被杀/断网），立刻补一发。
        runCatching {
            if (OutboxStore(this).pendingCount() > 0) FlushWorker.kick(this, 0L)
        }
    }
}
