# NBA Live（安卓直播观赛）

> ⚠️ **免责声明**：本项目**仅供学习与技术交流**使用，**不提供、不托管、不存储任何音视频内容**，
> 与任何联赛/球队/转播方/源站均无关联或授权关系；**禁止商业用途**，
> 使用者需自行遵守所在地区法律法规与第三方站点条款。
> 完整声明见 [DISCLAIMER.md](DISCLAIMER.md)（含 English 摘要）。**继续使用即表示已阅读并同意。**

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
| 播放 | Media3 ExoPlayer 1.9.2（HLS + OkHttp DataSource）· WebView 嗅探兜底 |
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

缺失时：**release 打包会直接失败**（`assembleRelease` 报错退出），避免"产出一个没签名的 APK 发上去"这类发布事故。
确实需要无签名包时显式传 `-PskipSigning=true`。

> 🔑 **密钥必须异地备份**：`release.jks` 只存在开发机上、不在仓库里。
> 丢了它 = 已安装的用户**永远收不到更新**（Android 要求同包名更新必须同签名）。
> 请把它和 `keystore.properties` 一起复制到离线介质（U 盘 / 加密压缩包）并另行保存密码。

release 构建开启 R8 代码压缩与资源压缩，保留规则见 `app/proguard-rules.pro`。

**安装包不入库**：APK 作为二进制附件发布在 [GitHub Releases](../../releases)。

## 发版清单

一次发版 = 一个只改 `app/build.gradle.kts` 的 `release: vX.Y.Z` 提交 + 同名 tag：

```bash
# 1) 版本号：versionCode 每次 +1（已发布过的号不能复用），versionName 同步改
#    app/build.gradle.kts -> versionCode / versionName

# 2) 先验证再打包。两个变体都跑：release 变体验证 BuildConfig / 压缩路径没被改坏
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest

# 3) 打包（缺签名凭据会直接失败，见上一节）
./gradlew :app:assembleRelease

# 4) ⚠️ 必须验签：产出 app-release.apk 本身不代表它被正确签名
#    （这正是"未签名包与正式包同名同路径"那类发布事故的兜底检查）
$ANDROID_HOME/build-tools/<ver>/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
# 再用 aapt2 核对版本号真的进了包：
$ANDROID_HOME/build-tools/<ver>/aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head -1

# 5) 归档两个产物到仓库根（两者都已被 .gitignore 排除）
cp app/build/outputs/apk/release/app-release.apk   nba-live-<version>.apk
cp app/build/outputs/mapping/release/mapping.txt   nba-live-<version>-mapping.txt

# 6) 提交版本号并打 tag（tag 指向被构建的那个提交）
git commit -am "release: vX.Y.Z"
git tag vX.Y.Z
```

> ⚠️ **`mapping.txt` 必须和 APK 一起归档**：R8 混淆后线上崩溃堆栈只剩 `a.b.c`，
> 用它才能还原成真实类名与行号。丢了就等于这个版本永久失去可诊断性。
> 它与 APK 一样**不入库**（`.gitignore` 已排除）：文件里是全量「混淆名 → 原名」对照表，
> 公开仓库等于把混淆形同虚设。

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

## 对源站的请求礼仪

源站是免费第三方站点，被 Cloudflare 挡在前面。App 对它的请求必须克制：

| 约束 | 实现 |
|---|---|
| **被拒绝不重试** | 403/429 是明确的限流信号，再打 3 次只会让限流窗口越拖越长 → 只请求一次并提示用户 |
| **网络故障才重试** | 超时/DNS 抖动值得重试，最多 3 次，退避 400ms / 800ms |
| **详情页短缓存** | 同一个详情页在"刷新列表 → 点进播放页 → 刷新线路"里会被拉 2~3 次；用 5 分钟 TTL 缓存消掉重复（上限 64 条，LRU 淘汰） |
| **并发封顶** | 状态校正拉详情页并发 ≤ 4，避免十几场直播同时打爆源站 |
| **故障切换封顶** | 一次最多试 3 条直连线路（每条约 9 次请求），扫完直接降级到网页承载 |
| **嗅探回环阻断** | 升级失败的播放页记下来，同页不再重复升级（否则每轮都销毁重建 WebView、重放整条页面链） |
| **无自动轮询** | 除用户手动点击与首次打开外，没有任何定时请求。播放期间尤其不自动刷新 |

## 播放器手势

- **左半屏上下滑**：调屏幕亮度（只改本窗口亮度，不动系统设置，退出 App 自动还原；下限 2%，不会黑到看不见）
- **右半屏上下滑**：调媒体音量（不弹系统音量条，用 App 自己的指示器）
- **单击画面**：全屏时显示/隐藏浮层；网页线路的单击仍透传给源站播放器
- 指示器 800ms 后自动收起

## 免责声明

本项目仅为个人学习与技术研究用途。数据与流媒体资源归原站点所有，请勿用于任何商业用途或二次分发。
