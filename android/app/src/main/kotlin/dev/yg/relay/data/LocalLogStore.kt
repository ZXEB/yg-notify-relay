package dev.yg.relay.data

import android.content.Context
import android.content.SharedPreferences
import dev.yg.relay.model.RelayEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 本机看到的每条消息的发送状态。 */
enum class LogStatus { PENDING, SENT, FAILED }

@Serializable
data class LogEntry(
    val event: RelayEvent,
    val status: String,
    val updatedAt: Long,
    val error: String? = null,
)

/**
 * 本机最近消息记录（给 App 内「消息」页看）。
 *
 * 只保留最近 [MAX_ENTRIES] 条：这是给人看的回执，不是归档——归档在服务器上。
 * 存 SharedPreferences 而不是文件，是因为要按条改状态（发送成功/失败），
 * 整块 JSON 重写比按行改文件简单得多。
 */
class LocalLogStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun record(event: RelayEvent) {
        val current = recent().toMutableList()
        current.removeAll { it.event.id == event.id }
        current.add(0, LogEntry(event = event, status = LogStatus.PENDING.name, updatedAt = System.currentTimeMillis()))
        persist(current.take(MAX_ENTRIES))
    }

    fun markSent(ids: Set<String>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        persist(recent().map {
            if (it.event.id in ids) it.copy(status = LogStatus.SENT.name, updatedAt = now, error = null) else it
        })
    }

    fun markFailed(ids: Set<String>, error: String?) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        persist(recent().map {
            if (it.event.id in ids) it.copy(status = LogStatus.FAILED.name, updatedAt = now, error = error) else it
        })
    }

    fun recent(): List<LogEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(LogEntry.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private fun persist(entries: List<LogEntry>) {
        val encoded = json.encodeToString(ListSerializer(LogEntry.serializer()), entries)
        prefs.edit().putString(KEY_ENTRIES, encoded).apply()
    }

    companion object {
        const val PREFS_NAME = "yg_relay_log"
        private const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 80

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
