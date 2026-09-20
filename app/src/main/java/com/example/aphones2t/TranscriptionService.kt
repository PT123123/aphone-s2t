package com.example.aphones2t

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.aphones2t.asr.SherpaStreamingAsr
import com.example.aphones2t.data.TranscriptSegment
import com.example.aphones2t.data.TranscriptSegments
import com.example.aphones2t.model.ModelManager
import com.example.aphones2t.utils.ErrorCodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that captures microphone audio and runs the streaming
 * sherpa-onnx Paraformer recognizer in real time. Partial results are broadcast
 * continuously; the final transcript (+ WAV + 分段时间轴) is broadcast when
 * recording stops.
 */
class TranscriptionService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"

        const val ACTION_PARTIAL = "com.example.aphones2t.PARTIAL"
        const val ACTION_FINAL = "com.example.aphones2t.FINAL"
        const val ACTION_ERROR = "com.example.aphones2t.ERROR"
        const val EXTRA_TEXT = "text"
        const val EXTRA_WAV = "wav"
        const val EXTRA_DURATION = "duration"

        /** 错误码（见 utils/ErrorCodes.kt），UI 按码决定弹窗还是 Toast。 */
        const val EXTRA_ERROR_CODE = "error_code"
        const val ERR_ASR_INIT = "ERR_ASR_INIT"
        const val ERR_MIC = "ERR_MIC"
        const val ERR_RECORD = "ERR_RECORD"

        /** 分段时间轴 JSON（[{s,e,t}]），用于「点文字跳到对应录音位置」。 */
        const val EXTRA_SEGMENTS = "segments"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "transcription_channel"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 1600 // 100 ms
        private const val TAG = "TranscriptionService"
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var audioRecord: AudioRecord? = null
    private var asr: SherpaStreamingAsr? = null
    private var recording = false
    private var paused = false
    private var hasError = false
    private var wakeLock: PowerManager.WakeLock? = null

    /** True when recording in record-only mode (no ASR model installed yet). */
    private var noModel = false

    private var outputWav: File? = null
    private var outputTxt: File? = null
    private var startedAt = 0L
    private val pcm = ByteArrayOutputStream()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(
            when {
                noModel && paused -> getString(R.string.notification_paused_no_model)
                paused -> getString(R.string.notification_paused)
                noModel -> getString(R.string.notification_no_model)
                else -> getString(R.string.notification_content)
            }
        )
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .setOngoing(true)
        .build()

    private fun broadcast(action: String, text: String = "") {
        sendBroadcast(
            Intent(action)
                .setPackage(packageName)
                .putExtra(EXTRA_TEXT, text)
        )
    }

    /** 带错误码的广播：UI 据此选文案与展示方式（弹窗 / 状态行）。 */
    private fun broadcastError(code: String, detail: String = "") {
        sendBroadcast(
            Intent(ACTION_ERROR)
                .setPackage(packageName)
                .putExtra(EXTRA_ERROR_CODE, code)
                .putExtra(EXTRA_TEXT, detail)
        )
    }

    private fun startRecording() {
        if (recording) return
        hasError = false
        paused = false
        pcm.reset()

        val modelDir = ModelManager.getActiveModelDirectory(this)
        // Record-only mode when no usable model: audio is still captured & saved,
        // transcription is deferred until a model is installed.
        if (modelDir != null && SherpaStreamingAsr.isModelValid(modelDir)) {
            noModel = false
            asr = SherpaStreamingAsr()
            if (!asr!!.init(modelDir)) {
                // 模型加载失败**不再**停服务、不再只弹一句 Toast：
                // 降级为「仅录音」继续把音频录下来（不丢录音），
                // 并把「缺了哪些文件」广播给 UI，让用户知道去哪儿修。
                asr?.release()
                asr = null
                noModel = true
                val missing = ErrorCodes.missingRoles(ModelManager.getInstalledModelDirectory(this))
                Log.w(TAG, "ASR init failed, fall back to record-only. missing=$missing")
                broadcastError(ERR_ASR_INIT, missing.joinToString(","))
            }
        } else {
            noModel = true
            asr = null
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            broadcastError(ERR_MIC, getString(R.string.record_no_mic))
            cleanup(); stopSelf(); return
        }
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            (minBuf * 2).coerceAtLeast(CHUNK_SAMPLES * 2)
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            broadcastError(ERR_MIC, getString(R.string.record_no_mic))
            cleanup(); stopSelf(); return
        }

        val dir = File(filesDir, "recordings").apply { if (!exists()) mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        outputWav = File(dir, "record_$ts.wav")
        outputTxt = File(dir, "record_$ts.txt")

        startForeground(NOTIFICATION_ID, notification())
        acquireWakeLock()
        audioRecord!!.startRecording()
        recording = true
        startedAt = System.currentTimeMillis()

        scope.launch { recordLoop() }
        Log.i(TAG, "recording started")
    }

    private suspend fun recordLoop() {
        val shortBuf = ShortArray(CHUNK_SAMPLES)
        val floatBuf = FloatArray(CHUNK_SAMPLES)
        try {
            while (recording && !hasError) {
                if (paused) {
                    delay(150)
                    continue
                }
                val n = audioRecord?.read(shortBuf, 0, CHUNK_SAMPLES) ?: -1
                if (n > 0) {
                    for (i in 0 until n) floatBuf[i] = shortBuf[i] / 32768.0f
                    // persist raw PCM
                    for (i in 0 until n) {
                        val v = shortBuf[i].toInt()
                        pcm.write(v and 0xFF); pcm.write((v shr 8) and 0xFF)
                    }
                    val text = asr?.accept(floatBuf.copyOfRange(0, n)) ?: ""
                    if (text.isNotEmpty()) broadcast(ACTION_PARTIAL, text)
                } else if (n < 0) {
                    delay(50)
                } else {
                    delay(10)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "recordLoop error", e)
            hasError = true
            // 录音循环崩了：告诉用户「音频还在」，而不是静默失败
            broadcastError(ERR_RECORD, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun pauseRecording() {
        if (!recording || paused) return
        paused = true
        try { audioRecord?.stop() } catch (_: Exception) {}
        refreshNotification()
    }

    private fun resumeRecording() {
        if (!recording || !paused) return
        try {
            audioRecord?.startRecording()
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) paused = false
        } catch (_: Exception) {}
        refreshNotification()
    }

    /** 状态切换后刷新常驻通知文案（暂停 / 继续）。 */
    private fun refreshNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, notification())
        } catch (_: Exception) {}
    }

    private fun stopRecording() {
        Log.d(TAG, "stopRecording called recording=$recording hasError=$hasError")
        if (!recording && !hasError) return
        recording = false
        paused = false
        scope.launch {
            delay(300) // let the recognizer consume the tail
            val finalText = asr?.finalText() ?: ""
            val rawSegments = asr?.segments().orEmpty()
            cleanupAudio()
            val durationMs = System.currentTimeMillis() - startedAt
            val pcmBytes = pcm.toByteArray()
            if (pcmBytes.size > SAMPLE_RATE * 2) { // at least 1s of audio
                val segmentsJson = TranscriptSegments.encode(
                    rawSegments.map {
                        TranscriptSegment(
                            startMs = it.startMs,
                            endMs = it.endMs.coerceAtMost(durationMs),
                            text = it.text
                        )
                    }
                )
                saveWav(pcmBytes)
                saveTranscript(finalText, durationMs, segmentsJson)
                Log.d(TAG, "broadcasting FINAL wav=${outputWav?.absolutePath} text='$finalText'")
                val i = Intent(ACTION_FINAL)
                    .setPackage(packageName)
                    .putExtra(EXTRA_TEXT, finalText)
                    .putExtra(EXTRA_WAV, outputWav?.absolutePath ?: "")
                    .putExtra(EXTRA_DURATION, durationMs)
                    .putExtra(EXTRA_SEGMENTS, segmentsJson ?: "")
                sendBroadcast(i)
            } else {
                outputWav?.delete(); outputTxt?.delete()
            }
            asr?.release(); asr = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cleanupAudio() {
        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        audioRecord = null
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
    }

    private fun cleanup() {
        cleanupAudio()
        asr?.release(); asr = null
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AphoneS2T::rec").apply {
                acquire(30 * 60 * 1000L)
            }
        } catch (_: Exception) {}
    }

    private fun saveWav(pcmBytes: ByteArray) {
        val f = outputWav ?: return
        try {
            RandomAccessFile(f, "rw").use { raf ->
                val total = 36 + pcmBytes.size
                raf.write(byteArrayOf(82, 73, 70, 70))
                raf.writeInt(Integer.reverseBytes(total))
                raf.write(byteArrayOf(87, 65, 86, 69))
                raf.write(byteArrayOf(102, 109, 116, 32))
                raf.writeInt(Integer.reverseBytes(16))
                raf.write(byteArrayOf(1, 0)); raf.write(byteArrayOf(1, 0))
                raf.writeInt(Integer.reverseBytes(SAMPLE_RATE))
                raf.writeInt(Integer.reverseBytes(SAMPLE_RATE * 2))
                raf.write(byteArrayOf(2, 0)); raf.write(byteArrayOf(16, 0))
                raf.write(byteArrayOf(100, 97, 116, 97))
                raf.writeInt(Integer.reverseBytes(pcmBytes.size))
                raf.write(pcmBytes)
            }
        } catch (_: Exception) {}
    }

    private fun saveTranscript(text: String, durationMs: Long, segmentsJson: String?) {
        val f = outputTxt ?: return
        val body = when {
            text.isNotBlank() -> text
            noModel -> getString(R.string.txt_no_model)
            else -> getString(R.string.txt_no_speech)
        }
        val duration = SimpleDateFormat("mm:ss", Locale.getDefault()).format(Date(durationMs))
        val timeline = TranscriptSegments.decode(segmentsJson).joinToString("\n") { seg ->
            "[${formatClock(seg.startMs)}] ${seg.text}"
        }
        try {
            f.writeText(
                buildString {
                    append(getString(R.string.txt_header_title, outputWav?.nameWithoutExtension ?: ""))
                    append(getString(R.string.txt_header_duration, duration))
                    append(getString(R.string.txt_header_engine))
                    append("---\n\n")
                    if (timeline.isNotBlank()) {
                        append(timeline)
                    } else {
                        append(body)
                    }
                }
            )
        } catch (_: Exception) {}
    }

    private fun formatClock(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.getDefault(), "%02d:%02d", totalSec / 60, totalSec % 60)
    }

    override fun onDestroy() {
        super.onDestroy()
        recording = false
        hasError = true
        scope.coroutineContext[Job]?.cancel()
        cleanup()
    }
}
