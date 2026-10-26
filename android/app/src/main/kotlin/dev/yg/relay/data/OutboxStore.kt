package dev.yg.relay.data

import android.content.Context
import dev.yg.relay.model.RelayEvent
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 待上传队列：一个 JSONL 文件，一行一条事件。
 *
 * 为什么是文件而不是内存队列/数据库：
 * - 进程随时可能被系统杀掉，内存队列会丢消息；文件天然持久。
 * - 单条事件结构扁平，JSONL 追加写最省事，出问题也能直接用文本编辑器看。
 *
 * 上限 [MAX_EVENTS]：服务器长期不可达时不能无限长胖，超出部分丢最旧的
 * （新消息比旧消息有价值），并在 [append] 的返回值里告诉调用方丢了多少条。
 */
class OutboxStore(context: Context) {

    private val directory = File(context.applicationContext.filesDir, DIR_NAME)
    private val file = File(directory, FILE_NAME)
    private val lock = Any()

    /** 惰性计数：避免每次 append 都全量读文件。 */
    private var approxCount = -1

    /**
     * 追加事件。返回丢掉的旧事件条数（0 表示没有触发淘汰）。
     */
    fun append(events: List<RelayEvent>): Int {
        if (events.isEmpty()) return 0
        synchronized(lock) {
            directory.mkdirs()
            val text = events.joinToString(separator = "") { json.encodeToString(RelayEvent.serializer(), it) + "\n" }
            file.appendText(text)
            if (approxCount < 0) approxCount = readLines().size
            approxCount += events.size
            if (approxCount <= MAX_EVENTS) return 0

            val lines = readLines()
            val kept = lines.takeLast(MAX_EVENTS)
            val dropped = lines.size - kept.size
            writeLines(kept)
            approxCount = kept.size
            return dropped
        }
    }

    /** 读取最多 [limit] 条待发事件（不删除，发送成功后用 [ack] 确认）。 */
    fun take(limit: Int): List<RelayEvent> {
        if (limit <= 0) return emptyList()
        synchronized(lock) {
            return readLines().asSequence()
                .mapNotNull { parse(it) }
                .take(limit)
                .toList()
        }
    }

    /** 确认已发送：按 id 删除对应行。 */
    fun ack(sentIds: Set<String>) {
        if (sentIds.isEmpty()) return
        synchronized(lock) {
            val lines = readLines()
            if (lines.isEmpty()) return
            val kept = lines.filter { line ->
                val event = parse(line)
                event == null || event.id !in sentIds
            }
            if (kept.size != lines.size) {
                writeLines(kept)
                approxCount = kept.size
            }
        }
    }

    fun pendingCount(): Int {
        synchronized(lock) {
            if (approxCount < 0) approxCount = readLines().size
            return approxCount
        }
    }

    fun clear() {
        synchronized(lock) {
            runCatching { file.delete() }
            approxCount = 0
        }
    }

    // -- 内部 -------------------------------------------------------------

    private fun readLines(): List<String> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun writeLines(lines: List<String>) {
        directory.mkdirs()
        val tmp = File(directory, "$FILE_NAME.tmp")
        tmp.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n")
        if (!tmp.renameTo(file)) {
            // rename 在个别文件系统上会失败，退回覆盖写。
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun parse(line: String): RelayEvent? = runCatching {
        json.decodeFromString(RelayEvent.serializer(), line)
    }.getOrNull()

    companion object {
        private const val DIR_NAME = "outbox"
        private const val FILE_NAME = "pending.jsonl"

        /** 2000 条 × 单条通常几百字节 ≈ 1MB，对手机存储是可以忽略的量级。 */
        const val MAX_EVENTS = 2000

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
