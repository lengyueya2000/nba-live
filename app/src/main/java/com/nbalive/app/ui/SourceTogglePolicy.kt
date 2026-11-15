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
     * 从存储还原（**含老用户升级迁移**）。
     *
     * 空集 / 全部读不出来 → [ALL]。理由见类 KDoc 的"为什么存的是开着的集合"：
     * 让用户对着一个永远空列表，比"多解析了一个源"糟得多。
     *
     * ⚠️ 但"合法的旧集合"是第三种情况，原来的推理漏了它：存储的是**开着的集合**，
     * 老用户磁盘上是 `{JRS, JRKAN}`，原样读回 = 新源**默认关** ——
     * 与本文件选"存开着的集合"时写下的取舍（"功能默认可用"）正好相反。
     * 后果不是小事：接入第三源后，**所有升级用户都看不见它**，
     * 而界面上显示"已关闭"，谁都分不清是自己关的还是没升级。
     * 迁移只做一次（[migrated] 来自磁盘标记），且**不许**顺带打开用户自己关掉的源。
     *
     * @param migrated 磁盘上是否已记录"新源迁移做过"。默认 **true**：
     *   既有调用点与测试一行都不用改，它们的语义本来就是"直接读回"。
     *   ⚠️ 迁移入口必须这么窄 —— 别处若也传 false，用户每次冷启动都会
     *   被重新打开一次他关掉的源。
     */
    fun fromStorage(names: Set<String>?, migrated: Boolean = true): Set<Provider> {
        val raw = names.orEmpty()
        val parsed = raw
            .mapNotNull { name -> Provider.entries.firstOrNull { it.name == name } }
            .toSet()
        if (parsed.isEmpty()) return ALL
        if (migrated) return parsed
        // 两道闸叠加，缺一不可：
        //  - [isLegacyShapedNames] 保证只动"旧时代形态"的数据。标记位与数据是两次写入，
        //    标记丢了而数据其实已是升级后写的（含 K88）时，没有这道闸就会替他补开 JRKAN；
        //  - [newSourcesAfterLegacy] 只补"旧时代之后新增、且数据里没有"的源，
        //    用户自己关掉的旧源不在其列。
        // 已知余量：标记写失败时，用户下次启动会被再补开一次新增源（他重新关一次就好）——
        // 比"升级后新源永远不出现"轻得多，所以这里不追求两次写入的原子性。
        if (!isLegacyShapedNames(raw)) return parsed
        return parsed + newSourcesAfterLegacy(parsed)
    }

    /**
     * 这份磁盘名字是不是"旧时代形态"（非空，且只含 [LEGACY_NAMES] 里的名字）。
     *
     * ⚠️ 判定用"子集"而不是 `size == 2`：加第四个源时，"三源里开两个"与
     * "旧时代两源都开"的 size 相同会撞车；而子集判定回答的正是它该回答的问题
     * ——"这份数据里有没有新源时代的痕迹"。
     * 空集返回 false：那是坏数据，走 [ALL] 回落，不许被迁移改出第四种状态。
     */
    fun isLegacyShapedNames(names: Set<String>): Boolean =
        names.isNotEmpty() && LEGACY_NAMES.containsAll(names)

    /**
     * 旧时代之后新增、且这份数据里没出现的源（迁移要补开的那些）。
     *
     * ⚠️ 判定只看**名字属于哪一代**，不看"有没有脏名字"：
     * `{JRS, BOGUS}` 解析后剩 `{JRS}`，仍属旧时代数据 → 该补开 K88；
     * 而 `{JRS, K88}` 里已有新源时代的痕迹 → 说明这份数据是升级之后写的，
     * 用户没开 JRKAN 是他的选择，一次都不该替他改（上面那道闸也会拦住它）。
     */
    private fun newSourcesAfterLegacy(enabled: Set<Provider>): Set<Provider> =
        Provider.entries.filter { it.name !in LEGACY_NAMES && it !in enabled }.toSet()

    /**
     * 接入第三源**之前**存在磁盘上的那批源。
     *
     * ⚠️ 写死这份**历史**名单，而不是 `Provider.entries - K88`：
     * 用减法的话，将来加第四源时这批人又会被判成"旧时代"、再被补开一次，
     * 把用户后来手动关掉的源悄悄打开 —— 那已经是篡改设置了。
     */
    private val LEGACY_NAMES: Set<String> = setOf(Provider.JRS.name, Provider.JRKAN.name)

    /**
     * 「我的」页开关下面那行摘要：一眼看出现在在解析哪些源。
     *
     * ⚠️ 全部源的名字**不许**在界面里另写一份字面量（"JRS / JRKAN 双源" 这种）：
     * 第三源接入时那份文案就成了假的，而它是 `InfoRow` 的静态文本，
     * 没有任何测试能发现"少写了一个源"。用 [allSummary] 派生。
     */
    fun summary(enabled: Set<Provider>): String {
        val active = Provider.entries.filter { it in enabled }
        return when (active.size) {
            0 -> "未选择任何源"           // 理论上到不了（canDisable 挡住），留作防御
            1 -> "仅 ${active.single().displayName}"
            else -> active.joinToString(" / ") { it.displayName }
        }
    }

    /** 全部源的摘要（详情页/我的页的"数据来源"一行用）。 */
    fun allSummary(): String = summary(ALL)

    /** 关最后一个源时给用户的一句解释（不是错误，是取舍说明）。 */
    fun lastOneHint(): String = "至少保留一个源，否则没有线路可解析"
}
