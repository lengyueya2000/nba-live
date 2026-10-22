# 方案 B · 运动卡片（Sport Card）— 已选定并落地

> 状态：**已选定为 App 视觉方向**，并已实现到 Kotlin + Jetpack Compose 工程中。
> 设计源文件：`design/sportcard/index.html`；实机截图：`design/app-run/`

## 设计取向

明快、有活力的运动视觉。深色底 + 高饱和联赛色 + 大圆角卡片 + 渐变强调条，
目标是「一屏扫到更多场次、一键进入播放」。

## 设计 token（`app/src/main/java/com/nbalive/app/ui/theme/Theme.kt`）

### 暗色（默认）

| Token | 值 | 用途 |
|---|---|---|
| `Bg` | `#0A0D18` | 页面底色 |
| `Bg2` | `#0F1424` | 次级背景 |
| `Card` | `#161C33` | 卡片 |
| `Card2` | `#1E2542` | 选中态卡片 |
| `Card3` | `#252D4E` | 徽章底 |
| `Line` | `rgba(255,255,255,.10)` | 描边 |
| `Txt` | `#F4F7FF` | 主文字 |
| `Txt2` | `#A7B0CC` | 次文字 |
| `Txt3` | `#6E7799` | 弱文字 |
| `Red` | `#FF3B4E` | 主色 / CTA |
| `Orange` | `#FF8A3D` | 渐变终点 |
| `Nba` | `#3B82F6` | NBA 联赛色 |
| `Cba` | `#FF4D6D` | CBA 联赛色 |
| `Ok` | `#2FD07E` | 直连可用 |
| `Warn` | `#FFC24B` | 需网页播放 |

### 浅色

`Bg #EDF0F8` / `Card #FFFFFF` / `Txt #0B1020` / `Red #E01F3D`

### 渐变

- `GradLive` `135° #FF2E4D → #FF6B2C → #FF8A3D`（直播大卡 / 直播态）
- `GradRed` `135° #FF3B4E → #FF7A2F`（主 CTA）
- `GradNba` `90° #2563EB → #60A5FA`（NBA 色条）
- `GradCba` `90° #E11D48 → #FB923C`（CBA 色条）

### 圆角与间距

- 卡片 `22dp`，按钮 `14dp`，徽章 `7–8dp`，胶囊 `9dp`
- 间距节奏 `4 / 6 / 8 / 10 / 16 / 20 / 24dp`

### 字阶

`30/24/21/18/15/13/14/13/11sp`，标题字重 `Black(900)`，正文 `Medium(500)`，时间数字用大字号 `21sp Black`。

## 组件（`ui/components/Common.kt`）

| 组件 | 说明 |
|---|---|
| `TeamLogo` | 白底圆形 + `ContentScale.Fit`；加载中/失败回退队名首字色块 |
| `LeagueStrip` | 卡片顶部 5dp 联赛渐变色条 |
| `LeagueChip` | 联赛标签，20dp 高 / 7dp 圆角 / 9.5sp Black |
| `SignalBadge` | 线路数徽章 |
| `StatusChip` | 直播中（渐变）/ 未开赛（蓝调）/ 已结束（中性） |
| `LiveDot` | 呼吸红点（同时有"直播中"文字，不单靠颜色传达） |

## 三屏结构

| 屏 | 文件 | 要点 |
|---|---|---|
| 赛事列表 | `ui/screens/MatchListScreen.kt` | 全宽直播 hero 卡 + 双列 `LazyVerticalGrid`；日期分组；联赛三筛选 |
| 赛事详情 | `ui/screens/MatchDetailScreen.kt` | 左右对阵 76dp 队徽 + 大字队名；线路列表带播放方式徽章；源站信息 |
| 播放器 | `ui/screens/PlayerScreen.kt` | 16:9 视频区；三段式线路切换；其他直播推荐 |

## 与设计稿的差异（有意为之）

1. **不编造比分**：源站不提供比分数据，因此设计稿里的「第2节 08:42」类信息全部移除，
   只显示 未开赛 / 直播中 / 已结束。
2. **不编造画质与可用性**：设计稿的 `1080P / 720P` 与「可用 / 速度慢」徽章没有真实数据来源，
   改为展示**真实已知信息**：播放方式（直连 HLS / 网页播放）+ 源站（JRS / JRKAN）。
3. **底部 CTA**：详情页设计稿的「去观看 · 信号1」固定按钮改为**直接点击线路行**进入播放，
   少一步操作；播放器内仍可切线路。
