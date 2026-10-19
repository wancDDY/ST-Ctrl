package com.tavern.app.console

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tavern.app.backup.AutoBackupWorker
import com.tavern.app.backup.BackupCoordinator
import com.tavern.app.backup.BackupEntry
import com.tavern.app.backup.BackupManager
import com.tavern.app.backup.BackupMetadata
import com.tavern.app.node.NodeRunner
import com.tavern.app.node.NodeState
import com.tavern.app.update.CoreUpdater
import com.tavern.app.update.UpdateChecker
import com.tavern.app.util.AssetExtractor
import com.tavern.app.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ConsoleViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx = getApplication<Application>()
    val backupManager = BackupManager(ctx)

    val nodeState: StateFlow<NodeState.State> = NodeState.state
    val nodePort: StateFlow<Int> = NodeState.port
    val startInFlight: StateFlow<Boolean> = NodeState.startInFlight
    val isRunning: Boolean get() = nodeState.value == NodeState.State.RUNNING

    /** Unified state for backup/restore operations. */
    sealed class OpState {
        object Idle : OpState()
        data class Running(val current: Int, val total: Int, val phase: String, val log: List<String>) : OpState()
        data class Done(
            val success: Boolean,
            val message: String,
            val log: List<String> = emptyList(),
            val entry: BackupEntry? = null
        ) : OpState()
    }

    private val _backupState = MutableStateFlow<OpState>(OpState.Idle)
    val backupState: StateFlow<OpState> = _backupState.asStateFlow()

    private val _restoreState = MutableStateFlow<OpState>(OpState.Idle)
    val restoreState: StateFlow<OpState> = _restoreState.asStateFlow()

    sealed class CoreUpdateState {
        object Idle : CoreUpdateState()
        data class Running(
            val progress: Float,
            val phase: String,
            val version: String,
            /**
             * Whether cancelling right now would actually stop the work. The replacement of the
             * core directory cannot be interrupted without leaving a half-written core, so the
             * screen hides the button rather than offering one that does nothing.
             */
            val cancellable: Boolean = true,
            /**
             * True from the moment a cancel was accepted until the work has stopped. Separate from
             * [cancellable] because "cannot be cancelled" and "is being cancelled" call for
             * different words on screen, and showing the first while the second is happening told
             * the reader both things at once.
             */
            val cancelling: Boolean = false
        ) : CoreUpdateState()
        data class Done(
            val success: Boolean,
            val message: String,
            val version: String,
            /**
             * A cancel is neither a success nor a failure: the update did not happen and nothing
             * is broken, so reporting it with the failure caption told the reader something untrue.
             */
            val cancelled: Boolean = false
        ) : CoreUpdateState()
    }

    private val _coreUpdateState = MutableStateFlow<CoreUpdateState>(CoreUpdateState.Idle)
    val coreUpdateState: StateFlow<CoreUpdateState> = _coreUpdateState.asStateFlow()

    private val coreUpdateCancelled = AtomicBoolean(false)

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

    val backupResult: StateFlow<Result<BackupEntry>?> = _backupState.map {
        when (val s = it) {
            is OpState.Done -> if (s.success) {
                Result.success(s.entry ?: throw Exception("缺少备份文件信息"))
            } else {
                Result.failure(Exception(s.message))
            }
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
    val autoBackupIncludeApiKeys: Boolean get() = AutoBackupWorker.getIncludeApiKeys(ctx)
    private var lastIncludeApiKeys = true

    private val _storageInfo = MutableStateFlow<StorageInfo?>(null)
    val storageInfo: StateFlow<StorageInfo?> = _storageInfo.asStateFlow()

    private fun packageSizeBytes(): Long = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val dirs = buildList {
            info.applicationInfo?.sourceDir?.let(::add)
            info.applicationInfo?.splitSourceDirs?.forEach(::add)
        }
        dirs.sumOf { File(it).length() }
    } catch (_: Exception) {
        0L
    }

    // The runtime is a set of .so files the installer unpacks next to the APK, so the
    // package on disk is the APK plus this directory, not the APK alone.
    private fun nativeLibraryBytes(): Long = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val dir = info.applicationInfo?.nativeLibraryDir ?: return 0L
        backupManager.getDirSize(File(dir))
    } catch (_: Exception) {
        0L
    }

    suspend fun refreshStorageInfo(): StorageInfo = withContext(Dispatchers.IO) {
        try {
            val coreDir = AssetExtractor.getCoreDir(ctx)
            val dataDir = File(coreDir, "data")
            // Single pass: bucket files into categories
            var coreOnly = 0L
            var dataSize = 0L
            var chars = 0L; var chats = 0L; var vectors = 0L; var thumb = 0L; var otherData = 0L
            var coreProgram = 0L; var coreModules = 0L; var coreExtensions = 0L
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
                } else {
                    coreOnly += len
                    when {
                        // Tested before node_modules so the dependencies an installed
                        // extension brought with it stay under that extension.
                        path.contains("${File.separator}extensions${File.separator}third-party${File.separator}") -> coreExtensions += len
                        path.contains("${File.separator}node_modules${File.separator}") -> coreModules += len
                        else -> coreProgram += len
                    }
                }
            }
            // Chars dir directly
            val charDir = File(dataDir, "default-user/characters")
            if (charDir.exists()) {
                charDir.walkTopDown().filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach { chars += it.length() }
            }
            dataSize -= chars // already counted in dataSize, chars is separate
            if (dataSize < 0) dataSize = 0
            // One listing serves both the total and the newest entry the overview
            // shows when the backup block is opened.
            val backups = backupManager.listBackups()
            val backupSize = backups.sumOf { it.first.sizeBytes }
            val latestBackup = backups.maxByOrNull { it.first.lastModified }
            // Two things the total used to leave out: the installed package (the APK
            // carries the runtime and the core) and everything the app keeps outside
            // files/core, where the WebView cache is usually the biggest item.
            val apkSize = packageSizeBytes()
            val nativeLibSize = nativeLibraryBytes()
            val dataRoot = ctx.dataDir
            val privateTracked = coreOnly + dataSize + chars
            val privateTotal = backupManager.getDirSize(dataRoot)
            // A private backup inside the data dir is already reported by the backup
            // row; leaving it in the remainder counts it twice.
            val internalBackupBytes = backups
                .filter { it.first.privateStorage }
                .sumOf { (entry, _) ->
                    val file = entry.file ?: return@sumOf 0L
                    if (file.absolutePath.startsWith(dataRoot.absolutePath + File.separator)) {
                        file.length()
                    } else {
                        0L
                    }
                }
            val otherSize = (privateTotal - privateTracked - internalBackupBytes)
                .coerceAtLeast(0L)
            val cacheSize = backupManager.getDirSize(File(dataRoot, "cache")) +
                backupManager.getDirSize(File(dataRoot, "code_cache"))
            val databaseSize = backupManager.getDirSize(File(dataRoot, "databases"))
            val prefsSize = backupManager.getDirSize(File(dataRoot, "shared_prefs"))
            val webviewSize = backupManager.getDirSize(File(dataRoot, "app_webview"))
            val otherRestSize = (otherSize - cacheSize - databaseSize - prefsSize - webviewSize)
                .coerceAtLeast(0L)
            val freeSpace = backupManager.availableSpaceBytes()
            StorageInfo(
                coreSize = coreOnly,
                dataSize = dataSize,
                backupSize = backupSize,
                freeSpace = freeSpace,
                charactersSize = chars,
                chatsSize = chats,
                vectorsSize = vectors,
                thumbnailsSize = thumb,
                otherDataSize = otherData,
                coreProgramSize = coreProgram,
                coreModulesSize = coreModules,
                coreExtensionsSize = coreExtensions,
                backupCount = backups.size,
                latestBackupAt = latestBackup?.first?.lastModified ?: 0L,
                latestBackupSize = latestBackup?.first?.sizeBytes ?: 0L,
                apkSize = apkSize,
                nativeLibSize = nativeLibSize,
                otherSize = otherSize,
                cacheSize = cacheSize,
                databaseSize = databaseSize,
                prefsSize = prefsSize,
                webviewSize = webviewSize,
                otherRestSize = otherRestSize,
            ).also { _storageInfo.value = it }
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
        val otherDataSize: Long = 0,
        // Breakdown behind coreSize: the three buckets are disjoint and cover every
        // file outside data/, so their sum is coreSize.
        val coreProgramSize: Long = 0,
        val coreModulesSize: Long = 0,
        val coreExtensionsSize: Long = 0,
        val backupCount: Int = 0,
        val latestBackupAt: Long = 0,
        val latestBackupSize: Long = 0,
        // The installed package and everything outside files/core, so the
        // total matches what the system reports for the app.
        val apkSize: Long = 0,
        val nativeLibSize: Long = 0,
        val otherSize: Long = 0,
        val cacheSize: Long = 0,
        val databaseSize: Long = 0,
        val prefsSize: Long = 0,
        val webviewSize: Long = 0,
        val otherRestSize: Long = 0,
    )

    fun startBackup(coreVersion: String = "1.0.0") {
        startBackupWithOptions(coreVersion, lastIncludeApiKeys)
    }

    fun startBackup(coreVersion: String, includeApiKeys: Boolean) {
        startBackupWithOptions(coreVersion, includeApiKeys)
    }

    private fun startBackupWithOptions(coreVersion: String, includeApiKeys: Boolean) {
        lastIncludeApiKeys = includeApiKeys
        // Long tasks are mutually exclusive: never run a backup while a restore
        // (or another backup) is still in progress — they share the same
        // user-data tree and would corrupt each other.
        if (
            _backupState.value is OpState.Running
            || _restoreState.value is OpState.Running
            || _coreUpdateState.value is CoreUpdateState.Running
        ) return
        viewModelScope.launch {
            val logBuf = mutableListOf<String>()
            BackupCoordinator.withDataLock {
                if (
                    _backupState.value is OpState.Running
                    || _restoreState.value is OpState.Running
                    || _coreUpdateState.value is CoreUpdateState.Running
                ) return@withDataLock
                _backupState.value = OpState.Running(0, 0, "正在扫描用户数据…", emptyList())

                val coreDir = AssetExtractor.getCoreDir(ctx)

                // The caller only knows whether this is the scheduled ("auto") run; the
                // version written into the metadata is read from the installed core so
                // the archive does not claim a version nobody shipped.
                val recordedCoreVersion = if (coreVersion == "auto") {
                    coreVersion
                } else {
                    withContext(Dispatchers.IO) {
                        JsonUtil.readVersion(java.io.File(coreDir, "package.json"), "")
                    }.ifBlank { coreVersion }
                }

                val result = backupManager.createBackup(
                    coreDir = coreDir,
                    coreVersion = recordedCoreVersion,
                    onProgress = { cur, total, name ->
                        val phase = if (cur > 0) "正在打包（$cur/$total）…" else name
                        val line = if (cur > 0) "[${cur}/${total}] $name" else "\$ $name"
                        logBuf.add(line)
                        if (cur % 50 == 0 || cur == total) {
                            _backupState.value = OpState.Running(cur, total, phase, logBuf.toList())
                        }
                    },
                    includeApiKeys = includeApiKeys
                )
                _backupState.value = result.fold(
                    onSuccess = { entry ->
                        AutoBackupWorker.recordBackupDone(ctx)
                        OpState.Done(true, "备份完成：${entry.name}", logBuf.toList(), entry)
                    },
                    onFailure = { OpState.Done(false, it.message ?: "备份失败", logBuf.toList()) }
                )
            }
        }
    }

    fun startRestore(backup: BackupEntry) {
        // Mutual exclusion with backups — starting a restore while a backup is
        // running (or vice-versa) would race on the same data directory.
        if (
            _backupState.value is OpState.Running
            || _restoreState.value is OpState.Running
            || _coreUpdateState.value is CoreUpdateState.Running
        ) return
        viewModelScope.launch {
            val logBuf = mutableListOf<String>()
            var restored = false
            BackupCoordinator.withDataLock {
                if (
                    _backupState.value is OpState.Running
                    || _restoreState.value is OpState.Running
                    || _coreUpdateState.value is CoreUpdateState.Running
                ) return@withDataLock
                _restoreState.value = OpState.Running(0, 0, "正在还原…", emptyList())

                val coreDir = AssetExtractor.getCoreDir(ctx)

                val result = backupManager.restoreBackup(backup, coreDir) { cur, total, name ->
                    val phase = if (cur > 0) "正在还原（$cur/$total）…" else name
                    val line = if (cur > 0) "[${cur}/${total}] $name" else "\$ $name"
                    logBuf.add(line)
                    // Update progress every 5 items or at completion
                    if (cur % 5 == 0 || cur == total) {
                        _restoreState.value = OpState.Running(cur, total, phase, logBuf.toList())
                    }
                }
                _restoreState.value = result.fold(
                    onSuccess = {
                        restored = true
                        OpState.Done(true, "还原完成")
                    },
                    onFailure = { OpState.Done(false, it.message ?: "还原失败") }
                )
            }
            if (restored) {
                BackupManager.onAfterRestore?.invoke()
            }
        }
    }

    fun clearBackupState() {
        _backupState.value = OpState.Idle
        _cachedBackups = null
    }

    private var _cachedBackups: List<Pair<BackupEntry, BackupMetadata>>? = null

    suspend fun listBackupsCached(): List<Pair<BackupEntry, BackupMetadata>> {
        return _cachedBackups ?: backupManager.listBackups().also { _cachedBackups = it }
    }

    fun invalidateBackupCache() { _cachedBackups = null }

    fun clearRestoreState() {
        _restoreState.value = OpState.Idle
    }

    fun startCoreUpdate(info: UpdateChecker.ReleaseInfo) {
        if (
            _coreUpdateState.value is CoreUpdateState.Running
            || _backupState.value is OpState.Running
            || _restoreState.value is OpState.Running
        ) return

        coreUpdateCancelled.set(false)
        _coreUpdateState.value = CoreUpdateState.Running(
            progress = 0f,
            phase = "正在停止服务…",
            version = info.version
        )
        viewModelScope.launch {
            BackupCoordinator.withDataLock {
                if (
                    _backupState.value is OpState.Running
                    || _restoreState.value is OpState.Running
                ) {
                    _coreUpdateState.value = CoreUpdateState.Done(
                        false,
                        "更新失败：其他数据任务正在进行",
                        info.version
                    )
                    return@withDataLock
                }

                CoreUpdater.beginUpdate(ctx)
                try {
                    if (!stopNodeForCoreUpdate()) {
                        _coreUpdateState.value = CoreUpdateState.Done(
                            false,
                            "更新失败：核心服务未能停止，请稍后重试",
                            info.version
                        )
                        return@withDataLock
                    }

                    val result = CoreUpdater.applyUpdate(
                        context = ctx,
                        downloadUrl = info.downloadUrl,
                        version = info.version,
                        onProgress = { progress, phase, cancellable ->
                            _coreUpdateState.value = CoreUpdateState.Running(
                                progress = progress,
                                phase = phase,
                                version = info.version,
                                cancellable = cancellable
                            )
                        },
                        isCancelled = { coreUpdateCancelled.get() }
                    )

                    result.fold(
                        onSuccess = {
                            // Automatic health recovery must stay blocked until
                            // the replacement is complete, then yield to start.
                            CoreUpdater.endUpdate(ctx)
                            _coreUpdateState.value = CoreUpdateState.Running(
                                progress = 0.95f,
                                phase = "正在重启核心服务…",
                                version = info.version,
                                cancellable = false
                            )
                            val restarted = restartNodeAfterCoreUpdate()
                            _coreUpdateState.value = if (restarted) {
                                CoreUpdateState.Done(
                                    true,
                                    "核心已更新，服务已重启",
                                    info.version
                                )
                            } else {
                                CoreUpdateState.Done(
                                    false,
                                    "核心已更新，但服务启动失败：" +
                                        (NodeState.lastError.value ?: "启动超时"),
                                    info.version
                                )
                            }
                        },
                        onFailure = { error ->
                            if (error is com.tavern.app.update.UpdateCancelledException) {
                                // Reaching this point means the service was already stopped for the
                                // swap. Cancelling therefore has to put it back by itself: telling
                                // the reader to go and start it makes calling off an update cost
                                // them a running core.
                                CoreUpdater.endUpdate(ctx)
                                _coreUpdateState.value = CoreUpdateState.Running(
                                    progress = 0f,
                                    phase = "已取消更新，正在重启核心服务…",
                                    version = info.version,
                                    cancellable = false,
                                    cancelling = true
                                )
                                val restarted = restartNodeAfterCancel()
                                _coreUpdateState.value = CoreUpdateState.Done(
                                    success = restarted,
                                    message = if (restarted) {
                                        "已取消更新，核心服务已重启"
                                    } else {
                                        // The lock is released above, so the keep-alive probe is
                                        // free to bring the service back; it retries on its own.
                                        "已取消更新，核心服务正在自动重试启动"
                                    },
                                    version = info.version,
                                    cancelled = true
                                )
                            } else {
                                _coreUpdateState.value = CoreUpdateState.Done(
                                    false,
                                    "更新失败：${error.message ?: "未知错误"}",
                                    info.version
                                )
                            }
                        }
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _coreUpdateState.value = CoreUpdateState.Done(
                        false,
                        "更新失败：${e.message ?: "未知错误"}",
                        info.version
                    )
                } finally {
                    CoreUpdater.endUpdate(ctx)
                }
            }
        }
    }

    fun cancelCoreUpdate() {
        val running = _coreUpdateState.value as? CoreUpdateState.Running ?: return
        if (!running.cancellable) return
        coreUpdateCancelled.set(true)
        // The flag is only read between reads of the response body, so the download is dropped as
        // well; the phase text changes with it so the tap is acknowledged immediately.
        CoreUpdater.cancelActiveDownload()
        _coreUpdateState.value = running.copy(
            phase = "正在取消更新…",
            cancellable = false,
            cancelling = true
        )
    }

    private suspend fun stopNodeForCoreUpdate(): Boolean = withContext(Dispatchers.IO) {
        val port = SettingsState.nodePort()
        if (!NodeRunner.isPortOpen(port) && NodeState.state.value == NodeState.State.IDLE) {
            return@withContext true
        }

        NodeRunner.requestStop(ctx)
        val deadline = System.currentTimeMillis() + 20_000L
        var portClosedAt = 0L
        while (System.currentTimeMillis() < deadline) {
            if (!NodeRunner.isPortOpen(port)) {
                if (portClosedAt == 0L) portClosedAt = System.currentTimeMillis()
                if (System.currentTimeMillis() - portClosedAt >= 500L) {
                    return@withContext true
                }
            } else {
                portClosedAt = 0L
            }
            delay(200)
        }
        false
    }

    /**
     * Brings the service back after a cancelled update, retrying once.
     *
     * A single attempt is not enough here: the previous stop is often still settling when the
     * start lands, and the reader is left without a core for a reason that has nothing to do with
     * what they asked for. If both attempts fail the state says the retry continues, because the
     * caller has released the update lock and the keep-alive probe picks the service up from
     * there.
     */
    private suspend fun restartNodeAfterCancel(): Boolean {
        // Bounded more tightly than the update path: this one is undoing something the reader
        // asked for, and a service that has not answered in 45 seconds is not going to answer
        // in two minutes either. The keep-alive probe keeps trying afterwards.
        if (restartNodeAfterCoreUpdate(CANCEL_RESTART_TIMEOUT_MS)) return true
        delay(2_000)
        return restartNodeAfterCoreUpdate(CANCEL_RESTART_TIMEOUT_MS)
    }

    private suspend fun restartNodeAfterCoreUpdate(
        timeoutMs: Long = NodeRunner.STARTUP_TIMEOUT_MS
    ): Boolean = withContext(Dispatchers.IO) {
        val port = SettingsState.nodePort()
        val coreDir = AssetExtractor.getCoreDir(ctx)
        SettingsState.preparePerfParams(ctx)
        SettingsState.writeTavernServerOpts(ctx, coreDir)
        NodeState.setStarting()
        NodeState.setProgress(0.3f, "等待服务就绪…")

        try {
            NodeRunner.requestStart(ctx)
        } catch (e: Exception) {
            NodeState.setError("启动服务失败：${e.message ?: "未知错误"}")
            return@withContext false
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (
                NodeRunner.isPortOpen(port)
                && NodeRunner.isNodeHealthy(port)
            ) {
                NodeState.setRunning(port)
                SettingsState.markTavernServerOptsRead(ctx)
                return@withContext true
            }
            if (NodeState.state.value == NodeState.State.ERROR) {
                return@withContext false
            }
            delay(500)
        }
        false
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

    private companion object {
        /** How long a cancelled update waits for the service before handing off to keep-alive. */
        const val CANCEL_RESTART_TIMEOUT_MS = 45_000L
    }
}
