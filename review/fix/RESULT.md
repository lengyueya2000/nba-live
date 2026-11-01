# 修复 + 实机验证结果（75 条审查项 · 最终版）

- 起点：`HEAD = ba6b247`（v1.2.24，工作区干净）→ 检查点提交 `57eb33a` → 本轮最终状态
- 审查依据：[optimization-review.md](../optimization-review.md) + 四份分册 + [对抗式复核](../opt/verification.md)
- 修复台账：`review/fix/{data,player,ui-state,test-slim,test-reconcile}.md`；本文件是**总账**

## 1. 最终结论

**75 条：73 条全落地 / 2 条部分落地 / 0 条未落地**（起点是 65/5/5；用户要求"继续做完剩余项"后，B3、B10、B11、B12、B5、P7、P9 全部补齐，D10 又补抽一层）。

| 验收项（全部由 Lead 实跑） | 结果 |
|---|---|
| `:app:testDebugUnitTest` | **844 通过 / 0 失败 / 0 跳过**（120 suites） |
| `:app:testReleaseUnitTest` | **844 通过 / 0 失败** |
| `:app:assembleDebug` | 成功 · **14.73 MB**（B3 之前 **21.26 MB**，**−31%**） |
| `:app:assembleRelease` | 成功 · 2.65 MB（无 `app-UNSIGNED-*`、**无** `version-control-info.textproto`） |
| `:app:lintDebug`（CI 阻塞步骤） | 成功（3 warning / 0 error） |
| `:app:jacocoDebugUnitTestReport` | **行覆盖 45.0%**（3548/7891）、指令 34.7%、方法 51.9%、分支 25.1% |
| 雷电模拟器 · debug 最新包 | 装机/冷启动 1404 ms/列表/我的/比分/播放 全通，**0 次 `FATAL EXCEPTION`** |
| 雷电模拟器 · release 最新包 | 装机成功、冷启动 470 ms、无崩溃 |

判据总数：**714 → 844（+130 条）**；本轮新增 13 个测试文件（`AppIconsParityTest`、`CiWorkflowGuardTest`、`LogCallDisciplineTest`、`TestPaths`、`JrsDetailCacheTest` 等）。

## 2. 三条"看起来做不到、最后做到了"的条目

| 条目 | 最终做法（都比原报告的"怎么改"更进一步） |
|---|---|
| **B5** 发布产物带构建元数据 | 原判"只能用 AGP 内部类、故放弃"。**用 `javap` 反查字节码**发现：旧 Gradle 属性 `android.enableVcsInfo` 已被 AGP 弃用，提示原文写明改为**公开 DSL**：`BuildType.vcsInfo { include = false }`（`com.android.build.api.dsl.VcsInfo` 是公开接口）。落进 release 构建类型后**逐 entry 验证产物**：`version-control-info.textproto` 与 `META-INF/README.md` **双双消失**，且**没有碰任何 AGP 内部实现类**（沿用 04-P2-7 的教训）。 |
| **B3** 图标依赖 35.7 MB | 原判"手绘 25 个图标无法验证、风险过高"。改走**从官方 AAR 字节码逐命令还原**（`review/fix/icon-extract.py`：解析 `javap -c` 的 `PathBuilder.moveTo/lineTo/curveTo/…` 与浮点常量）→ 25 个图标生成为本地 `AppIcons`，11 个继续用 `material-icons-core`。**验证不靠眼睛**：`material-icons-extended` 降级为 `testImplementation`（不进 APK），新增 `AppIconsParityTest` 把每个本地图标与官方 `ImageVector` **逐字段比对**（viewport/autoMirror/每个 path 块/每条命令的每个参数/fill/alpha）。**这条判据当场抓出两个真实差异**：① 官方图标常常由**多个 `path{}` 块**组成，我第一版把命令合并成一条 path（填充规则下**渲染会不同**）；② `strokeLineWidth` 官方是 1.0f、DSL 默认 0f。修完 25/25 全等。收益：debug 包 −6.5 MB。 |
| **B11** 99 处路径解析 | fix-ui-state 完成主体（88 处统一 + 28 个函数委托，`user.dir` 从 99 处降到 1 处），但它把 6 个"只读目录"里的 11 处留给了我。用 `review/fix/b11-leftover.py` 做**逐模式等价替换**收尾：现在全仓**只有 `TestPaths.kt` 读 `user.dir`**。 |

## 3. 剩余 2 条部分落地（如实报告）

| 条目 | 落地程度 | 为什么停在这里 |
|---|---|---|
| **D10** 拆 `MatchRepository`（1180+ 行） | 已抽出 **2/3**：`MatchMerger`（纯合并判据）+ `JrsDetailCache`（TTL/LRU/single-flight/失效，时钟可注入）；`LoadOrchestrator`（两源并发编排）未抽 | `MatchRepository` 的对外签名**一个没改**、3 条反射钉私有实现的既有测试已重锚成**行为断言**（TTL 用注入时钟、LRU 用"第 0 场已淘汰/第 199 场仍命中"、排序走公开 `agedMatches`）。再抽 `LoadOrchestrator` 要动的是**并发与超时编排**（H3/D9 刚修好的那一段），在"844 条判据全绿 + 已过真机"的状态下继续拆属于独立一轮。 |
| **B14** 测试体系瘦身 | **维护点目标达成**：`user.dir` 99→1、重复自检 8→1、三套 WCAG 算式复核后本就只有一套。**行数目标未达成且不该按原口径衡量**：本轮同时新增 **130 条判据**，测试源码 19,972 → **24,694 行（+23%）** | 原报告估"714 → 610~630 用例、−13~17% 行"是在**不新增判据**的前提下算的；本轮把 75 条修复的守卫一起加进来，两个目标互相矛盾。剩余的"7 个接线 suite 断言合并 + 2 组行为级试点"落点与顺序已写进 [test-slim.md](test-slim.md) §3.3，属独立一轮。 |

## 4. 雷电模拟器实测证据（改动后的最新包）

设备：`nbatest`（LDPlayer / Android 14 / 1080x1920 / 480dpi）。中途两个实例同时卡死（`adb devices` 显示 `device` 但 `adb shell` 无响应）——已通过 `ldconsole reboot --index 1` 恢复并复测。

| 验证 | 结果 |
|---|---|
| 装机 / 冷启动 / 崩溃 | debug 冷启动 **1404 ms**；release **470 ms**；全程 **0 次 `FATAL EXCEPTION`** |
| 列表（换图标后） | 6 场（NBA 4 / CBA 2）渲染正常；「我的」页 22 个文本节点全在（图标最多的页面，布局未被 B3 破坏） |
| **H7 后台不再轮询（回归复测）** | 前台基线正常 → **按 Home 200 秒新增 0 次** → 回前台 **60 秒内 2 次** |
| 比分链路 | logcat `ESPN 比分板 4 场，匹配上 4 场`（未开赛时为"匹配上 0 场"——正确降级） |
| 播放页（P9 复用播放器之后） | 点比赛 → chromium 加载 → `ExoPlayerImpl Init` → 404（已结束场次）→ 错误处理无崩溃 |
| **恢复条 + H6 重新加载（端到端）** | 恢复条渲染 → 点「重新加载」→ logcat `WebSurface: reload requested #1` + `dead-end bar -> hide` → **CDP 里 WebView URL 从 `paps.html?id=EcThdK…` 变成 `paps.html?id=rLksSY…`（新签名）**；且这次是在**复用同一播放器**（P9）的前提下完成的 |
| **U3 只有赛事页轮询** | 「直播」页停留 130 s → **0 次**；切回赛事 70 s → **2 次** |

## 5. 本轮新增/重写的守卫（防回退）

- **H1**：`release.yml` 新增"核对**产物**版本号 + `versionCode` 严格递增"步骤（上一版 Release 的 APK 下载复读），`ReleasePipelineGuardTest` 加**步骤块级**判据。
- **H2**：`GuardSourceScan.stripComments`（只剔注释、保留字符串字面量）+ `BuildScriptHygieneTest` 改用它，并加**负控**（"只含注释的样本不得满足判据"）。
- **H5**：`AppStructureGuardTest` 的收集数守卫重写为"根体内直接收集 ≤1 + 必须走 `rememberAppUiState` + `AppUiState` 字段 ≤6 + 收集数 ≤ 字段数"——**搬文件绕不过去**。
- **B3**：`AppIconsParityTest`（25 条逐字段等价比对 + 反射可用性自检 + "改一个数必须被发现"的自检）。
- **B6/B7**：`CiWorkflowGuardTest`（push/PR 触发、两变体 + `assembleDebug` + lint、超时）；`.github/workflows/ci.yml`。
- **B13**：`stripNonCode` 的原样字符串分支现在处理 `${…}`；`GuardHygieneTest` 加自检样本。
- **U20**：`LogCallDisciplineTest`——`ui/` 与 `MainActivity` 不许裸调 `Log.`（`Logs.kt` 新增两参 `safeLogWarn` 出口）。
- **P9/P11**：`PlayerFixTest` 的两条按"完整版"重锚（`key(streamUrl,…)` 必须消失、`setMediaItem` 只许 2 处且不在 `update` 里、`LineSwitchPolicy.decide` 的四条行为断言）。
- **P7**：`PlayerLoadingHintPolicyTest` 按新形状重锚（`fun PlayerScreen(` 体内不许再有 ticker 与 `shouldShow`）。

## 6. 诚实边界

1. **未做真机量化**：H8/U6/U10/U13 的重组与分配、U7 的字号裁切、U8 的主线程读盘毫秒数（验证法在各分册）。
2. **未做真机行为验证**：P3 通话恢复、P2/P5 的误判率、P9 换线延迟的收益量级、D4 源站真实 `data-link` 形态、D9 真实限流频率。P9 的**正确性**由判据 + 真机"换线后仍能拿到新签名并起播"覆盖，**收益大小未测**。
3. **CI 仍未在真实 runner 上跑过**：`ci.yml` / `release.yml` 只做静态核对 + 本地等价命令实跑（两变体单测 / 两个产物 / lint / 覆盖率全绿）。
4. **release 逐字节不可复现**仍是未解现象（B4 按原口径只交付定位法：逐 entry CRC + 首个差异字节偏移）。
5. **中途两次半成品事故**（两名队友在写一半时失败）已修复并把当时的工作区状态提交为检查点 `57eb33a`；这也是本轮加"每完成一批就提交"纪律的原因。
6. 本轮改动**已提交**为检查点 + 最终两个提交（见 §7）；`git status` 干净。

## 7. 复现命令

```powershell
# 全量验收
.\gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease --rerun-tasks
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:jacocoDebugUnitTestReport        # 覆盖率 → app/build/reports/jacoco/…

# 图标集再生成（B3；只在需要改图标时跑）
python review/fix/icon-extract.py <extended 解包目录> <core 解包目录> app/src/main/java/com/nbalive/app/ui/icons/AppIcons.kt
python review/fix/icon-migrate.py app/src/main/java

# 真机
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 logcat -s Scores            # H7：前台 45s 一次；按 Home 后应再无新增
adb -s emulator-5556 forward tcp:9222 localabstract:webview_devtools_remote_<pid>
curl -s http://127.0.0.1:9222/json/list           # H6：点「重新加载」后 paps id 应变化
```