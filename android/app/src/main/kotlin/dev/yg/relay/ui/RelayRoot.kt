package dev.yg.relay.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yg.relay.R
import dev.yg.relay.ui.components.GlassTopButton
import dev.yg.relay.ui.components.HyperMenu
import dev.yg.relay.ui.glass.component.FloatingBottomBar
import dev.yg.relay.ui.glass.component.FloatingBottomBarItem
import dev.yg.relay.work.FlushWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings as SettingsIcon
import top.yukonga.miuix.kmp.icon.extended.VerticalSplit
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用根节点（与参考工程同构的澎湃 4 图库布局）。
 *
 * - 页面内容通过 [layerBackdrop] 录进图层，底栏与顶栏圆钮实时模糊它 —— 这就是液态玻璃；
 * - 顶栏没有实色底：右上角两颗玻璃圆钮悬浮在滚动内容之上，内容从它们下方穿过；
 * - 大标题放进滚动内容顶部，随页面一起滑走/滑入；
 * - 页面切换用 [HorizontalPager]，点底栏后两页同屏横向滑动，与玻璃折射联动。
 *
 * 内容区刻意不为底栏留 padding，让滚动内容从玻璃底栏下穿过；每个页面自己在末尾留
 * 90dp 空白，避免最后一项被底栏永久遮挡。
 */
@Composable
fun RelayRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val pageCount = 3
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { pageCount })
    var selectedIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.targetPage }.distinctUntilChanged().collect {
            selectedIndex = it
        }
    }

    // 数据都是本地 SharedPreferences / 文件，读一次很快；用 key 强制重算比引入
    // Repository + Flow 那一套更省事，也不会因为漏了 collect 导致界面不刷新。
    var refreshKey by remember { mutableIntStateOf(0) }
    val refresh: () -> Unit = { refreshKey += 1 }

    val backgroundColor = MiuixTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop {
        // 先铺不透明底色：否则模糊会把透明像素的颜色扩散成色块。
        drawRect(backgroundColor)
        drawContent()
    }

    val glassSupported = remember { isRenderEffectSupported() && isRuntimeShaderSupported() }

    var menuExpanded by remember { mutableStateOf(false) }
    var moreButtonBounds by remember { mutableStateOf<Rect?>(null) }

    // ⋮ 菜单的「立即上传」之后给个短反馈。
    fun notify(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    val goto: (Int) -> Unit = { page ->
        scope.launch { pagerState.animateScrollToPage(page) }
    }

    val openListenerSettings: () -> Unit = {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        // 从系统设置返回后自动重算权限状态。
        scope.launch {
            delay(400)
            refresh()
        }
    }

    val tabs = remember {
        listOf(
            TabSpec(context.getString(R.string.tab_overview), MiuixIcons.VerticalSplit),
            TabSpec(context.getString(R.string.tab_events), MiuixIcons.Info),
            TabSpec(context.getString(R.string.tab_settings), MiuixIcons.SettingsIcon),
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
            // ---- 页面内容（录进 backdrop，供底栏/顶钮采样） ----
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                ) { page ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .statusBarsPadding(),
                    ) {
                        LargePageTitle(title = tabs[page].label)
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            when (page) {
                                0 -> OverviewScreen(
                                    refreshKey = refreshKey,
                                    onOpenSettings = { goto(2) },
                                    onOpenListenerSettings = openListenerSettings,
                                    onSendNow = { notify("已触发上传") },
                                )

                                1 -> EventsScreen(refreshKey = refreshKey)

                                else -> SettingsScreen(
                                    refreshKey = refreshKey,
                                    onSaved = refresh,
                                    onOpenListenerSettings = openListenerSettings,
                                )
                            }
                        }
                    }
                }
            }

            // ---- 顶部悬浮层：两颗玻璃圆钮（内容从下方穿过并被采样模糊） ----
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 8.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassTopButton(
                    backdrop = backdrop,
                    icon = MiuixIcons.Refresh,
                    contentDescription = context.getString(R.string.action_refresh),
                    onClick = {
                        FlushWorker.kick(context, 0L)
                        refresh()
                        notify("已刷新")
                    },
                )
                GlassTopButton(
                    backdrop = backdrop,
                    icon = MiuixIcons.More,
                    contentDescription = context.getString(R.string.action_more),
                    onClick = { menuExpanded = !menuExpanded },
                    modifier = Modifier.onGloballyPositioned { coords ->
                        moreButtonBounds = coords.boundsInWindow()
                    },
                )
            }

            HyperMenu(
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                anchorBounds = moreButtonBounds,
                items = listOf("立即上传", "前往设置"),
                onItemClick = { index ->
                    when (index) {
                        0 -> {
                            FlushWorker.kick(context, 0L)
                            refresh()
                            notify("已触发上传")
                        }

                        else -> goto(2)
                    }
                },
            )

            // ---- 底部液态玻璃悬浮导航 ----
            FloatingBottomBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 28.dp, end = 28.dp, bottom = 16.dp),
                selectedIndex = selectedIndex,
                onSelected = goto,
                backdrop = backdrop,
                tabsCount = tabs.size,
                isBlurEnabled = glassSupported,
            ) { activateTab ->
                tabs.forEachIndexed { index, tab ->
                    FloatingBottomBarItem(
                        selected = selectedIndex == index,
                        // 必须调组件给的 activateTab，不要自己改 selectedIndex：
                        // 它会同时驱动 pill 与拖拽状态，自己改会和 pager 互相打架。
                        onClick = { activateTab(index) },
                        // ⚠️ item 用 weight(1f)，父 Row 是 IntrinsicSize.Min 测量，
                        // 没有 minWidth 兜底整条栏会塌缩。
                        modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(text = tab.label, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** 一个底栏 tab 的描述（标题 + 图标）。 */
private data class TabSpec(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

/**
 * 滚动内容顶部的大标题（澎湃 4 图库的「最近/浏览」形态）。
 *
 * 顶部留白要能容纳右上角两颗悬浮圆钮（44dp 高 + 8dp 上边距 = 52dp），
 * 否则标题会和圆钮叠在同一水平线上。
 */
@Composable
private fun LargePageTitle(title: String) {
    Text(
        text = title,
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        color = MiuixTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 16.dp),
    )
}
