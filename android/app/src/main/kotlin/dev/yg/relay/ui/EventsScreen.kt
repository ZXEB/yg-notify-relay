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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yg.relay.data.LocalLogStore
import dev.yg.relay.data.LogEntry
import dev.yg.relay.data.LogStatus
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本机最近捕获到的消息（最多 [LocalLogStore.MAX_ENTRIES] 条）。
 *
 * 这一页的意义不是「看消息」——完整消息在服务器网页上——而是确认「本机到底抓到了什么、
 * 发出去了没有」。所以每条都带状态点，失败时把错误原因显示在下面。
 */
@Composable
fun EventsScreen(refreshKey: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val entries = remember(refreshKey) { LocalLogStore(context).recent() }

    if (entries.isEmpty()) {
        Column(modifier = modifier.fillMaxWidth()) {
            SmallTitle("消息")
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "还没有捕获到通知",
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "先在「概览」里打开通知使用权，然后等一条微信或短信进来。" +
                            "捕获到的每一条都会显示在这里，并标注是否已上传到服务器。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Spacer(Modifier.height(90.dp))
        }
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SmallTitle("最近 ${entries.size} 条")
        entries.forEach { entry ->
            EventCard(entry)
        }
        Spacer(Modifier.height(90.dp))
    }
}

@Composable
private fun EventCard(entry: LogEntry) {
    val event = entry.event
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = event.app.label,
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusLabel(entry.status),
                    fontSize = 11.sp,
                    color = statusColor(entry.status),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatTime(event.postedAt),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            event.title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.main,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            event.text?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            entry.error?.takeIf { it.isNotBlank() }?.let {
                Text(text = "⚠ $it", fontSize = 11.sp, color = RelayErrorColor)
            }
        }
    }
}

private fun statusLabel(status: String): String = when (status) {
    LogStatus.SENT.name -> "已发送"
    LogStatus.FAILED.name -> "发送失败"
    else -> "待发送"
}

private fun statusColor(status: String) = when (status) {
    LogStatus.SENT.name -> RelaySuccessColor
    LogStatus.FAILED.name -> RelayErrorColor
    else -> androidx.compose.ui.graphics.Color(0xFFFF9F0A)
}

private fun formatTime(millis: Long): String {
    if (millis <= 0L) return "--"
    return SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
}
