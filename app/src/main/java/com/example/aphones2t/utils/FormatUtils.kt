package com.example.aphones2t.utils

import android.content.Context
import android.text.format.Formatter
import java.util.Locale

/** 体积 / 速度 / 时长 的统一格式化，供模型卡片与录音列表共用。 */
object FormatUtils {

    /** 文件大小；<= 0 时返回占位符。 */
    fun size(context: Context, bytes: Long, placeholder: String = "—"): String =
        if (bytes <= 0L) placeholder else Formatter.formatFileSize(context, bytes)

    /** 下载速度 xx MB/s。 */
    fun speed(context: Context, bytesPerSec: Long): String =
        if (bytesPerSec <= 0L) "—" else Formatter.formatFileSize(context, bytesPerSec) + "/s"

    /** mm:ss（超过 1 小时给 h:mm:ss），用于时间轴与进度。 */
    fun clock(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        else String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }

    /** 剩余时间：12秒 / 1分20秒 / 1小时3分。 */
    fun eta(seconds: Long): String = when {
        seconds <= 0L -> "—"
        seconds < 60L -> "${seconds}秒"
        seconds < 3600L -> {
            val m = seconds / 60
            val s = seconds % 60
            if (s == 0L) "${m}分" else "${m}分${s}秒"
        }
        else -> {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            if (m == 0L) "${h}小时" else "${h}小时${m}分"
        }
    }
}
