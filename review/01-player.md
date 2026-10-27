# 播放核心审查：PlayerScreen / 手势 / 锁 / 音焦点 / PiP

审查方式：只读，逐行读完下列文件（`read` 带 offset/limit），关键行号用 `Select-String` / `ReadAllLines` 二次核对。
**未修改任何源码**（`git status` 只有 `review/` 与本文件）。

| 文件 | 实际行数 | 说明 |
|---|---|---|
| `app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt` | **1841** | 任务描述里写的 1587 行已过时；实际文件更长，已全文读完 |
| `app/src/main/java/com/nbalive/app/ui/screens/PlayerGestureLayer.kt` | 464 | |
| `app/src/main/java/com/nbalive/app/ui/PlayerControls.kt` | 95 | |
| `app/src/main/java/com/nbalive/app/ui/PlayerLock.kt` | 88 | |
| `app/src/main/java/com/nbalive/app/ui/PlayerPrefs.kt` | 67 | |
| `app/src/main/java/com/nbalive/app/ui/AudioFocusPolicy.kt` | 85 | |
| `app/src/main/java/com/nbalive/app/ui/PipState.kt` | 98 | |
| `app/src/main/java/com/nbalive/app/ui/screens/VideoResolution.kt` | 111 | （任务描述里的 `ui/VideoResolution.kt` 不存在，实际在 `ui/screens/`） |

为判断调用链，另读了 `MainActivity.kt`、`NbaLiveApp.kt`、`MainViewModel.kt`、`data/model/Match.kt`、`AndroidManifest.xml`、`app/build.gradle.kts` 与 `app/src/test` 下的相关用例。

**结论：19 条发现 —— P0 1 条 / P1 6 条 / P2 12 条。** 最要紧的是「网页兜底判死」这条链：判据是启发式推断，处置却是 `finishAndRemoveTask()`（不可逆），两者叠在一起会在误判时把用户正在看的小窗连同任务一起移除。

---

## P0

### P0-1 网页兜底「判死」是启发式推断，判死后却 `finishAndRemoveTask()` 移除整个任务

- **位置**
  - `app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:1669-1673`（25 秒无嗅探即上报死亡）
  - `app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:1671`（`suppressHint` 时也照样上报，只吞提示）
  - `app/src/main/java/com/nbalive/app/ui/MainViewModel.kt:594-600`（`onPlaybackDead()` 置 `playbackDead=true`；本轮播放内不再复位，只有 `play()`/`back()` 会清）
  - `app/src/main/java/com/nbalive/app/ui/NbaLiveApp.kt:182-185`（`playbackDead` → `finishPipIfBroken()`）
  - `app/src/main/java/com/nbalive/app/MainActivity.kt:237-245`（`finishAndRemoveTask()`）
- **问题**
  1. 「25 秒内没看到含 `.m3u8` 的请求 ⇒ 这条线播不出来」是**推断**，不是播放器的真实状态。`PipState.shouldKeepPip` 的注释（`PipState.kt:86-91`）明确写过「有地址不等于在播，真正确认要看播放器有没有报错」——这条判定恰好违反了该原则：它既不问播放器，也不看画面，只看网络请求里有没有 `.m3u8` 字面量。
  2. 误判后的处置是 `finishAndRemoveTask()`：不只是关小窗，而是**把整个任务从最近任务里移除**。用户从桌面再点图标是一次全新启动（`onCreate` → 新 ViewModel → `Route.LIST`），`MainActivity.kt:230-232` 注释承诺的「用户点开 App 就能看到完整错误页和重试入口」**在代码里不可能成立**——Activity 已经被 finish 了。
- **触发条件**（任一即可误判）
  - 源站播放页用 MSE/`blob:` 播放，或 m3u8 地址不带 `.m3u8` 字面量（`PlayerScreen.kt:1725` 只做 `u.contains(".m3u8")` 字符串匹配），正常在播也永远嗅探不到 → 25 秒后判死。
  - 弱网/源站慢：25 秒内页面还没发起视频请求（首帧 > 25s），小窗直接被 `finishAndRemoveTask()` 干掉。
  - 嗅探升级失败退回网页（`suppressHint=true`）后 25 秒 → 判死（这条是设计意图，但同样走不可逆处置）。
- **建议修法**
  - 判死分级：25 秒只置「疑似异常」（用于显示提示/停止自动进小窗），**不要**触发 `finishPipIfBroken`；真正的「死」只认 `onPlayerError`（直连）与 `onReceivedError`（网页主框架，且需按 P1-1 过滤 errorCode）。
  - 处置改为「可逆」：小窗里判死时不要 finish，改为 `setPictureInPictureParams` 提示 + 用户展开后回到播放页错误态；至少把 `finishAndRemoveTask()` 换成 `finish()`（保留最近任务记录），并删除注释里「点开 App 能看到错误页」这句与代码不符的承诺。
  - 若坚持用嗅探做判据，把匹配条件放宽到「任意 media/segment 请求」（`.m3u8`/`.mpd`/`.ts`/`/hls/`），并给 25 秒计时加「页面 `onPageFinished` 之后才开始计时」的前置条件。

---

## P1

### P1-1 `onReceivedError` 不区分 errorCode，页面被中止/重定向也会立刻判死

- **位置**：`app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:1736-1744`
- **问题**：只判 `request?.isForMainFrame == true` 就调 `onPlaybackDeadState.value()`。`WebViewClient.onReceivedError` 也会收到 `ERROR_ABORTED(-3)`（导航被取消/重定向/快速二次 `loadUrl`）、`ERROR_UNKNOWN(-1)`、`ERROR_UNSUPPORTED_SCHEME`。这些都不代表页面加载失败，但都会立刻把 `playbackDead` 置 true，进而触发 P0-1 的不可逆处置。
- **触发条件**：源站播放页里有一句 `location.href = ...` 跳转，或用户在 25 秒内连点两次「重连」（`WebSurface.update` 里 `loadUrl` 打断上一次导航）→ 主框架收到 `ERR_ABORTED` → 判死。
- **建议修法**：只对真正的失败码判定，例如 `error.errorCode in setOf(WebViewClient.ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT, ERROR_BAD_URL, ERROR_FILE_NOT_FOUND, ERROR_FAILED_SSL_HANDSHAKE)`；同时加 `!request.isRedirect` 与「首次加载完成前不判死」的保护。

### P1-2 焦点被抖音抢走后既不重申请也不放弃，此后**不再持有焦点** → 来电收不到 LOSS，直播声音盖住通话

- **位置**：`app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:1468-1475`（`am?.requestAudioFocus(...)` 返回值被丢弃，且只在 `onDispose` 里 `abandonAudioFocus`）、`1443-1467`（监听器只在收到事件时才暂停）
- **问题**：整套策略（`AudioFocusPolicy` 里「只在来电时停」）成立的前提是**我们确实持有音频焦点**，但代码没有任何一处保证这一点：
  - `requestAudioFocus` 的返回值从未检查，`AUDIOFOCUS_REQUEST_FAILED` 与 `GRANTED` 走同一条路；
  - 焦点被抖音以 `AUDIOFOCUS_GAIN` 抢走后（我们收到 `AUDIOFOCUS_LOSS`），按需求**不停播、也不重新申请**。AOSP 的 `MediaFocusControl` 在向某条目派发**永久** `AUDIOFOCUS_LOSS` 后会把它移出焦点栈，只有 `GAIN_TRANSIENT` 类丢失才保留；也就是说收到 LOSS 之后我们**已经不在焦点栈里**了；
  - 不在栈里就收不到任何后续焦点事件：来电时系统的 `GAIN_TRANSIENT` 只派给当前持有者（抖音），**我们什么都收不到 → 直播继续外放，盖住通话**；
  - 反过来，抖音退出后也不会给我们 `AUDIOFOCUS_GAIN`，`pausedByFocus` 那套「丢了再还回来」的恢复逻辑（`1459-1464`）在永久 LOSS 场景下永远不会触发。
- **触发条件**：小窗看直播 → 打开抖音 → 此时来电。
- **建议修法**：收到 `AUDIOFOCUS_LOSS` 且决定继续播时，立即重新 `requestAudioFocus`（`AUDIOFOCUS_GAIN`），并在 `ON_START` 与每次起播时都补一次（把「是否持有焦点」变成一个显式状态，而不是靠系统记住我们）；同时检查 `requestAudioFocus` 返回值，失败时走 `AudioManager.getMode()` 轮询兜底（仅播放中、1-2 秒一次）作为第二信号源。
- **待确认**：焦点栈的具体行为是 AOSP 实现（`MediaFocusControl`），部分厂商 ROM 会向所有曾申请过焦点的进程广播丢失；但「不重新申请就一定收不到后续事件」这一结论在两种实现下都成立。

### P1-3 来电判据依赖「事件到达时现查 `AudioManager.mode`」，响铃瞬间存在竞态

- **位置**：`app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:1445`（现查 `am?.mode`）、`1452`；策略在 `app/src/main/java/com/nbalive/app/ui/AudioFocusPolicy.kt:65`（`shouldPauseOnFocusLoss` 完全忽略 `focusLossType`）
- **问题**：注释（`PlayerScreen.kt:1431-1433`）自己指出「焦点丢失和来电几乎同时发生」，但判据仍然只依赖这一瞬间的 `mode`。来电时系统先发 `AUDIOFOCUS_LOSS_TRANSIENT`，`AudioManager` 切到 `MODE_RINGTONE` 往往稍晚几毫秒；此时现查得到 `MODE_NORMAL` → 判定「不是来电」→ **不停播，响铃/通话期间直播继续外放**，正是 v1.2.10 想修的那个问题。
- **触发条件**：来电瞬间（尤其响铃先于音频路由切换的机型）。
- **建议修法**：不要用一个瞬时值承担全部判定。`AUDIOFOCUS_LOSS_TRANSIENT` 直接暂停——媒体类 App（抖音/B 站/别的播放器）实测用 `AUDIOFOCUS_GAIN` 抢焦点（`AudioFocusPolicy.kt:74-77` 已写明），TRANSIENT 基本只来自来电/语音助手/导航播报，暂停是安全的；`AUDIOFOCUS_LOSS` 再走 `mode` 现查。这样既保住「抖音不停」，又消掉竞态。改动点只在 `AudioFocusPolicy.shouldPauseOnFocusLoss` 内部，可继续用现有单测覆盖。

### P1-4 亮度只写窗口、离开播放页不还原 → 整个 App（列表/详情）停在被调暗的亮度

- **位置**：`app/src/main/java/com/nbalive/app/ui/screens/PlayerGestureLayer.kt:296-301`（`writeBrightness` 直接改 `window.attributes.screenBrightness`）；同文件 `112-114` 的 `DisposableEffect` 只取消 OSD 计时，**没有复位亮度**
- **问题**：注释（`PlayerGestureLayer.kt:295`、`62-63`）声称「退出 App 自动回落到系统亮度」，实际是**退出 Activity 才回落**。本 App 是单 Activity（`AndroidManifest.xml` 只有 `MainActivity`，`launchMode="singleTask"`），从播放页返回列表/详情页时窗口不重建，`screenBrightness` 保持被改过的值。用户把亮度滑到 2% 看球，返回列表后整个 App 都是 2%，且列表页没有任何恢复入口。
- **触发条件**：播放页左半屏下滑调暗 → 按返回键回到列表。
- **建议修法**：在 `PlayerMediaGestures` 的 `DisposableEffect(Unit)` 里 `onDispose` 复位：`activity?.window?.let { val a = it.attributes; a.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE; it.attributes = a }`（即 -1f，恢复跟随系统）。注意只在「用户确实改过」时复位，避免把系统跟随值写死。

### P1-5 15 秒看门狗会把「弱网首帧 > 15 秒」的健康线路永久拉黑

- **位置**：`app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:1514-1525`；拉黑逻辑在 `app/src/main/java/com/nbalive/app/ui/MainViewModel.kt:633`（`failedSources += it.pageUrl`）
- **问题**：看门狗 15 秒到点只看两件事：`!reachedReady` 且当前不是 READY。直播 HLS 首帧 = 主清单 + 变体清单 + 首个分片 + 防盗链鉴权，弱网/源站慢时 15 秒很常见。一旦触发就 `onPlaybackFailed()` → 该线路进 `failedSources`（**本次会话内永久拉黑**，只有用户手动点这条线路才会被 `selectIndex` 清掉），然后自动切下一条。用户看到的是「线路莫名被跳走」，且再回不来。
- **触发条件**：4G 弱信号/源站 CDN 冷启动，15 秒内没进 `STATE_READY`。
- **建议修法**：三选一即可——(a) 阈值提到 30 秒；(b) 计时器在 `onPlaybackStateChanged` 收到 `STATE_BUFFERING` 且有 `bufferedPosition` 前进时重置；(c) 看门狗只上报「慢」用于显示转圈提示，不触发拉黑。另建议 `failedSources` 加时间戳（例如 5 分钟后自动解封），避免一次误判整场都废。

### P1-6 锁定态单击不消费事件 → 网页线路的「防误触」锁不住源站播放器控件

- **位置**：`app/src/main/java/com/nbalive/app/ui/screens/PlayerGestureLayer.kt:184-187`（未拖动且未被别人消费 → 只 `invoke()` 回调，**不 consume**）、`128-132`（`awaitFirstDown(requireUnconsumed = false)`，down 也不消费）
- **问题**：直连路径下面是没有控制条的 `PlayerView`（`useController = false`），所以「不消费」无害。网页路径下面是 `WebView`，源站 H5 播放器自带「单击显隐控制条 / 双击全屏 / 底部按钮」。锁定后我们的浮层全隐藏了，但**每一次误触的单击都照旧透传给源站播放器**：手掌蹭一下就会点亮源站控制条、甚至双击进源站自己的全屏。用户以为锁住了，实际只锁住了我们这层的 UI。
- **触发条件**：网页兜底线路 → 锁定 → 手掌/手指在画面上蹭动。
- **建议修法**：`tapOnly` 分支里把事件消费掉——`awaitFirstDown` 之后（或判定为单击抬手时）`change.consume()`，让 WebView 收不到这次点击；同时保留 `unlockUiVisible` 的唤出逻辑。注意别影响非锁定态的「单击透传给源站」（那是 v1.2.5 明确要的行为）。

---

## P2

### P2-1 播放中途卡死没有检测（看门狗只覆盖「首帧前」）

- **位置**：`app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:1514-1525`（一次性 `delay(15_000)`）、`1589`（`reachedReady = true` 后看门狗已结束）
- **问题**：直播流最常见的故障是**播到一半** CDN 断流：连接不断、`onPlayerError` 不触发、`STATE_BUFFERING` 一直挂着，画面冻结但 App 毫不知情（只有缓冲圈）。目前没有任何「长时间 BUFFERING」的检测。
- **触发条件**：比赛进行中 CDN 节点故障/签名过期（`onReconnect` 的注释 `PlayerScreen.kt:160` 提到地址带时效签名）。
- **建议修法**：在 `onPlaybackStateChanged` 里对 `STATE_BUFFERING` 起一个「连续 20 秒未恢复 READY 且 `bufferedPosition` 不前进」的计时器（`Handler`/`LaunchedEffect` 均可），超时走 `onError` → 自动重连或换线。

### P2-2 非全屏时手势层覆盖整个视频区并消费垂直拖动，从视频区起手的上滑滚不动页面

- **位置**：`app/src/main/java/com/nbalive/app/ui/screens/PlayerGestureLayer.kt:125-128`（`Box` 内 `fillMaxSize()` + `pointerInput`）、`167-168`（`dragging` 时 `change.consume()`）；调用点 `app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:367-378`（`enabled = !inPip`，非全屏也启用）、`261`（非全屏的 Column 有 `verticalScroll`）
- **问题**：非全屏时手势层只覆盖 16:9 的视频 Box（`PlayerScreen.kt:267-272`），但它把落在视频区内的垂直拖动**全部消费**掉。用户从视频画面上滑想滚到下方的「信号切换」列表时，滚动被解释成调亮度/音量（还弹 OSD），页面一动不动；只有把手指放到视频下方的信息卡上滑才滚得动。视频区占屏幕上部约 1/3，这是很自然的手势起点。
- **触发条件**：非全屏播放页，手指落在视频区域内向上/向下滑。
- **建议修法**：非全屏时不要无条件接管垂直拖动——把 `PlayerMediaGestures` 的接管条件收紧为「位移以垂直为主（`abs(dy) > abs(dx) * 1.5`）**且** 视频区可滚动内容不在该方向继续滚动」，或最省事：非全屏只在**全屏态**启用亮度/音量（`enabled = fullscreen`），非全屏保留单击行为。无论哪种，都要让未被接管的拖动回到父级 `verticalScroll`。

### P2-3 网页路径 `ON_STOP` 只 `onPause/pauseTimers`，HTML5 媒体不会停；且 `pauseTimers` 是进程级

- **位置**：`app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:1676-1686`（`webView?.pauseTimers(); webView?.onPause()`）
- **问题**：注释（`1675`）写「退到后台（非小窗）时暂停页面定时器，省电并防止声音后台外放」，但 `WebView.onPause()` 只暂停解析/布局/JS 定时器，**`<video>`/MSE 的音频播放不会停**；`pauseTimers()` 还是 `WebView` 的**静态**方法，作用于进程内所有 WebView（以后加第二个 WebView 会被误伤）。直连路径有 `ON_STOP → player.pause()`（`1491-1497`），两条路径行为不一致。
- **触发条件**：网页兜底线路播放中，锁屏或切到别的 App 而未进小窗（`playerActive` 为 false 时不会自动小窗，必然走 `ON_STOP`）。
- **建议修法**：`ON_STOP` 里补一句 JS：`webView.evaluateJavascript("document.querySelectorAll('video,audio').forEach(e=>e.pause())", null)`；`ON_START` 视需要 `play()`。并把 `pauseTimers()` 换成只对本 WebView 有效的做法（或至少在注释里标明其进程级副作用）。

### P2-4 网页线路切线路时复用同一个 WebView，旧页在途的 m3u8 请求可能被当成新线路的流上报

- **位置**：`app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:1774-1781`（`update` 里 `loadUrl(url)` 复用 WebView，未 `key(url)`）；上报点 `1720-1731`；消费点 `app/src/main/java/com/nbalive/app/ui/MainViewModel.kt:563-582`（`onStreamSniffed` 不校验 m3u8 来自哪个播放页）
- **问题**：切到另一条网页线路时 WebView 不重建，只 `loadUrl`。旧页面的 fetch/XHR 在导航提交前可能仍在飞，其 m3u8 请求会经 `shouldInterceptRequest` 上报，`onStreamSniffed` 只判「当前是网页播放」就升级 → **用户界面显示线路 B，实际播的是线路 A 的流**（A 往往正是刚才失败/被放弃的那条）。
- **触发条件**：网页线路 A 正在起播（m3u8 请求在途）时用户切到线路 B，窗口较窄但确实存在。
- **建议修法**：`shouldInterceptRequest` 里把当前 `url`（组件参数）一起带上，`onStreamSniffed(pageUrl, m3u8, referer)` 里校验 `pageUrl == state.webFallbackUrl` 才升级；或在切线路时先 `loadUrl("about:blank")` 再 `loadUrl(新地址)`。

### P2-5 `PipState.videoSize` 从不清零 → 切比赛后小窗比例沿用上一场

- **位置**：`app/src/main/java/com/nbalive/app/ui/PipState.kt:43-44`、`57-59`（只有 `setVideoSize`，无 reset）；写入点 `app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:1579-1585`；读取点 `app/src/main/java/com/nbalive/app/MainActivity.kt:151-163`
- **问题**：`videoSize` 是进程级单例，只在 `onVideoSizeChanged` 时更新。切换比赛后、新流还没回调宽高之前，`pipAspectRatio()` 用的是**上一场**的宽高 → 小窗比例错误（16:9 的场次被塞进 4:3 的窗口或被裁）。同理，网页兜底路径永远不会写这个值，会一直沿用最近一次直连的宽高。
- **触发条件**：看 16:9 的 A 场 → 「同时在播」切到 4:3 的 B 场 → 立刻按 Home 进小窗。
- **建议修法**：在 `VideoSurface` 的 `onRelease`（`1622-1631`）或 `MainViewModel.play()` 重置状态时调 `PipState.setVideoSize(0, 0)`；`pipAspectRatio()` 已有 `w<=0||h<=0 → 16:9` 的兜底，重置即可生效。

### P2-6 解析中点「画中画」按钮 → 进入一个转圈/黑窗小窗

- **位置**：`app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:415-431`（PiP 按钮只受 `!inPip && showOverlays(locked) && (!fullscreen || controlsVisible)` 约束）；`app/src/main/java/com/nbalive/app/MainActivity.kt:112-143`（`enterPip()` 不检查有没有流）
- **问题**：`PipState.shouldAutoEnterPip` 已经明确「没流不该弹小窗」（`PipState.kt:61-76`），但**手动按钮这条路径没有同样的守卫**。解析中（`streamUrl`/`webFallbackUrl` 都为 null）点小窗，会得到一个只画着「正在解析直播源…」的小窗。
- **触发条件**：点某条线路后（`resolving=true`）立刻点右上角画中画按钮。
- **建议修法**：按钮的 `onClick` 前置判断 `if (streamUrl == null && webFallbackUrl == null) { /* toast 或忽略 */ return }`，或在 `MainActivity.enterPip()` 里用 `PipState.shouldAutoEnterPip` 的同一套判定做守卫（可测）。

### P2-7 进/出小窗时 `requestedOrientation` 被复位，PiP 窗口可能跟着转

- **位置**：`app/src/main/java/com/nbalive/app/ui/NbaLiveApp.kt:622-641`（`DisposableEffect(enabled, activity)`，`enabled = fullscreen && !inPip`）
- **问题**：进小窗时 `enabled` 由 true→false，`onDispose` 把 `requestedOrientation` 设回 `UNSPECIFIED`，随后新块又执行 `else` 分支再设一次 `UNSPECIFIED`。PiP 窗口的方向因此交给系统（可能从横屏小窗变竖屏小窗）；退出小窗时又立刻 `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`，中间会有一次方向跳变。
- **触发条件**：全屏横屏播放 → 按 Home 进小窗 → 展开回前台。
- **建议修法**：PiP 期间不要把方向复位成 `UNSPECIFIED`，保留 `SENSOR_LANDSCAPE`（小窗比例由 `setAspectRatio` 决定，与 Activity 方向无关）；只在真正离开播放页/退出全屏时复位。

### P2-8 全屏浮层 10 秒计时在「线路/播放源」面板打开时照样走完，面板选完底部栏消失

- **位置**：`app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:236-241`（计时条件不含 `picker`/`showLivePanel`）、`253-255`（打开面板只把 `controlsVisible` 置 true 一次）
- **问题**：打开面板时 `controlsVisible=true` 重启计时，10 秒后计时器仍会把 `controlsVisible` 置 false —— 面板还开着，底部控制栏却没了。用户选完线路关掉面板，全屏里没有任何按钮，必须再点一次画面才唤回。`253-255` 的注释意图是「面板开着时别让栏消失」，实现只做了「打开瞬间置 true」，没做「面板期间挂起计时」。
- **触发条件**：全屏 → 点「线路」胶囊 → 在面板里停留 > 10 秒 → 选线路并关闭面板。
- **建议修法**：把 `picker != null || showLivePanel` 加进 `236` 的 `LaunchedEffect` key 并在条件里排除（`if (fullscreen && !inPip && controlsVisible && !locked && picker == null && !showLivePanel)`）。

### P2-9 `PlayerLock.gesturesEnabled` / `tapShowsUnlockUi` 是死函数，单测测的是没人用的实现

- **位置**：`app/src/main/java/com/nbalive/app/ui/PlayerLock.kt:29`、`71`；单测 `app/src/test/java/com/nbalive/app/ui/PlayerLockTest.kt:35-40`、`124-126`；真实调用点是 `app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:368-377`（直接写 `enabled = !inPip`、`tapOnly = locked`、`onTap = when { locked -> ... }`）
- **问题**：这正是 `PlayerLock.kt:49-53` 自己批评过的模式——「规则有两份实现、其中一份是死代码，测试测的是没人用的那份，真实行为反而零保护」。现在 `gesturesEnabled` / `tapShowsUnlockUi` 没有任何生产调用方（已 grep 全仓确认），但 `PlayerLockTest` 对它们断言通过，给人「锁定逻辑已被测试守住」的错觉；真实判定散在 `PlayerScreen` 的三元表达式里，改坏了不会有测试红。
- **触发条件**：任何人以后改 `PlayerScreen.kt:368-377` 的锁定分支。
- **建议修法**：`PlayerScreen` 改为调用 `PlayerLock.gesturesEnabled(locked)` / `PlayerLock.tapShowsUnlockUi(locked)`，或删除这两个函数与对应单测。二选一，不要两套并存。

### P2-10 `PlayerControls.shouldShowBottomBar` 的 `streamUrl`/`webFallbackUrl` 参数完全没参与判定，测试制造「已区分两条路径」的假象

- **位置**：`app/src/main/java/com/nbalive/app/ui/PlayerControls.kt:34-41`（实现只有 `fullscreen && !inPip && controlsVisible && !locked`）；测试 `app/src/test/java/com/nbalive/app/ui/WebFallbackControlsTest.kt:34-117`（每个用例都精心构造 `streamUrl`/`webFallbackUrl` 组合）
- **问题**：参数存在但不用。测试里「网页兜底全屏时底部控制栏也要显示」这条用例，把 `streamUrl=null` 换成任意值结果都一样——它并没有真正守住 v1.2.9 那个回归（`webFullscreen` 被塞进条件）。后来人若要重新加路径区分，测试不会红。
- **触发条件**：未来把 `streamUrl == null` 之类的条件加回实现。
- **建议修法**：要么删掉两个无用参数（签名收敛为 4 个布尔），要么在实现里显式断言「不区分路径」并让测试覆盖「两条路径结果相同」的等价性（例如对同一组布尔值遍历 `streamUrl`/`webFallbackUrl` 的 4 种组合，断言结果恒等）。

### P2-11 网页兜底路径下「画面」胶囊（自适应/铺满裁切/拉伸铺满）完全无效，点了没有任何变化

- **位置**：`app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:298-326`（`webViewSizeForAspect(... aspectRatio = 16f / 9f ...)` 硬编码 16:9，`WebSurface` 调用（`319-324`）根本没接 `aspectMode` 参数）；胶囊入口 `592-601`（三档循环 `onAspectChange`）
- **问题**：`aspectMode` 只被传给了直连路径的 `PlayerView.resizeMode`（`1599`、`1611`），网页路径完全没有使用它。用户在网页线路全屏里点「画面 自适应」切到「铺满裁切」/「拉伸铺满」，底部胶囊文字变了、实际画面毫无变化（永远被约束在 16:9 内接矩形里）。更糟的是这个选择会被 `PlayerPrefs` 持久化（`PlayerPrefs.setAspect`），之后进直连线路才突然生效——用户会认为「这个按钮有时灵有时不灵」。
- **触发条件**：任意网页兜底线路全屏 → 点「画面」胶囊切换三档。
- **建议修法**：二选一——(a) 网页路径隐藏/禁用这枚胶囊（`streamUrl == null` 时不渲染，并在 `PlayerControls` 里补一个可测的 `shouldShowAspectChip(streamUrl)`）；(b) 把 `aspectMode` 传进 `WebSurface`/尺寸计算：FIT 用 16:9 内接，ZOOM/FILL 用 `fillMaxSize()`（并把 `aspectRatio` 参数按 `aspectMode` 映射）。当前「显示了三档但不生效」是最差的一种。

### P2-12 WebView 只按 scheme 过滤导航、不限制目标主机，源站广告可把主框架导航走 → 播放中断且不报错

- **位置**：`app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:1710-1717`（`shouldOverrideUrlLoading` 仅 `return scheme != "http" && scheme != "https"`）、`1700-1708`（`mixedContentMode = MIXED_CONTENT_ALWAYS_ALLOW`）
- **问题**：`shouldOverrideUrlLoading` 放行**所有** http/https 导航，只挡 `intent://`/`market://`。源站播放页里混入的广告脚本做 `location.href = "https://广告页"` 时，WebView 主框架会被导航走：源站播放器随页面销毁，画面变黑/变广告页，**既不触发 `onPlayerError`（不是 ExoPlayer）也不触发 `onReceivedError`（导航本身成功）**，25 秒判死逻辑（P0-1）此时也未必触发——用户看到一个挂着广告、点不动、且 App 认为「在播」的播放页。
- **触发条件**：源站投放的插屏/跳转广告（该源站已在 `onJsAlert`/`onJsConfirm` 里被专门处理过弹窗，说明这类页面确实不可控）。
- **建议修法**：给主框架导航加白名单——`request.isForMainFrame && request.url.host !in allowedHosts` 时返回 true（拦截）并记日志；`allowedHosts` 由播放页 URL 的 host 及其一级域构成（或允许列表在 `loadUrl` 时记录一次）。顺带 `settings.allowContentAccess = false`（该项默认 true，与 `targetSdk` 无关；`allowFileAccess` 在 `targetSdk 35` 下已默认 false，无需处理）。
- **备注**：这条与 P0-1 联动——它是「判死逻辑之外」的第三条静默失败路径，值得和 P0-1 一起改。

---

## 已核对、未发现问题的点（避免后续重复审查）

- **ExoPlayer 释放**：`PlayerScreen.kt:1622-1631` 顺序正确——先 `handle.detach()`、`view.player = null`、再 `release()`，符合 Media3「先摘引用再释放」的要求；`AndroidView` 用 `key(streamUrl, streamReferer)` 包住（`275`），换线路整体重建，Referer 不会残留。
- **WebView 释放**：`1782-1795` 依次 `removeView → stopLoading → about:blank → 清 client → clearHistory → destroy → webView = null`，完整；`webView = null` 在 `destroy()` 之后，生命周期观察者不会对已销毁实例调 `pauseTimers()`（`1679` 的 `?.` 兜底也成立）。
- **协程生命周期**：`LaunchedEffect`/`rememberCoroutineScope` 均绑定组合；OSD 防抖任务在 `DisposableEffect` 的 `onDispose` 取消（`PlayerGestureLayer.kt:112-114`），没有「Composable 离开后仍回调」的路径（`onStreamFound`/`onPlaybackDead` 都过 `rememberUpdatedState`）。
- **`onTap` 状态提升**：`PlayerGestureLayer.kt:123` 用 `rememberUpdatedState` 包 `onTap` 且不放进 `pointerInput` 的 key，避免了进全屏后单击失效——写法正确。
- **除零/越界**：`PlayerGestureMath.fullSwipe`（`243-244`）与 `valueFromDrag`（`259`）都有保护；`PlayerControls.webViewSizeForAspect`（`83`）对 0 尺寸返回 null；`PlayerScreen.kt:604` 的 `selectedIndex.coerceIn(0, signals.lastIndex)` 与 `702` 的 `getOrNull` 都安全；`MainActivity.pipAspectRatio`（`156`）对非正尺寸回退 16:9。
- **PiP 入口守卫**：`MainActivity.enterPip`（`112-121`）对 API < 26 与无 `FEATURE_PICTURE_IN_PICTURE` 都有 toast 提示，`setPictureInPictureParams` 全部包在 `runCatching` 里。
- **锁与比赛绑定**：`PlayerLock.isLocked(lockedMatchId, currentMatchId)`（`PlayerLock.kt:86-87`）+ `NbaLiveApp.kt:148` 的组合，切场自动失效，没有「裸布尔漏到下一场」的问题。
- **`rememberSaveable` 的 key 用法**：`controlsVisible`/`showLivePanel`/`picker` 用 saveable、`unlockUiVisible`/`controlsTouchSeq` 用 `remember`，与注释里「瞬时交互态不该跨进程恢复」的判断一致。

## 建议的修复优先级

1. **P0-1**（判死链）：先拆开「疑似异常」与「彻底失败」，把 `finishAndRemoveTask()` 降级为可逆处置。
2. **P1-2 / P1-3**（音焦点）：这两条是用户刚修过的「来电不该外放 / 抖音不该暂停」的残留缺口，改动小、单测能覆盖。
3. **P1-4**（亮度不复位）：影响面最大（所有页面），修法最短。
4. **P1-1 / P1-5 / P1-6**：误判与防误触的边界。
5. P2 按上面顺序随手带走，其中 **P2-9 / P2-10** 是「测试给假安全感」类问题，建议与下一次改播放页时一起处理。
