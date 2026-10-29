package com.nbalive.app.ui

import com.nbalive.app.data.model.Match
import com.nbalive.app.data.model.MatchStatus

/**
 * 比赛列表的派生过滤（review/audit-guards.md「未验证的怀疑 #5」）。
 *
 * ## 为什么要提出来：LIVE 过滤**只能有一处实现**
 *
 * 原来有两份逐字相同的实现：生产在根组合项里
 * （`listState.matches.filter { it.status == MatchStatus.LIVE }`），
 * 测试 `MainViewModelTest.liveIds` 又自己写了一遍。两者当时一致，
 * 但生产一旦加条件（例如排除延期场次），那两条用例**不会红** ——
 * 「测试与生产各写一份规则」正是本仓库反复踩的假安全感。
 *
 * 现在两边都调 [liveOnly]：规则只有一处，测试断言的就是生产真正用的那份结果。
 * `MatchFilterGuardTest` 同时钉住两件事：
 *  - `ui/` 里这个「过滤形状」只许出现在本文件；
 *  - [liveOnly] 必须有真实生产调用点（防止生产改回就地 `filter`）。
 */
internal fun liveOnly(matches: List<Match>): List<Match> =
    matches.filter { it.status == MatchStatus.LIVE }