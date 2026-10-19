package com.tavern.app.update

object TavernCoreCapabilities {
    enum class Feature(val minimumCoreVersion: String, val label: String) {
        COMPATIBILITY("1.18.0", "兼容渲染"),
        LONG_CHAT("1.19.0", "长对话渐进渲染"),
        CHARACTER_IMPORT("1.19.0", "角色卡低内存导入"),
        MEMORY("1.19.0", "角色记忆"),
    }

    fun supports(feature: Feature, coreVersion: String): Boolean {
        if (coreVersion.isBlank()) {
            return false
        }
        return !VersionComparator.isNewer(
            feature.minimumCoreVersion,
            coreVersion,
        )
    }

    fun disabledReason(feature: Feature): String {
        return "需要核心 ${feature.minimumCoreVersion} 或更高版本"
    }
}
