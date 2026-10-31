package com.nbalive.app.ui

import com.nbalive.app.data.model.Provider

/**
 * 「我的」页的数据源开关判据（2026-10-31 用户需求）。
 *
 * ## 需求原文
 *
 * 「在我的页面增加一个控制 jrs 和 jrkan 的开关，比如我可以选择不解析某一个播放源，就可以手动关掉」。
 *
 * 关键词是**不解析**：关掉的源不能只是"解析完再从列表里过滤掉" ——
 * 那既浪费请求（源站有严格限流，403/429 已经在代码里专门处理），
 * 也不符合用户的表达。所以开关要一直传到 `MatchRepository.loadMatchesWithOutcome`，
 * 在**发起请求之前**就决定跳不跳这个源。
 *
 * ## 两条硬约束
 *
 * 1. **至少保留一个源**（[canDisable]）：两个都关 = 永远空列表，等于把 App 变成砖头。
 *    用户想"什么都不解析"的合理做法是退出 App，不是一个能点出来的死状态。
 * 2. **关掉的源不报"源暂时不可用"**：那是用户自己关的，不是故障。
 *    把它混进 `failedProviders` 会让用户以为源站挂了，下次又去开回来试。
 *
 * ## 为什么存的是"开着的集合"而不是"关掉的集合"
 *
 * 新增第三个源（[Provider] 加枚举值）时，"关掉的集合"里的旧数据意味着新源**默认开**，
 * "开着的集合"里的旧数据意味着新源**默认关** —— 后者是静默少一个源，
 * 前者是"用户没关它却在解析"。两者都不完美，但前者（默认开）符合"功能默认可用"。
 * 所以 [fromStorage] 遇到空集/未知值一律回落到 [ALL]。
 */
object SourceTogglePolicy {

    /** 全部源（默认状态）。 */
    val ALL: Set<Provider> = Provider.entries.toSet()

    /** 默认全开。 */
    fun default(): Set<Provider> = ALL

    /** 该源现在是否参与解析。 */
    fun isEnabled(enabled: Set<Provider>, provider: Provider): Boolean = provider in enabled

    /**
     * 能不能把这个源关掉。
     *
     * 已经关着 → 不用再关（返回 false，但开关仍可点回来，见 [toggle]）。
     * 只剩它一个开着 → 不能关（[LAST_ONE] 的理由）。
     */
    fun canDisable(enabled: Set<Provider>, provider: Provider): Boolean =
        provider in enabled && (enabled - provider).isNotEmpty()

    /**
     * 执行一次开关切换。
     *
     * ⚠️ **判据与执行必须是同一份实现**：`canDisable` 只负责界面把开关置灰，
     * 真正写状态走这里。分成两处写会让"置灰了但回调仍然改了状态"这类漏洞无从发现。
     */
    fun toggle(enabled: Set<Provider>, provider: Provider, on: Boolean): Set<Provider> = when {
        on -> enabled + provider
        canDisable(enabled, provider) -> enabled - provider
        else -> enabled
    }

    /** 落盘形态：枚举名集合（不用序号，枚举顺序变了不会串味）。 */
    fun toStorage(enabled: Set<Provider>): Set<String> = enabled.map { it.name }.toSet()

    /**
     * 从存储还原。
     *
     * 空集 / 全部读不出来 → [ALL]。理由见类 KDoc 的"为什么存的是开着的集合"：
     * 让用户对着一个永远空列表，比"多解析了一个源"糟得多。
     */
    fun fromStorage(names: Set<String>?): Set<Provider> {
        val parsed = names.orEmpty()
            .mapNotNull { name -> Provider.entries.firstOrNull { it.name == name } }
            .toSet()
        return parsed.ifEmpty { ALL }
    }

    /** 「我的」页开关下面那行摘要：一眼看出现在在解析哪些源。 */
    fun summary(enabled: Set<Provider>): String {
        val active = Provider.entries.filter { it in enabled }
        return when (active.size) {
            0 -> "未选择任何源"           // 理论上到不了（canDisable 挡住），留作防御
            1 -> "仅 ${active.single().displayName}"
            else -> active.joinToString(" / ") { it.displayName }
        }
    }

    /** 关最后一个源时给用户的一句解释（不是错误，是取舍说明）。 */
    fun lastOneHint(): String = "至少保留一个源，否则没有线路可解析"
}
