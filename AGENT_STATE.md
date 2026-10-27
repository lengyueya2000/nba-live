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

当前全量测试 **309 通过 / 0 失败 / 0 跳过**，`assembleDebug` 通过。

## 已验证落地（不必重做）
P0-1 判死分级、P0-2 m3u8 正则、01-P1-2/3/4/6、02-P1-1/3/4/5/6/7/8、
03-P1-1~4、04-P1-1/2/3/4、01-P2-2/10/12、02-P2-1

## 仍存在的主要缺口（按价值排序，来自 95 条矩阵）
1. **01-P2-4** 切网页线路时旧页在途 m3u8 可能被当新线路升级 →
   WebSurface 加 key(webFallbackUrl) 或 onStreamSniffed 校验 pageUrl（小/中）
2. **01-P2-3** 网页线路锁屏/切 App 后台仍可能出声（无 JS pause）（小）
3. **01-P2-5/6/7/8** 小窗比例沿用上一场、解析中点画中画得黑窗、进小窗方向复位、
   面板停留>10s 选完线全屏无底部栏（各小）
4. **02-P2-7/8/9** TTL 时间戳在请求前取、无 single-flight、redactUrl 不处理 path/userinfo（小）
5. **02-P2-12/13** normalizeKey 退化成 timeLabel、League.OTHER 死分支（小）
6. **02-P2-10** Match.isPostponed 算好了没人渲染（小）
7. **03 组 33 条 P2**（多数是 UI 打磨/重复代码/无障碍；其中 03-P2-1 非默认标签按返回
   直接退出 App、03-P2-5 缺 match 静默白屏，各小）
8. **04-P2-3/4/6/7/8/9** WebView file/content access、.gitignore 是 GBK、
   依赖声明不一致、AGP 内部类、AAB 任务名、废弃主题属性（各小）
9. **遗留**：`MatchRepository.loadMatches()/loadMatchesInternal` 是零生产调用的旧管线
   （生产走 loadMatchesOutcome），但 5 条限流/重试测试测的是旧管线 ——
   要么删旧管线并把测试迁到生产管线，要么在旧管线上加注释说明只服务测试

## 教训（务必遵守）
- **不要用 PowerShell 的 Set-Content/Replace 改 .kt 源码**：会把 UTF-8 中文写成乱码
  （本轮踩过两次，一次把 MainViewModel 写成 400 个替换字符）。
  只用 edit/write 工具改源码；改完可用
  `[System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($p))` 检查替换字符数
- 编辑前必须 read；`git checkout -- <file>` 可恢复
- 新测试必须验证「去掉修复会变红」，否则就是又一个假安全感
- 守卫测试用源码扫描时，注意别写成永真断言（如 `assertEquals(A.first(), A.first())`）
