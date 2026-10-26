package dev.yg.relay.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
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

/**
 * 可选的短信直读。
 *
 * 通知监听其实已经能收到短信通知，但那条路径拿到的是「短信应用渲染出来的通知」，
 * 多卡、双应用、免打扰下可能被折叠。直接听 SMS_RECEIVED 拿到的是原始内容与发件号码，
 * 更完整 —— 代价是要多一个 RECEIVE_SMS 权限，所以默认关闭，只在用户主动打开时才处理。
 */
class SmsRelayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val settings = SettingsStore(context)
        if (!settings.smsEnabled || settings.paused) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages.firstOrNull()?.displayOriginatingAddress
            ?: messages.firstOrNull()?.originatingAddress
        val body = messages.joinToString(separator = "") {
            it.displayMessageBody ?: it.messageBody ?: ""
        }
        if (body.isBlank() && sender.isNullOrBlank()) return

        val postedAt = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        val deviceId = DeviceIdentity.of(context)
        val event = RelayEvent(
            id = EventIds.forSms(deviceId, sender, body, postedAt),
            type = RelayEvent.TYPE_SMS,
            postedAt = postedAt,
            app = AppRef(packageName = "android.provider.Telephony", label = "短信"),
            title = EventPolicy.clipTitle(sender),
            text = if (settings.contentMode == ContentMode.TITLE_ONLY) null else EventPolicy.clipText(body),
            category = "msg",
            channel = "sms",
            priority = 0,
            seq = postedAt,
            clientTs = System.currentTimeMillis(),
        )

        OutboxStore(context).append(listOf(event))
        LocalLogStore(context).record(event)
        FlushWorker.kick(context, settings.batchDelayMs)
    }
}
