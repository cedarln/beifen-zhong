# 《正在备份》v0.1.3

一款**离线**的 Android 叙事恐怖游戏。307 房间的女孩小满只存在于这台手机的本地缓存里，
而你是这栋楼里最后一个还会打开它的人。

七章 = 七个真实日。主动阅读约 99 分钟，剩下的时间她在那头等着，你把手机放下它也还在等。

- 离线可玩：剧情、她的每一句话、全部调度都在本机；她能感知你的只有前台、灭屏、省电、电量、日期这些合法设备信号
- 唯一的网络出口是设置页那颗「检查更新」，不点它这个包一次网都不出（边界由 `NetworkBoundaryTest` 守着，见[怎么更新](#怎么更新)）
- 存档只在这台手机上，卸载即真的消失
- 七章 / 182 个剧本节点 / 7 个结局（含 1 个隐藏结局）
- 她说的话过了一遍机器味重写（见 [v0.1.2 发布说明](docs/release-notes-v0.1.2.md)），判据写进 `ScriptVoiceTest`
- 往回翻能看见中间在哪儿空过一段：等待之后有一行「过了 3 分钟」，老进度装上就有（见 [v0.1.3 发布说明](docs/release-notes-v0.1.3.md)）

## 下载与安装

到 [Releases](../../releases) 拿 **`beifen-zhong-v0.1.3-release.apk`**（约 11 MB，已签名）。
Android 8.0 及以上。安装时系统会提示"允许来自此来源的应用"——这是侧载，不是商店包。

> **装过 v0.1.0 / v0.1.1 / v0.1.2 的话，不用重新下载**：设置页「检查更新」在应用里升上来就行。
> v0.1.1 修的是 Android 15 一类机器上界面顶进状态栏、D1 第三个选项压在虚拟键上按不到（开局就卡住）；
> v0.1.2 只动剧本文本；v0.1.3 只加了一层表现（时间分隔）。三版都没动存档格式，覆盖安装进度保留。

首次进入会走 D1，她在那里请求两件事：**通知权限**与**忽略电池优化**。不给也能玩，
但跨天的等待会被系统杀掉；那是剧情级的事件，不是 bug。

她用的是 `AlarmManager`，**不是系统闹钟**：不会进时钟 App，也不会响铃。
这条唤醒如果玩家在系统设置里关掉，会退到 `setAndAllowWhileIdle`——**她可能迟到，但不是她的问题**，
设置页那一行会把这个状态直说。

夜里 23:30–07:30 她默认**不出声**：到点的话推到早上 07:30 进通知栏。想让她夜里也说，
去设置里打开「夜里也让她说」。她不是系统闹钟，也不会出现在你的时钟 App 里。

`beifen-zhong-v0.1.3-debug.apk` 是开发包（包名多 `.debug`，可与正式版并存）。
开发后门：**会话页左上角「正在备份」那行标题连点 5 次**（相邻两次小于 1.5 秒），
弹「房间号」，填 `307`。跳章、跳等待、时间倍率（3600× 下 22 小时 = 22 秒）、按节点传送、
伪造设备信号都在里面。口令是硬编码的，**两个包都进得去**（详见
[docs/04 第一节](docs/04-开发后门设计.md)）；用它试玩会毁掉设计好的节奏，只给开发者。

## 怎么更新

设置页 →「关于这一版」→ **检查更新**：查到新版就在应用里下（走系统 `DownloadManager`，
进度在通知栏），下完按『安装』交给系统安装器覆盖安装。**不跳浏览器。**
进度不会丢（同签名覆盖安装会留着存档；**卸载才会真的没有**）。
下完的包会先按发布页给的 sha256 校验一遍才放开『安装』；手机上已经有这一版时
直接给『安装』，不再重复花一次流量。按钮写「等待网络」而不是一个不动的 0%——
系统下载器只肯在过了校验的网络上传字节，认证 Wi‑Fi、门户网络就是这种情况。

这一层是 v0.1.1 才有的，代价是本包申请了 `INTERNET` 与 `REQUEST_INSTALL_PACKAGES`——
"整包没有网络权限"这句话从这一版起不再成立。还能成立的是边界，而且它写在代码里、
由 `NetworkBoundaryTest` 判失败，不靠自觉：

1. 全工程只有 [`update/UpdateChecker.kt`](android/app/src/main/java/cn/linan/backingup/update/UpdateChecker.kt)
   可以出现网络 API；
2. 只有玩家按下那一颗按钮才动一次——没有启动时自动检查、没有后台轮询，
   引擎、唤醒链路、`WakeReceiver`、`CatchUpWorker` 都不引用它；
3. 它查的是我们自己的发布页。小满说的话、她知道的东西，一个字都不从网络回来——
   剧本仍然只来自 `script/*.json`（`StoryEngine` 第一条铁律）。

换句话说：上网的是"你下楼取东西"这件事，不是她能上网。
版本号在设置页第一行，从 `BuildConfig` 读，不是写死的。

## 仓库结构

```
script/     七章剧本 JSON + flags.json —— 唯一的文本真相，改它不用重编包
android/    Kotlin + Jetpack Compose 工程（剧本以 assets.srcDir 挂进 APK）
docs/       剧本圣经 / 结构与时间线 / 对话实录 / 后门设计 / 声音层 / 验收清单 / 发布规范
tools/      剧本校验、跨章回指对账、对话实录生成、声床生成、设备验证脚本
```

两条分工写死在代码里：**引擎不发明内容**（剧本没写的字，屏幕上不许出现）；
**改剧本不改代码**（`script/*.json` 直接进 assets，设置页那行节点数也是从剧本算出来的）。

## 自己构建

需要 JDK 21 与 Android SDK（API 35）。`android/local.properties` 不在仓库里，首次构建前补上：

```properties
sdk.dir=/你的/Library/Android/sdk
# 发布签名可选；不填则 release 产物未签名、装不上
beifen.storeFile=/仓库外/keystore/beifen-zhong-release.jks
beifen.storePassword=…
beifen.keyAlias=beifen-zhong-release
beifen.keyPassword=…
```

```bash
python3 tools/validate_script.py script/act*.json       # 先验剧本
python3 tools/audit_continuity.py script/act*.json      # 再验跨章回指与结局前置
cd android && ./gradlew :app:testDebugUnitTest :app:assembleRelease
```

51 条 JVM 单测里包含**贪心通关**：每章从入口走到章末，引擎一步都不许卡住；隐藏结局
《紧急联系人》的可达性也在那儿判——条件齐了必须出，差一条必须不出。可达性要靠跑，不靠读代码推。

设备侧验证（模拟器）见 [docs/07-模拟器验收清单.md](docs/07-模拟器验收清单.md)，
脚本在 `tools/device_reboot_check.sh` 与 `tools/device_wait_reboot_check.py`。

## 隐私

这是前提，不是合规动作：

- 不读通知栏内容、不要无障碍、不要通讯录、不读短信、不采集任何标识符
- 通知渠道 `IMPORTANCE_LOW`：不响、不震、不横幅弹出
- 只要 `SCHEDULE_EXACT_ALARM`（你可以在系统设置里撤掉它），不要 `USE_EXACT_ALARM`
- `allowBackup=false`：不备份、不上传、不统计
- **`INTERNET` + `REQUEST_INSTALL_PACKAGES` 是 v0.1.1 起的唯一例外**，只服务设置页那颗
  「检查更新」，边界与它的三条限制写在[怎么更新](#怎么更新)那一节；v0.1.0 及之前这个包
  连这两条都没有，那段历史留在 `docs/07` 的验收表里
- 唯一的"我们没写却出现在清单里"的东西：合并清单后 APK 里多出四条——
  `WAKE_LOCK` / `ACCESS_NETWORK_STATE` / `FOREGROUND_SERVICE`（全部来自 `androidx.work`，
  重启补投靠它）和 `cn.linan.backingup.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
  （androidx.core 给运行期注册接收器加的自定义签名级权限，只发给这个包自己）。
  `ACCESS_NETWORK_STATE` 只表示"能不能读到网络连接状态"，不给任何出网能力，
  除更新链路以外我们的代码从未查询它。
  详见 [AndroidManifest.xml](android/app/src/main/AndroidManifest.xml) 顶部注释。
- 反过来删掉了一条：`VIBRATE`。代码里一次都没调用过震动，而设置页写着"不响铃、不震动"——
  一条用不上的权限不是"以防万一"，是对那句承诺的伪造。

## 已知没验的

七条，写在 [docs/07 第四节](docs/07-模拟器验收清单.md) 里而不是藏起来。最要紧的两条：
**只有模拟器、没有真机**（各家 ROM 的后台管控能把等待掐掉，这部分我们没有数据），
以及**没连续跑过真的 7 天**。

## 文档

| 文件 | 讲什么 |
|---|---|
| [docs/01-剧本圣经.md](docs/01-剧本圣经.md) | 概念、小满是谁、设备状态→叙事的映射表、恐怖手法清单 |
| [docs/02-结构与时间线.md](docs/02-结构与时间线.md) | 七章结构、等待时长表、分支节点图、七结局及其前置 |
| [docs/03-第一章-对话实录.md](docs/03-第一章-对话实录.md) | D1 全文本（脚本从 JSON 生成） |
| [docs/04-开发后门设计.md](docs/04-开发后门设计.md) | 跳等待/跳章/传送，以及唤醒链路踩过的坑 |
| [docs/05-全篇对话实录.md](docs/05-全篇对话实录.md) | D1–D7 全文本通读版（脚本生成） |
| [docs/06-声音层设计.md](docs/06-声音层设计.md) | 环境声而非配乐：四条声床、增益表、三条红线 |
| [docs/07-模拟器验收清单.md](docs/07-模拟器验收清单.md) | 验过什么、怎么复现、还有什么没验 |
| [docs/08-发布与命名规范.md](docs/08-发布与命名规范.md) | 仓库/版本号/APK 命名/签名约定（给下一个游戏用） |
