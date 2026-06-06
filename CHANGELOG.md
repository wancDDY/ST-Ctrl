# ST-Ctrl 更新日志

## v1.0.1

### 修复
- 修复数据丢失严重 bug — 应用内更新 ST 核心后重启 APP 会导致聊天记录、角色卡被清空
- `needsExtraction()` 改为仅当 APK 版本严格高于已安装版本时才触发提取
- `extractCore()` 在删除 core/ 前自动备份全部用户数据，提取后恢复

---

## v1.0.0

- 初始发布
- Kotlin + Jetpack Compose 原生 UI
- 嵌入式 Node.js v18.20.4 (JNI dlopen)
- WebView 承载 SillyTavern 界面
- 4 档性能模式 (FULL / LIGHT / BALANCED / SAVE)
- ZIP 备份/还原系统
- ST 核心版本管理 + 应用内更新
- Termux 迁移工具
- 角色卡世界书匹配
- 前台服务保活
