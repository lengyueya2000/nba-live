#!/usr/bin/env python3
"""
从 material-icons-extended 的 classes.jar **按字节码还原**图标路径，生成等价的本地 ImageVector
源码（review/optimization-review.md B3）。

为什么要这么做而不是手绘：B3 要把 `material-icons-extended`（classes.jar 35.7 MB）从
`implementation` 里去掉，只保留 `material-icons-core` + 自绘/生成的图标。手绘 25 个图标
属于"看起来差不多"，无法验证；本脚本直接从**官方实现的字节码**里逐条还原
`ImageVector.Builder` 的 path 命令，因此生成的图形与官方**逐命令一致**，
再由 `AppIconsParityTest`（JVM 单测）把两者结构逐项比对钉死。

用法：
  python icon-extract.py <classes.jar 解包目录> <输出 Kt 文件>
"""
import os
import re
import subprocess
import sys
from pathlib import Path

_java_home = os.environ.get("JAVA_HOME", "")
JAVAP = Path(_java_home) / "bin" / ("javap.exe" if os.name == "nt" else "javap")

# 生产代码里实际用到的图标（包 → 名字）
ICONS = [
    ("filled", "BrightnessHigh"), ("filled", "BrightnessLow"), ("filled", "CalendarMonth"),
    ("filled", "Check"), ("filled", "Clear"), ("filled", "Close"), ("filled", "CloudOff"),
    ("filled", "DarkMode"), ("filled", "ErrorOutline"), ("filled", "Fullscreen"),
    ("filled", "FullscreenExit"), ("filled", "Info"), ("filled", "LightMode"),
    ("filled", "LiveTv"), ("filled", "Lock"), ("filled", "LockOpen"), ("filled", "MoreHoriz"),
    ("filled", "OpenInNew"), ("filled", "Pause"), ("filled", "Person"),
    ("filled", "PictureInPictureAlt"), ("filled", "PlayArrow"), ("filled", "PlayCircle"),
    ("filled", "Refresh"), ("filled", "Schedule"), ("filled", "Search"),
    ("filled", "SignalCellularAlt"), ("filled", "SportsBasketball"), ("filled", "Warning"),
    ("outlined", "CalendarMonth"), ("outlined", "Person"), ("outlined", "PlayCircle"),
    ("automirrored.filled", "ArrowBackIos"), ("automirrored.filled", "ArrowForwardIos"),
    ("automirrored.filled", "VolumeOff"), ("automirrored.filled", "VolumeUp"),
]

# PathBuilder 的公开方法 → 参数个数（float / boolean）。键是 JVM 方法名。
PATH_METHODS = {
    "moveTo": 2, "moveToRelative": 2,
    "lineTo": 2, "lineToRelative": 2,
    "horizontalLineTo": 1, "horizontalLineToRelative": 1,
    "verticalLineTo": 1, "verticalLineToRelative": 1,
    "curveTo": 6, "curveToRelative": 6,
    "reflectiveCurveTo": 4, "reflectiveCurveToRelative": 4,
    "quadTo": 4, "quadToRelative": 4,
    "reflectiveQuadTo": 2, "reflectiveQuadToRelative": 2,
    # arcTo(horizontalEllipseRadius, verticalEllipseRadius, theta, isMoreThanHalf, isPositiveArc, x1, y1)
    "arcTo": (3, 2, 2), "arcToRelative": (3, 2, 2),
    "close": 0,
}

PUSH_FLOAT = re.compile(r"//\s*float\s+([-\d.eE+]+)")
PUSH_DOUBLE = re.compile(r"//\s*double\s+([-\d.eE+]+)")
FCONST = {"fconst_0": 0.0, "fconst_1": 1.0, "fconst_2": 2.0}
ICONST = {"iconst_0": 0, "iconst_1": 1, "iconst_2": 2, "iconst_3": 3, "iconst_4": 4, "iconst_5": 5}
INVOKE_PATH = re.compile(r"// Method androidx/compose/ui/graphics/vector/PathBuilder\.(\w+):([^)]*)\)")


def javap(tree: Path, fqcn: str) -> list[str]:
    out = subprocess.run(
        [str(JAVAP), "-c", "-p", "-cp", str(tree), fqcn],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    return out.stdout.splitlines()


def parse_commands(lines: list[str]) -> tuple[list[list[str]], bool]:
    """返回 (每个 `path {}` 块一套 Kotlin 命令, autoMirror)。

    ⚠️ **必须按 `new PathBuilder` 切块**：官方的图标常常写成**多个** `path { }`
    （例如 Schedule 有两个子路径）—— 而"两个 subpath 写在同一个 path 里"与
    "两个独立的 path"在填充规则（NonZero/EvenOdd）下**渲染结果可能不同**，
    所以这不是等价改写。第一版提取器把它们合成一条 path，正是被 parity 测试抓出来的。
    """
    pushes: list[object] = []
    groups: list[list[str]] = []
    current: list[str] | None = None
    auto_mirror = False

    for line in lines:
        stripped = line.strip()
        # 每遇到一次 `new PathBuilder` 就是源文件里的一个新 `path { }` 块
        if "PathBuilder" in stripped and "new " in stripped and "class" in stripped:
            current = []
            groups.append(current)
            continue
        m = INVOKE_PATH.search(stripped)
        if m:
            name, desc = m.group(1), m.group(2)
            if name in ("<init>", "build", "apply", "getDefaultFillType", "constructor-impl"):
                continue
            if name not in PATH_METHODS or current is None:
                continue
            arity = PATH_METHODS[name]
            if name == "close":
                current.append("close()")
                continue
            if isinstance(arity, tuple):
                nfloats, nbool, nfloats2 = arity
                need = nfloats + nbool + nfloats2
                args = pushes[-need:]
                del pushes[-need:]
                f1 = [f(x) for x in args[:nfloats]]
                b = [("true" if x else "false") for x in args[nfloats:nfloats + nbool]]
                f2 = [f(x) for x in args[nfloats + nbool:]]
                current.append("{}({}, {}, {}, {}, {}, {}, {})".format(
                    name, f1[0], f1[1], f1[2], b[0], b[1], f2[0], f2[1]))
                continue
            args = pushes[-arity:] if arity else []
            if arity:
                del pushes[-arity:]
            current.append("{}({})".format(name, ", ".join(f(x) for x in args)))
            continue

        # 收集压栈的常量
        if ".class" in stripped and "new " in stripped:
            continue
        m = PUSH_FLOAT.search(stripped)
        if m:
            pushes.append(float(m.group(1)))
            continue
        m = PUSH_DOUBLE.search(stripped)
        if m:
            pushes.append(float(m.group(1)))
            continue
        for token, value in FCONST.items():
            if re.search(r"\b" + token + r"\b", stripped):
                pushes.append(value)
                break
        else:
            for token, value in ICONST.items():
                if re.search(r"\b" + token + r"\b", stripped):
                    pushes.append(value)
                    break
            else:
                m = re.search(r"\bbipush\s+(-?\d+)", stripped)
                if m:
                    pushes.append(int(m.group(1)))
                    continue
                m = re.search(r"\bsipush\s+(-?\d+)", stripped)
                if m:
                    pushes.append(int(m.group(1)))
                    continue
                # autoMirror：Builder 构造里紧跟在 lconst_0(tintColor) 之后的那一个布尔
                if "lconst_0" in stripped:
                    pushes.append("__tint__")
                    continue
                if re.search(r"\biconst_[01]\b", stripped) and pushes and pushes[-1] == "__tint__":
                    auto_mirror = bool(pushes[-1] is None)  # placeholder
                    continue
    return groups, auto_mirror


def f(value) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    text = repr(float(value))
    if text.endswith(".0"):
        text += "f"
    else:
        text += "f"
    return text


def main() -> int:
    tree, core_tree, output = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    blocks = []
    from_core = []
    for pkg, name in ICONS:
        class_rel = pkg.replace(".", "/") + "/" + name + "Kt.class"
        core_class = core_tree / "androidx/compose/material/icons" / class_rel
        ext_class = tree / "androidx/compose/material/icons" / class_rel
        if core_class.is_file():
            # 这个图标由 material-icons-core 提供（extended 不重复它）→ 不需要生成，
            # 生产代码继续用 `Icons.<pkg>.<name>`；core AAR 只有 0.8 MB。
            from_core.append("%s.%s" % (pkg, name))
            continue
        fqcn = "androidx.compose.material.icons.%s.%sKt" % (pkg, name)
        if not ext_class.is_file():
            print("MISSING in both:", class_rel, file=sys.stderr)
            return 2
        lines = javap(tree, fqcn)
        commands, _ = parse_commands(lines)
        if not commands:
            print("NO COMMANDS:", name, file=sys.stderr)
            return 3
        accessor = name
        kt_name = "%s_%s" % (pkg.replace(".", "_"), name)
        auto = "true" if pkg.startswith("automirrored") else "false"
        icon_name = "AutoMirrored.Filled.%s" % name if pkg.startswith("automirrored") else (
            "Filled.%s" % name if pkg == "filled" else "Outlined.%s" % name)
        # 一个 `path { }` 块 = 一个 VectorPath（官方常常一份图标里有多个）；
        # 合并成一条 path 会改变填充规则下的渲染结果，所以这里逐块生成。
        body_parts = []
        for group in commands:
            body_parts.append(
                "            path(fill = SolidColor(Color.Black), strokeLineWidth = 1f) {\n"
                + "\n".join("                " + c for c in group)
                + "\n            }")
        body = "\n".join(body_parts)
        blocks.append(
            """    /** 等价于官方 `%s`（路径逐命令由官方实现的字节码还原，见 parity 测试）。 */
    val %s: ImageVector by lazy {
        ImageVector.Builder(
            name = "%s",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = %s,
        ).apply {
            // ⚠️ 每个 `path {}` 块对应官方的一个 VectorPath（**不要合并**：
            // 两个独立 path 与"同一条 path 里的两个子路径"在填充规则下渲染可能不同）。
            // `strokeLineWidth = 1f` 必须写出来：官方就是 1.0f、DSL 默认是 0f
            // （两边都不描边，视觉无差别，但等价比对是逐字段的）。
%s
        }.build()
    }
""" % (icon_name, kt_name, icon_name, auto, body))
    header = """package com.nbalive.app.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 本地图标集（review/optimization-review.md **B3**）。
 *
 * ## 为什么不直接用 `material-icons-extended`
 *
 * 那个 AAR 的 `classes.jar` 是 **35.7 MB**（每个图标一个类），而本 App 实际只用三十来个；
 * release 侧 R8 会砍到只剩用到的，但**每个 clean build 都要 D8/R8 处理这 35.7 MB**，
 * debug 包更是把整包打进 dex（22 MB / 12 dex）。
 *
 * ## 为什么敢用"生成"而不是"手绘"
 *
 * 这些路径是从官方 AAR 的**字节码**里逐命令还原的（`review/fix/icon-extract.py`），
 * 不是照着图片描的；并且 `AppIconsParityTest` 把每个图标与官方 `Icons.*` 的
 * `ImageVector` 结构（viewport / autoMirror / 每条 path 命令与参数）**逐项比对**，
 * 任何一处还原错误都会当场变红。所以视觉与原版一致这件事是**可验证**的，不靠眼睛。
 *
 * ⚠️ 改这里的图标 = 改 UI 外观。请只新增，不要"顺手调整"路径。
 */
@Suppress("UnusedReceiverParameter")
object AppIcons {

"""
    output.write_text(header + "\n".join(blocks) + "}\n", encoding="utf-8")
    print("written:", output, "generated:", len(blocks))
    print("provided by material-icons-core (no generation needed):", ", ".join(from_core))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())