package com.example.aphones2t.utils

import android.content.Context
import com.example.aphones2t.R
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 错误码 → 人话映射。
 *
 * 约定（这三条是本次改版的核心）：
 *  1. 每条错误都有稳定的 [UiError.code]，便于反馈时定位；
 *  2. 用户先看到「标题 + 人话」，技术细节（HTTP 码 / 异常 / 堆栈 / 日志）放进详情折叠区，
 *     不再把 `java.net.SocketTimeoutException: failed to connect...` 直接甩给用户；
 *  3. 分级 [Level]：L0 Toast、L2 页面内提示、L4 必须弹窗（可能丢录音）。
 */
object ErrorCodes {

    /** 用户可见的严重级别。 */
    enum class Level {
        /** 轻，Toast 即可。 */
        L0,
        /** 中，页面内可见提示（卡片/状态行）。 */
        L2,
        /** 重，必须弹窗（可能丢录音 / 需要用户做决定）。 */
        L4
    }

    /**
     * 一条准备好展示的错误。UI 只管按 [level] 决定用 Toast / 状态行 / 对话框，
     * 文案与按钮不再散落在各个 Activity 里。
     */
    data class UiError(
        val code: String,
        val level: Level,
        val title: String,
        val body: String = "",
        /** 是否提供「换镜像重试」（下载 403 / 超时时用）。 */
        val offerMirror: Boolean = false,
        /** 是否提供「重试」。 */
        val offerRetry: Boolean = true,
        /** 技术详情，给「详情」折叠区用；可为空。 */
        val details: String? = null
    ) {
        /** Toast 用的一行文案。 */
        fun oneLine(): String = if (body.isBlank()) title else "$title：$body"
    }

    // ---------------- 下载失败 ----------------

    /**
     * 下载失败的人话判定。
     *
     * @param httpCode Worker 记录的 HTTP 状态码（0 表示没有 HTTP 响应，比如超时/DNS）。
     * @param type 异常类名；@param message 异常消息（仅用于判定，不直接展示给用户）。
     * @param details 已拼好的技术详情（阶段/HTTP/异常/堆栈/日志），放折叠区。
     */
    fun download(
        context: Context,
        httpCode: Int = 0,
        type: String? = null,
        message: String? = null,
        details: String? = null
    ): UiError {
        val msg = message.orEmpty()
        return when {
            httpCode == 401 || httpCode == 403 -> UiError(
                code = "DL_HTTP_$httpCode",
                level = Level.L2,
                title = context.getString(R.string.err_dl_403_title),
                body = context.getString(R.string.err_dl_403_body),
                offerMirror = true,
                details = details
            )

            httpCode == 404 -> UiError(
                code = "DL_HTTP_404",
                level = Level.L2,
                title = context.getString(R.string.err_dl_404_title),
                body = context.getString(R.string.err_dl_404_body),
                offerRetry = false,
                details = details
            )

            type == SocketTimeoutException::class.java.name || msg.contains("timeout", true) -> UiError(
                code = "DL_TIMEOUT",
                level = Level.L2,
                title = context.getString(R.string.err_dl_timeout_title),
                body = context.getString(R.string.err_dl_timeout_body),
                offerMirror = true,
                details = details
            )

            type == UnknownHostException::class.java.name -> UiError(
                code = "DL_NO_DNS",
                level = Level.L2,
                title = context.getString(R.string.err_dl_network_title),
                body = context.getString(R.string.err_dl_network_body),
                offerRetry = false,
                details = details
            )

            msg.contains("ENOSPC", true) || msg.contains("No space left", true) -> UiError(
                code = "DL_NO_SPACE",
                level = Level.L4,
                title = context.getString(R.string.err_dl_space_title),
                body = context.getString(R.string.err_dl_space_body),
                offerRetry = false,
                details = details
            )

            msg.contains("SHA", true) || msg.contains("校验", true) -> UiError(
                code = "DL_CHECKSUM",
                level = Level.L2,
                title = context.getString(R.string.err_dl_checksum_title),
                body = context.getString(R.string.err_dl_checksum_body),
                details = details
            )

            else -> UiError(
                code = "DL_UNKNOWN",
                level = Level.L2,
                title = context.getString(R.string.err_dl_unknown_title),
                body = context.getString(R.string.err_dl_unknown_body),
                details = details
            )
        }
    }

    // ---------------- ASR 初始化失败（不再一句 Toast、不再直接停服务） ----------------

    /**
     * 模型加载失败：降级为「仅录音」并**说出缺了哪些文件**。
     *
     * @param missing 缺失/损坏的角色名 encoder / decoder / tokens，空则按 encoder 处理。
     */
    fun asrInit(context: Context, missing: List<String>): UiError {
        val list = missing.ifEmpty { listOf("encoder") }
            .map { missingFileLabel(context, it) }
            .joinToString("、")
        return UiError(
            code = "ASR_INIT_FAIL",
            level = Level.L4,
            title = context.getString(R.string.err_asr_init_title),
            body = context.getString(R.string.err_asr_init_body, list),
            offerRetry = false,
            details = list
        )
    }

    /**
     * 扫描模型目录，判断缺了哪些角色文件。
     * 目录不存在 → 三个都缺；文件都在但还是加载失败 → 归到 encoder（多半是权重损坏）。
     */
    fun missingRoles(modelDir: File?): List<String> {
        if (modelDir == null || !modelDir.exists()) return listOf("encoder", "decoder", "tokens")
        val names = modelDir.walkTopDown().filter { it.isFile }
            .map { it.name.lowercase() }
            .toList()
        val missing = mutableListOf<String>()
        val isParaformer = names.any { it.startsWith("decoder") }
        val isTransducer = names.any { it.startsWith("joiner") }
        if (names.none { it.startsWith("encoder") && it.endsWith(".onnx") }) missing += "encoder"
        if ((isParaformer || isTransducer) && names.none { it.startsWith("decoder") && it.endsWith(".onnx") }) {
            missing += "decoder"
        }
        if (names.none { it == "tokens.txt" }) missing += "tokens"
        if (missing.isEmpty()) missing += "encoder"
        return missing
    }

    private fun missingFileLabel(context: Context, role: String): String = when (role) {
        "decoder" -> context.getString(R.string.err_asr_file_decoder)
        "tokens" -> context.getString(R.string.err_asr_file_tokens)
        else -> context.getString(R.string.err_asr_file_encoder)
    }

    // ---------------- 麦克风 ----------------

    fun mic(context: Context, detail: String? = null): UiError = UiError(
        code = "MIC_OPEN_FAIL",
        level = Level.L4,
        title = context.getString(R.string.err_mic_title),
        body = context.getString(R.string.err_mic_body),
        details = detail
    )

    /** 权限被拒：区分「这次拒绝」和「永久拒绝」，后者引导去系统设置。 */
    fun permission(context: Context, permanent: Boolean): UiError = UiError(
        code = if (permanent) "PERM_DENIED_FOREVER" else "PERM_DENIED",
        level = if (permanent) Level.L4 else Level.L0,
        title = context.getString(R.string.permission_required_title),
        body = context.getString(if (permanent) R.string.err_perm_forever_body else R.string.err_perm_body),
        offerRetry = false
    )

    // ---------------- 导入 / 转写 / 数据库 ----------------

    fun import(context: Context, message: String?): UiError {
        val msg = message.orEmpty()
        return when {
            msg.contains("decode", true) || msg.contains("MediaCodec", true) ||
                msg.contains("无法解码", true) -> UiError(
                code = "IMP_DECODE",
                level = Level.L4,
                title = context.getString(R.string.err_import_format_title),
                body = context.getString(R.string.err_import_format_body),
                offerRetry = false,
                details = msg
            )

            msg.contains("space", true) || msg.contains("ENOSPC", true) -> UiError(
                code = "IMP_NO_SPACE",
                level = Level.L4,
                title = context.getString(R.string.err_dl_space_title),
                body = context.getString(R.string.err_dl_space_body),
                offerRetry = false,
                details = msg
            )

            else -> UiError(
                code = "IMP_UNKNOWN",
                level = Level.L4,
                title = context.getString(R.string.err_import_unknown_title),
                body = msg.ifBlank { context.getString(R.string.err_generic_body) },
                details = msg
            )
        }
    }

    /** 转写失败：OOM 单独成码，因为对应的「救法」完全不同（换小模型）。 */
    fun transcribe(context: Context, message: String?): UiError {
        val msg = message.orEmpty()
        return if (msg.contains("OutOfMemory", true) || msg.contains("OOM", true) ||
            msg.contains("Failed to allocate", true)
        ) {
            UiError(
                code = "TR_OOM",
                level = Level.L4,
                title = context.getString(R.string.err_tr_oom_title),
                body = context.getString(R.string.err_tr_oom_body),
                details = msg
            )
        } else {
            UiError(
                code = "TR_FAIL",
                level = Level.L4,
                title = context.getString(R.string.err_tr_unknown_title),
                body = msg.ifBlank { context.getString(R.string.err_generic_body) },
                offerRetry = false,
                details = msg
            )
        }
    }

    /** Room 写入失败：以前只写 Log，用户以为录音丢了。 */
    fun room(context: Context, message: String? = null): UiError = UiError(
        code = "DB_WRITE_FAIL",
        level = Level.L4,
        title = context.getString(R.string.err_room_title),
        body = context.getString(R.string.err_room_body, ""),
        details = message
    )

    /** 兜底。 */
    fun generic(context: Context, detail: String? = null): UiError = UiError(
        code = "GENERIC",
        level = Level.L0,
        title = context.getString(R.string.err_generic_title),
        body = context.getString(R.string.err_generic_body),
        details = detail
    )
}

