package com.example.aphones2t

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.aphones2t.data.AppDatabase
import com.example.aphones2t.data.TranscriptRepository
import com.example.aphones2t.databinding.ActivityMainBinding
import com.example.aphones2t.model.ModelCatalog
import com.example.aphones2t.model.ModelManager
import com.example.aphones2t.utils.AudioFileDecoder
import com.example.aphones2t.utils.FileTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var recording = false
    private var paused = false
    private var processing = false
    private val repo by lazy {
        TranscriptRepository(AppDatabase.get(this).transcriptDao())
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { importAudio(it) } }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) startRecording() else
            Toast.makeText(this, "需要麦克风/通知权限", Toast.LENGTH_SHORT).show()
    }

    private val partialReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            binding.tvTranscript.text = i.getStringExtra(TranscriptionService.EXTRA_TEXT) ?: ""
        }
    }
    private val finalReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val text = i.getStringExtra(TranscriptionService.EXTRA_TEXT) ?: ""
            val wav = i.getStringExtra(TranscriptionService.EXTRA_WAV)
            val dur = i.getLongExtra(TranscriptionService.EXTRA_DURATION, 0L)
            Log.d("MainActivity", "FINAL received text=$text wav=$wav dur=$dur")
            binding.tvTranscript.text = text
            updateUI()
            // Recordings made without a model still land in history as pending.
            if (text.isNotBlank() || !wav.isNullOrBlank()) saveToHistory(text, wav, dur)
        }
    }
    private val errorReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Toast.makeText(c, i.getStringExtra(TranscriptionService.EXTRA_TEXT) ?: "错误",
                Toast.LENGTH_LONG).show()
            updateUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_models -> { startActivity(Intent(this, ModelManagerActivity::class.java)); true }
                R.id.action_history -> { startActivity(Intent(this, HistoryActivity::class.java)); true }
                R.id.action_import -> { if (!processing) importLauncher.launch("audio/*"); true }
                R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                else -> false
            }
        }

        binding.btnRecord.setOnClickListener {
            if (recording) stopRecording() else checkPermissionsAndRecord()
        }
        binding.btnPause.setOnClickListener {
            if (!recording) return@setOnClickListener
            if (paused) {
                startService(Intent(this, TranscriptionService::class.java)
                    .setAction(TranscriptionService.ACTION_RESUME))
                paused = false
            } else {
                startService(Intent(this, TranscriptionService::class.java)
                    .setAction(TranscriptionService.ACTION_PAUSE))
                paused = true
            }
            updateUI()
        }
        updateUI()
    }

    override fun onStart() {
        super.onStart()
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_NOT_EXPORTED else 0
        registerReceiver(partialReceiver, IntentFilter(TranscriptionService.ACTION_PARTIAL), flags)
        registerReceiver(finalReceiver, IntentFilter(TranscriptionService.ACTION_FINAL), flags)
        registerReceiver(errorReceiver, IntentFilter(TranscriptionService.ACTION_ERROR), flags)
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(partialReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(finalReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(errorReceiver) } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        refreshModelStatus()
    }

    private fun refreshModelStatus() {
        val ready = ModelManager.getActiveModelDirectory(this) != null
        if (!ready) {
            binding.tvTranscript.hint = getString(R.string.hint_no_model)
        } else if (!recording) {
            binding.tvTranscript.hint = getString(R.string.hint_ready)
        }
        updateUI()
    }

    private fun checkPermissionsAndRecord() {
        val needed = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startRecording() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startRecording() {
        startForegroundService(Intent(this, TranscriptionService::class.java)
            .setAction(TranscriptionService.ACTION_START))
        recording = true; paused = false
        updateUI()
    }

    private fun stopRecording() {
        startService(Intent(this, TranscriptionService::class.java)
            .setAction(TranscriptionService.ACTION_STOP))
        recording = false; paused = false
        updateUI()
    }

    private fun updateUI() {
        binding.btnRecord.isEnabled = !processing
        if (recording) {
            binding.btnRecord.text = getString(R.string.stop_recording)
            binding.btnRecord.setBackgroundColor(getColor(R.color.rec))
            binding.btnPause.visibility = android.view.View.VISIBLE
            binding.btnPause.text = if (paused) getString(R.string.resume_recording) else getString(R.string.pause_recording)
            binding.tvStatus.text = if (paused) getString(R.string.paused_status) else getString(R.string.recording_status)
        } else {
            binding.btnRecord.text = getString(R.string.start_recording)
            binding.btnRecord.setBackgroundColor(getColor(R.color.seed))
            binding.btnPause.visibility = android.view.View.GONE
            binding.tvStatus.text = getString(R.string.idle_status)
        }
    }

    private fun saveToHistory(text: String, wav: String?, durationMs: Long) {
        val id = ModelManager.getActiveModelId(this)
        val modelName = id?.let { ModelCatalog.findById(this, it)?.name }
            ?: getString(R.string.pending_transcribe)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rowId = repo.insert(text, wav, durationMs, modelName)
                Log.d("MainActivity", "inserted rowId=$rowId text='$text'")
            } catch (e: Exception) {
                Log.e("MainActivity", "insert failed", e)
            }
        }
    }

    /** Copies a picked audio file into app storage and transcribes it if a model is ready. */
    private fun importAudio(uri: Uri) {
        if (processing) return
        processing = true
        binding.tvStatus.text = getString(R.string.importing_status)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val originalName = queryDisplayName(uri) ?: "import_$ts"
                val ext = originalName.substringAfterLast('.', "").ifBlank { "m4a" }
                val dir = File(filesDir, "recordings").apply { if (!exists()) mkdirs() }
                val dest = File(dir, "import_$ts.$ext")
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("无法读取所选文件")

                val modelDir = ModelManager.getActiveModelDirectory(this@MainActivity)
                var text = ""
                var durationMs = 0L
                var modelName = getString(R.string.pending_transcribe)
                if (modelDir != null) {
                    val r = FileTranscriber.transcribe(this@MainActivity, modelDir, dest.absolutePath)
                    if (r != null) {
                        text = r.text
                        durationMs = r.durationMs
                        modelName = ModelManager.getActiveModelId(this@MainActivity)
                            ?.let { ModelCatalog.findById(this@MainActivity, it)?.name } ?: "sherpa-onnx"
                    }
                } else {
                    // No model: still record duration so the pending entry is informative.
                    durationMs = AudioFileDecoder
                        .decodeToPcm16kMono(this@MainActivity, dest.absolutePath)
                        ?.size?.div(16)?.toLong() ?: 0L
                }
                repo.insert(text, dest.absolutePath, durationMs, modelName)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        if (text.isNotBlank()) getString(R.string.import_success)
                        else getString(R.string.import_pending),
                        Toast.LENGTH_LONG
                    ).show()
                    processing = false
                    updateUI()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.import_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                    processing = false
                    updateUI()
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }
}
