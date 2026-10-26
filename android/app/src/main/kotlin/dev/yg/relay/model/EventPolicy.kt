package dev.yg.relay.model

/** 内容模式：完整正文，还是只发标题（隐私优先）。 */
enum class ContentMode {
    FULL,
    TITLE_ONLY;

    companion object {
        fun parse(raw: String?): ContentMode =
            if (raw == TITLE_ONLY.name) TITLE_ONLY else FULL
    }
}

/**
 * 过滤与裁剪规则（纯函数，全部有单测）。
 *
 * 这里刻意不碰任何 Android 类型：通知监听的代码路径跑在高频回调里，
 * 规则能不能被独立测一遍，决定了「改了过滤条件之后会不会漏消息」这件事有没有把门。
 */
object EventPolicy {

    /** 系统自噪声：转发它们只会把时间线刷满。 */
    val ALWAYS_IGNORED: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.settings",
    )

    private const val TITLE_LIMIT = 2000
    private const val TEXT_LIMIT = 8000

    fun isNoise(packageName: String): Boolean = packageName in ALWAYS_IGNORED

    /**
     * 应用是否允许转发。
     *
     * 白名单非空时按白名单（只发这几家）；否则按黑名单排除。
     * 两个都空 = 全放行（除系统噪声）。
     */
    fun isAppAllowed(packageName: String, whitelist: Set<String>, blacklist: Set<String>): Boolean {
        if (isNoise(packageName)) return false
        if (whitelist.isNotEmpty()) return packageName in whitelist
        return packageName !in blacklist
    }

    /** 有没有值得转发的内容：全空白就没什么可发的。 */
    fun hasContent(title: String?, text: String?, bigText: String?): Boolean =
        !title.isNullOrBlank() || !text.isNullOrBlank() || !bigText.isNullOrBlank()

    fun clipTitle(value: String?): String? = clip(value, TITLE_LIMIT)

    fun clipText(value: String?): String? = clip(value, TEXT_LIMIT)

    /**
     * 选正文：优先 text，其次 bigText 的前几行（很多聊天应用只把长文放 bigText），
     * 最后退回 subText。TITLE_ONLY 模式一律返回 null。
     */
    fun pickBody(mode: ContentMode, text: String?, bigText: String?, subText: String?): String? {
        if (mode == ContentMode.TITLE_ONLY) return null
        val candidates = listOf(text, bigText, subText)
        return candidates.firstOrNull { !it.isNullOrBlank() }?.let { clipText(it) }
    }

    private fun clip(value: String?, limit: Int): String? {
        if (value == null) return null
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.length <= limit) trimmed else trimmed.substring(0, limit)
    }
}
