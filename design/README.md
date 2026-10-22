# `design/` — HTML 设计原型目录

本目录是 **NBA Live**（Kotlin + Jetpack Compose）App 的 UI 设计原型与配套截图工具。
仓库根 README 指向的"深色运动卡片设计语言"就是这里的 **`design/sportcard`**。

> 这些 HTML **不是** App 的运行代码，只是设计稿 + 截图工具。
> 真正被编译进 APK 的是 `app/src/main/java/com/nbalive/app/ui/**` 下的 Compose 实现。

---

## 目录一览

| 路径 | 说明 | 状态 |
|---|---|---|
| `sportcard/` | **方案 B · 运动卡片风** —— 已选定为 App 视觉方向并落地到 Compose | **主线** |
| `cinema/` | 方案 A · 影院级沉浸（OLED 深黑 + 播放红） | 视觉探索，参考留档 |
| `minimal/` | 方案 C · 极简信息流（单列紧凑行 + 细分割线） | 视觉探索，参考留档 |
| `compare.html` / `compare.png` | 三套方案并排对比页（历史页面） | 参考 |
| `_shared/DESIGN-BRIEF.md` | **唯一事实源**：产品定义、硬性约束、真实数据样本、判定标准 | 必读 |
| `_shared/shoot.mjs` | 通用截图工具：HTML → 390×844 PNG | 工具 |
| `_shared/shoot-compare.mjs` | 给 `compare.html` 整页截图 | 工具 |
| `_shared/chrome.mjs` | 浏览器可执行文件查找（Chrome / Edge / Playwright Chromium） | 工具 |
| `_shared/probe-players.mjs` | 探针：抓源站播放页结构，判断某条线路是不是 `/play/sm.html` | 工具 |
| `_shared/selftest.html` | 截图工具自测页 | 工具 |
| `*/out/` | 各方案的历史截图产物 | 产物 |

---

## 三份原型：**有意不同步**

`cinema` 与 `sportcard` 共享约 23% 的 JS 行与 31 个 CSS class token，
`minimal` 更进一步——把同一批赛事数据重新手写成静态 HTML。
合并它们是**有风险的**，而且**没有必要**：只有一套方案进了产品。

因此：

- **`sportcard` 是主线。** 它是唯一与 `app/` 里的 Compose 实现保持语义一致的原型：
  设计 token、组件划分、屏幕结构以 `sportcard/README.md` 为准。
- **`cinema` 和 `minimal` 是视觉探索留档。** 它们用于回答"要不要走影院沉浸风 /
  极简信息流风"，结论已经有了（选 B），所以这两份**不再与实现同步维护**。
- **不要试图把三份拉齐。** 已知且被接受的差异包括：

  | 差异 | cinema | sportcard | minimal |
  |---|---|---|---|
  | 联赛筛选计数 | 无 | 有（由数据实时统计） | 无 |
  | 浅色主题 | 有 | 有 | 有（`html[data-theme]`） |
  | 底部导航元素 | `<button>` | `<button>`（原先是 `<div>`，已修正） | `<button>` |
  | 列表渲染方式 | 数据驱动 + 可筛选 | 数据驱动 + 可筛选 | 数据驱动 + 可筛选 |
  | 事件/工具 | `window.__SHOW_SCREEN` | `window.__SHOW_SCREEN` | `window.__SHOW_SCREEN` |

  如果你只想改**会影响产品**的东西 → 改 `sportcard/` 与 `app/`。
  如果你只想改**留档的视觉探索** → 只改对应那一份，别动另外两份。

### 共同约定

三份原型都遵守：

- 通过 `window.__SCREENS` 声明自己的屏，`window.__SHOW_SCREEN(id)` 切屏，`window.__SET_THEME(theme)` 切主题。
- 视图尺寸 390×844（`deviceScaleFactor = 2`）。
- 底部导航 3 项：**赛事 / 直播 / 我的**，都能真正跳屏。
- 联赛筛选：**全部 / NBA / CBA**（与 `League.filters` 一致），真正按数据重新渲染列表。

---

## 数据放在哪里

**每份原型自带一份**，互不共享：

| 文件 | 常量 | 内容 |
|---|---|---|
| `cinema/index.html` | `T` / `LEAGUE` / `LIVE_GAME` / `GROUPS` / `LINES` | 队徽表、联赛名、直播大卡、赛事分组、线路表 |
| `sportcard/index.html` | `T` / `L` / `GROUPS` / `LINES` | 同上（字段名略有差异） |
| `minimal/index.html` | `TEAMS` / `MATCHES` | 队徽表 + 赛事数组 |

改动数据时**同时改这三处**（它们已经不同步，见上文）。

数据真值来自 `_shared/DESIGN-BRIEF.md` §6，源站地址与队徽 URL 只用那里列出的那些。
**不要编造**：比分、比赛时钟、分辨率（1080P/720P）、线路可用性（可用/速度慢）源站都没有。

### 线路播放方式怎么标

只有源站 `/play/sm.html` 链路能静态解析成 m3u8 直连；JRS 的 `mgxl`/`pao` 与
JRKAN 的 `wen`/`pao` 是 XXTEA 加密 / 需令牌，只能由 App 内置 WebView 承载。
所以每条线路标的是 **播放方式（直连 HLS / 网页播放）+ 源站**，不是"直连 HLS (m3u8)"。
依据：`app/.../data/model/Match.kt` 的 `StreamSource.isDirectHls`。

---

## 截图工具

依赖装在 `design/_shared/node_modules`（已在 `.gitignore` 里，不入库）：

```bash
cd design/_shared
npm install          # 首次：装 playwright
npx playwright install chromium   # 可选：没装系统 Chrome 时用内置内核
```

用法：

```bash
# 给某个原型出一套图（含浅色）
node design/_shared/shoot.mjs design/sportcard/index.html design/sportcard/out --light

# 量一量某屏的滚动高度与子块高度（排查溢出）
node design/sportcard/measure.mjs design/sportcard/index.html 01-list-dark 02-player-dark

# 重新生成三方案对比图
node design/_shared/shoot-compare.mjs
```

工具行为约定：

- 浏览器可执行文件按 `CHROME_PATH` → 常见 Chrome/Edge 路径 → Playwright 内置 Chromium 的顺序找；
  全都没有时报一条列出候选路径的错误（见 `_shared/chrome.mjs`）。
- 所有路径都相对脚本自身解析（`import.meta.url`），拷到别的机器也能跑。
- 远程队徽走明文 HTTP，**加载失败不算原型出错**：`shoot.mjs` 会把这类
  `Failed to load resource` 单独归到 `remoteAssetFailures`，只有真实的 JS 错误才让退出码为 1。

---

## 已知缺口

- `sportcard/out/` 只有 7 张图，**缺 01/02/03 的 `--light` 版本**（cinema 有）。
  补齐方式：`node design/_shared/shoot.mjs design/sportcard/index.html design/sportcard/out --light`。
  本次修复没有伪造截图来"补齐"这个缺口。
- `design/app-run/` 是真机 / 模拟器截图的本地留档，`.gitignore` 已排除，不入库。
