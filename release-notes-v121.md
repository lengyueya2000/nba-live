### 新增
- **全屏沉浸模式**：进入全屏默认纯净画面，无任何按钮与信息；单击画面显示浮层，再单击隐藏（仅直连播放路径，网页线路触摸仍留给源站播放器）
- 打开「播放源 / 播放线路 / 同时在播」面板时，控制栏强制保持可见

### 修复
- 浅色主题下「播放源 / 播放线路」浮层白字压白底完全看不见
- 播放器错误提示卡因写死 null 永不渲染（非全屏时现已常驻显示，全屏交给 Snackbar）
- 快速切线路时，被取消的旧解析任务可能覆盖用户最新选择（挂起返回后校验协程活跃）
- 直播中场次显示假「00:00」：校正回填真实开赛时间后正常显示 HH:mm
- 状态芯片「已结束 / 未开赛」硬编码深色，浅色主题下压成深色砖

### 清理
- 删除无调用方的死代码：openDetail、liveCount、MatchRepository.filter、MetaPill/VSpace/HSpace、StreamSource 未用字段
- 移除未使用依赖 navigation-compose、kotlinx-serialization（APK 体积下降）
- 清理未使用 import；versionName 兜底不再硬编码

版本：versionCode 5 / versionName 1.2.1（可直接覆盖升级）
