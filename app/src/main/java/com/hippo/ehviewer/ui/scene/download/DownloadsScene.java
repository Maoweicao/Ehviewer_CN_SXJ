/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui.scene.download;

import static com.hippo.ehviewer.spider.SpiderDen.getExistingGalleryDownloadDir;
import static com.hippo.ehviewer.spider.SpiderDen.getGalleryDownloadDir;
import static com.hippo.ehviewer.spider.SpiderInfo.getSpiderInfo;
import static com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter.DRAG_ENABLE;
import static com.hippo.util.FileUtils.getFileName;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RatingBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.github.amlcurran.showcaseview.ShowcaseView;
import com.github.amlcurran.showcaseview.SimpleShowcaseEventListener;
import com.github.amlcurran.showcaseview.targets.PointTarget;
import com.github.amlcurran.showcaseview.targets.ViewTarget;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.h6ah4i.android.widget.advrecyclerview.animator.DraggableItemAnimator;
import com.h6ah4i.android.widget.advrecyclerview.animator.GeneralItemAnimator;
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager;
import com.hippo.android.resource.AttrResources;
import com.hippo.app.CheckBoxDialogBuilder;
import com.hippo.drawable.AddDeleteDrawable;
import com.hippo.drawerlayout.DrawerLayout;
import com.hippo.easyrecyclerview.EasyRecyclerView;
import com.hippo.easyrecyclerview.FastScroller;
import com.hippo.easyrecyclerview.HandlerDrawable;
import com.hippo.easyrecyclerview.MarginItemDecoration;
import com.hippo.ehviewer.Analytics;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.callBack.DownloadSearchCallback;
import com.hippo.ehviewer.client.EhConfig;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.DownloadService;
import com.hippo.ehviewer.task.impl.CompressSelectedGalleriesTask;
import com.hippo.ehviewer.BackgroundTaskManager;
import com.hippo.ehviewer.event.SomethingNeedRefresh;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.sync.DownloadListInfosExecutor;
import com.hippo.ehviewer.sync.DownloadSpiderInfoExecutor;
import com.hippo.ehviewer.ui.GalleryActivity;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.ui.CommonOperations;
import com.hippo.ehviewer.ui.annotation.ViewLifeCircle;
import com.hippo.ehviewer.ui.dialog.SelectItemWithIconAdapter;
import com.hippo.ehviewer.ui.scene.ToolbarScene;
import com.hippo.ehviewer.ui.scene.download.part.CheckboxAdapter;
import com.hippo.ehviewer.ui.scene.download.part.DownloadAdapter;
import com.hippo.ehviewer.ui.scene.download.part.DownloadCategoryTable;
import com.hippo.ehviewer.ui.scene.download.part.MyPageChangeListener;
import com.hippo.ehviewer.transfer.core.TransferClientManager;
import com.hippo.ehviewer.transfer.core.RelayTaskManager;
import com.hippo.ehviewer.transfer.data.ConnectedDevice;
import com.hippo.ehviewer.util.TagTranslationUtil;
import com.hippo.ehviewer.widget.AdvanceSearchTable;
import com.hippo.ehviewer.widget.MyEasyRecyclerView;
import com.hippo.ehviewer.widget.SearchBar;
import com.hippo.lib.yorozuya.AssertUtils;
import com.hippo.lib.yorozuya.ObjectUtils;
import com.hippo.lib.yorozuya.ViewUtils;
import com.hippo.lib.yorozuya.collect.LongList;
import com.hippo.ripple.Ripple;
import com.hippo.unifile.UniFile;
import com.hippo.util.AppHelper;
import com.hippo.util.DrawableManager;
import com.hippo.util.IoThreadPoolExecutor;
import com.hippo.view.ViewTransition;
import com.hippo.widget.FabLayout;
import com.hippo.widget.LoadImageViewNew;
import com.hippo.widget.ProgressView;
import com.hippo.widget.SearchBarMover;
import com.hippo.widget.recyclerview.AutoStaggeredGridLayoutManager;
import com.sxj.paginationlib.PaginationIndicator;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class DownloadsScene extends ToolbarScene
        implements DownloadManager.DownloadInfoListener, DownloadSearchCallback,
        MyEasyRecyclerView.OnItemClickListener,
        MyEasyRecyclerView.OnItemLongClickListener,
        FabLayout.OnClickFabListener, FabLayout.OnExpandListener, FastScroller.OnDragHandlerListener, SearchBar.Helper, SearchBarMover.Helper, SearchBar.OnStateChangeListener, DownloadAdapter.DownloadAdapterCallback {

    private static final String TAG = DownloadsScene.class.getSimpleName();

    public static final String KEY_GID = "gid";

    public static final String KEY_ACTION = "action";
    private static final String KEY_LABEL = "label";
    private static final String KEY_SELECTED_CATEGORY = "selected_category";
    private static final String KEY_INDEX_PAGE = "index_page";
    private static final String KEY_PAGE_SIZE = "page_size";
    private static final String KEY_SEARCH_KEY = "search_key";
    private static final String KEY_LAST_FILTER_SORT_ID = "last_filter_sort_id";

    public static final String ACTION_CLEAR_DOWNLOAD_SERVICE = "clear_download_service";

    public static final int LOCAL_GALLERY_INFO_CHANGE = 909;

    private static final long ANIMATE_TIME = 300L;

    @Nullable
    private AddDeleteDrawable mActionFabDrawable;


    /*---------------
         Whole life cycle
         ---------------*/
    @Nullable
    private DownloadManager mDownloadManager;
    @Nullable
    public String mLabel;
    @Nullable
    private List<DownloadInfo> mList;
    @Nullable
    private List<DownloadInfo> mBackList;

    @Nullable
    private EhTagDatabase ehTags;

    /*---------------
     List pagination
     ---------------*/
    private int indexPage = 1;
    private int pageSize = 1;
    private boolean canPagination = true;
    private final int paginationSize = 500;
    //    private final int paginationSize = 5;
    private final int[] perPageCountChoices = {50, 100, 200, 300, 500};
//    private final int[] perPageCountChoices = {1, 2, 3, 4, 5};

    private MyPageChangeListener myPageChangeListener;

    private final Map<Long, SpiderInfo> mSpiderInfoMap = new HashMap<>();

    /*---------------
     View life cycle
     ---------------*/
    @Nullable
    private MyEasyRecyclerView mRecyclerView;
    @Nullable
    private ViewTransition mViewTransition;
    @Nullable
    private FabLayout mFabLayout;
    @Nullable
    private RecyclerView.Adapter mAdapter;
    @Nullable
    private DownloadAdapter mOriginalAdapter;
    @Nullable
    private AutoStaggeredGridLayoutManager mLayoutManager;

    // 拖拽管理器
    @Nullable
    private RecyclerViewDragDropManager mDragDropManager;

    private ShowcaseView mShowcaseView;

    private ProgressView mProgressView;

    private AlertDialog mSearchDialog;
    private SearchBar mSearchBar;
    @Nullable
    private PaginationIndicator mPaginationIndicator;

    private DownloadLabelDraw downloadLabelDraw;
    @Nullable
    @ViewLifeCircle
    private SearchBarMover mSearchBarMover;
    private boolean mSearchMode = false;
    @Nullable
    private DownloadListInfosExecutor mCurrentExecutor;
    public String searchKey = null;
    private int mLastFilterSortId = 0;

    private int mInitPosition = -1;

    public boolean searching = false;
    private boolean doNotScroll = false;

    private boolean needInitPage = false;
    private boolean needInitPageSize = false;

    @Nullable
    private Spinner mCategorySpinner;
    private int mSelectedCategory = EhUtils.ALL_CATEGORY;

    @NonNull
    private final ActivityResultLauncher<Intent> galleryActivityLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            this::updateReadProcess
    );

    @NonNull
    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            this::handleSelectedFile
    );

    @Override
    public int getNavCheckedItem() {
        return R.id.nav_downloads;
    }

    private boolean handleArguments(Bundle args) {
        if (null == args) {
            return false;
        }

        if (ACTION_CLEAR_DOWNLOAD_SERVICE.equals(args.getString(KEY_ACTION))) {
            DownloadService.Companion.clear();
        }

        long gid;
        if (null != mDownloadManager && -1L != (gid = args.getLong(KEY_GID, -1L))) {
            DownloadInfo info = mDownloadManager.getDownloadInfo(gid);
            if (null != info) {
                mLabel = info.getLabel();
                updateForLabel();
                updateView();

                // Get position
                if (null != mList) {
                    int position = mList.indexOf(info);
                    if (position >= 0 && null != mRecyclerView) {
                        initPage(position);
                    } else {
                        mInitPosition = position;
                    }
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void onNewArguments(@NonNull Bundle args) {
        handleArguments(args);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context context = getEHContext();
        AssertUtils.assertNotNull(context);
        mDownloadManager = EhApplication.getDownloadManager(context);
        mDownloadManager.addDownloadInfoListener(this);
        canPagination = Settings.getDownloadPagination();
        if (savedInstanceState == null) {
            onInit();
        } else {
            onRestore(savedInstanceState);
        }
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelCurrentExecutor();
        mList = null;

        DownloadManager manager = mDownloadManager;
        if (null == manager) {
            Context context = getEHContext();
            if (null != context) {
                manager = EhApplication.getDownloadManager(context);
            }
        } else {
            mDownloadManager = null;
        }

        if (null != manager) {
            manager.removeDownloadInfoListener(this);
        } else {
            Log.e(TAG, "Can't removeDownloadInfoListener");
        }
        mActionFabDrawable = null;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void updateForLabel() {
        if (null == mDownloadManager) {
            return;
        }

        if (mLabel == null) {
            mList = mDownloadManager.getDefaultDownloadInfoList();
        } else {
            mList = mDownloadManager.getLabelDownloadInfoList(mLabel);
            if (mList == null) {
                mLabel = null;
                mList = mDownloadManager.getDefaultDownloadInfoList();
            }
        }

        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
        mBackList = mList;
//        filterByCategory();
        updateTitle();
        updatePaginationIndicator();
        Settings.putRecentDownloadLabel(mLabel);
        queryUnreadSpiderInfo();
    }

    private void updatePaginationIndicator() {
        if (mPaginationIndicator == null || mList == null) {
            return;
        }
        if (mList.size() < paginationSize || !canPagination) {
            mPaginationIndicator.setVisibility(View.GONE);
            return;
        }
        mPaginationIndicator.setVisibility(View.VISIBLE);
        needInitPageSize = true;
        mPaginationIndicator.initPaginationIndicator(pageSize, perPageCountChoices, mList.size(), indexPage);
//        mPaginationIndicator.setTotalCount();
        mPaginationIndicator.setListener(myPageChangeListener);

        // 同步分页监听器的状态
        if (myPageChangeListener != null) {
            myPageChangeListener.setIndexPage(indexPage);
            myPageChangeListener.setPageSize(pageSize);
            myPageChangeListener.setNeedInitPage(needInitPage);
            myPageChangeListener.setDoNotScroll(doNotScroll);
        }
    }

    @SuppressLint("StringFormatMatches")
    private void updateTitle() {
        try {
            setTitle(getString(R.string.scene_download_title_new,
                    mLabel != null ? mLabel : getString(R.string.default_download_label_name),
                    Integer.toString(mList == null ? 0 : mList.size())));
        } catch (Exception e) {
            Analytics.recordException(e);
            setTitle(getString(R.string.scene_download_title_new,
                    mLabel != null ? mLabel : getString(R.string.default_download_label_name)));
        }
    }

    private void onInit() {
        if (!handleArguments(getArguments())) {
            mLabel = Settings.getRecentDownloadLabel();
            updateForLabel();
        }
    }

    private void onRestore(@NonNull Bundle savedInstanceState) {
        mLabel = savedInstanceState.getString(KEY_LABEL);
        mSelectedCategory = savedInstanceState.getInt(KEY_SELECTED_CATEGORY, EhUtils.ALL_CATEGORY);
        indexPage = savedInstanceState.getInt(KEY_INDEX_PAGE, 1);
        pageSize = savedInstanceState.getInt(KEY_PAGE_SIZE, 1);
        searchKey = savedInstanceState.getString(KEY_SEARCH_KEY);
        mLastFilterSortId = savedInstanceState.getInt(KEY_LAST_FILTER_SORT_ID, 0);
        updateForLabel();
        if (mLastFilterSortId > 0 && mLastFilterSortId != R.id.all && mLastFilterSortId != R.id.sort_by_default) {
            gotoFilterAndSort(mLastFilterSortId);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_LABEL, mLabel);
        outState.putInt(KEY_SELECTED_CATEGORY, mSelectedCategory);
        outState.putInt(KEY_INDEX_PAGE, indexPage);
        outState.putInt(KEY_PAGE_SIZE, pageSize);
        outState.putString(KEY_SEARCH_KEY, searchKey);
        outState.putInt(KEY_LAST_FILTER_SORT_ID, mLastFilterSortId);
    }

    @Nullable
    @Override
    public View onCreateView3(LayoutInflater inflater,
                              @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.scene_download, container, false);

        Context context = getEHContext();
        assert context != null;

        mCategorySpinner = (Spinner) ViewUtils.$$(view, R.id.category_spinner);
        // Initialize category spinner
        List<String> categoryList = new ArrayList<>();
        categoryList.add(getString(R.string.category_all)); // Add "All" option
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.DOUJINSHI)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.MANGA)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.ARTIST_CG)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.GAME_CG)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.WESTERN)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.NON_H)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.IMAGE_SET)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.COSPLAY)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.ASIAN_PORN)).toUpperCase(Locale.ROOT));
        categoryList.add(Objects.requireNonNull(EhUtils.getCategory(EhConfig.MISC)).toUpperCase(Locale.ROOT));
        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, categoryList);
        categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mCategorySpinner.setAdapter(categoryAdapter);
        mCategorySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int selectedCategory;
                switch (position) {
                    case 0:
                        selectedCategory = EhUtils.ALL_CATEGORY;
                        break;
                    case 1:
                        selectedCategory = EhConfig.DOUJINSHI;
                        break;
                    case 2:
                        selectedCategory = EhConfig.MANGA;
                        break;
                    case 3:
                        selectedCategory = EhConfig.ARTIST_CG;
                        break;
                    case 4:
                        selectedCategory = EhConfig.GAME_CG;
                        break;
                    case 5:
                        selectedCategory = EhConfig.WESTERN;
                        break;
                    case 6:
                        selectedCategory = EhConfig.NON_H;
                        break;
                    case 7:
                        selectedCategory = EhConfig.IMAGE_SET;
                        break;
                    case 8:
                        selectedCategory = EhConfig.COSPLAY;
                        break;
                    case 9:
                        selectedCategory = EhConfig.ASIAN_PORN;
                        break;
                    case 10:
                        selectedCategory = EhConfig.MISC;
                        break;
                    default:
                        selectedCategory = EhUtils.ALL_CATEGORY;
                        break;
                }
                if (selectedCategory != mSelectedCategory) {
                    mSelectedCategory = selectedCategory;
                    filterByCategory();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });
        // Set default selection, restored from saved state if available
        mCategorySpinner.setSelection(categoryToSpinnerPos(mSelectedCategory));

        // Reapply category filter if restoring a non-default category
        if (savedInstanceState != null && mSelectedCategory != EhUtils.ALL_CATEGORY) {
            filterByCategory();
        }

        mProgressView = (ProgressView) ViewUtils.$$(view, R.id.download_progress_view);
        View content = ViewUtils.$$(view, R.id.content);
        mRecyclerView = (MyEasyRecyclerView) ViewUtils.$$(content, R.id.recycler_view);
        FastScroller fastScroller = (FastScroller) ViewUtils.$$(content, R.id.fast_scroller);
        mFabLayout = (FabLayout) ViewUtils.$$(view, R.id.fab_layout);
        TextView tip = (TextView) ViewUtils.$$(view, R.id.tip);
        if (mPaginationIndicator != null) {
            needInitPage = true;
        }
        mPaginationIndicator = (PaginationIndicator) ViewUtils.$$(view, R.id.indicator);

        mPaginationIndicator.setPerPageCountChoices(perPageCountChoices, getPageSizePos(pageSize));

        mViewTransition = new ViewTransition(content, tip);

        Resources resources = context.getResources();

        Drawable drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_download);
        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        tip.setCompoundDrawables(null, drawable, null, null);
        // 初始化拖拽管理器
        mDragDropManager = new RecyclerViewDragDropManager();
        try {
            mDragDropManager.setDraggingItemShadowDrawable(
                    (NinePatchDrawable) context.getResources().getDrawable(R.drawable.shadow_8dp));
        } catch (Exception e) {
            // 忽略硬件位图相关错误
            android.util.Log.w("DownloadsScene", "Error setting drag shadow: " + e.getMessage());
        }


        mOriginalAdapter = new DownloadAdapter(this, this);
        mOriginalAdapter.setHasStableIds(true);
        mAdapter = mDragDropManager.createWrappedAdapter(mOriginalAdapter); // 包装适配器以支持拖拽
        mDragDropManager.setCheckCanDropEnabled(false);
        mRecyclerView.setAdapter(mAdapter);

        // 初始化分页监听器
        myPageChangeListener = new MyPageChangeListener(indexPage, pageSize, needInitPage, doNotScroll, mOriginalAdapter, mRecyclerView);

        // 设置分页监听器的回调
        myPageChangeListener.setPageChangeCallback(new MyPageChangeListener.PageChangeCallback() {
            @Override
            public void onPageChanged(int newIndexPage) {
                indexPage = newIndexPage;
            }

            @Override
            public void onPageSizeChanged(int newPageSize) {
                pageSize = newPageSize;
            }
        });
        mLayoutManager = new AutoStaggeredGridLayoutManager(0, StaggeredGridLayoutManager.VERTICAL);
        mLayoutManager.setColumnSize(resources.getDimensionPixelOffset(Settings.getDetailSizeResId()));
        mLayoutManager.setStrategy(AutoStaggeredGridLayoutManager.STRATEGY_MIN_SIZE);

        // 设置拖拽动画器
        final GeneralItemAnimator animator = new DraggableItemAnimator();
        mRecyclerView.setItemAnimator(animator);

        mRecyclerView.setItemViewCacheSize(10);
        try {
            mRecyclerView.setDrawingCacheEnabled(true);
            mRecyclerView.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);
        } catch (Exception e) {
            // 忽略硬件位图相关错误
            android.util.Log.w("DownloadsScene", "Error setting drawing cache: " + e.getMessage());
        }
        mRecyclerView.setLayoutManager(mLayoutManager);
        mRecyclerView.setSelector(Ripple.generateRippleDrawable(context, !AttrResources.getAttrBoolean(context, androidx.appcompat.R.attr.isLightTheme), new ColorDrawable(Color.TRANSPARENT)));
        mRecyclerView.setDrawSelectorOnTop(true);
        mRecyclerView.setClipToPadding(false);
        mRecyclerView.setOnItemClickListener(this);
        mRecyclerView.setOnItemLongClickListener(this);
        mRecyclerView.setChoiceMode(MyEasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM);
        mRecyclerView.setCustomCheckedListener(new DownloadChoiceListener());
//        mRecyclerView.setOnGenericMotionListener(this::onGenericMotion);
        // Cancel change animation
        RecyclerView.ItemAnimator itemAnimator = mRecyclerView.getItemAnimator();
        if (itemAnimator instanceof GeneralItemAnimator) {
            ((GeneralItemAnimator) itemAnimator).setSupportsChangeAnimations(false);
        }
        int interval = resources.getDimensionPixelOffset(R.dimen.gallery_list_interval);
        int paddingH = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_h);
        int paddingV = resources.getDimensionPixelOffset(R.dimen.gallery_list_margin_v);
        MarginItemDecoration decoration = new MarginItemDecoration(interval, paddingH, paddingV, paddingH, paddingV);
        mRecyclerView.addItemDecoration(decoration);
        decoration.applyPaddings(mRecyclerView);

        // 将拖拽管理器附加到RecyclerView
        if (mDragDropManager != null) {
            try {
                mDragDropManager.attachRecyclerView(mRecyclerView);
            } catch (Exception e) {
                // 忽略硬件位图相关错误
                android.util.Log.w("DownloadsScene", "Error attaching drag manager: " + e.getMessage());
            }
        }

        if (mInitPosition >= 0 && indexPage != 1) {
            initPage(mInitPosition);
            mRecyclerView.scrollToPosition(listIndexInPage(mInitPosition));
            mInitPosition = -1;
        }

        fastScroller.attachToRecyclerView(mRecyclerView);
        HandlerDrawable handlerDrawable = new HandlerDrawable();
        handlerDrawable.setColor(AttrResources.getAttrColor(context, R.attr.widgetColorThemeAccent));
        fastScroller.setHandlerDrawable(handlerDrawable);
        fastScroller.setOnDragHandlerListener(this);

        mFabLayout.setExpanded(false, true);
        mFabLayout.setHidePrimaryFab(false);
        mFabLayout.setAutoCancel(false);
        mFabLayout.setOnClickFabListener(this);
        mFabLayout.setOnExpandListener(this);
        mActionFabDrawable = new AddDeleteDrawable(context, resources.getColor(R.color.primary_drawable_dark, null));
        mFabLayout.getPrimaryFab().setImageDrawable(mActionFabDrawable);
        FloatingActionButton fab = mFabLayout.getSecondaryFabAt(7);
        if (DRAG_ENABLE) {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_x24, context.getTheme()));
        } else {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_off_x24, context.getTheme()));
        }
        setupFabContentDescriptions();
        mFabLayout.setShowFabFunctionName(Settings.getShowFabFunctionName());
        addAboveSnackView(mFabLayout);

        updateView();

        guide();
        updatePaginationIndicator();
        return view;
    }

    private void guide() {
        if (Settings.getGuideDownloadThumb() && null != mRecyclerView) {
            mRecyclerView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    if (Settings.getGuideDownloadThumb()) {
                        guideDownloadThumb();
                    }
                    if (null != mRecyclerView) {
                        ViewUtils.removeOnGlobalLayoutListener(mRecyclerView.getViewTreeObserver(), this);
                    }
                }
            });
        } else {
            guideDownloadLabels();
        }
    }

    private void guideDownloadThumb() {
        MainActivity activity = getActivity2();
        if (null == activity || !Settings.getGuideDownloadThumb() || null == mLayoutManager || null == mRecyclerView) {
            guideDownloadLabels();
            return;
        }
        int position = mLayoutManager.findFirstCompletelyVisibleItemPositions(null)[0];
        if (position < 0) {
            guideDownloadLabels();
            return;
        }
        RecyclerView.ViewHolder holder = mRecyclerView.findViewHolderForAdapterPosition(position);
        if (null == holder) {
            guideDownloadLabels();
            return;
        }

        mShowcaseView = new ShowcaseView.Builder(activity)
                .withMaterialShowcase()
                .setStyle(R.style.Guide)
                .setTarget(new ViewTarget(((DownloadAdapter.DownloadHolder) holder).thumb))
                .blockAllTouches()
                .setContentTitle(R.string.guide_download_thumb_title)
                .setContentText(R.string.guide_download_thumb_text)
                .replaceEndButton(R.layout.button_guide)
                .setShowcaseEventListener(new SimpleShowcaseEventListener() {
                    @Override
                    public void onShowcaseViewDidHide(ShowcaseView showcaseView) {
                        mShowcaseView = null;
                        ViewUtils.removeFromParent(showcaseView);
                        Settings.putGuideDownloadThumb(false);
                        guideDownloadLabels();
                    }
                }).build();
    }

    private void guideDownloadLabels() {
        MainActivity activity = getActivity2();
        if (null == activity || !Settings.getGuideDownloadLabels()) {
            return;
        }

        Display display = activity.getWindowManager().getDefaultDisplay();
        Point point = new Point();
        display.getSize(point);

        mShowcaseView = new ShowcaseView.Builder(activity)
                .withMaterialShowcase()
                .setStyle(R.style.Guide)
                .setTarget(new PointTarget(point.x, point.y / 3))
                .blockAllTouches()
                .setContentTitle(R.string.guide_download_labels_title)
                .setContentText(R.string.guide_download_labels_text)
                .replaceEndButton(R.layout.button_guide)
                .setShowcaseEventListener(new SimpleShowcaseEventListener() {
                    @Override
                    public void onShowcaseViewDidHide(ShowcaseView showcaseView) {
                        mShowcaseView = null;
                        ViewUtils.removeFromParent(showcaseView);
                        Settings.puttGuideDownloadLabels(false);
                        openDrawer(Gravity.RIGHT);
                    }
                }).build();
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        updateTitle();
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (null != mShowcaseView) {
            ViewUtils.removeFromParent(mShowcaseView);
            mShowcaseView = null;
        }
        if (null != mRecyclerView) {
            mRecyclerView.stopScroll();
            mRecyclerView = null;
        }
        if (mDragDropManager != null) {
            mDragDropManager.release();
            mDragDropManager = null;
        }
        if (null != mFabLayout) {
            removeAboveSnackView(mFabLayout);
            mFabLayout = null;
        }

        mViewTransition = null;
        mAdapter = null;
        mOriginalAdapter = null;
        mLayoutManager = null;
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mFabLayout != null) {
            mFabLayout.setShowFabFunctionName(Settings.getShowFabFunctionName());
        }
    }

    @Override
    public void onNavigationClick(View view) {
        onBackPressed();
    }

    @Override
    public int getMenuResId() {
        return R.menu.scene_download;
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public boolean onMenuItemClick(MenuItem item) {
        // Skip when in choice mode
        Activity activity = getActivity2();
        if (null == activity || null == mRecyclerView || mRecyclerView.isInCustomChoice()) {
            return false;
        }

        int id = item.getItemId();
        switch (id) {
            case R.id.action_start_all: {
                Intent intent = new Intent(activity, DownloadService.class);
                intent.setAction(DownloadService.ACTION_START_ALL);
                activity.startService(intent);
                return true;
            }
            case R.id.action_stop_all: {
                if (null != mDownloadManager) {
                    mDownloadManager.stopAllDownload();
                }
                return true;
            }
            case R.id.action_reset_reading_progress: {
                Context context = getEHContext();
                if (context == null) {
                    return false;
                }
                if (searching) {
                    Toast.makeText(context, R.string.download_searching, Toast.LENGTH_LONG).show();
                    return true;
                }
                new AlertDialog.Builder(context)
                        .setMessage(R.string.reset_reading_progress_message)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            if (mDownloadManager != null) {
                                mDownloadManager.resetAllReadingProgress();
                            }
                        }).show();
                return true;
            }
            case R.id.search_download_gallery: {
                Context context = getEHContext();
                if (context == null) {
                    return false;
                }
                gotoSearch(context);
                return true;
            }
            case R.id.advanced_filter: {
                Context context = getEHContext();
                if (context == null) {
                    return false;
                }
                gotoAdvancedFilter(context);
                return true;
            }
            case R.id.all:
            case R.id.sort_by_default:
            case R.id.download_done:
            case R.id.not_started:
            case R.id.waiting:
            case R.id.downloading:
            case R.id.failed:
            case R.id.relay_download:
            case R.id.sort_by_gallery_id_asc:
            case R.id.sort_by_gallery_id_desc:
            case R.id.sort_by_create_time_asc:
            case R.id.sort_by_create_time_desc:
            case R.id.sort_by_rating_asc:
            case R.id.sort_by_rating_desc:
            case R.id.sort_by_name_asc:
            case R.id.sort_by_name_desc:
            case R.id.sort_by_file_size_asc:
            case R.id.sort_by_file_size_desc:
            case R.id.all_kind:
            case R.id.misc:
            case R.id.doujinshi:
            case R.id.manga:
            case R.id.artist_cg:
            case R.id.game_cg:
            case R.id.image_set:
            case R.id.cosplay:
            case R.id.asian_porn:
            case R.id.non_h:
            case R.id.western:
            case R.id.unknown:
                gotoFilterAndSort(id);
                return true;
            case R.id.import_local_archive:
                importLocalArchive();
                return true;
            case R.id.action_relay_center: {
                Intent intent = new Intent(activity, com.hippo.ehviewer.ui.transfer.TransferActivity.class);
                intent.putExtra("open_tab", "relay");
                activity.startActivity(intent);
                return true;
            }
            case R.id.repair_thumbnails:
                repairThumbnails();
                return true;
//            case R.id.misc:
//            case R.id.doujinshi:
//            case R.id.manga:
//            case R.id.artist_cg:
//            case R.id.game_cg:
//            case R.id.image_set:
//            case R.id.cosplay:
//            case R.id.asian_porn:
//            case R.id.non_h:
//            case R.id.western:
//            case R.id.unknown:
//
//                return true;
        }
        return false;
    }

    private void gotoSearch(Context context) {
        if (mSearchDialog != null) {
            mSearchDialog.show();
            return;
        }
        LayoutInflater layoutInflater = LayoutInflater.from(context);

        Drawable drawable = DrawableManager.getVectorDrawable(context, R.drawable.big_download);

        LinearLayout linearLayout = (LinearLayout) layoutInflater.inflate(R.layout.download_search_dialog_v2, null);
        mSearchBar = linearLayout.findViewById(R.id.download_search_bar);
        mSearchBar.setHelper(this);
        mSearchBar.setIsComeFromDownload(true);
        mSearchBar.setEditTextHint(R.string.download_search_hint);
        mSearchBar.setLeftDrawable(drawable);
        mSearchBar.setText(searchKey);
        if (searchKey != null && !searchKey.isEmpty()) {
            mSearchBar.setTitle(searchKey);
            mSearchBar.cursorToEnd();
        } else {
            mSearchBar.setTitle(R.string.download_search_hint);
        }
        mSearchBar.setRightDrawable(DrawableManager.getVectorDrawable(context, R.drawable.v_magnify_x24));
        mSearchBarMover = new SearchBarMover(this, mSearchBar);

        // Setup category filter toggle
        LinearLayout categoryToggleRow = linearLayout.findViewById(R.id.category_filter_toggle_row);
        LinearLayout categoryContent = linearLayout.findViewById(R.id.category_filter_content_container);
        TextView categoryToggleIcon = linearLayout.findViewById(R.id.category_filter_toggle_icon);
        DownloadCategoryTable categoryTable = linearLayout.findViewById(R.id.category_table);
        categoryTable.setAllSelected();
        categoryToggleRow.setOnClickListener(v -> {
            if (categoryContent.getVisibility() == View.GONE) {
                categoryContent.setVisibility(View.VISIBLE);
                categoryToggleIcon.setText("−");
            } else {
                categoryContent.setVisibility(View.GONE);
                categoryToggleIcon.setText("+");
            }
        });

        // Setup sort RecyclerView
        RecyclerView sortRecyclerView = linearLayout.findViewById(R.id.search_sort_recycler_view);
        sortRecyclerView.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(context));
        List<String> sortNames = new ArrayList<>();
        List<Integer> sortIds = new ArrayList<>();
        sortNames.add(context.getString(R.string.default_sort));
        sortIds.add(R.id.sort_by_default);
        sortNames.add(context.getString(R.string.sort_by_gallery_id_asc));
        sortIds.add(R.id.sort_by_gallery_id_asc);
        sortNames.add(context.getString(R.string.sort_by_gallery_id_desc));
        sortIds.add(R.id.sort_by_gallery_id_desc);
        sortNames.add(context.getString(R.string.sort_by_create_time_asc));
        sortIds.add(R.id.sort_by_create_time_asc);
        sortNames.add(context.getString(R.string.sort_by_create_time_desc));
        sortIds.add(R.id.sort_by_create_time_desc);
        sortNames.add(context.getString(R.string.sort_by_rating_asc));
        sortIds.add(R.id.sort_by_rating_asc);
        sortNames.add(context.getString(R.string.sort_by_rating_desc));
        sortIds.add(R.id.sort_by_rating_desc);
        sortNames.add(context.getString(R.string.sort_by_name_asc));
        sortIds.add(R.id.sort_by_name_asc);
        sortNames.add(context.getString(R.string.sort_by_name_desc));
        sortIds.add(R.id.sort_by_name_desc);
        sortNames.add(context.getString(R.string.sort_by_file_size_asc));
        sortIds.add(R.id.sort_by_file_size_asc);
        sortNames.add(context.getString(R.string.sort_by_file_size_desc));
        sortIds.add(R.id.sort_by_file_size_desc);
        final CheckboxAdapter sortAdapter = new CheckboxAdapter(sortNames, sortIds);
        sortAdapter.setMutuallyExclusive(true);
        sortRecyclerView.setAdapter(sortAdapter);

        // Buttons
        Button resetButton = linearLayout.findViewById(R.id.reset_button);
        Button searchButton = linearLayout.findViewById(R.id.search_button);
        AdvanceSearchTable advanceSearchTable = linearLayout.findViewById(R.id.advance_search_table);

        final int[] selectedSortId = {R.id.sort_by_default};

        sortAdapter.setOnSelectionChangedListener(selected -> {
            if (!selected.isEmpty()) {
                selectedSortId[0] = selected.iterator().next();
            }
        });

        mSearchDialog = new AlertDialog.Builder(context)
                .setView(linearLayout)
                .setCancelable(true)
                .setOnDismissListener(this::onSearchDialogDismiss)
                .show();

        resetButton.setOnClickListener(v -> {
            mSearchBar.setText("");
            mSearchBar.setTitle(null);
            categoryTable.setAllSelected();
            advanceSearchTable.setAdvanceSearch(0);
            sortAdapter.setSelectedItems(new HashSet<>());
            Set<Integer> defaultSort = new HashSet<>();
            defaultSort.add(R.id.sort_by_default);
            sortAdapter.setSelectedItems(defaultSort);
            selectedSortId[0] = R.id.sort_by_default;
        });

        searchButton.setOnClickListener(v -> {
            searchKey = mSearchBar.getText().toString();
            if (searchKey == null) searchKey = "";
            searchKey = searchKey.trim();

            int searchOption = advanceSearchTable.getAdvanceSearch();
            Set<Integer> categories = categoryTable.getSelectedCategories();
            int sortId = selectedSortId[0];

        if (mSearchDialog != null) {
        if (mSearchDialog != null) {
            mSearchDialog.dismiss();
        }
        }
            mSearchMode = false;

            mProgressView.setVisibility(View.VISIBLE);
            if (mRecyclerView != null) {
                mRecyclerView.setVisibility(View.GONE);
            }
            updateForLabel();

            DownloadListInfosExecutor executor = new DownloadListInfosExecutor(mBackList, "");
            executor.setDownloadSearchingListener(this);
            executor.executeAdvancedSearch(searchKey, searchOption, categories, sortId);
            searching = true;
        });
    }

    private void gotoAdvancedFilter(Context context) {
        LayoutInflater layoutInflater = LayoutInflater.from(context);

        LinearLayout linearLayout = (LinearLayout) layoutInflater.inflate(R.layout.dialog_sort_filter_v2, null);

        // Category table - set all selected by default
        DownloadCategoryTable categoryTable = linearLayout.findViewById(R.id.category_table);
        categoryTable.setAllSelected();

        // Status filter setup
        RecyclerView statusRecyclerView = linearLayout.findViewById(R.id.status_recycler_view);
        statusRecyclerView.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(context));
        List<String> statusNames = new ArrayList<>();
        List<Integer> statusIds = new ArrayList<>();
        statusNames.add(context.getString(R.string.download_state_none));
        statusIds.add(DownloadInfo.STATE_NONE);
        statusNames.add(context.getString(R.string.download_state_wait));
        statusIds.add(DownloadInfo.STATE_WAIT);
        statusNames.add(context.getString(R.string.download_state_downloading));
        statusIds.add(DownloadInfo.STATE_DOWNLOAD);
        statusNames.add(context.getString(R.string.download_state_downloaded));
        statusIds.add(DownloadInfo.STATE_FINISH);
        statusNames.add(context.getString(R.string.download_state_failed));
        statusIds.add(DownloadInfo.STATE_FAILED);
        statusNames.add(context.getString(R.string.download_state_relay_download));
        statusIds.add(DownloadInfo.STATE_RELAY_DOWNLOAD);
        final CheckboxAdapter statusAdapter = new CheckboxAdapter(statusNames, statusIds);
        statusRecyclerView.setAdapter(statusAdapter);
        // Select all by default
        Set<Integer> allStatuses = new HashSet<>(statusIds);
        statusAdapter.setSelectedItems(allStatuses);

        Button selectAllStatusBtn = linearLayout.findViewById(R.id.select_all_status_button);
        Button selectNoneStatusBtn = linearLayout.findViewById(R.id.select_none_status_button);
        selectAllStatusBtn.setOnClickListener(v -> statusAdapter.setSelectedItems(allStatuses));
        selectNoneStatusBtn.setOnClickListener(v -> statusAdapter.setSelectedItems(new HashSet<>()));

        // Sort method setup
        RecyclerView sortRecyclerView = linearLayout.findViewById(R.id.sort_recycler_view);
        sortRecyclerView.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(context));
        List<String> sortNames = new ArrayList<>();
        List<Integer> sortIds = new ArrayList<>();
        sortNames.add(context.getString(R.string.default_sort));
        sortIds.add(R.id.sort_by_default);
        sortNames.add(context.getString(R.string.sort_by_gallery_id_asc));
        sortIds.add(R.id.sort_by_gallery_id_asc);
        sortNames.add(context.getString(R.string.sort_by_gallery_id_desc));
        sortIds.add(R.id.sort_by_gallery_id_desc);
        sortNames.add(context.getString(R.string.sort_by_create_time_asc));
        sortIds.add(R.id.sort_by_create_time_asc);
        sortNames.add(context.getString(R.string.sort_by_create_time_desc));
        sortIds.add(R.id.sort_by_create_time_desc);
        sortNames.add(context.getString(R.string.sort_by_rating_asc));
        sortIds.add(R.id.sort_by_rating_asc);
        sortNames.add(context.getString(R.string.sort_by_rating_desc));
        sortIds.add(R.id.sort_by_rating_desc);
        sortNames.add(context.getString(R.string.sort_by_name_asc));
        sortIds.add(R.id.sort_by_name_asc);
        sortNames.add(context.getString(R.string.sort_by_name_desc));
        sortIds.add(R.id.sort_by_name_desc);
        sortNames.add(context.getString(R.string.sort_by_file_size_asc));
        sortIds.add(R.id.sort_by_file_size_asc);
        sortNames.add(context.getString(R.string.sort_by_file_size_desc));
        sortIds.add(R.id.sort_by_file_size_desc);
        final CheckboxAdapter sortAdapter = new CheckboxAdapter(sortNames, sortIds);
        sortAdapter.setMutuallyExclusive(true);
        sortRecyclerView.setAdapter(sortAdapter);

        // Time inputs
        EditText timeFromInput = linearLayout.findViewById(R.id.filter_time_from_input);
        EditText timeToInput = linearLayout.findViewById(R.id.filter_time_to_input);
        Button pickTimeFromBtn = linearLayout.findViewById(R.id.pick_time_from_button);
        Button pickTimeToBtn = linearLayout.findViewById(R.id.pick_time_to_button);

        final java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);

        pickTimeFromBtn.setOnClickListener(v -> {
            Calendar cal = Calendar.getInstance();
            new DatePickerDialog(context, (view, year, month, dayOfMonth) -> {
                cal.set(year, month, dayOfMonth);
                timeFromInput.setText(dateFormat.format(cal.getTime()));
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
        });

        pickTimeToBtn.setOnClickListener(v -> {
            Calendar cal = Calendar.getInstance();
            new DatePickerDialog(context, (view, year, month, dayOfMonth) -> {
                cal.set(year, month, dayOfMonth);
                timeToInput.setText(dateFormat.format(cal.getTime()));
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
        });

        // Size inputs
        EditText sizeFromInput = linearLayout.findViewById(R.id.filter_size_from_input);
        EditText sizeToInput = linearLayout.findViewById(R.id.filter_size_to_input);

        // Duplicate checkbox
        CheckBox duplicateCheckbox = linearLayout.findViewById(R.id.filter_duplicate_only_checkbox);

        // Rating filter
        RatingBar ratingFromBar = linearLayout.findViewById(R.id.filter_rating_from_bar);
        RatingBar ratingToBar = linearLayout.findViewById(R.id.filter_rating_to_bar);
        final float[] ratingFrom = {0f};
        final float[] ratingTo = {5f};
        RatingBar.OnRatingBarChangeListener ratingListener = (bar, rating, fromUser) -> {
            if (bar == ratingFromBar) {
                ratingFrom[0] = rating;
            } else {
                ratingTo[0] = rating;
            }
        };
        ratingFromBar.setOnRatingBarChangeListener(ratingListener);
        ratingToBar.setOnRatingBarChangeListener(ratingListener);

        // Page count inputs
        EditText pageFromInput = linearLayout.findViewById(R.id.filter_page_from_input);
        EditText pageToInput = linearLayout.findViewById(R.id.filter_page_to_input);

        final int[] selectedSortId = {R.id.sort_by_default};
        sortAdapter.setOnSelectionChangedListener(selected -> {
            if (!selected.isEmpty()) {
                selectedSortId[0] = selected.iterator().next();
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(linearLayout)
                .setCancelable(true)
                .show();

        Button resetButton = linearLayout.findViewById(R.id.reset_button);
        Button applyButton = linearLayout.findViewById(R.id.apply_button);

        resetButton.setOnClickListener(v -> {
            categoryTable.setAllSelected();
            statusAdapter.setSelectedItems(allStatuses);
            Set<Integer> defaultSort = new HashSet<>();
            defaultSort.add(R.id.sort_by_default);
            sortAdapter.setSelectedItems(defaultSort);
            selectedSortId[0] = R.id.sort_by_default;
            timeFromInput.setText("");
            timeToInput.setText("");
            sizeFromInput.setText("");
            sizeToInput.setText("");
            pageFromInput.setText("");
            pageToInput.setText("");
            duplicateCheckbox.setChecked(false);
            ratingFromBar.setRating(0f);
            ratingToBar.setRating(5f);
            ratingFrom[0] = 0f;
            ratingTo[0] = 5f;
        });

        applyButton.setOnClickListener(v -> {
            Set<Integer> categoryIds = categoryTable.getSelectedCategories();
            Set<Integer> selectedStatusIds = statusAdapter.getSelectedItems();
            int sortId = selectedSortId[0];

            Long timeFrom = parseTimeInput(timeFromInput.getText().toString());
            Long timeTo = parseTimeInput(timeToInput.getText().toString());
            Long sizeFrom = parseSizeInput(sizeFromInput.getText().toString());
            Long sizeTo = parseSizeInput(sizeToInput.getText().toString());
            Long pageFrom = parsePageInput(pageFromInput.getText().toString());
            Long pageTo = parsePageInput(pageToInput.getText().toString());
            boolean duplicateOnly = duplicateCheckbox.isChecked();
            float ratFrom = ratingFrom[0];
            float ratTo = ratingTo[0];

            dialog.dismiss();

            mProgressView.setVisibility(View.VISIBLE);
            if (mRecyclerView != null) {
                mRecyclerView.setVisibility(View.GONE);
            }

            updateForLabel();

            DownloadListInfosExecutor executor = new DownloadListInfosExecutor(mBackList, mDownloadManager);
            executor.setDownloadSearchingListener(this);
            cancelCurrentExecutor();
            executor.executeFilterAndSort(categoryIds, selectedStatusIds, sortId, timeFrom, timeTo, sizeFrom, sizeTo, pageFrom, pageTo, duplicateOnly, ratFrom, ratTo);
            mCurrentExecutor = executor;
            searching = true;
        });
    }

    private Long parseTimeInput(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date date = sdf.parse(text.trim());
            return date != null ? date.getTime() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Long parseSizeInput(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String s = text.trim().toUpperCase(Locale.US);
        try {
            long multiplier = 1;
            if (s.endsWith("GB")) {
                multiplier = 1024L * 1024 * 1024;
                s = s.substring(0, s.length() - 2).trim();
            } else if (s.endsWith("MB")) {
                multiplier = 1024L * 1024;
                s = s.substring(0, s.length() - 2).trim();
            } else if (s.endsWith("KB")) {
                multiplier = 1024L;
                s = s.substring(0, s.length() - 2).trim();
            } else if (s.endsWith("B")) {
                s = s.substring(0, s.length() - 1).trim();
            }
            return Long.parseLong(s) * multiplier;
        } catch (Exception e) {
            return null;
        }
    }

    private Long parsePageInput(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            return Long.parseLong(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private void onSearchDialogDismiss(DialogInterface dialog) {
        mSearchMode = false;
    }

    private void enterSearchMode(boolean animation) {
        if (mSearchMode || mSearchBar == null || mSearchBarMover == null) {
            return;
        }
        mSearchMode = true;
        mSearchBar.setState(SearchBar.STATE_SEARCH_LIST, animation);

        mSearchBarMover.returnSearchBarPosition(animation);

    }

    public void updateView() {
        if (mViewTransition != null) {
            if (mList == null || mList.size() == 0) {
                mViewTransition.showView(1);
            } else {
                mViewTransition.showView(0);
            }
        }
    }

    @Override
    public View onCreateDrawerView(LayoutInflater inflater,
                                   @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (downloadLabelDraw == null) {
            downloadLabelDraw = new DownloadLabelDraw(inflater, container, this);
        }

        return downloadLabelDraw.createView();
    }

    @Override
    public void onBackPressed() {
        if (null != mShowcaseView) {
            return;
        }

        if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
            mRecyclerView.outOfCustomChoiceMode();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onStartDragHandler() {
        // Lock right drawer
        setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT);
    }

    @Override
    public void onEndDragHandler() {
        // Restore right drawer
        if (null != mRecyclerView && !mRecyclerView.isInCustomChoice()) {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT);
        }
    }

    @Override
    public boolean onItemClick(EasyRecyclerView parent, View view, int position, long id) {
        Activity activity = getActivity2();
        MyEasyRecyclerView recyclerView = mRecyclerView;
        if (null == activity || null == recyclerView) {
            return false;
        }

        if (recyclerView.isInCustomChoice()) {
            recyclerView.toggleItemChecked(position);
            return true;
        } else {
            List<DownloadInfo> list = mList;
            if (list == null) {
                return false;
            }
            if (position < 0 || position >= list.size()) {
                return false;
            }

            DownloadInfo downloadInfo = list.get(positionInList(position));
            Intent intent = new Intent(activity, GalleryActivity.class);
            // Check if this is an imported archive
            if (downloadInfo.archiveUri != null && downloadInfo.archiveUri.startsWith("content://")) {
                // This is an imported archive, ensure URI permission is available
                Uri archiveUri = Uri.parse(downloadInfo.archiveUri);
                try {
                    // Test if we can access the URI
                    try (InputStream testStream = getEHContext().getContentResolver().openInputStream(archiveUri)) {
                        if (testStream == null) {
                            Toast.makeText(getEHContext(), R.string.archive_not_accessible, Toast.LENGTH_SHORT).show();
                            return true;
                        }
                    }
                } catch (SecurityException e) {
                    // Try to restore permission
                    try {
                        getEHContext().getContentResolver().takePersistableUriPermission(archiveUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ex) {
                        Toast.makeText(getEHContext(), R.string.archive_permission_lost, Toast.LENGTH_LONG).show();
                        Analytics.recordException(ex);
                        return true;
                    }
                } catch (Exception e) {
                    Toast.makeText(getEHContext(), R.string.archive_not_accessible, Toast.LENGTH_SHORT).show();
                    return true;
                }
                intent.setAction(Intent.ACTION_VIEW);
                intent.setData(archiveUri);
            } else {
                // This is a normal download, use ACTION_EH
                intent.setAction(GalleryActivity.ACTION_EH);
                intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, downloadInfo);
            }
//            startActivity(intent);
            galleryActivityLauncher.launch(intent);
            return true;
        }
    }

    @Override
    public boolean onItemLongClick(EasyRecyclerView parent, View view, int position, long id) {
        final Context context = getEHContext();
        final MainActivity activity = getActivity2();
        if (null == context || null == activity) {
            return false;
        }

        List<DownloadInfo> list = mList;
        if (list == null) {
            return false;
        }
        if (position < 0 || position >= list.size()) {
            return false;
        }

        DownloadInfo di = list.get(positionInList(position));
        if (di == null) {
            return true;
        }

        boolean favourited = di.favoriteSlot != -2;
        boolean isRelayDownload = di.state == DownloadInfo.STATE_RELAY_DOWNLOAD;

        CharSequence[] items;
        int[] icons;

        if (isRelayDownload) {
            // 接力下载状态：显示取消接力下载
            items = new CharSequence[]{
                    context.getString(R.string.read),
                    context.getString(favourited ? R.string.remove_from_favourites : R.string.add_to_favourites),
                    context.getString(R.string.relay_cancel_download),
                    context.getString(R.string.delete),
            };
            icons = new int[]{
                    R.drawable.v_book_open_x24,
                    favourited ? R.drawable.v_heart_broken_x24 : R.drawable.v_heart_x24,
                    R.drawable.v_send_dark_x24,
                    R.drawable.v_delete_x24,
            };
        } else {
            // 正常状态：显示接力到设备
            items = new CharSequence[]{
                    context.getString(R.string.read),
                    context.getString(favourited ? R.string.remove_from_favourites : R.string.add_to_favourites),
                    context.getString(R.string.relay_send_to_device),
                    context.getString(R.string.delete),
            };
            icons = new int[]{
                    R.drawable.v_book_open_x24,
                    favourited ? R.drawable.v_heart_broken_x24 : R.drawable.v_heart_x24,
                    R.drawable.v_send_dark_x24,
                    R.drawable.v_delete_x24,
            };
        }

        @SuppressLint("InflateParams") LinearLayout linearLayout = (LinearLayout) getLayoutInflater2().inflate(R.layout.gallery_item_dialog_coustom_title, null);

        linearLayout.setOnClickListener(l -> onItemClick(parent, view, position, id));

        LoadImageViewNew imageViewNew = linearLayout.findViewById(R.id.dialog_thumb);
        imageViewNew.load(EhCacheKeyFactory.getThumbKey(di.gid), di.thumb);
        imageViewNew.setOnClickListener(l -> onItemClick(parent, view, position, id));

        buildChipGroup(di, linearLayout.findViewById(R.id.tab_tag_flow));

        TextView textView = linearLayout.findViewById(R.id.title_text);
        textView.setText(EhUtils.getSuitableTitle(di));
        textView.setOnClickListener(l -> {
            AppHelper.copyPlainText(EhUtils.getSuitableTitle(di), getEHContext());
            Toast toast = Toast.makeText(getEHContext(), R.string.title_text_copied, Toast.LENGTH_SHORT);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        });

        new AlertDialog.Builder(getDialogContext())
                .setCustomTitle(linearLayout)
                .setAdapter(new SelectItemWithIconAdapter(context, items, icons), (dialog, which) -> {
                    switch (which) {
                        case 0: // Read
                            Intent intent = new Intent(activity, GalleryActivity.class);
                            intent.setAction(GalleryActivity.ACTION_EH);
                            intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, di);
                            galleryActivityLauncher.launch(intent);
                            break;
                        case 1: // Favorites
                            if (favourited) {
                                CommonOperations.removeFromFavorites(activity, di, new EhClient.Callback<Void>() {
                                    @Override
                                    public void onSuccess(Void result) {}
                                    @Override
                                    public void onFailure(Exception e) {}
                                    @Override
                                    public void onCancel() {}
                                });
                            } else {
                                CommonOperations.addToFavorites(activity, di, new EhClient.Callback<Void>() {
                                    @Override
                                    public void onSuccess(Void result) {}
                                    @Override
                                    public void onFailure(Exception e) {}
                                    @Override
                                    public void onCancel() {}
                                }, false);
                            }
                            break;
                        case 2: // Relay Download / Cancel Relay
                            if (isRelayDownload) {
                                // 取消接力下载，恢复为本机下载
                                if (mDownloadManager != null) {
                                    mDownloadManager.cancelRelayDownload(di.gid);
                                    Toast.makeText(context, R.string.relay_cancel_download, Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                // 接力到设备：检查已连接设备并弹出选择对话框
                                handleRelayDownload(activity, di);
                            }
                            break;
                        case 3: // Delete
                            new AlertDialog.Builder(getDialogContext())
                                    .setTitle(R.string.download_remove_dialog_title)
                                    .setMessage(getString(R.string.download_remove_dialog_message, di.title))
                                    .setPositiveButton(android.R.string.ok, (dialog1, which1) -> mDownloadManager.deleteDownload(di.gid))
                                    .show();
                            break;
                    }
                }).show();
        return true;
    }

    /**
     * 处理接力下载：检查已连接设备，弹出选择对话框，调用接力API
     */
    private void handleRelayDownload(MainActivity activity, DownloadInfo di) {
        // 获取已连接设备
        TransferClientManager clientManager = TransferClientManager.getInstance(getEHContext());
        if (clientManager == null || clientManager.getConnectedDevices().isEmpty()) {
            Toast.makeText(getEHContext(), R.string.relay_no_connected_devices, Toast.LENGTH_SHORT).show();
            return;
        }

        java.util.List<ConnectedDevice> devices = clientManager.getConnectedDevices();

        if (devices.size() == 1) {
            // 只有一台设备，直接确认
            showRelayConfirmDialog(activity, di, clientManager, devices.get(0));
        } else {
            // 多台设备，弹出选择对话框
            String[] deviceNames = new String[devices.size()];
            for (int i = 0; i < devices.size(); i++) {
                ConnectedDevice d = devices.get(i);
                deviceNames[i] = d.getName() + " (" + d.getHost() + ":" + d.getPort() + ")";
            }
            new AlertDialog.Builder(getDialogContext())
                    .setTitle(R.string.relay_device_select)
                    .setItems(deviceNames, (dialog, which) -> {
                        showRelayConfirmDialog(activity, di, clientManager, devices.get(which));
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    /**
     * 显示接力确认对话框
     */
    private void showRelayConfirmDialog(MainActivity activity, DownloadInfo di,
                                         TransferClientManager clientManager, ConnectedDevice device) {
        String title = EhUtils.getSuitableTitle(di);
        new AlertDialog.Builder(getDialogContext())
                .setTitle(R.string.relay_confirm_relay)
                .setMessage(getString(R.string.relay_confirm_message, title, device.getName()))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    // 调用接力API
                    clientManager.createRelayTask(device, di, new TransferClientManager.RelayTaskCallback() {
                        @Override
                        public void onSuccess(String taskId) {
                            RelayTaskManager relayTaskManager = RelayTaskManager.getInstance(getEHContext());
                            relayTaskManager.createTask(
                                di.gid, di.token, di.title, di.titleJpn,
                                di.thumb, di.category, di.posted, di.uploader,
                                di.rating, di.pages,
                                null, null, null, 0,
                                device.getName(), device.getDeviceId(),
                                null, true);
                            if (mDownloadManager != null) {
                                mDownloadManager.setRelayDownload(di.gid);
                            }
                            Toast.makeText(getEHContext(), R.string.relay_task_created, Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onError(String error) {
                            Toast.makeText(getEHContext(), "Relay failed: " + error, Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void buildChipGroup(GalleryInfo gi, ChipGroup tagFlowLayout) {
        int colorTag = AttrResources.getAttrColor(getContext(), R.attr.tagBackgroundColor);
        if (null == gi.tgList) {
            String tagName = getString(R.string.gallery_list_no_preview_tag);
            @SuppressLint("InflateParams") Chip chip = (Chip) getLayoutInflater().inflate(R.layout.item_chip_tag, null);
            chip.setChipBackgroundColor(ColorStateList.valueOf(colorTag));
            chip.setTextColor(Color.WHITE);
            chip.setText(tagName);
            tagFlowLayout.addView(chip, 0);
            return;
        }
        for (int i = 0; i < gi.tgList.size(); i++) {
            String tagName = gi.tgList.get(i);
            @SuppressLint("InflateParams") Chip chip = (Chip) getLayoutInflater().inflate(R.layout.item_chip_tag, null);
            chip.setChipBackgroundColor(ColorStateList.valueOf(colorTag));
            chip.setTextColor(Color.WHITE);
            if (Settings.getShowTagTranslations()) {
                if (ehTags == null) {
                    ehTags = EhTagDatabase.getInstance(getContext());
                }
                chip.setText(TagTranslationUtil.getTagCNBody(tagName.split(":"), ehTags));
            } else {
                String[] tagSplit = tagName.split(":");
                chip.setText(tagSplit.length > 1 ? tagSplit[1] : tagSplit[0]);
            }
            tagFlowLayout.addView(chip, i);
        }
    }

    @SuppressLint("RtlHardcoded")
    @Override
    public void onExpand(boolean expanded) {
        if (null == mActionFabDrawable) {
            return;
        }

        if (expanded) {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT);
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT);
            mActionFabDrawable.setDelete(ANIMATE_TIME);
        } else {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.LEFT);
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT);
            mActionFabDrawable.setAdd(ANIMATE_TIME);
        }
    }

    @Override
    public void onClickPrimaryFab(FabLayout view, FloatingActionButton fab) {
        if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
            mRecyclerView.outOfCustomChoiceMode();
            return;
        }
        if (!view.isExpanded()) {
            view.toggle();
            return;
        }
        if (mRecyclerView != null) {
            mRecyclerView.intoCustomChoiceMode();
        }
    }

    private void setupFabContentDescriptions() {
        if (mFabLayout == null) {
            return;
        }
        Context context = getEHContext();
        if (context == null) {
            return;
        }
        int totalFabs = mFabLayout.getSecondaryFabCount();
        for (int i = 0; i < totalFabs; i++) {
            FloatingActionButton fab = mFabLayout.getSecondaryFabAt(i);
            if (fab == null) {
                continue;
            }
            String desc = null;
            switch (i) {
                case 0 -> desc = context.getString(R.string.multi_select_mode);
                case 1 -> desc = context.getString(R.string.select_all);
                case 2 -> desc = context.getString(R.string.fab_start);
                case 3 -> desc = context.getString(R.string.pause);
                case 4 -> desc = context.getString(R.string.delete);
                case 5 -> desc = context.getString(R.string.move_download);
                case 6 -> desc = context.getString(R.string.random_download);
                case 7 -> desc = context.getString(R.string.drag_mode);
                case 8 -> desc = context.getString(R.string.fab_compress);
                case 9 -> desc = context.getString(R.string.refresh);
            }
            if (desc != null) {
                fab.setContentDescription(desc);
            }
        }
    }

    @Override
    public void onClickSecondaryFab(FabLayout view, FloatingActionButton fab, int position) {
        Context context = getEHContext();
        Activity activity = getActivity2();
        MyEasyRecyclerView recyclerView = mRecyclerView;
        if (null == context || null == activity || null == recyclerView) {
            return;
        }

        if (0 == position) {
            recyclerView.intoCustomChoiceMode();
        } else if (1 == position) {
            recyclerView.checkAll();
        } else {
            List<DownloadInfo> list = mList;
            if (list == null) {
                return;
            }

            LongList gidList = null;
            List<DownloadInfo> downloadInfoList = null;
            boolean collectGid = position == 2 || position == 3 || position == 4; // Start, Stop, Delete
            boolean collectDownloadInfo = position == 4 || position == 5 || position == 8; // Delete, Move, or Compress
            if (collectGid) {
                gidList = new LongList();
            }
            if (collectDownloadInfo) {
                downloadInfoList = new LinkedList<>();
            }

            SparseBooleanArray stateArray = recyclerView.getCheckedItemPositions();
            for (int i = 0, n = stateArray.size(); i < n; i++) {
                if (stateArray.valueAt(i)) {
                    DownloadInfo info = list.get(positionInList(stateArray.keyAt(i)));
                    if (collectDownloadInfo) {
                        downloadInfoList.add(info);
                    }
                    if (collectGid) {
                        gidList.add(info.gid);
                    }
                }
            }

            switch (position) {
                case 2: { // Start
                    if (gidList.isEmpty()) {
                        break;
                    }
                    Intent intent = new Intent(activity, DownloadService.class);
                    intent.setAction(DownloadService.ACTION_START_RANGE);
                    intent.putExtra(DownloadService.KEY_GID_LIST, gidList);
                    activity.startService(intent);
                    // Cancel check mode
                    recyclerView.outOfCustomChoiceMode();
                    break;
                }
                case 3: { // Stop
                    if (gidList.isEmpty()) {
                        break;
                    }
                    if (null != mDownloadManager) {
                        mDownloadManager.stopRangeDownload(gidList);
                    }
                    // Cancel check mode
                    recyclerView.outOfCustomChoiceMode();
                    break;
                }
                case 4: { // Delete
                    if (downloadInfoList.isEmpty()) {
                        break;
                    }
                    CheckBoxDialogBuilder builder = new CheckBoxDialogBuilder(context,
                            getString(R.string.download_remove_dialog_message_2, gidList.size()),
                            getString(R.string.download_remove_dialog_check_text),
                            Settings.getRemoveImageFiles());
                    DeleteRangeDialogHelper helper = new DeleteRangeDialogHelper(
                            downloadInfoList, gidList, builder);
                    builder.setTitle(R.string.download_remove_dialog_title)
                            .setPositiveButton(android.R.string.ok, helper)
                            .show();
                    break;
                }
                case 5: {// Move
                    if (downloadInfoList.isEmpty()) {
                        break;
                    }
                    List<DownloadLabel> labelRawList = EhApplication.getDownloadManager(context).getLabelList();
                    List<String> labelList = new ArrayList<>(labelRawList.size() + 1);
                    labelList.add(getString(R.string.default_download_label_name));
                    for (int i = 0, n = labelRawList.size(); i < n; i++) {
                        labelList.add(labelRawList.get(i).getLabel());
                    }
                    String[] labels = labelList.toArray(new String[labelList.size()]);

                    MoveDialogHelper helper = new MoveDialogHelper(labels, downloadInfoList);

                    new AlertDialog.Builder(context)
                            .setTitle(R.string.download_move_dialog_title)
                            .setItems(labels, helper)
                            .show();
                    break;
                }
                case 6:
                    if (mList == null || mList.isEmpty()) {
                        return;
                    }
                    onClickPrimaryFab(mFabLayout, null);
                    viewRandom();
                    break;
                case 7:
                    setDragEnable(fab);
                    break;
                case 8: { // Compress
                    if (downloadInfoList == null || downloadInfoList.isEmpty()) {
                        Toast.makeText(context, R.string.compress_no_downloaded_galleries, Toast.LENGTH_SHORT).show();
                        break;
                    }
                    List<DownloadInfo> completedList = new ArrayList<>();
                    for (DownloadInfo info : downloadInfoList) {
                        if (info.state == DownloadInfo.STATE_FINISH) {
                            completedList.add(info);
                        }
                    }
                    if (completedList.isEmpty()) {
                        Toast.makeText(context, R.string.compress_no_downloaded_galleries, Toast.LENGTH_SHORT).show();
                        break;
                    }
                    CompressSelectedGalleriesTask task = new CompressSelectedGalleriesTask(context, completedList);
                    BackgroundTaskManager.getInstance().submitBackgroundTask(task);
                    recyclerView.outOfCustomChoiceMode();
                    break;
                }
            }
        }
    }

    private void setDragEnable(FloatingActionButton fab) {
        DRAG_ENABLE = !DRAG_ENABLE;
        Settings.setDragDownloadGallery(DRAG_ENABLE);
        Context context = getEHContext();
        if (null == context) return;
        if (DRAG_ENABLE) {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_x24, context.getTheme()));
        } else {
            fab.setImageDrawable(ResourcesCompat.getDrawable(getResources(), R.drawable.v_mobile_hand_left_off_x24, context.getTheme()));
        }
//        mDragDropManager.cancelDrag(dragEnable);
    }

    private void viewRandom() {
        List<DownloadInfo> list = mList;
        if (list == null) {
            return;
        }
        int position = (int) (Math.random() * list.size());
        if (position < 0 || position >= list.size()) {
            return;
        }
        Activity activity = getActivity2();
        if (null == activity || null == mRecyclerView) {
            return;
        }

        Intent intent = new Intent(activity, GalleryActivity.class);
        DownloadInfo downloadInfo = list.get(position);
        if (downloadInfo.archiveUri != null && downloadInfo.archiveUri.startsWith("content://")) {
            intent.setAction(GalleryActivity.ACTION_EH);
            intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, downloadInfo);
            intent.setData(Uri.parse(downloadInfo.archiveUri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent.setAction(GalleryActivity.ACTION_EH);
            intent.putExtra(GalleryActivity.KEY_GALLERY_INFO, downloadInfo);
        }
        galleryActivityLauncher.launch(intent);
    }

    @Override
    public void onAdd(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, int position) {
        if (mOriginalAdapter != null && mDownloadManager != null) {
            mOriginalAdapter.setWaitList(mDownloadManager.getWaitList());
        }
        if (mList != list) {
            updateForLabel();
            filterByCategory();
            if (mAdapter != null) {
                mAdapter.notifyDataSetChanged();
            }
            if (downloadLabelDraw != null) {
                downloadLabelDraw.updateDownloadLabels();
            }
            updateView();
            return;
        }
        if (mAdapter != null) {
            mAdapter.notifyItemInserted(position);
        }
        if (downloadLabelDraw != null) {
            downloadLabelDraw.updateDownloadLabels();
        }
        updateView();
    }

    @Override
    public void onReplace(@NonNull DownloadInfo newInfo, @NonNull DownloadInfo oldInfo) {
        if (mList == null) {
            return;
        }
        updateForLabel();
        updateView();

        int index = mList.indexOf(newInfo);
        if (index >= 0 && mAdapter != null) {
//            mSpiderInfoMap.put(info.gid,getSpiderInfo(info));
            mAdapter.notifyItemChanged(listIndexInPage(index));
        }
        List<DownloadInfo> infos = new ArrayList<>();
        infos.add(newInfo);
        DownloadSpiderInfoExecutor executor = new DownloadSpiderInfoExecutor(infos, this::spiderInfoResultCallBack);
        executor.execute();
    }

    @Override
    public void onUpdate(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, LinkedList<DownloadInfo> mWaitList) {
        if (mList != list && !mList.contains(info)) {
            return;
        }
        if (mOriginalAdapter != null) {
            mOriginalAdapter.setWaitList(mWaitList);
        }
        int index = mList.indexOf(info);
        if (index >= 0 && mAdapter != null) {
            mAdapter.notifyItemChanged(listIndexInPage(index));
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onUpdateAll() {
        if (mOriginalAdapter != null && mDownloadManager != null) {
            mOriginalAdapter.setWaitList(mDownloadManager.getWaitList());
        }
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onReload() {
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
        updateView();
    }

    @Override
    public void onChange() {
        mLabel = null;
        updateForLabel();
        updateView();
    }

    @Override
    public void onRenameLabel(String from, String to) {
        if (!ObjectUtils.equal(mLabel, from)) {
            return;
        }

        mLabel = to;
        updateForLabel();
        updateView();
    }

    @Override
    public void onRemove(@NonNull DownloadInfo info, @NonNull List<DownloadInfo> list, int position) {
        if (mList != list) {
            updateForLabel();
            filterByCategory();
            if (mAdapter != null) {
                mAdapter.notifyDataSetChanged();
            }
            updateView();
            return;
        }
        if (mAdapter != null) {
            mAdapter.notifyItemRemoved(listIndexInPage(position));
        }
        updateView();
    }

    @Override
    public void onUpdateLabels() {
        // TODO
    }

    @Nullable
    public DownloadManager getMDownloadManager() {
        return mDownloadManager;
    }

    // DownloadAdapterCallback 接口实现
    @Override
    public int getIndexPage() {
        return indexPage;
    }

    @Override
    public int getPageSize() {
        return pageSize;
    }

    @Override
    public int getPaginationSize() {
        return paginationSize;
    }

    @Override
    public boolean isCanPagination() {
        return canPagination;
    }

    @Override
    public int positionInList(int position) {
        if (mList != null && mList.size() > paginationSize && canPagination) {
            return position + pageSize * (indexPage - 1);
        }
        return position;
    }

    @Override
    public int listIndexInPage(int position) {
        if (mList != null && mList.size() > paginationSize && canPagination) {
            return position % pageSize;
        }
        return position;
    }

    @Override
    public List<DownloadInfo> getList() {
        return mList;
    }

    @Override
    public Map<Long, SpiderInfo> getSpiderInfoMap() {
        return mSpiderInfoMap;
    }

    @Override
    public DownloadManager getDownloadManager() {
        return mDownloadManager;
    }

    @Override
    public MyEasyRecyclerView getRecyclerView() {
        return mRecyclerView;
    }


    private static void deleteFileAsync(UniFile... files) {
        new AsyncTask<UniFile, Void, Void>() {
            @Override
            protected Void doInBackground(UniFile... params) {
                for (UniFile file : params) {
                    if (file != null) {
                        file.delete();
                    }
                }
                return null;
            }
        }.executeOnExecutor(IoThreadPoolExecutor.Companion.getInstance(), files);
    }

    private static void deleteGalleryFilesAsync(List<? extends GalleryInfo> galleryInfoList) {
        new AsyncTask<List<? extends GalleryInfo>, Void, Void>() {
            @Override
            protected Void doInBackground(List<? extends GalleryInfo>... params) {
                for (GalleryInfo info : params[0]) {
                    UniFile file = getGalleryDownloadDir(info);
                    EhDB.removeDownloadDirname(info.gid);
                    if (file != null) {
                        file.delete();
                    }
                }
                return null;
            }
        }.executeOnExecutor(IoThreadPoolExecutor.Companion.getInstance(), galleryInfoList);
    }

    @Override
    public void onClickTitle() {
        if (!mSearchMode) {
            enterSearchMode(true);
        }
    }

    @Override
    public void onClickLeftIcon() {

    }

    @Override
    public void onClickRightIcon() {
        mSearchBar.applySearch(true);
    }

    @Override
    public void onSearchEditTextClick() {

    }


    @Override
    public void onApplySearch(String query) {
        searchKey = query;
        mSearchBar.hideKeyBoard();
        searching = true;
        startSearching();
    }

    protected void startSearching() {
        mProgressView.setVisibility(View.VISIBLE);
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(View.GONE);
        }

        if (mSearchMode) {
            mSearchMode = false;
            mSearchBar.setTitle(searchKey);
            mSearchBar.setState(SearchBar.STATE_NORMAL);
        }

        mSearchDialog.dismiss();

        updateForLabel();

        DownloadListInfosExecutor executor = new DownloadListInfosExecutor(mBackList, searchKey);

        executor.setDownloadSearchingListener(this);

        cancelCurrentExecutor();
        executor.executeSearching();
        mCurrentExecutor = executor;
    }

    private void cancelCurrentExecutor() {
        if (mCurrentExecutor != null) {
            mCurrentExecutor.cancel();
            mCurrentExecutor = null;
        }
    }

    private void gotoFilterAndSort(int id) {
        mLastFilterSortId = id;
        mProgressView.setVisibility(View.VISIBLE);
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(View.GONE);
        }

        updateForLabel();

        DownloadListInfosExecutor executor = new DownloadListInfosExecutor(mBackList, mDownloadManager);

        executor.setDownloadSearchingListener(this);

        cancelCurrentExecutor();
        executor.executeFilterAndSort(id);
        mCurrentExecutor = executor;
    }

    private void updateAdapter() {
        // 检查 Fragment 是否已附加，如果未附加则延迟创建适配器
        if (!isAdded()) {
            return;
        }
        mOriginalAdapter = new DownloadAdapter(this, this);
        mOriginalAdapter.setHasStableIds(true);
        // 避免重复创建包装适配器，直接使用原始适配器
        mAdapter = mOriginalAdapter;
        if (mRecyclerView != null) {
            mRecyclerView.setAdapter(mAdapter);
        }
        // 更新分页监听器中的适配器引用，避免分页变化时操作旧的适配器
        if (myPageChangeListener != null) {
            myPageChangeListener.setAdapter(mAdapter);
            myPageChangeListener.setIndexPage(1);
        }
    }

    @Override
    public void onSearchEditTextBackPressed() {
        if (mSearchMode) {
            mSearchMode = false;
        }
        mSearchBar.setState(SearchBar.STATE_NORMAL, true);
    }

    @Override
    public void onClickAdvance() {
    }

    @Override
    public void onStateChange(SearchBar searchBar, int newState, int oldState, boolean animation) {

    }

    @Override
    public boolean isValidView(RecyclerView recyclerView) {
        return false;
    }

    @Nullable
    @Override
    public RecyclerView getValidRecyclerView() {
        return mRecyclerView;
    }

    @Override
    public boolean forceShowSearchBar() {
        return false;
    }

    @Override
    public void onDownloadSearchSuccess(List<DownloadInfo> list) {
        if (!isAdded()) {
            return;
        }
        mList = list;
        mBackList = new ArrayList<>(list);
        indexPage = 1;
        updateAdapter();
        updateTitle();
        updatePaginationIndicator();
        updateView();
        mProgressView.setVisibility(View.GONE);
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(View.VISIBLE);
        }
        searching = false;
        queryUnreadSpiderInfo();
    }

    @Override
    public void onDownloadListHandleSuccess(List<DownloadInfo> list) {
        if (!isAdded()) {
            return;
        }
        mList = list;
        mBackList = new ArrayList<>(list);
        indexPage = 1;
        updateAdapter();
        updateTitle();
        updatePaginationIndicator();
        updateView();
        mProgressView.setVisibility(View.GONE);
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(View.VISIBLE);
        }
        queryUnreadSpiderInfo();
    }

    @Override
    public void onDownloadSearchFailed(List<DownloadInfo> list) {
        if (!isAdded()) {
            return;
        }
        Toast.makeText(getEHContext(), R.string.download_searching_failed, Toast.LENGTH_LONG).show();
        mList = list;
        indexPage = 1;
        updateAdapter();
        updateTitle();
        updatePaginationIndicator();
        updateView();
        mProgressView.setVisibility(View.GONE);
        if (mRecyclerView != null) {
            mRecyclerView.setVisibility(View.VISIBLE);
        }
        searching = false;
        queryUnreadSpiderInfo();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void updateReadProcess(ActivityResult result) {
        if (result.getResultCode() == LOCAL_GALLERY_INFO_CHANGE) {
            Intent data = result.getData();
            if (data != null) {
                GalleryInfo info = data.getParcelableExtra("info");

                // Check if this is an imported archive - skip SpiderInfo processing
                boolean isImportedArchive = false;
                if (info instanceof DownloadInfo downloadInfo) {
                    isImportedArchive = downloadInfo.archiveUri != null &&
                            downloadInfo.archiveUri.startsWith("content://");
                }

                if (!isImportedArchive && info != null) {
                    // Only process SpiderInfo for regular downloads, not imported archives
                    mSpiderInfoMap.remove(info.gid);
                    SpiderInfo spiderInfo = getSpiderInfo(info);
                    if (spiderInfo != null) {
                        mSpiderInfoMap.put(info.gid, spiderInfo);
                    }
                }

//                mSpiderInfoMap.remove(info.gid);
//                SpiderInfo spiderInfo = getSpiderInfo(info);
                int position = -1;
                if (mList == null || mAdapter == null || info == null) {
                    return;
                }
                for (int i = 0; i < mList.size(); i++) {
                    if (mList.get(i).gid == info.gid) {
                        position = listIndexInPage(i);
                        break;
                    }
                }
                if (position != -1) {
                    mAdapter.notifyItemChanged(position);
                } else {
                    mAdapter.notifyDataSetChanged();
                }

            }
        }
    }

    private static final int SPIDER_INFO_QUERY_BATCH_LIMIT = 200;

    private void queryUnreadSpiderInfo() {
        if (mList == null) {
            return;
        }
        List<DownloadInfo> requestList = new ArrayList<>();
        for (int i = 0; i < mList.size(); i++) {
            DownloadInfo info = mList.get(i);
            if (!mSpiderInfoMap.containsKey(info.gid) || mSpiderInfoMap.get(info.gid) == null) {
                requestList.add(info);
                if (requestList.size() >= SPIDER_INFO_QUERY_BATCH_LIMIT) {
                    break;
                }
            }
        }
        if (requestList.isEmpty()) {
            return;
        }
        DownloadSpiderInfoExecutor executor = new DownloadSpiderInfoExecutor(requestList, this::spiderInfoResultCallBack);
        executor.execute();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void spiderInfoResultCallBack(Map<Long, SpiderInfo> resultMap) {
        mSpiderInfoMap.putAll(resultMap);
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void updateDownloadLabels(SomethingNeedRefresh somethingNeedRefresh) {
        if (somethingNeedRefresh.isDownloadLabelDrawNeed()) {
            downloadLabelDraw.updateDownloadLabels();
        }
    }


    @SuppressLint("NotifyDataSetChanged")
    private void initPage(int position) {
        if (mList != null && mList.size() > paginationSize && canPagination) {
            indexPage = position / pageSize + 1;
        }
        doNotScroll = true;
        if (mPaginationIndicator != null) {
            mPaginationIndicator.skip2Pos(indexPage);
        }
        mRecyclerView.scrollToPosition(listIndexInPage(position));
    }


    private int getPageSizePos(int pageSize) {
        int index = 0;
        for (int i = 0; i < perPageCountChoices.length; i++) {
            if (pageSize == perPageCountChoices[i]) {
                index = i;
                break;
            }
        }
        return index;
    }

    private int categoryToSpinnerPos(int category) {
        switch (category) {
            case EhConfig.DOUJINSHI:    return 1;
            case EhConfig.MANGA:        return 2;
            case EhConfig.ARTIST_CG:    return 3;
            case EhConfig.GAME_CG:      return 4;
            case EhConfig.WESTERN:      return 5;
            case EhConfig.NON_H:        return 6;
            case EhConfig.IMAGE_SET:    return 7;
            case EhConfig.COSPLAY:      return 8;
            case EhConfig.ASIAN_PORN:   return 9;
            case EhConfig.MISC:         return 10;
            default:                    return 0;
        }
    }

    private void importLocalArchive() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip",
                "application/x-zip-compressed",
                "application/x-rar-compressed",
                "application/vnd.rar",
                "application/x-rar",
                "application/rar",
                "application/x-cbz",
                "application/x-cbr"
        });
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // CRITICAL: Add flags to enable persistent URI permissions
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        try {
            filePickerLauncher.launch(Intent.createChooser(intent, getString(R.string.import_archive_title)));
        } catch (Exception e) {
            Context context = getEHContext();
            if (context != null) {
                Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void repairThumbnails() {
        Context context = getEHContext();
        Activity activity = getActivity2();
        if (context == null || activity == null) return;

        new AlertDialog.Builder(activity)
                .setTitle(R.string.repair_thumbnail_menu)
                .setMessage(R.string.repair_thumbnail_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    com.hippo.ehviewer.task.RepairDownloadedThumbnailTask task =
                            new com.hippo.ehviewer.task.RepairDownloadedThumbnailTask(context, true);
                    com.hippo.ehviewer.BackgroundTaskManager.getInstance().submitBackgroundTask(task);
                    Toast.makeText(context, R.string.repair_thumbnail_start, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void handleSelectedFile(ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            return;
        }

        Uri uri = result.getData().getData();
        if (uri == null) {
            return;
        }

        Context context = getEHContext();
        if (context == null) {
            return;
        }

        // CRITICAL: Request persistent URI permission IMMEDIATELY when file is selected
        // This is the key to solving the permission loss issue after app restart
        try {
            context.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Log.d(TAG, "Successfully obtained persistent URI permission for: " + uri);
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to obtain persistent URI permission for: " + uri, e);
            Toast.makeText(context, R.string.archive_permission_lost, Toast.LENGTH_LONG).show();
            return;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error when obtaining URI permission for: " + uri, e);
            Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        // Show processing dialog
        Toast.makeText(context, R.string.import_archive_processing, Toast.LENGTH_LONG).show();

        // Process the archive file in background
        Thread thread = new Thread(() -> processArchiveFile(uri));
        thread.setDaemon(true);
        thread.start();
    }

    private void processArchiveFile(Uri uri) {
        Context context = getEHContext();
        if (context == null) {
            return;
        }

        try {
            // Verify URI accessibility (permission should already be granted)
            try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
                if (inputStream == null) {
                    runOnUiThread(() ->
                            Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
                    );
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "Cannot access file even with persistent permission", e);
                runOnUiThread(() ->
                        Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
                );
                return;
            }

            // Get file name
            String fileName = getFileName(context, uri);
            if (fileName == null) {
                fileName = "imported_archive_" + System.currentTimeMillis();
            }

            // Validate file format
            if (!isValidArchiveFormat(fileName)) {
                runOnUiThread(() ->
                        Toast.makeText(context, R.string.import_archive_invalid_format, Toast.LENGTH_SHORT).show()
                );
                return;
            }

            // Create DownloadInfo for the archive
            DownloadInfo downloadInfo = createArchiveDownloadInfo(context, uri, fileName);
            if (downloadInfo == null) {
                runOnUiThread(() ->
                        Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
                );
                return;
            }

            // Check if already imported
            if (mDownloadManager != null && mDownloadManager.containDownloadInfo(downloadInfo.gid)) {
                runOnUiThread(() ->
                        Toast.makeText(context, R.string.import_archive_already_imported, Toast.LENGTH_SHORT).show()
                );
                return;
            }

            // Add to download manager
            if (mDownloadManager != null) {
                List<DownloadInfo> downloadList = new ArrayList<>();
                downloadList.add(downloadInfo);
                mDownloadManager.addDownload(downloadList);
                runOnUiThread(() -> {
                    Toast.makeText(context, R.string.import_archive_success, Toast.LENGTH_SHORT).show();
                    updateForLabel();
                    updateView();
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to process archive file", e);
            runOnUiThread(() ->
                    Toast.makeText(context, R.string.import_archive_failed, Toast.LENGTH_SHORT).show()
            );
        }
    }

    private boolean isValidArchiveFormat(String fileName) {
        if (fileName == null) return false;
        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".zip") || lowerName.endsWith(".rar") ||
                lowerName.endsWith(".cbz") || lowerName.endsWith(".cbr");
    }


    public void runOnUiThread(Runnable runnable) {
        Activity activity = getActivity2();
        if (activity != null) {
            activity.runOnUiThread(runnable);
        }
    }

    private DownloadInfo createArchiveDownloadInfo(Context context, Uri uri, String fileName) {
        try {
            DownloadInfo downloadInfo = new DownloadInfo();
            downloadInfo.gid = System.currentTimeMillis(); // Use timestamp as unique ID
            downloadInfo.token = "";
            downloadInfo.title = fileName.replaceAll("\\.[^.]*$", ""); // Remove extension
            downloadInfo.titleJpn = null;
            downloadInfo.thumb = null; // No thumbnail for imported archives
            downloadInfo.category = EhUtils.UNKNOWN; // Keep as UNKNOWN, will be handled in display logic
            downloadInfo.posted = null;
            downloadInfo.uploader = getString(R.string.local_archive);
            downloadInfo.rating = -1.0f; // Keep default rating to not affect other downloads
            downloadInfo.state = DownloadInfo.STATE_FINISH;
            downloadInfo.legacy = 0;
            downloadInfo.time = System.currentTimeMillis();
            downloadInfo.label = null;
            downloadInfo.total = 0; // Will be set by archive provider
            downloadInfo.finished = 0;

            // Store the URI in the archiveUri field - this is the key identifier
            downloadInfo.archiveUri = uri.toString();

            return downloadInfo;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create DownloadInfo", e);
            return null;
        }
    }

    private class DeleteDialogHelper implements DialogInterface.OnClickListener {

        private final GalleryInfo mGalleryInfo;
        private final CheckBoxDialogBuilder mBuilder;

        public DeleteDialogHelper(GalleryInfo galleryInfo, CheckBoxDialogBuilder builder) {
            mGalleryInfo = galleryInfo;
            mBuilder = builder;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (which != DialogInterface.BUTTON_POSITIVE) {
                return;
            }

            // Delete
            if (null != mDownloadManager) {
                mDownloadManager.deleteDownload(mGalleryInfo.gid);
            }

            // Delete image files
            boolean checked = mBuilder.isChecked();
            Settings.putRemoveImageFiles(checked);
            if (checked) {
                UniFile file = getExistingGalleryDownloadDir(mGalleryInfo);
                EhDB.removeDownloadDirname(mGalleryInfo.gid);
                if (file != null) {
                    deleteFileAsync(file);
                } else {
                    deleteGalleryFilesAsync(Collections.singletonList(mGalleryInfo));
                }
            }
        }
    }

    private class DeleteRangeDialogHelper implements DialogInterface.OnClickListener {

        private final List<DownloadInfo> mDownloadInfoList;
        private final LongList mGidList;
        private final CheckBoxDialogBuilder mBuilder;

        public DeleteRangeDialogHelper(List<DownloadInfo> downloadInfoList,
                                       LongList gidList, CheckBoxDialogBuilder builder) {
            mDownloadInfoList = downloadInfoList;
            mGidList = gidList;
            mBuilder = builder;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (which != DialogInterface.BUTTON_POSITIVE) {
                return;
            }

            // Cancel check mode
            if (mRecyclerView != null) {
                mRecyclerView.outOfCustomChoiceMode();
            }

            // Delete
            if (null != mDownloadManager) {
                mDownloadManager.deleteRangeDownload(mGidList);
            }

            // Delete image files
            boolean checked = mBuilder.isChecked();
            Settings.putRemoveImageFiles(checked);
            if (checked) {
                deleteGalleryFilesAsync(mDownloadInfoList);
            }
        }
    }

    private class MoveDialogHelper implements DialogInterface.OnClickListener {

        private final String[] mLabels;
        private final List<DownloadInfo> mDownloadInfoList;

        public MoveDialogHelper(String[] labels, List<DownloadInfo> downloadInfoList) {
            mLabels = labels;
            mDownloadInfoList = downloadInfoList;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            // Cancel check mode
            Context context = getEHContext();
            if (null == context) {
                return;
            }
            if (null != mRecyclerView) {
                mRecyclerView.outOfCustomChoiceMode();
            }

            String label;
            if (which == 0) {
                label = null;
            } else {
                label = mLabels[which];
            }
            EhApplication.getDownloadManager(context).changeLabel(mDownloadInfoList, label);
        }
    }

//    /**
//     * 更新thumb的可见性（拖拽功能已直接附加到thumb上）
//     * @param isSelectionMode 是否处于选择模式
//     */
//    private void updateThumbVisibility(boolean isSelectionMode) {
//        if (mRecyclerView == null) {
//            return;
//        }
//
//        for (int i = 0; i < mRecyclerView.getChildCount(); i++) {
//            RecyclerView.ViewHolder holder = mRecyclerView.getChildViewHolder(mRecyclerView.getChildAt(i));
//            if (holder instanceof DownloadAdapter.DownloadHolder) {
//                DownloadAdapter.DownloadHolder downloadHolder = (DownloadAdapter.DownloadHolder) holder;
//                // thumb 始终可见，拖拽功能已直接附加到thumb上
//                downloadHolder.thumb.setVisibility(View.VISIBLE);
//            }
//        }
//    }

    private class DownloadChoiceListener implements MyEasyRecyclerView.CustomChoiceListener {

        @Override
        public void onIntoCustomChoice(EasyRecyclerView view) {
            if (mRecyclerView != null) {
                mRecyclerView.setOnItemLongClickListener(null);
                mRecyclerView.setLongClickable(false);
            }
            if (mFabLayout != null) {
                mFabLayout.setExpanded(true);
            }
            if (mAdapter != null) {
                mAdapter.notifyDataSetChanged();
            }
            // Lock drawer
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT);
            setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT);

//            // 进入选择模式时，thumb保持可见（拖拽功能已直接附加到thumb上）
//            updateThumbVisibility(true);
        }

        @Override
        public void onOutOfCustomChoice(EasyRecyclerView view) {
            if (mRecyclerView != null) {
                mRecyclerView.setOnItemLongClickListener(DownloadsScene.this);
            }
            if (mFabLayout != null) {
                mFabLayout.setExpanded(false);
            }
            if (mAdapter != null) {
                mAdapter.notifyDataSetChanged();
            }
            // Unlock drawer
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.LEFT);
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT);

//            // 退出选择模式时，thumb保持可见（拖拽功能已直接附加到thumb上）
//            updateThumbVisibility(false);
        }

        @Override
        public void onItemCheckedStateChanged(EasyRecyclerView view, int position, long id, boolean checked) {
            if (view.getCheckedItemCount() == 0) {
                view.outOfCustomChoiceMode();
            }
        }
    }

    private void filterByCategory() {
        if (mBackList == null) {
            return;
        }
        if (mSelectedCategory == EhUtils.ALL_CATEGORY) {
            mList = new ArrayList<>(mBackList);
        } else {
            mList = new ArrayList<>();
            for (DownloadInfo info : mBackList) {
                if (info.category == mSelectedCategory) {
                    mList.add(info);
                }
            }
        }
        indexPage = 1;
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
        if (myPageChangeListener != null) {
            myPageChangeListener.setIndexPage(1);
        }
        updateTitle();
        updatePaginationIndicator();
        updateView();
        queryUnreadSpiderInfo();
    }
}
