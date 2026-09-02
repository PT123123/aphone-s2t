package com.example.aphones2t

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.aphones2t.data.AppDatabase
import com.example.aphones2t.data.TranscriptRepository
import com.example.aphones2t.databinding.ActivityMainBinding
import com.example.aphones2t.model.ModelCatalog
import com.example.aphones2t.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var recording = false
    private var paused = false
    private val repo by lazy {
        TranscriptRepository(AppDatabase.get(this).transcriptDao())
    }

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
            binding.tvTranscript.text = text
            updateUI()
            if (text.isNotBlank()) saveToHistory(text, wav, dur)
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
            binding.tvTranscript.hint = getString(R.string.hint_wait_model)
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
        if (needed.isEmpty()) {
            if (ModelManager.getActiveModelDirectory(this) == null) {
                Toast.makeText(this, getString(R.string.model_not_ready), Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, ModelManagerActivity::class.java))
                return
            }
            startRecording()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
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
        val ready = ModelManager.getActiveModelDirectory(this) != null
        binding.btnRecord.isEnabled = ready
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
        val modelName = id?.let { ModelCatalog.findById(this, it)?.name } ?: "sherpa-onnx"
        CoroutineScope(Dispatchers.IO).launch {
            repo.insert(text, wav, durationMs, modelName)
        }
    }
}
