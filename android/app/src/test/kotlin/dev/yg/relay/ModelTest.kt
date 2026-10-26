package dev.yg.relay

import dev.yg.relay.data.SettingsStore
import dev.yg.relay.model.ContentMode
import dev.yg.relay.model.EventIds
import dev.yg.relay.model.EventPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 单测：不碰 Android 框架，CI 上 `testDebugUnitTest` 直接跑。
 *
 * 覆盖的三处都是「改错了不会崩、只会悄悄丢消息」的地方：
 * 事件 id 的去重口径、过滤规则、以及服务器地址的归一化。
 */
class EventIdsTest {

    @Test
    fun `相同输入得到相同 id`() {
        val first = EventIds.forNotification("dev1", "com.tencent.mm", "k1", 1000L, "张三", "在吗")
        val second = EventIds.forNotification("dev1", "com.tencent.mm", "k1", 1000L, "张三", "在吗")
        assertEquals(first, second)
    }

    @Test
    fun `正文变化算新事件`() {
        val first = EventIds.forNotification("dev1", "com.tencent.mm", "k1", 1000L, "张三", "在吗")
        val second = EventIds.forNotification("dev1", "com.tencent.mm", "k1", 1000L, "张三", "在的")
        assertNotEquals(first, second)
    }

    @Test
    fun `设备不同 id 不同`() {
        val first = EventIds.forNotification("dev1", "com.a", "k", 1L, "t", "x")
        val second = EventIds.forNotification("dev2", "com.a", "k", 1L, "t", "x")
        assertNotEquals(first, second)
    }

    @Test
    fun `id 固定 24 位十六进制`() {
        val id = EventIds.forNotification("dev1", "com.a", null, 1L, null, null)
        assertEquals(24, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
    }

    @Test
    fun `短信 id 与通知 id 不会撞`() {
        val notification = EventIds.forNotification("dev1", "sms", "k", 5L, "10086", "hi")
        val sms = EventIds.forSms("dev1", "10086", "hi", 5L)
        assertNotEquals(notification, sms)
    }
}

class EventPolicyTest {

    @Test
    fun `系统噪声永远跳过`() {
        assertFalse(EventPolicy.isAppAllowed("android", emptySet(), emptySet()))
        assertFalse(EventPolicy.isAppAllowed("com.android.systemui", emptySet(), emptySet()))
    }

    @Test
    fun `黑名单排除指定应用`() {
        assertFalse(EventPolicy.isAppAllowed("com.a", emptySet(), setOf("com.a")))
        assertTrue(EventPolicy.isAppAllowed("com.b", emptySet(), setOf("com.a")))
    }

    @Test
    fun `白名单非空时只放行白名单`() {
        assertTrue(EventPolicy.isAppAllowed("com.a", setOf("com.a"), emptySet()))
        assertFalse(EventPolicy.isAppAllowed("com.b", setOf("com.a"), emptySet()))
    }

    @Test
    fun `白名单也拦不住系统噪声`() {
        assertFalse(EventPolicy.isAppAllowed("android", setOf("android"), emptySet()))
    }

    @Test
    fun `全空白内容不算消息`() {
        assertFalse(EventPolicy.hasContent(null, null, null))
        assertFalse(EventPolicy.hasContent("   ", "", "\n"))
        assertTrue(EventPolicy.hasContent("标题", null, null))
    }

    @Test
    fun `只发标题模式丢弃正文`() {
        assertNull(EventPolicy.pickBody(ContentMode.TITLE_ONLY, "正文", "长文", "副标题"))
    }

    @Test
    fun `正文优先 text 再退 bigText`() {
        assertEquals("正文", EventPolicy.pickBody(ContentMode.FULL, "正文", "长文", "副"))
        assertEquals("长文", EventPolicy.pickBody(ContentMode.FULL, null, "长文", "副"))
        assertEquals("副", EventPolicy.pickBody(ContentMode.FULL, "", null, "副"))
        assertNull(EventPolicy.pickBody(ContentMode.FULL, null, null, null))
    }

    @Test
    fun `超长正文被截断`() {
        val long = "x".repeat(10_000)
        val clipped = EventPolicy.pickBody(ContentMode.FULL, long, null, null)
        assertEquals(8000, clipped?.length)
    }

    @Test
    fun `正文首尾空白被清掉`() {
        assertEquals("在吗", EventPolicy.pickBody(ContentMode.FULL, "  在吗\n", null, null))
    }
}

class SettingsUrlTest {

    @Test
    fun `省略协议时补 http`() {
        assertEquals("http://192.168.1.10:8787", SettingsStore.normalizeUrl("192.168.1.10:8787"))
    }

    @Test
    fun `保留 https`() {
        assertEquals("https://relay.example.com", SettingsStore.normalizeUrl("https://relay.example.com/"))
    }

    @Test
    fun `去掉结尾斜杠`() {
        assertEquals("http://a.b:1", SettingsStore.normalizeUrl("http://a.b:1///"))
    }

    @Test
    fun `空串保持空`() {
        assertEquals("", SettingsStore.normalizeUrl("   "))
    }
}
