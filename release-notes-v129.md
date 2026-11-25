# v1.2.29 发布说明

## 新功能：已完赛观看录像（独立「录像」标签页）

底部导航新增第 4 个标签「录像」，展示已完赛篮球赛事的**真实录像回放**（不是直播信号）。

### 数据来源（真网探测确认）
- 回放接口：`GET /api/v1/replays?matchId={id}`（xxzb5.com 跳转后的 `www.xixizb.com`）
  - ⚠️ 单数 `/api/v1/replay/{id}` 是**另一个足球实体**，不是回放，已避开。
- 完赛场次取自身窗口接口：`status=finished && sport=篮球 && replayId>0`，保留源站真实比分（如 老鹰 107-97 活塞）。
- 一场有多条清晰度线路：实测「标清回放 / 中文高清回放 / 英文高清回放」，均为裸 m3u8（`#EXTM3U`，HTTP 200），按源站 `sort` 排序，播放器底栏可直接换线。

### 实现
- 模型：`ReplayItem` / `ReplayLine`（与直播 `Match` 分离，不参与源开关/去重/状态校正）。
- 解析：`Xxzb5Parser.parseReplayCandidates`（窗口挑篮球完赛+比分）、`parseReplays`（多清晰度排序、过滤未发布/非 m3u8）；`JsonCursor` 新增 `bool()`。
- 仓库：`MatchRepository.loadReplays` —— 复用窗口数据，按 matchId 并发（上限 4）拉回放详情；单场详情失败只让那场无线路，不影响其他场次。
- VM：`ReplayUiState` + `loadReplays()` + `playReplay()`；录像线路转成 `StreamSource` 复用现有直连播放器，可换清晰度。
- UI：`NavTab.REPLAY` + `ReplayScreen`（比分卡片、下拉刷新、空/错误态），首次进入标签加载一次。

### 质量
- 两个单测变体 **各 906 通过 / 0 失败**（新增 11 个：解析器 5、仓库 3、VM 3）。
- `assembleDebug` / `assembleRelease` / `bundleRelease` 全绿。
- 修复守卫测试接线：`ScreenConsistencyGuardTest`（时间走 `ReplayItem.displayTime()`）、`TopBarAndLiveFilterTest`（播放徽标改圆形，非方形按钮副本）。

### 边界（诚实说明）
- 仅覆盖近 3 天窗口的篮球完赛（含 WCBA 等篮球联赛），本期不做往期日期选择。
- 清晰度按源站实际给出的线路数量呈现，不编造；无线路的场次卡片仍在，点进去提示暂无线路。
- 录像信号同样依赖 xxzb5（西西直播）可用性；源站下线则录像页为空。
- 本次发版前**未在模拟器实测 VOD 起播**（用户未要求模拟器测试）；代码层由单测覆盖数据链路。
