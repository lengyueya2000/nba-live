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
- `23ac56c` 03-P2-1/5：非默认标签按返回不再退出 App（BackPolicy 纯判据 + 有序动作，
  锁定态排在全屏之前）、详情页缺 match 不再白屏（MissingMatchPlaceholder）
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

当前全量测试 **347 通过 / 0 失败 / 0 跳过**，`assembleDebug` 通过，工作区干净。

## 已验证落地（不必重做）
P0-1 判死分级、P0-2 m3u8 正则、01-P1-2/3/4/6、02-P1-1/3/4/5/6/7/8、
03-P1-1~4、04-P1-1/2/3/4、01-P2-2/10/12、02-P2-1

## 仍存在的主要缺口（按价值排序，来自 95 条矩阵）
1. **01-P2-5/6/7/8** 小窗比例沿用上一场、解析中点画中画得黑窗、进小窗方向复位、
   面板停留>10s 选完线全屏无底部栏（各小）
2. **03 组剩余 P2**（约 30 条，多为 UI 打磨/重复代码/无障碍）：
   最值得做的几条 —— 03-P2-25 直播卡两个可点节点（TalkBack 停两次）、
   03-P2-23/24 无障碍（TextField 无 label、底栏未成组）、
   03-P2-7 Snackbar 后一条取消前一条、03-P2-13 每个 LIVE chip 一份无限动画
3. **04-P2-3/4/6/7/8/9** WebView allowFileAccess/allowContentAccess、
   .gitignore 是 GBK、依赖声明与 lockfile 不一致、AGP 内部类、AAB 任务名、
   废弃主题属性（各小）
4. 其余 P2 已在审查矩阵里逐条给出 file:line，可按需继续

## 教训（务必遵守）
- **不要用 PowerShell 的 Set-Content/Replace 改 .kt 源码**：会把 UTF-8 中文写成乱码
  （本轮踩过两次，一次把 MainViewModel 写成 400 个替换字符）。
  只用 edit/write 工具改源码；改完可用
  `[System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($p))` 检查替换字符数
- 编辑前必须 read；`git checkout -- <file>` 可恢复
- 新测试必须验证「去掉修复会变红」，否则就是又一个假安全感
- 守卫测试用源码扫描时，注意别写成永真断言（如 `assertEquals(A.first(), A.first())`）
