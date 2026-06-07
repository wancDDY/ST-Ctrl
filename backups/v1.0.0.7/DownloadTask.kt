package com.tavern.app.util

import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class DownloadTask(
    private val url: String,
    private val destFile: File,
    private val onProgress: (Float, Long, Long, String) -> Unit = { _, _, _, _ -> },
    private val onPhase: suspend (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "DownloadTask"
        private const val BUF_SIZE = 64 * 1024
    }

    private val paused = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val downloaded = AtomicLong(0)
    private var currentConn: HttpURLConnection? = null

    val isPaused: Boolean get() = paused.get()
    val isCancelled: Boolean get() = cancelled.get()
    val downloadedBytes: Long get() = downloaded.get()

    suspend fun start(): File = withContext(Dispatchers.IO) {
        try {
            cancelled.set(false)
            paused.set(false)
            downloaded.set(0)

            if (destFile.exists()) {
                downloaded.set(destFile.length())
            }

            destFile.parentFile?.mkdirs()

            var totalSize: Long
            try {
                val headConn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 10_000; readTimeout = 10_000
                    setRequestProperty("User-Agent", "TavernApp")
                    instanceFollowRedirects = true
                }
                val headCode = headConn.responseCode
                if (headCode != 200) {
                    headConn.disconnect()
                    throw Exception("HTTP $headCode — 文件不存在或网络不可达")
                }
                totalSize = headConn.contentLengthLong.coerceAtLeast(1)
                headConn.disconnect()
            } catch (e: Exception) {
                // HEAD may not be supported; fall back to GET for size
                totalSize = 0
            }

            downloadChunk(totalSize)
            if (destFile.length() < 1024) {
                try { destFile.delete() } catch (_: Exception) {}
                throw Exception("下载失败：文件无效，请检查网络或链接是否正确")
            }
            Log.i(TAG, "Download complete: ${destFile.absolutePath} (${destFile.length()} bytes)")
            destFile
        } finally {
            currentConn?.disconnect()
            currentConn = null
        }
    }

    private suspend fun downloadChunk(totalSize: Long): File {
        var position = downloaded.get()
        val unknownSize = totalSize <= 0

        while (unknownSize || position < totalSize) {
            if (cancelled.get()) {
                try { destFile.delete() } catch (_: Exception) {}
                throw CancellationException("Download cancelled")
            }
            if (paused.get()) { delay(500); continue }

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000; readTimeout = 60_000
                setRequestProperty("User-Agent", "TavernApp")
                instanceFollowRedirects = true
                if (position > 0) setRequestProperty("Range", "bytes=$position-")
            }

            val code = conn.responseCode
            if (code != 200 && code != 206) {
                conn.disconnect()
                throw Exception("HTTP $code")
            }

            currentConn = conn
            val input = conn.inputStream
            val raf = RandomAccessFile(destFile, "rw")
            raf.seek(position)

            try {
                val buf = ByteArray(BUF_SIZE)
                var bytesRead: Int
                val startTime = System.currentTimeMillis()
                var lastReport = startTime
                var bytesSinceReport = 0L

                while (input.read(buf).also { bytesRead = it } != -1) {
                    if (cancelled.get()) {
                        raf.close(); input.close()
                        try { destFile.delete() } catch (_: Exception) {}
                        throw CancellationException("Download cancelled")
                    }
                    if (paused.get()) break

                    raf.write(buf, 0, bytesRead)
                    position += bytesRead
                    bytesSinceReport += bytesRead
                    downloaded.set(position)

                    val now = System.currentTimeMillis()
                    if (now - lastReport > 500) {
                        val pct = if (totalSize > 0) position.toFloat() / totalSize else 0f
                        val speed = bytesSinceReport.toFloat() / ((now - lastReport).coerceAtLeast(1) / 1000f)
                        val phase = when {
                            speed > 1024 * 1024 -> String.format("%.1f MB/s", speed / 1024 / 1024)
                            speed > 1024 -> String.format("%.0f KB/s", speed / 1024)
                            else -> "${speed.toInt()} B/s"
                        }
                        withContext(Dispatchers.Main) { onProgress(pct.coerceIn(0f, 1f), position, totalSize, phase) }
                        lastReport = now
                        bytesSinceReport = 0
                    }
                }
                // Unknown size stream exhausted — done
                if (unknownSize) { position = Long.MAX_VALUE; break }
            } finally {
                raf.close()
                input.close()
                currentConn?.disconnect()
                currentConn = null
            }

            if (paused.get()) {
                withContext(Dispatchers.Main) { onProgress(downloaded.get().toFloat() / totalSize.coerceAtLeast(1), position, totalSize, "已暂停") }
            }
        }

        return destFile
    }

    fun pause() { paused.set(true) }
    fun resume() { paused.set(false) }

    fun cancel() {
        cancelled.set(true)
        currentConn?.disconnect()
        currentConn = null
    }
}
