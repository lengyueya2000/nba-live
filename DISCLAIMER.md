# 免责声明 / Disclaimer

> 本项目**仅供学习与技术交流**使用。请在使用前完整阅读本声明。
> This project is provided **for learning and technical exchange only**.

## 1. 本项目是什么

一个用 **Kotlin + Jetpack Compose + Media3** 编写的安卓客户端**工程实践示例**。
它演示的是这些技术点本身：

- Compose 的状态管理、自定义播放器浮层、画中画、无障碍语义；
- Media3 ExoPlayer 直连 HLS 播放、WebView 承载与「嗅探后升级为直连」的播放路径切换；
- 第三方**公开网页**的解析（Jsoup）、候选域名轮换、失败记账与截止时间控制；
- 以及配套的工程纪律：单元测试、源码守卫（防止"永远不会红的守卫"与"零消费点声明"）。

## 2. 本项目不做什么（重要）

- **不提供、不托管、不存储、不分发任何音视频内容**；
- **不破解任何加密**，不绕过付费墙、登录或地域限制，不修改任何第三方服务；
- **不内置任何播放地址**：App 运行时从第三方**公开网页**读取页面内容，
  由客户端解析出地址后播放——**内容本身始终来自第三方站点**；
- **与任何联赛、球队、转播方、源站没有任何关联、合作或授权关系**。

## 3. 权利归属

赛事直播、录像、图片、队徽、商标等内容的**全部权利归各自权利人所有**。
本项目对上述内容不主张任何权利。

若任何权利人认为本项目（例如其中的示例代码、截图或说明文字）侵犯了其权益，
请通过 Issue 或仓库主页所列联系方式告知，我们会在核实后**立即删除相关内容**。

## 4. 使用者的责任

- 本项目**禁止用于任何商业用途**；
- 使用者应**自行遵守所在国家/地区的法律法规**，并遵守所访问第三方站点的服务条款；
- 请勿将本项目用于任何侵犯他人合法权益的用途；
- **因使用本项目而产生的一切后果（包括但不限于网络、账号、法律风险）由使用者自行承担**，
  作者与贡献者不承担任何责任。

## 5. 无担保

本项目按"**现状**"（AS IS）提供，不附带任何形式的明示或默示担保，
包括但不限于适销性、特定用途适用性与不侵权担保。
第三方站点的页面结构、域名与访问策略随时可能变化，
因此**不保证任何功能可用、稳定或长期有效**。

## 6. 面向贡献者

提交代码即表示你已阅读并同意本声明；请勿在仓库中提交：

- 任何密钥、口令、证书（`*.jks`、`keystore.properties` 等一律不入库）；
- 任何抓取到的第三方内容正文、录像或清单文件；
- 任何绕过第三方访问限制的实现。

---

## English (summary)

This project is a **learning/demo Android client** built with Kotlin, Jetpack Compose and Media3.
**It does not host, store, provide or distribute any audio/video content**, does not break any
encryption, and is not affiliated with or authorized by any league, team, broadcaster or source
site. All content rights belong to their respective owners. Playback addresses are read at runtime
from third-party **public web pages**; the content itself always comes from those third-party sites.
**Commercial use is prohibited.** Users are solely responsible for complying with the laws of their
jurisdiction and with any third-party terms of service, and bear all consequences of using this
project. The software is provided **AS IS**, without warranty of any kind. Rights holders may open
an issue to request removal of any content, and it will be removed promptly.