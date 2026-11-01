#!/usr/bin/env python3
"""
B11 收尾：把剩余 6 个测试文件里直接读 `user.dir` 的写法统一到 `TestPaths`。

语义必须逐字等价：`System.getProperty("user.dir") ?: "."` 与 `TestPaths.workingDirPath()`
返回同一个字符串；`File(System.getProperty("user.dir"), rel)` 与
`File(TestPaths.workingDir(), rel)` 是同一个 File。
"""
import re
import sys
from pathlib import Path

REPLACEMENTS = [
    # 纯字符串 cwd
    ('val cwd = System.getProperty("user.dir") ?: "."',
     'val cwd = TestPaths.workingDirPath()'),
    ('private fun cwd(): String = File(System.getProperty("user.dir") ?: ".").absolutePath',
     'private fun cwd(): String = TestPaths.workingDirPath()'),
    # File(cwd, rel)
    ('File(System.getProperty("user.dir"), "src/main/java/com/nbalive/app")',
     'File(TestPaths.workingDir(), "src/main/java/com/nbalive/app")'),
    ('File(System.getProperty("user.dir") ?: ".", "src/main/java/com/nbalive/app")',
     'File(TestPaths.workingDir(), "src/main/java/com/nbalive/app")'),
    # 错误文案里打印 cwd
    ('${File(System.getProperty("user.dir") ?: ".").absolutePath}',
     '${TestPaths.workingDirPath()}'),
]

IMPORT_LINE = "import com.nbalive.app.TestPaths\n"


def main() -> int:
    root = Path(sys.argv[1])
    changed = []
    for path in sorted(root.rglob("*.kt")):
        if path.name == "TestPaths.kt":
            continue
        text = path.read_text(encoding="utf-8")
        original = text
        for old, new in REPLACEMENTS:
            if old in text:
                text = text.replace(old, new)
        if text == original:
            continue
        # 补 import（同包文件不需要）
        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        if pkg and pkg.group(1) != "com.nbalive.app" and IMPORT_LINE not in text:
            lines = text.splitlines(keepends=True)
            last_import = max(
                (i for i, line in enumerate(lines) if line.startswith("import ")),
                default=None,
            )
            if last_import is None:
                print("NO IMPORT BLOCK:", path, file=sys.stderr)
                return 2
            lines.insert(last_import + 1, IMPORT_LINE)
            text = "".join(lines)
        path.write_text(text, encoding="utf-8")
        changed.append(str(path.relative_to(root)))
    print("changed:", len(changed))
    for item in changed:
        print("  ", item)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())