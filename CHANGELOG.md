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

## BUG 修复

- 导入文件时名称/扩展名丢失（改用 ContentResolver DISPLAY_NAME）
- 双指缩放误判滑动（自定义手势，仅 2 指以上激活）
- 编辑器点击无法定位光标（手动滚动计算替代 bringIntoView）
- 行号尾部显示不全（600dp 底部留白 + 200dp Row 内边距）
- `content-visibility: auto` 导致聊天不滚到底部（已移除）
- `--max-old-space-size` 通过 argv 导致闪退（改用 NODE_OPTIONS）
- **C++ const_cast UB**：argv 改用 strdup 可写副本，Node.js 写入 argv 不再造成崩溃
- **Node crash 无感知**：管道断连时设置崩溃标志，isRunning 准确反映状态
- **备份恢复数据混合**：renameTo 失败时 copyRecursively 兜底，不丢数据
- **JSON 必填字段崩溃**：BackupMetadata 全部改用 opt*，旧格式兼容
- **备份日志 O(n²)**：改为每 50 条批量推送，大备份不卡
- **KeepAlive cancel 无效**：requestCode 对齐 3001，闹钟能正确取消
- **并发启动竞态**：AtomicBoolean 保护，防重复启动
- **配置变更丢状态**：composeScreen 存入 onSaveInstanceState，旋转屏幕不重置
- **WebView pauseRendering 崩溃**：加 try-catch 保护
- **MutationObserver 性能**：getComputedStyle → offsetHeight，节流 500ms→2000ms

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
- 修复酒馆内主题/文件无法选取（`createIntent()` 返回 null 时无兜底，部分设备文件选择器白屏或不弹出）
- 修复 `DownloadTask` HEAD 失败直接跳过下载
- 移除废弃的 `setRenderPriority`
- 删除未使用的 `MANAGE_EXTERNAL_STORAGE` 权限
- 修复 `sillytavern_logo.png` 伪 PNG
- 删除 3 个未引用死代码文件
