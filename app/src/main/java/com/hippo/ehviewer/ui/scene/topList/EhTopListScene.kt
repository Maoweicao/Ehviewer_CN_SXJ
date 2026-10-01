package com.hippo.ehviewer.ui.scene.topList

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.util.SparseArray
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.annotation.IntDef
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhRequest
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.EhTopListDetail
import com.hippo.ehviewer.client.data.GalleryDetail
import com.hippo.ehviewer.client.data.GalleryInfo
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.data.topList.TopListInfo
import com.hippo.ehviewer.client.data.topList.TopListItem
import com.hippo.ehviewer.client.exception.EhException
import com.hippo.ehviewer.ui.CommonOperations
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.ui.scene.EhCallback
import com.hippo.ehviewer.ui.scene.ProgressScene
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene
import com.hippo.ehviewer.util.ClipboardUtil.createAnnouncerFromClipboardUrl
import com.hippo.lib.yorozuya.ResourcesUtils
import com.hippo.scene.Announcer
import com.hippo.scene.SceneFragment
import com.hippo.util.ExceptionUtils
import com.hippo.util.ExecutorManager
import com.hippo.view.ViewTransition

private const val TAG = "EhTopListScene"

private const val STATE_INIT = -1
private const val STATE_NORMAL = 0
private const val STATE_FAILED = 3
private const val STATE_EMPTY = 4
private const val BACK_PRESSED_INTERVAL = 2000L

private const val CATEGORY_TAB_COUNT = 7
private const val TIME_BUCKET_COUNT = 4

// Time bucket ordering matches TopListInfo.get(): 0=yesterday,1=past-month,2=past-year,3=all-time
private val TIME_BUCKET_LABELS = intArrayOf(
    R.string.top_list_tab_yesterday,
    R.string.top_list_tab_past_month,
    R.string.top_list_tab_past_year,
    R.string.top_list_tab_all_time,
)

private var mCategory = 0
private var mTimeBucket = 0

class EhTopListScene : BaseScene() {

    @IntDef(STATE_INIT, STATE_NORMAL, STATE_FAILED, STATE_EMPTY)
    @Retention(AnnotationRetention.SOURCE)
    private annotation class State

    private var pressBackTime = 0L

    @State
    private var state = STATE_INIT

    private var ehTopListDetail: EhTopListDetail? = null

    private var viewTransition: ViewTransition? = null
    private var recyclerView: RecyclerView? = null
    private var categoryTabLayout: TabLayout? = null
    private var timeBucketTabLayout: TabLayout? = null

    private var emptyStateView: View? = null
    private var emptyStateText: TextView? = null
    private var emptyStateRetry: Button? = null

    private var client: EhClient? = null
    private var topListRequest: EhRequest? = null
    private var hasFirstRefresh = false

    // ---- 画廊排行榜：懒加载画廊详情 ----
    /** gid -> GalleryDetail，跨 tab 切换复用，避免重复请求 */
    private val galleryCache = SparseArray<GalleryDetail>()
    /** 正在请求的 gid，避免同一页重复发起 */
    private val pendingGids = HashSet<Long>()
    /** 本页还需拉取详情的数量，串行请求的推进依据 */
    private var pendingDetailCount = 0

    // ---- 多选下载 ----
    private var multiSelectMode = false
    private val selectedGids = LinkedHashSet<Long>()
    /** gid -> 缓存的 GalleryInfo，多选下载时直接复用 */
    private val selectedGalleries = LinkedHashMap<Long, GalleryInfo>()
    private var topListAdapter: EhTopListAdapterView? = null
    private var downloadBar: View? = null
    private var downloadBarCount: TextView? = null
    private var multiSelectFab: FloatingActionButton? = null
    private var selectedIconColor = 0
    private var unselectedIconColor = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ehContext = ehContext ?: return
        client = EhApplication.getEhClient(ehContext)
        // 提前算出图标着色，onCreateView2 里逐个 tab 应用
        selectedIconColor = resolveThemeColor(R.attr.widgetColorThemePrimary, 0xFF2196F3.toInt())
        unselectedIconColor = resolveThemeColor(R.attr.textColorThemePrimary, 0xFF888888.toInt())
    }

    override fun onCreateView2(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.scene_gallery_top_list, container, false)

        val loadingView = view.findViewById<View>(R.id.data_loading_view)
        val detailView = view.findViewById<View>(R.id.page_detail_view)
        viewTransition = ViewTransition(loadingView, detailView)

        emptyStateView = view.findViewById(R.id.empty_state_view)
        emptyStateText = view.findViewById(R.id.empty_state_text)
        emptyStateRetry = view.findViewById(R.id.empty_state_retry)
        emptyStateRetry?.setOnClickListener { retryLoad() }

        bindDownloadBar(view)

        bindCategoryTabLayout(view)
        bindTimeBucketTabLayout(view)
        bindList(view)

        if (!hasFirstRefresh) {
            hasFirstRefresh = true
            try {
                loadTopListData()
            } catch (e: EhException) {
                e.printStackTrace()
            }
        } else {
            rebindListAdapter()
            adjustViewVisibility(STATE_NORMAL, true)
        }

        return view
    }

    private fun bindDownloadBar(root: View) {
        downloadBar = root.findViewById(R.id.top_list_download_bar)
        downloadBarCount = root.findViewById(R.id.top_list_download_count)
        root.findViewById<View>(R.id.top_list_download_action)
            ?.setOnClickListener { downloadSelected() }
        root.findViewById<View>(R.id.top_list_download_cancel)
            ?.setOnClickListener {
                if (multiSelectMode) {
                    toggleMultiSelectMode()
                }
            }

        // 多选模式入口 FAB：只有画廊排行榜能进入多选，其他 6 个分类是关键词榜单
        val fab = root.findViewById<View>(R.id.top_list_multi_select_fab) as? FloatingActionButton
        multiSelectFab = fab
        fab?.setOnClickListener {
            if (multiSelectMode) {
                downloadSelected()
            } else {
                toggleMultiSelectMode()
            }
        }
        updateMultiSelectFabVisibility()
    }

    /** 画廊排行榜之外不显示多选 FAB（关键词榜单没有 gid，无法多选下载） */
    private fun updateMultiSelectFabVisibility() {
        val fab = multiSelectFab ?: return
        val isGallery = ehTopListDetail?.get(mCategory)
            ?.type == EhTopListDetail.ListType.GALLERY
        fab.visibility = if (isGallery && state != STATE_EMPTY) View.VISIBLE else View.GONE
    }

    private fun retryLoad() {
        // 取消上次请求
        topListRequest?.cancel()
        // 重置 UI
        emptyStateView?.visibility = View.GONE
        emptyStateRetry?.visibility = View.GONE
        adjustViewVisibility(STATE_INIT, true)
        // 重新发请求
        loadTopListData()
    }

    private fun isEmptyDetail(detail: EhTopListDetail): Boolean {
        for (i in 0 until CATEGORY_TAB_COUNT) {
            val info = detail[i] ?: continue
            for (j in 0 until TIME_BUCKET_COUNT) {
                val bucket = info[j]
                if (bucket != null && bucket.length() > 0) return false
            }
        }
        return true
    }

    private fun bindCategoryTabLayout(root: View) {
        categoryTabLayout = root.findViewById(R.id.top_list_tab_layout)
        val tabs = categoryTabLayout ?: return
        val array = resources.getStringArray(R.array.top_list_type)
        val iconArray = resources.obtainTypedArray(R.array.top_list_type_icons)
        val iconTint = getColorStateListFromTheme()
        for (i in 0 until CATEGORY_TAB_COUNT) {
            val tab = tabs.newTab()
            if (i < array.size) {
                tab.text = array[i]
            }
            if (i < iconArray.length()) {
                // 分类 tab 配图标做视觉装饰；时间分段 tab 保持纯文字
                // 图标自身 fillColor 已是 ?attr/widgetColorThemePrimary，
                // 这里不再单独染色，统一交给 tabIconTint 处理选中/未选中态
                tab.icon = iconArray.getDrawable(i)
            }
            tab.tag = i
            tabs.addTab(tab)
        }
        iconArray.recycle()
        // 图标跟随 tab 选中态着色：选中主题色，未选中普通文字色
        tabs.tabIconTint = iconTint
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val pos = (tab.tag as? Int) ?: 0
                if (pos != mCategory) {
                    mCategory = pos
                    // Switching category resets the time bucket to "Yesterday" so the
                    // list immediately reflects the new category without confusion.
                    mTimeBucket = 0
                    syncTimeBucketTabSelection()
                    rebindListAdapter()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    /**
     * 分类 tab 图标着色：选中态用主题色，未选中态用普通文字色，
     * 跟 TabLayout 的 tabTextColor 保持一致。
     */
    private fun getColorStateListFromTheme(): ColorStateList {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_selected),
            intArrayOf(),
        )
        return ColorStateList(
            states,
            intArrayOf(selectedIconColor, unselectedIconColor)
        )
    }

    private fun resolveThemeColor(attrRes: Int, fallback: Int): Int {
        val context = ehContext ?: return fallback
        return ResourcesUtils.getAttrColor(context, attrRes)
    }

    private fun bindTimeBucketTabLayout(root: View) {
        timeBucketTabLayout = root.findViewById(R.id.top_list_sub_tab_layout)
        val tabs = timeBucketTabLayout ?: return
        for (i in 0 until TIME_BUCKET_COUNT) {
            val tab = tabs.newTab()
            tab.text = getString(TIME_BUCKET_LABELS[i])
            tab.tag = i
            tabs.addTab(tab)
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val pos = (tab.tag as? Int) ?: 0
                if (pos != mTimeBucket) {
                    mTimeBucket = pos
                    rebindListAdapter()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun bindList(root: View) {
        recyclerView = root.findViewById(R.id.top_list_recycler_view)
        recyclerView?.layoutManager = LinearLayoutManager(ehContext)
    }

    private fun syncTimeBucketTabSelection() {
        val tabs = timeBucketTabLayout ?: return
        if (mTimeBucket in 0 until tabs.tabCount) {
            val tab = tabs.getTabAt(mTimeBucket)
            if (tab != null && !tab.isSelected) tab.select()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewTransition = null
        topListAdapter = null
        downloadBar = null
        downloadBarCount = null
        multiSelectFab = null
    }

    override fun onBackPressed() {
        // 多选模式下先退出多选，避免直接退出页面
        if (multiSelectMode) {
            toggleMultiSelectMode()
            return
        }
        if (!checkDoubleClickExit()) {
            if (state == STATE_INIT) {
                topListRequest?.cancel()
            }
            finish()
        }
    }

    private fun checkDoubleClickExit(): Boolean {
        if (stackIndex != 0) {
            return false
        }
        val time = System.currentTimeMillis()
        return if (time - pressBackTime > BACK_PRESSED_INTERVAL) {
            pressBackTime = time
            showTip(R.string.press_twice_exit, LENGTH_SHORT)
            true
        } else {
            false
        }
    }

    @Throws(EhException::class)
    private fun loadTopListData() {
        if (!requestTopList()) {
            throw EhException("请求数据失败请更换IP地址或检查网络设置是否正确~")
        }
    }

    private fun requestTopList(): Boolean {
        val context = ehContext ?: return false
        val activity = activity2 ?: return false
        val ehClient = client ?: return false
        val url = EhUrl.getTopListUrl()

        val callback = GetTopListDetailListener(context, activity.stageId, tag)
        topListRequest = EhRequest()
            .setMethod(EhClient.METHOD_GET_TOP_LIST)
            .setArgs(url)
            .setCallback(callback)
        ehClient.execute(topListRequest)
        return true
    }

    private fun onGetEhTopListDetailSuccess(detail: EhTopListDetail) {
        ehTopListDetail = detail
        if (isEmptyDetail(detail)) {
            showEmptyState(false)
            multiSelectFab?.visibility = View.GONE
        } else {
            rebindListAdapter()
            adjustViewVisibility(STATE_NORMAL, true)
        }
    }

    private fun showEmptyState(isError: Boolean) {
        val textView = emptyStateText ?: return
        val retryBtn = emptyStateRetry ?: return
        val emptyView = emptyStateView ?: return

        if (isError) {
            textView.text = getString(R.string.top_list_load_failed)
            retryBtn.visibility = View.VISIBLE
        } else {
            textView.text = getString(R.string.top_list_empty)
            retryBtn.visibility = View.GONE
        }
        emptyView.visibility = View.VISIBLE
        adjustViewVisibility(STATE_EMPTY, true)
    }

    /**
     * Re-binds the RecyclerView using the currently selected category +
     * time bucket. Called when the data first arrives, when the category
     * TabLayout changes, or when the secondary time-bucket TabLayout changes.
     */
    private fun rebindListAdapter() {
        val detail = ehTopListDetail ?: return
        val rv = recyclerView ?: return
        val ctx = ehContext ?: return
        val info = detail[mCategory] ?: return
        // searchType 0 = GALLERY, anything else = UPLOADER-like (ListUrlBuilder.MODE_UPLOADER).
        val searchType = if (info.type == EhTopListDetail.ListType.GALLERY) 0 else 1
        val adapter = EhTopListAdapterView(
            ctx, info, this, searchType, mTimeBucket, galleryCache, selectedGids, multiSelectMode
        )
        rv.adapter = adapter
        topListAdapter = adapter
        emptyStateView?.visibility = View.GONE
        // 换分类时多选状态不再适用，退出并刷新 FAB
        if (multiSelectMode) {
            multiSelectMode = false
            selectedGids.clear()
            selectedGalleries.clear()
            downloadBar?.visibility = View.GONE
        }
        updateMultiSelectFabVisibility()
        updateMultiSelectFabState()
        // 画廊排行榜才需要拉详情，其余 6 类是关键词榜单，服务器不下发 gid
        if (info.type == EhTopListDetail.ListType.GALLERY) {
            requestGalleryDetails(adapter)
        }
    }

    /**
     * 为本页的画廊条目懒加载详情。逐个串行请求（榜单单页最多 10 条），
     * 每拿到一个就刷新一次列表，避免一次性并发 10 个请求。
     */
    private fun requestGalleryDetails(adapter: EhTopListAdapter) {
        val ctx = ehContext ?: return
        val bucket = adapter.currentBucket() ?: return
        val gids = ArrayList<Long>()
        for (i in 0 until bucket.length()) {
            val item = bucket.get(i)
            if (item.gid.isNullOrEmpty() || item.token.isNullOrEmpty()) continue
            val gid = item.gid.toLongOrNull() ?: continue
            gids.add(gid)
        }
        pendingDetailCount = gids.count { galleryCache.get(it.toInt()) == null }
        fetchNextGalleryDetail(ctx, adapter, gids, 0)
    }

    /** 串行拉取，从 index 起找第一个既没缓存也没在请求中的 gid */
    private fun fetchNextGalleryDetail(
        ctx: Context,
        adapter: EhTopListAdapter,
        gids: List<Long>,
        startIndex: Int,
    ) {
        var index = startIndex
        while (index < gids.size) {
            val gid = gids[index]
            if (galleryCache.get(gid.toInt()) != null || !pendingGids.add(gid)) {
                index++
                continue
            }
            val item = findTopListItem(adapter, gid) ?: run {
                pendingGids.remove(gid)
                index++
                continue
            }
            fetchGalleryDetailForGid(ctx, adapter, gid, item.token) {
                // 串行推进：这条回来后再取下一个，避免一页 10 个请求齐发
                if (pendingDetailCount > 0) {
                    fetchNextGalleryDetail(ctx, adapter, gids, index + 1)
                }
            }
            return
        }
    }

    /**
     * 拉取单个画廊详情。onDone 一定会在主线程回调（成功或失败），
     * 调用方靠它推进串行队列。
     */
    private fun fetchGalleryDetailForGid(
        ctx: Context,
        adapter: EhTopListAdapter,
        gid: Long,
        token: String,
        onDone: () -> Unit,
    ) {
        val httpClient = EhApplication.getOkHttpClient(ctx)
        ExecutorManager.getComputationExecutor().execute {
            var detail: GalleryDetail? = null
            try {
                val url = EhUrl.getGalleryDetailUrl(gid, token)
                detail = EhEngine.getGalleryDetail(null, httpClient, url)
            } catch (e: Throwable) {
                ExceptionUtils.throwIfFatal(e)
                Log.w(TAG, "Failed to load gallery detail for gid=$gid", e)
            }
            val loaded = detail
            ExecutorManager.runOnMainThread {
                if (loaded != null) {
                    galleryCache.put(gid.toInt(), loaded)
                    // 已选中的同步补进待下载列表
                    if (gid in selectedGids) {
                        selectedGalleries[gid] = loaded
                        updateDownloadBar()
                    }
                    pendingDetailCount = (pendingDetailCount - 1).coerceAtLeast(0)
                    // 只在还停留在同一页时刷新，避免切走后误刷
                    if (isCurrentPage(adapter)) {
                        topListAdapter?.notifyDataSetChanged()
                    }
                }
                pendingGids.remove(gid)
                onDone()
            }
        }
    }

    private fun findTopListItem(adapter: EhTopListAdapter, gid: Long): TopListItem? {
        val bucket = adapter.currentBucket() ?: return null
        for (i in 0 until bucket.length()) {
            val item = bucket.get(i)
            if (item.gid?.toLongOrNull() == gid) return item
        }
        return null
    }

    private fun isCurrentPage(adapter: EhTopListAdapter): Boolean {
        return topListAdapter === adapter && ehTopListDetail?.get(mCategory) === adapter.topListInfo
    }

    private fun adjustViewVisibility(@State newState: Int, animation: Boolean) {
        val transition = viewTransition ?: return
        state = newState
        when (newState) {
            STATE_INIT -> transition.showView(0, animation)
            else -> transition.showView(1, animation)
        }
    }

    private inner class GetTopListDetailListener(
        context: Context,
        stageId: Int,
        sceneTag: String?,
    ) : EhCallback<EhTopListScene, EhTopListDetail>(context, stageId, sceneTag) {
        override fun isInstance(scene: SceneFragment): Boolean = scene is EhTopListScene

        override fun onSuccess(result: EhTopListDetail) {
            onGetEhTopListDetailSuccess(result)
        }

        override fun onFailure(e: Exception) {
            // 网络错误时隐藏 loading view，显示空状态 + 重试按钮
            adjustViewVisibility(STATE_FAILED, true)
            showEmptyState(true)
        }

        override fun onCancel() {}
    }

    private inner class EhTopListAdapterView(
        ctx: Context,
        topListInfo: TopListInfo,
        private val sceneFragment: SceneFragment,
        searchType: Int,
        timeBucketIndex: Int,
        cache: SparseArray<GalleryDetail>,
        selected: LinkedHashSet<Long>,
        multiSelect: Boolean,
    ) : EhTopListAdapter(ctx, topListInfo, searchType, timeBucketIndex, cache, selected, multiSelect) {

        override fun onItemClick(topListItem: TopListItem, searchType: Int) {
            // 多选模式下点击是切换选中，不是跳转；长按才跳画廊详情
            if (multiSelectMode) {
                toggleSelection(topListItem)
                return
            }
            openTopListItem(topListItem, searchType)
        }

        override fun onLongClick(topListItem: TopListItem): Boolean {
            // 长按进入多选模式，并把这一个直接选中
            if (!multiSelectMode) {
                toggleMultiSelectMode()
            }
            toggleSelection(topListItem)
            return true
        }
    }

    /**
     * 非多选模式下点击条目的原始行为：
     * 画廊排行榜（有 gid+token）直接进画廊详情；其余关键词榜单按关键词搜索。
     */
    private fun openTopListItem(topListItem: TopListItem, searchType: Int) {
        val urlBuilder = ListUrlBuilder()
        if (searchType == 0) {
            urlBuilder.mode = ListUrlBuilder.MODE_NORMAL
        } else {
            urlBuilder.mode = ListUrlBuilder.MODE_UPLOADER
        }

        if (!topListItem.gid.isNullOrEmpty() && !topListItem.token.isNullOrEmpty()) {
            val args = Bundle()
            args.putString(ProgressScene.KEY_ACTION, ProgressScene.ACTION_GALLERY_TOKEN)
            args.putLong(ProgressScene.KEY_GID, topListItem.gid.toLong())
            args.putString(ProgressScene.KEY_PTOKEN, topListItem.tag)
            val announcer = Announcer(GalleryDetailScene::class.java).setArgs(args)
            startScene(announcer)
            return
        } else if (topListItem.href != null) {
            val announcer = createAnnouncerFromClipboardUrl(topListItem.href)
            if (announcer != null) {
                startScene(announcer)
                return
            }
        }

        urlBuilder.keyword = topListItem.value
        GalleryListScene.startScene(this, urlBuilder)
    }

    // ---------------- 多选下载 ----------------

    /** 切换某个条目的选中状态 */
    private fun toggleSelection(topListItem: TopListItem) {
        val gid = topListItem.gid?.toLongOrNull() ?: run {
            showTip(R.string.multi_select_no_gallery, LENGTH_SHORT)
            return
        }
        if (selectedGids.remove(gid)) {
            selectedGalleries.remove(gid)
        } else {
            selectedGids.add(gid)
            // 详情已缓存时直接放进待下载列表；未缓存的等 fetchGalleryDetail 回填
            galleryCache.get(gid.toInt())?.let { selectedGalleries[gid] = it }
        }
        updateDownloadBar()
        topListAdapter?.notifyDataSetChanged()
    }

    /**
     * 为已选中但详情尚未到位的 gid 补拉详情。
     * 长按选中可能早于详情加载完，此时直接下载会漏掉这些画廊。
     */
    private fun ensureSelectedDetails() {
        val ctx = ehContext ?: return
        val adapter = topListAdapter ?: return
        val missing = selectedGids.filter { selectedGalleries[it] == null }
        if (missing.isEmpty()) {
            return
        }
        showTip(R.string.multi_select_loading_details, LENGTH_SHORT)
        for (gid in missing) {
            val item = findTopListItem(adapter, gid)
            val token = item?.token
            if (token.isNullOrEmpty() || !pendingGids.add(gid)) {
                continue
            }
            fetchGalleryDetailForGid(ctx, adapter, gid, token) { }
        }
    }

    private fun toggleMultiSelectMode() {
        multiSelectMode = !multiSelectMode
        if (!multiSelectMode) {
            selectedGids.clear()
            selectedGalleries.clear()
            showTip(R.string.multi_select_mode_disabled, LENGTH_SHORT)
        } else {
            showTip(R.string.multi_select_mode_enabled, LENGTH_SHORT)
        }
        updateDownloadBar()
        topListAdapter?.setMultiSelectMode(multiSelectMode)
    }

    private fun updateDownloadBar() {
        val bar = downloadBar ?: return
        if (!multiSelectMode || selectedGids.isEmpty()) {
            bar.visibility = View.GONE
        } else {
            bar.visibility = View.VISIBLE
            downloadBarCount?.text =
                getString(R.string.multi_select_selected_count, selectedGids.size)
        }
        updateMultiSelectFabState()
    }

    /** 多选模式下 FAB 图标切成"下载"，未选中时保持"全选"进入多选 */
    private fun updateMultiSelectFabState() {
        val fab = multiSelectFab ?: return
        fab.setImageResource(
            if (multiSelectMode && selectedGids.isNotEmpty()) {
                R.drawable.v_download_dark_x24
            } else {
                R.drawable.v_check_all_dark_x24
            }
        )
    }

    /** 底部下载条：把已选中的画廊交给统一下载入口，和主画廊列表一致 */
    private fun downloadSelected() {
        if (selectedGids.isEmpty()) {
            showTip(R.string.no_gallery_selected, LENGTH_SHORT)
            return
        }
        // 详情还没到位的先补拉，否则会漏下载
        if (selectedGalleries.size < selectedGids.size) {
            ensureSelectedDetails()
            return
        }
        val activity = activity2 ?: return
        val dialogContext = getDialogContext() ?: return
        val list = ArrayList<GalleryInfo>(selectedGalleries.values)
        AlertDialog.Builder(dialogContext)
            .setTitle(R.string.download_selected)
            .setMessage(getString(R.string.download_selected_confirm, list.size))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                CommonOperations.startDownload(activity, list, false)
                // 下载后退出多选，清空选择
                if (multiSelectMode) {
                    toggleMultiSelectMode()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val KEY_ACTION = "action"
        const val ACTION_TOP_LIST = "action_top_list"
    }
}
