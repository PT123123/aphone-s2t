package com.example.aphones2t.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.aphones2t.R
import com.example.aphones2t.asr.SherpaStreamingAsr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Downloads a model archive, verifies it, extracts it, installs it atomically and
 * finally load-tests the native recognizer. Supports resume via HTTP Range and
 * retries transient failures through WorkManager.
 *
 * 作为 WorkManager 前台任务运行，所以退出页面 / 切后台也会继续下载；
 * 每个阶段的失败现场（阶段、HTTP 码、异常、堆栈、已下载字节）都会写进
 * [DownloadDiagnostics]，模型卡片可以展开看到完整原因。
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val client = OkHttpClient()
    private val extractor = ArchiveExtractor

    private var modelId: String = ""
    private var stage: String = STAGE_INIT
    private var httpCode: Int = 0
    private var downloadedBytes: Long = 0L
    private var totalBytes: Long = 0L
    private var bytesPerSec: Long = 0L
    private var etaSec: Long = 0L
    private var sourceUrl: String = ""
    private var lastFailure: DownloadError? = null

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_MODEL_ID)
            ?: return@withContext Result.failure(errorData("缺少模型 id（内部错误）"))
        modelId = id
        val model = ModelCatalog.findById(applicationContext, id)
            ?: return@withContext Result.failure(errorData("未知模型：$id"))
        sourceUrl = model.archive.url
        totalBytes = model.archive.sizeBytes.coerceAtLeast(0L)

        try {
            stage = STAGE_QUEUED
            publish()
            setForeground(foregroundInfo(model.name, 0))
            DownloadDiagnostics.log(applicationContext, model.id, "开始下载 ${model.archive.name}")

            val staging = ModelManager.stagingDirectory(applicationContext, model)
            staging.mkdirs()
            checkFreeSpace(model, staging)

            stage = STAGE_DOWNLOADING
            downloadArchive(model, staging)

            stage = STAGE_EXTRACTING
            publish()
            setForeground(foregroundInfo(model.name, percentInt(), verifying = true))
            DownloadDiagnostics.log(applicationContext, model.id, "下载完成，正在解压")

            extractor.extract(File(staging, model.archive.name), staging)

            stage = STAGE_VERIFYING
            publish()
            if (model.files.isNotEmpty()) verifyFiles(model, staging)

            stage = STAGE_INSTALLING
            publish()
            installAtomically(model, staging)

            stage = STAGE_LOADING
            publish()
            val dest = ModelManager.installedDirectory(applicationContext, model)
            writeMetadata(model, dest)
            if (!SherpaStreamingAsr.isModelValid(dest)) {
                dest.deleteRecursively()
                throw IllegalStateException("模型加载校验失败：encoder / decoder / tokens 不匹配或文件损坏")
            }

            ModelManager.setActiveModel(applicationContext, model.id)
            DownloadDiagnostics.clearError(applicationContext, model.id)
            DownloadDiagnostics.saveProgress(
                applicationContext, model.id,
                ProgressSnapshot(STAGE_DONE, downloadedBytes, totalBytes, 100, 0L, 0L)
            )
            DownloadDiagnostics.log(applicationContext, model.id, "安装完成，已设为当前模型")
            Result.success(workDataOf(KEY_MODEL_ID to model.id, KEY_PROGRESS to 100))
        } catch (cancelled: CancellationException) {
            DownloadDiagnostics.log(applicationContext, modelId, "任务被取消，已下载的分片保留")
            throw cancelled
        } catch (e: Exception) {
            recordFailure(e)
            Result.failure(errorData(e.message ?: "下载失败"))
        }
    }

    // ---------------- download ----------------

    private suspend fun downloadArchive(model: LocalModelInfo, staging: File) {
        val name = model.archive.name
        val complete = File(staging, name)
        val sizeKnown = model.archive.sizeBytes > 0
        val sha = model.archive.sha256

        if (complete.exists() && (!sizeKnown || complete.length() == model.archive.sizeBytes)) {
            if (sha == null || sha256(complete) == sha) {
                downloadedBytes = complete.length()
                DownloadDiagnostics.log(applicationContext, modelId, "压缩包已存在且完整，跳过下载")
                return
            }
            complete.delete()
        }

        val partial = File(staging, "$name.part")
        if (sizeKnown && partial.length() == model.archive.sizeBytes) {
            if (sha == null || sha256(partial) == sha) {
                downloadedBytes = partial.length()
                move(partial, complete)
                return
            }
            partial.delete()
        }

        var offset = if (sizeKnown) partial.length().coerceAtLeast(0) else 0L
        if (!sizeKnown) partial.delete() // 总大小未知时不做断点续传
        downloadedBytes = offset
        if (offset > 0L) {
            DownloadDiagnostics.log(
                applicationContext, modelId,
                "断点续传：已有 ${Formatter.formatFileSize(applicationContext, offset)}"
            )
        }

        val request = Request.Builder()
            .url(model.archive.url)
            .header("User-Agent", "AphoneS2T/1.0")
            .apply { if (sizeKnown && offset > 0) header("Range", "bytes=$offset-") }
            .build()

        client.newCall(request).execute().use { resp ->
            httpCode = resp.code
            if (!resp.isSuccessful) {
                throw IOException(
                    "服务器返回 HTTP ${resp.code}${resp.message.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}"
                )
            }
            val append = sizeKnown && offset > 0 && resp.code == 206
            if (sizeKnown && offset > 0 && !append) {
                DownloadDiagnostics.log(applicationContext, modelId, "服务器不支持断点续传，从头开始")
                partial.delete(); offset = 0
                downloadedBytes = 0
            }
            val contentLength = resp.body?.contentLength() ?: -1L
            if (contentLength > 0L) {
                totalBytes = if (append) offset + contentLength else contentLength
            }
            val body = resp.body ?: throw IOException("响应体为空：服务器返回 ${resp.code} 但没有数据")
            val source = body.byteStream()
            var windowStart = System.currentTimeMillis()
            var windowBytes = offset
            FileOutputStream(partial, append).use { out ->
                val buf = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val c = source.read(buf)
                    if (c < 0) break
                    out.write(buf, 0, c)
                    offset += c
                    if (sizeKnown && offset > model.archive.sizeBytes) {
                        throw IOException("${model.archive.name} 体积超过预期（服务器数据比声明的多）")
                    }
                    val now = System.currentTimeMillis()
                    val dt = now - windowStart
                    if (dt >= 500L) {
                        bytesPerSec = ((offset - windowBytes) * 1000L) / dt.coerceAtLeast(1L)
                        windowStart = now
                        windowBytes = offset
                        downloadedBytes = offset
                        etaSec = if (bytesPerSec > 0L && totalBytes > offset) {
                            (totalBytes - offset) / bytesPerSec
                        } else 0L
                        publish()
                        setForeground(foregroundInfo(model.name, percentInt()))
                    }
                }
                out.fd.sync()
            }
        }

        downloadedBytes = offset
        bytesPerSec = 0L
        etaSec = 0L
        publish()

        if (sizeKnown && partial.length() != model.archive.sizeBytes) {
            throw IOException(
                "${model.archive.name} 下载不完整：期望 ${model.archive.sizeBytes} 字节，" +
                    "实际 ${partial.length()} 字节（网络中断或服务器提前断开）"
            )
        }
        if (sha != null && sha256(partial) != sha) {
            partial.delete()
            throw IOException("${model.archive.name} SHA-256 校验和不匹配（服务器上的文件可能与清单不一致）")
        }
        move(partial, complete)
    }

    private fun checkFreeSpace(model: LocalModelInfo, staging: File) {
        val need = model.archive.sizeBytes
        if (need <= 0) return
        val usable = try { staging.usableSpace } catch (_: Exception) { 0L }
        if (usable <= 0L) return
        val required = (need * 1.3).toLong() + 64L * 1024 * 1024
        if (usable < required) {
            throw IOException(
                "存储空间不足：需要约 ${Formatter.formatFileSize(applicationContext, required)}" +
                    "（含解压余量），当前可用 ${Formatter.formatFileSize(applicationContext, usable)}"
            )
        }
    }

    private fun verifyFiles(model: LocalModelInfo, staging: File) {
        for (f in model.files) {
            val file = File(staging, f.name)
            if (!file.isFile) throw IOException("缺少文件: ${f.name}")
            if (f.sizeBytes > 0 && file.length() != f.sizeBytes) {
                throw IOException("文件大小不匹配: ${f.name}（期望 ${f.sizeBytes}，实际 ${file.length()}）")
            }
            if (!f.sha256.isNullOrBlank() && sha256(file) != f.sha256) {
                throw IOException("文件校验失败: ${f.name}（SHA-256 不匹配）")
            }
        }
    }

    private fun installAtomically(model: LocalModelInfo, staging: File) {
        val root = ModelManager.modelRoot(applicationContext, model)
        val dest = ModelManager.installedDirectory(applicationContext, model)
        if (ModelManager.isInstalled(applicationContext, model)) {
            staging.deleteRecursively(); return
        }
        val backup = File(root, "${model.version}.previous")
        backup.deleteRecursively()
        if (dest.exists() && !dest.renameTo(backup)) {
            throw IOException("无法替换旧模型：${dest.absolutePath} 被占用")
        }
        if (!staging.renameTo(dest)) {
            backup.renameTo(dest)
            throw IOException("无法安装已校验的模型：${staging.absolutePath} -> ${dest.absolutePath} 移动失败")
        }
        backup.deleteRecursively()
        File(root, ".paused").delete()
    }

    private fun writeMetadata(model: LocalModelInfo, dir: File) {
        File(dir, "installed.marker").writeText(model.version)
        val manifest = JSONObject().apply {
            put("id", model.id)
            put("version", model.version)
            put("runtime", "sherpa-onnx")
            put("task", "stt")
            put("license", model.license)
            put("url", model.archive.url)
            put(
                "files", JSONArray().apply {
                    dir.listFiles()?.forEach { f -> put(f.name) }
                }
            )
        }
        File(dir, "manifest.json").writeText(manifest.toString())
    }

    private fun move(src: File, dst: File) {
        dst.delete()
        if (!src.renameTo(dst)) throw IOException("无法移动 ${dst.name}")
    }

    // ---------------- progress / errors ----------------

    /** 上报进度给 WorkManager（UI 观察 workInfo）+ 持久化快照（重开页面也能看到）。 */
    private suspend fun publish() {
        val pct = percentInt()
        DownloadDiagnostics.saveProgress(
            applicationContext, modelId,
            ProgressSnapshot(
                stage = stage,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                percent = pct,
                bytesPerSec = bytesPerSec,
                etaSec = etaSec
            )
        )
        setProgress(
            workDataOf(
                KEY_MODEL_ID to modelId,
                KEY_PROGRESS to pct,
                KEY_DOWNLOADED_BYTES to downloadedBytes,
                KEY_TOTAL_BYTES to totalBytes,
                KEY_STAGE to stage
            )
        )
    }

    private fun percentInt(): Int = when {
        stage == STAGE_DOWNLOADING && totalBytes > 0L ->
            ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
        stage == STAGE_DOWNLOADING -> 0
        else -> 100
    }

    private fun recordFailure(e: Throwable) {
        val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        val detail = DownloadError(
            stage = stage,
            type = e.javaClass.name,
            message = message,
            cause = e.cause?.let { "${it.javaClass.name}: ${it.message.orEmpty()}" },
            httpCode = httpCode,
            url = sourceUrl,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            stack = e.stackTrace.take(14).joinToString("\n") { "    at $it" }
        )
        lastFailure = detail
        DownloadDiagnostics.saveError(applicationContext, modelId, detail)
        DownloadDiagnostics.log(applicationContext, modelId, "失败（阶段 $stage）：$message")
        if (httpCode > 0) DownloadDiagnostics.log(applicationContext, modelId, "HTTP 状态码：$httpCode")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val c = input.read(buf)
                if (c < 0) break
                digest.update(buf, 0, c)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun foregroundInfo(name: String, progress: Int, verifying: Boolean = false): ForegroundInfo {
        createChannel()
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val content = if (verifying) {
            applicationContext.getString(R.string.download_verifying)
        } else {
            applicationContext.getString(R.string.download_progress, name, progress.coerceIn(0, 100))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.download_notification_title))
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), verifying)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                applicationContext.getString(R.string.action_cancel), cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = applicationContext.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    /** 失败时往 outputData 里塞结构化信息，UI 用不上也不会丢。 */
    private fun errorData(message: String) = workDataOf(
        KEY_ERROR to message.take(500),
        KEY_ERROR_STAGE to stage,
        KEY_ERROR_TYPE to (lastFailure?.type ?: ""),
        KEY_ERROR_TIME to System.currentTimeMillis(),
        KEY_ERROR_HTTP to httpCode
    )

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_STAGE = "stage"
        const val KEY_ERROR = "error"
        const val KEY_ERROR_STAGE = "error_stage"
        const val KEY_ERROR_TYPE = "error_type"
        const val KEY_ERROR_TIME = "error_time"
        const val KEY_ERROR_HTTP = "error_http"

        const val STAGE_INIT = "init"
        const val STAGE_QUEUED = "queued"
        const val STAGE_DOWNLOADING = "downloading"
        const val STAGE_EXTRACTING = "extracting"
        const val STAGE_VERIFYING = "verifying"
        const val STAGE_INSTALLING = "installing"
        const val STAGE_LOADING = "loading"
        const val STAGE_PAUSED = "paused"
        const val STAGE_DONE = "done"

        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 4307
    }
}
