package com.example.aphones2t

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.aphones2t.data.AppDatabase
import com.example.aphones2t.data.TranscriptEntity
import com.example.aphones2t.data.TranscriptRepository
import com.example.aphones2t.databinding.ActivityHistoryBinding
import com.example.aphones2t.databinding.ItemHistoryBinding
import com.example.aphones2t.model.ModelCatalog
import com.example.aphones2t.model.ModelManager
import com.example.aphones2t.utils.FileTranscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val repo by lazy { TranscriptRepository(AppDatabase.get(this).transcriptDao()) }
    private val adapter = HistoryAdapter { showDetails(it) }

    /** Id of the item currently being (re-)transcribed; -1 when idle. Guards against double runs. */
    private var transcribingId = -1L

    /**
     * 尚未执行过转写的记录：文本为空、有音频文件、且模型名仍为待转写标记。
     * 转写完成（即使结果为空）后模型名会变成实际模型名，不再视为 pending，
     * 避免"待转写"无限循环。
     */
    private fun isPending(item: TranscriptEntity): Boolean =
        item.text.isBlank() && !item.wavPath.isNullOrBlank() &&
            (item.modelName.isBlank() || item.modelName == getString(R.string.pending_transcribe))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.history.collectLatest { list ->
                    adapter.submitList(list)
                    binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun showDetails(item: TranscriptEntity) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.createdAt))
        val dur = DateUtils.formatElapsedTime(item.durationMs / 1000)
        val modelDir = ModelManager.getActiveModelDirectory(this)
        val modelReady = modelDir != null
        val pending = isPending(item)
        // 模型就绪时，点击"待转写"记录即自动开始转写；详情弹窗同时保留，便于播放/删除等。
        val willAutoTranscribe = pending && modelReady && transcribingId != item.id
        val isTranscribing = transcribingId == item.id
        val actions = buildList {
            add(getString(R.string.copy))
            if (pending && modelReady) add(getString(R.string.action_transcribe))
            add(getString(R.string.share))
            add(getString(R.string.play))
            add(getString(R.string.delete))
        }
        val message = when {
            item.text.isNotBlank() -> item.text
            pending && !modelReady -> getString(R.string.history_pending_no_model)
            pending && (isTranscribing || willAutoTranscribe) -> getString(R.string.history_pending_transcribing)
            pending -> getString(R.string.history_pending_ready)
            item.wavPath.isNullOrBlank() -> "[空]"
            else -> getString(R.string.history_pending_empty)
        }
        AlertDialog.Builder(this)
            .setTitle("$time · $dur")
            .setMessage(message)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    getString(R.string.copy) -> {
                        getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("transcript", item.text))
                        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    getString(R.string.action_transcribe) -> modelDir?.let { transcribe(item, it) }
                    getString(R.string.share) -> startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, item.text)
                        }, getString(R.string.share)))
                    getString(R.string.play) -> play(item)
                    getString(R.string.delete) -> lifecycleScope.launch { repo.delete(item) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // 模型就绪时点击待转写记录：直接开始转写，完成后列表自动更新
        if (willAutoTranscribe) modelDir?.let { transcribe(item, it) }
    }

    /** Re-transcribes a pending recording/import once a model is available. */
    private fun transcribe(item: TranscriptEntity, modelDir: File) {
        if (transcribingId == item.id) return // 已在转写中，避免重复
        val path = item.wavPath ?: run {
            Toast.makeText(this, "无音频文件", Toast.LENGTH_SHORT).show(); return
        }
        transcribingId = item.id
        Toast.makeText(this, getString(R.string.transcribing_status), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                FileTranscriber.transcribe(this@HistoryActivity, modelDir, path)
            }
            transcribingId = -1L
            if (result == null) {
                Toast.makeText(this@HistoryActivity, getString(R.string.transcribe_failed), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val modelName = ModelManager.getActiveModelId(this@HistoryActivity)
                ?.let { ModelCatalog.findById(this@HistoryActivity, it)?.name } ?: "sherpa-onnx"
            repo.update(item.copy(text = result.text, durationMs = result.durationMs, modelName = modelName))
            Toast.makeText(this@HistoryActivity, getString(R.string.transcribe_success), Toast.LENGTH_SHORT).show()
        }
    }

    private fun play(item: TranscriptEntity) {
        val path = item.wavPath ?: run {
            Toast.makeText(this, "无音频文件", Toast.LENGTH_SHORT).show(); return
        }
        try {
            MediaPlayer().apply {
                setDataSource(path); setOnCompletionListener { it.release() }
                setOnErrorListener { mp, _, _ -> mp.release(); true }
                prepare(); start()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "播放失败", Toast.LENGTH_SHORT).show()
        }
    }

    private class HistoryAdapter(
        private val onClick: (TranscriptEntity) -> Unit
    ) : ListAdapter<TranscriptEntity, HistoryAdapter.VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.bind(item)
            holder.itemView.setOnClickListener { onClick(item) }
        }

        class VH(private val b: ItemHistoryBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: TranscriptEntity) {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.createdAt))
                val dur = DateUtils.formatElapsedTime(item.durationMs / 1000)
                b.tvTitle.text = "$time · $dur · ${item.modelName}"
                b.tvSnippet.text = when {
                    item.text.isNotBlank() -> item.text
                    !item.wavPath.isNullOrBlank() &&
                        (item.modelName.isBlank() ||
                            item.modelName == b.root.context.getString(R.string.pending_transcribe)) ->
                        b.root.context.getString(R.string.pending_transcribe)
                    !item.wavPath.isNullOrBlank() -> b.root.context.getString(R.string.transcribe_empty)
                    else -> "[空]"
                }
            }
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<TranscriptEntity>() {
                override fun areItemsTheSame(a: TranscriptEntity, b: TranscriptEntity) = a.id == b.id
                override fun areContentsTheSame(a: TranscriptEntity, b: TranscriptEntity) = a == b
            }
        }
    }
}
