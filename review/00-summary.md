# 全量代码审查汇总（v1.2.12）

审查范围：`app/src/main/java/` 全部 29 个文件 7386 行 + 构建配置 + 清单 + 资源。
审查方式：4 名审查员并行、只读，范围互不重叠。**未修改任何源码**（`git status` 仅 `?? review/`）。

| 分册 | 范围 | P0 | P1 | P2 | 合计 |
|---|---|---|---|---|---|
| [01-player.md](01-player.md) | 播放核心、手势、锁、音焦点、PIP | 1 | 6 | 12 | 19 |
| [02-data.md](02-data.md) | 仓库、解析器、网络、ViewModel | 1 | 9 | 13 | 23 |
| [03-ui.md](03-ui.md) | 应用骨架、各页面、组件、主题 | 0 | 4 | 35 | 39 |
| [04-security.md](04-security.md) | Activity、清单、Gradle、签名、R8 | 0 | 5 | 9 | 14 |
| **合计** | | **2** | **24** | **69** | **95** |

---

## 总体判断

代码质量**高于同类个人项目**。几个明确的优点：

- 关键逻辑被抽成纯函数并配单测（`PlayerControls`、`AudioFocusPolicy`、`PlayerLock`、`PlayerGestureMath`、`VideoResolution`、`PipState`）
- 并发与取消处理严谨（显式重抛 `CancellationException`、`isActive` + 路由双重守卫）
- 注释记录了**为什么**这么写，包含多次真实回归的来龙去脉（这是最难得的部分）
- 依赖锁定、R8 规则、签名凭据检查都有
- 安全基本面干净：无密钥泄漏（含历史）、无 SSL 忽略、无 JS 桥、权限最小化

问题集中在两类：

1. **启发式判据 + 不可逆处置**（P0-1、P1 若干）——用推断代替真实状态，且误判后代价很大
2. **静默失败**（P1 若干）——解析失败、单源失败、日志脱敏绕过，都不报错、不告警

---

## P0（2 条，建议优先修）

### P0-1 网页兜底「判死」是启发式推断，判死后却移除整个任务

**位置**：`PlayerScreen.kt:1669-1673` → `MainViewModel.kt:594-600` → `NbaLiveApp.kt:182-185` → `MainActivity.kt:237-245`

25 秒内没嗅探到含 `.m3u8` 的请求就判「播不出来」，然后 `finishAndRemoveTask()` 把整个任务从最近任务里移除。

三个问题叠在一起：
- 判据是**推断**不是播放器状态（MSE/`blob:` 播放、地址不含 `.m3u8` 字面量、弱网首帧 > 25s，都会误判）
- 处置**不可逆**（移除任务 ≠ 关小窗）
- `MainActivity.kt:230-232` 注释承诺「点开 App 能看到错误页」，但 Activity 已 finish，这承诺不可能成立

**建议**：25 秒只置「疑似异常」（停自动进小窗 + 显示提示），不触发 finish；真正的死只认 `onPlayerError` / 主框架 `onReceivedError`（且按 P1-1 过滤 errorCode）。处置改可逆。

### P0-2 `StreamUrlResolver` 的 m3u8 正则吞掉 `&id2=`

**位置**：`StreamUrlResolver.kt:105`

正则 `msss\.html\?id=([^"'\s<]+)(?=&id2=|["'\s<]|$)` 的字符类没排除 `&`，贪婪匹配把 `&id2=` 吞进捕获组，`(?=&id2=)` 前瞻成死分支。

**我已独立实测复现**（Kotlin 实跑，非推测）：

```
输入: msss.html?id=//hdl2.gnimixuiq.com/live/a.m3u8&id2=
捕获: //hdl2.gnimixuiq.com/live/a.m3u8&id2=      ← 应为 .../a.m3u8
```

端到端跑真实 `StreamUrlResolver`，三个候选全被污染：

```
[0] http://hdl2.qiuximing.com/live/a.m3u8?auth_key=abc&t=123&id2=
[1] http://hdl2.gnimixuiq.com/live/a.m3u8?auth_key=abc&t=123&id2=
[2] http://hdl2.gnimixuiq.com/live/a.m3u8?auth_key=abc%26t%3D123&id2=
```

下游 `MatchRepository.kt:632-641` 用「能否拉到 `#EXTM3U`」校验候选，污染地址大概率拉不到清单 → 该线路判死 → `/play/sm.html` 这条**唯一能静态还原**的链路失效 → 退化成 WebView 兜底。

**代码与注释不符**：`StreamUrlResolver.kt:101-104` 注释写着「再把尾部可能的 `&id2=` 剥掉」，但**代码里没有这一步**。

**为什么 9 条测试全绿**：`StreamUrlResolverTest.kt:72-83` 本意就是测这个，但断言用 `contains("auth_key=abc&t=123")` —— 带 bug 的结果 `...&t=123&id2=` 恰好满足。**测试写了但没测住**。

**建议**：`[^"'\s<&]+`，并补一条断言捕获结果不含 `id2` 的用例。

**严重度说明（我下调了原报告的判断）**：原报告称「正在发生的故障」。我核实后发现 `.forensics/` 里**没有** `msss.html` 的真实抓包，无法确认当前源站是否真带 `&id2=`。但代码注释和 `JrkanParserTest` 样本都写的是 `sm.html?id=439&id2=` 形态，**行为 bug 确定成立**，是否正在发生需实机抓一次 `/play/sm.html` 确认。

---

## P1 重点（24 条，按主题归并）

### A. 音频焦点（3 条，直接关系用户已反馈的问题）

| 编号 | 问题 |
|---|---|
| 01-P1-2 | `requestAudioFocus` 返回值被丢弃；被抖音 GAIN 抢走后不重申请也不放弃 → 已不在焦点栈 → **来电收不到 LOSS，直播声盖住通话** |
| 01-P1-3 | 来电判据只靠事件到达瞬间现查 `AudioManager.mode`，响铃瞬间常仍是 `MODE_NORMAL` → 判定「不是来电」→ 不停播 |
| 01-P1-2 续 | `pausedByFocus` 的「丢了再还回来」恢复逻辑在永久 LOSS 下永不触发 |

这是 v1.2.10 / v1.2.11 连续两次修过的区域，说明策略仍有盲区。建议：`LOSS_TRANSIENT` 直接暂停（抖音实测走 `GAIN`，TRANSIENT 基本只来自来电/语音助手），`LOSS` 再查 `mode`。

### B. 状态与数据（4 条，直接关系「比赛结束了还显示直播中」和「场次不全」）

| 编号 | 问题 |
|---|---|
| 02-P1-1 | **状态是一次性快照，进程内永不老化**。`correctLiveStatuses` 只在 `loadMatches` 里跑，用户不手动刷新就永不再校正 —— 这是「结束还显示直播中」的**结构性根因**（之前修的是「算错了」，这层是「不再算第二次」） |
| 02-P1-3 | 详情页校正有 **8 场硬上限**，超出部分靠 `isPastDayEnd` 只在整日过完后才判结束 → 当天 23:59 前仍显示「直播中」 |
| 02-P1-2 | 单源失败完全静默（只 `Log.w` 后当空列表），两源都空才报错 → 用户只看到「场次变少了」 |
| 02-P1-新 | 联赛名解析失败 → `League.OTHER` → 被 `supported` 过滤丢弃整场，**连日志都没有**（队名缺失反而有 `Log.w`） |

关于「场次不全」的诚实结论：**代码侧没找到「只取第一条/只取某区块」的漏场次**——JRKAN 119 块实测 119/119 全部解析成功。源站首页各只覆盖约 2 天，与你的判断一致。但上面两条静默丢场次路径会在改版/限流时放大观感。

### C. 解析器健壮性（3 条，源站改版即失效）

| 编号 | 问题 |
|---|---|
| 02-P1-7 | `JrkanParser` 用手写正则切 HTML，块起点写死 `<ul class="item play`、`data-lid` 严格三段数字 → 上游改属性顺序/加一段就**整表归零且静默** |
| 02-P1-8 | `JrsParser.parseScheduledTime` 正则要求 class **紧跟引号** → `<p class="match-info-time live">` 返回 null（我已验证：加 `live` 就失效） |
| 02-P1-6 | `JrkanParser` 把播放域名写死 `play.sportsteam356.com`，源站换域名后全部 JRKAN 线路**静默失效**（历史已发生两次） |

建议：JRKAN 的 `index.js` 剥掉 `document.write('...')` 就是合法 HTML，直接交给已在依赖里的 Jsoup，与 `JrsParser` 对齐。

### D. 日志脱敏被绕过（3 条，release 常开）

| 编号 | 问题 |
|---|---|
| 02-P1-5 / 04-P1-1 | `MainViewModel.kt:249/288/387/492`、`MatchRepository.kt:82/86` 直接把 `Throwable` 交给 `Log.w`。而 `HttpClient.kt:64-66` 明确注释「OkHttp 异常消息内嵌完整带 auth_key 的 URL，只打异常类型」—— 上层把这道防线拆了 |
| 04-P1-2 | `JrsParser.kt:59/69` 把源站原始 `href` 直接打进 `Log.w`，**完全没过 `redactUrl()`**（我已核实） |

`RedactUrlTest` 只测 `redactUrl()` 函数本身，**不覆盖「调用点是否真的用了它」**——工具函数有测试，使用纪律没有。

### E. 导航与 UI 行为（3 条）

| 编号 | 问题 |
|---|---|
| 03-P1-1 | **返回栈错位**：列表点比赛走 `playMatch` 直接 LIST→PLAYER，但 `back()` 里 PLAYER 无条件回 DETAIL → 用户按返回掉进**从未见过的详情页**（我已核实 `MainViewModel.kt:647-650` 与 `NbaLiveApp.kt:278`） |
| 01-P1-4 | 亮度只写窗口、离开播放页不复位 → 单 Activity 下返回列表后整个 App 停在调暗的亮度 |
| 01-P1-6 | 锁定态单击不消费事件 → 网页线路的「防误触」锁不住源站播放器控件 |

### F. 其他（8 条）

- **01-P1-5**：15 秒看门狗把弱网首帧 > 15s 的健康线路**永久拉黑**（会话内），用户看到「线路莫名被跳走且回不来」
- **01-P1-1**：`onReceivedError` 不区分 errorCode，`ERR_ABORTED`（重定向/连点重连）也立刻判死
- **02-P1-4**：`statusByClock` 忽略注入的 `now`，「可注入时钟」承诺是假的（我已核实 `:243` 内部重取时钟）
- **04-P1-3/4**：WebView 加载第三方 HTTP 页并执行其 JS，`shouldOverrideUrlLoading` 只拦非 http(s)，**http(s) 广告/钓鱼页全放行**。风险边界：**无 `addJavascriptInterface`**（已核实），恶意 JS 拿不到原生桥
- **04-P1-5**：`network_security_config.xml` 全局放行明文（当前产品形态下是硬需求），但 `jrszbt.com` 等已用 https 的域名未收紧
- **03-P1-2/3/4**：对比度不达标（hero 卡半透明白字 2.85~4.07:1、浅色主题漏设 `isAppearanceLightNavigationBars`、筛选胶囊 3.35:1）

---

## 测试的「假安全感」（值得单独说）

审查发现 4 处**测试存在但没测住**的情况，比没有测试更危险：

1. `StreamUrlResolverTest.kt:72-83` 断言用 `contains()`，带 bug 的结果恰好满足 → P0-2 长期潜伏
2. `PlayerLock.gesturesEnabled` / `tapShowsUnlockUi` 是**死函数**，但单测在测它们（真实判定散在 `PlayerScreen.kt:368-377`）
3. `PlayerControls.shouldShowBottomBar` 的 `streamUrl` / `webFallbackUrl` 参数**完全没参与判定**，而 `WebFallbackControlsTest` 精心构造了这两个参数却没守住任何东西
4. `RedactUrlTest` 只测纯函数，不测调用点

---

## 建议的修复顺序

| 优先级 | 项目 | 理由 |
|---|---|---|
| 1 | P0-1 判死逻辑改可逆 | 误判代价最高（移除任务），且判据不可靠 |
| 2 | P0-2 正则 + 补测试 | 一行改动，恢复唯一静态解析链路 |
| 3 | A 组音频焦点 | 用户已连续反馈两次，影响通话 |
| 4 | B 组状态老化 | 「结束还显示直播中」的结构性根因 |
| 5 | E 组返回栈 | 用户高频操作，体验明显 |
| 6 | D 组日志脱敏 | 改动小，安全收益明确 |
| 7 | C 组解析器改 Jsoup | 工作量大，但决定源站改版后的存活率 |

---

## 待你确认的事项

1. **是否要我按上表顺序开始修？** 还是先只修 P0 两条？
2. **P0-2 是否正在发生**需要实机抓一次 `/play/sm.html` 确认（明天有比赛时可做）。
3. **两处涉及产品决策**，需要你定：
   - 单源失败时是否给用户「部分场次」提示（P1-2）
   - 是否加「延期」状态（`MatchStatus` 目前无 `POSTPONED`，延期的比赛显示成「未开赛」）
4. **审查报告是否要入库？** 目前 `review/` 未跟踪。建议入库（是项目资产），也可以只留在本地。
