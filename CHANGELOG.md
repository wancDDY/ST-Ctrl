# ST-Ctrl v1.0.1 更新日志

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
