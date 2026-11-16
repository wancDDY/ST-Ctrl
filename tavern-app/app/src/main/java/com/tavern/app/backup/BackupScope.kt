package com.tavern.app.backup

/**
 * What a backup carries.
 *
 * Every part is on unless the reader turned it off: a backup that silently leaves a
 * piece behind only surfaces on the device where the piece is missing. The two that
 * are easy to forget are the card library — it lives in app-private storage and dies
 * with the app — and the app's own settings, which a core restore does not bring back.
 */
data class BackupScope(
    /** Chats, characters, world books, memory graphs: the tavern's own data tree. */
    val chats: Boolean = true,
    /** Installed extensions, including their per-extension data. */
    val extensions: Boolean = true,
    /** The core's config.yaml. */
    val coreConfig: Boolean = true,
    /** The card library under app-private storage, with folders and version labels. */
    val library: Boolean = true,
    /** Theme, port, switches and the backup schedule. */
    val appSettings: Boolean = true,
    /** Keys inside settings and secrets files. */
    val apiKeys: Boolean = true,
) {
    /** Nothing ticked means nothing to write; the caller refuses instead of writing an empty archive. */
    val isEmpty: Boolean get() = !chats && !extensions && !coreConfig && !library && !appSettings
}
