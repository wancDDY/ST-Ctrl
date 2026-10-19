package com.tavern.app.backup

import android.net.Uri
import java.io.File

data class BackupEntry(
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val file: File? = null,
    val uri: Uri? = null,
    val folderUri: Uri? = null,
    val privateStorage: Boolean = false,
) {
    val key: String
        get() = file?.absolutePath ?: uri?.toString().orEmpty()

    val isTemporaryImport: Boolean
        get() = file?.name?.startsWith("imported-backup-") == true
}
