package com.example.aphones2t.model

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一次下载失败的完整现场。之前失败只往 WorkInfo.outputData 里塞一句话，卡片又被
 * maxLines=2 截断，所以用户只看到「失败」看不到原因。这里把阶段、HTTP 码、异常类型、
 * 异常消息、底层 cause、堆栈摘要、已下载字节数全部持久化下来，WorkInfo 被清理后依然能看。
 */
data class DownloadError(
    val time: Long = System.currentTimeMillis(),
    val stage: String = "",
    val type: String = "",
    val message: String = "",
    val cause: String? = null,
    val httpCode: Int = 0,
    val url: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val stack: String? = null
) {
    /** 卡片上显示的一行摘要。 */
    fun summary(): String = message.ifBlank { type.ifBlank { "未知错误" } }
}

/** 下载进度快照：页面重建后（WorkInfo 还没重新发射时）也能继续显示真实进度。 */
data class ProgressSnapshot(
    val stage: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val percent: Int = 0,
    val bytesPerSec: Long = 0L,
    val etaSec: Long = 0L,
    val time: Long = System.currentTimeMillis()
)

/**
 * 模型下载的诊断信息仓库（SharedPreferences）。
 * key 规则：err_<id> / prog_<id> / log_<id>，全部以模型 id 区分。
 */
object DownloadDiagnostics {

    private const val PREFS = "aphones2t_download_diag"
    private const val KEY_ERR = "err_"
    private const val KEY_LOG = "log_"
    private const val KEY_PROG = "prog_"
    private const val MAX_LOG_LINES = 30

    private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        cached ?: context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { cached = it }

    // ---------- 事件日志（谁都能调，worker / UI 都往这里写） ----------

    fun log(context: Context, id: String, message: String) {
        val p = prefs(context)
        val lines = (p.getString(KEY_LOG + id, "") ?: "")
            .lines().filter { it.isNotBlank() }.toMutableList()
        lines.add("${stamp(System.currentTimeMillis())}  $message")
        while (lines.size > MAX_LOG_LINES) lines.removeAt(0)
        p.edit().putString(KEY_LOG + id, lines.joinToString("\n")).apply()
    }

    fun readLog(context: Context, id: String): List<String> =
        (prefs(context).getString(KEY_LOG + id, "") ?: "").lines().filter { it.isNotBlank() }

    fun clearLog(context: Context, id: String) {
        prefs(context).edit().remove(KEY_LOG + id).apply()
    }

    // ---------- 失败详情 ----------

    fun saveError(context: Context, id: String, e: DownloadError) {
        prefs(context).edit().putString(KEY_ERR + id, errorToJson(e).toString()).apply()
    }

    fun readError(context: Context, id: String): DownloadError? {
        val raw = prefs(context).getString(KEY_ERR + id, null) ?: return null
        return try {
            val j = JSONObject(raw)
            DownloadError(
                time = j.optLong("time", 0L),
                stage = j.optString("stage"),
                type = j.optString("type"),
                message = j.optString("message"),
                cause = j.optString("cause").takeIf { it.isNotBlank() },
                httpCode = j.optInt("http", 0),
                url = j.optString("url"),
                downloadedBytes = j.optLong("downloaded", 0L),
                totalBytes = j.optLong("total", 0L),
                stack = j.optString("stack").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clearError(context: Context, id: String) {
        prefs(context).edit().remove(KEY_ERR + id).apply()
    }

    // ---------- 进度快照 ----------

    fun saveProgress(context: Context, id: String, s: ProgressSnapshot) {
        val j = JSONObject().apply {
            put("stage", s.stage)
            put("downloaded", s.downloadedBytes)
            put("total", s.totalBytes)
            put("percent", s.percent)
            put("speed", s.bytesPerSec)
            put("eta", s.etaSec)
            put("time", s.time)
        }
        prefs(context).edit().putString(KEY_PROG + id, j.toString()).apply()
    }

    fun readProgress(context: Context, id: String): ProgressSnapshot? {
        val raw = prefs(context).getString(KEY_PROG + id, null) ?: return null
        return try {
            val j = JSONObject(raw)
            ProgressSnapshot(
                stage = j.optString("stage"),
                downloadedBytes = j.optLong("downloaded", 0L),
                totalBytes = j.optLong("total", 0L),
                percent = j.optInt("percent", 0),
                bytesPerSec = j.optLong("speed", 0L),
                etaSec = j.optLong("eta", 0L),
                time = j.optLong("time", 0L)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clearProgress(context: Context, id: String) {
        prefs(context).edit().remove(KEY_PROG + id).apply()
    }

    /** 清空某个模型的全部诊断信息（重新下载 / 删除模型时调用）。 */
    fun clearAll(context: Context, id: String) {
        prefs(context).edit()
            .remove(KEY_ERR + id)
            .remove(KEY_PROG + id)
            .remove(KEY_LOG + id)
            .apply()
    }

    fun stamp(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

    private fun errorToJson(e: DownloadError) = JSONObject().apply {
        put("time", e.time)
        put("stage", e.stage)
        put("type", e.type)
        put("message", e.message)
        put("cause", e.cause ?: "")
        put("http", e.httpCode)
        put("url", e.url)
        put("downloaded", e.downloadedBytes)
        put("total", e.totalBytes)
        put("stack", e.stack ?: "")
    }
}
