# 护眼TV · EyeCareTV

**到点就全屏打断你，播完眼保健操自动放行。**

一个 Windows / Android 双端护眼提醒工具：累计屏幕使用时长达到阈值后，自动全屏播放眼保健操视频，
播放结束再恢复使用。Windows 端基于 Python 与 PySide6，Android 端为 Kotlin 原生实现，
界面原生、无浏览器依赖、可离线运行。

内置 **AI 智能护眼引擎**：基于强化学习（UCB1 多臂老虎机）自适应调整提醒间隔，
通过在线学习的疲劳预测模型实时评估眼疲劳指数，并生成个性化护眼洞察。

![Python](https://img.shields.io/badge/Python-3.11%2B-3776AB?logo=python&logoColor=white)
![PySide6](https://img.shields.io/badge/PySide6-6.x-41CD52?logo=qt&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)
![Windows](https://img.shields.io/badge/Windows-10%2F11-0078D4?logo=windows&logoColor=white)
![Android](https://img.shields.io/badge/Android-8.0%2B-34A853?logo=android&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)

![设置界面](docs/settings.png)


## 为什么做这个

常见的护眼工具只弹一个通知，几秒后就被关掉，形同虚设。护眼TV 换了个思路：
把提醒做成一次**默认无法跳过的全屏播放**，用真实的眼保健操视频占住屏幕 5 分钟，只保留长按 3 秒的紧急通道。
视频播完自动关闭并重新计时，不打扰正常工作节奏。

## 功能（Windows 客户端）

| 能力 | 说明 |
|---|---|
| 定时提醒 | 默认 30 分钟，可调 5–180 分钟 |
| 空闲暂停 | 连续 3 分钟无键盘鼠标输入自动暂停累计，回来继续 |
| 强制全屏 | 置顶无边框窗口播放视频，屏蔽 Esc 与 Alt+F4，播放期间阻止息屏 |
| 自动恢复 | 播完关闭窗口、累计清零，重新开始计时 |
| 失败兜底 | 视频无法播放时降级为 5 分钟闭眼倒计时，流程不会卡死 |
| 延迟一次 | 可推迟 5 分钟，第二次提醒不再提供该按钮 |
| 免打扰时段 | 时段内到点先挂起，时段结束立即补弹 |
| 托盘常驻 | 显示剩余时间，支持立即休息、暂停 30 分钟、开机自启、退出 |
| 单实例 | 重复启动时第二个进程自动退出，避免计时翻倍 |
| 自定义视频 | 设置页可选本地视频，自动复制到用户目录，不动内置素材 |
| **AI 疲劳预测** | 基于连续用屏时长、距上次休息、延迟次数、时段、空闲占比等特征，在线学习预测 0-100 疲劳指数 |
| **AI 自适应间隔** | UCB1 多臂老虎机从你的"完成/延迟"反馈中学习最优提醒间隔，越用越懂你 |
| **AI 护眼报告** | 今日休息/延迟/用屏统计 + 疲劳仪表盘 + 个性化洞察建议 |
| **AI 提醒建议** | 提醒弹窗展示根据你用眼习惯生成的个性化建议 |

## Windows 快速开始

环境要求：Windows 10/11、Python 3.11 及以上。

```powershell
git clone https://github.com/tenderxiamu/eye-care-tv.git
cd eye-care-tv
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements.txt
.venv\Scripts\pythonw.exe main.py
```

也可以直接双击 `run.bat` 启动。

启动后程序驻留系统托盘，不会出现在任务栏。右键托盘图标可以打开设置。
想立刻看到效果，点托盘菜单里的「立即休息」。

## Android 客户端

同一套逻辑的 Kotlin 原生实现：前台服务累计亮屏使用时长，到点用悬浮窗全屏播放视频，
播完自动放行。只统计「亮屏且已解锁」的时间，息屏、锁屏自动暂停。

| 能力 | 说明 |
|---|---|
| 前台服务 | `FOREGROUND_SERVICE_SPECIAL_USE` 常驻计时，通知栏显示剩余时间 |
| 全屏悬浮窗 | `SYSTEM_ALERT_WINDOW` 覆盖在任意应用之上，播放期间保持屏幕常亮 |
| 权限引导 | 通知、悬浮窗、忽略电池优化三项逐项检测，未授权时说明影响 |
| 权限降级 | 没有悬浮窗权限时降级为高优先级通知 + 全屏提醒页 |
| 自动恢复 | 视频播完关闭悬浮窗，累计清零重新计时 |
| 失败兜底 | 视频缺失或解码失败时降级为 5 分钟闭眼倒计时 |
| 延迟一次 | 5 分钟后再提醒，第二次提醒不再提供该按钮 |
| 免打扰 | 时段内到点先挂起，时段结束立即补弹 |
| 开机自启 | 重启后自动恢复计时 |
| 自定义视频 | 从文件选择器挑视频，复制到应用私有目录，不动内置素材 |
| 应用白名单 | 勾选的应用在前台时不打断，切出后再补弹（需「使用情况访问」权限） |
| 纯音频模式 | 仅播放音频，不显示画面；默认仍为视频模式 |
| 紧急跳过 | 提醒页任意位置长按 3 秒跳过本次，应对抢票、看急救信息等场景 |
| 护眼统计 | 今日/本周打断次数与累计观看时长，仅在本地存储 |
| 首次引导 | 三步授权向导，按机型给出华为/小米/OPPO/vivo 的保活路径 |

<p>
  <img src="docs/android-home.png" width="30%" />
  <img src="docs/android-settings.png" width="30%" />
  <img src="docs/android-report.png" width="30%" />
</p>
<p align="center">
  <img src="docs/android-reminder.png" width="100%" />
</p>

### 构建

要求 JDK 17+ 与 Android SDK（compileSdk 36）。`android/local.properties` 保存本机 SDK 路径，
该文件不入库，需自行创建：

```properties
sdk.dir=C\:\\Android\\Sdk
```

```powershell
cd android
.\gradlew :app:assembleDebug
```

产物在 `android/app/build/outputs/apk/debug/app-debug.apk`，约 36 MB（其中内置视频 32 MB）。
内置视频直接复用 `app/resources/default_eye_exercise.mp4`，由 Gradle 在构建时拷贝进 `res/raw`，
Git 仓库里只存一份。

### 首次使用

1. 打开「护眼TV」，按设置页顺序授权：通知权限 → 悬浮窗权限 → 忽略电池优化
2. 国产 ROM 还需在系统管家中允许「自启动 / 后台运行」
3. 点「立即休息（预览提醒）」，可立刻查看全屏提醒效果

状态机与 Windows 端一致（`计时中 / 暂停 / 挂起 / 提醒 / 延后`），
见 [TimerEngine.kt](android/app/src/main/java/com/tenderxiamu/eyecaretv/TimerEngine.kt)，
纯 JVM 逻辑，可脱离 Android 框架做单元测试。

## 两端能力对照

两端不是逐项镜像，而是围绕同一套核心机制做端侧适配。

**共通机制**

- 到点全屏打断、播完自动放行，只累计真实使用时间（Windows 键鼠空闲自动暂停 / Android 息屏锁屏自动暂停）
- 五状态机：计时中 / 暂停 / 挂起 / 提醒 / 延后
- 延迟 5 分钟且仅一次、免打扰时段挂起后补弹、视频异常降级为 5 分钟闭眼倒计时、自定义视频、开机自启

**平台专属**

| 能力 | Windows | Android |
|---|---|---|
| 常驻形态 | 系统托盘显示剩余时间 | 前台服务 + 通知栏显示剩余时间 |
| 打断方式 | 置顶无边框窗口，屏蔽 Esc 与 Alt+F4 | `TYPE_APPLICATION_OVERLAY` 悬浮窗，覆盖任意应用 |
| 场景避让 | 键鼠连续空闲自动暂停 | 应用白名单延后提醒 + 纯音频模式 |
| 逃生通道 | 无专用通道（可延迟一次） | 任意位置长按 3 秒跳过 |
| 数据沉淀 | AI 疲劳预测、自适应间隔、护眼报告与个性化洞察 | AI 疲劳预测、自适应间隔、护眼报告与洞察，附今日/本周统计 |
| 上手引导 | 设置页逐项说明 | 首启三步授权向导 + 国产 ROM 保活路径 |

差异来自系统能力边界：Windows 能拿到全局键鼠空闲状态、进程可长期驻留，所以避让可以做到「人不在就暂停」；
Android 需要前台服务、悬浮窗和使用情况访问权限才能维持计时与覆盖显示，因此避让只能基于「当前前台应用」。

## 工作原理（Windows）

```mermaid
flowchart LR
    A[Win32 空闲检测] --> B[计时状态机]
    B -->|达到阈值| C[全屏提醒窗]
    C -->|视频播放结束| D[累计清零]
    C -->|延迟 5 分钟| B
    B -->|空闲超阈值| E[暂停累计]
    E -->|恢复输入| B
    B -->|免打扰时段| F[挂起提醒]
    F -->|时段结束| C
```

核心是 [timer_engine.py](app/core/timer_engine.py) 里的五状态机：
`计时中 / 暂停 / 挂起 / 提醒 / 延后`。UI 只负责呈现，所有判定逻辑集中在状态机中，
因此可以脱离界面直接做单元测试。

## 配置

配置保存在 `%APPDATA%\EyeCareTV\config.json`，首次启动自动生成。

| 字段 | 默认值 | 说明 |
|---|---|---|
| `remind_minutes` | 30 | 提醒间隔（分钟） |
| `idle_minutes` | 3 | 空闲暂停阈值（分钟） |
| `snooze_minutes` | 5 | 延迟时长（分钟） |
| `volume` | 50 | 提醒音量（0–100） |
| `video_path` | 空 | 自定义视频路径，空则使用内置视频 |
| `dnd_enabled` | false | 是否启用免打扰时段 |
| `dnd_start` / `dnd_end` | 12:00 / 13:00 | 免打扰起止时间，支持跨午夜 |
| `auto_start` | false | 是否开机自启 |
| `smart_enabled` | true | 启用 AI 智能护眼（疲劳预测 + 自适应间隔 + 洞察） |
| `smart_mode` | "adaptive" | 提醒策略："fixed" 固定间隔 / "adaptive" AI 自适应 |

在设置页选择自定义视频后，文件会被复制到 `%APPDATA%\EyeCareTV\media\`，内置视频不受影响。

## AI 智能护眼引擎

所有 AI 逻辑集中在 [app/core/smart_engine.py](app/core/smart_engine.py)，纯 Python 实现、无第三方依赖，
可离线运行、可单元测试。包含三个核心组件：

```mermaid
flowchart LR
    A[每秒心跳] --> B[会话追踪器<br/>累计活跃/空闲时间]
    B --> C[疲劳预测模型<br/>在线学习权重]
    B --> D[UCB1 老虎机<br/>自适应间隔]
    C --> E[疲劳指数 0-100]
    D --> F[建议提醒间隔]
    G[用户反馈<br/>完成/延迟] --> C
    G --> D
    C --> H[个性化洞察]
```

### 1. 疲劳预测模型（在线学习）

基于 5 个特征预测 0-100 的眼疲劳指数：

| 特征 | 说明 |
|---|---|
| 连续屏幕时长 | 本次累计未休息的用屏分钟数 |
| 距上次休息 | 距离上次完整休息的时长 |
| 延迟连击 | 连续被延迟的次数 |
| 时段因子 | 22:00-06:00 最高，午后次之 |
| 空闲占比 | 近期空闲时间比例（越低越累） |

模型权重随用户反馈**在线更新**（梯度下降式）：
- 延迟提醒 → 说明疲劳被高估，下调相关特征权重
- 完整看完 → 说明时机合适，权重向默认值轻微收敛

### 2. 自适应间隔优化器（UCB1 多臂老虎机）

候选间隔为 `[20, 25, 30, 35, 40, 45, 50, 60]` 分钟，每次提醒完成后：
- 完整看完 → 奖励 +1.0
- 被延迟 → 奖励 -0.3

使用 **UCB1（Upper Confidence Bound）** 算法在"探索新间隔"和"利用已知好间隔"之间平衡，
逐步学到最适合该用户的提醒节奏。数据不足 3 次时保持用户设置，避免初期乱调。

### 3. 个性化洞察生成器

聚合最近 30 条使用记录，自动生成洞察，例如：
- 延迟率过高 → 提示当前间隔可能偏短
- 识别用眼高峰时段
- 工作日 vs 周末用屏差异
- 深夜用眼提醒

### 数据持久化

- 使用记录：`%APPDATA%\EyeCareTV\usage.jsonl`（追加式，每次提醒一条）
- 模型状态：`%APPDATA%\EyeCareTV\smart_model.json`（权重 + 老虎机统计）

## 打包

```powershell
.venv\Scripts\python.exe -m PyInstaller --noconsole --onedir --name EyeCareTV --add-data "app/resources;app/resources" --clean --noconfirm main.py
```

产物在 `dist/EyeCareTV/`，约 159 MB。分发时拷贝整个文件夹，双击 `EyeCareTV.exe` 即用，
目标机器无需安装 Python。

## 测试

Windows：

```powershell
.venv\Scripts\python.exe -m unittest discover -s tests -v
```

覆盖五个场景：到点触发、空闲暂停与恢复、延迟一次、播放完成归零、免打扰挂起。
测试注入伪造时钟与空闲读数，不依赖真实等待。

AI 引擎测试（`tests/test_smart_engine.py`，25 个用例）：
- 疲劳模型：预测范围、高低疲劳对比、在线学习权重更新、序列化
- UCB1 老虎机：未尝试臂优先、奖励统计、最优间隔选择、探索-利用平衡
- 使用存储：追加/读取/截断/清空
- 洞察生成：无数据、高延迟率、高峰时段、深夜使用
- 智能引擎端到端：完成流程、延迟流程、持久化跨实例恢复

Android：

```powershell
cd android
.\gradlew :app:testDebugUnitTest
```

覆盖六个场景，包括跨午夜免打扰。

## 项目结构

```
eye-care-tv/
├─ main.py                    入口：单实例、信号接线、事件循环
├─ app/
│  ├─ core/
│  │  ├─ idle_detector.py     Win32 键鼠空闲检测
│  │  ├─ timer_engine.py      计时状态机
│  │  ├─ smart_engine.py      AI 智能护眼引擎（疲劳预测+UCB1+洞察）
│  │  ├─ dnd.py               免打扰时段判断
│  │  └─ autostart.py         开机自启注册
│  ├─ ui/
│  │  ├─ overlay.py           全屏提醒窗与视频播放
│  │  ├─ report.py            AI 护眼报告对话框
│  │  ├─ settings.py          设置页
│  │  └─ tray.py              托盘菜单
│  ├─ storage/config.py       配置读写
│  └─ resources/              内置眼保健操视频
├─ android/                   Android 客户端（Kotlin + Gradle）
│  └─ app/src/main/java/com/tenderxiamu/eyecaretv/
│     ├─ EyeCareService.kt    前台服务：计时、通知、悬浮窗调度
│     ├─ TimerEngine.kt       五状态机（纯 JVM，可单测）
│     ├─ ReminderView.kt      全屏提醒 UI 与视频播放
│     ├─ MainActivity.kt      权限引导与设置页
│     └─ BootReceiver.kt      开机自启
├─ tests/                     计时状态机单元测试
├─ tools/check_idle.py        空闲检测验证脚本
└─ requirements.txt
```

## Roadmap

- [x] Windows 客户端（计时、全屏提醒、视频播放、托盘、自启）
- [x] Windows 端 AI 智能护眼引擎（疲劳预测 + 自适应间隔 + 护眼报告）
- [x] Android 客户端（前台服务 + 悬浮窗 + 通知降级）
- [x] Android 端应用白名单、纯音频模式、护眼统计与首启引导
- [x] Android 端 AI 智能护眼引擎与护眼报告
- [ ] 自定义提醒文案（双端可配置）
- [ ] Windows 端纯音频模式
- [ ] 单文件 exe 打包，进一步压缩体积
- [ ] 多显示器独立布局与镜像播放

## 贡献

欢迎提 Issue 和 PR。提交前请确保两端单测都通过：

```powershell
.venv\Scripts\python.exe -m unittest discover -s tests -v
cd android; .\gradlew :app:testDebugUnitTest
```

全部用例通过。

## 已知限制

- 无法拦截 `Ctrl+Alt+Del`、任务管理器或系统强杀，定位是强力提醒而非防沉迷
- 独占全屏游戏可能覆盖置顶窗口
- 挂机播放视频会被判定为空闲，不计入使用时长
- Android 上按 Home 键可暂时离开悬浮窗；部分国产 ROM 会清理前台服务，需手动加白名单
- Android 只累计「亮屏且解锁」的时间，息屏与锁屏期间不计时
- 若项目目录路径含中文等非 ASCII 字符，Gradle 单元测试 worker 可能报 ClassNotFoundException；
  把仓库放到纯英文路径即可（构建 APK 不受影响）

## License

[MIT](LICENSE) © 2026 tenderxiamu

![Star History](https://api.star-history.com/svg?repos=tenderxiamu/eye-care-tv&type=Date)
