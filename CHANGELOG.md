# ST-Ctrl 更新日志

## v1.0.1 (2026-06-06)

### 紧急修复
- **修复数据丢失严重 bug** — 应用内更新 ST 核心后重启 APP，会导致所有聊天记录、角色卡、世界书被清空。原因是 `AssetExtractor` 版本比较逻辑错误 + 提取时未备份 `data/` 目录。现已修复：
  - `needsExtraction()` 改为仅当 APK 版本**严格高于**已安装版本时才触发提取
  - `extractCore()` 在删除 `core/` 前自动备份全部用户数据，提取后恢复

### 新增
- 实时日志查看器 — 控制台新增页面，可查看 Node.js 服务端运行输出
- 文件管理器 — 浏览/编辑/重命名/删除 `core/` 目录下的文件，支持图片预览
- 代码编辑器 — 全屏编辑器，行号、双指缩放、语法高亮、键盘适配、未保存提示
- JNI 日志回调 — C++ 层管道输出同时转发到 Kotlin `LogCollector`，实现日志实时显示

### 改进
- 控制台主页支持垂直滚动，适配更多卡片
- 日志查看器：白底黑字亮色主题，大字体，筛选/搜索/复制
- 代码编辑器：根层双指手势检测，大文件自动关闭高亮防 OOM
- 编辑器从 `Dialog` 改为全屏 overlay，继承 Activity 的 IME resize 行为

---

## v1.0.0 (2026-05-31)

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
