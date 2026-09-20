package com.example.aphones2t.utils

import android.content.Context
import com.example.aphones2t.asr.SherpaStreamingAsr
import com.example.aphones2t.data.TranscriptSegment
import com.example.aphones2t.data.TranscriptSegments
import java.io.File

/**
 * 离线转写一个音频文件（走同一个 sherpa-onnx 流式识别器）。
 *
 * 失败时**必须带回原因**：以前统一返回 null，UI 只能弹「转写失败」，用户完全不知道
 * 是音频坏了、模型没装好，还是内存不够。现在返回 [Failure.message]，交给
 * [ErrorCodes.transcribe] 翻译成人话（OOM 单独成码）。
 */
object FileTranscriber {

    sealed class Result {
        data class Success(
            val text: String,
            val durationMs: Long,
            /** 分段时间轴 JSON（可直接写库），无分段时为 null。 */
            val segmentsJson: String? = null
        ) : Result()

        /** @param message 技术原因（异常消息）；UI 用它判定错误类型，不直接展示。 */
        data class Failure(val message: String) : Result()
    }

    fun transcribe(context: Context, modelDir: File, path: String): Result {
        val pcm = try {
            AudioFileDecoder.decodeToPcm16kMono(context, path)
        } catch (e: Exception) {
            return Result.Failure("decode failed: ${e.message ?: e.javaClass.simpleName}")
        } ?: return Result.Failure("decode failed: 无法解码该音频（格式不支持或文件损坏）")

        val durationMs = (pcm.size / 16.0).toLong()
        if (pcm.isEmpty()) return Result.Success("", durationMs, null)

        val asr = SherpaStreamingAsr()
        if (!asr.init(modelDir)) {
            return Result.Failure("ASR init failed: 模型加载失败（${modelDir.name}）")
        }
        return try {
            val chunk = FloatArray(1600)
            var i = 0
            while (i < pcm.size) {
                val n = minOf(1600, pcm.size - i)
                pcm.copyInto(chunk, 0, i, i + n)
                asr.accept(chunk.copyOfRange(0, n))
                i += n
            }
            val text = asr.finalText()
            Result.Success(text, durationMs, encodeSegments(asr.segments(), durationMs))
        } catch (e: OutOfMemoryError) {
            // 长音频 + 大模型最容易在这里炸；单独立一个码，UI 才能给出「换小模型」的建议
            Result.Failure("OutOfMemory: ${e.message ?: "transcribe OOM"}")
        } catch (e: Exception) {
            Result.Failure("${e.javaClass.simpleName}: ${e.message ?: ""}")
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

