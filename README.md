# NBA Live（安卓直播观赛）

Kotlin + Jetpack Compose 编写的 NBA / CBA 直播观赛 App。聚合两个第三方源站，自动解析可直连的 HLS 线路，Media3 ExoPlayer 原生播放，支持全屏与画中画。

## 功能

- **赛事列表**：JRS + JRKAN 双源聚合、按队名+日期跨源合并去重；直播中置顶 + Hero 大卡；NBA / CBA 筛选
- **赛事详情**：展开源站播放页内的全部线路；线路名涉及主播解说的按规则过滤；显示真实播放方式（直连 HLS / 网页播放），不伪造比分、清晰度等源站没有的数据
- **播放器**：
  - 直连 HLS：Media3 ExoPlayer + OkHttp 数据源（复用连接池，Referer/UA 伪装过源站防盗链）
  - 加密线路（XXTEA 等）：WebView 静默承载源站页面，同时嗅探其解密后请求的 `.m3u8`，命中即自动升级回 ExoPlayer 直连（甩掉网页广告层、解锁小窗/全屏）；直连失败自动退回 WebView，防回环
  - 多线路自动故障切换 + 卡死看门狗
  - **全屏**（横屏 + 沉浸系统栏；默认纯净画面，单击画面显示/再击隐藏全部浮层）、**画中画**（手动按钮 + 按 Home 自动进小窗）
  - 全屏控制栏：暂停/重连 + 画面自适应（三档）+ 播放源 / 播放线路切换 + 同时在播面板（右侧滑出，上下滑选场直切）
  - 音频焦点、后台暂停、来电/拔耳机处理
- 深色运动卡片设计语言（见 `design/sportcard/README.md`）

## 技术栈

| | |
|---|---|
| 语言 / UI | Kotlin 2.0.21 · Jetpack Compose (BOM 2024.10.01) · Material 3 |
| 播放 | Media3 ExoPlayer 1.4.1（HLS + OkHttp DataSource）· WebView 嗅探兜底 |
| 网络 | OkHttp 4.12.0 · Jsoup 1.18.1 |
| 构建 | AGP 8.7.3 · Gradle 8.9 · JDK 17/21 · minSdk 24 · targetSdk 35 · core library desugaring |

## 构建

```bash
# 需要 JDK 17+（含 keytool），Android SDK 35
./gradlew :app:assembleDebug        # 调试包
./gradlew :app:testDebugUnitTest    # 单元测试
./gradlew :app:assembleRelease      # 发布包（需 keystore.properties，见下）
```

发布签名：在**仓库根目录**放置 `keystore.properties`（已被 .gitignore 排除）：

```properties
storeFile=/absolute/path/release.jks
storePassword=...
keyAlias=nba-live
keyPassword=...
```

缺失时 release 构建退回未签名，便于 CI 注入。

release 构建开启 R8 代码压缩与资源压缩，保留规则见 `app/proguard-rules.pro`。

**安装包不入库**：APK 作为二进制附件发布在 [GitHub Releases](../../releases)。

## 安全与网络

- 明文流量策略集中在 `app/src/main/res/xml/network_security_config.xml`：源站的播放页与 m3u8 切片走 HTTP，而清单所在的 CDN 主机名是**运行时由源站生成并混淆**的（`hdl2.<反转后的主域名>`），无法预先写进域名白名单，因此保留明文许可并把已知主机备案在文件里
- 备份 / 换机迁移全部排除（`data_extraction_rules.xml`）：App 只存深色模式与画面模式这类设备相关的显示设置，没有备份价值
- 签名凭据（`keystore.properties`）已被 `.gitignore` 排除，**不入库**

## 目录

```
app/                 Android 工程（data 解析 / ui Compose / 播放器）
design/              设计稿与真机截图（sportcard 为已选方案，cinema/minimal 为备选探索）
gradle/              Gradle Wrapper
```

## 已知取舍

- **直连 HLS 只覆盖 `/play/sm.html` 这一条链路**：JRS 的 mgxl/pao 播放器与 JRKAN 的 wen/pao 线路使用 XXTEA 加密，静态还原不了，只能交给 WebView 承载（WebView 会嗅探解密后的 m3u8 并自动升级回 ExoPlayer）
- **域名反混淆是有损的**：源站哪天不再混淆，反混淆会得到乱码域名。解析器因此一次给出多个候选地址，用"能否拉出清单"裁决，而不是只认一个
- **状态校正是启发式的**：源站列表的"直播中"会滞后 3 小时以上，只能用计划开赛时间做区间判断（开赛后 3.5 小时判已结束、开赛前 15 分钟判未开赛）

## 免责声明

本项目仅为个人学习与技术研究用途。数据与流媒体资源归原站点所有，请勿用于任何商业用途或二次分发。
