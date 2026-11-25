# v1.2.28 更新说明

本轮一件事：**把第三播放源从 88看球换成 xxzb5.com（西西直播）**。
按用户要求「88看球源去掉，增加 xxzb5.com」执行，先探测真实接口再实现，非照旧格式硬套。

## 改动

### 第三源换源：88看球 → xxzb5

- 彻底移除 88看球：`Provider.K88`、解析器、专属测试、只服务它的带噪 base64 解码一并删除，不留死代码。
- 新增 xxzb5 源（展示名 `xxzb5`）。实测 `xxzb5.com` 只做跳转到 `https://www.xixizb.com`（西西直播），
  且 `xxzb5.com` 自身不承载 `/api/v1/...`（全 404）——所以源名叫 xxzb5，**后端域名用跳转终点 xixizb.com**。
- 接入方式按实测接口，而不是硬套 88看球格式：
  - 赛程：`GET /api/v1/matches/window?from=&to=`（北京时间当天前后各一天）
  - 线路：`GET /api/v1/matches/{id}/progress`，`signals[]` 里是**裸 m3u8 直连**。
- 直连判据新增裸 m3u8（`.m3u8` 可带 query），`StreamUrlResolver` 对裸清单直接返回、不再抓播放页。
- 沿用既有纪律：主播/解说线路整条剔除、按等价键跨源去重、**腾讯超清排第一**（用户此前点名的要求继续生效）。

### 升级兼容：老用户的第三源开关不丢

真机测试发现：老用户磁盘上存着已删除的 `K88` 开关名，升级后解析被丢弃，
「我的」页显示 xxzb5「已关闭，不会请求这个源」——第三源静默失效。
现把旧名映射到新名（`K88 → XXZB5`），保留用户对第三源槽位的开/关选择；
当时关着的用户不会被擅自打开。

## 真机取证（雷电 nbatest / Android 14，v31 debug 包）

- 「我的」页摘要 `JRS / JRKAN / xxzb5`，升级用户 xxzb5 开关**开启**、显示正在解析。
- 只留 xxzb5 源：赛事列表 **22 场**，卡片标签 `xxzb5`，直播卡带源标记。
- 「掘金 vs 独行侠」信号切换共 **4 条**：腾讯超清3（选中/第一）/ CCTV5 / 高清直播1 / 原声直播1，
  全部标 `xxzb5` —— 腾讯超清优先排序在真实源上生效。
- 裸 m3u8 直连播放：`D/MatchRepo: m3u8 OK: https://www.rtmpcdn.com/live/qq3.m3u8` + `ExoPlayerImpl Init`，
  画面正常（画面自带 XXZB5.COM 水印，反证线路确实来自 xxzb5）。
- 换线/重试：点 CCTV5 → `m3u8 OK: .../cctv5.m3u8` + ExoPlayer 重新 Init；重复点选仍能重新拉流。
- 画中画：进入小窗（Launcher 成为 top activity），点小窗返回 → `com.nbalive.app/.MainActivity` 恢复，
  播放器界面与线路列表状态保留。
- 多次换线、PiP 往返后进程存活（PID 2279），全程 0 崩溃 / 0 ANR。

## 质量与防回归

- 判据 911 → **895**（删掉 88看球专属用例，新增 xxzb5 解析 11 条 + 升级迁移 1 条），
  新增用例全部先 RED 后 GREEN；双变体各 **895 / 0 失败 / 0 错误 / 0 跳过**。
- `assembleDebug` / `assembleRelease` 通过；release 产物 aapt2 核对版本、`apksigner verify` 验签，
  证书与历次为同一把发布密钥。
- `:app:jacocoCoverageGate` 通过。

## 已知问题 / 诚实边界

- 腾讯超清这条线的编号由源站给（抓到的是 qq2/qq3），按名字关键字「腾讯超清」排序，不写死某个编号。
- 全屏底部栏的重连按钮在模拟器横屏坐标下几次点偏，未单独截到它触发的日志；
  但「换线 / 重复点当前线路」走的是同一条 `play() → resolveStream()` 路径，`m3u8 OK` 重拉即刷新有效的直接证据，
  且重连 nonce 语义有单测钉住。
- 竖屏播放页仍无刷新按钮（底部控制栏按既有规则只在全屏显示，有单测钉住），本轮未新增入口。
- 视频里的买球广告仍不处理（此前用户确认）：烧进视频像素，遮挡会切掉记分牌。
- xxzb5 首页跳转到第三方站点、内容由该站点提供；本 App 不托管任何音视频，见 DISCLAIMER.md。

## 校验

- `:app:testDebugUnitTest` + `:app:testReleaseUnitTest`（`--rerun-tasks`）：各 **895 条 / 0 失败 / 0 错误 / 0 跳过**，124 套件。
- `:app:assembleDebug` / `:app:assembleRelease` / `:app:jacocoCoverageGate` 通过。
- 雷电 nbatest 真机全流程：升级开关迁移、三源独立工作、腾讯超清排序、裸 m3u8 直连、换线重试、PiP 往返，0 崩溃。
