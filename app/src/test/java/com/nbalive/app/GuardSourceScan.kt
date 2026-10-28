package com.nbalive.app

/**
 * 源码扫描类守卫的**共享**词法工具。
 *
 * ## 为什么需要它（而不是每个测试各写一份 `stripComments`）
 *
 * 只读审计 `review/audit-guards.md` 的「未验证的怀疑 #6」记录了本仓库存在**两套**
 * 剔注释实现：宽松版只把"整行注释"置空，**行尾 `// …` 与行内 `/* … */` 原样保留**。
 * 后果是与本仓库最核心的缺陷同形：把真正的调用删掉，只在同一行留一句
 * `// 用 ManualPipPolicy.decide(...)`，断言照样绿 —— 又一个"永远不会红的守卫"。
 *
 * 这里给出一份**词法级**的实现：注释（行注释、可嵌套块注释）与字符串字面量
 * （普通字符串、原样字符串、字符字面量）一律替换成空格。
 *
 *  - 替换**保留换行**，所以调用方的行号（`file:line`）依然真实；
 *  - 字符串模板 `${…}` 里的内容是**代码**，其中嵌套的字符串继续按同样规则处理，
 *    所以写在模板里的调用照样能被抓到；
 *  - 剔除的字符换成空格而不是删掉，避免把两个 token 粘成一个。
 *
 * 本文件本身也被 `GuardHygieneTest` 的元守卫扫描，所以它必须自己也不含
 * `assume` 家族的代码引用。
 */
internal object GuardSourceScan {

    /** 注释与字符串字面量 → 空格（保留换行）。 */
    fun stripNonCode(text: String): String =
        StringBuilder(text.length)
            .also { appendStripped(text, 0, it, stopAtCloseBrace = false) }
            .toString()

    /** 从 [start] 开始按上述规则把结果追加进 [out]，返回下一个未消费的下标。 */
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