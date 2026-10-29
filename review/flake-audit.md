# flake 取证：审计怀疑 #3（时间/线程相关用例会不会假红）

- 来源：`review/audit-guards.md:417-421`（「未验证的怀疑 #3」）
- 任务：`task-17`（flake 统计，owner: guard-fixups）+ `task-18`（判据收紧，owner: guard-fixups）
- 日期：2026-10-29
- **结论先行（第一部分 / task-17）**：**18 轮（10 空闲 + 5 半核负载 + 3 全核负载）× 3 个类 = 54 个「类×轮」全部 0 failures / 0 errors / 0 skipped**，**未复现任何 flake，因此未改动任何测试文件**。
  顺带发现一处**判据过松**（不是假红，而是"红了也说明不了什么"）：`LoadDeadlineTest` 的 `elapsedMs < 5_000` 与它注入的 300ms deadline 差了 16 倍，**它抓不住"deadline 被换成几秒硬编码"这类退化** —— 详见 §6。
- **结论先行（第二部分 / task-18）**：§6 的发现**已落地**。三次**生产单变量回退**实验（§11）：
  ① 旧判据 `< 5_000` + 生产写死 `3_000L` ⇒ 耗时 3.008s **仍然绿**（证实过松）；
  ② 收紧成双边窗口 `elapsedMs in 250L..1_500L` + 同一个回退 ⇒ `实测耗时 3003ms` **红**；
  ③ 生产把 deadline 改**短**到 `100L` ⇒ `实测耗时 104ms` **红**（下限也有牙齿）。
  收紧后复验：正常生产下空闲 1 轮 + 全核负载 3 轮 + 恢复后 1 轮**全绿**（目标用例 0.305~0.306s）。
  改动文件：**只有** `LoadDeadlineTest.kt`（§10）；`MatchRepository.kt` 每次实验后**逐字节恢复**（§11.4）。

---

## 1. 三个被点名的用例（现状事实）

| # | 文件:行 | 机制 | 风险形态（审计原话） | 本轮结论 |
|---|---|---|---|---|
| 1 | `app/src/test/java/com/nbalive/app/data/repository/LoadDeadlineTest.kt:27-45` | 真实墙钟 `System.nanoTime()`；`MatchRepository(loadDeadlineMs = 300L)`；断言 `elapsedMs < 5_000` | CI 慢机上可能假红 | **未复现**，最大观测 0.315s（见 §3.4） |
| 2 | `app/src/test/java/com/nbalive/app/data/remote/HttpClientFetchTest.kt`（6 条） | 真实 loopback `ServerSocket(0)` + 裸 socket 客户端（6 条用例各起一个服务器） | 沙箱/受限网络下可能挂或超时 | **未复现**，沙箱内 loopback 可用；最大 0.550s |
| 3 | `app/src/test/java/com/nbalive/app/ui/StreamSniffOwnershipTest.kt:77-82` | `while (… == null && spins < 10_000) { runCurrent(); Thread.yield() }` 等待真实 IO 落地 | 高负载下自旋耗尽 → 假红 | **未复现**，最大 0.118s；且上界后是 `checkNotNull` **硬失败**，不是静默跳过 |
| 4 | `app/src/test/java/com/nbalive/app/ui/MainViewModelTest.kt:67-75`（**只读记录，未改动**） | 同类 `Thread.yield()` 自旋，等待 `loadSignals` 落地 | 同上 | 归 nav-state 所有；本轮只做静态核对（见 §7） |

仓库里 `Thread.yield()` 自旋**只有这 2 处**（`grep -n "Thread.yield()" app/src/test/java` → 2 命中），`System.nanoTime()` 墙钟断言**只有这 1 处**（同 grep：`System.nanoTime|elapsedMs` → 仅 `LoadDeadlineTest.kt:28,34,37,38`）。

---

## 2. 方法（可复现）

- **宿主**：Windows 10，16 逻辑核，`pwsh 5.1`（注意：PS 5.1 的 `Add-Content/Out-File` 默认 ANSI，本轮的 CSV/日志一律显式 `[System.Text.Encoding]::UTF8`）。
- **Gradle 串行协议**：`$env:TEMP\nba-dsh-gradle.lock`，`[System.IO.File]::Open($lock,'CreateNew','Write','None')` 抢锁，抢不到 `Start-Sleep 5` 重试，`finally` 里 `Close/Dispose` + 删锁；最多等 25~30 分钟（>20 分钟的锁按陈旧删除）。**每次调用都带 `--rerun-tasks`**。
- **每轮先删结果目录**：`Remove-Item -Recurse -Force app\build\test-results\testDebugUnitTest`，跑完再把 XML 拷到该轮独立目录（`$env:TEMP\nba-flake-audit\round-XX-mode\`）。**每轮目录里恰好 3 个 XML**（见 §3.2）⇒ 预删除生效、不存在"编译失败留下旧 XML 虚报"的问题。
- **数字一律从 XML 读**：`[System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($p))` 后正则取 `tests/failures/errors/skipped` 与 `<testsuite … time>`，**不看控制台**。
- **一轮 = 一次 Gradle 调用同时跑 3 个类**（`--tests '*LoadDeadlineTest' --tests '*HttpClientFetchTest' --tests '*StreamSniffOwnershipTest'`），XML 按类分别计数 ⇒ 每轮每类都有独立数字。这样每轮只付一次 Gradle 启动/编译开销，也让 3 个类在同一 JVM 里互相竞争（对 flake 更严，不是更松）。
- **负载构造**：`Start-Job` 起 N 个满核忙等进程（`Get-Date` 限时 + 密集浮点循环），跑完 `Stop-Job/Remove-Job`。
  - 空闲（idle）：无额外负载，10 轮。
  - 半核负载（loaded）：**8 个忙等**（16 核的一半），5 轮。
  - 全核负载（extreme）：**15 个忙等**（≈ 全部核），3 轮。
  - `suite time` 相对空闲的抬升（最大值对比）：`HttpClientFetchTest` **+121%（2.2×）**、`StreamSniffOwnershipTest` **+57%**、Gradle 墙钟从 16~42s 抬到 31~73s ⇒ **负载确实生效**，不是"起了进程但没争用"。
- 原始产物：`results.csv`（15 行 + 9 行 = 24 行，字段 `round,mode,class,tests,failures,errors,skipped,gradleWallSec,suiteTimeSec,message`）、每轮 `gradle.log`、每轮 3 个 XML、`driver.log`。均在 `$env:TEMP\nba-flake-audit`（空闲+半核）与 `$env:TEMP\nba-flake-audit-extreme`（全核）下（未入库）。
- 驱动脚本：`$env:TEMP\nba-flake-driver.ps1`（18 行 CSV 里的前 15 轮）、`nba-flake-driver-extreme.ps1`（后 3 轮）。

---

## 3. 原始数据

### 3.1 每轮计数（54 个「类×轮」全部相同）

| 类 | tests | failures | errors | skipped | 空闲轮 | 半核负载轮 | 全核负载轮 |
|---|---|---|---|---|---|---|---|
| `LoadDeadlineTest` | 3 | 0 | 0 | 0 | 10/10 绿 | 5/5 绿 | 3/3 绿 |
| `HttpClientFetchTest` | 6 | 0 | 0 | 0 | 10/10 绿 | 5/5 绿 | 3/3 绿 |
| `StreamSniffOwnershipTest` | 3 | 0 | 0 | 0 | 10/10 绿 | 5/5 绿 | 3/3 绿 |

**没有任何一条断言消息**（0 failures ⇒ 无 message 可摘录）。

### 3.2 每轮结果目录的 XML 数（预删除生效的证据）

`round-01-idle … round-15-loaded` 与 `round-01-extreme … round-03-extreme`：每轮 `xml=3`，文件名恰为
`TEST-com.nbalive.app.data.repository.LoadDeadlineTest.xml`、
`TEST-com.nbalive.app.data.remote.HttpClientFetchTest.xml`、
`TEST-com.nbalive.app.ui.StreamSniffOwnershipTest.xml`。
（若预删除失效，会看到 60+ 个其它类的陈旧 XML —— 没有出现。）`MISSING` 行 0 条。

### 3.3 每轮 `suite time`（秒，XML 的 `<testsuite time>`）

| 轮 | 模式 | Gradle 墙钟 | LoadDeadlineTest | HttpClientFetchTest | StreamSniffOwnershipTest |
|---|---|---|---|---|---|
| 01 | idle | 38.7 | 0.632 | 0.249 | 0.069 |
| 02 | idle | 41.9 | 0.639 | 0.236 | 0.066 |
| 03 | idle | 32.8 | 0.637 | 0.236 | 0.067 |
| 04 | idle | 33.6 | 0.652 | 0.248 | 0.069 |
| 05 | idle | 38.9 | 0.654 | 0.239 | 0.069 |
| 06 | idle | 26.0 | 0.631 | 0.231 | 0.075 |
| 07 | idle | 33.3 | 0.643 | 0.234 | 0.069 |
| 08 | idle | 32.5 | 0.638 | 0.249 | 0.069 |
| 09 | idle | 35.7 | 0.637 | 0.236 | 0.069 |
| 10 | idle | 16.3 | 0.641 | 0.237 | 0.069 |
| 11 | loaded(8) | 46.7 | 0.635 | 0.275 | 0.085 |
| 12 | loaded(8) | 32.2 | 0.663 | 0.342 | 0.094 |
| 13 | loaded(8) | 32.7 | 0.679 | 0.333 | 0.095 |
| 14 | loaded(8) | 42.9 | 0.649 | 0.327 | 0.099 |
| 15 | loaded(8) | 30.9 | 0.662 | 0.299 | 0.095 |
| 16 | extreme(15) | 72.9 | 0.653 | 0.319 | 0.087 |
| 17 | extreme(15) | 67.7 | 0.691 | 0.488 | 0.111 |
| 18 | extreme(15) | 36.9 | 0.714 | 0.550 | 0.118 |

（第 16~18 轮是**第二次驱动调用**，`round-01/02/03-extreme`，行号连续只是为了便于阅读。）

汇总：

| 类 | 空闲 min/avg/max | 半核负载 min/avg/max | 全核负载 min/avg/max |
|---|---|---|---|
| `LoadDeadlineTest` | 0.631 / **0.640** / 0.654 | 0.635 / **0.658** / 0.679 | 0.653 / **0.686** / 0.714 |
| `HttpClientFetchTest` | 0.231 / **0.240** / 0.249 | 0.275 / **0.315** / 0.342 | 0.319 / **0.452** / 0.550 |
| `StreamSniffOwnershipTest` | 0.066 / **0.069** / 0.075 | 0.085 / **0.094** / 0.099 | 0.087 / **0.105** / 0.118 |

### 3.4 最贴近那条墙钟断言的数据：`LoadDeadlineTest` 单条用例耗时

被测断言所在的用例是「源站整体无响应时 在 deadline 内返回而不是无限等」：

| 用例 | idle 第 01 轮 | loaded 第 15 轮 | extreme 第 18 轮 | 断言上界 |
|---|---|---|---|---|
| 源站整体无响应时 在 deadline 内返回而不是无限等（注入 deadline=300ms） | 0.306s | 0.305s | **0.315s** | `elapsedMs < 5_000` |
| 超时时把两个源都记为失败（注入 deadline=200ms） | 0.246s | 0.256s | 0.269s | —（无墙钟断言） |
| 正常响应远快于 deadline 时不受影响（deadline=60s） | 0.080s | 0.101s | 0.129s | — |

⇒ 观测值 **0.305~0.315s**，与 5000ms 上界相差 **15.9~16.4 倍**；**把机器压到全核饱和也只从 0.306s 挪到 0.315s（+3%）**，因为它测的是 `withTimeoutOrNull` 的**取消**，不是算力。

### 3.5 全核负载下最慢的单条用例（各取 extreme 第 18 轮）

| 类 | 最慢用例 | 耗时 | 相对空闲同类抬升 | 说明 |
|---|---|---|---|---|
| `HttpClientFetchTest` | `429 判为 Rejected 不得重试` | **0.368s** | 其余 5 条 0.026~0.053s | 客户端 `callTimeout=35s` ⇒ 仍有 ~95 倍余量 |
| `StreamSniffOwnershipTest` | `拿不到 referer 时仍然允许升级`（进兜底的那次 IO 往返） | 0.109s | 另外 2 条 0.004~0.005s | 自旋上界 10 000 次 |
| `LoadDeadlineTest` | 见 §3.4 | 0.315s | — | 见 §3.4 |

---

## 4. 结论（逐类，含边界）

### 4.1 未复现，未改动

| 类 | 轮次（类×轮） | 失败轮次 | 结论 | 边界说明（本轮数据之外的残余风险） |
|---|---|---|---|---|
| `LoadDeadlineTest` | 10 idle + 5 loaded + 3 extreme = **18** | **0** | **未复现** | 该用例的耗时由**协程取消**决定：fetch lambda 挂在 `delay(60_000)`，`withTimeoutOrNull(300)` 到点即取消，墙上只多出「把取消后的续体调度回当前线程」的延迟（观测 +6~15ms）。要假红需上界被吃掉 ~16 倍；只有在**单核且被长时间抢占**的极端机器上才有理论可能，本轮 16 核（含全核忙等）无法构造。 |
| `HttpClientFetchTest` | 10 + 5 + 3 = **18** | **0** | **未复现** | loopback 在本沙箱可用（6 条用例各起 `ServerSocket(0)` 全部成功，全核负载下最慢 0.368s）。**真正的失败形态不是 flake 而是"环境不支持"**：若将来的执行环境禁 loopback/禁 `ServerSocket(0)`，该类会**确定性变红**（`serve()` 抛异常），且没有任何 `assume` 兜底 —— 这是**正确红**（暴露"这一层没被测到"），不是假红；但会阻塞整条流水线。另一个理论风险：`连接失败判为 Unreachable` 依赖 `127.0.0.1:1` 连不上。 |
| `StreamSniffOwnershipTest` | 10 + 5 + 3 = **18** | **0** | **未复现** | 等待的是 `loadSignals` 的一次真实 `Dispatchers.IO` 往返（0.066~0.118s，全核负载 +57%），上界是 10 000 次自旋（每次仅 `runCurrent()+yield()`，余量以数量级计）。**即使耗尽也不会静默变绿**：`task-9` 已把这里改成 `checkNotNull(...)` 硬失败（消息带 `spins=`），最坏是"可复现的确定性红 + 明确的诊断文案"，不是 hang、也不是 skip。 |

### 4.2 为什么这次可以说"没证据就不要改"

三例的风险形态都是"慢机/高负载下假红"，而本轮把负载从 **0 → 8 个满核 → 15 个满核（≈ 全核）** 三档都跑过：占比最大的两类（socket 往返、IO 往返）耗时最大只抬升 **121% / 57%**（Gradle 墙钟从 16s 抬到 73s，全核档明显更慢），而它们距各自的上界（socket 用例没有耗时上界、`callTimeout` 35s；自旋用例上界 10 000 次、余量 >100 倍）都差着 1~2 个数量级；`LoadDeadlineTest` 那条墙钟断言的上界余量是 **15.9~16.4 倍**。**没有数据支持"改测试"**（放宽阈值会削弱语义；改成虚拟时钟对这两处不可行，理由见 §6）。按任务口径：**未复现 ⇒ 不为了"看起来更稳"而改测试。**

---

## 5. 全核负载轮（extreme）原始数据

驱动：`$env:TEMP\nba-flake-driver-extreme.ps1`，`Start-Job` 起 **15 个忙等进程 / 16 核**，锁与预删除协议同 §2；结果目录 `$env:TEMP\nba-flake-audit-extreme\`（每轮 `xml=3`；`MISSING` 0 条）。

CSV 原文（`round,extreme,class,tests,failures,errors,skipped,gradleWallSec,suiteTimeSec,message`）：

```
1,extreme,LoadDeadlineTest,3,0,0,0,72.9,0.653,
1,extreme,HttpClientFetchTest,6,0,0,0,72.9,0.319,
1,extreme,StreamSniffOwnershipTest,3,0,0,0,72.9,0.087,
2,extreme,LoadDeadlineTest,3,0,0,0,67.7,0.691,
2,extreme,HttpClientFetchTest,6,0,0,0,67.7,0.488,
2,extreme,StreamSniffOwnershipTest,3,0,0,0,67.7,0.111,
3,extreme,LoadDeadlineTest,3,0,0,0,36.9,0.714,
3,extreme,HttpClientFetchTest,6,0,0,0,36.9,0.55,
3,extreme,StreamSniffOwnershipTest,3,0,0,0,36.9,0.118,
```

⇒ 全核饱和下 **3 轮 / 9 个「类×轮」全部 0 failures / 0 errors / 0 skipped**，`suite time` 最慢 0.714s（`LoadDeadlineTest`），距其断言上界 5s 仍有 7 倍余量。

---

## 6. 只读发现：`LoadDeadlineTest` 的 `elapsedMs < 5_000` 是不是"永远不会红"的守卫？

**判定：不是恒真断言，但**判据过松**，与它注入的 300ms deadline 不匹配 —— 属"红了也说明不了什么"的一类，而不是"永远不会红"。**

- **它确实会红（有真实触发条件）**：如果把整体 deadline 去掉/失效，`loadDeadlineMs` 落到默认 `60_000`（`MatchRepository.kt:359`，构造参数见 `:55`），fetch 挂 `delay(60_000)`，则该用例会在 ~60s 后才返回，`elapsedMs ≈ 60_000 > 5_000` ⇒ **红**。所以它不是摆设。
- **但它抓不住"deadline 值被改坏"**：本用例的意图是"整体必须在**注入的 300ms** 内结束"（KDoc 与 `MatchRepository(loadDeadlineMs = 300L)`），而判据只要求 `< 5_000`。任何"忽略注入值、改用 <5s 的硬编码"（例如退化成固定 3s）都会**照样绿**：`elapsedMs ≈ 3_000 < 5_000`，另外两条断言（`failedProviders` 非空、`matches` 为空）在超时路径下也仍然成立。观测数据把冗余量化了：**18 轮实测 0.305~0.315s，15.9~16.4 倍余量**（§3.4）。
  - ⚠️ 这一条是**代码推理**（我在本轮**没有**改生产去做单变量回退 —— task-17 的写范围不含 `src/main`，且该条被定为只读），不是实验结论。
- **更贴切的判据（建议，本轮未改）**：把"存在 deadline"升级成"**尊重注入的 deadline**"，写成双边窗口，例如
  `assertTrue(elapsedMs in 250..1_500)`（下边 250 防"根本没等就返回"，上边 1 500 仍有 ~4.8 倍余量，远高于本轮三档负载的实测上界 0.315s），
  或者更彻底：断言 `elapsedMs` 与注入值同量级（`elapsedMs < deadline + 1_000`）。
  **不建议**把阈值改成虚拟时钟：`loadMatchesWithOutcome` 的 deadline 跑在 `withContext(Dispatchers.IO)` 里（`MatchRepository.kt:586,595`），`runTest` 的虚拟时钟**不进真实 dispatcher**，只改测试无法把这条路径虚拟化；要做就得改生产（给仓库注入 dispatcher/时钟），超出"只改测试"的范围。
- 若采纳收紧，KDoc 需写清"为什么不能更严"：CI 慢机的调度延迟不可控，`300ms + 1s` 是"能抓住 3s 级退化、又不因 GC/抢占假红"的折中。**这是 follow-up 候选，不是本轮 flake 结论。**
- ✅ **2026-10-29 已落地（task-18）**：单变量回退实验证实了"抓不住"（实验一：生产写死 `3_000L` 时本条耗时 3.008s，旧断言 `< 5_000` 仍然绿），断言已收紧为双边窗口 `elapsedMs in 250L..1_500L`，同一个回退下**变红** —— 确切写法见 §10，三次实验的原始输出见 §11。**§6 上面那段"未改"的表述已被 §10/§11 取代。**

## 7. `MainViewModelTest` 的 `Thread.yield()` 自旋（只读记录，未改动）

`app/src/test/java/com/nbalive/app/ui/MainViewModelTest.kt:67-75`（`waitForLoadSignals`）：

- 静态核对：`while (vm.detailState.value.loadingSignals) { check(spins < 10_000) { … }; spins++; runCurrent(); Thread.yield() }` —— **上界后是 `check` 硬失败**（不是静默跳过/`assumeTrue`），失败文案还写明"IO 线程被饿死，或实现已改成发真实请求"；即便耗尽也是**可诊断的确定性红**。
- 与 `StreamSniffOwnershipTest` 同型，但该文件归 **nav-state** 所有：本任务**未触碰**（`git status` 可核对）。本轮 18 轮统计**不含该类**（不在 task-17 的三类内），所以**它没有自己的重复跑数据** —— 属 §8 的未验证项。若 Lead 要收紧，§4.1 对同型自旋的结论可作参考。

## 8. 没能验证的部分（如实列出）

1. **未在真正的"少核/单核 CI 慢机"上跑过**：宿主 16 核，最极端档是"全核被忙等占满"，与 1~2 核容器的调度行为不完全等价（虽然全核饱和已能放大调度延迟：Gradle 墙钟 16s→73s）。三例的实测余量（16 倍 / ~95 倍 / >100 倍）足以支撑"没有复现证据"，但不能当作"在任何 CI 上都不会红"的证明。
2. **网络受限环境**：本沙箱允许 loopback，所以 `HttpClientFetchTest` 的"禁网即红"分支**没有被实际触发**（我对该分支的判断是代码阅读，不是实验）。
3. **`MainViewModelTest`**（§7）：未做任何重复跑统计（只读），其自旋的 flake 率在本轮**没有数据**。
4. **CPU affinity 钉核实验未做**：本轮用"全核忙等"制造负载，没有把测试 JVM 用 `ProcessorAffinity` 钉到单核（会牵动 Gradle daemon，构造更重）。若要更强的边界证据，这是下一步。
5. ~~**`LoadDeadlineTest` 的收紧判据（§6）只给了方案，未实施**（任务定为只读确认）。~~ → **已由 task-18 实施并验证**（§10/§11）；本项转为已闭环。
6. **task-18 未验证的部分**：① 三次实验每次只跑了 `--tests '*LoadDeadlineTest*'`，**没有在临时回退下跑全量测试**（因此没有"回退下其它类是否受影响"的数据；三个窗口都很短且都在锁内，见 §11.4）；② 只说"3s 硬编码"和"100ms 缩短"两种退化，没有穷举所有 <5s 的硬编码值（2s、4s 的行为按同一机理可推，但**未逐个实测**）；③ 上限 1_500ms 的余量证据来自"15 忙等占满 16 核"档，**不是**真正的 1~2 核 CI 容器（同 §8.1）。

## 9. 改动清单

- **task-17（flake 统计）：未改动任何测试文件** —— `LoadDeadlineTest.kt` / `HttpClientFetchTest.kt` / `StreamSniffOwnershipTest.kt` 当时均保持 `641a8a1` 原始内容（`git status` 里没有它们）。新产物只有本文档与 `$env:TEMP` 下的原始数据（`nba-flake-audit\`、`nba-flake-audit-extreme\`）。
- **task-18（本判据收紧）：只改了 1 个测试文件** —— `app/src/test/java/com/nbalive/app/data/repository/LoadDeadlineTest.kt`（确切写法见 §10）。
- `app/src/main/java/com/nbalive/app/data/repository/MatchRepository.kt` 只在 §11 的三个实验窗口内被**临时**改过，**每次都逐字节恢复**（SHA-256 `387C3F3BE4F6D5716DCD79E24DFE5D4E2116BC901174A11A7269375B20771712`，等于 `HEAD` 的字节；`git status` 干净）。
- 未 `git add/commit/stash`；未碰 `MainViewModelTest.kt`、`ui/**`、`PlayerScreen.kt`、`.github/**`、`README.md`、`AGENT_STATE.md`。

---

# 第二部分：task-18 —— 判据收紧与三次单变量回退实验（2026-10-29）

## 10. 收紧后的判据（确切写法）

文件：`app/src/test/java/com/nbalive/app/data/repository/LoadDeadlineTest.kt`

**① 类 KDoc 新增一段（可执行判据的实测依据）：**

```kotlin
 * ## 判据窗口为什么是 250..1500ms（2026-10-29 实测，见 review/flake-audit.md）
 *
 * 本类注入 `loadDeadlineMs = 300L`，所以「honor 注入值」的实现耗时必然 ≈ 300ms
 * 加上"取消后的续体调度"延迟：
 *  - **下限 250ms（低于注入的 300ms）**：抓「根本没等就返回 / deadline 被改短」。
 *  - **上限 1500ms**：抓「注入值被忽略、退化成几秒硬编码」——
 *    旧的单边 `< 5_000` 抓不住它：实验一（生产写死 `3_000L`）本条耗时 **3.008s，
 *    旧断言仍然绿**；收紧后才变红（实验二）。
 *    同时给慢机留余量：task-17 的 18 轮里（含 15 个忙等进程占满 16 核的全核负载档）
 *    本条耗时最大 **0.315s** ⇒ 上限是实测最大值的 4.8 倍。
 *  - **不建议改用虚拟时钟**：deadline 跑在 `withContext(Dispatchers.IO)` 里
 *    （`MatchRepository.kt:586,595`），`runTest` 的虚拟时钟不进真实 dispatcher，
 *    只改测试无法虚拟化这条路径；要做就得改生产（注入 dispatcher/时钟）。
```

**② 断言本体（`源站整体无响应时 在 deadline 内返回而不是无限等`）：**

```kotlin
        // ⚠️ **双边窗口**，不是单边上界（审计口径：判据过松 = 红了也说明不了什么）。
        // 理由与实测依据见类 KDoc「判据窗口为什么是 250..1500ms」。
        assertTrue(
            "整体必须在**注入的 300ms** deadline 内结束（不是「随便小于几秒」）：" +
                "实测耗时 ${elapsedMs}ms，判据要求落在 250..1500ms 窗口内。\n" +
                "低于 250ms ⇒ 根本没等 deadline 就返回（或 deadline 被改短）；" +
                "高于 1500ms ⇒ deadline 被忽略或改坏（最坏会等近 3 分钟）。",
            elapsedMs in 250L..1_500L,
        )
```

- **下限 250ms 不是拍脑袋**：它**低于**注入的 300ms，而 `withTimeoutOrNull` 只会在 deadline
  到点后返回，所以"真的等了 300ms"的实现不可能落到 250 以下 —— 下限只对"没等 / 被改短"敏感。
- **上限 1_500ms**：实测最大 0.315s（全核负载档）⇒ 4.8 倍余量；同时仍能抓住一切 ≥1.5s 的退化
  （旧的 `< 5_000` 只能抓住 ≥5s 的，抓不住 3s 硬编码）。
- 另外两条断言（`failedProviders` 非空、`matches` 为空）保持不变，语义没有被放宽。

## 11. 三次单变量回退实验 + 复验（原始输出）

### 11.0 方法与纪律

- 生产侧**单变量回退**：`MatchRepository.kt:595` 的 `withTimeoutOrNull(loadDeadlineMs)`
  → 写死常量（**只改这一行**，并加 `// TEMP-REVERT-EXPx` 标记便于核对）。
- **快照/恢复**：实验前 `Copy-Item` 出字节快照并记 SHA-256；**恢复在同一把 Gradle 锁内完成**
  （先跑测试 → 再 `Copy-Item` 覆盖回去 → 校验 SHA → 才释放锁），所以任何队友的 Gradle 调用
  都不可能落在"生产被改过的窗口"里。改文件的动作（edit 工具）与跑测试之间只有几秒间隔，
  且全程只有 `LoadDeadlineTest` 一个类的测试被跑。
- 每轮都：夺 `$env:TEMP\nba-dsh-gradle.lock`（`CreateNew/Write/None`，`finally` 释放）→
  **先删 `app\build\test-results\testDebugUnitTest`** → `--rerun-tasks` → 从 XML（BOM 安全读）取数字。
- 原始证据目录：`$env:TEMP\nba-task18-exp1|exp2|exp3|final`（每轮一份 `gradle.log` + XML）、
  `$env:TEMP\nba-flake-audit-tighten`（复验 4 轮）。

### 11.1 实验一：旧判据 `< 5_000` + 生产忽略注入值（写死 `3_000L`）⇒ **仍然绿（漏）**

```
=== EXP1 result: OLD assertion (elapsedMs < 5_000) + production hardcoded 3_000L ===
gradle exit=0  wall=32.4s
tests=3  failures=0  errors=0  skipped=0      suite time=6.328s
per-testcase:
    超时时把两个源都记为失败                          3.23s
    正常响应远快于 deadline 时不受影响                0.089s
    源站整体无响应时 在 deadline 内返回而不是无限等     3.008s
--- gradle log tail ---
  BUILD SUCCESSFUL in 31s
```

⇒ 注入的是 300ms，实际等了 **3.008s**，旧断言 `elapsedMs < 5_000` **照样通过**。
**"deadline 被改坏"这件事，旧判据抓不住 —— 已被实验证实（不再是 §6 的推理）。**

### 11.2 实验二：收紧后的窗口 + 同样的回退（写死 `3_000L`）⇒ **红**

```
=== EXP2 result: TIGHTENED assertion (elapsedMs in 250..1500) + production hardcoded 3_000L ===
gradle exit=1 (non-zero expected)  wall=43.8s
tests=3  failures=1  errors=0  skipped=0
per-testcase:
    超时时把两个源都记为失败                          3.221s
    正常响应远快于 deadline 时不受影响                0.084s
    源站整体无响应时 在 deadline 内返回而不是无限等     3.009s
<failure message>（XML 解码后）:
  java.lang.AssertionError: 整体必须在**注入的 300ms** deadline 内结束（不是「随便小于几秒」）：
  实测耗时 3003ms，判据要求落在 250..1500ms 窗口内。
  低于 250ms ⇒ 根本没等 deadline 就返回（或 deadline 被改短）；
  高于 1500ms ⇒ deadline 被忽略或改坏（最坏会等近 3 分钟）。
console:
  LoadDeadlineTest > 源站整体无响应时 在 deadline 内返回而不是无限等 FAILED
  java.lang.AssertionError at LoadDeadlineTest.kt:41
  3 tests completed, 1 failed
  BUILD FAILED in 43s
```

⇒ **同一个单变量回退，从"绿"变成"红"**：照相机式的对照（EXP1 vs EXP2 只差判据本身）。

### 11.3 实验三（额外，验证**下限**也有牙齿）：生产把 deadline 改**短**到 `100L` ⇒ **红**

```
=== EXP3: tightened window + production deadline SHORTENED to 100ms ===
gradle exit=1 (non-zero expected)
tests=3  failures=1  errors=0  skipped=0
    超时时把两个源都记为失败                          0.315s
    正常响应远快于 deadline 时不受影响                0.094s
    源站整体无响应时 在 deadline 内返回而不是无限等     0.11s
RED: 源站整体无响应时 在 deadline 内返回而不是无限等
     java.lang.AssertionError: …：实测耗时 104ms，判据要求落在 250..1500ms 窗口内。
```

⇒ 下限不是装饰：只测上限的话，"deadline 被改短"同样逃不掉（104ms < 250ms）。

### 11.4 恢复证据（每次都在锁内完成）

```
EXP1 restore: restored SHA=387C3F3BE4F6D5716DCD79E24DFE5D4E2116BC901174A11A7269375B20771712  byte-exact vs snapshot = True
EXP2 restore: restored SHA=387C3F3B…  byte-exact vs snapshot = True
EXP3 restore: restored SHA=387C3F3B…  byte-exact = True
git status -- MatchRepository.kt : （空 = 干净）
residual marker 'TEMP-REVERT'     : marker absent (good)
lock present                      : False
```

`LoadDeadlineTest.kt` 在实验期间**从未被回退**（它是判据本身，只做收紧），
`HttpClientFetchTest.kt` / `StreamSniffOwnershipTest.kt` 全程未动。

### 11.5 收紧后的复验：正常生产（注入 300ms）必须绿

- **空闲 1 轮 + 全核负载（15 个忙等 / 16 核）3 轮**，全部 `tests=3 fail=0 err=0 skip=0`；
  目标用例耗时 **0.306s（idle）、0.305s、0.305s、0.306s（负载）** ⇒ 距下限 250ms 有余量，
  距上限 1_500ms 有 **4.9 倍**余量。
- **实验二恢复生产后再跑 1 轮**：`tests=3 fail=0 err=0 skip=0`，目标用例 **0.305s**，`BUILD SUCCESSFUL`，exit=0。

（复验原始数据：`$env:TEMP\nba-flake-audit-tighten\results.csv` 与 `round-0x-*\TEST-*LoadDeadlineTest.xml`、
`$env:TEMP\nba-task18-final\`。）