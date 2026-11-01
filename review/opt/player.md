# 播放链路复查（新优化点）：PlayerScreen / 手势 / PiP / 音焦点 / WebView

- 审查人 `rev-player`（只读；本文件是唯一写入物；未改任何生产代码/测试/构建脚本，未跑 Gradle）。范围：
  `PlayerScreen.kt`、`PlayerGestureLayer.kt`、`PlayerPageResolver.kt`、`VideoResolution.kt`、`ui/` 下 19 个
  策略/脚本文件、`MainActivity.kt`（播放/PiP 段）；跨文件只引用 `MainViewModel.kt`/`NbaLiveApp.kt`/
  `AppEffects.kt` 的**调用点**（这三份由其他审查员负责）。
- **行号口径**：一律用 `read`/`grep` 工具给的行号（`Get-Content` 把 UTF-8 中含 `0x85` 的字节当换行，
  行数少算约 30%）；实测 `PlayerScreen.kt` **2485** 行、`PlayerGestureLayer.kt` **582** 行。
- 已读 `review/coverage-ledger.md`（95 条：87 已落地 / 8 有据取舍）与 `review/01-player.md`。
  **不重复那 95 条**；涉及旧条目处标为「回归风险」并给新的可复核证据。

## Top 5 高价值

| # | 标题 | 等级 | 成本 | 一句话收益 |
|---|---|---|---|---|
| 1 | 死胡同恢复条的「重新加载」在 paps 直载路径下 `reload()` 的是同一个旧 `encodedStr` | 高 | S | 修掉本轮修复自己没兑现的承诺（拿新签名） |
| 2 | 死胡同判据的 `hasStream` 被写死 `false`，policy 的第一道否决在网页播放中永不生效 | 高 | S | 让"有流就绝不打扰"真正生效，避免踢掉正在播的画面 |
| 3 | 音频模式轮询只暂停、从不恢复（且 `shouldRequestFocusAgain` 被自己挡住） | 中 | S | 通话结束后不会永久停在暂停态 |
| 4 | `sawMediaRequest` 跨导航不清零 → 「重新加载 / 退回原页」后嗅探看门狗永久失效 | 中 | S | 重建页面后仍能报「疑似无信号」 |
| 5 | 看门狗计时基准不随导航重置 + 30s 硬上限 vs 实测加载 0.1~27s | 中 | S | 少一次"把能播的线踢掉"的误判 |

---

## 发现（按价值降序）
### 1. 死胡同恢复条的「重新加载」在 paps 直载路径下拿不到新签名 —— 高 / S
- **结论**：台账第十节 10.3 加的「重新加载」按钮做的是 `webView.reload()`，而当前页面在
  `PlayerPageResolver` 成功时是 `paps.html?id=<旧 encodedStr>`，reload 只会用**同一个** encodedStr
  重发一次请求；注释里"壳页会给出新的 encodedStr"这条路只在**没走 paps 捷径**时才成立。
- **位置**：`app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:2427`（相关：`:2008` `targetUrl`、
  `:2019` `targetUrl = direct`、`:2294/2302` `loadUrl(tag)`、`:2010-2020` 解析器接线）
- **原文**：
```kotlin
                        onClick = {
                            pageDeadEnd = false
                            pageErrorAt = 0L
                            // 重新加载原播放页：壳页会给出**新的** encodedStr，
                            // 这是唯一能拿到新签名地址的办法。
                            webView?.reload()
```
- **影响**：网页线路报「网络错误」的**主因**就是地址签名过期/删档。此时点「重新加载」→ 重新加载
  `paps.html`（旧 id）→ 解出来的还是那条死地址 → 1.5s 后同一块错误面板回来、9.5s 后自动换线。
  用户观感与本轮要修的 bug（"点刷新没反应"）**一模一样**，只是换了个按钮名；若解析器走的是回退路径
  （`targetUrl == url`）按钮才正确 —— 两条路径行为不一致，且默认路径是坏的那条。
- **价值/成本**：**高**（用户可见故障 + 修复成本 ≤S）/ **S**（把 `webView?.reload()` 换成回到壳页：
  `targetUrl = url` 并让 `update` 的 `view.tag != targetUrl` 触发重新 `loadUrl(url)`；或直接 `webView?.loadUrl(url)`）。
- **判定**：**已证实（代码事实）**：`reload()` 重载当前 URL、而当前 URL 在捷径路径下就是 paps。
  「paps 用同一个 encodedStr 解不出新签名」**需真机验证**：点「重新加载」后看 chromium 请求，
  若仍是 `paps.html?id=<同一个>` 且随后 m3u8 请求的 `expire` 未变，即坐实。
### 2. 死胡同判据的 `hasStream` 写死 `false`，policy 的第一道否决永不生效 —— 高 / S
- **结论**：`WebPageDeadEndPolicy.shouldOffer/shouldAutoAdvance` 的头号条件是 `!hasStream`（"有流就绝不打扰"），
  但生产调用点把它写成常量 `false`，等于这条安全阀**在生产代码里从没武装过**。
- **位置**：`PlayerScreen.kt:2109`（写死）+ `:2111-2126`（消费）；判据 `ui/WebPageDeadEndPolicy.kt:74/90`；
  iframe 遍历 `ui/WebErrorProbeScript.kt:68-73`
- **原文**：
```kotlin
            val elapsed = android.os.SystemClock.elapsedRealtime() - pageErrorAt
            val hasStream = false // 有流时本组件已被 VideoSurface 替换、协程自动取消
            val detected = pageErrorAt > 0L
            if (WebPageDeadEndPolicy.shouldOffer(
```
```kotlin
            var frames = document.querySelectorAll('iframe');
            for (var i = 0; i < frames.length; i++) {
              try {
                if (bad(frames[i].contentDocument)) return '1';
```
- **影响**：注释的前提只对**已嗅探升级**的流成立（那时 `streamUrl != null`、WebSurface 被替换）。
  网页线路靠 MSE/只发分片正常播放时 WebSurface **一直挂着**、`hasStream` 永远 false；而
  `WebErrorProbeScript` 会遍历**所有同源 iframe**，任意一个 xgplayer 实例（源站的广告位/备用播放器）
  报错就返回 `'1'` → 1.5s 后压恢复条 → 9.5s 后 `onPageDeadEnd` → `PlaybackAutoAdvance`
  **把正在播的画面换掉并拉黑这条线**（`MainViewModel.kt:1308`）。这正是 P0-1 批评过的
  "推断拿走不可逆处置"，只是判据来自 DOM 而非嗅探。
- **价值/成本**：**高**（误判 + 换线拉黑，不可逆）/ **S**（`hasStream = sawMediaRequest.get()`，
  即"页面确实在拉媒体"；或把探测限制在主文档/主播放器容器内）。
- **判定**：**已证实**：`hasStream = false` 是代码事实、iframe 遍历是代码事实、"换线会拉黑"在
  `MainViewModel.kt:1308` 可读。**推测（需真机）**：源站页面里是否真存在"会报错的同源 xgplayer iframe"
  —— 验证方式：`chrome://inspect` 看 `paps.html` 里的 iframe 数与 class，或在探测量到 `1` 时
  用 `evaluateJavascript` 打一份命中节点的 outerHTML 到 logcat。
### 3. 音频模式轮询只暂停、从不恢复 —— 中 / S
- **结论**：`poll` 在"没在焦点栈里 + 现查到通话模式"时 `pause()`，但通话结束后没有任何对称的 `play()`；
  而唯一会补申请焦点的 `ON_START` 分支要求 `playing == true` —— 被自己刚暂停的状态挡死。
- **位置**：`PlayerScreen.kt:1696-1709`（轮询体）、`:1712-1726`（ON_START 补申请）；
  判据 `ui/AudioFocusPolicy.kt:127-128`
- **原文**：
```kotlin
            if (player != null &&
                AudioFocusPolicy.shouldPauseForAudioMode(
                    playing = player.playWhenReady,
                    mode = am?.mode ?: android.media.AudioManager.MODE_NORMAL,
                )
            ) {
```
- **影响**：这条轮询存在的**唯一理由**就是"我们不在焦点栈里、收不到事件"（见 `PlayerScreen.kt:1636-1640`）。
  被抖音用永久 GAIN 抢走焦点后再来电：轮询暂停 → 通话结束 → 系统不会给我们任何事件（不在栈里）→
  `pausedByFocus=true` 但永远等不到 GAIN → **直播停在暂停态**，用户得自己点播放。
  另外 `AudioFocusPolicy.kt:148` 写着"只在播放中轮询，暂停态不做任何事"，
  而 `handler.postDelayed(poll, …)` 是**无条件**自我重投，暂停态/后台照样 1Hz 醒着 —— 注释与代码不符。
- **价值/成本**：**中**（影响少数场景，但一旦发生就是用户可见故障；修法 S 且能复用已有判据）
  / **S**（轮询里补：现查到非通话模式且 `pausedByFocus && !playing` → `acquire()` + `player.play()`，
  即复用 `shouldResumeOnFocusGain`）。
- **判定**：**已证实**（代码里只有 pause 分支，没有对应的 resume 分支；无条件重投也是代码事实）。
  "通话结束后是否真的收不到 GAIN"取决于厂商 `MediaFocusControl` 实现 —— 需真机：
  小窗看球 → 打开抖音 → 来电挂断 → 看直播是否仍在暂停。
### 4. `sawMediaRequest` 跨导航不清零 → 重新加载后看门狗永久失效 —— 中 / S
- **结论**：`sawMediaRequest` 按 `url` remember，**一次为 true 就永久为 true**；而「重新加载」
  会重载页面、`isMediaRequest` 的条件仍读这个陈旧值，于是这条 WebSurface 实例再也不会报「疑似无信号」。
- **位置**：`PlayerScreen.kt:1986`（定义）、`:2218`（置位）、`:2055-2060`（读）、`:2026/2427`（重新加载的两条路径）
- **原文**：
```kotlin
    val sawMediaRequest = remember(url) { java.util.concurrent.atomic.AtomicBoolean(false) }
```
```kotlin
                            if (PlaybackHealth.isMediaRequest(u)) sawMediaRequest.set(true)
```
- **影响**：用户点「重新加载」后页面卡住（不 finish、源站播放器又不画错误面板）时，三条感知通道全断：
  看门狗 `sawMediaRequest=true → shouldReport=false`、`onReceivedError` 只在主框架致命码触发、
  死胡同探测依赖 DOM class。界面只剩黑屏 + 没有提示 —— 与旧审查点名的"永久黑屏"同类。
  `:2026` 的 paps→原页回退同理（回退前若已见过媒体请求，回退后的新页面同样被豁免）。
- **价值/成本**：**中**（静默失败，用户无法自救）/ **S**（在 `reload`/`targetUrl` 变化处
  `sawMediaRequest.set(false)`；或把它改成"本次导航内是否见过媒体请求"，随 `targetUrl` 一起 remember）。
- **判定**：**已证实**（代码事实：`AtomicBoolean` 只在 `set(true)`，全仓无 `set(false)`）。
### 5. 看门狗计时基准不随导航重置 + 30s 硬上限与实测加载时长重叠 —— 中 / S
- **结论**：`loadStartedAt` 与 `pageFinishedAt` 都按 `url` remember、不随导航重置；
  而 `HARD_DEADLINE_MS=30s` 从**最初**一次 `loadUrl` 起算。回退/重载之后按旧基准算时间。
- **位置**：`PlayerScreen.kt:1994`、`:2266-2268`；判据 `ui/WebSniffWatchdog.kt:95-97`；回退点 `:2023-2027`
- **原文**：`:1994` 的 `val loadStartedAt = remember(url) { android.os.SystemClock.elapsedRealtime() }`，
  以及 `onPageFinished` 里：
```kotlin
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            pageFinishedAt = android.os.SystemClock.elapsedRealtime()
```
- **影响**：两条后果。① 退回原播放页后，"已 finish 15 秒"用的是 **paps 页**的 finish 时刻，
  于是刚加载的原页只等 ~9 秒就被判疑似（注释承诺的是"finish 之后 15 秒"）；
  ② 台账自己记的实测是"播放页导航 **0.1s~27s** + 页面脚本 0.8~5.3s 才发流"，
  与 30s 硬上限**重叠** —— 慢但健康的线路会被判疑似，而 `onPlaybackSuspect`
  会走 `advanceAfterNoSignal`（`MainViewModel.kt:1254`）**真的换线**。
- **价值/成本**：**中**（换线 = 用户看得见的跳变，且被换掉的线会进失败记账）/ **S**
  （`pageFinishedAt`/`loadStartedAt` 改为随 `targetUrl`（每次导航）重置；或把硬上限提到 45s
  并说明"多黑 15 秒"的取舍）。
- **判定**：**已证实**（基准语义与代码位置一致）；**推测**（真实误判率需弱网真机：4G 上限速下
  观测 `WebSniffWatchdog` 判定时刻与页面真出流时刻之差）。注意这是对第九回合"25→15 / 45→30"
  的**回归风险**举证，不是翻案：新证据是 `advanceAfterNoSignal` 这条"疑似即换线"的消费链与实测 27s 重叠。
### 6. 进全屏"收起浮层"被面板回补 effect 抵消 —— 中（推测） / S
- **结论**：两个 `LaunchedEffect` 都以 `fullscreen` 为 key、写同一个 `controlsVisible`：一个写 `false`，
  另一个在"无面板 + 全屏"时写 `true`；按组合顺序后者更晚执行，净结果是把刚收起的浮层又打开。
- **位置**：`PlayerScreen.kt:306-308` 与 `:317-325`
- **原文**：
```kotlin
    LaunchedEffect(fullscreen) {
        if (fullscreen) controlsVisible = false
    }
```
```kotlin
    LaunchedEffect(picker, showLivePanel, fullscreen) {
        if (SystemBarsPolicy.shouldRestoreControlsOnPanelClose(
```
- **影响**：`shouldRestoreControlsOnPanelClose` 只排除"面板**开着**"，不区分"这次 `fullscreen` 变化
  是不是进全屏"，于是进全屏时它照样命中（`:324 controlsVisible = true`）。
  观感：进入全屏后顶部时钟/右上按钮/底部控制栏**不会**立即隐藏，而是等 10 秒自动收起 ——
  与 `:299-305` 注释和 v1.2.x 的"全屏默认干净"不一致。更一般的隐患是这条规则依赖
  Compose 执行两个 effect 的先后（`AppEffects.kt:86-92` 刚好把这种依赖点名批评过）。
- **价值/成本**：**中**（边角观感回归，修法 ≤S）/ **S**（回补判据加一个"面板是否**刚刚关闭**"的输入，
  或把"进全屏收起"合并到同一个 effect 里显式排序）。
- **判定**：**推测**（`LaunchedEffect` 在同一次 apply 里按声明顺序启动，静态推断净值为 true；
  JVM 单测跑不到）。验证：非全屏播放页（无面板）点全屏，看底部控制栏是立刻消失还是可见 10 秒。
### 7. 250ms 提示 ticker 的状态在 PlayerScreen 顶层读取 + 派生列表未 remember —— 中 / S
- **结论**：加载提示的 `sinceLoadMs` 每 250ms 写一次，读它的 `if` 就在 `PlayerScreen` 函数体内，
  于是整个播放页函数体（含 `others` 的 `filter/take` 与 LazyRow）被反复重组。
- **位置**：`PlayerScreen.kt:444-459`（ticker + 读取）、`:978-980`（派生列表）、`:1208`（面板内再 filter 一次）
- **原文**：
```kotlin
                LaunchedEffect(page) {
                    val startedAt = android.os.SystemClock.elapsedRealtime()
                    while (sinceLoadMs < PlayerLoadingHintPolicy.MAX_HINT_MS) {
                        sinceLoadMs = android.os.SystemClock.elapsedRealtime() - startedAt
                        kotlinx.coroutines.delay(250)
                    }
                }
```
- **影响**：网页线路加载期间（最长 20s）**4 次/秒**重组整个 `PlayerScreen` 体 —— 而这段时间正是
  源站页面最吃 CPU/网络的时候（chromium 解码 + 播放器 JS），主线程重组与 WebView 抢帧。
  另外 `others`/`providers` 每次重组重新分配列表（`:978`、`:1107`），`videoHandle.isPlaying/isBuffering`
  也在同一作用域被读（`:650/708`），播放状态每翻转一次同样整页重组。
- **价值/成本**：**中**（可证实的重组面；修法 S，收益需 `gfxinfo` 量化）/ **S**
  （ticker 与 `shouldShow` 判定搬进独立小 composable，或改用 `derivedStateOf` + 只在真显示时读；
  `others`/`providers` 用 `remember(...)`）。
- **判定**：**已证实**（写状态与读状态在同一 restart 作用域是代码事实；250ms 周期性写入也是代码事实）。
  帧时间影响属**推测**，测量法：`adb shell dumpsys gfxinfo <pkg> framestats` 对比"网页加载中"与"已出流"两段。
### 8. `PlayerScreen` 单函数 ~990 行、20 个 `LaunchedEffect`，且没有结构守卫 —— 中 / M
- **结论**：`:227`—`:1140` 是一个函数体（全屏/网页/面板/提示/选择器全在里面），
  而 `AppStructureGuardTest` 只约束了根组合项（≤180 行），播放页这条最大的函数没有对应守卫。
- **位置**：`PlayerScreen.kt:227`（起）、`:1012/1139-1140`（收尾）；对照组 `ui/NbaLiveApp.kt` 已被拆成 5 个文件
- **原文**：
```kotlin
    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
```
- **影响**：任何一次改动都要在一个 990 行的函数里重新定位副作用；`:286/:306/:310/:317` 四个
  effect 互相写 `controlsVisible`（第 6 条就是这么来的），说明这块的状态机已经超出人眼可审范围。
- **价值/成本**：**中**（可维护性 & 后续回归的主要来源）/ **M**（按"视频区 / 浮层 / 面板 / 侧栏"抽
  private composable，并把 effect 归位；可顺带给播放页加上同款行数守卫）。
- **判定**：**已证实**（行数与调用点可复核）。
### 9. ExoPlayer 每次换线路整体重建（含 DataSourceFactory/解码器），可用 ResolvingDataSource 复用 —— 中 / L
- **结论**：`key(streamUrl, streamReferer)` 让每条线路都新建 ExoPlayer + PlayerView；
  Referer 之所以要烧进工厂，只是因为 `setDefaultRequestProperties` 在 build 期固定。
- **位置**：`PlayerScreen.kt:346`（key）、`:1838-1844`（工厂与 `ExoPlayer.Builder`）、`:1595-1596`（注释说明原因）
- **原文**：
```kotlin
                streamUrl != null -> key(streamUrl, streamReferer) {
```
```kotlin
            val dataSourceFactory = OkHttpDataSource.Factory(HttpClient.shared.client)
                .setDefaultRequestProperties(headers)
                .setUserAgent(HttpClient.USER_AGENT)
```
- **影响**：换线时重建播放器 = 重新初始化解码器/渲染器/缓冲，换线延迟与黑帧都比"换 MediaItem"长；
  这也是本仓库"解析播放慢"抱怨的一部分（第 8/9 回合的背景）。改法是让 Referer 每请求可变
  （自定义 `DataSource.Factory` 读当前 referer，或用 `ResolvingDataSource`），实例不重建。
- **价值/成本**：**中**（明确收益，但风险集中在这条最贵的链路上）/ **L**
  （要重新设计 referer 传递并补"换线后 CDN 不吃旧 Referer"的回归守卫）。
- **判定**：**已证实**（重建是代码事实）；"换线延迟由重建主导"属**推测**，需真机对比
  "换同一直连线路"与"重建播放器"的 `ExoPlayerImpl Init` 到首帧耗时。
### 10. WebSurface 内 3 个 `while(true)` + 6 个 effect 的嗅探状态机不可测 —— 中 / M
- **结论**：嗅探/看门狗/死胡同三条循环都直接写在 composable 里，判据虽已抽成纯函数，
  但"何时重投、何时复位、谁先谁后"这层只能靠真机（第 4/5/6 条都出在这层）。
- **位置**：`PlayerScreen.kt:1944-2441`（WebSurface 全体）、`:2053-2071`、`:2085-2102`、`:2105-2131`
- **原文**：
```kotlin
    LaunchedEffect(url, pageFinishedAt, suppressHint) {
        while (true) {
            val reported = WebSniffWatchdog.shouldReport(
```
- **影响**：与 `StalledPlayback.Tracker`（抽出来后可 JVM 单测）对照，这一层可测性明显更低：
  `sawMediaRequest` 的生命周期、`pageErrorAt` 的重置、三条循环的互斥关系，目前没有任何 JVM 判据。
- **价值/成本**：**中** / **M**（抽 `remember(url){ WebSniffState() }` 持有
  `sawMediaRequest/pageFinishedAt/pageErrorAt/loadStartedAt` 与"导航代次"，effect 只做采样与执行）。
- **判定**：**已证实**（结构事实）。
### 11. `update` 里的 `setMediaItem` 分支是死代码，异常时会退化成"每次重组都 prepare" —— 低 / S
- **结论**：组件被 `key(streamUrl, streamReferer)` 包住，同一 view 实例上 `streamUrl` 永不改变，
  这个分支正常情况下不可达；但它又是"URI 字符串往返不相等"时唯一的护栏。
- **位置**：`PlayerScreen.kt:1915-1925`
- **原文**：
```kotlin
        update = { view ->
            view.resizeMode = aspectMode.resizeMode
            val player = view.player
            if (player != null) {
                val current = player.currentMediaItem?.localConfiguration?.uri?.toString()
                if (current != streamUrl) {
```
- **影响**：死代码本身无害，但它制造"播放器会被复用/热切换"的错觉（实际每次都重建，见第 9 条）；
  而若某个 m3u8 地址的 `Uri.toString()` 与原串不一致（百分号编码/转义），这个分支会在**每次重组**
  （缓冲状态每秒翻转数次）都 `setMediaItem + prepare + playWhenReady`，表现为画面不断重缓冲。
- **价值/成本**：**低** / **S**（删掉该分支，或改为与"本次 factory 用的 URL"这个 remember 值比较）。
- **判定**：**已证实**（`key` 导致实例不跨 URL 复用）；"URI 不往返"属**推测** —— 验证：在该分支打一行
  `debugLog("VideoSurface", "remount $current")`，真机播放 10 分钟看是否为 0。
### 12. 死胡同探测 1Hz `evaluateJavascript` 无退避、无上限 —— 低 / S
- **结论**：整个网页播放期间每秒注入一次探测脚本，且脚本自身要遍历全部同源 iframe 并取
  `getComputedStyle`。
- **位置**：`PlayerScreen.kt:2085-2102`；间隔常量 `ui/WebPageDeadEndPolicy.kt:51`；脚本 `ui/WebErrorProbeScript.kt:53-79`
- **原文**：
```kotlin
    LaunchedEffect(url, targetUrl) {
        while (true) {
            val view = webView
            if (view != null) {
                view.evaluateJavascript(WebErrorProbeScript.HAS_ERROR) { raw ->
```
- **影响**：主线程每秒一次 JSBridge 往返 + 页面内 DOM 查询/样式计算；与嗅探看门狗（同 1Hz）叠加成
  2Hz。播放中的网页线路本来就吃 CPU，这是持续到"换线/退出播放页"的固定开销。
- **价值/成本**：**低**（无测量支撑的性能项，按口径封顶低）/ **S**
  （首轮密集、之后退避到 3~5s；或 `sawMediaRequest` 为真且连续 N 次探测为 `'0'` 后降频）。
- **判定**：**推测**（开销需 `adb shell dumpsys gfxinfo`/Systrace 或 observatory 量化；
  代码事实是"无条件 1Hz、无退避"）。
### 13. 每次进网页线路都多一次壳页 OkHttp 请求 —— 低 / S
- **结论**：`LaunchedEffect(url)` 先用 OkHttp 拉一次壳页（2s 超时）算 paps 地址，
  而 WebView 同时在加载同一个壳页；解析失败或超时这 2 秒纯属额外等待。
- **位置**：`PlayerScreen.kt:2010-2020`、`:2023-2027`
- **原文**：
```kotlin
        val html = kotlinx.coroutines.withTimeoutOrNull(2_000L) {
            runCatching {
                HttpClient.shared.get(url, HttpClient.shared.refererOf(url), fastFail = true)
            }.getOrNull()
        }
```
- **影响**：源站对播放页有限流（403/429 在仓库里专门处理过），换线时对同一 host 多发一次请求；
  弱网下 2s 超时会明显拖慢加载。收益（跳过 parser-blocking 的 `t.js`）依赖解析成功，属于"赌一次"。
- **价值/成本**：**低** / **S**（超时压到 800ms~1s；或对同一 host 复用上一次壳页正文做短暂缓存）。
- **判定**：**已证实**（多一次请求是代码事实）；代价大小属**推测**。
### 14. 播放页的时钟全部直用 `SystemClock`，无法注入 —— 低 / S
- **结论**：7 处直接调 `android.os.SystemClock.elapsedRealtime()` / `System.nanoTime()`，
  与已完成注入化的 `SniffedPlaylistCache(nowWallMs)`、`StalledPlayback.Tracker` 形成反差。
- **位置**：`PlayerScreen.kt:447/449/1820/1824/1994/2058/2091/2108/2268`
- **原文**：
```kotlin
                        if (pageErrorAt == 0L) pageErrorAt = android.os.SystemClock.elapsedRealtime()
```
- **影响**：正是第 4/5/6 条这类"计时/复位"缺陷最难被判据覆盖的原因 —— 这段逻辑只能在真机上试。
- **价值/成本**：**低**（可测性）/ **S**（抽 `internal fun interface Clock`，默认实现走 `SystemClock`）。
- **判定**：**已证实**（调用点可 grep）。
### 15. `VideoResolution.findTier` 每次调用重建 `Regex` —— 低 / S
- **结论**：档位正则写在函数体里，每次解析都重新编译。
- **位置**：`app/src/main/java/com/nbalive/app/ui/screens/VideoResolution.kt:97-100`
- **原文**：
```kotlin
    private fun findTier(text: String, requireP: Boolean): String? =
        Regex("""(?<![0-9])(2160|1440|1080|720|480|360|240)(p|P)?(?![0-9])""")
            .find(text)
            ?.let { tierOf(it.value, requireP) }
```
- **影响**：调用点 `PlayerScreen.kt:531`（`streamUrl?.let { VideoResolution.fromStreamUrl(it) }`）
  在每次重组都执行；直连线路"首帧宽高到达前"会连着被调用若干次，量级很小但是白花的钱。
- **价值/成本**：**低** / **S**（提成顶层 `private val`，或改用 `RegexOption` 预编译常量）。
- **判定**：**已证实**（代码事实；影响无测量 → 封顶低）。
### 16. `PipState.inPip` 是唯一可被外部直写的 `public MutableStateFlow` —— 低 / S
- **结论**：同一个对象里 `_playerActive/_videoSize/_expandRequests` 都私有 + 提供写入口，
  只有 `inPip` 暴露成可变（`PipState.inPip.value = …` 在 MainActivity 两处调用）。
- **位置**：`app/src/main/java/com/nbalive/app/ui/PipState.kt:20`；写入点 `MainActivity.kt:49/250/302`
- **原文**：
```kotlin
    /** 当前是否处于画中画窗口 */
    val inPip = MutableStateFlow(false)
```
- **影响**：封装口径不一致，后来者会照抄 `inPip` 的写法绕过写入口（`PipState.kt:64` 的"写入入口统一在这里"就失效了）。
- **价值/成本**：**低**（整洁性）/ **S**。
- **判定**：**已证实**。
### 17. `PipAspectReset.shouldResetOnMatchSwitch()` 恒为 `true`，effect 里的 `if` 是运行时死条件 —— 低 / S
- **结论**：为了"可被测试引用"保留的函数永远是常量，调用点于是写成 `if (恒真)`。
- **位置**：`ui/ManualPipPolicy.kt:64-65`；调用点 `PlayerScreen.kt:1775-1779`
- **原文**：
```kotlin
    /** 切场时是否要把视频尺寸清零。恒为 true —— 留着这个函数是为了让规则可被测试引用。 */
    fun shouldResetOnMatchSwitch(): Boolean = true
```
- **影响**：读代码的人会以为这里有个分支；测试断言的是一个常量而非行为（"测试给假安全感"这一类）。
- **价值/成本**：**低** / **S**（要么删掉函数直接调 `setVideoSize(0,0)`，要么把真实判据放进来）。
- **判定**：**已证实**（死条件）。
### 18. 死胡同期间页面仍被算作"正在看"，按 Home 会进一块坏小窗 —— 低 / S
- **结论**：`onPageDeadEnd` 不改 `playbackSuspect/playbackDead`，而 `playingNow` 只看这两者 +
  "有地址"，于是死胡同页面在自动换线前的 9.5 秒窗口里仍允许自动进小窗。
- **位置**：`ui/NbaLiveApp.kt:221-226`（`playingNow` 判据）、`:375`（`onPageDeadEnd` 接线）；画面状态 `PlayerScreen.kt:2393-2439`
- **原文**：
```kotlin
    val playingNow = (playerState.streamUrl != null || playerState.webFallbackUrl != null) &&
        !PlaybackHealth.blocksAutoEnterPip(playbackLevel)
```
- **影响**：源站播放器报错的那一刻按 Home，小窗里是那块点不动的错误面板（与 v1.2.8 那个回归同源：
  "有地址 ≠ 在播"）。窗口很短（9.5s 后自动换线），但用户的动作可能就落在这几秒里。
- **价值/成本**：**低**（边角 + 会自愈）/ **S**（`onPageDeadEnd` 里顺带置 `playbackSuspect`，
  让 `blocksAutoEnterPip` 生效）。
- **判定**：**已证实**（判据与调用点均可读）。

---

## 我读到、但认为**不值得改**的（防止 Lead 重复提）

- `PlayerControls.shouldShowBottomBar` 的两个未用参数 —— `PlayerControls.kt:31-51` 已写明保留理由，
  且 `WebFallbackControlsTest.底部控制栏不区分直连与网页路径` 做了四组 URL 的等价性断言，属**有据取舍**。
- `PlayerLock.gesturesEnabled` / `tapShowsUnlockUi` 保留为测试 oracle —— coverage-ledger 01-P2-9 已记，
  生产唯一入口是 `gestureConfig`，不要再提。
- `MIXED_CONTENT_ALWAYS_ALLOW`、`mediaPlaybackRequiresUserGesture = false`（`PlayerScreen.kt:2176-2179`）——
  实测取舍，翻了会黑屏 / 破坏自动起播。
- `WebView` 每次换线重建（`key(webFallbackUrl)`）—— P2-4 的防线，第 9.5 节已结论"必须先证明代次守卫"。
- `rememberClockText` 的 1Hz ticker（`PlayerGestureLayer.kt:496-506`）—— 每分钟才写一次状态，
  每次只做 `Calendar.getInstance()` + 一次 `String.format`，成本可忽略。
- `FIRST_FRAME_WATCHDOG_MS=30s`、`StalledPlayback.STALL_THRESHOLD_MS=30s`、`SniffRecoveryPolicy.GRACE_MS=8s`、
  `WebPageDeadEndPolicy` 的 1.5s/8s —— 都有实测取值依据（见各文件 KDoc），无新证据不动。
- 两套「疑似无信号」UI（`PlayerScreen.kt:2324` 网页区内遮罩 + `:943` 页面提示卡）：分处两层、面向不同布局，
  重叠概率低，改它只增分支。
- `onJsAlert` 直接 `confirm()` / `onJsConfirm` 直接 `cancel()`（`:2271-2293`）、`PipState` 用 StateFlow +
  递增计数而非 Boolean —— 均为产品/并发取舍，勿翻案。

## 我无法判断、需要运行 / 真机才能定的

1. **第 1 条**：「重新加载」在 paps 路径下能不能拿到新签名 —— 验证：点按钮后看 chromium 请求
   （是否回到壳页）与随后 m3u8 的 `expire`。
2. **第 2 条**：`paps.html` 里是否存在"会报错的同源 xgplayer iframe" —— 决定误判概率。
3. **第 3 条**：厂商 `MediaFocusControl` 是否真的会把我们移出焦点栈、通话结束是否真的收不到 GAIN。
4. **第 5 条**：30s 硬上限对弱网/慢源站的实际误判率（台账里"0.1~27s"是同一脚本的实测值，
   但样本量未知） —— 建议在 `WebSniffWatchdog.shouldReport` 命中处加一条 `debugLog` 采集 1 天。
5. **第 6 条**：两个 effect 的实际执行顺序（与 Compose 运行时版本相关）；静态推断是"回补 effect 更晚"。
6. **手势满量程是否在进全屏那一帧读到旧尺寸**：`PlayerGestureLayer.kt:173`
   （`val fullSwipe = PlayerGestureMath.fullSwipe(size.height.toFloat())`）在 `pointerInput` 块**起点**只算一次；
   `tapOnly` 随全屏变化会重启该块（`PlayerLock.kt:75`），但重启发生在**布局之前**，`size` 可能仍是竖屏
   16:9 视频框高度（约 405px 而非 720px）→ 全屏下滑一点点就到头。需真机在 `:173` 打日志，进/出全屏各看一次。
7. **第 7/12 条的 CPU/帧时间**需 `dumpsys gfxinfo framestats` 采样；**第 9 条**换线延迟中"重建播放器"占比
   需真机对比 `ExoPlayerImpl Init`→首帧耗时。两者都无法在只读阶段定论。
