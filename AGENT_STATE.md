# 会话状态记录（用于上下文压缩后恢复）

## 工程
- 路径：G:\flutter\work\nba
- 类型：Flutter + Kotlin 混合（Android 侧原生 WebView/ExoPlayer 播放 NBA 直播源）
- 构建/测试：`.\gradlew.bat :app:testDebugUnitTest`（全量 296 通过 / 0 失败 / 0 跳过）
- 验收还要跑 `.\gradlew.bat :app:assembleDebug`

## 总任务
按 95 条审查清单（review/01~04 + P0/P1/P2 + D 组安全）逐条落地修复。
纪律：先 TDD 写纯判据 RED 测试 → 生产代码 → 编译 → 全量测试 → 提交。
**特别注意「假安全感」**：写了/测了但生产代码零调用的东西，
必须用源码扫描守卫测试钉住（这是审查反复点名的最大风险形态）。

## 本轮已完成并提交（最新在上）
- `5627336` 03-P2-13：呼吸红点改共享动画相位（LiveDotPhase + LocalLiveDotAlpha），
  原来每颗红点一份独立无限动画
- `9cadbd2` 03-P2-23/24：搜索框加可读 label（placeholder 不是无障碍标签）、
  底栏标签加 selectableGroup()
- `b5d24be` 04-P2-7/8/9：values-v35 主题覆盖（API 35 起系统栏颜色被忽略）、
  清单启用预测式返回、打包签名检查改按任务类型匹配（原来字面任务名对不上时
  静默不执行）；P2-7 只能部分修（AGP 无公开 outputFileName setter），
  但把 `as?` 静默失败改成显式报错
- `0d19b08` 04-P2-4/6：.gitignore 转严格 UTF-8、coroutines-test 版本对齐 1.9.0
- `16a484f` 01-P2-7/8：进小窗不再复位屏幕方向（onDispose 判 inPip）、
  面板停留过久后控制栏能回来（暂停计时 + 关闭时回补）
- `8faedf3` 01-P2-6/5：手动画中画加守卫（解析中/全失败时给提示而非黑窗）、
  切场复位小窗视频尺寸
- `23ac56c` 03-P2-1/5：非默认标签按返回不再退出 App（BackPolicy 纯判据 + 有序动作，
  锁定态排在全屏之前）、详情页缺 match 不再白屏（MissingMatchPlaceholder）
- `b22db5e` 03-P2-25：直播卡片不再让 TalkBack 停两次（mergeDescendants + onClickLabel）
- `75090ab` 02-P2-10：延期场次显示「延期」而不是「未开赛」（StatusLabel 纯判据 +
  5 个 StatusChip 调用点全传 isPostponed，守卫测试逐个检查）
- `e7f6bc8` **删除零生产调用的旧抓取管线**，把 5 条限流/重试纪律测试迁到
  生产入口 loadMatchesWithOutcome。这是本轮最有价值的一处：
  那些测试原来守的是线上跑不到的代码（真·假安全感）
- `b23cdc7` 02-P2-12/13：合并键不再退化成字面量「直播中」（两级兜底：
  timeLabel 的 MM-dd → 当天），League.OTHER 死分支加注释
- `311ccec` 02-P2-7/8/9：详情页 single-flight 去重、缓存时间戳移到请求之后、
  redactUrl 补 userinfo 脱敏与超长 path 截断
- `54d6ead` 01-P2-3 网页线路锁屏/切后台不再外放（WebMediaPauseScript 注入 JS）
- `11a8da4` 01-P2-4 切网页线路不再串流（key(webFallbackUrl) + Referer 归属校验）
- `2784851` 时区纪律守卫：解析/判定层出现 systemDefault() 即红（已验证会红）
- `8371c40` 02-P2-2 + 02-P2-11：源站时间统一按北京时间解析
  （JrkanParser.parseDateTime/parseStatus、JrsParser 合成、normalizeKey）；
  顺带用 MonthDay.atYear 修平年 2/29 抛异常
- `a8633ec` 01-P2-1 中途卡死检测：StalledPlayback（Tracker 状态机，8 条单测），
  30 秒无进展自动换线；与首帧看门狗不重叠
- `25dfde9` 04-P1-5 精确收紧明文流量：jrszbt.com 与 OSS 加速域加
  domain-config cleartextTrafficPermitted=false；**base-config 明文许可必须保留**
  （抓包实测播放页本身就是 http，JRS 页面 28 处 http 引用）。
  CleartextPolicyTest 双向钉边界（该禁的禁、该放的放）
- `cfa74a6` 02-P2-3/4/5：列表加载 withTimeoutOrNull(60s) 整体 deadline（超时不抛异常、
  如实报两源失败）；refresh 竞态按 Job 身份收尾；PLAYER 返回补 detailJob.cancel()。
  RefreshRaceTest 复现真实竞态顺序（旧任务收尾慢 50ms），已验证去掉守卫会变红
- `cc294fb` 01-P2-11 网页线路不再渲染无效的「画面」胶囊（接线 shouldShowAspectChip）；
  01-P1-5 首帧看门狗 15s→30s + failedSources 换成带 TTL 的 SourceFailureBook
  （5 分钟自动解封）。新增 DeadCodeWiringGuardTest（已验证回退会红）
- `520c1f8` 02-P1-2 partialError 真正渲染到列表（ListNoticePolicy 纯判据 + PartialBanner）
- `ac3dd18` 02-P1-9 收尾：PLAY_HOSTS 接到 expandJrkanPlayPage、parseChannels(playHost)、
  jrsDetailUrls 候选、详情页缓存 key 改 detailId（02-P2-6）
- `cc0ed66` 02-P1-9 前半：SourceEndpoints 候选列表 + 403/429 立即停源 + 镜像只试一次

当前全量测试 **390 通过 / 0 失败 / 0 跳过**，`assembleDebug` 通过，工作区干净。
本轮（`fdc9f73` 之后）共 30 个提交。

## 已验证落地（不必重做）
P0-1 判死分级、P0-2 m3u8 正则、01-P1-2/3/4/6、01-P2-1/2/3/4/5/6/7/8/10/11/12、
02-P1-1/2/3/4/5/6/7/8/9、02-P2-1/2/3/4/5/6/7/8/9/10/11/12/13、
03-P1-1~4、03-P2-1/5/13/23/24/25、04-P1-1/2/3/4/5、04-P2-3/4/6/7(部分)/8/9

## 仍存在的主要缺口（按价值排序，来自 95 条矩阵）
1. **03 组剩余 P2**（约 26 条，多为 UI 打磨/重复代码/性能）：
   最值得做的 —— 03-P2-19/20 重复组件（InfoRow ×2、错误态 ×5）、
   03-P2-15 根节点 collect 7 个 flow（任一变化推动整树重组）、
   03-P2-18 上帝组件 681 行、03-P2-11 remember 里的局部 lambda、
   03-P2-12 条件性 remember、03-P2-14 详情页全量组合线路列表
2. **04-P2-5** release mapping 归档（仓库外流程，需要 CI 或发布脚本配合）
3. **04-P2-7 剩余部分**：彻底去掉 AGP 内部类依赖需要升级 AGP
   （当前已把 `as?` 静默失败改成显式报错，风险已降到"会当场报错"）
4. 其余 P2 已在审查矩阵里逐条给出 file:line，可按需继续

## 教训（务必遵守）
- **源码一律只用 edit/write 工具改**。PowerShell 的 Set-Content / -replace
  会把 UTF-8 中文写坏（本轮踩了三次：MainViewModel 400 个替换字符、
  LiveDotAnimationTest 全文乱码、.gitignore 被 checkout 还原成 GBK）。
  改完可用 `[System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($p))` 检查
- 编辑前必须 read；`git checkout -- <file>` 可恢复，但会丢掉**未提交**的修复
- 新测试必须验证「去掉修复会变红」，否则就是又一个假安全感
- **验证守卫时 Gradle 可能 UP-TO-DATE 跳过执行**，必须加 `--rerun-tasks` 才是真跑
- 守卫测试用源码扫描时：
  - 断言前**必须剔除注释**（注释里常写着"为什么这么改"，含同样的字面量）
  - 必须匹配**调用**而不是字符串出现（如 `.selectableGroup()` 而非 `selectableGroup`，
    否则 import 行会让守卫永远绿）
  - 别写成永真断言（如 `assertEquals(A.first(), A.first())`）
  - 别用 `contains("x = x")` 这种宽松匹配（`x = false` 也能过）
  - 路径解析失败时**不要用 assumeTrue**（会静默跳过变全绿），直接 error()
  - 提取函数体时 marker 要带左括号（`fun LiveDot(` 而非 `fun LiveDot`，
    否则会命中 `fun LiveDotPhase(`）
