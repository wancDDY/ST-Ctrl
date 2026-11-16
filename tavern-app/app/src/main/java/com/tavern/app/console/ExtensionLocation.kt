package com.tavern.app.console

/**
 * Where an extension directory lives. The core lists all three roots and loads all three: its own
 * built-in extensions, the user directory, and the global directory that holds extensions installed
 * for every account. A name present in both the user and the global directory is loaded once, from
 * the user directory, so the other copy is the only one that does nothing.
 */
enum class ExtensionLocation(val label: String) {
    USER("用户目录"),
    GLOBAL("全局目录"),
    BUILTIN("内置目录");

    companion object {
        fun fromStorage(value: String): ExtensionLocation =
            entries.firstOrNull { it.name == value } ?: USER
    }
}
