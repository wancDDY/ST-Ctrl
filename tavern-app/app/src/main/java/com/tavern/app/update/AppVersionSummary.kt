package com.tavern.app.update

data class VersionSection(
    val title: String,
    val summary: String,
    val items: List<String>,
)

data class AppReleaseSummary(
    val intro: String,
    val sections: List<VersionSection>,
    val migrationSteps: List<String> = emptyList(),
    val migrationTitle: String? = null,
    // The version this release is read as an upgrade from. Null when the release
    // stands alone; the update page shows "v<from> → v<this>" from it instead of
    // matching on the version string.
    val fromVersion: String? = null,
)

object AppVersionSummary {
    fun noticeText(versionName: String): String {
        return "v$versionName 更新日志"
    }

    const val intro = "以下内容汇总 ST Ctrl v1.0.2 到 v2.0.0 的主要变化。"

    val sections = listOf(
        VersionSection(
            title = "运行环境",
            summary = "内置运行时整体更新，两个架构行为一致。",
            items = listOf(
                "安装包内置自编译的 Node.js v24.20.0，64 位与 32 位版本功能一致。",
                "提高缺少系统组件的设备上的启动成功率；服务异常退出时会留下诊断信息。",
            ),
        ),
        VersionSection(
            title = "角色记忆",
            summary = "可选功能，默认关闭，在本机整理，不阻塞聊天。",
            items = listOf(
                "开关在记忆中心页面顶部；整理在后台进行，已经聊过的内容可以逐步建立索引。",
                "记忆存放在应用私有目录的 SQLite 数据库中，基础检索不依赖外部服务；创建备份会连记忆库一起打包，还原后记忆还在。",
                "检索混合全文索引与本地向量，角色别名、人物关系、地点、物品与场景一并参与。",
                "长聊天会生成阶段摘要与聊天总览，减少上下文只包含零散片段的情况；过短的阶段会与相邻阶段合并显示。",
                "记忆中心分为概览、故事、人物、物品地点、记忆本与设置，可编辑记忆、测试召回、删除某个聊天的记忆。",
                                "列表里聊天名旁的圆圈就是整理入口，点击开始整理并显示进度。",
                "整理默认使用核心已配置的模型，不需要另外填写服务地址与密钥；也可以为向量检索、查询重写与结果重排单独指定一个兼容 OpenAI 的服务，服务不可用或断网时退回本地检索。",
                "「收录旧聊天」把已有内容收进记忆库，只在本机入库，不调用模型，也不修改聊天原文。",
                "「附加提示词」可以追加一段自定义的整理指令；「脱敏后送出」在发送前替换露骨词句，聊天记录本身不受影响。",
                "「角色关系」列出整理出的关系；卡片把关系写在状态变量里（例如好感度）时，这些内容也会一并显示。",
                "人物册只收现实人物，表格名、榜单名与分组名不会混进人物列表。",
                "需要重启才生效的开关改动后，控制台右下角出现「重启核心服务」按钮，重启时机由用户决定。",
            ),
        ),
        VersionSection(
            title = "内置核心与兼容模式",
            summary = "内置核心升级到 1.19.0，旧系统上的主题与插件渲染更正常。",
            items = listOf(
                "内置核心由 1.18.0 升级到 1.19.0，更新页可以查看上游更新日志摘要。",
                "兼容渲染的现代 CSS 转换与降级同步更新，新版主题与第三方扩展在旧系统上不再错位或变白。",
                "系统 WebView 版本过低时提示一次，可以当场开启兼容渲染，也可以在浏览器中打开。",
                "修复旧系统上引号、部分边框与卡片底色被涂成实色的问题。",
                "修复从控制台返回核心后卡片重新加载的问题。",
                "升级核心不再搬移用户数据，升级被打断也不影响已有数据。",
                "运行文件只在升级后需要时重建。",
            ),
        ),
        VersionSection(
            title = "控制台与界面",
            summary = "重做了控制台，操作与进度集中到首页任务条。",
            items = listOf(
                "运行日志每次进入应用都从空开始，上一次运行的记录改存为文件。",
                "整理与插件安装更新都在后台进行，离开页面不中断，进度显示在首页顶部的任务条。",
                "插件安装与更新在页面底部实时显示下载、解压与写入进度。",
                "首页新增「后台运行」开关与「浏览器与局域网」入口。",
                "通知栏新增「控制台」快捷入口，运行日志可以圈选复制。",
                "应用名改为「ST Ctrl」；应用内统一称内置核心为「核心」。",
                "检测到新版本时首页任务条公告提示 5 秒，点开直接展开该版本的更新日志，没有网络也能看。",
                "修复核心更新时的几个问题：取消没有反应、服务被意外启动。",
                            ),
        ),
        VersionSection(
            title = "核心优化",
            summary = "降低长聊天首次打开与回翻时的等待。",
            items = listOf(
                "长聊天先渲染最近的消息，其余随滚动逐步补全，接近顶部时继续加载更早消息。",
                "页面结构仍保留全部消息，第三方扩展照常能读取完整聊天。",
                "离开核心时暂停页面渲染与定时任务，返回后自动恢复。",
                "世界书页的「生效世界书」列表加高，一次能看到更多已启用的世界书，选中项用主题色淡底。",
            ),
        ),
        VersionSection(
            title = "权限与数据",
            summary = "文件权限收窄，备份位置改由系统文件夹授权选择。",
            items = listOf(
                "移除“所有文件访问”与“写入外部存储”权限；备份与还原的保存位置改由系统文件夹授权选择。",
                "从 v1.0.2 升级后需要重新选择一次备份文件夹；没有授权时自动备份会跳过。",
                "应用不再参与系统云备份与设备迁移，聊天、角色卡与配置只保存在本机。",
                "新增通知权限与“安装未知应用”权限，通知栏快捷入口与安装包更新需要它们。",
                "备份也带上应用自身的设置，还原后不用重新调整；可以选择是否连同 API Key 一起备份。",
                "兼容渲染开关不随备份还原，它取决于这台手机的 WebView 能否正确渲染核心界面。",
                "还原保留文件原本的修改时间；第三方扩展里的空目录与符号链接内容不再丢失。",
            ),
        ),
        VersionSection(
            title = "安装与升级",
            summary = "安装包更换标识与签名，可与 v1.0.2 并存。",
            items = listOf(
                "应用标识改为 com.stctrl.mobile，无法覆盖安装 v1.0.2，但可以与它并存；迁移步骤见本页顶部。",
                "安装包按手机架构分成 64 位与 32 位两个，安装对应架构的那个。",
            ),
        ),
        VersionSection(
            title = "许可",
            summary = "许可文件随安装包一起提供，应用内可以直接阅读全文。",
            items = listOf(
                "安装包内新增 LICENSE、NOTICE 与 LICENSE-MIT 三份文件。",
                "打包产物整体按 GNU AGPL-3.0 发布，Android 壳层中未派生自上游的文件按 MIT，范围见 NOTICE。",
                "应用内可以直接阅读许可全文；“关于”页与局域网访问入口页都给出对应源码地址。",
            ),
        ),
    )


    private val sections100 = listOf(
        VersionSection(
            title = "首个正式版本",
            summary = "建立 ST Ctrl 的 Android 本地核心运行框架。",
            items = listOf(
                "使用 Kotlin 与 Jetpack Compose 构建原生控制台。",
                "在应用内嵌 Node.js，通过 JNI 加载并启动 SillyTavern 服务。",
                "使用 WebView 承载核心界面，并支持本地局域网访问准备。",
                "性能模式支持智能自动，也可手动选择渲染档位。",
            ),
        ),
        VersionSection(
            title = "数据与核心",
            summary = "加入核心版本管理、应用更新和基础备份能力。",
            items = listOf(
                "支持内置 ST 核心解压、版本识别和应用内更新。",
                "支持 ZIP 备份与还原用户数据。",
                "提供 Termux 数据迁移工具。",
                "加入前台服务保活，降低服务被系统回收的概率。",
            ),
        ),
    )

    private val sections101 = listOf(
        VersionSection(
            title = "文件管理",
            summary = "增加完整的核心 data/ 文件管理能力。",
            items = listOf(
                "支持目录浏览、面包屑导航、多选、压缩、解压、导入和导出。",
                "加入文本编辑器、行号、语法高亮、撤销重做和双指缩放。",
                "解压前校验包内路径是否合法，避免异常内容写入错误位置。",
            ),
        ),
        VersionSection(
            title = "性能与启动",
            summary = "加入更细的性能档位和后台快速启动。",
            items = listOf(
                "性能模式支持智能自动，也可手动选择渲染档位。",
                "离开控制台后核心可继续在后台运行，再次进入时快速唤醒。",
                "提前预热 WebView，降低进入核心等待。",
                "加入更细的内存与线程参数调节，降低卡顿。",
            ),
        ),
        VersionSection(
            title = "备份与更新",
            summary = "调整核心更新和备份恢复流程，增加失败回滚。",
            items = listOf(
                "修复更新核心后聊天记录和角色卡可能丢失的问题；更新过程会保护用户数据。",
                "备份还原兼容包含 data/ 目录的 ZIP，并加入失败回滚。",
                "支持在 Termux 中生成迁移脚本。",
            ),
        ),
    )

    private val sections102 = listOf(
        VersionSection(
            title = "兼容模式",
            summary = "为旧 Android WebView 建立三层现代 CSS 与扩展兼容处理。",
            items = listOf(
                "支持 color-mix、oklch、backdrop-filter、@layer、@supports、:host 等转换。",
                "页面样式在服务端与页面内同时处理，覆盖动态生成的样式。",
                "修复部分依赖 CSS 类型判断的扩展在旧 WebView 上加载失败的问题。",
                "高端设备不开启兼容模式时不会触发转换。",
            ),
        ),
        VersionSection(
            title = "局域网访问",
            summary = "增加局域网访问：同一时间只允许一个入口，电脑访问时手机进入核心会断开电脑连接。",
            items = listOf(
                "电脑通过手机 IP 和访问码进入核心。",
                "局域网访问使用访问码校验，授权只在本次运行内有效。",
                "电脑正在访问时手机进入核心会提示并断开电脑。",
                "局域网访问默认关闭，支持超时自动关闭。",
            ),
        ),
        VersionSection(
            title = "文本编辑器",
            summary = "重构编辑体验并修复键盘、光标和缩放问题。",
            items = listOf(
                "编辑器按文件大小自动选择渲染方式。",
                "修复撤销光标跳转、行合并、输入法遮挡和缩放失效。",
                "状态栏实时显示行列位置。",
            ),
        ),
        VersionSection(
            title = "稳定性与性能",
            summary = "修复启动、内存、文件操作和异常诊断相关的问题。",
            items = listOf(
                "增加全局崩溃文件和 Node.js 致命错误报告。",
                "服务就绪后再打开核心页面，减少提前报错。",
                "修复跨文件系统移动、双重保活和端口轮询问题。",
                "控制台减少重新加载，后台占用下降。",
                "性能档位按设备状态自动调节，并加入异常自动恢复。",
            ),
        ),
    )

    fun summaryFor(versionName: String): AppReleaseSummary? {
        val normalized = versionName.trim().removePrefix("v")
        return when (normalized) {
            "1.0.0" -> AppReleaseSummary(
                intro = "v1.0.0 是 ST Ctrl 的首个正式版本。",
                sections = sections100,
            )
            "1.0.1" -> AppReleaseSummary(
                intro = "v1.0.1 从 v1.0.0 升级，重点加入文件管理、性能模式和核心更新保护。",
                sections = sections101,
                fromVersion = "1.0.0",
            )
            "1.0.2" -> AppReleaseSummary(
                intro = "v1.0.2 从 v1.0.1 升级，重点加入旧 WebView 兼容模式、局域网访问和编辑器重构。",
                sections = sections102,
                fromVersion = "1.0.1",
            )
            "2.0.0" -> AppReleaseSummary(
                intro = intro,
                sections = sections,
                migrationSteps = migrationSteps,
                migrationTitle = "从 v1.0.2 升级",
                fromVersion = "1.0.2",
            )
            else -> null
        }
    }

    val migrationSteps = listOf(
        "v1.0.2 与新版本包名不同，二者可以并存，但不能直接覆盖安装。",
        "先在旧版里创建备份。",
        "安装 v2.0.0 后进入“还原备份”，选择刚才的备份文件恢复数据。",
        "恢复后检查聊天、角色卡、扩展和配置；需要时再开启角色记忆并收录旧聊天。",
    )
}
