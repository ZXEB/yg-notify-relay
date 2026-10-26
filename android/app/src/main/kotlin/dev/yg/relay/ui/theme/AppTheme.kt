package dev.yg.relay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import dev.yg.relay.ui.glass.theme.LocalGlassDarkTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 主题：走 Monet 动态取色（澎湃/Android 12+ 的壁纸取色），跟随系统深浅色。
 *
 * 同时向液态玻璃组件提供 [LocalGlassDarkTheme]：玻璃需要知道深浅色来定
 * pill 的实色降级底色与投影 alpha，这两件事 Compose 的主题系统表达不了。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.MonetSystem) }
    val darkTheme = isSystemInDarkTheme()
    MiuixTheme(controller = controller) {
        CompositionLocalProvider(LocalGlassDarkTheme provides darkTheme, content = content)
    }
}
