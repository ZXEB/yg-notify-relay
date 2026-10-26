package dev.yg.relay.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 上传契约的数据模型。
 *
 * 字段名就是服务端 `server/serve.py` 里 `normalize_events` 读的名字，改名等于改协议；
 * `@SerialName` 只在 Kotlin 侧名字与线上字段不同处使用（`package`、`posted_at` 这类）。
 */
@Serializable
data class RelayEvent(
    val id: String,
    val type: String = TYPE_NOTIFICATION,
    @SerialName("posted_at") val postedAt: Long,
    val app: AppRef,
    val title: String? = null,
    val text: String? = null,
    @SerialName("big_text") val bigText: String? = null,
    @SerialName("sub_text") val subText: String? = null,
    val category: String? = null,
    val channel: String? = null,
    val priority: Int? = null,
    val ongoing: Boolean = false,
    @SerialName("group_summary") val groupSummary: Boolean = false,
    val seq: Long = 0L,
    @SerialName("client_ts") val clientTs: Long = 0L,
) {
    companion object {
        const val TYPE_NOTIFICATION = "notification"
        const val TYPE_SMS = "sms"
    }
}

@Serializable
data class AppRef(
    @SerialName("package") val packageName: String,
    val label: String,
)

@Serializable
data class DeviceInfo(
    val id: String,
    val alias: String,
    val manufacturer: String,
    val model: String,
    val android: String,
    @SerialName("app_version") val appVersion: String,
    val battery: Int? = null,
    val charging: Boolean? = null,
    val network: String? = null,
)

/** 一次上传的载荷。空 `events` 也是合法的：那是心跳，只用来刷新设备的最后在线时间。 */
@Serializable
data class Batch(
    val schema: String = SCHEMA,
    @SerialName("batch_id") val batchId: String,
    @SerialName("sent_at") val sentAt: Long,
    val device: DeviceInfo,
    val events: List<RelayEvent>,
) {
    companion object {
        const val SCHEMA = "yg.relay.batch.v1"
    }
}
