package com.tavern.app.console

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tavern.app.backup.AutoBackupWorker
import com.tavern.app.backup.BackupManager
import com.tavern.app.node.NodeState
import com.tavern.app.util.AssetExtractor
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class ConsoleViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx = getApplication<Application>()
    val backupManager = BackupManager(ctx)

    val nodeState: StateFlow<NodeState.State> = NodeState.state
    val nodePort: StateFlow<Int> = NodeState.port
    val isRunning: Boolean get() = nodeState.value == NodeState.State.RUNNING

    private val _backupProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val backupProgress: StateFlow<Pair<Int, Int>?> = _backupProgress.asStateFlow()

    private val _backupPhase = MutableStateFlow("")
    val backupPhase: StateFlow<String> = _backupPhase.asStateFlow()

    private val _backupLog = MutableStateFlow<List<String>>(emptyList())
    val backupLog: StateFlow<List<String>> = _backupLog.asStateFlow()

    private val _backupResult = MutableStateFlow<Result<File>?>(null)
    val backupResult: StateFlow<Result<File>?> = _backupResult.asStateFlow()

    private val _restoreProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val restoreProgress: StateFlow<Pair<Int, Int>?> = _restoreProgress.asStateFlow()

    private val _restorePhase = MutableStateFlow("")
    val restorePhase: StateFlow<String> = _restorePhase.asStateFlow()

    private val _restoreLog = MutableStateFlow<List<String>>(emptyList())
    val restoreLog: StateFlow<List<String>> = _restoreLog.asStateFlow()

    private val _restoreResult = MutableStateFlow<Result<Unit>?>(null)
    val restoreResult: StateFlow<Result<Unit>?> = _restoreResult.asStateFlow()

    val autoBackupEnabled: Boolean get() = AutoBackupWorker.isEnabled(ctx)
    val autoBackupInterval: Int get() = AutoBackupWorker.getInterval(ctx)
    val autoBackupMaxKeep: Int get() = AutoBackupWorker.getMaxKeep(ctx)

    fun getStorageInfo(): StorageInfo {
        val coreDir = AssetExtractor.getCoreDir(ctx)
        val dataDir = File(coreDir, "data")
        val coreSize = backupManager.getDirSize(coreDir)
        val dataSize = backupManager.getDirSize(dataDir)
        val backupSize = backupManager.getBackupsSize()
        val freeSpace = backupManager.backupDir.freeSpace
        return StorageInfo(coreSize, dataSize, backupSize, freeSpace)
    }

    data class StorageInfo(
        val coreSize: Long,
        val dataSize: Long,
        val backupSize: Long,
        val freeSpace: Long
    )

    fun startBackup(coreVersion: String = "1.0.0") {
        viewModelScope.launch {
            _backupProgress.value = 0 to 0
            _backupPhase.value = "正在扫描用户数据…"
            _backupLog.value = emptyList()
            _backupResult.value = null

            val coreDir = AssetExtractor.getCoreDir(ctx)

            val result = backupManager.createBackup(coreDir, coreVersion) { cur, total, name ->
                _backupProgress.value = cur to total
                _backupPhase.value = if (cur > 0) "正在打包（$cur/$total）…" else name
                // Must create new list ref: StateFlow uses referential equality (===)
                _backupLog.value = _backupLog.value + (if (cur > 0) "[${cur}/${total}] $name" else "\$ $name")
            }
            _backupResult.value = result
        }
    }

    fun startRestore(backupFile: File) {
        viewModelScope.launch {
            _restoreProgress.value = 0 to 0
            _restorePhase.value = "正在还原…"
            _restoreLog.value = emptyList()
            _restoreResult.value = null

            val coreDir = AssetExtractor.getCoreDir(ctx)

            val result = backupManager.restoreBackup(backupFile, coreDir) { cur, total, name ->
                _restoreProgress.value = cur to total
                _restorePhase.value = if (cur > 0) "正在还原（$cur/$total）…" else name
                _restoreLog.value = _restoreLog.value + (if (cur > 0) "[${cur}/${total}] $name" else "\$ $name")
            }
            _restoreResult.value = result
        }
    }

    fun clearBackupState() {
        _backupProgress.value = null
        _backupResult.value = null
    }

    fun clearRestoreState() {
        _restoreProgress.value = null
        _restoreResult.value = null
    }

    fun setAutoBackup(enabled: Boolean) {
        AutoBackupWorker.setEnabled(ctx, enabled)
        AutoBackupWorker.schedule(ctx)
    }

    fun setAutoBackupInterval(days: Int) {
        AutoBackupWorker.setInterval(ctx, days)
        AutoBackupWorker.schedule(ctx)
    }

    fun setAutoBackupMaxKeep(max: Int) {
        AutoBackupWorker.setMaxKeep(ctx, max)
    }

    fun clearAppCache(): Long {
        var freed = 0L
        val webviewCache = File(ctx.cacheDir, "WebView")
        if (webviewCache.exists()) {
            freed += backupManager.getDirSize(webviewCache)
            webviewCache.deleteRecursively()
        }
        val appCache = ctx.cacheDir
        appCache.listFiles()?.forEach { f ->
            if (f.name != "WebView" && f.name != "tavern-node") {
                freed += f.length()
                f.deleteRecursively()
            }
        }
        return freed
    }
}
