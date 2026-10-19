# ST-Ctrl

[![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green.svg)](#安装)
[![SillyTavern](https://img.shields.io/badge/SillyTavern-1.19.0-orange.svg)](https://github.com/SillyTavern/SillyTavern)

在 Android 设备上运行 SillyTavern：应用内置自编译的 Node.js 运行时、经过修改的酒馆核心与 WebView 外壳。

## 这是什么

ST-Ctrl 是 SillyTavern 的非官方 Android 容器。它把项目自编译的 Node.js v24.20.0 和经过修改的 SillyTavern 1.19.0 核心一起打进应用，在应用内启动服务、用 WebView 打开界面，并配一套原生控制台来管理服务、文件、扩展、备份和更新。

- **运行时随包分发**：Node 运行时随应用分发，安装后即可使用
- **资源内置**：Node.js 运行时、酒馆核心与前端资源随应用分发，首次启动自动解压
- **非官方移植**：与 SillyTavern 官方无关；对上游源码的修改见 [NOTICE](NOTICE)，许可结构见 [许可与致谢](#许可与致谢)

## 功能

| | |
|---|---|
| 内置运行时 | Node.js v24.20.0，arm64 与 armeabi-v7a 各自一份自编译版本 |
| 内置核心 | SillyTavern 1.19.0，支持应用内检查与更新核心 |
| 角色记忆 | Node 24 内置 SQLite + 本地向量检索；默认关闭，默认全部在设备本地处理 |
| 长聊天 | 末尾优先渲染、随滚动补全，先显示最近的消息再加载更早的 |
| 兼容模式 | 旧 WebView 上对现代 CSS（color-mix、oklch、Tailwind v4 等）的转换与降级 |
| 控制台 | 服务器状态、文件管理、扩展管理、角色卡与世界书、备份还原、运行日志 |
| 局域网访问 | 访问密钥校验；电脑访问期间手机进入酒馆会断开电脑连接 |
| 备份 | 公共目录或应用私有目录，可选是否包含 API Key |

## 安装

1. 到 [Releases](https://github.com/wancDDY/ST-Ctrl/releases) 下载对应架构的 APK：
   - `ST-Ctrl-v2.0.0-arm64.apk`：绝大多数手机
   - `ST-Ctrl-v2.0.0-arm32.apk`：较老的 32 位设备
2. 允许「安装未知应用」后安装，要求 Android 8.0（API 26）以上。
3. 首次启动会解压核心并编译前端，视机型需要一段时间，之后进入酒馆。

### 从 v1.0.2 迁移

v2.0.0 换了应用标识（`com.tavern.app` → `com.stctrl.mobile`），新旧版本可以并存，但不能直接覆盖安装：

1. 打开旧版「备份与还原」，创建完整备份到公共目录
2. 安装 v2.0.0，停止旧版服务，再在「还原备份」里恢复数据
3. 确认聊天、角色卡、扩展都在之后，再卸载旧版

## 使用

- **进入酒馆**：控制台首页点「SillyTavern」；返回控制台后服务继续在后台运行
- **运行日志**：控制台「运行日志」分「服务端」与「页面控制台」两个页签；正文可长按圈选，拖两端手柄调整选区，整页复制在右上角
- **局域网访问**：控制台首页「浏览器与局域网」，用电脑浏览器打开手机上显示的地址
- **角色记忆**：「扩展管理 → 角色记忆」，默认关闭；开启后可收录旧聊天、编辑记忆、测试召回
- **酒馆优化**：设置里的「酒馆优化」按兼容渲染、启动与响应、长聊天、资源与稳定性分组；兼容渲染与长聊天在重新进入酒馆时重载页面，角色卡低内存导入需要重启服务，其余改动不需要重启


## 数据目录

```text
/data/data/com.stctrl.mobile/files/
├── core/                          酒馆核心
│   ├── data/default-user/         聊天、角色卡、世界书等
│   │   ├── chats/
│   │   ├── characters/
│   │   ├── worlds/
│   │   ├── themes/
│   │   ├── groups/
│   │   └── settings.json
│   ├── tavern-opts.json           酒馆优化开关（角色卡导入、角色记忆、预设面板、请求复用）
│   └── data/stctrl-memory.sqlite  角色记忆数据库
└── TavernBackups/                 应用私有目录下的备份
```

备份默认写在 `/sdcard/Android/data/com.stctrl.mobile/files/TavernBackups/`，也可以选择公共目录；外部存储不可用时退回上面的 `TavernBackups/`。

备份文件可能包含聊天记录、角色卡和 API key。包含 API key 的备份不应公开分享。

## 技术架构

```text
┌──────────────────┐
│ WebView          │
│ SillyTavern UI   │
└─────────┬────────┘
          │ HTTP 127.0.0.1:8000
┌─────────▼────────┐
│ Node.js 服务      │
│ tavern-core      │
│ stctrl-runtime   │
└─────────┬────────┘
          │ dlopen / JNI
┌─────────▼────────┐
│ Android 壳        │
│ Compose + Kotlin  │
└──────────────────┘
```

Android 应用数据目录默认以 `noexec` 挂载，Node.js 无法作为独立可执行文件运行。ST-Ctrl 在 JNI 层加载 `libnode.so` 并调用 `node::Start`，将 Node 事件循环运行在独立进程。

## 角色记忆

控制台“扩展管理 → 角色记忆”为酒馆提供本地长期记忆，默认关闭，整理结果可能不完整或不稳定。原生记忆功能要求酒馆核心 `1.19.0` 或更高版本；旧核心会显示最低版本要求，未达版本时不会进入该功能。

记忆中心分为概览、故事、人物、物品地点、记忆本和设置六个页面。消息使用 SQLite FTS5 筛选候选，再结合本地哈希向量、可选模型向量和 RRF 融合。结构化剧情会整理为实体、别名、事实、关系、地点与场景，并生成阶段摘要和聊天总览。`SceneInfo`、`environment_details`、`location`、`items`、`<Memory>` 和 `<tableEdit>` 中的地点、物品、任务、表格和在场角色状态也会进入记忆。切换 Swipe 时只继承共同前缀。每条记忆严格隔离在 `用户 + 聊天 + 分支` 内。

“收录旧聊天”把已经聊过的内容写进记忆库：只做本地入库（消息与本地剧情摘要），不调用模型，也不修改聊天原文；长聊天按时间片续跑，显示进度、失败记录和最终结果。设置页可分别开关向量检索、查询重写与结果重排：关闭时这一层完全不用，开启但没填服务时用设备本地实现，填好并测试通过后改用所填服务。参与嵌入、重写和重排的文本会发送到所填服务，外部服务不可用时会退回设备本地，不阻塞聊天生成。数据库保存在 App 私有目录的 `core/data/stctrl-memory.sqlite`，基础能力不依赖外部服务。

## 从源码构建

核心包与 Node.js 原生库使用 Git LFS 存储。克隆前必须先安装 Git LFS，否则拿到的是一百多字节的指针文件，构建出的安装包无法运行。

```bash
git lfs install
git clone https://github.com/wancDDY/ST-Ctrl.git
cd ST-Ctrl
git lfs pull
```

已有克隆在仓库根目录补两句即可：

```bash
git lfs install
git lfs pull
```

GitHub 网页上的 “Download ZIP” 不包含 LFS 内容，不要用它取源码。

核对是否拉全，每行开头的 `*` 表示已实体化，`-` 表示仍是指针：

```bash
git lfs ls-files
```

需要实体化的共 5 个文件：`tavern-core.zip` 约 127 MB，两个 ABI 各一份 `libnode.so`（arm64 约 112 MB、armeabi-v7a 约 133 MB）与各一份 `libc++_shared.so`（约 1.3 MB 与 0.9 MB）。

主要目录：

```text
tavern-app/                                              Android Gradle 工程
tavern-app/app/src/main/assets/core/tavern-core.zip     内置酒馆核心
tavern-app/app/src/main/jniLibs/                         Node.js 原生库
```

环境要求：

- Android SDK 34
- Android NDK 29
- CMake 3.22 或更高版本
- JDK 17

构建 Debug APK：

```bash
cd tavern-app
./gradlew.bat assembleDebug
```

运行测试：

```bash
./gradlew.bat test
```

（非 Windows 系统把 `gradlew.bat` 换成 `./gradlew`。）

## 许可与致谢

打包后的产物整体按 GNU AGPL-3.0 发布，全文见 [LICENSE](LICENSE)；Android 壳层中未派生自上游的部分按 MIT，范围与全文见 [NOTICE](NOTICE) 与 [LICENSE-MIT](LICENSE-MIT)。

内置核心是 SillyTavern 的修改版，修改者为 wancDDY，修改起始日期 2026-06-04。上游文件清单、随包第三方组件与许可正文的位置都列在 [NOTICE](NOTICE) 里。
