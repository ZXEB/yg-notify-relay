package dev.yg.relay.data

import android.content.Context
import android.content.SharedPreferences
import dev.yg.relay.model.ContentMode

/**
 * 本机设置。
 *
 * 用 SharedPreferences 而不是数据库：总共十来个标量 + 两个集合，读写同步、
 * 通知监听回调里可以直接读，不需要协程调度。凭据只在这里，且已排除出云备份。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 服务器地址，形如 `http://192.168.1.10:8787`（不带路径）。 */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_SERVER, normalizeUrl(value)).apply()

    /** 上传令牌（服务端 `config.json` 里的 `ingest_token`）。 */
    var token: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /** 设备别名，网页上显示这个名字。 */
    var alias: String
        get() = prefs.getString(KEY_ALIAS, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_ALIAS, value.trim()).apply()

    var contentMode: ContentMode
        get() = ContentMode.parse(prefs.getString(KEY_MODE, ContentMode.FULL.name))
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /** 批量延迟：通知到达后等多久再合并上传，起到去抖作用。 */
    var batchDelayMs: Long
        get() = prefs.getLong(KEY_BATCH_DELAY, DEFAULT_BATCH_DELAY_MS).coerceIn(500L, 30_000L)
        set(value) = prefs.edit().putLong(KEY_BATCH_DELAY, value.coerceIn(500L, 30_000L)).apply()

    /** 只在 Wi-Fi / 不计费网络上传（移动网络下会等）。 */
    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    /** 暂停转发：监听服务还在，但不再入队。 */
    var paused: Boolean
        get() = prefs.getBoolean(KEY_PAUSED, false)
        set(value) = prefs.edit().putBoolean(KEY_PAUSED, value).apply()

    /** 可选：短信直读（需要 RECEIVE_SMS 权限）。 */
    var smsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMS, false)
        set(value) = prefs.edit().putBoolean(KEY_SMS, value).apply()

    var whitelist: Set<String>
        get() = prefs.getStringSet(KEY_WHITELIST, emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_WHITELIST, value).apply()

    var blacklist: Set<String>
        get() = prefs.getStringSet(KEY_BLACKLIST, emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_BLACKLIST, value).apply()

    /** 最近一次上传成功的时间（毫秒），用于概览页。 */
    var lastSuccessAt: Long
        get() = prefs.getLong(KEY_LAST_SUCCESS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SUCCESS, value).apply()

    /** 最近一次失败原因（人类可读），成功后清空。 */
    var lastError: String
        get() = prefs.getString(KEY_LAST_ERROR, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_ERROR, value).apply()

    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && token.isNotBlank()

    fun clearCredentials() {
        prefs.edit().remove(KEY_SERVER).remove(KEY_TOKEN).remove(KEY_LAST_SUCCESS).remove(KEY_LAST_ERROR).apply()
    }

    companion object {
        const val PREFS_NAME = "yg_relay_settings"
        const val DEFAULT_BATCH_DELAY_MS = 2500L

        private const val KEY_SERVER = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_ALIAS = "alias"
        private const val KEY_MODE = "content_mode"
        private const val KEY_BATCH_DELAY = "batch_delay_ms"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_PAUSED = "paused"
        private const val KEY_SMS = "sms_enabled"
        private const val KEY_WHITELIST = "whitelist"
        private const val KEY_BLACKLIST = "blacklist"
        private const val KEY_LAST_SUCCESS = "last_success_at"
        private const val KEY_LAST_ERROR = "last_error"

        /**
         * 把用户随手填的地址收拾成可用形式：
         * `192.168.1.10:8787` → `http://192.168.1.10:8787`，并去掉结尾多余斜杠。
         */
        fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            val withScheme = when {
                trimmed.startsWith("http://", ignoreCase = true) -> trimmed
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                else -> "http://$trimmed"
            }
            return withScheme.trimEnd('/')
        }
    }
}
