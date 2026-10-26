package dev.yg.relay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yg.relay.data.LocalLogStore
import dev.yg.relay.data.LogStatus
import dev.yg.relay.data.OutboxStore
import dev.yg.relay.data.SettingsStore
import dev.yg.relay.service.RelayNotificationService
import dev.yg.relay.work.FlushWorker
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 错误色：Miuix 的 colorScheme 不保证有语义化的 error 槽位，这里固定一个可读的红色。 */
internal val RelayErrorColor = Color(0xFFFF453A)
internal val RelaySuccessColor = Color(0xFF35C759)

/**
 * 概览：一眼看清「有没有在转发、卡在哪」。
 *
 * 排版刻意按「先结论、再数字、最后原因」排：最上面是接入与权限（不通就是这里的问题），
 * 中间是队列与统计，最下面是最近一次错误原文。
 */
@Composable
fun OverviewScreen(
    refreshKey: Int,
    onOpenSettings: () -> Unit,
    onOpenListenerSettings: () -> Unit,
    onSendNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snapshot = remember(refreshKey) {
        val settings = SettingsStore(context)
        val pending = OutboxStore(context).pendingCount()
        val entries = LocalLogStore(context).recent()
        OverviewSnapshot(
            configured = settings.isConfigured,
            serverUrl = settings.serverUrl,
            paused = settings.paused,
            alias = settings.alias.ifBlank { android.os.Build.MODEL ?: "本机" },
            listenerEnabled = RelayNotificationService.isEnabled(context),
            pending = pending,
            sent = entries.count { it.status == LogStatus.SENT.name },
            failed = entries.count { it.status == LogStatus.FAILED.name },
            lastSuccessAt = settings.lastSuccessAt,
            lastError = settings.lastError,
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SmallTitle("接入")
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (snapshot.configured) snapshot.serverUrl else "还没有填写服务器",
                    style = MiuixTheme.textStyles.subtitle,
                    color = if (snapshot.configured) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        RelayErrorColor
                    },
                )
                Text(
                    text = "设备名：${snapshot.alias}" +
                        if (snapshot.paused) "　·　已暂停转发" else "",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = "最近成功上传：" + formatTime(snapshot.lastSuccessAt),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (snapshot.lastError.isNotBlank()) {
                    Text(
                        text = "⚠ ${snapshot.lastError}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = RelayErrorColor,
                    )
                }
                if (!snapshot.configured) {
                    Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("去填写服务器与令牌")
                    }
                }
            }
        }

        SmallTitle("通知权限")
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(ok = snapshot.listenerEnabled)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (snapshot.listenerEnabled) "已获得通知使用权" else "尚未获得通知使用权",
                        style = MiuixTheme.textStyles.main,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = "系统把通知推给本应用后，才会转发出去。这个权限只能由你在系统设置里手动打开，" +
                        "本应用无法代劳，也不需要常驻后台。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (!snapshot.listenerEnabled) {
                    Button(onClick = onOpenListenerSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("前往系统设置开启")
                    }
                }
            }
        }

        SmallTitle("队列与统计")
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Metric("待发", snapshot.pending.toString(), Modifier.weight(1f))
                    Metric("已发送", snapshot.sent.toString(), Modifier.weight(1f))
                    Metric("失败", snapshot.failed.toString(), Modifier.weight(1f))
                }
                Text(
                    text = "统计来自本机最近 ${LocalLogStore.MAX_ENTRIES} 条记录；完整历史在服务器网页上。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Button(
                    onClick = {
                        FlushWorker.kick(context, 0L)
                        onSendNow()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (snapshot.pending > 0) "立即上传 ${snapshot.pending} 条" else "队列已清空")
                }
            }
        }

        Spacer(Modifier.height(90.dp))
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            fontSize = 28.sp,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun StatusDot(ok: Boolean) {
    Text(
        text = if (ok) "●" else "○",
        fontSize = 12.sp,
        color = if (ok) RelaySuccessColor else RelayErrorColor,
    )
}

private data class OverviewSnapshot(
    val configured: Boolean,
    val serverUrl: String,
    val paused: Boolean,
    val alias: String,
    val listenerEnabled: Boolean,
    val pending: Int,
    val sent: Int,
    val failed: Int,
    val lastSuccessAt: Long,
    val lastError: String,
)

private fun formatTime(millis: Long): String {
    if (millis <= 0L) return "从未成功"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}
