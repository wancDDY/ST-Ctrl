package com.tavern.app.console

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tavern.app.backup.AutoBackupWorker
import com.tavern.app.backup.BackupManager
import com.tavern.app.backup.BackupMetadata
import com.tavern.app.node.NodeState
import com.tavern.app.util.AssetExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ConsoleViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx = getApplication<Application>()
    val backupManager = BackupManager(ctx)

    val nodeState: StateFlow<NodeState.State> = NodeState.state
    val nodePort: StateFlow<Int> = NodeState.port
    val isRunning: Boolean get() = nodeState.value == NodeState.State.RUNNING

    /** Unified state for backup/restore operations. */
    sealed class OpState {
        object Idle : OpState()
        data class Running(val current: Int, val total: Int, val phase: String, val log: List<String>) : OpState()
        data class Done(val success: Boolean, val message: String, val log: List<String> = emptyList(), val file: java.io.File? = null) : OpState()
    }

    private val _backupState = MutableStateFlow<OpState>(OpState.Idle)
    val backupState: StateFlow<OpState> = _backupState.asStateFlow()

    private val _restoreState = MutableStateFlow<OpState>(OpState.Idle)
    val restoreState: StateFlow<OpState> = _restoreState.asStateFlow()

    // Derived flows for backward compatibility with existing UI screens
    val backupProgress: StateFlow<Pair<Int, Int>?> = _backupState.map {
        (it as? OpState.Running)?.let { s -> s.current to s.total }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val backupPhase: StateFlow<String> = _backupState.map {
        (it as? OpState.Running)?.phase ?: ""
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val backupLog: StateFlow<List<String>> = _backupState.map {
        when (val s = it) {
            is OpState.Running -> s.log
            is OpState.Done -> s.log
            else -> emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val backupResult: StateFlow<Result<File>?> = _backupState.map {
        when (val s = it) {
            is OpState.Done -> if (s.success) Result.success(s.file ?: File("")) else Result.failure(Exception(s.message))
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val restoreProgress: StateFlow<Pair<Int, Int>?> = _restoreState.map {
        (it as? OpState.Running)?.let { s -> s.current to s.total }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val restorePhase: StateFlow<String> = _restoreState.map {
        (it as? OpState.Running)?.phase ?: ""
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val restoreLog: StateFlow<List<String>> = _restoreState.map {
        (it as? OpState.Running)?.log ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val restoreResult: StateFlow<Result<Unit>?> = _restoreState.map {
        when (val s = it) {
            is OpState.Done -> if (s.success) Result.success(Unit) else Result.failure(Exception(s.message))
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val autoBackupEnabled: Boolean get() = AutoBackupWorker.isEnabled(ctx)
    val autoBackupInterval: Int get() = AutoBackupWorker.getInterval(ctx)
    val autoBackupMaxKeep: Int get() = AutoBackupWorker.getMaxKeep(ctx)

    private val _storageInfo = MutableStateFlow<StorageInfo?>(null)
    val storageInfo: StateFlow<StorageInfo?> = _storageInfo.asStateFlow()

    suspend fun refreshStorageInfo(): StorageInfo = withContext(Dispatchers.IO) {
        try {
            val coreDir = AssetExtractor.getCoreDir(ctx)
            val dataDir = File(coreDir, "data")
            // Single pass: bucket files into categories
            var coreOnly = 0L
            var dataSize = 0L
            var chars = 0L; var chats = 0L; var vectors = 0L; var thumb = 0L; var otherData = 0L
            coreDir.walkTopDown().filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach { f ->
                val path = f.absolutePath
                val len = f.length()
                if (path.startsWith(dataDir.absolutePath + File.separator) || path == dataDir.absolutePath) {
                    dataSize += len
                    when {
                        path.contains("${File.separator}characters${File.separator}") || path.contains("${File.separator}chats${File.separator}") -> chats += len
                        path.contains("${File.separator}vectors${File.separator}") -> vectors += len
                        path.contains("${File.separator}thumbnails${File.separator}") || path.contains("${File.separator}User Avatars${File.separator}") -> thumb += len
                        else -> otherData += len
                    }
                } else coreOnly += len
            }
            // Chars dir directly
            val charDir = File(dataDir, "default-user/characters")
            if (charDir.exists()) {
                charDir.walkTopDown().filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach { chars += it.length() }
            }
            dataSize -= chars // already counted in dataSize, chars is separate
            if (dataSize < 0) dataSize = 0
            val backupSize = backupManager.getBackupsSize()
            val freeSpace = backupManager.backupDir.freeSpace
            StorageInfo(coreOnly, dataSize, backupSize, freeSpace, chars, chats, vectors, thumb, otherData).also { _storageInfo.value = it }
        } catch (e: Exception) {
            android.util.Log.e("ConsoleVM", "refreshStorageInfo failed", e)
            StorageInfo(-1, -1, -1, -1, -1, -1, -1, -1, -1).also { _storageInfo.value = it }
        }
    }

    data class StorageInfo(
        val coreSize: Long,
        val dataSize: Long,
        val backupSize: Long,
        val freeSpace: Long,
        val charactersSize: Long = 0,
        val chatsSize: Long = 0,
        val vectorsSize: Long = 0,
        val thumbnailsSize: Long = 0,
        val otherDataSize: Long = 0
    )

    fun startBackup(coreVersion: String = "1.0.0") {
        viewModelScope.launch {
            val logBuf = mutableListOf<String>()
            _backupState.value = OpState.Running(0, 0, "正在扫描用户数据…", emptyList())

            val coreDir = AssetExtractor.getCoreDir(ctx)

            val result = backupManager.createBackup(coreDir, coreVersion) { cur, total, name ->
                val phase = if (cur > 0) "正在打包（$cur/$total）…" else name
                val line = if (cur > 0) "[${cur}/${total}] $name" else "\$ $name"
                logBuf.add(line)
                if (cur % 50 == 0 || cur == total) {
                    _backupState.value = OpState.Running(cur, total, phase, logBuf.toList())
                }
            }
            _backupState.value = result.fold(
                onSuccess = {
                    AutoBackupWorker.recordBackupDone(ctx)
                    OpState.Done(true, "备份完成：${it.name}", logBuf.toList(), it)
                },
                onFailure = { OpState.Done(false, it.message ?: "备份失败", logBuf.toList()) }
            )
        }
    }

    fun startRestore(backupFile: File) {
        viewModelScope.launch {
            val logBuf = mutableListOf<String>()
            _restoreState.value = OpState.Running(0, 0, "正在还原…", emptyList())

            val coreDir = AssetExtractor.getCoreDir(ctx)

            val result = backupManager.restoreBackup(backupFile, coreDir) { cur, total, name ->
                val phase = if (cur > 0) "正在还原（$cur/$total）…" else name
                val line = if (cur > 0) "[${cur}/${total}] $name" else "\$ $name"
                logBuf.add(line)
                // Update progress every 5 items or at completion
                if (cur % 5 == 0 || cur == total) {
                    _restoreState.value = OpState.Running(cur, total, phase, logBuf.toList())
                }
            }
            _restoreState.value = result.fold(
                onSuccess = { OpState.Done(true, "还原完成") },
                onFailure = { OpState.Done(false, it.message ?: "还原失败") }
            )
        }
    }

    fun clearBackupState() {
        _backupState.value = OpState.Idle
        _cachedBackups = null
    }

    private var _cachedBackups: List<Pair<File, BackupMetadata>>? = null

    suspend fun listBackupsCached(): List<Pair<File, BackupMetadata>> {
        return _cachedBackups ?: backupManager.listBackups().also { _cachedBackups = it }
    }

    fun invalidateBackupCache() { _cachedBackups = null }

    fun clearRestoreState() {
        _restoreState.value = OpState.Idle
    }

    fun setAutoBackup(enabled: Boolean) {
        AutoBackupWorker.setEnabled(ctx, enabled)
    }

    fun setAutoBackupInterval(days: Int) {
        AutoBackupWorker.setInterval(ctx, days)
    }

    fun setAutoBackupMaxKeep(max: Int) {
        AutoBackupWorker.setMaxKeep(ctx, max)
    }

    suspend fun clearAppCache(): Long = withContext(Dispatchers.IO) {
        try {
            var freed = 0L
            // Protected entries: runtime, node binary, updater temp files
            val protected = setOf(
                "WebView", "tavern-node",
                "data-extract-bak", "ext-backup", "data-update-bak", "ext-update-bak", "core-update-bak",
                "core-update-tmp",  // CoreUpdater active temp dir
            )
            // Also protect any file starting with "tavern-update-" or "ext-" (active downloads/installs)
            val webviewCache = File(ctx.cacheDir, "WebView")
            if (webviewCache.exists()) {
                freed += backupManager.getDirSize(webviewCache)
                webviewCache.deleteRecursively()
            }
            ctx.cacheDir.listFiles()?.forEach { f ->
                val n = f.name
                val isProtected = n in protected || n.startsWith("tavern-update-") || n.startsWith("ext-tmp-") || n.startsWith("ext-") && n.endsWith(".zip")
                if (!isProtected) {
                    freed += if (f.isDirectory) backupManager.getDirSize(f) else f.length()
                    f.deleteRecursively()
                }
            }
            freed
        } catch (e: Exception) {
            android.util.Log.e("ConsoleVM", "clearAppCache failed", e)
            throw e  // rethrow so UI can show error
        }
    }
}
