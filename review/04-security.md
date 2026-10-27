# 04 · 构建与安全审查（Android / Gradle / 签名 / R8）

- 审查对象：`G:\flutter\work\nba`（Kotlin 2.0.21 / AGP 8.7.3 / Gradle 8.9 / minSdk 24 / targetSdk 35）
- 审查方式：只读审查。源码与配置**未做任何修改**，唯一新增文件为本报告。
- 审查人：reviewer-security
- 结论概览：**0 个 P0，5 个 P1，9 个 P2**（另附 6 项"检查通过"结论）

> **最重要的两条结论先说：**
> 1. **没有发现密钥泄漏**——`keystore.properties` / `*.jks` / `*.keystore` / `local.properties` 均未被 Git 跟踪，`.gitignore` 已覆盖，且 `git log --all --full-history`、`git rev-list --objects --all`（831 个历史对象）、`git fsck --unreachable`（22 个游离对象）全量扫描后**历史提交中也没有提交过任何密钥内容**。
> 2. **没有发现"忽略 SSL 证书错误"**——全仓库不存在 `onReceivedSslError` 覆写、不存在自定义 `TrustManager` / `HostnameVerifier`、不存在 `handler.proceed()`。

---

## 一、发现列表

### P0 阻塞

无。

> 说明：审查前最担心的两类"必炸"问题（密钥入库、WebView 忽略证书错误）经全量核查均不成立，详见第二节检查通过项。

---

### P1 重要

#### P1-1　日志脱敏被"直接打 throwable"绕过，签名 URL 仍可能进 logcat

- **文件:行号**
  - `app/src/main/java/com/nbalive/app/ui/MainViewModel.kt:249`、`:288`、`:387`、`:492`
  - `app/src/main/java/com/nbalive/app/data/repository/MatchRepository.kt:82`、`:86`
- **问题**：`Logs.kt:28` 的 `redactUrl()` 只对"显式传入的 URL 字符串"生效。但上面这些点是 `Log.w(tag, "...", t)` / `Log.w(tag, "... ${t.message}", t)`，把**异常对象本身**交给 Log。而 `HttpClient.kt:64-66` 的注释明确写着"OkHttp 部分异常消息内嵌完整 URL（如 `unexpected end of stream on https://...?auth_key=...`），照样泄露播放凭证。只打异常类型"——`HttpClient` 自己在 `:67` 遵守了这条约定，但**上层把 `t` 原样打出去，等于把这道防线拆掉**。
- **触发条件**：release 包（这些是常开的 `Log.w`/`Log.e`，不受 `BuildConfig.DEBUG` 保护）。源站连接中途断开、畸形 m3u8 地址让 OkHttp 抛 `IllegalArgumentException`、DNS/超时异常——任一情况都会把带 `auth_key`/`sign` 的完整 URL 写进 logcat。`adb logcat` 无需任何权限即可读取。
- **建议修法**：
  1. 统一改为只打异常类型，与 `HttpClient.kt:67` 保持一致：`Log.w(TAG, "resolveStream failed: ${t.javaClass.simpleName}")`；
  2. 或在 `Logs.kt` 增加 `redactThrowable(t)` 辅助函数，对 `t.message` 先跑 `redactUrl` 再拼接，禁止任何地方直接 `Log.w(..., t)`；
  3. 加一条 lint/单测约定（`RedactUrlTest` 已存在，可扩展为"禁止 Log 直接接收 Throwable"的静态检查）。

#### P1-2　`JrsParser` 把未脱敏的原始 href 写进常开日志

- **文件:行号**：`app/src/main/java/com/nbalive/app/data/remote/JrsParser.kt:59`、`:69`
- **问题**：
  - `:59` `Log.w(TAG, "JRS 列表项缺队名，跳过：$path")`
  - `:69` `Log.w(TAG, "JRS 列表项 href 不合预期，跳过：$path")`

  `path` 来自 `anchor.attr("href")`（`:53`），是**源站给的原始 href**，可能是绝对 URL 且带 query（`?id=...&sign=...`）。这两条是 `Log.w`，release 包常开，且**完全没有经过 `redactUrl()`**——而同文件其他位置、以及 `HttpClient` / `MatchRepository` / `StreamUrlResolver` 都已经刻意做了脱敏。属于同一份代码里脱敏标准不一致留下的缺口。
- **触发条件**：源站改版导致某个列表项缺队名、或 href 不是 `/live/{数字}.html` 形态（`javascript:`、外链推广等），即会触发。这类"异常路径"恰恰在源站改版期最容易命中。
- **建议修法**：改为 `Log.w(TAG, "JRS 列表项缺队名，跳过：${redactUrl(path)}")`（`redactUrl` 需 `import com.nbalive.app.data.remote.redactUrl`，同包直接可用），`:69` 同理。

#### P1-3　WebView 加载第三方 HTTP 页面并执行其 JS，缺少来源约束

- **文件:行号**：`app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:1700-1708`（`settings.apply` 块）、`:1769`（`loadUrl(url)`）
- **问题**：`javaScriptEnabled = true`（`:1701`）+ `domStorageEnabled = true`（`:1702`）+ `loadUrl(url)` 加载的是源站（JRKAN/JRS）播放页。该页面的最终 URL **不做任何校验**：`loadUrl` 之后页面可自行跳转（代码注释 `:1775` 自己承认"页面会自行跳转（sm.html -> 内层播放页）"），跳转目标只受 `shouldOverrideUrlLoading` 限制，而它**只拦非 http(s) scheme**（见 P1-4）。因此源站（或篡改源站响应的人）可以让 WebView 停在任意第三方 http 页面上，该页面的 JS 在本 App 的 WebView 内执行，拥有 `domStorageEnabled` 提供的持久化存储。
- **触发条件**：源站被入侵/被投放广告脚本/中间人篡改（页面本身是明文 HTTP，见 P1-5），页面跳转到任意域。
- **风险边界（据实说明，不夸大）**：本项目**没有 `addJavascriptInterface`**（全仓库 grep 无匹配），所以恶意 JS **拿不到任何原生桥**，无法直接调用 App 的 Kotlin 代码、读 SharedPreferences 或发 Intent。危害被限制在 WebView 沙箱内（可发网络请求、可写 DOM storage、可尝试读 `file://` 子资源——见 P2-3）。
- **建议修法**：
  1. 在 `shouldOverrideUrlLoading` 里对 http(s) 也做 host 白名单校验（`sportsteam`/`jrkan`/`jrs` 等已知播放器域名），非白名单返回 `true` 拦截（或改为 `Intent.ACTION_VIEW` 交系统浏览器，并先确认不是本 App）；
  2. 至少在主框架 `onPageStarted`/`doUpdateVisitedHistory` 里校验 host，偏离已知域名时中止加载并上报 `onPlaybackDead`；
  3. 长期方案：`settings.blockNetworkLoads` 无法局部用，考虑用 `androidx.webkit` 的 `WebViewAssetLoader`/请求拦截做域名收敛。

#### P1-4　`shouldOverrideUrlLoading` 只拦非 http(s)，广告/外链/钓鱼页全部在 WebView 内放行

- **文件:行号**：`app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:1711-1717`
- **问题**：
  ```kotlin
  val scheme = request?.url?.scheme ?: return false
  return scheme != "http" && scheme != "https"
  ```
  语义是"只有 `intent://` / `market://` 这类非 http(s) 才拦"。注释（`:1709`）写的意图是"拦截源站广告经常用 intent:// / market:// 拉起商店和外链 App"——**这个意图是对的，但实现只覆盖了一半**：http(s) 的广告落地页、钓鱼页、博彩推广页会被**直接加载进 WebView 主框架**，用户看到的是一个没有任何地址栏、无法退出的全屏页面（本 App 的 Compose 浮层还压在它上面）。
- **触发条件**：源站广告位点击、或页面自动跳转（`location.href = ...`）。源站本身是免费第三方体育站，广告跳转是常态。
- **建议修法**：见 P1-3 建议 1/2。若产品上确实需要放行 http(s) 跳转（例如内层播放页跳转），则至少限制"只允许同源或已知播放器域名"，其余 `return true` 丢弃。

#### P1-5　`network_security_config.xml` 对**所有域名**放行明文流量

- **文件:行号**：`app/src/main/res/xml/network_security_config.xml:23-29`
- **问题**：`<base-config cleartextTrafficPermitted="true">` 是全局放行，不是"只对源站放行"。文件头注释（`:2-17`）说明的理由是成立的：源站播放页与切片走 http，且 m3u8 所在 CDN 主机名是**运行时由源站生成并混淆**的（`hdl2.<反转后的主域名>`），无法预先写进域名白名单。因此"全局明文"在当前产品形态下确实是硬需求，**这不是疏忽**。
  但风险是实的：本 App 的核心资产是"带时效签名的 m3u8 URL"，而明文 HTTP 意味着链路上任何人都能读到它、也能篡改响应注入任意 JS（放大 P1-3）。
- **触发条件**：用户处于不可信 Wi-Fi / 被劫持的运营商网络 / 公共热点。
- **建议修法**（不要求改产品形态，只做收敛）：
  1. 保留 `base-config cleartextTrafficPermitted="true"`，但为**已知且支持 https 的域名**加 `<domain-config cleartextTrafficPermitted="false">`：至少 `jrszbt.com`、`im-imgs-bucket.oss-accelerate.aliyuncs.com`、`m.jrkan.com`（源码里这三处都是 `https://`，见 `MatchRepository.kt:300-301`、`JrsParser.kt:41`、`JrkanParser.kt:41`）——目前这些域名的 https 能力没有被利用来收紧策略；
  2. 把已知明文主机（`play.sportsteam356.com`、`play.sportsteam368.com`、`jrszbt.com` 的 http 播放页）显式列进 `<domain-config>`，让"允许明文"的域名集合**可审计**，而不是"全网都允许"；
  3. 后续源站支持 https 时，只需删 `base-config` 的许可，`domain-config` 已在位。

---

### P2 建议

#### P2-1　`mixedContentMode = MIXED_CONTENT_ALWAYS_ALLOW`

- **文件:行号**：`app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:1706`
- **问题**：显式允许 https 页面加载 http 子资源。注释（`:1704-1705`）说是实测后的取舍（收紧成 `COMPATIBILITY` 会黑屏）。当前播放页本身是 http，所以这条设置**今天并不额外增加实质风险**；但它是"为未来埋的坑"——一旦播放页迁到 https，这条设置会让所有 http 切片（含 m3u8 签名）静默降级加载，攻击面重新打开。
- **触发条件**：源站播放页改为 https 而切片仍为 http 时。
- **建议修法**：保留现状，但在文件里加 TODO + 在 release-notes 记一条"源站转 https 时必须把 mixedContentMode 收紧为 `MIXED_CONTENT_NEVER_ALLOW`"；或改为运行时判断：页面 scheme 为 https 时才收紧。

#### P2-2　`mediaPlaybackRequiresUserGesture = false` 允许源站页面无手势自动播放音频

- **文件:行号**：`app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:1703`
- **问题**：这是自动起播的产品需求，本身合理。但结合 P1-3（页面可跳到任意域），任意被加载的页面都能**无用户手势**自动播放音频，用户会遇到"进播放页就有不明声音、找不到来源"。
- **触发条件**：源站广告页/跳转页含自动播放媒体。
- **建议修法**：不改此项（改了会破坏自动起播）。改为配合 P1-3 的域名白名单收敛，把"能自动播放的页面"限制在已知播放器域。

#### P2-3　`allowFileAccess` 未显式关闭，API 24–29 上默认允许 `file://` 子资源

- **文件:行号**：`app/src/main/java/com/nbalive/app/ui/screens/PlayerScreen.kt:1700-1708`（`settings.apply` 块内**没有** `allowFileAccess = false`）
- **问题**：`WebSettings.getAllowFileAccess()` 在 **API ≤ 29 默认 true**，API 30+ 才默认 false。本项目 minSdk 24，因此在 Android 7.0–10 的设备上 WebView 允许加载 `file://` 资源。主框架跳转已被 `shouldOverrideUrlLoading`（`:1716`）挡住，但**子资源加载不经过该回调**，页面可用 `<img src="file:///...">` 探测本地文件是否存在（存在性探测 / 指纹）。
- **触发条件**：仅 Android 7.0–10（API 24–29）设备 + 加载了恶意/被篡改页面。
- **风险边界**：`allowFileAccessFromFileURLs` 与 `allowUniversalAccessFromFileURLs` 均未开启（默认 false），页面 JS **无法通过 XHR 读取** `file://` 内容，因此只能做存在性探测，不能直接外泄文件内容。**待确认**：是否在实机上验证过子资源探测路径。
- **建议修法**：在 `settings.apply` 块内显式加两行，消除版本差异：
  ```kotlin
  allowFileAccess = false
  allowContentAccess = false
  ```

#### P2-4　`.gitignore` 文件本身不是合法 UTF-8（GBK 编码），违反仓库 `.gitattributes` 的 `* text=auto` 假设

- **文件:行号**：`.gitignore`（全文；非法字节起始于 offset 594，即第 36 行注释 `# 调查期抓取的源站页面快照…`。行号以 `git check-ignore` 的输出为准：`.forensics/` 在第 37 行）
- **问题**：该文件以 GBK/CP936 编码保存，含非法 UTF-8 字节序列（严格 UTF-8 解码在 offset 594 处抛异常）。Git 本身能正常匹配（规则行全是 ASCII，`git check-ignore -v` 结果正确），但：
  - 本仓库 `.gitattributes` 声明 `* text=auto eol=lf`，即假定所有文本文件是 UTF-8；
  - 任何按 UTF-8 读取的工具链（IDE、CI、lint、我本次使用的 read 工具直接报 `invalid UTF-8 text`）会在该文件上失败或显示乱码。
- **触发条件**：CI/编辑器/工具链按 UTF-8 读取 `.gitignore`。
- **影响范围**：**不影响 ignore 规则生效**（已用 `git check-ignore -v` 逐条验证：`keystore.properties`→`.gitignore:16`、`local.properties`→`:5`、`*.apk`→`:9`、`.forensics/`→`:37`），因此不构成安全缺口，仅是可维护性问题。
- **建议修法**：把 `.gitignore` 另存为 UTF-8（无 BOM），提交后 `git check-ignore` 复查即可。**注意**：本仓库另有 12 个 `.kt`/`.xml`/`.md` 文件的中文注释在 GBK 终端下显示为乱码，但它们本身是合法 UTF-8（已全量校验，仅 `.gitignore` 一个文件非法），无需处理。

#### P2-5　R8 已开但未保留/未归档 mapping.txt，release 崩溃栈不可定位

- **文件:行号**：`app/build.gradle.kts:59-61`（minify 配置）、`app/proguard-rules.pro:7-8`
- **问题**：`isMinifyEnabled = true` + `isShrinkResources = true` 已正确开启（`:59-60`），`proguard-rules.pro:7` 保留 `SourceFile,LineNumberTable`——这部分做得对。但 **`mapping.txt` 只生成在 `app/build/outputs/mapping/release/`，而 `build/` 被 `.gitignore:3` 排除，仓库里没有任何崩溃上报平台配置**。release 包一旦线上崩溃，混淆后的类名/方法名（如 `a.b.c()`）无法还原。
- **触发条件**：release 包线上崩溃。
- **建议修法**：至少在每次发布时把 `mapping.txt` 归档到 Release 附件或私有存储（**不要**提交进公开仓库——mapping 会暴露完整的类结构，等于给逆向者画好地图）。若接入崩溃平台，用 `mappingFileUpload` 上传。
- **补充**：`proguard-rules.pro` 的规则对**当前代码**是够的——全仓库无反射（`Class.forName` / `getDeclaredField` / `getDeclaredMethod` 均无匹配）、无 `addJavascriptInterface`（故不需要 WebView JS 接口保留规则）、OkHttp/Okio 的 `-dontwarn` 齐备、Jsoup `-keep class org.jsoup.nodes.**` 已在（R8 seeds 里 755 个 `org.jsoup` 类被保留）。**不存在"把需要的类优化掉"的已确认风险**。

#### P2-6　`dependencyLocking` 与显式版本声明不一致（coroutines-test）

- **文件:行号**：`app/build.gradle.kts:158`（`testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")`）
- **问题**：声明 1.8.1，但 `app/gradle.lockfile` 实际锁定为 `kotlinx-coroutines-test:1.9.0`（被 `kotlinx-coroutines-android:1.9.0` 提升）。声明与实际解析版本不符，`--write-locks` 后仍会显示 1.9.0，读代码的人会误判测试框架版本。
- **触发条件**：无功能故障，纯一致性问题。
- **建议修法**：把声明改为 `1.9.0`，与 `:152` 的 `kotlinx-coroutines-android:1.9.0` 对齐。

#### P2-7　`app/build.gradle.kts` 使用 AGP 内部实现类

- **文件:行号**：`app/build.gradle.kts:77`
- **问题**：`(output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName = ...`。`VariantOutputImpl` 是 AGP 的**内部实现类**（`impl` 包），不在公开 API 契约内，跨 AGP 版本可能被重命名/移除，导致升级 AGP 时构建脚本编译失败。且该转换用 `as?` 静默失败——若 AGP 改了实现类名，`outputFileName` 不会被设置，**未签名包会悄悄退回 `app-release.apk` 这个名字**，正是这段代码要防的发布事故。
- **触发条件**：升级 AGP（例如 8.8/9.0）。
- **建议修法**：改用公开 API `variant.outputs.forEach { it.outputFileName.set(...) }`（AGP 8.x 的 `VariantOutput.outputFileName` 已是公开 `Property<String>`）；或至少把 `as?` 改成 `as` 让失败可见。

#### P2-8　未签名 AAB 的拦截点在依赖任务之后（`bundleRelease` vs `packageReleaseBundle`）

- **文件:行号**：`app/build.gradle.kts:115-126`
- **问题**：`tasks.matching { it.name == "packageRelease" || it.name == "bundleRelease" }`。AGP 8.7 里 AAB 的**实际打包任务是 `packageReleaseBundle`**，`bundleRelease` 是聚合任务且**依赖** `packageReleaseBundle`。Gradle 会先执行依赖任务，因此 `packageReleaseBundle` 已经产出 AAB 文件之后，`bundleRelease` 的 `doFirst` 才抛 `GradleException`。构建最终仍会失败（exit != 0，不会误发布），但 `build/outputs/bundle/release/*.aab` 里已经躺着一个未签名产物，与"绝不让未签名包产生"的意图有偏差。
- **触发条件**：`./gradlew bundleRelease` 且缺少 `keystore.properties`。
- **建议修法**：把匹配条件改为 `it.name == "packageRelease" || it.name == "packageReleaseBundle"`（两者都是产出文件的叶子任务）。
- **附**：`skipSigning`（`:14`）与 `missingKeystoreKeys` 的判断逻辑本身是对的——`assembleRelease` 缺凭据会直接失败，比旧版"缺省退回未签名"安全得多。

#### P2-9　targetSdk 35 适配：`themes.xml` 里已废弃的状态栏属性 + 未声明 `enableOnBackInvokedCallback`

- **文件:行号**：`app/src/main/res/values/themes.xml:4-5`、`app/src/main/AndroidManifest.xml:18-25`
- **问题**（两项均为"未适配但当前不崩"）：
  1. **edge-to-edge 强制**：targetSdk 35 在 Android 15 上强制 edge-to-edge。`MainActivity.kt:42` 已调用 `enableEdgeToEdge()`（正确），但 `themes.xml:4-5` 仍设置 `android:statusBarColor` / `android:navigationBarColor` 为 transparent——这两个属性在 API 35 已**废弃**，会被系统忽略，形成"看起来在配置、实际无效"的误导。建议删除或加注释说明由 `enableEdgeToEdge()` 接管。
  2. **predictive back**：targetSdk ≥ 33 时，未声明 `android:enableOnBackInvokedCallback` 则默认 false，`BackHandler`（`NbaLiveApp.kt:202`）走旧的 `onBackPressed` 分发路径。当前**功能正常**，只是拿不到 Android 13+ 的预测式返回动画。若后续要开，需在 `<application>` 加 `android:enableOnBackInvokedCallback="true"` 并回归验证 `BackHandler` 各分支（搜索页 / 全屏 / 锁定态）。
- **前台服务类型 / PIP / 通知权限：经核查均无未适配项**：
  - 项目**没有任何 Service / startForeground / NotificationCompat**（全仓库 grep 无匹配），因此 targetSdk 34+ 的前台服务类型强制要求**不适用**；
  - 无 `POST_NOTIFICATIONS` 需求（不发通知），不适用；
  - PIP 参数已做完整适配：`supportsPictureInPicture="true"`（Manifest:22）、`setAutoEnterEnabled` 有 `SDK_INT >= S` 守卫（`MainActivity.kt:94`、`:126-129`）、宽高比按系统 `1/2.39 ~ 2.39` 限幅并做 `gcd` 约分（`:151-163`）、`enterPip()` 有 `SDK_INT < O` 与 `FEATURE_PICTURE_IN_PICTURE` 双重守卫（`:113-121`）、`configChanges` 含 `orientation|screenSize|smallestScreenSize|screenLayout`（Manifest:24，PIP 窗口尺寸变化必需）——这部分质量明显高于一般项目。

---

## 二、检查通过项（逐项结论）

### 1. 密钥 / 签名配置是否入库 —— **通过，含历史扫描**

| 检查项 | 结论 | 证据 |
|---|---|---|
| `keystore.properties` 是否被跟踪 | **否** | `git ls-files` 无此项；`git check-ignore -v keystore.properties` → `.gitignore:16` |
| `*.jks` / `*.keystore` 是否被跟踪 | **否** | `git ls-files` 无匹配；工作区递归查找 `*.jks`/`*.keystore` **0 个文件**（密钥在仓库外：`C:\Users\...\Documents\nba-live-keystore\release.jks`） |
| `local.properties` 是否被跟踪 | **否** | `git check-ignore -v local.properties` → `.gitignore:5`；工作区**不存在**该文件（用 `ANDROID_HOME` 环境变量，`G:\android-sdk`） |
| `*.apk` / `*.aab` 是否被跟踪 | **否** | `git check-ignore -v nba-live-1.2.8.apk` → `.gitignore:9`；135 个跟踪文件里无 APK |
| `.gitignore` 覆盖是否完整 | **是** | 第 16-18 行覆盖 `keystore.properties` / `*.jks` / `*.keystore`；第 5 行 `local.properties`；第 9-10 行 `*.apk`/`*.aab`；第 37 行 `.forensics/`（行号均由 `git check-ignore -v` 逐条输出确认） |
| **历史提交是否泄漏过密钥** | **否（重点核查）** | 见下方四项全量扫描 |
| 是否把密码硬编码进构建脚本 | **否** | `app/build.gradle.kts:15-18` 从仓库外 `keystore.properties` 读取；`:46-49` 只引用属性值，无字面量 |

**历史泄漏的四层扫描（这是本项重点，全部做了）：**

1. `git log --all --full-history --oneline -- <path>`，对 `keystore.properties`、`local.properties`、`*.jks`、`*.keystore`、`*.apk` 逐个查：**全部为空**（这些路径从未出现在任何提交中）。
2. `git log --all --pretty=format: --name-only --diff-filter=ACMRD | sort -u` 全历史路径去重，按 `keystore|.jks|.keystore|local.properties|.apk|.aab|.properties` 过滤：只剩 `gradle.properties` 与 `gradle/wrapper/gradle-wrapper.properties` 两个正常文件。
3. `git rev-list --objects --all` 枚举**全部 831 个历史对象**，对每个 blob 做内容正则扫描（`BEGIN PRIVATE KEY` / `storePassword=非空` / `keyPassword=非空` / `AKIA...` / `ghp_...`）：命中项**全部是误报**——
   - `README.md` 的 4 个历史版本：示例里是 `storePassword=...`、`keyPassword=...`、`storeFile=/absolute/path/release.jks`，**占位符，不是真密码**；
   - `app/build.gradle.kts` 的 23 个历史版本：命中 `storePassword`/`keyPassword` 的行都是 `keystoreProps.getProperty("storePassword")` 这类**属性名引用**，不是字面量密码。
4. `git fsck --unreachable`（游离对象，即"提交过又 reset 掉、但对象还在 .git 里"的密钥泄漏高发区）：22 个游离对象，其中 1 个 blob（`7dbafbc8`）命中 `storePassword` —— 内容是旧版 `app/build.gradle.kts` 的 `signingConfigs` 片段，同样是 `getProperty(...)` 引用，**非密钥**。

> 补充：`git remote -v` 确认 remote 是 `https://github.com/lengyueya2000/nba-live.git`（public），因此上述"无泄漏"结论对公开暴露面成立。`keystore.properties` 里的 `storeFile` 绝对路径含开发者用户名/机器名（`Administrator.DESKTOP-V0UO6QO`），但该文件**不入库**，故不构成泄漏。

### 2. WebView 安全 —— **无致命项，2 个 P1 + 2 个 P2**

WebView 只在 `PlayerScreen.kt:1636-1797`（`WebSurface`）一处使用。逐项核对：

| 检查点 | 现状 | 判定 |
|---|---|---|
| `setJavaScriptEnabled` | `javaScriptEnabled = true`（`:1701`） | **必要**（源站 JS 解密 XXTEA，见 `StreamUrlResolver.kt:20-24`），非缺陷 |
| `addJavascriptInterface` | **全仓库无匹配** | **通过**——无原生桥，恶意 JS 无法触达 Kotlin 层 |
| `setAllowFileAccess` | 未设置 | **P2-3**（API 24–29 默认 true） |
| `setAllowFileAccessFromFileURLs` | 未设置（默认 false） | 通过 |
| `setAllowUniversalAccessFromFileURLs` | 未设置（默认 false） | 通过 |
| `setMixedContentMode` | `MIXED_CONTENT_ALWAYS_ALLOW`（`:1706`） | **P2-1**（当前无额外风险，为未来埋坑） |
| `setDomStorageEnabled` | `domStorageEnabled = true`（`:1702`） | 必要（源站播放器依赖），但与 P1-3 叠加需注意 |
| **`onReceivedSslError`** | **无覆写** | **通过**——`WebViewClient` 默认实现对证书错误**取消加载**，本项目没有调用 `handler.proceed()`，全仓库亦无 `TrustManager`/`HostnameVerifier`/`checkServerTrusted` |
| `shouldOverrideUrlLoading` | 覆写（`:1711-1717`），仅拦非 http(s) | **P1-4**（http(s) 跳转全放行） |
| `WebChromeClient` | `onJsAlert` 自动 confirm（`:1748-1756`）、`onJsConfirm` 自动 cancel（`:1759-1767`） | 合理（吞广告弹窗），无安全问题 |
| `setWebContentsDebuggingEnabled` | **仅 `BuildConfig.DEBUG` 下开启**（`NbaLiveApplication.kt:28-30`） | **通过**——release 包不暴露 DevTools |
| WebView 生命周期清理 | `onRelease` 里 `stopLoading` + `loadUrl("about:blank")` + 清 client + `clearHistory` + `destroy`（`:1782-1795`） | 通过（防内存泄漏与上下文泄漏） |

### 3. Manifest —— **通过（权限最小化良好）**

`app/src/main/AndroidManifest.xml`（32 行，逐行核对）：

| 检查点 | 现状 | 判定 |
|---|---|---|
| 权限列表 | **源码只声明 1 个：`INTERNET`**（`:4`） | **通过**（最小化做得很好） |
| 合并后实际权限 | `INTERNET` + `ACCESS_NETWORK_STATE` + `WAKE_LOCK` + 自有 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | 通过——后三者由 `androidx.media3:media3-common` / `media3-exoplayer` 的 manifest 合并而来（已用 `app/build/outputs/logs/manifest-merger-release-report.txt` 逐条确认来源），均为播放器必需，且都是普通权限（无需用户授权、不涉隐私） |
| 危险权限 | **无**（无存储/相机/麦克风/定位/通讯录） | **通过** |
| `android:exported` | `MainActivity` = `true`（`:20`），**这是唯一组件** | **通过**——exported 是 `LAUNCHER` 入口的必需项；该 Activity 的 intent-filter 只有 `MAIN` + `LAUNCHER`（`:26-29`），**不接收任何外部 Intent 数据**（无 `VIEW`/`SEND`/深链），`onNewIntent` 只做 `setIntent`（`MainActivity.kt:70-73`），不存在 Intent 注入面 |
| 其他组件 | 无 Service / Receiver / 自定义 Provider（`androidx.startup.InitializationProvider` 与 `ProfileInstallReceiver` 为库自带，前者 `exported=false`，后者受 `android.permission.DUMP` 保护） | **通过** |
| `android:allowBackup` | `false`（`:8`）+ `fullBackupContent="false"`（`:9`）+ `dataExtractionRules`（`:10`，`res/xml/data_extraction_rules.xml` 对 cloud-backup 与 device-transfer 的 5 个 domain 全部 exclude） | **通过**（三重防护，敏感数据不会随备份外流） |
| `android:debuggable` | **未声明**（release 由 AGP 自动置 false） | **通过** |
| `android:usesCleartextTraffic` | **未声明**，由 `networkSecurityConfig` 接管 | 通过（见 P1-5 的收敛建议） |
| `android:launchMode` | `singleTask`（`:21`） | 通过——注释（`MainActivity.kt:59-69`）说明是为避免叠出两份 Compose 树/两个 ViewModel 导致 `PipState` 打架；本 App 无深链，`singleTask` 不会产生"外部拉起清栈"问题 |

### 4. R8 / ProGuard —— **配置正确，仅缺 mapping 归档**

- `isMinifyEnabled = true`（`app/build.gradle.kts:59`）、`isShrinkResources = true`（`:60`）、`proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")`（`:61`）——**均已开启**，无遗漏。
- `app/proguard-rules.pro`（41 行）覆盖情况：诊断信息保留（`:7-8`）、数据模型（`:12`）、Jsoup（`:14-16`）、OkHttp/Okio/Conscrypt/BouncyCastle/OpenJSSE 的 `-dontwarn`（`:18-23`）、Kotlin 元数据与注解（`:26`）、协程（`:31-38`）。
- **不需要** WebView JS 接口保留规则——项目没有 `addJavascriptInterface`，没有 `@JavascriptInterface` 注解的类（已 grep 确认）。
- **不需要**反射保留规则——全仓库无 `Class.forName` / `getDeclaredField` / `getDeclaredMethod` / `javaClass.getDeclared*`（已 grep 确认）。
- 已用 R8 产物交叉验证：`app/build/outputs/mapping/release/seeds.txt` 里 `org.jsoup` 相关保留项 **755 个**，说明 Jsoup 规则实际生效。
- 唯一缺陷是 **P2-5**（mapping.txt 未归档）。
- `-dontnote **`（`:41`）会吞掉全部 R8 提示，属于"降低噪音但可能掩盖真实问题"的取舍，可接受，仅记录。

### 5. 依赖版本 —— **未发现确定的高危漏洞版本**

`app/build.gradle.kts:128-159` + `app/gradle.lockfile`（234 行，`dependencyLocking { lockAllConfigurations() }` 已开，版本被钉死）：

| 依赖 | 声明/锁定版本 | 评估 |
|---|---|---|
| AGP / Kotlin / Gradle | 8.7.3 / 2.0.21 / 8.9 | 配套正确；wrapper 有 `distributionSha256Sum`（`gradle-wrapper.properties:6`，防供应链篡改）——**做得对** |
| Compose BOM | 2024.10.01（→ Compose 1.7.5、Material3 1.3.1） | 非最新，但**无已知高危 CVE** |
| media3 | 1.9.2（exoplayer/hls/ui/datasource-okhttp/common 全家桶同版本） | 版本一致，本地 Gradle 缓存中存在该版本制品（构建已验证可用）；**未发现已确认高危漏洞** |
| okhttp / okio | 4.12.0 / 3.9.0 | 4.12.0 是 OkHttp 4.x 最新，**无已知高危** |
| jsoup | 1.18.1 | 已包含 CVE-2022-36033（1.15.3 修复）与 CVE-2021-37714（1.14.2 修复）的修复，**不受影响** |
| coil-compose | 2.7.0 | Coil 2.x 末版，无已知高危 |
| kotlinx-coroutines | android 1.9.0 / test 声明 1.8.1（实际锁定 1.9.0） | 见 **P2-6** |
| desugar_jdk_libs | 2.1.5 | 正确（minSdk 24 上 `java.time` 必需，`build.gradle.kts:88` 已开 `isCoreLibraryDesugaringEnabled`） |

> 说明：按"只报能确定的"要求，**没有**把"版本不是最新"当作漏洞上报。上述依赖均未命中我掌握的公开高危 CVE 列表。若要更严格，建议接入 `dependency-check` 或 Dependabot 做持续扫描。

### 6. 日志脱敏 —— **机制正确，有 2 处绕过（P1-1 / P1-2）**

- **机制本身是对的**：`Logs.kt:15-17` 的 `debugLog()` 受 `BuildConfig.DEBUG` 保护，release 包不输出；`:28-42` 的 `redactUrl()` 抹掉 query 与 fragment（签名就在 query 里：`auth_key` / `sign` / `token` / `t`），保留 scheme+host+path 用于排障。
- **`RedactUrlTest.kt` 覆盖到位**（5 个用例）：query 签名不外泄、fragment 也被抹、无 query 原样保留、非法地址不炸、http 明文地址也能脱敏。
- **实际调用点遵守情况**：`HttpClient.kt:67`、`:76`、`MatchRepository.kt:408`、`:640`、`:642-646`、`StreamUrlResolver.kt:61`、`:79` **全部正确使用了 `redactUrl()`**；`HttpClient.kt:64-66` 甚至专门写了注释解释"不打 `e.message` 因为 OkHttp 异常消息内嵌完整 URL"——这个细节说明作者是认真做过这件事的。
- **两个缺口**：`JrsParser.kt:59`、`:69` 直接打原始 `href`（**P1-2**）；`MainViewModel.kt:249/288/387/492` 与 `MatchRepository.kt:82/86` 直接把 `Throwable` 交给 Log（**P1-1**），绕过了 `HttpClient` 建立的那道防线。
- **`redactUrl` 自身的已知局限**（不单独计为发现，因注释 `Logs.kt:39-40` 已显式声明取舍）：只抹 query/fragment，**保留 path**；若源站把签名编进 path（`/live/{sign}/x.m3u8`），不会被抹。当前实测签名在 query，故暂无实际泄漏；若源站改法需重新评估。
- **Cookie / token / Authorization**：全仓库无 `Cookie`、`Authorization`、`auth_key` 的**日志输出**（grep 确认仅出现在注释里）。WebView 的 cookie 不落 logcat。

### 7. targetSdk 35 适配 —— **基本到位，2 个 P2（P2-9）**

- **edge-to-edge**：`enableEdgeToEdge()` 已调用（`MainActivity.kt:42`）；Compose 侧 `Scaffold`（`NbaLiveApp.kt:223`）配合 `statusBarsPadding()`（`:389`、`:475`）与 `navigationBarsPadding()`（`:555`）、`imePadding()`（`SearchScreen.kt:116`）——**主要界面已处理**。残留问题见 P2-9（`themes.xml` 废弃属性）。
- **前台服务类型**：**不适用**——项目无任何 Service / `startForeground` / 通知（grep 确认）。播放完全依赖 Activity 前台 + PIP，因此 targetSdk 34+ 的 FGS 类型强制、targetSdk 33+ 的 `POST_NOTIFICATIONS` 运行时权限均无需适配。
- **PIP**：见 P2-9 末尾的逐项核对——`supportsPictureInPicture`、`setAutoEnterEnabled`（带 `SDK_INT >= S` 守卫）、宽高比限幅 + `gcd` 约分、API 版本与硬件特性双重守卫、`configChanges` 覆盖 PIP 尺寸变化——**无未适配项，质量良好**。
- **predictive back**：见 P2-9 第 2 点。

---

## 三、未完成 / 待确认事项

诚实标注，以下项**未完成**或**无法在只读+离线条件下确认**：

| # | 事项 | 状态 | 说明 |
|---|---|---|---|
| 1 | `file://` 子资源存在性探测的实际可利用性 | **待确认**（对应 P2-3） | 需在 API 24–29 真机/模拟器上构造恶意页验证；本次为只读审查，未运行 App |
| 2 | P1-3 中"源站页面实际是否会跳到任意外域" | **待确认** | 需抓包观察真实广告跳转行为；`.forensics/` 下的页面快照（`jrs_list.html`、`jrkan_index.js` 等，已 gitignore）我扫过一遍未见明文签名，但未逐行分析全部 JS |
| 3 | 依赖 CVE 全量比对 | **未完成** | 仅按我掌握的知识判断，未接入 CVE 数据库/OSV 做全量比对；`media3:1.9.2` 的实际发布信息未联网核实（但本地 Gradle 缓存存在该版本制品且构建成功） |
| 4 | `configChanges` 声明 `density`/`fontScale` 后 Compose 的 `LocalDensity` 是否实时更新 | **待确认** | `AndroidManifest.xml:24` 声明了 `density|fontScale`，理论上不重建 Activity；`androidx.activity:1.9.3` 会在 `onConfigurationChanged` 分发，但未实机验证"改系统字体大小后界面是否立刻重排" |
| 5 | 崩溃平台 / mapping 归档流程 | **未完成** | 仓库内无 CI 配置文件（无 `.github/workflows`），无法确认发布流程是否已手工归档 mapping |
| 6 | 12 个 `res/xml` 与 drawable | **已核对 11 个** | 实际 `res/` 下 xml 共 11 个（任务描述写 12 个）：4 个 mipmap/drawable 图标、3 个 values、2 个 `res/xml`、2 个 drawable。图标与 values 均为纯资源，无安全含义；`res/xml` 两个已逐行审查（见 P1-5 与第二节第 3 项） |

---

## 四、修复优先级建议

1. **立刻**：P1-2（两行改动，把 `$path` 换成 `redactUrl(path)`）—— 成本最低、收益直接。
2. **立刻**：P1-1（统一禁止 `Log.w(tag, msg, throwable)`，改打异常类型）。
3. **本迭代**：P1-4 + P1-3（`shouldOverrideUrlLoading` 加 http(s) host 白名单）—— 两者是同一处代码，一起改。
4. **本迭代**：P1-5（`network_security_config.xml` 加 `domain-config` 收紧已知 https 域名）。
5. **下个迭代**：P2-5（mapping 归档流程）、P2-7 / P2-8（构建脚本 API 与任务名）。
6. **顺手**：P2-3（两行 `settings`）、P2-6（版本号对齐）、P2-4（`.gitignore` 转 UTF-8）、P2-1 / P2-2 / P2-9（加注释 + TODO）。

---

## 五、审查方法与环境（可复现）

- 工具：`pwsh`（PowerShell 7）+ `read` / `grep` / `glob`
- Git 命令：`git ls-files`、`git log --all --full-history -- <path>`、`git log --all --pretty=format: --name-only --diff-filter=ACMRD`、`git rev-list --objects --all`（831 对象）+ 逐 blob `git cat-file -p` 正则扫描、`git log --all -S 'storePassword'/'keyPassword'/'keystore'`（pickaxe）、`git fsck --unreachable`、`git check-ignore -v`、`git grep -n -E '...' <commit>`、`git show HEAD:.gitignore`
- APK 交叉验证：`aapt2 dump badging` / `dump xmltree --file AndroidManifest.xml`、`apksigner verify --print-certs --verbose`（确认 v2 签名有效、单一签名者、证书 SHA-256 `62bd4ee4…52a8`）、`System.IO.Compression` 解包核对 `lib/*/libandroidx.graphics.path.so` 的 ELF `PT_LOAD` 对齐为 `0x4000`（16KB page 兼容，targetSdk 35 相关要求已满足）
- R8 产物：`app/build/outputs/mapping/release/{mapping,seeds,usage,resources}.txt`、`app/build/outputs/logs/manifest-merger-release-report.txt`
- 编码校验：`UTF8Encoding(throwOnInvalidBytes: true)` 对全部 78 个跟踪文本文件逐个严格解码

**再次确认：本次审查未修改任何源码、Gradle 文件、Manifest 或资源文件。`git status` 在审查前后均为干净（除本报告所在的 `review/` 目录为新增未跟踪目录）。**
