# ST-Ctrl v1.0.2 更新日志

## 稳定性增强

### 崩溃诊断
- 全局异常捕获：闪退自动写 `last-crash.txt`，含设备信息+完整堆栈
- Node.js 崩溃诊断：`--report-on-fatalerror` 参数，崩了自动生成诊断 JSON
- 启动超时诊断：60s+ 自动输出端口/进程状态日志

### 启动优化
- WebView 引擎预热：Application.onCreate 预加载 chromium，进入酒馆更快
- 服务就绪探针：`/api/ping` 确认 Express 完全启动，不再"端口开了但页面 500"
- 角色列表浅加载默认开启：省内存

### 内存优化
- 后台 WebView 30s 自动释放渲染缓存
- 角色列表 `lazyLoadCharacters: true`（Android 强制默认）
- 文件选择器 URI 持久化权限，chromium 流式读取

## 局域网访问

### Token 保护 + 手机/电脑互斥
- 设置 → 启动 → 局域网访问，默认关闭
- 开启后同一 WiFi 下电脑浏览器访问 `http://手机IP:7999`
- 6 位数字访问码验证，验证通过后 IP 自动白名单
- 电脑连入时手机进入酒馆会弹窗提示"电脑正在访问中"，继续进入自动断开电脑
- 保持开启开关：每次启动自动恢复局域网访问状态，无需手动开启
- 30 分钟自动关闭

### 技术实现
- `LanProxy.kt`：Java NIO TCP 代理，Token 门禁 + IP 白名单 + Cookie 会话
- 不碰 `config.yaml` 的 `listen` 参数，服务端仍只监听 127.0.0.1，避免 OPPO 等设备上 `0.0.0.0` 绑定导致的闪退

### 升级安全
- `AssetExtractor` 解压时保留用户 `config.yaml`，版本升级不会覆盖用户自定义的白名单/端口设置

## 动态优化重写
- 离散三档改为连续自适应，堆上限升至 1024MB，IO 池按 CPU 核心数动态分配
- 崩溃渐进恢复：50%→75%→100%
- 温控感知：过热自动降载
- 手动模式：性能优先 / 均衡 / 省电三档
- UI 精简为卡片式

## 文本编辑器修复
- 撤销时光标跳开头 — 修复
- 撤销按字符记录 — 改为同行批量
- 行首 Backspace 合并行 — 修复
- 点击定位后状态栏不更新 — 修复
- 输入法弹出后大量空白 — 修复
- 行间距太紧凑 — 调整

## 文本编辑器重构

### 多层编辑模式
- **小文件（≤2000行）**：原生 `EditText`（AndroidView 桥接），系统级键盘滚动光标
- **大文件（>2000行）**：`LazyColumn` 逐行虚拟化，`LaunchedEffect` 自动滚焦点行
- **选中样式主题化**：高亮色改用 accent 主题色（18%透明度），不再硬编码蓝色

### 键盘遮挡彻底修复
- **`enableEdgeToEdge()`**：在 `MainActivity.onCreate` 中启用，让 Compose 接管窗口 insets
- **`OnGlobalLayoutListener`**：替代 `imePadding()`，直接监听窗口可见区域计算键盘高度，兼容厂商 ROM
- **动态底部 padding**：编辑器根据键盘高度动态增加底部间距，内容始终在键盘上方

### 状态栏光标跟踪
- **`onSelectionChanged`**：EditText 子类 override，实时跟踪光标位置变化
- 状态栏显示「行号:列号」，点击／键盘移动光标时立即更新

### 双指缩放在 EditText 中生效
- 缩放手势已存在，但之前只改了 Compose 字号状态未应用到 EditText
- `update` lambda 中始终执行 `setTextSize(13f * fontScale)`，缩放实时跟随

### 性能优化
- 按文件大小自动分界（2000行），大文件不走 EditText 避免卡顿
- `server-wrapper.js`（ST-Patcher）：同步桌面版 console 重定向段到仓库版

---

## 兼容模式重大更新 — 旧设备 CSS 渲染修复

### 问题背景
Android 9-10 等旧设备的 WebView（Chrome 76-99）不支持以下现代 CSS 特性，导致酒馆主题和扩展 UI 渲染异常（透明、错乱、无颜色）：
- `color-mix()` 颜色混合函数
- `backdrop-filter` 毛玻璃效果
- `oklch()` 颜色空间（Tailwind v4 使用）
- `rgb(from ...)` 相对颜色语法
- `@layer` CSS 级联层
- `@supports` 条件中的嵌套括号
- `:host` 选择器（只在 Shadow DOM 有效，普通页面导致整条规则丢弃）
- `CSSMediaRule`/`CSSStyleRule` 等类不存在导致 `instanceof` 报错

### 三层 CSS 拦截架构

| 层 | 文件 | 处理内容 |
|---|---|---|
| **Android WebView** | `TavernWebView.kt` | `shouldInterceptRequest` 拦截 HTML（注入 polyfill）和 CSS（oklch→rgb、color-mix→纯色、backdrop-filter→none、@layer/@supports/:host/::backdrop 剥离） |
| **服务端 Node.js** | `server-wrapper.js` | HTTP 响应层拦截 CSS，按 `st_compat=1` cookie 触发转换 |
| **客户端 JS** | `tavern-mobile-inject.js` | DOM 钩子（textContent/innerHTML/appendChild/insertRule）拦截动态插入的 `<style>` 标签，精确 sRGB 颜色混合计算 |

### 兼容模式 CSS 注入优化
- **移除 `.drawer-content` 子元素强制 `max-width` 和 `word-wrap`**
- **勾选框 accent-color 改用 `var(--SmartThemeQuoteColor)` 替代硬编码金色**
- **去掉 `#sheld`/`#top-bar`/`#form_sheld` 硬边框和阴影**
- **`contain:layout` 和 `background:#0a0a12` 从全局 CSS 移除**

### 扩展加载修复
- **JS-Slash-Runner（酒馆助手）Vue.js 加载**：`shouldInterceptRequest` 在 HTML `<head>` 注入 polyfill 定义 `CSSMediaRule`/`CSSStyleRule`/`CSSImportRule`/`CSSContainerRule`，修复 `dynamic-styles.js` 的 `instanceof` 崩溃
- **fetch 重定向**：旧 WebView TLS 不兼容 CDN 时，将 `fetch` 和 `createElement('script')` 重定向 CDN URL 到本地
- **CSS Transform 多项修复**：`fixCompatCSS` 方法完整处理 oklch→rgb 数学转换、`rgb(from ...)` 简化、嵌套 `color-mix()` 循环解析

### 服务端 CSS 拦截
- **gzip 处理**：`shouldInterceptRequest` 强制 `Accept-Encoding: identity` 获取未压缩 CSS
- **HTML polyfill**：`http.createServer` 钩子为兼容客户端注入 polyfill 和 fetch 重定向

### 其他修复
- **兼容模式 cookie 设置**：`loadTavern()` 时设 `st_compat=1` cookie
- **dvw/dvh polyfill 不再跳过后续修复**：修复 `return` 误退出整个兼容模式的 bug
- **`instanceof` → `rule.type`**：旧 WebView 不认 `instanceof CSSMediaRule`
- **缓存清除**：兼容模式下 `clearCache(true)` 确保转换后 CSS 生效

### 架构决策
- **通用方案**：所有 CSS 转换逻辑不针对特定主题，任何主题/扩展的 `color-mix()`/`oklch()` 都会自动转换
- **高端机零影响**：三层拦截均按 `SettingsState.compatModeEnabled()` 或 `st_compat=1` cookie 条件执行，不开兼容模式完全不触发
- **GeckoView 调研**：评估了嵌入 Firefox 引擎（APK +40-70MB）作为长期方案的可行性

## BUG 修复

- **APK 更新下载无反应**：`AppUpdateChecker` 硬编码下载文件名导致与实际 Release 资产名不匹配（404 静默失败）。改为调用 GitHub API 动态匹配 arm64 资产的真实下载地址，API 不可用时自动降级到 atom 源
- **setContent 重复调用**：修复 `showConsole()` 中不必要的 `setContent` 调用，改为单 Composition 状态驱动 UI 切换
- **WebViewBridge.shareText 空实现**：调用分享功能时给出 Toast 提示「暂未实现」
- **MediaStore DATA 列废弃**：`listBackupsViaMediaStore()` 在 API 29+ 禁用废弃的 DATA 列查询，增加注释说明
- **跨文件系统 renameTo 数据丢失**：提取统一的 `FileUtils.moveDirSafely()` 工具，CoreUpdater 和 AssetExtractor 统一使用「先拷贝成功再删源」策略
- **KeepAliveMonitor 双重 alarm**：API 30 以下不再重复 reschedule，避免每 5-30 分钟触发两次心跳
- **端口轮询超时**：Node.js 启动超时 120s → 60s
- **StoragePermissionDialog 代码重复**：提取为独立 @Composable 函数，消除 30 行重复代码

## 代码质量优化

- **formatBytes 统一**：提取 `FormatUtils.fileSize()` 消除 4 处重复定义
- **颜色常量集中**：创建 `DesignTokens.kt` 统一 10 个色板常量
- **JSON 辅助工具**：`JsonUtil.readVersion()` 封装 package.json/manifest.json 版本读取
- **NodeStartParams 数据类**：封装 JNI 启动参数，新增便捷重载 `start(params)`
- **NodeState WeakReference**：broadcaster 改用 WeakReference 防止 Context 泄漏
- **ConsoleViewModel OpState**：8 个备份/还原 StateFlow 合并为 sealed class `OpState`
- **JS 注入文件化**：~260 行内联 JavaScript 提取到 `assets/tavern-mobile-inject.js`，支持独立编辑和语法高亮
- **流式解压**：AssetExtractor 跳过临时文件拷贝，直接从 assets 解压，节省 144MB 磁盘空间
- **RoundedProgressBar 变量重命名**：`alpha` → `widthFraction` 消除命名误导
- **无障碍优化**：控制台 FAB 图标添加 `contentDescription`
- **下载暂停超时**：`DownloadTask` 暂停信号量超时 30s → 3s
- **兼容模式提示**：开启兼容模式时 Toast 提示「刷新酒馆页面后生效」

## 性能优化

- AssetExtractor 流式解压：消除 144MB 临时文件拷贝，减少一倍磁盘写入

---

# ST-Ctrl v1.0.1 更新日志

## 新增功能

### 文件管理
- 文件浏览器：浏览酒馆 data/ 目录，面包屑导航，点击进入子目录
- 多选模式：顶栏「选择」按钮或长按进入，批量压缩/导出/删除
- 压缩/解压：长按菜单 zip 压缩文件/文件夹，解压 zip（含路径穿越防护）
- 文本编辑器：全屏编辑，语法高亮（10+语言），行号显示，撤销/重做，双指缩放
- 导入导出：导入文件自动定位高亮；导出单文件直接、多文件自动打包
- 压缩进度：压缩/打包时弹窗提示，避免误以为无响应

### 性能模式
- 4级CPU控制：FULL/LIGHT/BALANCED/SAVE（nice值 + UV线程池 + 堆内存限制）
- SAVE定时器节流
- 模式卡片可展开，点「应用」才切换

### 更新功能
优化了"更新功能"

### 备份与恢复
- 兼容模式：含 data/ 目录的ZIP即可恢复，不强制 backup.json
- 恢复失败保护：先备份再操作，失败自动还原
- Termux数据迁移：一键生成迁移脚本，只需要去termux里执行命令即可

### 启动体验
- 快速启动：在APP关闭之后，相关服务静默运行在手机后台，打开APP时会被快速唤醒
- 后台酒馆：从酒馆退出到控制台时酒馆不关闭，等于只是切换页面
- WebView预热：引擎后台提前初始化

### UI优化
进行了一些UI优化

## 性能优化

- WebView CSS contain：缩小重排范围
- MutationObserver节流：250→500ms
- Node.js堆内存限制：按模式限制V8堆
- APK体积缩小：debug 300MB → release arm64 193MB
- ProGuard混淆 + 资源压缩
- CMake LTO + -Os

## BUG 修复

修复了已知BUG
