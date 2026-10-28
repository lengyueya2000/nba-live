package com.nbalive.app

import java.io.File

/**
 * 源码扫描类守卫的**共享**工具：剔非代码 + 结构定位。
 *
 * ## 为什么需要它（而不是每个测试各写一份 `stripComments`）
 *
 * 只读审计 `review/audit-guards.md` 的「未验证的怀疑 #6」记录了本仓库存在**两套**
 * 剔注释实现：宽松版只把"整行注释"置空，**行尾 `// …` 与行内 `/* … */` 原样保留**。
 * 后果与本仓库最核心的缺陷同形：把真正的调用删掉，只在同一行留一句
 * `// 用 ManualPipPolicy.decide(...)`，断言照样绿 —— 又一个"永远不会红的守卫"。
 * 现在所有测试统一走这里的词法级 [stripNonCode]。
 *
 * ## 三层能力
 *
 * 1. **剔非代码**：[stripNonCode]（Kotlin：注释 + 字符串字面量）、
 *    [stripXmlComments]（XML：`<!-- … -->`，含跨行）。
 * 2. **定位结构**：[functionBody]（函数体）、[callBlocks]（某调用的实参 + 尾随 lambda）、
 *    [branchBlocks]（`X -> { … }` 分支体）、[whenBodyWithCall]（挂在指定分支标签上的 `when` 体）。
 *    —— 用它们把判据从"文件里出现过"收紧到"必须在这一处"。
 * 3. **判据原语**：[callSites]（调用点计数，自动排除声明行）、[references]（不要求带括号的
 *    引用，用于枚举常量/属性）、[uniqueFile]（按文件名唯一定位，0 或多个命中都硬失败）。
 *
 * ## 语义约定（很重要）
 *
 * - 替换**保留换行**，所以 `file:line` 行号依然真实；
 * - 剔除的字符换成空格而不是删掉，避免把两个 token 粘成一个；
 * - 字符串模板 `${…}` 里的内容是**代码**，其中嵌套的字符串继续按同样规则处理，
 *   所以写在模板里的调用照样能被抓到；
 * - 点号附近允许空白（`Foo . bar (` 与 `Foo.bar(` 等价），换行/全限定名前缀都不影响匹配 ——
 *   这是为了拦住审计反复点名的「正则只匹配当前写法」（A1/A7）。
 *
 * 本文件自身也被 `GuardHygieneTest` 的元守卫扫描，所以它自己也不许含 `assume` 家族的代码引用。
 */
internal object GuardSourceScan {

    // ------------------------------------------------------------------
    // 一、剔非代码
    // ------------------------------------------------------------------

    /** 注释与字符串字面量 → 空格（保留换行）。 */
    fun stripNonCode(text: String): String =
        StringBuilder(text.length)
            .also { appendStripped(text, 0, it, stopAtCloseBrace = false) }
            .toString()

    /**
     * XML 注释 `<!-- … -->` → 空格（保留换行），**支持跨行**。
     *
     * 为什么不能用"逐行找 `<!--`"：`res/values/themes.xml` 顶部的说明块就是
     * `<!--` 起、几行后 `-->` 收 —— 只处理整行注释的实现会把中间几行当成"代码"，
     * 于是"删掉真属性、只在注释里提一句"又能骗过断言（审计 A2 的原形）。
     */
    fun stripXmlComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text.startsWith("<!--", i)) {
                val end = text.indexOf("-->", i + 4)
                val stop = if (end < 0) text.length else end + 3
                for (k in i until stop) out.append(if (text[k] == '\n') '\n' else ' ')
                i = stop
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // 二、判据原语
    // ------------------------------------------------------------------

    /**
     * [callee]（形如 `WebMediaPauseScript.PAUSE_ALL`，**不带**括号）的**调用点**数量。
     *
     * - 先剔注释与字符串（铁律①）；
     * - 排除把该名字**声明**出来的行（`fun`/`val`/`var …`）—— 声明不算使用（铁律②）；
     * - 匹配调用形状 `callee(`，点号两侧允许空白，因此 `Foo . bar(`、
     *   全限定名 `com.x.Foo.bar(` 都能命中（换个等价写法不会漏，铁律②）。
     */
    fun callSites(rawText: String, callee: String): Int {
        val code = stripNonCode(rawText)
        val call = callRegex(callee)
        val declaration = declarationRegex(callee)
        return code.lines()
            .filterNot { declaration.containsMatchIn(it) }
            .sumOf { line -> call.findAll(line).count() }
    }

    /** [dottedName] 作为**引用**（不要求后面跟 `(`）出现的次数：用于 `BackAction.ResetTab` 这类枚举分支。 */
    fun references(rawText: String, dottedName: String): Int =
        refRegex(dottedName).findAll(stripNonCode(rawText)).count()

    /**
     * 从 [declaration]（如 `fun onTick(`、`fun WebSurface(`）开始、用花括号配对截出的函数体（含两端花括号）。
     *
     * `private`/`internal` 等修饰符、`fun` 与函数名之间的空白都不影响匹配（只认函数名）。
     *
     * ⚠️ 必须先跳过**参数表**（括号配对）再找函数体的 `{`：
     * Compose 的形参默认值里全是 lambda（`onPlaybackError: () -> Unit = {}`），
     * 直接找第一个 `{` 会拿到那个空 lambda，于是"函数体"里永远看不到任何调用 ——
     * 一条永远不会红的守卫。
     *
     * 找不到声明 / 括号不配对 / 表达式体函数（`= …`）一律 `error()`，绝不返回空串让调用方静默全绿。
     */
    fun functionBody(rawText: String, declaration: String): String {
        val code = stripNonCode(rawText)
        val name = declaration
            .substringAfter("fun ", declaration)
            .substringBefore('(')
            .trim()
        if (name.isEmpty()) error("declaration 必须是 `fun 函数名(` 形式：$declaration")
        val decl = Regex("""\bfun\s+${Regex.escape(name)}\s*\(""").find(code)
            ?: error("找不到函数声明 `$name(…` —— 守卫会静默失效，所以直接失败")
        val paramOpen = decl.range.last
        var parens = 0
        var cursor = paramOpen
        while (cursor < code.length) {
            when (code[cursor]) {
                '(' -> parens++
                ')' -> {
                    parens--
                    if (parens == 0) break
                }
            }
            cursor++
        }
        if (cursor >= code.length) error("`$declaration` 的参数表括号没配对")
        val open = code.indexOf('{', cursor)
        if (open < 0) error("`$declaration` 后面找不到函数体起始 `{`")
        if (code.substring(cursor + 1, open).contains('=')) {
            error("`$declaration` 看起来是表达式体函数（`= …` 而不是 `{ … }`），本判据不支持")
        }
        val end = braceEnd(code, open)
        return code.substring(open, end + 1)
    }

    /**
     * [marker]（函数名，如 `BackHandler`、`LaunchedEffect`、`DisposableEffect`）的每一次调用：
     * 返回"实参 + 紧随其后的尾随 lambda"整段文本。
     *
     * 用途：把判据从"文件里出现过"收紧到"必须出现在这一处调用的实参/回调里"。
     * 例如返回键判据必须落在 `BackHandler(...) { … }` 这一段里，而不是文件任意位置。
     * `BackHandler (…)`（名字与括号之间有空白）同样命中。
     */
    fun callBlocks(rawText: String, marker: String): List<String> {
        val code = stripNonCode(rawText)
        val callee = marker.removeSuffix("(")
        val pattern = Regex("""\b""" + dottedPattern(callee) + """\s*\(""")
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val m = pattern.find(code, from) ?: break
            val openParen = m.range.last
            var depth = 0
            var i = openParen
            var close = -1
            while (i < code.length) {
                when (code[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            close = i
                            i = code.length
                        }
                    }
                }
                if (close >= 0) break
                i++
            }
            if (close < 0) error("`$callee` 的参数表括号不配对")
            var end = close
            var j = close + 1
            while (j < code.length && code[j].isWhitespace()) j++
            if (j < code.length && code[j] == '{') end = braceEnd(code, j)
            out += code.substring(m.range.first, end + 1)
            from = end + 1
        }
        return out
    }

    /**
     * [label]（如 `Lifecycle.Event.ON_STOP`、`Route.DETAIL`）后面紧跟的 `{ … }` 分支体。
     *
     * 先跳过括号里的内容再找 `{`：`Route.DETAIL -> when (val s = f()) { … }` 的分支体是
     * 那个 `when` 的体，而不是 `when` 条件里某个 lambda 的体。
     */
    fun branchBlocks(rawText: String, label: String): List<String> {
        val code = stripNonCode(rawText)
        val out = mutableListOf<String>()
        var from = 0
        val pattern = Regex("""\b""" + Regex.escape(label) + """\b""")
        while (true) {
            val m = pattern.find(code, from) ?: break
            var j = m.range.last + 1
            var parens = 0
            var open = -1
            while (j < code.length) {
                when (code[j]) {
                    '(' -> parens++
                    ')' -> parens--
                    '{' -> if (parens == 0) {
                        open = j
                        j = code.length
                    }
                }
                if (open >= 0) break
                j++
            }
            if (open >= 0) out += code.substring(open, braceEnd(code, open) + 1)
            from = m.range.last + 1
        }
        return out
    }

    /**
     * 找出"包含 [callee] 调用"且挂 [branchLabel]（如 `Route.DETAIL`）分支上的那个 `when`，
     * 返回**整个 `when` 块**（含条件里的 subject —— `when (val s = f()) { … }` 的 `f()` 在
     * 条件里，不在 `{ }` 体内；只看体内会漏掉它）。
     *
     * 这条判据同时守住两件事：
     *  - 判据调用必须在**这个** `when` 块里（不是文件里随便一处）；
     *  - 这个 `when` 必须是那个路由分支的表达式（`Route.DETAIL -> when (…)`）。
     * 命中数 ≠ 1、缺少该分支、花括号不配对，一律 `error()`。
     */
    fun whenBodyWithCall(rawText: String, callee: String, branchLabel: String): String {
        val code = stripNonCode(rawText)
        val call = callRegex(callee)
        val candidates = mutableListOf<Pair<Int, String>>()
        val whenPattern = Regex("""\bwhen\b""")
        var from = 0
        while (true) {
            val m = whenPattern.find(code, from) ?: break
            val blockStart = m.range.first
            var j = m.range.last + 1
            while (j < code.length && code[j].isWhitespace()) j++
            if (j < code.length && code[j] == '(') {
                var depth = 0
                while (j < code.length) {
                    when (code[j]) {
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0) {
                                j++
                                break
                            }
                        }
                    }
                    j++
                }
            }
            while (j < code.length && code[j].isWhitespace()) j++
            if (j < code.length && code[j] == '{') {
                val block = code.substring(blockStart, braceEnd(code, j) + 1)
                candidates += blockStart to block
            }
            from = m.range.last + 1
        }
        val hits = candidates.filter { (_, block) -> call.containsMatchIn(block) }
        if (hits.isEmpty()) error("找不到任何 `when` 块里包含 $callee 调用 —— 守卫会静默失效")
        // ⚠️ 取**最内层**：路由分支常写成 `when (route) { Route.DETAIL -> when (…) { … } }`，
        // 外层 when 也会"包含"这个调用。取最外层会让判据锚错块（甚至把整个路由表都算进去）。
        val innermost = hits.filter { (start, block) ->
            hits.none { (otherStart, otherBlock) ->
                otherStart != start &&
                    otherStart > start &&
                    otherStart < start + block.length &&
                    otherBlock.length < block.length
            }
        }
        if (innermost.size != 1) {
            error(
                "包含 `$callee` 的最内层 `when` 有 ${innermost.size} 个，无法唯一确定判据位置 —— " +
                    "守卫会静默失效，所以直接失败",
            )
        }
        val (whenIndex, block) = innermost.single()
        val prefix = code.substring(0, whenIndex).trimEnd().takeLast(300)
        val branchMarker = Regex("""\b""" + Regex.escape(branchLabel) + """\s*->\s*$""")
        if (!branchMarker.containsMatchIn(prefix)) {
            error(
                "包含 `$callee` 的那个 `when` 没有挂在 `$branchLabel ->` 分支上 —— " +
                    "判据必须锚在该路由分支内（前文：…${prefix.takeLast(80)}）",
            )
        }
        return block
    }

    /**
     * 按文件名在 [files] 里**唯一**定位。
     *
     * 0 个命中 → 硬失败（否则守卫静默全绿）；多个同名命中 → 硬失败
     * （审计 A16 /「未验证的怀疑 #7」：`firstOrNull { it.name == … }` 会静默取遍历序第一个，
     * "找到哪个"不确定 = 守卫可能守错文件）。
     */
    fun uniqueFile(files: Collection<File>, name: String): File {
        val hits = files.filter { it.name == name }
        if (hits.isEmpty()) {
            error("找不到 $name —— 依赖它的守卫会静默全绿，所以直接失败")
        }
        if (hits.size > 1) {
            error(
                "$name 有 ${hits.size} 份：${hits.joinToString { it.path }} —— " +
                    "按文件名取第一个的定位是不确定的（审计 A16/#7），守卫可能守错文件；请改用唯一路径",
            )
        }
        return hits.single()
    }

    /**
     * 在 [files] 里找**代码**（已剔注释/字符串）中唯一出现 [marker] 的文件。
     *
     * 用于**跨文件判据**：符号被拆分/搬家到新文件（例如 `NbaLiveApp.kt` 拆成
     * `AppChrome.kt` / `AppEffects.kt`）后，写死文件名的锚点会失效 ——
     * 按内容定位则跟着代码走，抗重构。
     *
     * 0 个 → `error()`（否则守卫静默全绿）；多个 → `error()`（锚点不唯一，需要更精确的 marker）。
     */
    fun uniqueFileContaining(files: Collection<File>, marker: String): File {
        val hits = files.filter { stripNonCode(it.readText()).contains(marker) }
        if (hits.isEmpty()) {
            error(
                "没有任何生产文件里出现 `$marker` —— 依赖它的守卫会静默失效，" +
                    "所以直接失败（若代码被改名/搬走，请同步更新 marker）",
            )
        }
        if (hits.size > 1) {
            error(
                "`$marker` 出现在 ${hits.size} 个文件：${hits.joinToString { it.path }} —— " +
                    "无法确定唯一锚点；请换更精确的 marker",
            )
        }
        return hits.single()
    }

    // ------------------------------------------------------------------
    // 三、内部工具
    // ------------------------------------------------------------------

    private fun callRegex(callee: String): Regex =
        Regex("""\b""" + dottedPattern(callee) + """\s*\(""")

    private fun refRegex(dottedName: String): Regex =
        Regex("""\b""" + dottedPattern(dottedName) + """\b""")

    private fun dottedPattern(dottedName: String): String =
        dottedName.split('.').joinToString("""\s*\.\s*""") { Regex.escape(it) }

    private fun declarationRegex(callee: String): Regex {
        val name = callee.substringBefore('(').substringAfterLast('.')
        return Regex("""\b(?:fun|val|var)\s+""" + Regex.escape(name) + """\b""")
    }

    /** [open] 处的 `{` 对应的 `}` 下标；不配对直接 `error()`。 */
    private fun braceEnd(code: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        error("花括号不配对（起点 $open）—— 守卫会静默全绿")
    }

    /** 从 [start] 开始按词法规则把结果追加进 [out]，返回下一个未消费的下标。 */
    private fun appendStripped(s: String, start: Int, out: StringBuilder, stopAtCloseBrace: Boolean): Int {
        var i = start
        var braceDepth = 1
        while (i < s.length) {
            val c = s[i]
            when {
                // 行注释
                c == '/' && s.getOrNull(i + 1) == '/' -> {
                    while (i < s.length && s[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                // 块注释（Kotlin 允许嵌套）
                c == '/' && s.getOrNull(i + 1) == '*' -> {
                    out.append(' ').append(' ')
                    i += 2
                    var depth = 1
                    while (i < s.length && depth > 0) {
                        if (s[i] == '/' && s.getOrNull(i + 1) == '*') {
                            depth++
                            out.append(' ').append(' ')
                            i += 2
                        } else if (s[i] == '*' && s.getOrNull(i + 1) == '/') {
                            depth--
                            out.append(' ').append(' ')
                            i += 2
                        } else {
                            out.append(if (s[i] == '\n') '\n' else ' ')
                            i++
                        }
                    }
                }
                // 原样字符串 """..."""
                c == '"' && s.getOrNull(i + 1) == '"' && s.getOrNull(i + 2) == '"' -> {
                    out.append("   ")
                    i += 3
                    while (i < s.length) {
                        if (s[i] == '"' && s.getOrNull(i + 1) == '"' && s.getOrNull(i + 2) == '"') {
                            out.append("   ")
                            i += 3
                            break
                        }
                        out.append(if (s[i] == '\n') '\n' else ' ')
                        i++
                    }
                }
                // 普通字符串（未闭合时到行尾收手，不吞掉整个文件）
                c == '"' -> {
                    out.append(' ')
                    i++
                    while (i < s.length) {
                        val d = s[i]
                        when {
                            d == '\\' && i + 1 < s.length -> {
                                out.append(' ').append(' ')
                                i += 2
                            }
                            d == '$' && s.getOrNull(i + 1) == '{' -> {
                                out.append(' ').append(' ')
                                i += 2
                                i = appendStripped(s, i, out, stopAtCloseBrace = true)
                            }
                            d == '"' -> {
                                out.append(' ')
                                i++
                                break
                            }
                            d == '\n' -> break
                            else -> {
                                out.append(' ')
                                i++
                            }
                        }
                    }
                }
                // 字符字面量
                c == '\'' -> {
                    out.append(' ')
                    i++
                    while (i < s.length) {
                        val d = s[i]
                        when {
                            d == '\\' && i + 1 < s.length -> {
                                out.append(' ').append(' ')
                                i += 2
                            }
                            d == '\'' -> {
                                out.append(' ')
                                i++
                                break
                            }
                            d == '\n' -> break
                            else -> {
                                out.append(' ')
                                i++
                            }
                        }
                    }
                }
                stopAtCloseBrace && c == '{' -> {
                    out.append(' ')
                    braceDepth++
                    i++
                }
                stopAtCloseBrace && c == '}' -> {
                    out.append(' ')
                    braceDepth--
                    i++
                    if (braceDepth == 0) return i
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return i
    }
}