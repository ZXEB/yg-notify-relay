# 第三方代码来源与许可

## 液态玻璃 UI（`android/app/src/main/kotlin/dev/yg/relay/ui/glass/**` 与 `ui/components/Glass*.kt`）

来源：[ZXEB/command-code-usage-android](https://github.com/ZXEB/command-code-usage-android)（MIT License，Copyright (c) 2026 ZXEB）。

移植方式：整份搬运并只替换包名 `dev.zxeb.ccusage` → `dev.yg.relay`，动画与着色器参数保持原值。
同步脚本见 [`tools/sync_glass.py`](tools/sync_glass.py)，需要重新同步时直接跑它。

涉及文件：

- `ui/glass/animation/DampedDragAnimation.kt`
- `ui/glass/animation/DragGestureInspector.kt`
- `ui/glass/animation/InteractiveHighlight.kt`
- `ui/glass/component/FloatingBottomBar.kt`
- `ui/glass/liquid/CombinedBackdrop.kt`
- `ui/glass/liquid/InnerShadow.kt`
- `ui/glass/liquid/Lens.kt`
- `ui/glass/liquid/Vibrancy.kt`
- `ui/glass/theme/GlassTheme.kt`
- `ui/components/GlassCommon.kt`
- `ui/components/GlassTopBar.kt`

## 运行时依赖

| 依赖 | 许可 |
|---|---|
| [Miuix](https://github.com/compose-miuix-ui/miuix)（`top.yukonga.miuix.kmp:*`） | Apache-2.0 |
| [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) | Apache-2.0 |
| [OkHttp](https://github.com/square/okhttp) | Apache-2.0 |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Apache-2.0 |
| [AndroidX WorkManager](https://developer.android.com/jetpack/androidx/releases/work) | Apache-2.0 |

服务端（`server/serve.py`、`server/web/*`）为纯标准库实现，不包含任何第三方代码。
