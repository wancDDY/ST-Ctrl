# ST-Ctrl v1.0.3 更新日志

> 最近更新：2026-06-09

## Termux 数据迁移

- **备份列表可见性修复**：Android 11+ Scoped Storage 导致 Termux 生成的 ZIP 不显示
  - `BackupManager` 改为 MediaStore + File 双通道查询，合并去重
  - 添加 `MANAGE_EXTERNAL_STORAGE` 权限（启动时申请，用于跨应用读备份）
  - 迁移脚本末尾自动发送媒体扫描广播，确保文件立即被索引
- **脚本换行符修复**：写入文件时显式去除 `\r`，解决 Termux bash 报错
- **列表自动刷新**：从 Termux 切回 APP 时（ON_RESUME）自动刷新备份列表

## UI 优化

- **进度条圆角化**：`RoundedProgressBar`（pill 形状），覆盖启动/备份/还原/更新/扩展 5 处
- Termux 迁移命令去除 `--yes`，恢复文件名自定义和确认提示

## 权限精简

- 移除 `READ_MEDIA_IMAGES` / `READ_MEDIA_AUDIO` / `READ_MEDIA_VIDEO`
- 移除 `READ_EXTERNAL_STORAGE` (≤API 32) / `WRITE_EXTERNAL_STORAGE` (≤API 29)
- 文件选择器走 SAF 无需额外权限，`MANAGE_EXTERNAL_STORAGE` 覆盖全部场景
- 首次启动仅需：通知权限 + 文件访问权限

## 文件选择器

- 多级回退：特定 MIME → `*/*` → `ACTION_OPEN_DOCUMENT` — 兼容各类模拟器/定制 ROM

---

# ST-Ctrl v1.0.2 更新日志

> 最近更新：2026-06-08

## BUG 修复（27 项代码审查 + 2 项用户反馈）

### 进入酒馆闪屏（用户反馈）
- **根因**：Overlay JS 的 `findVisiblePanel()` 将 ST 主应用容器 `#sheld` 误判为面板，捕获后使整个界面滑入 overlay
- **修复**：移除 `#sheld` 选择器 + 添加 80% 视口保护（主容器特征过滤）

### 控制台顶栏遮挡（用户反馈）
- **修复**：重构为 Box 覆层布局，顶栏覆盖滚动区域，消除卡片滚到顶栏边缘时的硬裁剪

### 核心状态机（MainActivity）
- WebView 销毁后 `lastLoadedPort` 不复位 → 关闭后台酒馆后重进黑屏
- `handlingBack` JS 回调丢失 → 返回键永久失效
- `composeScreen` 状态不追踪 → 旋转屏幕后 UI 错乱
- `starting` AtomicBoolean 无 finally 块 → OOM 等 Error 导致永久卡启动
- `CancellationException` 被通用 catch 吞掉 → 取消信号丢失
- `consoleShown` 在 showWebView 中未更新 → 状态不一致

### 原生层（node-bridge.cpp）
- Reader 线程永久挂起：`node::Start` 返回后 fds 未恢复 → 线程泄漏
- `g_nodeThread` 数据竞争：`nativeStartNode`/`nativeStopNode` 并发访问 → 潜在崩溃
- `g_pipeClosed` 假阳性：正常退出顺序导致误报崩溃

### Compose 视觉（ConsoleScreen）
- 触摸与视觉错位：`Modifier.offset` → `Modifier.graphicsLayer`
- 内容不满屏双边界拉伸修复 + `NestedScrollSource.Drag` 过滤
- 回弹动画 `MediumBouncy` → `LowBouncy`

### 线程安全
- `SimpleDateFormat` → `DateTimeFormatter`（多协程并发安全）
- WebView MIME 类型保留（不再无条件覆盖 `*/*`）
- `isPaused` 添加 `@Volatile`

### 安全加固
- **zip-slip 路径穿越防护**：`canonicalPath` 校验
- 备份/解压时跳过符号链接（防无限递归）
- `renameTo` 跨文件系统失败 → 磁盘空间检查 + 回退策略

### 服务层（KeepAliveMonitor）
- `setInexactRepeating` (API 31+ 废弃) → `setAndAllowWhileIdle`
- Socket 连接超时无效 → `connect(InetSocketAddress, timeout)`

### 文件管理
- 大文件防崩溃：512KB 编辑限制 + 256KB 语法高亮限制 + 3000 行上限
- `addToZip` symlink 跳过

---

# ST-Ctrl v1.0.1 更新日志

> 最近更新：2026-06-07

## 文件管理

- **文件浏览器**：浏览酒馆 data/ 目录，面包屑导航，点击进入子目录
- **多选模式**：顶栏「选择」按钮或长按进入，批量压缩/导出/删除
- **压缩/解压**：长按菜单支持 zip 压缩文件/文件夹，解压 zip（含路径穿越防护）
- **文本编辑器**：全屏编辑，语法高亮（10+ 语言）、行号、撤销/重做、双指缩放
- **导入导出**：导入文件自动定位高亮；导出单文件直接、多文件自动打包
- **压缩进度**：压缩/打包时弹窗提示，避免误以为无响应

## 性能优化

- **WebView CSS contain**：聊天消息添加 `contain: layout style`，缩小重排范围
- **WebView 暗色背景**：消除页面加载白闪，OLED 省电
- **MutationObserver 节流**：间隔 250→500ms，跳过纯文本节点变更
- **Node.js 堆内存限制**：按模式限制 V8 堆（FULL 256M / BALANCED 128M / SAVE 96M），减少 GC 卡顿和发热
- **编辑器防遮挡**：共享滚动架构，键盘弹起光标自动滚入可见区

## 构建优化

- **APK 体积缩小**：debug 300MB → release arm64 193MB，移除 x86_64 ABI 和未使用 node 可执行文件
- **按 ABI 拆分**：arm64 和 arm32 各自独立打包，按需下载
- **ProGuard 混淆 + 资源压缩**
- **CMake LTO + -Os**：原生桥接库体积减小

## 启动体验

- **WebView 预热**：引擎后台提前初始化，酒馆加载更快
- **node-bridge 无锁化**：`std::atomic` 替代 `std::mutex`，消除 native 崩溃

## 性能模式

- **4 级 CPU 控制**：通过 nice 值和 UV 线程池区分（FULL/LIGHT/BALANCED/SAVE），重启后生效
- **SAVE 定时器节流**：仅节流长周期 setInterval，不影响 UI 交互
- **模式卡片可展开**：点击显示详情，点「应用」才切换

## 更新系统

- **ST 核心版本选择**：下拉显示 GitHub 所有 `st-` 版本，进度条 + 取消按钮
- **ST-Ctrl 版本选择**：下拉显示所有 `v-` 版本，旧版可下载降级
- **版本数值比较**：不再把旧版标记为「新版本」
- **更新源提示**：标注文件来自 GitHub，需稳定网络

## 备份与恢复

- **兼容模式**：不再强制 backup.json，含 data/ 目录的 ZIP 即可恢复
- **恢复失败保护**：先备份再操作，失败自动还原（含 copyRecursively 兜底）
- **Termux 数据迁移**：一键生成脚本，含自定义文件名

## 更新安全

- **ST 核心更新原子化**：先解压到临时目录验证再切换，验证失败自动还原数据
- **更新取消**：下载支持取消（`UpdateCancelledException` 确保取消信号不丢）
- **自动清理只删自动备份**：不再误删手动备份

## 角色卡管理

- **世界书匹配**：3 层检测（内嵌数据 · extensions.world · 文件名）+ 模板残留过滤 + 角色目录扫描
- **删除安全**：短角色名用精确匹配；世界书内容含角色名也删；角色目录正则文件一并清空
- **扩展安装地址自动检测**：从 package.json / .git/config / README 推断来源

## UI 优化

- **点击波纹圆角裁剪**：全局卡片点击反馈跟随圆角
- **关于卡片可折叠**：点标题展开/收起详情
- **SillyTavern 地址可点击 + 复制**

## BUG 修复

- 修复恢复后重启闪退（node-bridge `std::atomic`）
- 修复 WebView 切换时内存泄漏（`destroy()`）
- 修复缓存策略导致非 FULL 模式循环加载（改回 `LOAD_DEFAULT`）
- 修复 HTTP 连接泄漏（`try-finally` 兜底）
- 修复 CoreUpdater 验证失败后数据丢失
- 修复 `setpriority(PRIO_PROCESS, 0)` 误降 UI 线程（改 `gettid()`）
- 修复 ST-Ctrl 版本号不刷新（`remember` → `mutableStateOf`）
- 修复酒馆内主题/文件无法选取（`createIntent()` 返回 null 时无兜底）
- 修复 `DownloadTask` HEAD 失败直接跳过下载
- 修复导入文件时名称/扩展名丢失（改用 ContentResolver DISPLAY_NAME）
- 修复编辑器点击光标跳动（手动滚动替代 bringIntoView）
- 修复行号尾部显示不全
- 修复备份恢复时 renameTo 跨文件系统失败导致数据混合（copyRecursively 兜底）
- 修复 KeepAlive cancel 无法取消闹钟（requestCode 对齐）
- 修复配置变更后启动动画重放（onSaveInstanceState）
- **C++ const_cast UB**：argv 改用 strdup 可写副本
- **node crash 无感知**：管道断连时设置崩溃标志
- **BackupMetadata JSON 必填字段**：全部改用 opt* 兼容旧格式
- **并发启动竞态**：AtomicBoolean 保护
- **WebView pauseRendering**：加 try-catch 保护
- 移除废弃的 `setRenderPriority`
- 删除未使用的 `MANAGE_EXTERNAL_STORAGE` 权限
- 修复 `sillytavern_logo.png` 伪 PNG
- 删除 3 个未引用死代码文件
