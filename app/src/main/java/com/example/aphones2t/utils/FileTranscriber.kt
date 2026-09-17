package com.example.aphones2t.utils

import android.content.Context
import com.example.aphones2t.asr.SherpaStreamingAsr
import com.example.aphones2t.data.TranscriptSegment
import com.example.aphones2t.data.TranscriptSegments
import java.io.File

/**
 * Offline transcription of an audio file through the streaming recognizer:
 * decodes the file to 16 kHz mono PCM and feeds it chunk by chunk, then returns
 * the accumulated final text. Used both for freshly imported files and for
 * re-transcribing previously pending recordings.
 */
object FileTranscriber {

    data class Result(
        val text: String,
        val durationMs: Long,
        /** 分段时间轴 JSON（可直接写库），无分段时为 null。 */
        val segmentsJson: String? = null
    )

    /**
     * Returns null when decode or ASR init fails; otherwise a [Result] (text may
     * be blank when no speech is detected).
     */
    fun transcribe(context: Context, modelDir: File, path: String): Result? {
        val pcm = AudioFileDecoder.decodeToPcm16kMono(context, path) ?: return null
        val durationMs = (pcm.size / 16.0).toLong()
        if (pcm.isEmpty()) return Result("", durationMs, null)

        val asr = SherpaStreamingAsr()
        if (!asr.init(modelDir)) return null
        try {
            val chunk = FloatArray(1600)
            var i = 0
            while (i < pcm.size) {
                val n = minOf(1600, pcm.size - i)
                pcm.copyInto(chunk, 0, i, i + n)
                asr.accept(chunk.copyOfRange(0, n))
                i += n
            }
            val text = asr.finalText()
            return Result(text, durationMs, encodeSegments(asr.segments(), durationMs))
        } finally {
            asr.release()
        }
    }

    /** 段落转成入库 JSON，并把超出音频长度的尾巴裁掉。 */
    private fun encodeSegments(
        segments: List<SherpaStreamingAsr.Segment>,
        durationMs: Long
    ): String? = TranscriptSegments.encode(
        segments.map {
            TranscriptSegment(
                startMs = it.startMs.coerceAtLeast(0L),
                endMs = if (durationMs > 0L) it.endMs.coerceAtMost(durationMs) else it.endMs,
                text = it.text
            )
        }
    )
}
