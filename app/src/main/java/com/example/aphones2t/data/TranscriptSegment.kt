package com.example.aphones2t.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一段带时间轴的转写文本（相对录音起点的毫秒区间）。
 * 列表里点击某一句即可跳到录音的对应位置。
 */
data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/** [TranscriptSegment] 列表 <-> JSON 字符串（存 Room 的 segments 列）。 */
object TranscriptSegments {

    fun encode(list: List<TranscriptSegment>): String? {
        if (list.isEmpty()) return null
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("s", s.startMs)
                    put("e", s.endMs)
                    put("t", s.text)
                }
            )
        }
        return arr.toString()
    }

    fun decode(json: String?): List<TranscriptSegment> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("t").trim()
                if (text.isEmpty()) return@mapNotNull null
                TranscriptSegment(
                    startMs = o.optLong("s", 0L),
                    endMs = o.optLong("e", 0L),
                    text = text
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 纯文本（丢掉时间戳），用于复制 / 分享 / 兜底显示。 */
    fun plainText(json: String?): String = decode(json).joinToString("\n") { it.text }
}
