# 🍺 ST-Ctrl — 酒馆的 Android 新家

> 不需要 Termux，不需要命令行，一个 APK，装好就能聊。

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)

---

## 这是什么

把 [SillyTavern](https://github.com/SillyTavern/SillyTavern)（1.18.0）完整装进手机：Node.js 运行时、酒馆服务、控制台管理，全部内置在一个 APK 里。安装即用，数据完全保存在本地。

---

## ✨ 核心功能

### 🚀 开箱即用
- 首次启动自动解压内置 ST 核心（约几十秒），之后秒进
- 酒馆服务独立进程运行 + 前台服务保活，锁屏不掉线
- 重启 App 自动恢复服务，无需手动启动

### 🔒 1:1 备份系统
- 一键打包全部用户数据：角色卡、聊天记录、预设、群组、世界书、Persona、扩展、API 密钥、全局设置
- 手动备份（自定义文件名）/ 还原失败自动回滚
- **启动时自动备份**：按你设定的间隔（如每 3 天），打开 App 时检查并自动备份，无需后台常驻

### 🌐 局域网访问
- 同一 WiFi 下，电脑浏览器访问 `http://手机IP:7999` 即可用酒馆
- 6 位访问码验证（首位字母 + 5 位数字），验证通过后 IP 自动白名单
- 手机与电脑互斥：一端使用，另一端进入会自动断开，不会同时占用
- 支持自定义访问码、自动关闭、保持开启

### 🎨 兼容模式
- 适配 WebView 版本较老的设备：自动转换酒馆用到的现代 CSS 特性
- 解决旧设备上主题/扩展的透明、错乱、模糊失效等渲染问题

### ⌨️ 移动端输入体验
- 输入法弹出不再跳动、黑闪，聊天区高度实时跟随键盘
- Enter 键强制换行，长按呼出右键菜单
- 动画节奏优化（125ms），操作跟手、接近原生手感

### 📊 性能模式
- 连续自适应优化：按设备 CPU 核心动态分配 IO 池
- 崩溃渐进恢复（50%→75%→100%），温控感知自动降载
- 手动模式：性能优先 / 均衡 / 省电三档，可自定义堆上限

### 📁 文件管理
- 浏览酒馆数据目录，面包屑导航、多选批量操作
- 内置文本编辑器：语法高亮 10+ 语言、大文件虚拟化渲染、双指缩放
- ZIP 压缩/解压（带路径穿越防护）

### 🧩 扩展管理
- 从 GitHub 仓库或 zip 直链安装第三方扩展，支持更新检测、一键卸载

### 🎭 角色卡管理
- 网格展示、详情预览、自动关联世界书与内嵌正则，支持编辑备注

### 🛠 其他
- 服务器状态实时监控、存储概览、清除缓存
- 深色/浅色控制台主题
- 崩溃自动诊断（`last-crash.txt` + Node 诊断报告）
- ST 核心与 App 在线更新

---

## 📱 安装

1. 下载 APK（[Releases](https://github.com/wancDDY/ST-Ctrl/releases)）
2. 允许「未知来源」→ 安装
3. 首次启动自动解压核心（约几十秒）

**最低要求**：Android 8.0+，arm64-v8a / armeabi-v7a

---

## 🛠 技术栈

| 层级 | 技术 |
|------|------|
| 原生壳 | Kotlin + Jetpack Compose |
| 运行时 | `nodejs-mobile`（libnode.so, v24.5.0，独立 :node 进程） |
| 渲染 | Android WebView（硬件加速） |
| 后台 | Foreground Service（START_STICKY 保活） |
| 备份 | 启动时触发检查（无 WorkManager 定时依赖） |

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
