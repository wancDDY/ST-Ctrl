# 🍺 ST-Ctrl — 把 SillyTavern 装进口袋

> 不需要 Termux，不需要命令行，一个 APK，装好就能聊。

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)

---

## 📱 安装

下载 APK → 允许「未知来源」→ 安装打开。首次启动自动解压内置的 ST 源码（约几十秒），之后打开秒进。最低 Android 8.0。

---

## 🍺 酒馆体验

进入酒馆后就是完整的 SillyTavern 界面，和平时在浏览器里玩的一模一样。简单说就是给 ST 套了一层壳，让它以原生 App 的形式运行在手机上。后台通过前台服务保活，锁屏也不会掉线。

---

## 📦 核心功能

### 🔒 备份系统

真正做到 **1:1 备份，原封不动还原**。

一键打包所有用户数据：
- 角色卡（含头像、定义文件）
- 对话记录（完整聊天历史）
- AI 采样预设（含预设内的全部条目和用户选择）
- 群组配置和群聊记录
- 世界书 / 知识库
- 用户扮演角色（Persona）
- 扩展插件（含记忆扩展的向量数据等）
- 自定义背景、主题、快捷回复
- API 密钥、全局设置、UI 布局

无论你玩到什么程度，只要备份过，还原后就是一模一样的状态。不是"差不多"——是**一模一样**。

支持：
- 手动备份，自定义文件名
- 自动定时备份（每日 / 每 3 天 / 每周）
- 保留最近 N 份，自动清理旧备份
- 还原时列出所有备份，点开可预览内容

### 🧩 扩展管理

- 从 GitHub 仓库或任意 zip 直链安装第三方扩展
- 已安装扩展的更新检测
- 一键卸载
- 查看安装来源地址

### 🎭 角色卡管理

- 网格展示所有角色卡，点进去看描述、开场白
- 自动关联世界书和内嵌正则脚本
- 支持编辑备注、删除角色及关联数据

### 📊 其他功能

- **服务器状态**：实时查看 Node 运行状态、端口、运行时长
- **存储概览**：核心代码、用户数据、备份文件分别占用空间，一目了然
- **清除缓存**：释放存储空间
- **四个性能模式**：性能优先 / 轻度优化 / 均衡 / 深度优化
- **深色 / 浅色模式**：控制台主题自由切换

### 🌐 原生化体验

- 顶部导航点选后全屏覆盖，带动画过渡
- 返回键逻辑：WebView 内返回控制台，控制台双击退出
- 前台服务后台保活，通知栏常驻

---

## 🛠 技术栈

| 层级 | 技术 |
|------|------|
| 原生壳 | Kotlin + Jetpack Compose |
| 运行时 | `nodejs-mobile` (libnode.so, v18.20.4) |
| 渲染 | Android WebView (硬件加速) |
| 后台 | Foreground Service + AlarmManager |
| 主题 | Material 3 + Neon Nocturne (#D4A853 琥珀金) |

---

## 🚧 已知问题

- 更新核心（ST 版本更新）仍在开发中
- 包体较大，核心解压后约 140MB（ST 源码 25000+ 文件），首次启动需要几秒钟解压
- 需要手动关闭电池优化，否则后台可能被系统杀掉
- 目前 arm64 设备完整支持（其他架构理论上可行）

---

## 🏗 构建

```bash
# 前置：Android Studio + NDK 26+ + CMake 3.22+
cd tavern-app
./gradlew assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk
```

---

## 📄 许可

本项目壳层代码以 MIT 协议开源。SillyTavern 版权归其原作者及社区贡献者所有，本应用非官方产品。

---

## ⚠️ 声明

本应用仅供个人学习与娱乐使用。AI 内容由第三方 API 生成，使用者需自行承担相关风险并遵守服务条款。

---

## 🙏 致谢

- [SillyTavern](https://github.com/SillyTavern/SillyTavern) — 最好的 AI 角色扮演前端
- [nodejs-mobile](https://github.com/nodejs-mobile/nodejs-mobile) — Node.js 移动端移植
