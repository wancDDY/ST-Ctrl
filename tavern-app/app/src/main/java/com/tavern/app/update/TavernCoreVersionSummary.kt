package com.tavern.app.update

object TavernCoreVersionSummary {
    const val version = "1.19.0"
    const val intro = "以下内容摘译自 SillyTavern 上游 1.19.0 更新日志；ST Ctrl 为非官方第三方项目。"

    val sections = listOf(
        VersionSection(
            title = "后端与模型",
            summary = "增加新的模型、供应商参数和请求能力。",
            items = listOf(
                "Claude：增加 Fable 5、Fable 5.1、Opus 4.8、Opus 5 和 Sonnet 5 支持。",
                "OpenAI：增加 GPT-5.6 和 GPT-6 Astra 支持。",
                "其他供应商：增加 Gemini 3.5/3.6/3.7 Flash、GLM-5.2 和 DeepSeek V4 Flash Vision 支持。",
                "Fireworks AI：更新模型列表，增加推理强度、推理显示和提示缓存会话关联。",
                "OpenRouter：增加可选的 logprobs 支持并更新供应商列表。",
                "Pollinations：增加带密钥和无密钥端点选择，并更新 TTS 模型别名。",
                "DeepSeek：增加低推理强度；通用文本补全的模型列表改为下拉选择。",
                "更新 Gemini TTS、Z.AI、MiniMax TTS 和 BFL API 支持。",
            ),
        ),
        VersionSection(
            title = "聊天与数据",
            summary = "改善聊天文件安全、分支保存和 Swipe 操作。",
            items = listOf(
                "世界书“应用当前排序”支持升降序、起始位置和步长，并提供实时校验。",
                "重命名世界书时同步更新聊天、角色和 Persona 中的引用，不再留下失效引用。",
                "新增 Swipe 不再重新加载整条聊天，连续滑动更顺。",
                "聊天文件头部无效时不再静默覆盖；末行损坏时仍可进入并显示降级预览。",
                "删除消息时同时清理关联的工具调用记录，避免残留。",
            ),
        ),
        VersionSection(
            title = "宏与 STscript",
            summary = "扩展变量、角色表达式和脚本命令能力。",
            items = listOf(
                "变量宏可以读取数组元素和对象属性。",
                "新增 {{defaultExpression}}、{{lastExpression}} 和 {{availableExpressions}} 角色表达式宏。",
                "/expression-list 可以筛选自定义表达式并支持更多返回格式。",
                "修复了若干已知问题。",
            ),
        ),
        VersionSection(
            title = "扩展",
            summary = "增加新的消息处理接口并改善扩展兼容。",
            items = listOf(
                "新增 MessageFormatter，扩展可以在正则处理前、正则处理后或 Markdown 转换后修改消息。",
                "角色表达式改善自定义表达式和默认值处理。",
                "ComfyUI 历史查询只使用当前提示，并忽略非图像输出。",
                "扩展发现会忽略没有 manifest 的目录，残缺目录不再被误认为扩展。",
                "修复了若干已知问题。",
            ),
        ),
        VersionSection(
            title = "性能与稳定性",
            summary = "降低排序、列表和移动端操作开销。",
            items = listOf(
                "优化世界书排序、插件列表、按日期排列图片和手机端自动补全。",
                "加固服务端请求限制，降低被滥用的风险。",
                "修复了若干已知问题。",
            ),
        ),
        VersionSection(
            title = "问题修复",
            summary = "修复连接、流式响应、扩展和聊天列表中的多类异常。",
            items = listOf(
                "供应商通过 HTTP 200 返回错误时，现在显示实际错误内容，不再只报成功。",
                "快速回复覆盖取消、群聊头像缩放和标签筛选的显示异常已修复。",
                "修复了若干已知问题。",
            ),
        ),
    )

    private val sections118 = listOf(
        VersionSection(
            title = "后端与模型",
            summary = "增加新的聊天补全来源、模型和供应商参数。",
            items = listOf(
                "增加 Cloudflare Workers AI 和 MiniMax 聊天补全来源。",
                "增强 DeepSeek V4、GPT-5.4/5.5、Gemma 4、GLM-5V-Turbo 和 Claude Opus 4.7 支持。",
                "KoboldCpp 的 Continue 操作保留语法状态，并转发推理强度设置。",
                "工具调用增加可配置的递归上限，自定义来源支持交错推理。",
                "Text Generation WebUI 增加 Adaptive-P 控制。",
                "NanoGPT 增加供应商选择和模型排序，OpenRouter 与 NanoGPT 支持查看余额。",
            ),
        ),
        VersionSection(
            title = "服务器与部署",
            summary = "调整初始化方式、部署条件和会话安全。",
            items = listOf(
                "初始化方式调整，改由应用自动完成。",
                "安装依赖时不再执行依赖包自带的脚本，减少安装环节的风险。",
                "错误页面与自定义样式的存放位置调整，支持只读部署。",
                "可配置连接复用，默认与旧版本一致。",
                "加强认证与会话安全：密码修改后需要重新登录，请求来源校验更严。",
            ),
        ),
        VersionSection(
            title = "界面与操作",
            summary = "改善手机端操作、流式请求反馈和编辑体验。",
            items = listOf(
                "手机端 Swipe 选择器改为长按打开，并增加展开和复制按钮。",
                "Click to Edit 模式支持推理块。",
                "欢迎页最近聊天数量可以配置。",
                "流式请求失败时在控制台显示错误信息。",
            ),
        ),
        VersionSection(
            title = "STscript 与扩展",
            summary = "增加 Persona、正则、连接配置和扩展管理能力。",
            items = listOf(
                "增加 /persona-create、/persona-update、/persona-delete、/persona-duplicate 和 /persona-get。",
                "增加 /pm-render、/regex-state、/expression-fallback 和 /profile-genstream。",
                "扩展资源按官方和社区分类，第三方安装增加确认提示。",
                "扩展可以使用连接配置中的 secret-id。",
                "扩展列表显示从 Git 远程地址解析出的作者。",
                "Vector Storage、Image Generation、Captioning 和 TTS 增加新来源或改进重试、取消和宏支持。",
            ),
        ),
        VersionSection(
            title = "问题修复",
            summary = "修复移动端编辑、聊天列表和向量删除等问题。",
            items = listOf(
                "修复手机 Firefox 的文本编辑流程。",
                "修复聊天重命名后欢迎页固定聊天不更新，以及角色列表筛选器卡住。",
                "修复 Show More Messages 误触编辑模式，以及移动端多选元素高度异常。",
                "修复删除消息后 KoboldCpp 向量未清理。",
                "修复了若干已知问题。",
            ),
        ),
    )

    fun summaryFor(version: String): Pair<String, List<VersionSection>>? {
        return when (version) {
            "1.18.0" -> "以下内容摘译自 SillyTavern 上游 1.18.0 更新日志；ST Ctrl 为非官方第三方项目。" to sections118
            this.version -> intro to sections
            else -> null
        }
    }
}
