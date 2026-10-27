# 会话状态记录（用于上下文压缩后恢复）

## 工程
- 路径：G:\flutter\work\nba
- 类型：Flutter + Kotlin 混合（Android 侧原生 WebView/ExoPlayer 播放 NBA 直播源）

## 总任务
按 95 条审查清单（分组 01~05 + P0/P1/P2 + D 组安全）逐条落地修复。
先 TDD 写纯判据测试 → 接线 → 编译 → 全量测试。

## 已完成
- 编译错误修复：补 `delay` 导入 + 缺失常量定义（老化循环）
- 全量测试 198/198 通过（该节点）
- D 组：日志泄露带签名 URL → `Logs.kt` 加 `safeLogWarn`，泄露点逐个替换；
  相对路径形态 `/live/xxx.html?auth_key=` 也纳入脱敏正则；
  新增「调用点守卫」测试（源码扫描，3/3 通过，skipped=0）
- P0-1：`finishPipIfBroken` 条件恒为假（`shouldKeepPip(false,true)` 永远 false，
  自 v1.2.9 起「小窗报错收窗」是死代码）→ 重写分级：25 秒降级为「疑似」`onPlaybackSuspect`，
  `onReceivedError` 按 errorCode 过滤；`playbackDead` 纯判据测试补齐
- 媒体识别改为按「路径」判定，避免源站页面自身 `/live/*.html` 被误算成媒体
  （否则疑似判定永不触发）→ 18/18 通过
- 解析器修复（源站改版即失效那组）
- 音频焦点全部接线
- 导航栏浅色标志（03-P1-3）
- WebView 导航守卫（04-P1-3/4 + 「广告把播放页导航走」静默失败）→ 8/8 纯判据测试通过
  （解析兼容裸 host 与完整 URL 两种形态）

## 当前进行中 / 待办
1. **WebViewClient 接线（多行编辑踩坑中）**
   - 该文件区域是 CRLF，跨行 old_string 匹配失败 → 必须逐行替换
   - 第 1830 行编辑已生效
   - **第 1834 行被一次单行替换误覆盖，需要修回**
   - 待办：更新该处过时注释 + 确认后续内容完整
2. 核对 P0-1 与 01 组剩余项落地状态
3. 剩余 95 条清单未核对项
4. 每完成一段：编译 + 全量测试 + git 提交

## 教训
- `onPageFinished` 属于 **WebViewClient**，不是 WebChromeClient（曾误放并造成重复块）
- 编辑前必须 read；逐行替换而非多行块替换（CRLF）
