# v1.2.30 发布说明（热修）

## 修复：已完赛录像页首次进入一直加载
- **现象**：v1.2.29 首次切到「录像」标签，页面一直显示「正在加载录像…」，永远不出数据；下拉刷新后才正常。
- **根因**：`ReplayUiState.loading` 初值 `true` 的语义是「还没拉过、首载要显示转圈」，但自动加载守卫写成了 `!replayState.loading`（NbaLiveApp 里的 LaunchedEffect），把这层初值当成「正在拉」挡掉了，导致首次进入根本没发请求。
- **修复**：VM 新增独立的一次性标志 `replayAutoLoadRequested` 与 `ensureReplaysLoaded(tab)`，只在首次进入录像标签时拉一次；不再复用 `loading` 当守卫。
- **验证**：
  - TDD 回归测试 `ReplayVmTest.首次切到录像标签必须自动加载…`（RED→GREEN）：首次触发一次、重复不重复打源站、其它标签不触发。
  - 双变体 **各 907 通过 / 0 失败**；`assembleDebug` / `assembleRelease` 全绿。
  - 雷电模拟器（`nbatest`，Android 14）实测：冷启动切到「录像」立即显示 **24 场**，带真实比分、多数 3 条清晰度；测试时媒体音量已归零、模拟器已最小化。

## 边界
- 仅修首次自动加载守卫；录像数据链路、清晰度、近 3 天窗口范围均与 v1.2.29 一致，未改动。
