package com.hippo.ehviewer.ui.scene.topList

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.annotation.IntDef
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhClient
import com.hippo.ehviewer.client.EhRequest
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.EhTopListDetail
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.data.topList.TopListInfo
import com.hippo.ehviewer.client.data.topList.TopListItem
import com.hippo.ehviewer.client.exception.EhException
import com.hippo.ehviewer.ui.scene.BaseScene
import com.hippo.ehviewer.ui.scene.EhCallback
import com.hippo.ehviewer.ui.scene.ProgressScene
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene
import com.hippo.ehviewer.ui.scene.gallery.list.GalleryListScene
import com.hippo.ehviewer.util.ClipboardUtil.createAnnouncerFromClipboardUrl
import com.hippo.scene.Announcer
import com.hippo.scene.SceneFragment
import com.hippo.view.ViewTransition

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ehContext = ehContext ?: return
        client = EhApplication.getEhClient(ehContext)
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
        for (i in 0 until CATEGORY_TAB_COUNT) {
            val tab = tabs.newTab()
            if (i < array.size) {
                tab.text = array[i]
            }
            tab.tag = i
            tabs.addTab(tab)
        }
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
    }

    override fun onBackPressed() {
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
        val adapter = EhTopListAdapterView(ctx, info, this, searchType, mTimeBucket)
        rv.adapter = adapter
        emptyStateView?.visibility = View.GONE
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
    ) : EhTopListAdapter(ctx, topListInfo, searchType, timeBucketIndex) {

        override fun onItemClick(topListItem: TopListItem, searchType: Int) {
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
            GalleryListScene.startScene(sceneFragment, urlBuilder)
        }
    }

    companion object {
        const val KEY_ACTION = "action"
        const val ACTION_TOP_LIST = "action_top_list"
    }
}
