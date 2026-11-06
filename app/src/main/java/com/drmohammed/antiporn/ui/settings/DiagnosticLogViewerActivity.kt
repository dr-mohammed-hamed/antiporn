package com.drmohammed.antiporn.ui.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.telemetry.AiTelemetryReporter
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.DiagnosticEventDto
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import timber.log.Timber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modern In-App Black Box Diagnostic Flight Log Viewer.
 * Displays local SQLite diagnostic events with category filtering,
 * execution latency tracking, one-click Markdown export, and manual clear capability.
 */
class DiagnosticLogViewerActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var chipGroupFilters: ChipGroup
    private lateinit var tvLogsCount: TextView
    private lateinit var tvActivePolicySummary: TextView
    private lateinit var rvLogs: RecyclerView
    private lateinit var tvEmptyLogs: TextView

    private val adapter = DiagnosticLogAdapter()
    private var currentFilter: String? = null
    private var loadJob: Job? = null
    private var isExporting = false
    private var isClearing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostic_log_viewer)

        findViewById<View>(R.id.layoutRootLogViewer)?.let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
                windowInsets
            }
        }

        initViews()
        setupListeners()
        loadLogs()
        loadPolicySummary()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbarLogViewer)
        toolbar.setNavigationOnClickListener { finish() }

        // Setup Toolbar Menu (Share & Clear) directly without unnecessary inflate
        toolbar.menu.clear()
        toolbar.menu.add(0, MENU_SHARE, 0, R.string.btn_share_telemetry).apply {
            setIcon(R.drawable.ic_share)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, MENU_CLEAR, 1, R.string.menu_clear_logs).apply {
            setIcon(R.drawable.ic_delete)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SHARE -> {
                    exportAndShareReport()
                    true
                }
                MENU_CLEAR -> {
                    showClearConfirmationDialog()
                    true
                }
                else -> false
            }
        }

        chipGroupFilters = findViewById(R.id.chipGroupFilters)
        tvLogsCount = findViewById(R.id.tvLogsCount)
        tvActivePolicySummary = findViewById(R.id.tvActivePolicySummary)
        rvLogs = findViewById(R.id.rvDiagnosticLogs)
        tvEmptyLogs = findViewById(R.id.tvEmptyLogs)

        rvLogs.layoutManager = LinearLayoutManager(this)
        rvLogs.adapter = adapter
    }

    private fun setupListeners() {
        chipGroupFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            currentFilter = when (checkedIds.first()) {
                R.id.chipFilterCurfew -> BlackBoxRecorder.TAG_CURFEW
                R.id.chipFilterCloud -> BlackBoxRecorder.TAG_CLOUD_SYNC
                R.id.chipFilterTime -> BlackBoxRecorder.TAG_TIME
                R.id.chipFilterDpm -> BlackBoxRecorder.TAG_DPM
                R.id.chipFilterPairing -> BlackBoxRecorder.TAG_PAIRING
                else -> null
            }
            loadLogs()
        }
    }

    private fun loadLogs() {
        // Red-Teaming Scenario 1: Cancel any in-flight query to prevent race conditions on rapid taps
        loadJob?.cancel()
        loadJob = lifecycleScope.launch(Dispatchers.IO) {
            val eventDao = HisnRoomDatabase.getInstance(applicationContext).diagnosticEventDao()
            val filter = currentFilter
            val tag = if (filter.isNullOrBlank() || filter == BlackBoxRecorder.CATEGORY_ALL) null else filter
            val entities = eventDao.getDiagnosticEvents(limit = 300, tag = tag)
            val events = entities.map { it.toDto() }
            val totalCount = eventDao.getEventsCount()

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                adapter.submitList(events)
                tvLogsCount.text = getString(R.string.total_events_count_format, events.size, totalCount)
                tvEmptyLogs.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
                rvLogs.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun loadPolicySummary() {
        lifecycleScope.launch(Dispatchers.IO) {
            val policy = try {
                PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
            } catch (e: Exception) {
                null
            }
            val isCurfewRunning = try {
                CurfewEnforcer.isEffectiveBlockActive(applicationContext)
            } catch (e: Exception) {
                false
            }

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (policy == null) {
                    tvActivePolicySummary.visibility = View.GONE
                    return@withContext
                }
                tvActivePolicySummary.visibility = View.VISIBLE
                val statusStr = if (isCurfewRunning) {
                    getString(R.string.status_curfew_active)
                } else {
                    getString(R.string.status_curfew_normal)
                }
                tvActivePolicySummary.text = getString(
                    R.string.policy_summary_format,
                    policy.version,
                    statusStr,
                    policy.curfewStartHour,
                    policy.curfewStartMinute
                )
            }
        }
    }

    private fun exportAndShareReport() {
        if (isExporting) return
        isExporting = true
        lifecycleScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    AiTelemetryReporter.generateMarkdownReport(applicationContext)
                }
                if (isFinishing || isDestroyed) return@launch
                AiTelemetryReporter.copyReportToClipboard(applicationContext, report)
                Toast.makeText(applicationContext, R.string.toast_telemetry_copied, Toast.LENGTH_SHORT).show()

                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.telemetry_share_subject))
                    putExtra(android.content.Intent.EXTRA_TEXT, report)
                }
                try {
                    startActivity(android.content.Intent.createChooser(shareIntent, getString(R.string.share_telemetry_chooser_title)))
                } catch (e: Exception) {
                    Timber.e(e, "Error launching share chooser")
                }
            } finally {
                isExporting = false
            }
        }
    }

    private fun showClearConfirmationDialog() {
        if (isClearing) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_clear_logs_title)
            .setMessage(R.string.dialog_clear_logs_msg)
            .setPositiveButton(R.string.btn_delete) { dialog, _ ->
                dialog.dismiss()
                if (isClearing) return@setPositiveButton
                isClearing = true
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val eventDao = HisnRoomDatabase.getInstance(applicationContext).diagnosticEventDao()
                        eventDao.clearAllEvents()
                        withContext(Dispatchers.Main) {
                            if (isFinishing || isDestroyed) return@withContext
                            Toast.makeText(applicationContext, R.string.toast_logs_cleared, Toast.LENGTH_SHORT).show()
                            loadLogs()
                        }
                    } finally {
                        isClearing = false
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val MENU_SHARE = 101
        private const val MENU_CLEAR = 102
    }
}

/**
 * Modern ListAdapter utilizing DiffUtil for flicker-free and performant list updates.
 */
class DiagnosticLogAdapter : ListAdapter<DiagnosticEventDto, DiagnosticLogAdapter.LogViewHolder>(DiagnosticEventDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_diagnostic_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val viewStatusDot: View = itemView.findViewById(R.id.viewStatusDot)
        private val tvLogTag: TextView = itemView.findViewById(R.id.tvLogTag)
        private val tvLogAction: TextView = itemView.findViewById(R.id.tvLogAction)
        private val tvLogLatency: TextView = itemView.findViewById(R.id.tvLogLatency)
        private val tvLogTime: TextView = itemView.findViewById(R.id.tvLogTime)
        private val tvLogDetails: TextView = itemView.findViewById(R.id.tvLogDetails)
        private val tvLogError: TextView = itemView.findViewById(R.id.tvLogError)

        fun bind(event: DiagnosticEventDto) {
            tvLogTag.text = event.tag
            tvLogAction.text = event.actionName
            tvLogTime.text = formatTimeOnly(event.timestamp)

            val context = itemView.context
            val successColor = ContextCompat.getColor(context, R.color.status_green)
            val fallbackErrorColor = ContextCompat.getColor(context, R.color.status_red)
            val failureColor = MaterialColors.getColor(
                context,
                androidx.appcompat.R.attr.colorError,
                fallbackErrorColor
            )

            if (event.isSuccess) {
                viewStatusDot.backgroundTintList = ColorStateList.valueOf(successColor)
                tvLogError.visibility = View.GONE
            } else {
                viewStatusDot.backgroundTintList = ColorStateList.valueOf(failureColor)
                if (!event.errorDetails.isNullOrBlank()) {
                    tvLogError.text = event.errorDetails
                    tvLogError.visibility = View.VISIBLE
                } else {
                    tvLogError.visibility = View.GONE
                }
            }

            if (event.executionTimeMs > 0L) {
                tvLogLatency.text = context.getString(R.string.log_latency_format, event.executionTimeMs)
                tvLogLatency.visibility = View.VISIBLE
            } else {
                tvLogLatency.visibility = View.GONE
            }

            val meta = event.contextMetadata
            if (!meta.isNullOrBlank()) {
                tvLogDetails.text = meta
                tvLogDetails.visibility = View.VISIBLE
            } else {
                tvLogDetails.visibility = View.GONE
            }
        }

        companion object {
            private val timeFormat = ThreadLocal.withInitial {
                SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            }

            private fun formatTimeOnly(timestamp: Long): String {
                return try {
                    timeFormat.get()?.format(Date(timestamp)) ?: timestamp.toString()
                } catch (e: Exception) {
                    timestamp.toString()
                }
            }
        }
    }

    private object DiagnosticEventDiffCallback : DiffUtil.ItemCallback<DiagnosticEventDto>() {
        override fun areItemsTheSame(oldItem: DiagnosticEventDto, newItem: DiagnosticEventDto): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: DiagnosticEventDto, newItem: DiagnosticEventDto): Boolean {
            return oldItem == newItem
        }
    }
}
