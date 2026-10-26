package dev.yg.relay.service

import android.app.Notification
import android.content.ComponentName
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.yg.relay.data.DeviceIdentity
import dev.yg.relay.data.LocalLogStore
import dev.yg.relay.data.OutboxStore
import dev.yg.relay.data.SettingsStore
import dev.yg.relay.model.AppRef
import dev.yg.relay.model.ContentMode
import dev.yg.relay.model.EventIds
import dev.yg.relay.model.EventPolicy
import dev.yg.relay.model.RelayEvent
import dev.yg.relay.work.FlushWorker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 通知监听的落点 —— 这就是「像智能穿戴一样取手机信息」的那一层。
 *
 * 关键事实：
 * - 这是**系统推事件**给我们（用户授权通知使用权后由系统绑定），不是我们去轮询。
 *   手环/手表取通知用的也是同一个机制，因此这条路径额外功耗接近零。
 * - 服务被系统杀死后，系统在下次有通知时会重新绑定它，不需要我们保活、不需要前台服务。
 * - 回调里只做「读字段 + 追加一行文件 + 排一次调度」，绝不在这里发网络请求：
 *   回调在系统线程里，做慢操作会拖住整个通知栏。
 */
class RelayNotificationService : NotificationListenerService() {

    private lateinit var settings: SettingsStore
    private lateinit var outbox: OutboxStore
    private lateinit var logs: LocalLogStore
    private val labels = ConcurrentHashMap<String, String>()
    private val sequence = AtomicLong(0)

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        outbox = OutboxStore(this)
        logs = LocalLogStore(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        runCatching { capture(posted) }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        labels.clear()
    }

    private fun capture(sbn: StatusBarNotification) {
        if (settings.paused) return

        val packageName = sbn.packageName ?: return
        if (!EventPolicy.isAppAllowed(packageName, settings.whitelist, settings.blacklist)) return

        val notification = sbn.notification ?: return
        val extras: Bundle = notification.extras ?: Bundle.EMPTY

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val rawBigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val rawSubText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        // 聊天类应用常把最新一条放在 EXTRA_MESSAGES 里，EXTRA_TEXT 只有「N 条新消息」。
        val conversationLine = lastMessageLine(extras)

        val body = EventPolicy.pickBody(
            mode = settings.contentMode,
            text = rawText ?: conversationLine,
            bigText = rawBigText,
            subText = rawSubText,
        )
        val title = EventPolicy.clipTitle(rawTitle)
        if (!EventPolicy.hasContent(title, body, null)) return

        val label = labels.getOrPut(packageName) { resolveLabel(packageName) }
        val deviceId = DeviceIdentity.of(this)
        val event = RelayEvent(
            id = EventIds.forNotification(deviceId, packageName, sbn.key, sbn.postTime, title, body),
            type = RelayEvent.TYPE_NOTIFICATION,
            postedAt = sbn.postTime,
            app = AppRef(packageName = packageName, label = label),
            title = title,
            text = body,
            bigText = if (settings.contentMode == ContentMode.FULL) EventPolicy.clipText(rawBigText) else null,
            subText = if (settings.contentMode == ContentMode.FULL) EventPolicy.clipText(rawSubText) else null,
            category = notification.category,
            channel = notification.channelId,
            priority = notification.priority,
            ongoing = sbn.isOngoing,
            // 中缀 `and` 的优先级低于 `!=`，不加括号会被解析成 `flags and (FLAG != 0)`。
            groupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            seq = sequence.incrementAndGet(),
            clientTs = System.currentTimeMillis(),
        )

        outbox.append(listOf(event))
        logs.record(event)
        // 去抖交给 WorkManager：连续多条通知只会在最后一条之后 delayMs 触发一次上传。
        FlushWorker.kick(this, settings.batchDelayMs)
    }

    /** 取「消息列表」里的最后一条文本，取不到就返回 null。 */
    private fun lastMessageLine(extras: Bundle): String? = runCatching {
        @Suppress("DEPRECATION")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        messages.asSequence()
            .filterIsInstance<Bundle>()
            .mapNotNull { it.getCharSequence("text")?.toString() }
            .lastOrNull()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun resolveLabel(packageName: String): String = runCatching {
        val manager = packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    companion object {
        /** 给设置页判断「通知使用权是否已授予」。 */
        fun isEnabled(context: android.content.Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            val component = ComponentName(context, RelayNotificationService::class.java)
            return flat.split(':').any {
                ComponentName.unflattenFromString(it) == component
            }
        }
    }
}
