# ST-Ctrl 更新日志

> v1.0.0 → v1.0.3 · 最近更新：2026-06-09

---

## 文件管理（新增）

- **文件浏览器**：浏览酒馆 data/ 目录，面包屑导航，点击进入子目录
- **多选模式**：顶栏「选择」按钮或长按进入，批量压缩/导出/删除
- **压缩/解压**：长按菜单支持 zip 压缩文件/文件夹，解压 zip（含路径穿越防护）
- **文本编辑器**：全屏编辑，语法高亮（10+ 语言）、行号、撤销/重做、双指缩放
- **大文件保护**：512KB 编辑限制 + 256KB 语法高亮限制 + 3000 行上限
- **导入导出**：导入文件自动定位高亮；导出单文件直接、多文件自动打包

## Termux 数据迁移（新增）

- **迁移脚本**：一键生成备份脚本，连同命令复制到剪贴板
- **备份可见性**：Android 11+ Scoped Storage 下通过 MediaStore + File 双通道查询
- **自动刷新**：从 Termux 切回 APP 时备份列表自动刷新
- **非交互模式**：脚本支持 `--yes` + `--output` 参数，可无人值守运行

## 性能优化

- **WebView CSS contain**：聊天消息添加 `contain: layout style`，缩小重排范围
- **WebView 暗色背景**：消除页面加载白闪，OLED 省电
- **Node.js 堆内存限制**：按模式限制 V8 堆（FULL 256M / BALANCED 128M / SAVE 96M），减少 GC 卡顿和发热
- **编辑器防遮挡**：键盘弹起光标自动滚入可见区
- **4 级性能模式**：通过 nice 值和 UV 线程池区分（FULL/LIGHT/BALANCED/SAVE），重启后生效
- **WebView 预热**：引擎后台提前初始化，酒馆加载更快
- **SAVE 定时器节流**：仅节流长周期 setInterval，不影响 UI 交互

## 构建优化

- **APK 体积缩小**：debug 300MB → release arm64 193MB，移除 x86_64 ABI 和未使用 node 可执行文件
- **按 ABI 拆分**：arm64 和 arm32 各自独立打包
- **ProGuard 混淆 + 资源压缩 + CMake LTO -Os**

## 更新系统

- **ST 核心版本选择**：下拉显示 GitHub 所有 `st-` 版本，进度条 + 取消
- **ST-Ctrl 版本选择**：下拉显示所有 `v-` 版本，支持下载降级
- **ST 核心更新原子化**：先解压到临时目录验证再切换，失败自动还原

## 备份与恢复

- **兼容模式**：不再强制 backup.json，含 data/ 目录的 ZIP 即可恢复
- **恢复失败保护**：先备份再操作，失败自动还原
- **自动备份**：定时备份 + 保留最近 N 份，自动清理旧档
- **zip-slip 路径穿越防护**：`canonicalPath` 校验
- **符号链接安全**：备份/解压/压缩均跳过 symlink

## 角色卡管理

- **世界书匹配**：3 层检测（内嵌数据 · extensions.world · 文件名）+ 模板残留过滤
- **删除安全**：短角色名精确匹配；关联数据一并清理
- **扩展安装地址自动检测**：从 package.json / .git/config / README 推断来源

## UI 优化

- **控制台滚动回弹**：iOS 风格拉伸效果，替代 Android 灰色发光边缘
- **顶栏覆层布局**：内容滚动时平滑消失在顶栏后，不再硬裁剪
- **进度条圆角化**（pill 形状）：覆盖启动/备份/还原/更新/扩展 5 处
- **点击波纹圆角裁剪**：全局卡片点击反馈跟随圆角
- **关于卡片可折叠**：点标题展开/收起详情

## 权限精简

- 移除 `READ_MEDIA_*` (3 个) — 文件选择器走 SAF 不需要
- 移除 `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` — 已过期
- 首启仅需：通知权限 + 文件访问权限

## 文件选择器兼容

- 动作回退：`ACTION_GET_CONTENT` → `ACTION_OPEN_DOCUMENT`
- MIME 回退：特定类型 → `*/*`
- 保留有效 MIME 类型，不再无条件覆盖 — 模拟器/定制 ROM 均可正常导入

## BUG 修复（核心）

- **进入酒馆闪屏**：Overlay JS 误捕获 `#sheld` 主容器 → 移除选择器 + 80% 视口保护
- **关闭后台酒馆后黑屏**：`lastLoadedPort` 未复位 + 返回键失效
- **旋转屏幕状态丢失**：`composeScreen` 追踪 + `onSaveInstanceState`
- **启动永久卡死**：`starting` AtomicBoolean 缺 finally 兜底
- **取消信号被吞**：`CancellationException` 先于通用 catch 处理
- **Reader 线程挂起**：`node::Start` 返回后 fds 未恢复 → 保存/还原 stdout/stderr
- **g_nodeThread 数据竞争**：添加 `std::mutex` 保护
- **g_pipeClosed 假阳性**：调整退出顺序，先设 false 再关管道
- **触摸与视觉错位**：`Modifier.offset` → `Modifier.graphicsLayer`
- **SimpleDateFormat 线程安全** → `DateTimeFormatter`
- **Socket 超时无效** → `connect(InetSocketAddress, timeout)`
- **AlarmManager API 废弃** → `setAndAllowWhileIdle`
- **renameTo 跨文件系统失败** → 磁盘空间检查 + copyRecursively 兜底
- **isPaused 多线程可见性** → `@Volatile`
- 23 项其他 BUG 修复（详见 git log）
