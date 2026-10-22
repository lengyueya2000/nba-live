# NBA Live 安卓 App — UI 设计共享基座

> 所有方案必须遵守本文件。它是唯一事实源，任何偏离都会导致方案无法落地到 Kotlin/Compose。

## 1. 产品定义

**NBA Live** — 一个面向中国球迷的 NBA/CBA 赛事直播观看 Android 应用。

- 目标用户：中文用户，18-40 岁，夜间/通勤时段观赛
- 核心价值：**打开就能看到今天有哪些比赛，点一下就能看**
- 源站：`jrszbt.com`（JRS）、`m.jrkan.com`（JRKAN），均为第三方聚合源
- 播放：Media3 ExoPlayer 直连 HLS（m3u8），失败时多源故障切换
- 技术栈：Kotlin + Jetpack Compose + Material 3 + Media3 + OkHttp + Coil

**非目标（YAGNI）**：比分预测、投注、评论社交、录像回放、多语言、登录账号。

## 2. 关键约束（硬性，不可违反）

| 约束 | 说明 |
|---|---|
| **单手操作** | 竖屏单手可达，底部导航固定，拇指热区在屏幕下 1/3 |
| **48dp 最小点击区** | 所有可点击元素 >= 48x48dp |
| **8dp 间距节奏** | 4/8/12/16/24/32dp，禁止 13/17/23dp 之类 |
| **暗色为默认** | 观赛场景（夜间），浅色模式可提供但非首选 |
| **对比度** | 正文 >= 4.5:1，大字 >= 3:1，描边/图标 >= 3:1 |
| **不隐藏系统栏** | 顶部避开状态栏，底部避开手势条（用 WindowInsets） |
| **无 Emoji 图标** | 全部使用 Material Icons 或矢量图标 |
| **Live 区不能假数据** | 直播状态、比分若源站无数据，显示"未开赛"/"直播中"等真实状态，不编造比分 |

## 3. 必须覆盖的界面（3 个核心屏）

### 屏 A：赛事列表（首页）
- 顶部：品牌区 + 联赛筛选（全部 / NBA / CBA）+ 搜索
- 主体：按日期分组的赛事卡片列表
- 赛事卡片必须包含：联赛标识、双方队名+队徽、开赛时间或"直播中"状态、可用信号数
- 状态呈现：未开赛（时间）/ 直播中（红色脉冲标识）/ 已结束（比分或"已结束"）
- 空态、加载态、错误态都要有设计

### 屏 B：播放器（核心）
- 视频区（16:9 或全屏沉浸）
- 播放控制：播放/暂停、进度、全屏、音量
- **信号切换**：多线路（源站每场有 3+ 条），必须在播放器内可见且可切换
- **源故障切换**：当某线路播放失败，自动切到下一线路，UI 要有明确反馈（Toast/Snackbar）
- 赛事信息条：队名、联赛、时间
- 返回手势需正确处理

### 屏 C：赛事详情
- 赛事头部：两队队徽 + 队名 + 联赛 + 时间/状态
- 信号列表：每条信号一行，显示信号名（如"信号1"/"高清"/"备线"）、质量、状态
- 点击某信号 → 进入播放器并选中该线路
- 其他源站补充信息（如更多信号外链、备用地址）以次要样式呈现

## 4. 设计语言基线（从 ui-ux-pro-max 生成）

**风格**：Dark Mode (OLED) + Cinema dark
**主色**：深蓝黑 `#0F0F23` / 次色 `#1E1B4B` / 强调 CTA `#E11D48`（播放红）
**背景**：`#000000` / 卡片 `#0C0C0D` / 边框 `#312E81`
**文字**：前景 `#F8FAFC` / 次要 `#94A3B8`
**字体**：Inter（或等价系统无衬线），字号梯度 12/14/16/20/24/32
**效果**：极简辉光（text-shadow 模拟）、深浅过渡、低白场

> 方案可以在此基础上调整配色与形态，但必须保持"暗色 + 红色 CTA"的核心识别，且对比度达标。

## 5. Mockup 产出要求

每个方案必须交付：

```
design/<scheme-id>/
  index.html          # 完整可交互的 HTML mockup（多屏切换）
  out/
    01-list-dark.png        # 屏A 赛事列表
    02-player-dark.png      # 屏B 播放器
    03-detail-dark.png      # 屏C 赛事详情
    04-list-light.png       # 屏A 浅色（如提供浅色模式）
  README.md           # 方案说明：设计理念、配色、字号、组件清单、适用场景
```

**渲染要求**：
- 手机视口 **390x844**（iPhone 14 / Pixel 7 逻辑尺寸），deviceScaleFactor=2
- 每个状态（直播中/未开赛/已结束/空态/加载态/错误态）都要在截图中体现
- 截图必须真实可读，文字不能溢出、不能重叠、不能截断

## 6. 真实数据样本（必须使用，不得编造）

### NBA 赛事（来自 jrkan index.js）
| 联赛 | 时间 | 主队 | 客队 | 信号数 |
|---|---|---|---|---|
| NBA | 10-22 07:00 | 凯尔特人 | 老鹰 | 3 |
| NBA | 10-22 07:30 | 湖人 | 开拓者 | 3 |

### CBA 赛事（来自 JRS + jrkan）
| 联赛 | 时间 | 主队 | 客队 | 信号数 |
|---|---|---|---|---|
| CBA | 10-21 19:35 | 浙江 | 山东 | 3 |
| CBA | 10-21 19:35 | 青岛 | 辽宁 | 3 |
| CBA | 10-21 19:35 | 深圳 | 广厦 | 3 |

### 其他可用于填充列表的联赛
欧冠联赛、北美联赛、澳篮、韩K联赛、捷甲联赛、葡超联赛、乌拉圭联赛、巴西甲、英超联、超级杯、VTB联赛、俄超联

### 队伍头像（真实可用 URL）
- 凯尔特人: `https://cdn.sportnanoapi.com/basketball/team/0dc24d08ef0b5584e0b70f967db64b36.png`
- 老鹰: `https://cdn.sportnanoapi.com/basketball/team/4e67b14905a05af9e1bd04406bf8690f.png`
- 浙江: `http://www.jrszb444.com/d/file/p/2019/07-22/6da83cc0a417e07e2787d56b9c335a70.jpg`
- 山东: `http://www.jrszb444.com/d/file/p/2019/07-22/964df7d6987b40bff17f8b25cc0b1186.jpg`
- 青岛: `http://www.jrszb444.com/d/file/p/2019/07-17/ddf3e58f3df2f8c872c503b97c254681.jpg`
- 辽宁: `http://www.jrszb444.com/d/file/p/2019/07-22/acb1277db8452ad3271a65207209575.jpg`
- 深圳: `http://www.jrszb444.com/d/file/p/2019/04-16/76af9384d3eb454a4d318ae371734d15.jpg`
- 广厦: `http://www.jrszb444.com/d/file/p/2019/07-22/964df7d6987b40bff17f8b25cc0b1186.jpg`

> 头像加载失败时必须有兜底（队名首字/缩写色块）。

## 7. 技术落地映射（设计必须可落地）

| Mockup 元素 | Compose 实现 |
|---|---|
| 底部导航 | `NavigationBar` + `NavigationBarItem`（Material 3） |
| 赛事卡片 | `Card` + `Row`/`Column`，点击区 >= 48dp |
| 直播中标识 | `Badge`/`Box` + 红色 + 呼吸动画（`rememberInfiniteTransition`） |
| 联赛筛选 | `ScrollableTabRow` 或 `FilterChip` 横向流 |
| 信号切换 | `SingleChoiceSegmentedButtonRow` 或 `LazyRow` + `FilterChip` |
| 视频播放 | `AndroidView` 包裹 `PlayerView`（Media3） |
| 失败切换提示 | `Snackbar` |
| 图标 | `androidx.compose.material:material-icons-extended` |
| 图片 | `coil-compose` `AsyncImage` |
| 网络 | `OkHttp` + `kotlinx.serialization`（或 Moshi） |

## 8. 判定标准

一个方案合格，当且仅当：
1. 三个核心屏都完整且可交互（能切换屏）
2. 截图文字无溢出/重叠/截断
3. 暗色模式对比度达标（正文 4.5:1+）
4. 所有点击目标 >= 48dp
5. 覆盖空态/加载态/错误态
6. 使用真实数据（不编造比分）
7. README 清晰说明设计理念与差异点
