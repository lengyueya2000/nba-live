# 构建 / CI / 仓库卫生 / 测试体系 优化点清单

- 审查员：`rev-build-test`（只读；本文件是唯一写入物）。快照：工作区干净（`git status --porcelain` 0 行，122 提交）。
- 只读对象：配置文件、`app/src/test/**`、以及 `app/build/` 下**既有**产物（reports / test-results / intermediates / outputs）。
- 与既有 95 条审查的关系：先读 `review/coverage-ledger.md`。**已落地/有据取舍的条目不复述、不翻案**；本文件只提新点，或对旧结论给"回归风险 + 证据"。
- 核数口径：单测 **714 条 / 104 suite**（`app/build/test-results/testDebugUnitTest` 求和，与测试源里 714 个 `fun \`…\`` 一致）；执行时间合计 **19.8 s**；release 同样 714/0/0/0。

## Top 5（按价值降序）

| # | 发现 | 价值 | 成本 | 判定 |
|---|---|---|---|---|
| 1 | 发版流水线只核对源码里的 `versionName`，**不核对产物内的版本号，也不核对 `versionCode` 递增** | 高 | S | 已证实 |
| 2 | `BuildScriptHygieneTest` 有 2/7 条断言用**未剔注释的 raw 文本**——注释里留着字面量就能骗过它（本仓自己批判过的形态回潮） | 高 | S | 已证实 |
| 3 | `material-icons-extended`：37.4 MB 的 `classes.jar` 只为 28 个图标；debug 包 22.26 MB / 12 个 dex | 中 | M | 已证实 |
| 4 | 唯一工作流是 tag 触发：日常 push/PR **零 CI**；且 debug 变体从不打包 | 中 | S | 已证实 |
| 5 | lint 不是闸：只有 `lintVital`（fatal-only）跟着 `assembleRelease` 跑，唯一完整报告是 **4 天前**的 | 中 | S | 已证实 |

---

## 一、构建与体积

### 1. 发版流水线不核对产物版本，也不核对 `versionCode` 递增 —— 高 / S / 已证实
**结论**：CI 只把 `app/build.gradle.kts` 里的 `versionName` 与 tag 对一遍；产物里的版本号只"打印"不断言，`versionCode` 根本没有判据 → 发一个 `versionCode` 不递增的包时流水线全绿。
**位置**：`.github/workflows/release.yml:73-82`、`.github/workflows/release.yml:149-158`、`app/build.gradle.kts:37-38`
**原文**：
```bash
VERSION_NAME="$(grep -E '^ *versionName' app/build.gradle.kts | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
if [ "v$VERSION_NAME" != "$GITHUB_REF_NAME" ]; then
  echo "::error::tag $GITHUB_REF_NAME 与 app/build.gradle.kts 的 versionName $VERSION_NAME 不一致 —— 拒绝发版"
```
```yaml
          { "$BUILD_TOOLS/aapt2" dump badging "$APK" > /tmp/badging.txt 2>/dev/null \
              || ... ; }
            head -1 /tmp/badging.txt
```
**影响**：`versionCode = 28`（`app/build.gradle.kts:37`）是**手工**维护的。下次只改 `versionName` 忘 +1，流水线照样全绿并把包发出去。`versionCode` 变小 → 已安装用户 `INSTALL_FAILED_VERSION_DOWNGRADE`，永远收不到更新；不变 → 覆盖安装可行，但商店/自动更新识别不出"这是新版"，而且这个号被占死、回退发版也装不上。**产物版本号其实已经在 `/tmp/badging.txt` 里，只是没拿去比对**——拦一条发布事故的判据，数据就在手边。
**怎么改**：验签步骤里解析 `badging.txt` 的 `versionCode`/`versionName`/`package`，与 tag、与上一版 Release 的 `versionCode` 做单调性断言。

### 2. `BuildScriptHygieneTest` 的 2/7 条断言没有剔注释 —— 注释就能骗过它 —— 高 / S / 已证实
**结论**：同一文件里一条断言正确地用了 `GuardSourceScan.stripNonCode`，另两条却直接 `raw.contains(...)`；而这两个字面量在构建脚本的**注释**里都存在。
**位置**：`app/src/test/java/com/nbalive/app/security/BuildScriptHygieneTest.kt:55-58`（对照 `:42-47` 的正确写法）
**原文**：
```kotlin
        assertTrue("改名判据丢了：必须只处理 `-unsigned.apk` 产物", raw.contains("""endsWith("-unsigned.apk")"""))
        assertTrue("改写目标丢了：未签名包必须变成 app-UNSIGNED-<variant>.apk", raw.contains("app-UNSIGNED-"))
        assertTrue("改名必须调用文件 API 真的落盘", raw.contains(".renameTo(target)"))
        assertTrue("改名必须挂在打包任务上", raw.contains("PackageAndroidArtifact"))
```
命中注释的证据（`app/build.gradle.kts`）：
```kotlin
     //    改名后是 `app-UNSIGNED-release.apk`；            ← 第 81 行，满足上面第 2 条
 // **不继承** `com.android.build.gradle.tasks.PackageAndroidArtifact` —— 所以上面那道 `doFirst`   ← 第 174 行，满足第 4 条
```
**影响**：把 `app/build.gradle.kts:160` 的真改名目标（`File(variantDir, "app-UNSIGNED-${variantDir.name}.apk")`）和 `:135` 的 `withType<PackageAndroidArtifact>()` 删掉，只要注释里的字面量还在，这两条断言仍为真 → 未签名包又会占用 `app-release.apk` 这个发布路径（04-P2-7/8 要拦的发布事故），而**守卫仍是绿的**。这正是 `GuardHygieneTest.kt:7-31` 明文讨伐的"断言命中注释"形态，在同一批硬化里漏了两条。
**怎么改**：`raw` → `stripNonCode(raw)`（两处，成本半小时内）；顺手给 `GuardHygieneTest` 加一条"安全类守卫不许直接对 `readText()` 做 `contains`"的元判据。

### 3. `material-icons-extended`：37.4 MB 输入换 28 个图标 —— 中 / M / 已证实
**结论**：release 侧被 R8 砍到只剩 28 个图标类，但每个 clean build 都要 D8/R8 处理 37.4 MB `classes.jar`；debug 不混淆，这 37 MB 全部进包。
**位置**：`app/build.gradle.kts:237`
**原文**：
```kotlin
    implementation("androidx.compose.material:material-icons-extended")
```
**证据（全部数自既有产物/缓存）**：
- `material-icons-extended-android-1.7.5` AAR = 35,720,998 B，内含 `classes.jar` = **37,425,981 B**；对照 `material-icons-core` AAR 只有 **823,514 B**。
- `app/build/outputs/mapping/release/mapping.txt` 里 `androidx.compose.material.icons.*Kt` 去重后**只有 28 个**：`BrightnessHigh, BrightnessLow, CalendarMonth, Check, Clear, Close, CloudOff, DarkMode, ErrorOutline, Fullscreen, FullscreenExit, Info, LightMode, LiveTv, Lock, LockOpen, MoreHoriz, OpenInNew, Pause, Person, PictureInPictureAlt, PlayArrow, PlayCircle, Refresh, Search, SignalCellularAlt, SportsBasketball, Warning`。
- debug 包 `app-debug.apk` = **22,264,122 B**，`classes.dex` 44,596,064 B + `classes2..12.dex`（未压缩 dex 合计约 66 MB）；release `app-release.apk` = 2,766,693 B（2 个 dex）。
**影响**：① CI 每次带 `--rerun-tasks`（`release.yml:86`），R8 输入固定多 37 MB，发版与本地 clean 构建都更慢；② debug 22 MB / 12 dex，装机与调试循环更重；③ release 体积影响很小（R8 已只留 28 个）。**所以这不是 APK 体积问题，是构建与调试循环问题**。
**怎么改**（M）：28 个里约 9 个在 `material-icons-core`（Check/Close/Info/Lock/Person/PlayArrow/Refresh/Search/Warning），余下约 19 个换成 `res/drawable` 矢量 + `painterResource`，依赖可整个去掉。收益需实测（见第七节第 3 条），但"输入 37 MB → 0"是确定的。

### 4. release 产物逐字节不可复现：已排除两条最常见主因，剩两条候选 + 可执行定位法 —— 中 / M / 推测
**结论**：台账记"同源码两次 `assembleRelease`，大小同为 2,766,693 B、sha 不同"。我用当前产物把两条最常见成因**排除**了，剩下 D8/R8 输出或签名块。
**位置**：`review/coverage-ledger.md:865-866`（原始记录）、`app/build.gradle.kts:54-66`（release 配置）
**原文**（台账 12.7 节）：
```
第一次产出 sha256 `932e9e76…`，第二次产出 `21ab02d9…`，**文件大小完全相同（2,766,693 B）**。
```
**已排除（已证实，证据是对 `app-release.apk` 的字节检查）**：
1. **不是 ZIP 时间戳**：292 个 entry 的 `LastWriteTime` **全部**是 `1981-01-01 01:01:02`（AGP 固定值）。这本是"同大小不同哈希"的头号嫌疑。
2. **不是签名算法随机性**：签名块里命中 `rsaEncryption` OID（`1.2.840.113549.1.1.1`），未命中 `id-ecPublicKey`（`1.2.840.10045.2.1`）→ 用的是 **RSA**，PKCS#1 v1.5 签名确定。（若当初用 EC 密钥，签名每次随机，产物**永远**不可复现——这条值得写进发版文档。）
3. **不是 v1（JAR）签名时间戳**：`META-INF/` 下没有 `.SF`/`.RSA`，只有依赖的 `.version` 文件。
4. **不是 VCS 元数据**：`META-INF/version-control-info.textproto` 全文只有 `revision: "3252466…"`，不含时间/dirty 标记。
**剩余候选（推测）**：① D8/R8 多线程输出顺序；② APK Signing Block 内部字段。
**可验证的定位法**：同一干净工作树连跑两次 `assembleRelease`、输出到两个目录，逐 entry 比 CRC32/长度，并找两文件**首个不同字节偏移**——落在最后一个 entry 之后（Signing Block）→ 签名步骤；落在 `classes*.dex` → dex 生成；落在 `resources.arsc`/`res/*` → 资源编译。再加一组 `--no-build-cache -Dorg.gradle.workers.max=1` 对照，可区分"并行顺序"与"纯算法非确定"。

### 5. `packaging.resources.excludes` 只挡两条，产物里留着构建元数据 —— 低 / S / 已证实
**位置**：`app/build.gradle.kts:103-105`
**原文**：
```kotlin
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
```
**影响**：release APK 里实际还有 `META-INF/README.md`(3,218 B) 与 `META-INF/version-control-info.textproto`——后者把本次构建的 **git revision**（`3252466…`）写进每个发布的包。体积收益约 30 KB（很小），但"发布产物不带构建元数据"是常见纪律红线，收掉零成本。
**怎么改**：`excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/README.md", "META-INF/version-control-info.textproto")`。

---

## 二、CI 缺环

### 6. 唯一工作流是 tag 触发（日常零 CI），且 debug 变体从不打包 —— 中 / S / 已证实
**位置**：`.github/workflows/release.yml:12-17`、`:85-86`、`:128-129`；另：`git ls-files .github` 只有这一个文件
**原文**：
```yaml
on:
  push:
    tags:
      - 'v*'
  # 演练用：把本文件所在 ref 指定成一个已存在的 tag 再手动触发
  workflow_dispatch:
```
```bash
      - name: 两变体全量单测
        run: ./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest --rerun-tasks
      - name: 打包 APK + AAB
        run: ./gradlew :app:assembleRelease :app:bundleRelease --stacktrace
```
**影响**：两处叠加——① `:app:testDebugUnitTest` 是**全仓唯一**的自动化测试入口，只在推 `v*` tag 时跑：714 条用例在两次发版之间一次都不跑，某次重构弄红 100 条要到发版当天才暴露，而那时已在写 `release: vX.Y.Z`、tag 也已推出（`concurrency` 又是 `cancel-in-progress: false`，`:24-26`），修复路径变成删 tag/重推。② 单测只在 JVM 上跑 debug 的**编译**类路径，不做 `packageDebug`：`debugImplementation("androidx.compose.ui:ui-tooling")`（`app/build.gradle.kts:250`）这类只在 debug 存在的依赖、debug 的多 dex 合并坏了，CI 也是绿的。
**怎么改**（S）：新增 `.github/workflows/ci.yml`，`on: [push, pull_request]` 跑 `:app:testDebugUnitTest :app:assembleDebug`（用例本身只占 19.8 s，一次约 1~2 分钟）。

### 7. lint 不是闸：只有 fatal-only 的 `lintVital`，唯一完整报告是 4 天前的 —— 中 / S / 已证实
**位置**：`app/build.gradle.kts:106-121`（`testOptions` 内没有任何 `lint {}` 配置）
**原文**（`app/build/intermediates/lint_vital_intermediate_text_report/release/lintVitalReportRelease/lint-results-release.txt`，全文）：
```
No issues found.
```
**原文**（`app/build/reports/lint-results-debug.xml`，2026-10-27 09:08；release APK 构建于 2026-10-31 20:13）：
```xml
        id="AutoboxingStateCreation"
        severity="Information"
            file="G:\flutter\work\nba\app\src\main\java\com\nbalive\app\ui\screens\PlayerScreen.kt"
            line="204"
```
**影响**：`assembleRelease` 确实带 `lintVitalRelease`（`lint_vital_return_value` 实测为 `0`），所以 **fatal 级别是闸**；但 warning/information 没有任何自动化。这条 `AutoboxingStateCreation` 指向 `PlayerScreen.kt:204` 的 `var controlsTouchSeq by remember { mutableStateOf(0) }`——已知、未修、不会有人被提醒。而那份完整 debug 报告早于当前 1.2.24 代码 4 天（其间 suite 从 97 涨到 104），**不能当现状证据**。
**怎么改**（S）：CI 加 `:app:lintDebug` 并把报告作 artifact；顺手把 `mutableStateOf(0)` 换成 `mutableIntStateOf(0)`。

### 8. 依赖锁定的一致性判据只覆盖一个 artifact，而 lockfile 又锁进了 AGP 内部工具链 —— 低 / S / 已证实
**位置**：`app/src/test/java/com/nbalive/app/security/RepoConfigDisciplineTest.kt:54-58`、`app/build.gradle.kts:265-267`
**原文**：
```kotlin
        val android = versionOf(text, "kotlinx-coroutines-android")
        val test = versionOf(text, "kotlinx-coroutines-test")
        assertTrue("必须能解析出两个版本号（android=$android, test=$test）", android != null && test != null)
```
```kotlin
dependencyLocking {
    lockAllConfigurations()
}
```
**影响**：04-P2-6 那类"声明 1.8.1 / lockfile 实际 1.9.0"只被点名写死在 `coroutines-test` 上；换成 `okio`/`jsoup` 重演同样漂移不会被发现，因为判据不是"遍历 `implementation("g:a:v")` 与 lockfile 同名条目逐个比对"。另：`lockAllConfigurations()` 把 15 个 `_internal-unified-test-platform-*`、`kotlinCompilerClasspath`、`testFixtures*` 等约 80 个 configuration（`app/gradle.lockfile` 末行 `empty=…`）也锁了——锁得住是好事，但每次升 AGP 都重写几十行，真实版本变化被淹没在噪音里。**我逐个核对了 12 条直接依赖（compose-bom 2024.10.01 / core-ktx 1.15.0 / lifecycle 2.8.6 / activity-compose 1.9.3 / media3 1.9.2 / okhttp 4.12.0 / jsoup 1.18.1 / coroutines 1.9.0 / coil 2.7.0 / desugar 2.1.5 / junit 4.13.2 / coroutines-test 1.9.0），当前全部一致。**
**怎么改**：把判据改成"通用比对"（S）；升级 AGP 时把 `--write-locks` 的重写单独一个 commit（写进发版清单即可，不必改码）。

### 9. CI 缺 `timeout-minutes`，而 `concurrency` 又禁止并行取消 —— 低 / S / 已证实
**位置**：`.github/workflows/release.yml:24-32`
**原文**：
```yaml
concurrency:
  group: release-${{ github.ref_name }}
  cancel-in-progress: false
jobs:
  release:
    runs-on: ubuntu-latest
```
**影响**：某一步挂住（网络等待、交互式命令）会一直占到 GitHub 默认 360 分钟上限；同 tag 的下一次触发因 `cancel-in-progress: false` 只能排队 → 发版窗口被堵数小时。加 `timeout-minutes: 30` 即可。

### 10. 没有任何静态检查 / 覆盖率 —— 低 / M / 已证实
**位置**：`app/build.gradle.kts:223-259`（无 ktlint/detekt/JaCoCo）、`build.gradle.kts:1-5`（只有 3 个插件）
**影响**：风格与覆盖率零自动化。"哪些生产代码没被测到"没有数据，所以 `ZeroConsumerGuardTest` 那种"按符号点名"的守卫才会被当作覆盖度替代品（见第三节）。JaCoCo 接入本身是 S，但报告要有人看才有价值，故记 M。

---

## 三、测试体系的成本与脆弱性

### 11. 49 个测试文件各自实现一遍路径解析（38 个自带"候选根"猜测）—— 中 / S / 已证实
**结论**："单测 cwd 是 `app/` 还是仓库根"这个问题被 49 个文件各自回答一次。
**位置**：`app/src/test/java/com/nbalive/app/security/RepoConfigDisciplineTest.kt:92-100`、`app/src/test/java/com/nbalive/app/ui/AppStructureGuardTest.kt:356-367`
**原文**：
```kotlin
    private fun repoFileOrFail(name: String): File {
        val cwd = System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            File(cwd, name), File(cwd, "../$name"), File(cwd, "../../$name"), File(name),
        )
        return candidates.firstOrNull { it.isFile }
```
**量化**：96 个测试文件里 **49** 个出现 `user.dir`、**38** 个自带"候选根 + `firstOrNull` + `error()`"、**35** 个用 `walkTopDown` 自己找生产源码。
**影响**：换执行器（IDE 单测、`--tests` 过滤、`gradle -p app`）或挪一个目录时，这些守卫要么大面积假红（失败信息是"找不到文件"），要么被后来人改写成 `assumeTrue` 后静默变绿。`GuardHygieneTest.kt:35-47` 已经专门立了"禁止 assume 家族"的元守卫，说明这坑被踩过；但兜它的代价摊在了 **46 个文件**的 `error()` 上。
**怎么改**（S，收益最大）：在 `GuardSourceScan` 旁加 `TestPaths.repoFile(name)` / `TestPaths.productionFiles()` / `TestPaths.testFiles()`，一次替换 49 处；各文件的语义断言保持不变。

### 12. 结构"形状"守卫是重构阻力，失败信息指向写法而不是行为 —— 中 / M / 已证实
**位置**：`app/src/test/java/com/nbalive/app/ui/AppStructureGuardTest.kt:45-53`、`app/src/test/java/com/nbalive/app/GuardSourceScan.kt:135-137`
**原文**：
```kotlin
    fun `根组合项函数体不得超过 180 行`() {
        val lines = bodyLineCount(source("NbaLiveApp.kt"))
        assertTrue("fun NbaLiveApp( 的函数体有 $lines 行（上限 180）：...", lines <= 180,)
```
```kotlin
        if (code.substring(cursor + 1, open).contains('=')) {
            error("`$declaration` 看起来是表达式体函数（`= …` 而不是 `{ … }`），本判据不支持")
        }
```
**影响**：`180` 不是需求，是某轮拆分的测量残留；根节点加 5 行**合法**逻辑就红，开发者面前只有两条路——改结构，或把数字改大（改数字＝消掉守卫）。同类还有 `collectAsStateWithLifecycle ≤2`、`whenBodyWithCall` 的"最内层 `when` 必须唯一"、`functionBody` 对表达式体函数直接 `error()`：把 `fun AppPipEffects(...) { … }` 改成 `= run { … }`、把 `when` 改成 `if`，都会以"判据找不到锚点"的形态红——**红的是写法，不是行为**。
**怎么改**：保留"唯一入口 / 搬运不许回写"这类**契约**断言（`definitionViolations` 已覆盖），把行数/数量上限换成可测不变量，预计减 4~6 条。

### 13. 共享扫描器的已知盲区：原样字符串里的 `${…}` 被整体当字符串剔掉 —— 中 / S / 已证实（实现）/ 推测（是否已有实例）
**位置**：`app/src/test/java/com/nbalive/app/GuardSourceScan.kt:30-31`（承诺）vs `:412-424`（实现）
**原文**（承诺）：
```
 * - 字符串模板 `${…}` 里的内容是**代码**，其中嵌套的字符串继续按同样规则处理，
 *   所以写在模板里的调用照样能被抓到；
```
**原文**（实现，原样字符串分支没有 `${` 处理，而普通字符串分支 `:436-440` 有）：
```kotlin
                c == '"' && s.getOrNull(i + 1) == '"' && s.getOrNull(i + 2) == '"' -> {
                    out.append("   ")
                    i += 3
                    while (i < s.length) {
                        if (s[i] == '"' && s.getOrNull(i + 1) == '"' && s.getOrNull(i + 2) == '"') { ... }
```
**影响**：任何写进 `"""…${Foo.bar()}…"""` 的调用（本仓已有 `WebErrorProbeScript`/`WebMediaPauseScript` 这类 JS 片段，将来很容易出现）在 `callSites`/`references` 里数成 0。后果双向：正向守卫（"必须有调用点"）**假红**；反向守卫（"生产里不许出现 `systemDefault()`"这类）**假绿**——后者正是本仓最忌讳的形态。
**验证**：一条内存样本即可（`stripNonCode("val s = \"\"\"${'$'}{a.b()}\"\"\"")` 里 `a.b(` 应为 1）。
**怎么改**：raw-string 分支复用 `appendStripped(..., stopAtCloseBrace = true)`，并把该样本加进 `GuardHygieneTest` 自检。

### 14. 45.4% 的用例是"把 Kotlin 源码当字符串匹配"，但**运行时长只有 5.1 s** —— 中 / M / 已证实
**结论**：成本在维护面，不在速度；因此"为了跑得快"去砍它是错误动机。
**量化（数自 `app/build/test-results/testDebugUnitTest` 与测试源码）**：
- 714 条 / 104 suite，执行时间合计 **19.8 s**；45 个 suite 引用 `GuardSourceScan` = **324 条（45.4%）**，占 **5.1 s**。最慢的 5 个 suite 全是行为/网络级：`LoadOutcomeTest 4.89s`、`MatchRepositoryRetryTest 3.90s`、`DetailCacheTtlTest 2.03s`、`MatchRepositoryTest 1.24s`、`DeadCodeWiringGuardTest 0.79s`。
- **36 条**用例名带"自检"（测的是判据本身）；其中 `自检 注释与字符串里的调用不算接线` 在 **8 个文件**里近乎逐字重复（`NewFeaturesTest.kt:451`、`PipExitWiringTest.kt:89`、`PlayerLoadingHintPolicyTest.kt:86`、`PlayerPageResolverTest.kt:121`、`SniffRecoveryPolicyTest.kt:140`、`SourceToggleTest.kt:311`、`WebPageDeadEndPolicyTest.kt:267`，另有 `CardButtonSemanticsTest`/`ZeroConsumerGuardTest` 的等价版本）。
- 剔注释/剔注释类实现有 **3 份**：`GuardSourceScan.stripNonCode`、`GuardSourceScan.stripXmlComments`、`ReleasePipelineGuardTest.stripYamlComments`（另加 `AndroidConfigVersionTest` 自带的 XML 自检）。
**影响**：真正的成本是"每次重构要同时改代码和一堆文本断言"，以及同一语义在 8 处各写一遍。

### 15. 源码扫描守卫里确实有价值、不要一起砍的部分
1. **反向红线**（一旦放开就是发布/安全事故）：`ReleasePipelineGuardTest.kt:160-192`（mapping 不许进 Release assets，且用**步骤块**判断）、`BuildScriptHygieneTest`（第 2 条修好后）、`CleartextPolicyTest`（该禁的禁/该放的放，双向）。
2. **退化无运行期信号的完整性断言**：`AppStructureGuardTest.definitionViolations`（实现不许回写根文件）、`ZeroConsumerGuardTest`（符号必须有真实消费点）——这类退化编译照样过、界面照样能用。
3. **"同一份实现"纪律**（防两处各写一份判据）：`SourceZoneDisciplineTest`、`JrkanChannelAdmissionTest` 的 `admit` 函数体扫描、`LoggingDisciplineTest`。

---

## 四、测试体系瘦身方案

### 4.1 可合并（约 −35 条用例 + 约 300 行重复代码）
| 动作 | 对象 | 净减 |
|---|---|---|
| 抽 `TestPaths` | 49 处 `user.dir`、38 处"候选根 + firstOrNull"、35 处 `walkTopDown` | 0 条用例，约 −300 行；维护点 49 → 1 |
| 合并"仓库/构建纪律" | `RepoConfigDisciplineTest`(4) + `BuildScriptHygieneTest`(3) + `AndroidConfigVersionTest`(5) + `ReleasePipelineGuardTest`(6) → `RepoGuardTest` + `PipelineGuardTest` | −3 条（重复的 XML/YAML 剔注释自检） |
| 合并重复自检 | 第 14 条点名的那 8 份"注释与字符串里的调用不算接线" | −7 条（留 1 条进 `GuardSourceScanTest`） |
| 合并对比度测试 | `AccentContrastTest`(8) + `LogoPlateContrastTest`(7) + `MatchListContrastDisciplineTest`（同一套 WCAG 算式写三遍） | −2~4 条 |
| 合并"接线"断言 | `PipExitWiringTest` / `SniffRecoveryWiringTest` / `WebPageDeadEndWiringTest` / `SourceEndpointsWiringTest` / `SourceToggleWiringTest` / `PlayerHealthWiringTest` / `PlayerPageResolverWiringTest` 之间重复的"生产必须真的调用 X" | −15~20 条 |

### 4.2 可删（约 12~18 条）
- `AppStructureGuardTest` 的 `根组合项函数体不得超过 180 行` + 它的 4 条结构自检中的 2 条（第 12 条）；`collectAsStateWithLifecycle ≤2` 同属数量阈值，一并删；**保留**"实现不许回写根文件"。
- `NavHostGuardTest`(12) 中与 `AppStructureGuardTest` 重叠的"根节点状态收集 / 占位宽度"断言。
- `BuildScriptHygieneTest` 的两条纯锚点（`raw.contains("android {")`、`raw.contains("dependencyLocking")`）并进"文件找到了"一条。

### 4.3 可替换成行为级（约 20~30 条，收益最实）
- `PrefsBoundaryGuardTest`(9)：现在是"UI 文件里不得出现 `prefs.put*` 文本"；`PrefsStore` 可注入，用 fake 断言"UI 调 `setDarkMode` 后落到 store"，9 条里至少 5 条能转成真行为。
- `DeadCodeWiringGuardTest`(14)：14 条全是"生产里必须有这个调用"。台账§四 A 类已证明**行为用例能覆盖同一批**（如"从播放页返回后详情晚到不覆写"，删生产的 `detailJob?.cancel()` 恰好 1 红）→ 保留 4~5 条"唯一入口"，其余转行为。
- `ZeroConsumerGuardTest`(6)：保留"某符号不得被零消费"的反向判据；正向"必须被消费"在有行为用例处（如 `PipExitWiringTest` 那套"effect 真的消费请求"）改行为断言。
- `GuardHygieneTest`(6)：`assume` 家族禁令与非空性锚点必留；`扫描器自检 能抓到违规调用…` 与 `不许用 Kotlin 原生 assert` 可合并（现在 3 条在测同一件事的三种输入）。

### 4.4 估算收益
- 用例数 **714 → 约 610~630**（−12%~−15%）；测试源码 19,972 行 → 约 **16,500~17,500 行**（−13%~−17%）。
- 维护点：路径解析 49 → 1；剔注释实现 3 → 1；"注释不算接线"自检 8 → 1。
- **运行时长几乎不变**（源码扫描只占 19.8 s 里的 5.1 s）——这次瘦身买的是**维护面**，不是速度；不要为"更快"扩大改动范围。

---

## 五、仓库卫生

### 16. 大文件确实都没入库；但"归档"只活在这一份工作区 —— 中 / S / 已证实
**位置**：`.gitignore:9,19`、`README.md:82-84,91-94`、`.github/workflows/release.yml:160-165`
**原文**（`README.md:91-92`）：
```
> ⚠️ **`mapping.txt` 必须和 APK 一起归档**：R8 混淆后线上崩溃堆栈只剩 `a.b.c`，
> 用它才能还原成真实类名与行号。丢了就等于这个版本永久失去可诊断性。
```
**实测**：`git status --porcelain` **0 行**；`git count-objects -vH` 的 `size-pack` 只有 **29.61 MiB**；仓库根有 `nba-live-1.2.13/14/15/16-mapping.txt`（各 ~44.5 MB，合计 178 MB）+ 5 个 `.apk`，逐条 `git check-ignore -v` 命中 `.gitignore:19 *-mapping.txt` / `.gitignore:9 *.apk`，均**不在** `git ls-files` 里。
**影响**：风险不是误提交，而是**归档脆弱**——一次 `git clean -xfd`（换机器、重置工作区）就让 1.2.13~1.2.16 的混淆映射永久消失，而这四个版本仍在线、仍可能崩；CI 侧 mapping 只作 workflow artifact（`release.yml:160-165`），GitHub 默认保留 90 天，到期同样没有。
**怎么改**（S）：把 `nba-live-*-mapping.txt` 压成 zip 放仓库外离线介质并记校验和；在 README/`AGENT_STATE` 写明"artifact 有保留期，本地副本是唯一长期归档"。

### 17. `design/**/out/` 与 `design/minimal/tmp/` 截图入库，与 `.gitignore` 自己的声明矛盾 —— 低 / S / 已证实
**位置**：`.gitignore:38-40`
**原文**：
```gitignore
# 真机/模拟器测试截图（本地留存，不入库）
design/app-run/
build-shots/
```
**实测**：`git ls-files design` = **54 个文件 / 7.12 MB**，其中 `design/cinema/out` 13 个、`design/minimal/out` 12 个、`design/minimal/tmp` **6 个**（`atl.png`/`bos.png`/… 明显是中间切图）、`design/sportcard/out` 7 个，多为 300~700 KB PNG。
**影响**：同类产物一半入库（`design/*/out`）一半不入库（`build-shots/`），纪律自相矛盾；改一次设计就多一批二进制 blob，clone 与历史体积只增不减。
**怎么改**：`git rm --cached -r design/*/out design/minimal/tmp`，`design/README.md` 里写明截图外链存放。

### 18. 生产文件把 git-ignored 的抓包文件当证据引用 —— 低 / S / 已证实
**位置**：`app/src/main/res/xml/network_security_config.xml:8-9`、`.gitignore:43`
**原文**：
```
    - 源站播放页本身就是明文：实测抓包
      `.forensics/detail_364127.html` 里 `toLive` 的 data-url 是
```
**影响**：这条是**全局放行明文**这个安全豁免的理由，但依据指向被 `.gitignore:43` 排除的 `.forensics/`（实测文件在磁盘上、不在库里）。换机器/后来者无法复核，注释会慢慢变成"传说"——而同一文件 `:27-38` 记录过"因为搞错谁在加载这台主机的脚本"导致的**线上黑屏**。
**怎么改**：把关键几行（`toLive` 的 data-url 片段）剪进注释或 `docs/`，别指向 ignored 路径。

---

## 六、我读到但认为不值得改的

- **keystore 从未入库**：122 个提交里 `git log --all --diff-filter=A -- keystore.properties '*.jks' '*.keystore'` 输出为空，`git ls-files` 里无任何 keystore 条目，`.gitignore:22-24` 覆盖三种形态——**没有泄漏风险需要处置**。
- **`gradlew` 执行位**：`git ls-files -s gradlew` = `100755`，且 `release.yml:57-59` 自己 `chmod +x`（双保险）。台账"模式位已修"的结论复核通过，不必再加守卫。
- **Gradle wrapper 供应链**：`gradle-wrapper.properties` 钉了 `distributionSha256Sum=d725d707…`（gradle-8.9-bin 官方值），比多数工程严，不动。
- **`unitTests.all { it.systemProperty("user.timezone", "UTC") }`**（`app/build.gradle.kts:120`）：把"只在东八区才绿"变成"任何机器都绿/都红"的常驻守卫，是净收益，不要为"贴近设备时区"撤掉。
- **`settings.gradle.kts` 的 `FAIL_ON_PROJECT_REPOS` + google content filter**（`:5-21`）：写法正确，不动。
- **53 个依赖的 `META-INF/*.version` 文件**：标准做法（资源压缩不覆盖 `META-INF/`），删了会破坏依赖元数据，不动。
- **`AndroidManifest.xml:16 enableOnBackInvokedCallback` + `values-v35` 双份主题**：台账 04-P2-9 已判"有据的取舍"，我复核现状一致，不翻案。
- **`.gitattributes`（`* text=auto eol=lf` + 二进制白名单）**：与 `git status` 干净的事实一致，无问题。
- **"178 MB mapping 会不会被误提交"**：不会——`git add -A` 会被 `.gitignore:9,19,40,43` 挡住（逐条 `git check-ignore -v` 已验证）；真正的问题只是归档脆弱（第 16 条）。

## 七、需要真跑构建 / CI 才能定的

1. **`release.yml` 从未在真实 runner 上跑过**（122 提交里只有这一个工作流；台账 §04-P2-5 与 §12 都自认）。我做的全是静态核对：`ReleasePipelineGuardTest` 全绿只证明"YAML 文本里有这些步骤"，**不等于流水线可用**。待定项：`ubuntu-latest` 上的 `ANDROID_HOME` 与 build-tools 路径、`gh` 可用性、`sort -V | tail -1` 选中的 build-tools 版本、四个 secret 是否齐全。第一次 `gh workflow run release.yml --ref <已存在 tag>` 才有答案。
2. **第 4 条（不可复现）的成因**：需同一工作树连跑两次 `assembleRelease`，逐 entry 比 CRC + 定位首个差异字节；再用 `--no-build-cache -Dorg.gradle.workers.max=1` 做对照。
3. **第 3 条的收益量化**：debug 22.26 MB 里 icons-extended 占多少——需要一次干净对照构建（或 `apkanalyzer dex packages app-debug.apk` 按包聚合）。
4. **`-Xmx2048m` 是否够 R8**：需一次 clean release 构建看是否 OOM（我不跑 Gradle，以免占锁）。
5. **configuration-cache 的收益**：CI 与本地各计时两次才有意义。
6. **完整 lint（warning/information）现状**：唯一报告是 4 天前的 2 条（`OldTargetApi` + `AutoboxingStateCreation`），需跑一次 `:app:lintDebug` 才能确认当前是否真的只有 2 条。
7. **第 13 条是否已在生产里"真的踩到"**：我没有穷举 `src/main` 里是否已有 `"""…${…}…"""` 形态的调用（倾向于是 0，故未按"已发生"计），需一次全仓扫描确认。