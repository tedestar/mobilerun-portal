package com.mobilerun.portal.ui.taskprompt

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.mobilerun.portal.R
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.databinding.ActivityTaskHistoryBinding
import com.mobilerun.portal.databinding.ItemTaskHistoryBinding
import com.mobilerun.portal.taskprompt.PortalCloudClient
import com.mobilerun.portal.taskprompt.PortalDashboardStats
import com.mobilerun.portal.taskprompt.PortalTaskHistoryItem
import com.mobilerun.portal.taskprompt.PortalTaskHistoryPage
import com.mobilerun.portal.taskprompt.PortalTaskHistoryResult
import com.mobilerun.portal.taskprompt.PortalTaskStatusAppearance
import com.mobilerun.portal.taskprompt.PortalTaskUiSupport
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

class TaskHistoryActivity : AppCompatActivity() {
    companion object {
        private const val PAGE_SIZE = 20
        private const val DASHBOARD_PAGE_SIZE = 100
        private const val SEARCH_DEBOUNCE_MS = 300L

        fun createIntent(context: Context): Intent {
            return Intent(context, TaskHistoryActivity::class.java)
        }
    }

    private val portalCloudClient = PortalCloudClient()
    private val handler = Handler(Looper.getMainLooper())
    private val items = mutableListOf<PortalTaskHistoryItem>()

    private lateinit var binding: ActivityTaskHistoryBinding
    private lateinit var footerView: LinearLayout
    private lateinit var adapter: TaskHistoryAdapter

    private val historySwipeRefresh: SwipeRefreshLayout
        get() = binding.taskHistorySwipeRefresh
    private val searchInput: TextInputEditText
        get() = binding.taskHistorySearchInput
    private val listView: ListView
        get() = binding.taskHistoryList
    private val loadingView: View
        get() = binding.taskHistoryLoadingView
    private val emptyView: View
        get() = binding.taskHistoryEmptyView
    private val emptyText: TextView
        get() = binding.taskHistoryEmptyText
    private val retryButton: MaterialButton
        get() = binding.taskHistoryRetryButton

    private val tabLayout: TabLayout
        get() = binding.taskTabs
    private val dashboardSwipeRefresh: SwipeRefreshLayout
        get() = binding.taskDashboardSwipeRefresh
    private val historyContainer: View
        get() = binding.taskHistoryContainer
    private val dashboardLoading: View
        get() = binding.taskDashboardLoading
    private val dashboardError: View
        get() = binding.taskDashboardError
    private val dashboardRetryButton: MaterialButton
        get() = binding.taskDashboardRetryButton

    private val avgDurationValue: TextView
        get() = binding.dashboardAvgDurationValue
    private val avgStepsValue: TextView
        get() = binding.dashboardAvgStepsValue
    private val topModelValue: TextView
        get() = binding.dashboardTopModelValue
    private val sparklineView: SparklineView
        get() = binding.dashboardSparkline
    private val successRateValue: TextView
        get() = binding.dashboardSuccessRateValue
    private val successRateDetail: TextView
        get() = binding.dashboardSuccessRateDetail
    private val totalRunsValue: TextView
        get() = binding.dashboardTotalRunsValue
    private val totalRunsDetail: TextView
        get() = binding.dashboardTotalRunsDetail
    private val successRingView: SuccessRingView
        get() = binding.dashboardSuccessRing
    private val statusLegend: LinearLayout
        get() = binding.dashboardStatusLegend
    private val performanceSampleHint: TextView
        get() = binding.dashboardPerformanceSampleHint
    private val successRateSampleHint: TextView
        get() = binding.dashboardSuccessRateSampleHint

    private var searchRunnable: Runnable? = null
    private var currentPage = 0
    private var hasNextPage = false
    private var isInitialLoading = false
    private var isLoadingMore = false
    private var requestToken = 0
    private var errorMessage: String? = null
    private var hasLoadedHistory = false

    private var dataRequestToken = 0
    private var isDataLoading = false
    private var hasLoadedData = false
    private var dashboardStats: PortalDashboardStats? = null
    private var cachedTaskPage: PortalTaskHistoryPage? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.taskHistoryBackButton.setOnClickListener { finish() }

        setupTabs()
        setupHistoryTab()
        setupDashboardTab()

        if (!hasValidSession(showToast = true)) {
            finish()
            return
        }

        // Start on Dashboard tab
        showDashboardTab()
    }

    override fun onDestroy() {
        searchRunnable?.let(handler::removeCallbacks)
        super.onDestroy()
    }

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tasks_tab_dashboard))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tasks_tab_history))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> showDashboardTab()
                    1 -> showHistoryTab()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun showDashboardTab() {
        searchRunnable?.let(handler::removeCallbacks)
        historyContainer.visibility = View.GONE
        if (!hasLoadedData && !isDataLoading) {
            loadData()
        } else {
            renderDashboardState()
        }
    }

    private fun showHistoryTab() {
        dashboardSwipeRefresh.visibility = View.GONE
        dashboardLoading.visibility = View.GONE
        dashboardError.visibility = View.GONE
        historyContainer.visibility = View.VISIBLE
        if (!hasLoadedHistory && !isInitialLoading) {
            if (seedHistoryFromDashboard()) return
            loadTasks(reset = true)
        }
    }

    private fun seedHistoryFromDashboard(): Boolean {
        val cached = cachedTaskPage ?: return false
        val query = searchInput.text?.toString()?.trim().orEmpty()
        if (query.isNotBlank()) return false

        items.clear()
        items.addAll(cached.items)
        currentPage = (cached.items.size + PAGE_SIZE - 1) / PAGE_SIZE
        hasNextPage = cached.total > cached.items.size
        hasLoadedHistory = true
        errorMessage = null
        adapter.notifyDataSetChanged()
        renderState()
        return true
    }

    private fun configureSwipeRefresh(
        swipeRefresh: SwipeRefreshLayout,
        onRefresh: () -> Unit,
    ) {
        swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.task_prompt_accent),
        )
        swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.background_card),
        )
        swipeRefresh.setOnRefreshListener { onRefresh() }
    }

    private fun setupDashboardTab() {
        dashboardRetryButton.setOnClickListener { loadData() }
        configureSwipeRefresh(dashboardSwipeRefresh) { refreshDashboard() }
    }

    private fun loadData() {
        val authToken = currentAuthToken()
        val restBaseUrl = currentRestBaseUrl()
        if (authToken.isBlank() || restBaseUrl == null) {
            hasLoadedData = false
            dashboardStats = null
            renderDashboardState()
            return
        }

        isDataLoading = true
        renderDashboardState()

        val localToken = ++dataRequestToken
        portalCloudClient.listTasks(
            restBaseUrl = restBaseUrl,
            authToken = authToken,
            query = null,
            page = 1,
            pageSize = DASHBOARD_PAGE_SIZE,
        ) { result ->
            val stats = when (result) {
                is PortalTaskHistoryResult.Success -> PortalDashboardStats.compute(
                    items = result.value.items,
                    total = result.value.total,
                )
                is PortalTaskHistoryResult.Error -> null
            }
            val dashboardItems = when (result) {
                is PortalTaskHistoryResult.Success -> result.value
                is PortalTaskHistoryResult.Error -> null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || localToken != dataRequestToken) {
                    return@runOnUiThread
                }
                isDataLoading = false
                if (stats != null) {
                    hasLoadedData = true
                    dashboardStats = stats
                    cachedTaskPage = dashboardItems
                } else {
                    hasLoadedData = false
                    dashboardStats = null
                    cachedTaskPage = null
                }
                if (tabLayout.selectedTabPosition == 1) {
                    if (dashboardItems != null) {
                        seedHistoryFromDashboard()
                    } else {
                        hasLoadedHistory = items.isNotEmpty()
                        Toast.makeText(this, getString(R.string.dashboard_error), Toast.LENGTH_SHORT).show()
                    }
                }
                renderDashboardState()
            }
        }
    }

    private fun refreshDashboard() {
        if (isDataLoading) return
        hasLoadedData = false
        dashboardStats = null
        cachedTaskPage = null
        loadData()
    }

    private fun renderDashboardState() {
        if (tabLayout.selectedTabPosition != 0) return

        if (isDataLoading) {
            if (!dashboardSwipeRefresh.isRefreshing) {
                dashboardSwipeRefresh.visibility = View.GONE
                dashboardLoading.visibility = View.VISIBLE
            } else {
                dashboardLoading.visibility = View.GONE
            }
            dashboardError.visibility = View.GONE
            return
        }

        dashboardSwipeRefresh.isRefreshing = false

        val stats = dashboardStats
        if (stats == null) {
            dashboardSwipeRefresh.visibility = View.GONE
            dashboardLoading.visibility = View.GONE
            dashboardError.visibility = View.VISIBLE
            return
        }

        dashboardSwipeRefresh.visibility = View.VISIBLE
        dashboardLoading.visibility = View.GONE
        dashboardError.visibility = View.GONE

        val na = getString(R.string.dashboard_not_available)

        // Performance
        avgDurationValue.text = stats.avgDurationMs?.let { PortalTaskUiSupport.formatDuration(it) } ?: na
        avgStepsValue.text = stats.avgSteps?.toString() ?: na
        topModelValue.text = stats.topModel?.let { PortalCloudClient.formatModelLabel(it) } ?: na
        val sampleHint = if (stats.sampleSize < stats.totalRuns) {
            getString(R.string.dashboard_sample_hint, stats.sampleSize)
        } else ""
        performanceSampleHint.text = sampleHint

        // Sparkline
        sparklineView.setData(
            stats.activityByDay.map { it.count },
            stats.activityByDay.map { it.label },
        )

        // Success Rate + Ring
        successRateSampleHint.text = sampleHint
        successRingView.setData(stats.statusCounts)

        // Status Legend
        statusLegend.removeAllViews()
        val rowPadding = (3 * resources.displayMetrics.density).toInt()
        val dotSize = (8 * resources.displayMetrics.density).toInt()
        val secondaryColor = ContextCompat.getColor(this, R.color.task_prompt_text_secondary)
        val primaryColor = ContextCompat.getColor(this, R.color.text_white)
        for (sc in stats.statusCounts) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, rowPadding, 0, rowPadding)
            }
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginEnd = dotSize
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(sc.color)
                }
            }
            val label = TextView(this).apply {
                text = PortalTaskUiSupport.statusLabel(this@TaskHistoryActivity, sc.status)
                setTextColor(secondaryColor)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val count = TextView(this).apply {
                text = sc.count.toString()
                setTextColor(primaryColor)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            }
            row.addView(dot)
            row.addView(label)
            row.addView(count)
            statusLegend.addView(row)
        }
        if (stats.successRate != null) {
            successRateValue.text = String.format(Locale.US, "%.1f%%", stats.successRate)
        } else {
            successRateValue.text = "—"
        }
        successRateDetail.text = getString(
            R.string.dashboard_done_failed_format,
            stats.completedCount,
            stats.failedCount,
        )

        // Total Runs
        totalRunsValue.text = String.format(Locale.US, "%,d", stats.totalRuns)
        totalRunsDetail.text = stats.lastTaskAgoMs?.let { ms ->
            getString(R.string.dashboard_last_task_format, PortalTaskUiSupport.formatTimeAgo(ms))
        } ?: getString(R.string.dashboard_no_tasks_yet)
    }

    private fun setupHistoryTab() {
        footerView = buildFooterView()
        listView.addFooterView(footerView, null, false)
        footerView.visibility = View.GONE
        adapter = TaskHistoryAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) as? PortalTaskHistoryItem ?: return@setOnItemClickListener
            startActivity(TaskDetailsActivity.createIntent(this, item.taskId))
        }
        listView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit

            override fun onScroll(
                view: AbsListView?,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int,
            ) {
                if (!hasNextPage || isInitialLoading || isLoadingMore || totalItemCount == 0) {
                    return
                }
                if (firstVisibleItem + visibleItemCount >= totalItemCount - 2) {
                    loadTasks(reset = false)
                }
            }
        })

        retryButton.setOnClickListener { loadTasks(reset = true) }
        searchInput.doAfterTextChanged { scheduleSearch() }
        configureSwipeRefresh(historySwipeRefresh) { loadTasks(reset = true) }
        historySwipeRefresh.setOnChildScrollUpCallback { _, _ ->
            listView.visibility == View.VISIBLE && listView.canScrollVertically(-1)
        }
    }

    private fun scheduleSearch() {
        searchRunnable?.let(handler::removeCallbacks)
        searchRunnable = Runnable { loadTasks(reset = true) }
        handler.postDelayed(searchRunnable!!, SEARCH_DEBOUNCE_MS)
    }

    private fun loadTasks(reset: Boolean) {
        val authToken = currentAuthToken()
        val restBaseUrl = currentRestBaseUrl()
        if (authToken.isBlank() || restBaseUrl == null) {
            historySwipeRefresh.isRefreshing = false
            if (hasValidSession(showToast = true)) return
            finish()
            return
        }

        if (reset) {
            isInitialLoading = true
            isLoadingMore = false
            currentPage = 0
            hasNextPage = false
            errorMessage = null
        } else {
            if (!hasNextPage || isInitialLoading || isLoadingMore) return
            isLoadingMore = true
        }
        renderState()

        val nextPage = if (reset) 1 else currentPage + 1
        val query = searchInput.text?.toString()?.trim().orEmpty().takeIf { it.isNotBlank() }
        val localRequestToken = ++requestToken
        portalCloudClient.listTasks(
            restBaseUrl = restBaseUrl,
            authToken = authToken,
            query = query,
            page = nextPage,
            pageSize = PAGE_SIZE,
        ) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed || localRequestToken != requestToken) {
                    return@runOnUiThread
                }

                isInitialLoading = false
                isLoadingMore = false
                historySwipeRefresh.isRefreshing = false
                when (result) {
                    is PortalTaskHistoryResult.Success -> {
                        errorMessage = null
                        hasLoadedHistory = true
                        if (reset) {
                            items.clear()
                        }
                        items.addAll(result.value.items)
                        currentPage = result.value.page
                        hasNextPage = result.value.hasNext
                    }

                    is PortalTaskHistoryResult.Error -> {
                        if (!reset) {
                            Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                        } else {
                            items.clear()
                            errorMessage = result.message
                            hasNextPage = false
                            currentPage = 0
                        }
                    }
                }

                adapter.notifyDataSetChanged()
                renderState()
            }
        }
    }

    private fun renderState() {
        val query = searchInput.text?.toString()?.trim().orEmpty()
        val showLoading = isInitialLoading && items.isEmpty()
        val showList = items.isNotEmpty()
        val showEmpty = !showLoading && !showList

        loadingView.visibility = if (showLoading) View.VISIBLE else View.GONE
        listView.visibility = if (showList) View.VISIBLE else View.GONE
        emptyView.visibility = if (showEmpty) View.VISIBLE else View.GONE
        footerView.visibility = if (isLoadingMore) View.VISIBLE else View.GONE

        if (showEmpty) {
            emptyText.text = when {
                !errorMessage.isNullOrBlank() -> errorMessage
                query.isNotBlank() -> getString(R.string.task_history_empty_search)
                else -> getString(R.string.task_history_empty)
            }
            retryButton.visibility = if (errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
        } else {
            retryButton.visibility = View.GONE
        }
    }

    private fun hasValidSession(showToast: Boolean): Boolean {
        val authToken = currentAuthToken()
        if (authToken.isBlank()) {
            if (showToast) {
                Toast.makeText(this, getString(R.string.task_prompt_missing_api_key), Toast.LENGTH_SHORT).show()
            }
            return false
        }
        if (currentRestBaseUrl() == null) {
            if (showToast) {
                Toast.makeText(this, getString(R.string.task_prompt_unsupported_custom_url), Toast.LENGTH_SHORT).show()
            }
            return false
        }
        return true
    }

    private fun currentAuthToken(): String {
        return ConfigManager.getInstance(this).reverseConnectionToken.trim()
    }

    private fun currentRestBaseUrl(): String? {
        return PortalCloudClient.deriveRestBaseUrl(
            ConfigManager.getInstance(this).reverseConnectionUrlOrDefault,
        )
    }

    private fun buildFooterView(): LinearLayout {
        val padding = (12 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            addView(ProgressBar(context))
            addView(TextView(context).apply {
                text = getString(R.string.task_history_load_more)
                setTextColor(ContextCompat.getColor(context, R.color.task_prompt_text_secondary))
                textSize = 13f
                setPadding(padding / 2, 0, 0, 0)
            })
        }
    }

    private inner class TaskHistoryAdapter : BaseAdapter() {
        private val inflater = LayoutInflater.from(this@TaskHistoryActivity)

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val itemBinding = if (convertView == null) {
                ItemTaskHistoryBinding.inflate(inflater, parent, false).also { binding ->
                    binding.root.tag = binding
                }
            } else {
                (convertView.tag as? ItemTaskHistoryBinding)
                    ?: ItemTaskHistoryBinding.bind(convertView).also { binding ->
                        binding.root.tag = binding
                    }
            }
            val item = items[position]

            itemBinding.taskHistoryItemStatusChip.text =
                PortalTaskUiSupport.statusLabel(this@TaskHistoryActivity, item.status)
            itemBinding.taskHistoryItemStatusChip.background =
                createChipBackground(PortalTaskUiSupport.statusAppearance(item.status))
            itemBinding.taskHistoryItemPrompt.text =
                item.promptPreview.ifBlank { item.prompt.ifBlank { item.taskId } }

            val metaParts = mutableListOf<String>()
            PortalTaskUiSupport.formatTimestamp(item.createdAt)?.let(metaParts::add)
            item.steps?.let { metaParts.add(getString(R.string.task_history_steps, it)) }
            itemBinding.taskHistoryItemMeta.text = metaParts.joinToString(" • ").ifBlank { item.taskId }

            val summary = PortalTaskUiSupport.buildSummary(
                context = this@TaskHistoryActivity,
                status = item.status,
                summary = item.summary,
                steps = item.steps,
            )
            itemBinding.taskHistoryItemSummary.text = summary.orEmpty()
            itemBinding.taskHistoryItemSummary.visibility =
                if (summary.isNullOrBlank()) View.GONE else View.VISIBLE
            return itemBinding.root
        }
    }

    private fun createChipBackground(appearance: PortalTaskStatusAppearance): GradientDrawable {
        val colorRes = when (appearance) {
            PortalTaskStatusAppearance.INFO -> R.color.task_prompt_chip_info_bg
            PortalTaskStatusAppearance.SUCCESS -> R.color.task_prompt_chip_success_bg
            PortalTaskStatusAppearance.ERROR -> R.color.task_prompt_chip_error_bg
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(ContextCompat.getColor(this@TaskHistoryActivity, colorRes))
        }
    }
}
