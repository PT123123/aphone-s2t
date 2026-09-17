package com.example.aphones2t.model

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.aphones2t.asr.SherpaStreamingAsr
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

enum class ModelInstallStatus {
    NOT_INSTALLED, QUEUED, DOWNLOADING, VERIFYING, PAUSED, INSTALLED, FAILED
}

/** 模型列表的状态筛选。 */
enum class ModelStatusFilter {
    ALL, NOT_INSTALLED, IN_PROGRESS, INSTALLED;

    fun matches(status: ModelInstallStatus): Boolean = when (this) {
        ALL -> true
        NOT_INSTALLED -> status == ModelInstallStatus.NOT_INSTALLED || status == ModelInstallStatus.FAILED
        IN_PROGRESS -> status == ModelInstallStatus.QUEUED ||
            status == ModelInstallStatus.DOWNLOADING ||
            status == ModelInstallStatus.VERIFYING ||
            status == ModelInstallStatus.PAUSED
        INSTALLED -> status == ModelInstallStatus.INSTALLED
    }
}

/**
 * 一个模型当前的全部状态。progressPercent < 0 表示总大小未知（自定义模型），
 * 此时 UI 用不确定进度条。errorDetail 是失败原因的结构化现场。
 */
data class ModelState(
    val info: LocalModelInfo,
    val status: ModelInstallStatus,
    val stage: String = "",
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSec: Long = 0L,
    val etaSec: Long = 0L,
    val error: String? = null,
    val errorDetail: DownloadError? = null,
    val isActive: Boolean = false,
    val installedSizeBytes: Long = 0L,
    val installedPath: String = ""
) {
    val isInstalled: Boolean get() = status == ModelInstallStatus.INSTALLED
    val hasFailure: Boolean get() = errorDetail != null || !error.isNullOrBlank()
}

/**
 * Orchestrates model download/install via WorkManager with pause-resume
 * (HTTP Range), SHA-256 verification, atomic install and failure recovery.
 *
 * 所有磁盘操作（删除 staging / 已安装目录）都丢到单线程后台执行，主线程只做
 * 状态切换，所以「取消 / 删除」不会再卡住界面。
 */
object ModelManager {

    private const val PREFS = "aphones2t_model_active"
    private const val KEY_ACTIVE = "active_model_id"

    /** 手动触发重新计算状态（设置活动模型、取消、删除等）。 */
    private val refresh = MutableStateFlow(0)

    /** 正在进行「取消」的模型 id：UI 立即按未下载显示，磁盘清理在后台慢慢做。 */
    private val cancelling = MutableStateFlow<Set<String>>(emptySet())

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "model-io").apply { isDaemon = true }
    }

    /** 已安装体积缓存，避免每次刷新都去遍历模型目录。 */
    private val installedSizeCache = ConcurrentHashMap<String, Long>()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun uniqueWorkName(info: LocalModelInfo) = "model-download-${info.id}"
    fun modelRoot(context: Context, info: LocalModelInfo) =
        File(context.filesDir, "models/sherpa-onnx/${info.id}")
    fun stagingDirectory(context: Context, info: LocalModelInfo) =
        File(modelRoot(context, info), "${info.version}.staging")
    fun installedDirectory(context: Context, info: LocalModelInfo) =
        File(modelRoot(context, info), info.version)
    private fun pausedMarker(context: Context, info: LocalModelInfo) =
        File(modelRoot(context, info), ".paused")

    fun isInstalled(context: Context, info: LocalModelInfo): Boolean {
        val dir = installedDirectory(context, info)
        return File(dir, "installed.marker").isFile && hasCoreFiles(dir)
    }

    private fun hasCoreFiles(dir: File): Boolean {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val names = files.map { it.name.lowercase() }
        return names.contains("tokens.txt") &&
            names.any { it.startsWith("encoder") && it.endsWith(".onnx") }
    }

    /** 已安装模型列表（按目录大小降序无关，保持目录顺序即可）。 */
    fun installedModels(context: Context): List<LocalModelInfo> =
        ModelCatalog.all(context).filter { isInstalled(context, it) }

    // ---- active model ----

    fun getActiveModelId(context: Context): String? = prefs(context).getString(KEY_ACTIVE, null)

    fun setActiveModel(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE, id).apply()
        refresh.value++
    }

    /** 当前生效的模型（已安装且 id 匹配，或第一个已安装模型）。 */
    fun getActiveModel(context: Context): LocalModelInfo? {
        val id = getActiveModelId(context)
        val candidate = id?.let { ModelCatalog.findById(context, it) }
            ?.takeIf { isInstalled(context, it) }
            ?: ModelCatalog.all(context).firstOrNull { isInstalled(context, it) }
        candidate ?: return null
        val dir = installedDirectory(context, candidate)
        return if (SherpaStreamingAsr.isModelValid(dir)) candidate else null
    }

    /** Returns the installed directory of the active model, or null if none ready. */
    fun getActiveModelDirectory(context: Context): File? {
        val model = getActiveModel(context) ?: return null
        return installedDirectory(context, model)
    }

    /**
     * 便宜版本：只判断「有已安装的模型」并返回目录，不构造原生识别器。
     * UI 判断按钮可用性用这个（构造 OnlineRecognizer 要加载几百 MB 的 ONNX，
     * 绝不能放在列表刷新 / 每次状态回调里）。
     */
    fun getInstalledModelDirectory(context: Context): File? {
        val id = getActiveModelId(context)
        val candidate = id?.let { ModelCatalog.findById(context, it) }
            ?.takeIf { isInstalled(context, it) }
            ?: ModelCatalog.all(context).firstOrNull { isInstalled(context, it) }
        return candidate?.let { installedDirectory(context, it) }
    }

    // ---- work control ----

    fun download(context: Context, info: LocalModelInfo) {
        val app = context.applicationContext
        DownloadDiagnostics.clearError(app, info.id)
        DownloadDiagnostics.log(app, info.id, "开始下载 ${info.archive.name}")
        pausedMarker(app, info).delete()
        cancelling.value = cancelling.value - info.id
        io.execute {
            val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to info.id))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .addTag("model-download")
                .addTag(info.id)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(
                uniqueWorkName(info), ExistingWorkPolicy.REPLACE, req
            )
            refresh.value++
        }
    }

    fun pause(context: Context, info: LocalModelInfo) {
        val app = context.applicationContext
        // 标记文件同步写，界面立刻变「已暂停」，不会等 WorkManager 的回调
        pausedMarker(app, info).apply { parentFile?.mkdirs(); writeText("paused") }
        DownloadDiagnostics.log(app, info.id, "用户暂停下载（已下载部分保留）")
        refresh.value++
        io.execute {
            WorkManager.getInstance(app).cancelUniqueWork(uniqueWorkName(info))
            refresh.value++
        }
    }

    fun resume(context: Context, info: LocalModelInfo) {
        DownloadDiagnostics.log(context.applicationContext, info.id, "继续下载（断点续传）")
        download(context, info)
    }

    /**
     * 取消下载：界面立即回到「未下载」，取消任务与清理分片文件都在后台完成，
     * 这样点按钮不会像以前那样卡住（1GB 分片删除以前是在主线程做的）。
     */
    fun cancel(context: Context, info: LocalModelInfo) {
        val app = context.applicationContext
        cancelling.value = cancelling.value + info.id
        DownloadDiagnostics.clearAll(app, info.id)
        io.execute {
            try {
                WorkManager.getInstance(app).cancelUniqueWork(uniqueWorkName(info))
                pausedMarker(app, info).delete()
                stagingDirectory(app, info).deleteRecursively()
            } finally {
                cancelling.value = cancelling.value - info.id
                refresh.value++
            }
        }
        refresh.value++
    }

    fun delete(context: Context, info: LocalModelInfo) {
        val app = context.applicationContext
        cancelling.value = cancelling.value + info.id
        installedSizeCache.remove(info.id)
        if (getActiveModelId(app) == info.id) {
            prefs(app).edit().remove(KEY_ACTIVE).apply()
        }
        DownloadDiagnostics.clearAll(app, info.id)
        io.execute {
            try {
                WorkManager.getInstance(app).cancelUniqueWork(uniqueWorkName(info))
                pausedMarker(app, info).delete()
                stagingDirectory(app, info).deleteRecursively()
                installedDirectory(app, info).deleteRecursively()
            } finally {
                cancelling.value = cancelling.value - info.id
                refresh.value++
            }
        }
        refresh.value++
    }

    // ---- observation ----

    fun observeStates(context: Context): Flow<List<ModelState>> {
        val app = context.applicationContext
        val models = ModelCatalog.all(app)
        if (models.isEmpty()) return kotlinx.coroutines.flow.flowOf(emptyList())
        val activeId = getActiveModelId(app)
        val signals = combine(refresh, cancelling) { _, _ -> Unit }
        val flows = models.map { m -> observeModel(app, m, activeId, signals) }
        return combine(flows) { arr -> arr.toList() }
    }

    private fun observeModel(
        context: Context,
        info: LocalModelInfo,
        activeId: String?,
        signals: Flow<Unit>
    ): Flow<ModelState> = combine(
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(uniqueWorkName(info)),
        signals
    ) { infos, _ -> stateFor(context, info, infos.lastOrNull(), activeId) }

    private fun stateFor(
        context: Context, info: LocalModelInfo, work: WorkInfo?, activeId: String?
    ): ModelState {
        val isActive = info.id == activeId

        fun installedState(): ModelState {
            val dir = installedDirectory(context, info)
            val size = installedSizeCache.getOrPut(info.id) { dirSize(dir) }
            return ModelState(
                info = info,
                status = ModelInstallStatus.INSTALLED,
                stage = ModelDownloadWorker.STAGE_DONE,
                progressPercent = 100,
                downloadedBytes = size,
                totalBytes = size,
                isActive = isActive,
                installedSizeBytes = size,
                installedPath = dir.absolutePath
            )
        }

        if (isInstalled(context, info)) return installedState()

        val snapshot = DownloadDiagnostics.readProgress(context, info.id)
        val persistedError = DownloadDiagnostics.readError(context, info.id)

        val downloaded = work?.progress?.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
            ?.takeIf { it > 0L } ?: snapshot?.downloadedBytes ?: 0L
        val total = info.archive.sizeBytes.takeIf { it > 0 }
            ?: work?.progress?.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)?.takeIf { it > 0L }
            ?: snapshot?.totalBytes ?: 0L
        val percent = work?.progress?.getInt(ModelDownloadWorker.KEY_PROGRESS, -1)?.takeIf { it >= 0 }
            ?: snapshot?.percent ?: -1
        val speed = snapshot?.bytesPerSec ?: 0L
        val eta = snapshot?.etaSec ?: 0L
        val stage = work?.progress?.getString(ModelDownloadWorker.KEY_STAGE)
            ?: snapshot?.stage.orEmpty()

        // WorkInfo 里还有 error 的话优先用它（比持久化的更新）
        val workFailure = work?.takeIf {
            !it.outputData.getString(ModelDownloadWorker.KEY_ERROR).isNullOrBlank()
        }
        val errorDetail = when {
            work?.state == WorkInfo.State.RUNNING -> null
            work?.state == WorkInfo.State.ENQUEUED || work?.state == WorkInfo.State.BLOCKED -> null
            workFailure != null -> DownloadError(
                time = workFailure.outputData.getLong(ModelDownloadWorker.KEY_ERROR_TIME, 0L),
                stage = workFailure.outputData.getString(ModelDownloadWorker.KEY_ERROR_STAGE).orEmpty(),
                type = workFailure.outputData.getString(ModelDownloadWorker.KEY_ERROR_TYPE).orEmpty(),
                message = workFailure.outputData.getString(ModelDownloadWorker.KEY_ERROR).orEmpty(),
                url = info.archive.url,
                downloadedBytes = downloaded
            )
            persistedError != null -> persistedError
            else -> null
        }

        // 正在取消 / 删除：立刻按未下载显示，不等后台清理完成
        if (info.id in cancelling.value) {
            return ModelState(info = info, status = ModelInstallStatus.NOT_INSTALLED, isActive = isActive)
        }

        // 暂停标记优先级最高（暂停后 WorkManager 状态可能会变成 CANCELLED）
        if (pausedMarker(context, info).exists()) {
            return ModelState(
                info = info,
                status = ModelInstallStatus.PAUSED,
                stage = ModelDownloadWorker.STAGE_PAUSED,
                progressPercent = percent,
                downloadedBytes = downloaded,
                totalBytes = total,
                isActive = isActive
            )
        }

        val status = when (work?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ModelInstallStatus.QUEUED
            WorkInfo.State.RUNNING -> when (stage) {
                ModelDownloadWorker.STAGE_VERIFYING,
                ModelDownloadWorker.STAGE_EXTRACTING,
                ModelDownloadWorker.STAGE_INSTALLING,
                ModelDownloadWorker.STAGE_LOADING -> ModelInstallStatus.VERIFYING
                else -> ModelInstallStatus.DOWNLOADING
            }
            WorkInfo.State.FAILED -> ModelInstallStatus.FAILED
            WorkInfo.State.CANCELLED -> ModelInstallStatus.NOT_INSTALLED
            else -> if (persistedError != null) ModelInstallStatus.FAILED else ModelInstallStatus.NOT_INSTALLED
        }

        return ModelState(
            info = info,
            status = status,
            stage = stage,
            progressPercent = percent,
            downloadedBytes = downloaded,
            totalBytes = total,
            bytesPerSec = if (work?.state == WorkInfo.State.RUNNING) speed else 0L,
            etaSec = if (work?.state == WorkInfo.State.RUNNING) eta else 0L,
            error = errorDetail?.summary(),
            errorDetail = errorDetail,
            isActive = isActive
        )
    }

    private fun dirSize(dir: File): Long = try {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Exception) {
        0L
    }
}
