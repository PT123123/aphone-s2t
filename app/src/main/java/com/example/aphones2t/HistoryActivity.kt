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
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.aphones2t.data.AppDatabase
import com.example.aphones2t.data.TranscriptEntity
import com.example.aphones2t.data.TranscriptRepository
import com.example.aphones2t.databinding.ActivityHistoryBinding
import com.example.aphones2t.databinding.ItemHistoryBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val repo by lazy { TranscriptRepository(AppDatabase.get(this).transcriptDao()) }
    private val adapter = HistoryAdapter { showDetails(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.recycler.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.all.collectLatest { list ->
                    adapter.submitList(list)
                    binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun showDetails(item: TranscriptEntity) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.createdAt))
        val dur = DateUtils.formatElapsedTime(item.durationMs / 1000)
        val actions = arrayOf(
            getString(R.string.copy),
            getString(R.string.share),
            getString(R.string.play),
            getString(R.string.delete)
        )
        AlertDialog.Builder(this)
            .setTitle("$time · $dur")
            .setMessage(item.text.ifBlank { "[空]" })
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("transcript", item.text))
                        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    1 -> startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, item.text)
                        }, getString(R.string.share)))
                    2 -> play(item)
                    3 -> lifecycleScope.launch { repo.delete(item) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
                b.tvSnippet.text = item.text.ifBlank { "[空]" }
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
