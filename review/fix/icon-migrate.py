#!/usr/bin/env python3
"""
把生产代码里对 `material-icons-extended` 的引用换成本地 `AppIcons`（review/optimization-review.md B3）。

- 只处理"由 extended 提供"的图标（`AppIcons` 里已生成的 25 个）；由 material-icons-core
  提供的 11 个继续用 `Icons.*`（core AAR 只有 0.8 MB）。
- 同时清理已失效的 `import androidx.compose.material.icons.<pkg>.<Name>`，
  并在需要时补 `import com.nbalive.app.ui.icons.AppIcons`。
- 不改任何测试文件。
"""
import re
import sys
from pathlib import Path

# 由 extended 提供 → 已生成到 AppIcons（包, 名字）→ Kotlin 属性名
GENERATED = {
    ("filled", "BrightnessHigh"): "filled_BrightnessHigh",
    ("filled", "BrightnessLow"): "filled_BrightnessLow",
    ("filled", "CalendarMonth"): "filled_CalendarMonth",
    ("filled", "CloudOff"): "filled_CloudOff",
    ("filled", "DarkMode"): "filled_DarkMode",
    ("filled", "ErrorOutline"): "filled_ErrorOutline",
    ("filled", "Fullscreen"): "filled_Fullscreen",
    ("filled", "FullscreenExit"): "filled_FullscreenExit",
    ("filled", "LightMode"): "filled_LightMode",
    ("filled", "LiveTv"): "filled_LiveTv",
    ("filled", "LockOpen"): "filled_LockOpen",
    ("filled", "MoreHoriz"): "filled_MoreHoriz",
    ("filled", "OpenInNew"): "filled_OpenInNew",
    ("filled", "Pause"): "filled_Pause",
    ("filled", "PictureInPictureAlt"): "filled_PictureInPictureAlt",
    ("filled", "PlayCircle"): "filled_PlayCircle",
    ("filled", "Schedule"): "filled_Schedule",
    ("filled", "SignalCellularAlt"): "filled_SignalCellularAlt",
    ("filled", "SportsBasketball"): "filled_SportsBasketball",
    ("outlined", "CalendarMonth"): "outlined_CalendarMonth",
    ("outlined", "PlayCircle"): "outlined_PlayCircle",
    ("automirrored.filled", "ArrowBackIos"): "automirrored_filled_ArrowBackIos",
    ("automirrored.filled", "ArrowForwardIos"): "automirrored_filled_ArrowForwardIos",
    ("automirrored.filled", "VolumeOff"): "automirrored_filled_VolumeOff",
    ("automirrored.filled", "VolumeUp"): "automirrored_filled_VolumeUp",
}

USAGE = {
    "filled": "Icons.Filled.%s",
    "outlined": "Icons.Outlined.%s",
    "automirrored.filled": "Icons.AutoMirrored.Filled.%s",
}
IMPORT = "androidx.compose.material.icons.%s.%s"


def main() -> int:
    root = Path(sys.argv[1])
    changed = []
    for path in sorted(root.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        original = text
        used_appicons = False
        for (pkg, name), prop in GENERATED.items():
            usage = USAGE[pkg] % name
            if usage in text:
                text = text.replace(usage, "AppIcons.%s" % prop)
                used_appicons = True
            # ⚠️ import 必须**无条件**删掉：extended 一旦从 implementation 移走，
            #    残留的 `import androidx.compose.material.icons.filled.FullscreenExit`
            #    就是**编译错误**（未解析的引用），与"这个图标还有没有别的用法"无关。
            imp = "import %s\n" % (IMPORT % (pkg, name))
            text = text.replace(imp, "")
        if used_appicons:
            if "import com.nbalive.app.ui.icons.AppIcons" not in text:
                # 插到 import 区块里（按字典序放在 androidx 之后最省事：紧跟最后一条 import）
                lines = text.splitlines(keepends=True)
                last_import = max(
                    (i for i, line in enumerate(lines) if line.startswith("import ")),
                    default=None,
                )
                if last_import is None:
                    print("NO IMPORT BLOCK:", path, file=sys.stderr)
                    return 2
                lines.insert(last_import + 1, "import com.nbalive.app.ui.icons.AppIcons\n")
                text = "".join(lines)
            # 若文件里不再有 `Icons.` 使用，则删掉 Icons 的 import 与未用的具体图标 import
            if not re.search(r"\bIcons\.", text):
                text = text.replace("import androidx.compose.material.icons.Icons\n", "")
                text = re.sub(r"import androidx\.compose\.material\.icons\.[\w.]+\n", "", text)
        if text != original:
            path.write_text(text, encoding="utf-8")
            changed.append(str(path.relative_to(root)))
    print("changed files:", len(changed))
    for item in changed:
        print("  ", item)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())