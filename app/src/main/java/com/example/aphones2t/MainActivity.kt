package com.example.aphones2t

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.aphones2t.data.AppDatabase
import com.example.aphones2t.data.TranscriptRepository
import com.example.aphones2t.databinding.ActivityMainBinding
import com.example.aphones2t.dialog.AddCustomModelDialog
import com.example.aphones2t.model.LocalModelInfo
import com.example.aphones2t.model.ModelCatalog
import com.example.aphones2t.model.ModelInstallStatus
import com.example.aphones2t.model.ModelManager
import com.example.aphones2t.model.ModelState
import com.example.aphones2t.utils.AudioFileDecoder
import com.example.aphones2t.utils.FileTranscriber
import com.example.aphones2t.utils.FormatUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面 —— 三个页面：实时转写 / 录音 / 模型。
 *
 * 之前「实时转写」和录音列表挤在同一个页面里，而且录音还分成本次录音与历史记录
 * 两套（点「管理录音」看不到录音列表）。现在：
 *  - 实时转写页只负责：当前模型卡片 + 转写文本 + 录音控制；
 *  - 录音页是唯一的录音列表（实时录音 / 导入 / 离线转写都在这里）；
 *  - 模型页是完整的模型管理（筛选 / 下载 / 详情 / 失败原因）。
 */
class MainActivity : AppCompatActivity(), AddCustomModelDialog.OnModelAddedListener {

    private lateinit var binding: ActivityMainBinding
    private var recording = false
    private var paused = false
    private var processing = false

    private val repo by lazy {
        TranscriptRepository(AppDatabase.get(this).transcriptDao())
    }

    private lateinit var recordingsController: RecordingsController
    private lateinit var modelListController: ModelListController

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
            val segments = i.getStringExtra(TranscriptionService.EXTRA_SEGMENTS)
            Log.d("MainActivity", "FINAL received text=$text wav=$wav dur=$dur")
            binding.tvTranscript.text = text
            updateUI()
            if (text.isNotBlank() || !wav.isNullOrBlank()) saveRecording(text, wav, dur, segments)
        }
    }

    private val errorReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Toast.makeText(
                c,
                i.getStringExtra(TranscriptionService.EXTRA_TEXT) ?: "错误",
                Toast.LENGTH_LONG
            ).show()
            updateUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_import -> {
                    if (!processing) importLauncher.launch("audio/*"); true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java)); true
                }
                else -> false
            }
        }

        setupTabs()
        setupRealtimePage()
        setupRecordingsPage()
        setupModelsPage()

        updateUI()
        if (savedInstanceState == null) {
            switchToTab(intent.getIntExtra(EXTRA_TAB, TAB_REALTIME))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        switchToTab(intent.getIntExtra(EXTRA_TAB, TAB_REALTIME))
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

    override fun onDestroy() {
        super.onDestroy()
        recordingsController.pausePlayback()
    }

    // ================= 页面骨架 =================

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                binding.llRealtime.isVisible = tab.position == TAB_REALTIME
                binding.llRecordings.isVisible = tab.position == TAB_RECORDINGS
                binding.llModels.isVisible = tab.position == TAB_MODELS
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun switchToTab(index: Int) {
        val clamped = index.coerceIn(TAB_REALTIME, TAB_MODELS)
        binding.tabLayout.getTabAt(clamped)?.select()
    }

    // ================= ① 实时转写 =================

    private fun setupRealtimePage() {
        binding.btnRecord.setOnClickListener {
            if (recording) stopRecording() else checkPermissionsAndRecord()
        }
        binding.btnPause.setOnClickListener {
            if (!recording) return@setOnClickListener
            if (paused) {
                startService(
                    Intent(this, TranscriptionService::class.java)
                        .setAction(TranscriptionService.ACTION_RESUME)
                )
                paused = false
            } else {
                startService(
                    Intent(this, TranscriptionService::class.java)
                        .setAction(TranscriptionService.ACTION_PAUSE)
                )
                paused = true
            }
            updateUI()
        }
        binding.btnCopyTranscript.setOnClickListener {
            copyText(binding.tvTranscript.text?.toString().orEmpty())
        }
        binding.btnSwitchModel.setOnClickListener { showModelSwitcher() }
        binding.btnManageModel.setOnClickListener { switchToTab(TAB_MODELS) }

        // 模型状态变化 → 顶部卡片实时刷新（下载完成会自动变成当前模型）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ModelManager.observeStates(this@MainActivity).collectLatest { states ->
                    renderCurrentModel(states)
                }
            }
        }
    }

    /** 显示「当前用的是什么模型」——以前实时转写页完全没有这个信息。 */
    private fun renderCurrentModel(states: List<ModelState>) {
        val activeId = ModelManager.getActiveModelId(this)
        val installed = states.filter { it.isInstalled }
        // 直接从状态里挑，不要调 getActiveModel()：那条路会真的去加载 ONNX 模型，
        // 而这里每次进度回调（约 500ms 一次）都会执行。
        val model: LocalModelInfo? =
            installed.firstOrNull { it.info.id == activeId }?.info ?: installed.firstOrNull()?.info

        if (model == null) {
            binding.tvCurrentModelName.text = getString(R.string.current_model_none)
            val downloading = states.firstOrNull { it.status == ModelInstallStatus.DOWNLOADING }
            binding.tvCurrentModelMeta.text = if (downloading != null) {
                getString(
                    R.string.model_downloading_meta,
                    downloading.info.name,
                    downloading.progressPercent.coerceAtLeast(0)
                )
            } else {
                getString(R.string.current_model_none_hint)
            }
            binding.btnSwitchModel.isEnabled = false
            binding.tvTranscript.hint = getString(R.string.hint_no_model)
            return
        }

        binding.tvCurrentModelName.text = model.name
        binding.tvCurrentModelMeta.text = buildString {
            append(ModelListController.languageLabel(model.language))
            if (model.downloadSizeBytes > 0) {
                append(" · ").append(FormatUtils.size(this@MainActivity, model.downloadSizeBytes))
            }
            append(" · ").append(getString(R.string.model_status_installed))
            if (model.id != activeId) {
                append("（").append(getString(R.string.model_not_active)).append("）")
            }
        }
        binding.btnSwitchModel.isEnabled = installed.isNotEmpty()
        if (!recording) binding.tvTranscript.hint = getString(R.string.hint_ready)
    }

    private fun showModelSwitcher() {
        val installed = ModelManager.installedModels(this)
        if (installed.isEmpty()) {
            Toast.makeText(this, R.string.model_switch_no_installed, Toast.LENGTH_SHORT).show()
            switchToTab(TAB_MODELS)
            return
        }
        val activeId = ModelManager.getActiveModelId(this)
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.model_switch_title)
            textSize = 17f
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, 0, 0, dp(4))
        })
        installed.forEach { info ->
            val isActive = info.id == activeId
            content.addView(TextView(this).apply {
                text = buildString {
                    if (isActive) append("✓ ")
                    append(info.name)
                    append('\n')
                    append(ModelListController.languageLabel(info.language))
                    append(" · ").append(FormatUtils.size(this@MainActivity, info.downloadSizeBytes))
                }
                textSize = 15f
                setPadding(0, dp(12), 0, dp(12))
                setTextColor(getColor(if (isActive) R.color.seed else R.color.text_primary))
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    ModelManager.setActiveModel(this@MainActivity, info.id)
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.model_activated, info.name),
                        Toast.LENGTH_SHORT
                    ).show()
                    sheet.dismiss()
                }
            })
        }
        content.addView(TextView(this).apply {
            text = getString(
                if (recording) R.string.model_switch_recording_hint else R.string.model_switch_hint
            )
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
            setTextColor(getColor(R.color.text_secondary))
        })
        sheet.setContentView(ScrollView(this).apply { addView(content) })
        sheet.show()
    }

    // ================= ② 录音 =================

    private fun setupRecordingsPage() {
        recordingsController = RecordingsController(
            activity = this,
            recycler = binding.rvRecordings,
            emptyView = binding.tvRecordingsEmpty,
            repo = repo,
            onCountChanged = { count ->
                binding.tvRecordingsCount.text = getString(R.string.recordings_count, count)
            }
        )
        recordingsController.start()
        binding.btnImportAudio.setOnClickListener {
            if (!processing) importLauncher.launch("audio/*")
        }
    }

    // ================= ③ 模型 =================

    private fun setupModelsPage() {
        modelListController = ModelListController(
            activity = this,
            container = binding.llModelContainer,
            emptyView = binding.tvModelEmpty,
            chipGroup = binding.chipGroupStatus,
            spLanguage = binding.spLanguage,
            spSort = binding.spSort
        )
        modelListController.start()
        binding.btnAddModel.setOnClickListener {
            AddCustomModelDialog().show(supportFragmentManager, "AddCustomModelDialog")
        }
        binding.btnPasteModels.setOnClickListener {
            startActivity(Intent(this, CustomModelActivity::class.java))
        }
    }

    override fun onModelAdded() {
        // 状态通过 ModelManager 的流自动刷新
    }

    // ================= 录音控制 =================

    private fun checkPermissionsAndRecord() {
        val needed = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startRecording() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startRecording() {
        startForegroundService(
            Intent(this, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_START)
        )
        recording = true; paused = false
        updateUI()
    }

    private fun stopRecording() {
        startService(
            Intent(this, TranscriptionService::class.java).setAction(TranscriptionService.ACTION_STOP)
        )
        recording = false; paused = false
        updateUI()
    }

    private fun updateUI() {
        binding.btnRecord.isEnabled = !processing
        if (recording) {
            binding.btnRecord.text = getString(R.string.stop_recording)
            binding.btnRecord.setBackgroundColor(getColor(R.color.rec))
            binding.btnPause.isVisible = true
            binding.btnPause.text = getString(
                if (paused) R.string.resume_recording else R.string.pause_recording
            )
            binding.tvStatus.text = getString(
                if (paused) R.string.paused_status else R.string.recording_status
            )
        } else {
            binding.btnRecord.text = getString(R.string.start_recording)
            binding.btnRecord.setBackgroundColor(getColor(R.color.seed))
            binding.btnPause.isVisible = false
            binding.tvStatus.text = getString(R.string.idle_status)
        }
    }

    private fun copyText(text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, R.string.nothing_to_copy, Toast.LENGTH_SHORT).show()
            return
        }
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("transcript", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    /** 一条实时录音落库（带分段时间轴），统一出现在录音页。 */
    private fun saveRecording(text: String, wav: String?, durationMs: Long, segmentsJson: String?) {
        val id = ModelManager.getActiveModelId(this)
        val modelName = id?.let { ModelCatalog.findById(this, it)?.name }
            ?: getString(R.string.pending_transcribe)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rowId = repo.insert(text, wav, durationMs, modelName, segmentsJson)
                Log.d("MainActivity", "inserted rowId=$rowId text='$text'")
            } catch (e: Exception) {
                Log.e("MainActivity", "insert failed", e)
            }
        }
    }

    // ================= 导入音频 =================

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
                var segmentsJson: String? = null
                if (modelDir != null) {
                    val r = FileTranscriber.transcribe(this@MainActivity, modelDir, dest.absolutePath)
                    if (r != null) {
                        text = r.text
                        durationMs = r.durationMs
                        segmentsJson = r.segmentsJson
                        modelName = ModelManager.getActiveModelId(this@MainActivity)
                            ?.let { ModelCatalog.findById(this@MainActivity, it)?.name } ?: "sherpa-onnx"
                    }
                } else {
                    // 没有模型：仍然记录时长，方便之后一键转写
                    durationMs = AudioFileDecoder
                        .decodeToPcm16kMono(this@MainActivity, dest.absolutePath)
                        ?.size?.div(16)?.toLong() ?: 0L
                }
                repo.insert(text, dest.absolutePath, durationMs, modelName, segmentsJson)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        if (text.isNotBlank()) getString(R.string.import_success)
                        else getString(R.string.import_pending),
                        Toast.LENGTH_LONG
                    ).show()
                    processing = false
                    updateUI()
                    switchToTab(TAB_RECORDINGS)
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** 打开主界面时定位到哪个页面（设置页的「管理模型 / 管理录音」会用）。 */
        const val EXTRA_TAB = "extra_tab"
        const val TAB_REALTIME = 0
        const val TAB_RECORDINGS = 1
        const val TAB_MODELS = 2
    }
}
