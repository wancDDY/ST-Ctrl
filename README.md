# 🍺 ST-Ctrl — 酒馆的 Android 新家

> 不需要 Termux，不需要命令行，一个 APK，装好就能聊。

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)

---

## 📱 安装

下载 APK → 允许「未知来源」→ 安装打开。首次启动自动解压内置的 ST 源码（约几十秒），之后打开秒进。最低 Android 8.0。

---

## 🍺 酒馆体验

进入酒馆后就是完整的 SillyTavern 界面，和平时在浏览器里玩的一模一样。后台通过前台服务保活，锁屏也不会掉线。

---

## 📦 核心功能

### 🔒 备份系统

真正做到 **1:1 备份，原封不动还原**。一键打包所有用户数据：

- 角色卡（含头像、定义文件）
- 对话记录（完整聊天历史）
- AI 采样预设
- 群组配置和群聊记录
- 世界书 / 知识库
- 用户扮演角色（Persona）
- 扩展插件
- 自定义背景、主题、快捷回复
- API 密钥、全局设置、UI 布局

支持：
- 手动备份，自定义文件名
- 自动定时备份（每日 / 每 3 天 / 每周）
- 保留最近 N 份，自动清理旧备份
- 还原失败自动回滚，不丢数据
- 从手机 Termux 迁移数据（生成脚本 → 粘贴命令）

### 📁 文件管理

- 浏览酒馆数据目录，面包屑导航
- 多选模式：批量压缩、导出、删除
- 内置文本编辑器：语法高亮 10+ 语言、行号、撤销/重做
- ZIP 压缩与解压，支持路径穿越防护
- 解压可选目录模式 / 直解模式，支持覆盖/跳过同名文件
- 导入文件自动检测同名冲突

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
- **存储概览**：核心代码、用户数据、备份文件分别占用空间
- **清除缓存**：释放存储空间
- **四个性能模式**：性能优先 / 轻度优化 / 均衡 / 深度省电
- **深色 / 浅色模式**：控制台主题自由切换
- **开机自启**：手机重启后自动恢复酒馆服务
- **更新系统**：ST 核心和 APP 均可在线更新

---

## 🛠 技术栈

| 层级 | 技术 |
|------|------|
| 原生壳 | Kotlin + Jetpack Compose |
| 运行时 | `nodejs-mobile` (libnode.so, v18.20.4) |
| 渲染 | Android WebView (硬件加速) |
| 后台 | Foreground Service + WorkManager |

---

## 🏗 构建

```bash
# 前置：Android Studio + NDK 26+ + CMake 3.22+
cd tavern-app
./gradlew assembleDebug
# 输出：app/build/outputs/apk/debug/
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
