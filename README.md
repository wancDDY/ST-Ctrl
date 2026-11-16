# ST-Ctrl

[![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green.svg)](#安装)
[![SillyTavern](https://img.shields.io/badge/SillyTavern-1.19.0-orange.svg)](https://github.com/SillyTavern/SillyTavern)

在 Android 设备上运行 SillyTavern：应用内置自编译的 Node.js 运行时、经过修改的酒馆核心与 WebView 外壳。

## 这是什么

ST-Ctrl 是 SillyTavern 的非官方 Android 容器。它把项目自编译的 Node.js v24.20.0 和经过修改的 SillyTavern 1.19.0 核心一起打进应用，在应用内启动服务、用 WebView 打开界面，并配一套原生控制台来管理服务、文件、扩展、备份和更新。

- **运行时随包分发**：Node 运行时随应用分发，安装后直接可用
- **资源内置**：Node.js 运行时、酒馆核心与前端资源随应用分发，首次启动自动解压
- **非官方移植**：与 SillyTavern 官方无关；对上游源码的修改见 [NOTICE](NOTICE)，许可结构见 [许可与致谢](#许可与致谢)

## 功能

| | |
|---|---|
| 内置运行时 | Node.js v24.20.0，arm64 与 armeabi-v7a 各自一份自编译版本 |
| 内置核心 | SillyTavern 1.19.0，支持应用内检查与更新核心 |
| 按聊天记忆 | 每个聊天单独整理摘要与条目；记忆默认开启，用模型整理默认关闭，开启后才调用你在酒馆里配置的模型 |
| 角色卡仓库 | 导入单张卡或整包，按角色保存多个版本；部署到核心前显示将写入的文件名与差异 |
| 长聊天 | 末尾优先渲染、随滚动补全，先显示最近的消息再加载更早的 |
| 兼容模式 | 旧 WebView 上对现代 CSS（color-mix、oklch、Tailwind v4 等）的转换与降级 |
| 控制台 | 服务器状态、文件管理、扩展管理、角色卡与世界书、备份还原、运行日志 |
| 局域网访问 | 访问密钥校验；电脑访问期间手机进入酒馆会断开电脑连接 |
| 备份 | 公共目录或应用私有目录，可选是否包含 API Key |

## 安装

1. 到 [Releases](https://github.com/wancDDY/ST-Ctrl/releases) 下载对应架构的 APK：
   - `ST-Ctrl-v2.1.0-arm64.apk`：绝大多数手机
   - `ST-Ctrl-v2.1.0-arm32.apk`：较老的 32 位设备
2. 允许「安装未知应用」后安装，要求 Android 8.0（API 26）以上。
3. 首次启动会解压核心并编译前端，视机型需要一段时间，之后进入酒馆。

从 1.0.x 升级的设备不能覆盖安装（v2.0.0 起应用标识已变更）：先在旧版创建完整备份，卸载旧版，装好新版后再恢复数据。

## 使用

- **进入酒馆**：控制台首页点「SillyTavern」；返回控制台后服务继续在后台运行
- **运行日志**：控制台「运行日志」分「服务端」与「页面控制台」两个页签；正文可长按圈选，拖两端手柄调整选区，整页复制在右上角
- **局域网访问**：控制台首页「浏览器与局域网」，在电脑浏览器里打开手机上显示的地址
- **按聊天记忆**：「扩展 → 记忆库」，记忆默认开启；可以逐个聊天整理，也可以查看和删除已记录的内容
- **角色卡仓库**：「扩展 → 仓库」，导入单张卡或整包，按角色保存多个版本；部署前显示写入核心的文件名与差异
- **核心优化**：设置里的「核心优化」按兼容渲染、启动与响应、长聊天、资源与稳定性分组；兼容渲染与长聊天在重新进入酒馆时重载页面，角色卡低内存导入需要重启服务，其余改动不需要重启


## 数据目录

```text
/data/data/com.stctrl.mobile/files/
├── core/                          核心
│   ├── data/default-user/         聊天、角色卡、世界书、主题与分组
│   ├── data/stctrl-chat-memory/   按聊天记忆的摘要与条目
│   ├── public/                    核心前端，首次启动时解压并编译
│   ├── config.yaml                核心配置
│   └── tavern-opts.json           核心优化开关（角色卡导入、按聊天记忆、预设面板、请求复用）
├── character-library/             角色卡仓库：原始卡文件与各个版本
├── stctrl-app.log                 应用与 Node 输出
├── tavern-server.log              核心服务端输出
└── TavernBackups/                 内部存储上的备份（外部存储不可用时的回退位置）
```

备份默认写在 `/sdcard/Android/data/com.stctrl.mobile/files/TavernBackups/`，也可以选择公共目录；外部存储不可用时退回应用内部存储的同名目录。

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

## 按聊天记忆

控制台「扩展 → 记忆库」按聊天保存记忆，记忆默认开启。每个聊天各自有一份摘要与条目；整理只读取上次整理之后新增的消息，不修改聊天原文。

「用模型整理」默认关闭；开启后整理使用你在酒馆里配置的模型，聊天内容会作为整理请求发送给该模型服务。没有配置模型时不会发起整理。

记忆页分「记忆」与「上下文」两栏：前者是固定记忆与按时间分组的条目，后者是最近摘要、当前状态、人物与地点、属性与转折、更早的经过。条目可以固定、编辑、删除与撤销，也可以查看来源原文。

原文检索分三档：按字面匹配、按本机模型计算相似度、外部向量接口。本机模型随应用提供，开启后建立索引；外部接口会把参与检索的文本发送到所填服务。

## 从源码构建

核心包与 Node.js 原生库使用 Git LFS 存储。克隆前必须先安装 Git LFS，否则拿到的只是指针文件，构建出的安装包无法运行。

```bash
git lfs install
git clone https://github.com/wancDDY/ST-Ctrl.git
cd ST-Ctrl
git lfs pull
```

已有克隆在仓库根目录补这两句：

```bash
git lfs install
git lfs pull
```

GitHub 网页上的 “Download ZIP” 拿不到 LFS 内容，取源码请用 `git clone`。

核对是否拉全，每行开头的 `*` 表示已实体化，`-` 表示仍是指针：

```bash
git lfs ls-files
```

需要实体化的共 5 个文件：`tavern-core.zip`，两个 ABI 各一份 `libnode.so` 与 `libc++_shared.so`。

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
