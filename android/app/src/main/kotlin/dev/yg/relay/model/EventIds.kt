package dev.yg.relay.model

import java.security.MessageDigest

/**
 * 事件 id：同一条通知重复回调必须算同一个 id。
 *
 * 为什么不用通知自带的 `sbn.id`：那个值在应用重启后会被复用，不同会话的通知会撞。
 * 这里把「设备 + 包名 + key + 发布时间 + 标题 + 正文」一起哈希：
 * 通知更新（正文变了）算新事件，重复回调（完全一样）自动合并，服务端主键再去一次重。
 */
object EventIds {

    fun forNotification(
        deviceId: String,
        packageName: String,
        key: String?,
        postedAt: Long,
        title: String?,
        text: String?,
    ): String = digest(listOf(deviceId, packageName, key.orEmpty(), postedAt.toString(), title.orEmpty(), text.orEmpty()))

    fun forSms(deviceId: String, sender: String?, body: String?, timeMillis: Long): String =
        digest(listOf(deviceId, "sms", sender.orEmpty(), body.orEmpty(), timeMillis.toString()))

    fun digest(parts: List<String>): String {
        val joined = parts.joinToString("|")
        val bytes = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(24)
    }
}
