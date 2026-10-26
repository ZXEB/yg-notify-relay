package dev.yg.relay.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.yg.relay.data.LocalLogStore
import dev.yg.relay.data.OutboxStore
import dev.yg.relay.data.SettingsStore
import dev.yg.relay.model.ContentMode
import dev.yg.relay.work.FlushWorker
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置。
 *
 * 所有开关都是「改完立即生效并落盘」，只有服务器地址/令牌/别名三个文本框需要按保存
 * —— 因为输入中途的半个 URL 不该被写进配置，否则 WorkManager 会拿着半截地址去重试。
 */
@Composable
fun SettingsScreen(
    refreshKey: Int,
    onSaved: () -> Unit,
    onOpenListenerSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }

    var serverUrl by remember(refreshKey) { mutableStateOf(settings.serverUrl) }
    var token by remember(refreshKey) { mutableStateOf(settings.token) }
    var alias by remember(refreshKey) { mutableStateOf(settings.alias) }
    var contentMode by remember(refreshKey) { mutableStateOf(settings.contentMode) }
    var batchDelay by remember(refreshKey) { mutableLongStateOf(settings.batchDelayMs) }
    var wifiOnly by remember(refreshKey) { mutableStateOf(settings.wifiOnly) }
    var paused by remember(refreshKey) { mutableStateOf(settings.paused) }
    var smsEnabled by remember(refreshKey) { mutableStateOf(settings.smsEnabled) }
    var whitelist by remember(refreshKey) { mutableStateOf(settings.whitelist) }
    var hint by remember { mutableStateOf<String?>(null) }
    var appsExpanded by remember { mutableStateOf(false) }
    val apps = remember { loadLaunchableApps(context) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---------------- 服务器 ----------------
        SmallTitle("服务器")
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = "服务器地址",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = token,
                    onValueChange = { token = it },
                    label = "上传令牌",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = "设备别名（网页上显示）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "地址形如 http://192.168.1.10:8787（可省略 http://）。令牌在服务器首次启动时打印，" +
                        "也可在网页「设置 → 接入令牌」里重置。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Button(
                    onClick = {
                        settings.serverUrl = serverUrl
                        settings.token = token
                        settings.alias = alias
                        hint = "已保存"
                        FlushWorker.kick(context, 0L)
                        onSaved()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("保存并立即上传一次")
                }
                hint?.let {
                    Text(text = it, style = MiuixTheme.textStyles.footnote2, color = RelaySuccessColor)
                }
            }
        }

        // ---------------- 转发 ----------------
        SmallTitle("转发")
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ToggleRow(
                    title = "暂停转发",
                    description = "打开后监听仍在，但不再把新通知加入队列。",
                    value = paused,
                ) { value ->
                    paused = value
                    settings.paused = value
                }

                ToggleRow(
                    title = "只在 Wi-Fi 上传",
                    description = "移动网络下先堆在本地队列，连上 Wi-Fi 再一次性发出去。",
                    value = wifiOnly,
                ) { value ->
                    wifiOnly = value
                    settings.wifiOnly = value
                }

                ToggleRow(
                    title = "短信直读",
                    description = "直接读 SMS_RECEIVED 拿到原始短信与发件号码（需要短信权限）。" +
                        "通知监听其实已能收到短信通知，这个开关是为了在通知被折叠时更完整。",
                    value = smsEnabled,
                ) { value ->
                    smsEnabled = value
                    settings.smsEnabled = value
                    if (value) requestSmsPermission(context)
                }

                Column {
                    Text(
                        text = "内容模式",
                        style = MiuixTheme.textStyles.main,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeButton("完整内容", contentMode == ContentMode.FULL, Modifier.weight(1f)) {
                            contentMode = ContentMode.FULL
                            settings.contentMode = ContentMode.FULL
                        }
                        ModeButton("只发标题", contentMode == ContentMode.TITLE_ONLY, Modifier.weight(1f)) {
                            contentMode = ContentMode.TITLE_ONLY
                            settings.contentMode = ContentMode.TITLE_ONLY
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "「只发标题」只把应用名与标题发出去，正文留在手机上。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                Column {
                    Text(
                        text = "合并延迟：" + if (batchDelay % 1000L == 0L) {
                            "${batchDelay / 1000} 秒"
                        } else {
                            "${batchDelay / 1000.0} 秒"
                        },
                        style = MiuixTheme.textStyles.main,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1000L, 2500L, 5000L).forEach { option ->
                            ModeButton(
                                label = "${option / 1000}s",
                                selected = batchDelay == option,
                                modifier = Modifier.weight(1f),
                            ) {
                                batchDelay = option
                                settings.batchDelayMs = option
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "连续多条通知只会在最后一条之后等这么久再合并上传，这是省电的主要手段。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        // ---------------- 应用范围 ----------------
        SmallTitle("应用范围")
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (whitelist.isEmpty()) {
                        "当前：转发全部应用（系统噪声除外）"
                    } else {
                        "当前：只转发选中的 ${whitelist.size} 个应用"
                    },
                    style = MiuixTheme.textStyles.main,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        text = if (appsExpanded) "收起列表" else "选择应用",
                        onClick = { appsExpanded = !appsExpanded },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "恢复全部",
                        onClick = {
                            whitelist = emptySet()
                            settings.whitelist = emptySet()
                            hint = "已恢复为全部应用"
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (appsExpanded) {
                    if (apps.isEmpty()) {
                        Text(
                            text = "没有读取到可启动的应用列表。",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    apps.take(60).forEach { app ->
                        val selected = app.packageName in whitelist
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = app.label,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            TextButton(
                                text = if (selected) "已选" else "选择",
                                onClick = {
                                    val next = if (selected) whitelist - app.packageName else whitelist + app.packageName
                                    whitelist = next
                                    settings.whitelist = next
                                },
                            )
                        }
                    }
                    if (apps.size > 60) {
                        Text(
                            text = "只列出前 60 个应用（共 ${apps.size} 个）。",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        // ---------------- 维护 ----------------
        SmallTitle("维护")
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(text = "打开通知使用权设置", onClick = onOpenListenerSettings, modifier = Modifier.fillMaxWidth())
                TextButton(
                    text = "电池优化白名单设置",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    text = "清空本地待发队列",
                    onClick = {
                        OutboxStore(context).clear()
                        LocalLogStore(context).clear()
                        hint = "本地队列已清空"
                        onSaved()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    text = "清除服务器地址与令牌",
                    onClick = {
                        settings.clearCredentials()
                        serverUrl = ""
                        token = ""
                        hint = "凭据已清除"
                        onSaved()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SmallTitle("隐私")
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    text = "服务器地址与令牌只保存在本机应用私有存储，已排除出云备份与设备迁移；" +
                        "消息只发往你填的那台服务器，不经过任何第三方。\n\n" +
                        "转发靠系统的通知使用权：通知到达时系统回调本应用，本应用把内容追加到本地队列，" +
                        "再由 WorkManager 择机批量上传。整个过程不轮询、不常驻连接、不开前台服务。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        Spacer(Modifier.height(90.dp))
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.main,
                color = MiuixTheme.colorScheme.onSurface,
            )
            TextButton(
                text = if (value) "已开启" else "已关闭",
                onClick = { onChange(!value) },
            )
        }
        Text(
            text = description,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        TextButton(text = label, onClick = onClick, modifier = modifier)
    }
}

private data class AppItem(val packageName: String, val label: String)

private fun loadLaunchableApps(context: Context): List<AppItem> = runCatching {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    context.packageManager.queryIntentActivities(intent, 0)
        .map { info ->
            AppItem(
                packageName = info.activityInfo.packageName,
                label = info.loadLabel(context.packageManager).toString(),
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label }
}.getOrDefault(emptyList())

private fun requestSmsPermission(context: Context) {
    val activity = context as? android.app.Activity ?: return
    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
        activity,
        android.Manifest.permission.RECEIVE_SMS,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!granted) {
        androidx.core.app.ActivityCompat.requestPermissions(
            activity,
            arrayOf(android.Manifest.permission.RECEIVE_SMS),
            SMS_PERMISSION_REQUEST,
        )
    }
}

private const val SMS_PERMISSION_REQUEST = 1001
