"""从 ZXEB/command-code-usage-android 同步液态玻璃组件到本工程。

为什么要脚本而不是手抄：这些文件的动画/着色器参数是作者调过的（阻尼、光照、
折射率），手抄一遍必然抄错一个数字，表现就是"看着差不多但手感不对"。
脚本只做两件事：拉原始 UTF-8 文本、把包名 `dev.zxeb.ccusage` 换成 `dev.yg.relay`。

用法（需要能访问 raw.githubusercontent.com，国内环境先设 HTTPS_PROXY）：
    python tools/sync_glass.py
"""

from __future__ import annotations

import os
import sys
import urllib.request

REPO = "ZXEB/command-code-usage-android"
BRANCH = "main"
OLD_PACKAGE = "dev.zxeb.ccusage"
NEW_PACKAGE = "dev.yg.relay"

FILES = [
    "ui/glass/animation/DampedDragAnimation.kt",
    "ui/glass/animation/DragGestureInspector.kt",
    "ui/glass/animation/InteractiveHighlight.kt",
    "ui/glass/component/FloatingBottomBar.kt",
    "ui/glass/liquid/CombinedBackdrop.kt",
    "ui/glass/liquid/InnerShadow.kt",
    "ui/glass/liquid/Lens.kt",
    "ui/glass/liquid/Vibrancy.kt",
    "ui/glass/theme/GlassTheme.kt",
    "ui/components/GlassCommon.kt",
    "ui/components/GlassTopBar.kt",
]

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "android", "app", "src", "main", "kotlin", "dev", "yg", "relay")
RAW = "https://raw.githubusercontent.com/%s/%s/app/src/main/kotlin/%s/%s"


def fetch(path: str) -> str:
    url = RAW % (REPO, BRANCH, OLD_PACKAGE.replace(".", "/"), path)
    with urllib.request.urlopen(url, timeout=60) as resp:
        return resp.read().decode("utf-8")


def main() -> int:
    for relative in FILES:
        source = fetch(relative).replace(OLD_PACKAGE, NEW_PACKAGE)
        destination = os.path.join(TARGET, relative.replace("/", os.sep))
        os.makedirs(os.path.dirname(destination), exist_ok=True)
        with open(destination, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(source)
        print("synced %-44s %5d bytes" % (relative, len(source)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
