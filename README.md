# 《正在备份》v0.1.0

一款**离线**的 Android 叙事恐怖游戏。307 房间的女孩小满只存在于这台手机的本地缓存里，
而你是这栋楼里最后一个还会打开它的人。

七章 = 七个真实日。主动阅读约 99 分钟，剩下的时间她在那头等着，你把手机放下它也还在等。

- 无网络权限：整包没有 `INTERNET`，她能感知你的只有前台、灭屏、省电、电量、日期这些合法设备信号
- 存档只在这台手机上，卸载即真的消失
- 七章 / 182 个剧本节点 / 7 个结局（含 1 个隐藏结局）

## 下载与安装

到 [Releases](../../releases) 拿 **`beifen-zhong-v0.1.0-release.apk`**（约 11 MB，已签名）。
Android 8.0 及以上。安装时系统会提示"允许来自此来源的应用"——这是侧载，不是商店包。

首次进入会走 D1，她在那里请求两件事：**通知权限**与**忽略电池优化**。不给也能玩，
但跨天的等待会被系统杀掉；那是剧情级的事件，不是 bug。

她用的是 `AlarmManager`，**不是系统闹钟**：不会进时钟 App，也不会响铃。
这条唤醒如果玩家在系统设置里关掉，会退到 `setAndAllowWhileIdle`——**她可能迟到，但不是她的问题**，
设置页那一行会把这个状态直说。

夜里 23:30–07:30 她默认**不出声**：到点的话推到早上 07:30 进通知栏。想让她夜里也说，
去设置里打开「夜里也让她说」。她不是系统闹钟，也不会出现在你的时钟 App 里。

`beifen-zhong-v0.1.0-debug.apk` 是开发包（包名多 `.debug`，可与正式版并存）。
里面有开发后门：连点标题 5 次，口令 `307`。**用它试玩会毁掉设计好的节奏**，只给开发者。

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

- 无 `INTERNET`，代码里没有任何网络调用；不读通知栏内容、不要无障碍、不要通讯录
- 通知渠道 `IMPORTANCE_LOW`：不响、不震、不横幅弹出
- 只要 `SCHEDULE_EXACT_ALARM`（你可以在系统设置里撤掉它），不要 `USE_EXACT_ALARM`
- `allowBackup=false`：不备份、不上传、不统计
- 唯一的例外要说清楚：合并清单后 APK 里多出四条我们没写的权限——
  `WAKE_LOCK` / `ACCESS_NETWORK_STATE` / `FOREGROUND_SERVICE`（全部来自 `androidx.work`，
  重启补投靠它）和 `cn.linan.backingup.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
  （androidx.core 给运行期注册接收器加的自定义签名级权限，只发给这个包自己）。
  `ACCESS_NETWORK_STATE`
  只表示"能不能读到网络连接状态"，不给任何出网能力，我们的代码从未查询它。
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
