package com.hippo.ehviewer.sync;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.DownloadedFileManager;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.callBack.DownloadSearchCallback;
import com.hippo.ehviewer.client.EhConfig;
import com.hippo.ehviewer.client.EhTagDatabase;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.GalleryTags;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.DownloadGalleryMetaHelper;
import com.hippo.ehviewer.local.LocalRatingManager;
import com.hippo.ehviewer.widget.AdvanceSearchTable;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.util.SearchDebugLog;
import com.hippo.unifile.UniFile;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadListInfosExecutor {
    private static final int sortByIdAsc = 1;
    private static final int sortByIdDesc = 2;
    private static final int sortByCreateTimeAsc = 3;
    private static final int sortByCreateTimeDesc = 4;
    private static final int sortByRatingAsc = 5;
    private static final int sortByRatingDesc = 6;
    private static final int sortByFileSizeAsc = 7;
    private static final int sortByFileSizeDesc = 8;


    private final String TAG = "DownloadSearchingExecutor";

    ExecutorService service = Executors.newSingleThreadExecutor();
    Handler handler = new Handler(Looper.getMainLooper());

    private volatile boolean mCancelled = false;
    private DownloadSearchCallback mDownloadSearchCallback;

    @Nullable
    private List<DownloadInfo> mList;

    private List<DownloadInfo> resultList;

    private final String mSearchKey;

    @Nullable
    private final Context mContext;

    private DownloadManager mDownloadManager;
    private final Map<Long, Long> mGalleryTimeCache = new HashMap<>();

    public DownloadListInfosExecutor(@Nullable List<DownloadInfo> mList, String searchKey) {
        this(mList, searchKey, null);
    }

    public DownloadListInfosExecutor(@Nullable List<DownloadInfo> mList, String searchKey,
                                     @Nullable Context context) {
        this.mList = mList;
        this.mSearchKey = searchKey;
        this.mContext = context != null ? context.getApplicationContext() : null;
    }

    public DownloadListInfosExecutor(@Nullable List<DownloadInfo> mList, DownloadManager downloadManager) {
        this.mList = mList;
        this.mSearchKey = "";
        this.mContext = null;
        mDownloadManager = downloadManager;
    }

    public void setDownloadSearchingListener(DownloadSearchCallback downloadSearchCallback) {
        mDownloadSearchCallback = downloadSearchCallback;
    }

    public void cancel() {
        mCancelled = true;
        handler.removeCallbacksAndMessages(null);
    }

    public void shutdown() {
        cancel();
        service.shutdownNow();
    }

    public void executeSearching() {
        mCancelled = false;
        service.execute(() -> {
            if (mCancelled) return;
            resultList = searchingInBackground();

            handler.post(() -> {
                if (mCancelled || mDownloadSearchCallback == null) {
                    return;
                }
                if (!mCancelled) {
                    mDownloadSearchCallback.onDownloadSearchSuccess(resultList);
                }
            });
        });
    }

    @SuppressLint("NonConstantResourceId")
    public void executeFilterAndSort(int id) {
        mCancelled = false;
        service.execute(() -> {
            if (mCancelled) return;
            List<DownloadInfo> safeList = new ArrayList<>(mList != null ? mList : new ArrayList<>());
            switch (id) {

                case R.id.download_done:
                    resultList = filterDownloadState(safeList, DownloadInfo.STATE_FINISH);
                    break;
                case R.id.not_started:
                    resultList = filterDownloadState(safeList, DownloadInfo.STATE_NONE);
                    break;
                case R.id.waiting:
                    resultList = filterDownloadState(safeList, DownloadInfo.STATE_WAIT);
                    break;
                case R.id.downloading:
                    resultList = filterDownloadState(safeList, DownloadInfo.STATE_DOWNLOAD);
                    break;
                case R.id.failed:
                    resultList = filterDownloadState(safeList, DownloadInfo.STATE_FAILED);
                    break;
                case R.id.relay_download:
                    resultList = filterDownloadState(safeList, DownloadInfo.STATE_RELAY_DOWNLOAD);
                    break;
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
                case R.id.sort_by_remaining_pages_asc:
                case R.id.sort_by_remaining_pages_desc:
                    resultList = sortByType(safeList, id);
                    break;
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
                    resultList = filterDownloadKind(safeList, id);
                    break;
                case R.id.all:
                case R.id.sort_by_default:
                default:
                    resultList = safeList;
                    break;
            }

            handler.post(() -> {
                if (mCancelled || mDownloadSearchCallback == null) {
                    return;
                }
                if (!mCancelled) {
                    mDownloadSearchCallback.onDownloadSearchSuccess(resultList);
                }
            });
        });
    }

    // 新增方法：同时应用状态过滤和排序
    public void executeFilterAndSort(int statusId, int sortId) {
        Log.d("DownloadListInfos", "executeFilterAndSort: 开始, statusId=" + statusId + ", sortId=" + sortId);
        Log.d("DownloadListInfos", "executeFilterAndSort: 输入列表大小=" + (mList != null ? mList.size() : 0));
        
        service.execute(() -> {
            if (mCancelled) return;
            List<DownloadInfo> safeList = new ArrayList<>(mList != null ? mList : new ArrayList<>());
            // 先应用状态过滤
            List<DownloadInfo> filteredList = safeList;
            if (statusId != R.id.all) {
                Log.d("DownloadListInfos", "executeFilterAndSort: 应用状态过滤, statusId=" + statusId);
                switch (statusId) {
                    case R.id.download_done:
                        filteredList = filterDownloadState(safeList, DownloadInfo.STATE_FINISH);
                        break;
                    case R.id.not_started:
                        filteredList = filterDownloadState(safeList, DownloadInfo.STATE_NONE);
                        break;
                    case R.id.waiting:
                        filteredList = filterDownloadState(safeList, DownloadInfo.STATE_WAIT);
                        break;
                    case R.id.downloading:
                        filteredList = filterDownloadState(safeList, DownloadInfo.STATE_DOWNLOAD);
                        break;
                    case R.id.failed:
                        filteredList = filterDownloadState(safeList, DownloadInfo.STATE_FAILED);
                        break;
                    case R.id.relay_download:
                        filteredList = filterDownloadState(safeList, DownloadInfo.STATE_RELAY_DOWNLOAD);
                        break;
                    default:
                        filteredList = safeList;
                        break;
                }
                Log.d("DownloadListInfos", "executeFilterAndSort: 状态过滤完成，列表大小=" + filteredList.size());
            }

                // 再应用排序
            if (sortId != R.id.sort_by_default) {
                Log.d("DownloadListInfos", "executeFilterAndSort: 应用排序, sortId=" + sortId);
                resultList = sortByType(filteredList, sortId);
                Log.d("DownloadListInfos", "executeFilterAndSort: 排序完成，结果列表大小=" + resultList.size());
            } else {
                resultList = filteredList;
                Log.d("DownloadListInfos", "executeFilterAndSort: 使用默认排序，结果列表大小=" + resultList.size());
            }

            handler.post(() -> {
                if (mDownloadSearchCallback == null) {
                    Log.e("DownloadListInfos", "executeFilterAndSort: 回调为null");
                    return;
                }
                Log.d("DownloadListInfos", "executeFilterAndSort: 调用成功回调，结果列表大小=" + resultList.size());
                mDownloadSearchCallback.onDownloadSearchSuccess(resultList);
            });
        });
    }

    // 新增方法：同时应用分类过滤、状态过滤和排序（支持多选分类）
    public void executeFilterAndSort(Set<Integer> categoryIds, int statusId, int sortId) {
        Log.d("DownloadListInfos", "executeFilterAndSort(多选分类): 开始, categoryIds=" + categoryIds + ", statusId=" + statusId + ", sortId=" + sortId);
        Log.d("DownloadListInfos", "executeFilterAndSort(多选分类): 输入列表大小=" + (mList != null ? mList.size() : 0));
        
        service.execute(() -> {
            if (mCancelled) return;
            List<DownloadInfo> safeList = new ArrayList<>(mList != null ? mList : new ArrayList<>());
            // 先应用分类过滤
            List<DownloadInfo> filteredList = safeList;
            if (categoryIds != null && !categoryIds.contains(EhUtils.ALL_CATEGORY)) {
                Log.d("DownloadListInfos", "executeFilterAndSort(多选分类): 应用分类过滤, categoryIds=" + categoryIds);
                filteredList = filterByCategories(categoryIds, filteredList);
                Log.d("DownloadListInfos", "executeFilterAndSort(多选分类): 分类过滤完成，列表大小=" + filteredList.size());
            }

            // 再应用状态过滤
            if (statusId != R.id.all) {
                Log.d("DownloadListInfos", "executeFilterAndSort(多选分类): 应用状态过滤, statusId=" + statusId);
                switch (statusId) {
                    case R.id.download_done:
                        filteredList = filterDownloadState(filteredList, DownloadInfo.STATE_FINISH);
                        break;
                    case R.id.not_started:
                        filteredList = filterDownloadState(filteredList, DownloadInfo.STATE_NONE);
                        break;
                    case R.id.waiting:
                        filteredList = filterDownloadState(filteredList, DownloadInfo.STATE_WAIT);
                        break;
                    case R.id.downloading:
                        filteredList = filterDownloadState(filteredList, DownloadInfo.STATE_DOWNLOAD);
                        break;
                    case R.id.failed:
                        filteredList = filterDownloadState(filteredList, DownloadInfo.STATE_FAILED);
                        break;
                    case R.id.relay_download:
                        filteredList = filterDownloadState(filteredList, DownloadInfo.STATE_RELAY_DOWNLOAD);
                        break;
                    default:
                        break;
                }
                Log.d("DownloadListInfos", "executeFilterAndSort(多选分类): 状态过滤完成，列表大小=" + filteredList.size());
            }

            // 最后应用排序
            if (sortId != R.id.sort_by_default) {
                Log.d("DownloadListInfos", "executeFilterAndSort(多选分类): 应用排序, sortId=" + sortId);
                resultList = sortByType(filteredList, sortId);
                Log.d("DownloadListInfos", "executeFilterAndSort(多选分类): 排序完成，结果列表大小=" + resultList.size());
            } else {
                resultList = filteredList;
                Log.d("DownloadListInfos", "executeFilterAndSort(多选分类): 使用默认排序，结果列表大小=" + resultList.size());
            }

            handler.post(() -> {
                if (mDownloadSearchCallback == null) {
                    Log.e("DownloadListInfos", "executeFilterAndSort(多选分类): 回调为null");
                    return;
                }
                Log.d("DownloadListInfos", "executeFilterAndSort(多选分类): 调用成功回调，结果列表大小=" + resultList.size());
                mDownloadSearchCallback.onDownloadSearchSuccess(resultList);
            });
        });
    }

    // 新增方法：同时应用分类过滤、状态过滤和排序
    public void executeFilterAndSort(int categoryId, int statusId, int sortId) {
        Log.d("DownloadListInfos", "executeFilterAndSort(3参数): 开始, categoryId=" + categoryId + ", statusId=" + statusId + ", sortId=" + sortId);
        Log.d("DownloadListInfos", "executeFilterAndSort(3参数): 输入列表大小=" + (mList != null ? mList.size() : 0));
        
        service.execute(() -> {
            if (mCancelled) return;
            List<DownloadInfo> safeList = new ArrayList<>(mList != null ? mList : new ArrayList<>());
            // 先应用分类过滤
            List<DownloadInfo> filteredList = safeList;
            if (categoryId != EhUtils.ALL_CATEGORY) {
                Log.d("DownloadListInfos", "executeFilterAndSort(3参数): 应用分类过滤, categoryId=" + categoryId);
                filteredList = filterByCategory(filteredList, categoryId);
                Log.d("DownloadListInfos", "executeFilterAndSort(3参数): 分类过滤完成，列表大小=" + filteredList.size());
            }

            // 再应用状态过滤
            if (statusId != R.id.all) {
                Log.d("DownloadListInfos", "executeFilterAndSort(3参数): 应用状态过滤, statusId=" + statusId);
                switch (statusId) {
                    case R.id.download_done:
                        filteredList = filterDownloadState(filteredList, DownloadInfo.STATE_FINISH);
                        break;
                    case R.id.not_started:
                        filteredList = filterDownloadState(filteredList, DownloadInfo.STATE_NONE);
                        break;
                    case R.id.waiting:
                        filteredList = filterDownloadState(filteredList, DownloadInfo.STATE_WAIT);
                        break;
                    case R.id.downloading:
                        filteredList = filterDownloadState(filteredList, DownloadInfo.STATE_DOWNLOAD);
                        break;
                    case R.id.failed:
                        filteredList = filterDownloadState(filteredList, DownloadInfo.STATE_FAILED);
                        break;
                    case R.id.relay_download:
                        filteredList = filterDownloadState(filteredList, DownloadInfo.STATE_RELAY_DOWNLOAD);
                        break;
                    default:
                        break;
                }
                Log.d("DownloadListInfos", "executeFilterAndSort(3参数): 状态过滤完成，列表大小=" + filteredList.size());
            }

            // 最后应用排序
            if (sortId != R.id.sort_by_default) {
                Log.d("DownloadListInfos", "executeFilterAndSort(3参数): 应用排序, sortId=" + sortId);
                resultList = sortByType(filteredList, sortId);
                Log.d("DownloadListInfos", "executeFilterAndSort(3参数): 排序完成，结果列表大小=" + resultList.size());
            } else {
                resultList = filteredList;
                Log.d("DownloadListInfos", "executeFilterAndSort(3参数): 使用默认排序，结果列表大小=" + resultList.size());
            }

            handler.post(() -> {
                if (mDownloadSearchCallback == null) {
                    Log.e("DownloadListInfos", "executeFilterAndSort(3参数): 回调为null");
                    return;
                }
                Log.d("DownloadListInfos", "executeFilterAndSort(3参数): 调用成功回调，结果列表大小=" + resultList.size());
                mDownloadSearchCallback.onDownloadSearchSuccess(resultList);
            });
        });
    }

    // 新增方法：同时应用分类过滤、多状态过滤和排序（支持多选分类和多选状态）
    public void executeFilterAndSort(Set<Integer> categoryIds, Set<Integer> statusIds, int sortId) {
        executeFilterAndSort(categoryIds, statusIds, sortId, null, null, null, null, false);
    }

    public void executeFilterAndSort(Set<Integer> categoryIds, Set<Integer> statusIds, int sortId,
                                     @Nullable Long timeFrom, @Nullable Long timeTo,
                                     @Nullable Long sizeFrom, @Nullable Long sizeTo) {
        executeFilterAndSort(categoryIds, statusIds, sortId, timeFrom, timeTo, sizeFrom, sizeTo, false, 0f, 5f);
    }

    public void executeFilterAndSort(Set<Integer> categoryIds, Set<Integer> statusIds, int sortId,
                                      @Nullable Long timeFrom, @Nullable Long timeTo,
                                      @Nullable Long sizeFrom, @Nullable Long sizeTo,
                                      boolean duplicateOnly) {
        executeFilterAndSort(categoryIds, statusIds, sortId, timeFrom, timeTo, sizeFrom, sizeTo, duplicateOnly, 0f, 5f);
    }

    public void executeFilterAndSort(Set<Integer> categoryIds, Set<Integer> statusIds, int sortId,
                                      @Nullable Long timeFrom, @Nullable Long timeTo,
                                      @Nullable Long sizeFrom, @Nullable Long sizeTo,
                                      boolean duplicateOnly, float ratingFrom, float ratingTo) {
        executeFilterAndSort(categoryIds, statusIds, sortId, timeFrom, timeTo, sizeFrom, sizeTo, null, null, duplicateOnly, ratingFrom, ratingTo);
    }

    public void executeFilterAndSort(Set<Integer> categoryIds, Set<Integer> statusIds, int sortId,
                                      @Nullable Long timeFrom, @Nullable Long timeTo,
                                      @Nullable Long sizeFrom, @Nullable Long sizeTo,
                                      @Nullable Long pageFrom, @Nullable Long pageTo,
                                      boolean duplicateOnly, float ratingFrom, float ratingTo) {
        Log.d("DownloadListInfos", "executeFilterAndSort: 开始, categoryIds=" + categoryIds + ", statusIds=" + statusIds + ", sortId=" + sortId + ", ratingFrom=" + ratingFrom + ", ratingTo=" + ratingTo);
        Log.d("DownloadListInfos", "executeFilterAndSort: 输入列表大小=" + (mList != null ? mList.size() : 0));
        
        service.execute(() -> {
            if (mCancelled) return;
            List<DownloadInfo> safeList = new ArrayList<>(mList != null ? mList : new ArrayList<>());
            List<DownloadInfo> filteredList = safeList;
            if (categoryIds != null && !categoryIds.contains(EhUtils.ALL_CATEGORY)) {
                Log.d("DownloadListInfos", "executeFilterAndSort: 应用分类过滤, categoryIds=" + categoryIds);
                filteredList = filterByCategories(categoryIds, filteredList);
                Log.d("DownloadListInfos", "executeFilterAndSort: 分类过滤完成，列表大小=" + filteredList.size());
            }

            if (statusIds != null && !statusIds.isEmpty()) {
                Log.d("DownloadListInfos", "executeFilterAndSort: 应用状态过滤, statusIds=" + statusIds);
                filteredList = filterByStates(statusIds, filteredList);
                Log.d("DownloadListInfos", "executeFilterAndSort: 状态过滤完成，列表大小=" + filteredList.size());
            }

            filteredList = filterByTimeRange(filteredList, timeFrom, timeTo);
            filteredList = filterBySizeRange(filteredList, sizeFrom, sizeTo);
            filteredList = filterByPageRange(filteredList, pageFrom, pageTo);
            filteredList = filterByRating(filteredList, ratingFrom, ratingTo);
            filteredList = filterDuplicateNamedGalleries(filteredList, duplicateOnly);

            List<DownloadInfo> resultList;
            if (sortId != R.id.sort_by_default) {
                Log.d("DownloadListInfos", "executeFilterAndSort: 应用排序, sortId=" + sortId);
                resultList = sortByType(filteredList, sortId);
                Log.d("DownloadListInfos", "executeFilterAndSort: 排序完成，结果列表大小=" + resultList.size());
            } else {
                resultList = filteredList;
                Log.d("DownloadListInfos", "executeFilterAndSort: 使用默认排序，结果列表大小=" + resultList.size());
            }

            handler.post(() -> {
                if (mDownloadSearchCallback == null) {
                    Log.e("DownloadListInfos", "executeFilterAndSort: 回调为null");
                    return;
                }
                Log.d("DownloadListInfos", "executeFilterAndSort: 调用成功回调，结果列表大小=" + resultList.size());
                mDownloadSearchCallback.onDownloadSearchSuccess(resultList);
            });
        });
    }

    // 快捷筛选类型（与菜单 R.id 对应）
    public static final int QUICK_FILTER_BIG_LOW_RATING = R.id.quick_filter_big_low_rating;
    public static final int QUICK_FILTER_DUPLICATES = R.id.quick_filter_duplicates;
    public static final int QUICK_FILTER_LOW_SPACE_EFFICIENCY = R.id.quick_filter_low_space_efficiency;
    public static final int QUICK_FILTER_LOW_RATING = R.id.quick_filter_low_rating;
    public static final int QUICK_FILTER_HUGE_FILE = R.id.quick_filter_huge_file;
    public static final int QUICK_FILTER_OLD_UNFAVORITED = R.id.quick_filter_old_unfavorited;
    public static final int QUICK_FILTER_AI_GENERATED = R.id.quick_filter_ai_generated;

    // 快捷筛选体积阈值
    private static final long SIZE_100MB = 100L * 1024 * 1024;
    private static final long SIZE_200MB = 200L * 1024 * 1024;
    private static final double SIZE_PER_PAGE_4MB = 4.0 * 1024 * 1024;

    /**
     * 执行快捷筛选预设。所有预设均要求“已下载完成”（STATE_FINISH）。
     * 在后台线程中一次遍历完成，体积类预设复用同一张尺寸表，避免重复 I/O。
     */
    @SuppressLint("NonConstantResourceId")
    public void executeQuickFilter(int quickFilterId) {
        mCancelled = false;
        service.execute(() -> {
            if (mCancelled) return;
            List<DownloadInfo> source = mList != null ? mList : new ArrayList<>();
            // 统一先过滤已完成下载，这是所有快捷筛选的前提
            List<DownloadInfo> finished = filterDownloadState(source, DownloadInfo.STATE_FINISH);
            List<DownloadInfo> result = new ArrayList<>();
            long start = System.currentTimeMillis();
            try {
                switch (quickFilterId) {
                    case QUICK_FILTER_BIG_LOW_RATING: {
                        Map<Long, Long> sizeMap = loadGallerySizeMap(finished);
                        for (DownloadInfo info : finished) {
                            long size = sizeMap.getOrDefault(info.gid, 0L);
                            info.fileSize = size;
                            if (size >= SIZE_100MB && getEffectiveRating(info) > 0f && getEffectiveRating(info) < 3.5f) {
                                result.add(info);
                            }
                        }
                        result.sort((a, b) -> Long.compare(sizeOf(b), sizeOf(a)));
                        break;
                    }
                    case QUICK_FILTER_DUPLICATES: {
                        Map<Long, Long> sizeMap = loadGallerySizeMap(finished);
                        for (DownloadInfo info : finished) {
                            info.fileSize = sizeMap.getOrDefault(info.gid, 0L);
                        }
                        result = filterDuplicatesExceptBest(finished);
                        break;
                    }
                    case QUICK_FILTER_LOW_SPACE_EFFICIENCY: {
                        Map<Long, Long> sizeMap = loadGallerySizeMap(finished);
                        Map<DownloadInfo, Double> perPageMap = new HashMap<>();
                        for (DownloadInfo info : finished) {
                            long size = sizeMap.getOrDefault(info.gid, 0L);
                            info.fileSize = size;
                            long pages = getPageCount(info);
                            if (pages > 0 && size >= SIZE_PER_PAGE_4MB * pages) {
                                perPageMap.put(info, size / (double) pages);
                                result.add(info);
                            }
                        }
                        result.sort((a, b) -> Double.compare(perPageMap.getOrDefault(b, 0d), perPageMap.getOrDefault(a, 0d)));
                        break;
                    }
                    case QUICK_FILTER_LOW_RATING: {
                        for (DownloadInfo info : finished) {
                            float effectiveRating = getEffectiveRating(info);
                            if (effectiveRating > 0f && effectiveRating < 3f) {
                                result.add(info);
                            }
                        }
                        result.sort((a, b) -> Float.compare(getEffectiveRating(a), getEffectiveRating(b)));
                        break;
                    }
                    case QUICK_FILTER_HUGE_FILE: {
                        Map<Long, Long> sizeMap = loadGallerySizeMap(finished);
                        for (DownloadInfo info : finished) {
                            long size = sizeMap.getOrDefault(info.gid, 0L);
                            info.fileSize = size;
                            if (size >= SIZE_200MB) {
                                result.add(info);
                            }
                        }
                        result.sort((a, b) -> Long.compare(sizeOf(b), sizeOf(a)));
                        break;
                    }
                    case QUICK_FILTER_OLD_UNFAVORITED: {
                        Set<Long> favoritedGids = loadLocalFavoritedGids();
                        for (DownloadInfo info : finished) {
                            if (!favoritedGids.contains(info.gid) && getPostedYear(info) < 2024) {
                                result.add(info);
                            }
                        }
                        result.sort((a, b) -> Integer.compare(getPostedYear(a), getPostedYear(b)));
                        break;
                    }
                    case QUICK_FILTER_AI_GENERATED: {
                        // 筛选标签或标题中含有“AI 生成”标记的画廊（不限于已完成）
                        for (DownloadInfo info : source) {
                            if (isAiGenerated(info)) {
                                result.add(info);
                            }
                        }
                        break;
                    }
                    default:
                        result = finished;
                        break;
                }
            } catch (Exception e) {
                Log.w("DownloadListInfos", "executeQuickFilter failed, quickFilterId=" + quickFilterId, e);
                result = new ArrayList<>();
            }
            Log.d("DownloadListInfos", "executeQuickFilter 完成, id=" + quickFilterId + ", 结果=" + result.size() + ", 耗时=" + (System.currentTimeMillis() - start) + "ms");
            List<DownloadInfo> finalResult = result;
            handler.post(() -> {
                if (mCancelled || mDownloadSearchCallback == null) {
                    return;
                }
                mDownloadSearchCallback.onDownloadSearchSuccess(finalResult);
            });
        });
    }

    private long sizeOf(DownloadInfo info) {
        return info.fileSize > 0 ? info.fileSize : 0L;
    }

    /**
     * 判断画廊是否属于“AI 生成/辅助”作品：标签或标题中包含 AI 生成相关标记。
     *
     * 判定依据（区分大小写不敏感）：
     * <ul>
     *   <li>标签（simpleTags）：命名空间或值包含 ai-generated / ai_generated / ai_assisted 等</li>
     *   <li>标题（title / titleJpn）：包含 “ai generated”、“ai-gen”、“[ai]” 等特征词</li>
     * </ul>
     */
    private boolean isAiGenerated(DownloadInfo info) {
        if (info == null) {
            return false;
        }
        // 1) 标签检测：命名空间/值中包含 ai-generated、ai_generated、ai_assisted、ai- 等
        String[] tags = info.simpleTags;
        if (tags != null) {
            for (String tag : tags) {
                if (tag != null && isAiGeneratedTag(tag)) {
                    return true;
                }
            }
        }
        // 2) 标题检测
        return matchesAiInText(info.title) || matchesAiInText(info.titleJpn);
    }

    private boolean isAiGeneratedTag(String tag) {
        String t = tag.toLowerCase(Locale.ROOT);
        return t.contains("ai-generated")
                || t.contains("ai_generated")
                || t.contains("ai_assisted")
                || t.startsWith("ai-")
                || t.startsWith("ai_");
    }

    private boolean matchesAiInText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("ai-generated")
                || lower.contains("ai generated")
                || lower.contains("ai_generated")
                || lower.contains("ai-gen")
                || lower.contains(" ai_gen")
                || lower.matches(".*\\[\\s*ai[^]]*\\].*")
                || lower.matches(".*\\bai\\b.*\\b(generat|diffusion|dream|art)\\b.*")
                || lower.matches(".*\\b(generat|diffusion|dream|art)\\b.*\\bai\\b.*");
    }

    /**
     * 获取页数：优先 total，其次 pages，再退化为 SpiderInfo。
     */
    private long getPageCount(DownloadInfo info) {
        if (info.total > 0) {
            return info.total;
        }
        if (info.pages > 0) {
            return info.pages;
        }
        try {
            SpiderInfo spiderInfo = SpiderInfo.getSpiderInfo(info);
            if (spiderInfo != null && spiderInfo.pages > 0) {
                return spiderInfo.pages;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /**
     * 从 posted（yyyy-MM-dd HH:mm）解析发布年份；解析失败返回 0。
     */
    private int getPostedYear(DownloadInfo info) {
        String posted = info.posted;
        if (posted == null || posted.isEmpty()) {
            return 0;
        }
        try {
            String yearStr = posted.substring(0, Math.min(4, posted.length())).trim();
            int dash = yearStr.indexOf('-');
            if (dash > 0) {
                yearStr = yearStr.substring(0, dash);
            }
            int year = Integer.parseInt(yearStr);
            return year > 0 ? year : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 加载本地收藏 gid 集合（仅本地收藏表，离线可靠）。
     */
    private Set<Long> loadLocalFavoritedGids() {
        Set<Long> gids = new HashSet<>();
        try {
            List<GalleryInfo> favorites = EhDB.getAllLocalFavorites();
            if (favorites != null) {
                for (GalleryInfo gi : favorites) {
                    if (gi != null) {
                        gids.add(gi.gid);
                    }
                }
            }
        } catch (Exception e) {
            Log.w("DownloadListInfos", "loadLocalFavoritedGids failed", e);
        }
        return gids;
    }

    /**
     * 重复作品：规范化标题相同的分组中 >= 2 个时，选出“最优”保留（评分最高 -> 完成度最高 -> 体积最大），
     * 返回组内除最优外的其余，便于清理。
     */
    private List<DownloadInfo> filterDuplicatesExceptBest(List<DownloadInfo> sourceList) {
        List<DownloadInfo> result = new ArrayList<>();
        if (sourceList == null || sourceList.isEmpty()) {
            return result;
        }
        Map<String, List<DownloadInfo>> grouped = new HashMap<>();
        for (DownloadInfo info : sourceList) {
            String key = buildNormalizedTitleKey(info);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(info);
        }
        for (List<DownloadInfo> group : grouped.values()) {
            if (group.size() < 2) {
                continue;
            }
            DownloadInfo best = pickBestDuplicate(group);
            for (DownloadInfo info : group) {
                if (info != best) {
                    result.add(info);
                }
            }
        }
        return result;
    }

    /**
     * 从重复组中选出最优：评分高 -> 完成度高 -> 体积大。
     */
    private DownloadInfo pickBestDuplicate(List<DownloadInfo> group) {
        DownloadInfo best = group.get(0);
        for (int i = 1; i < group.size(); i++) {
            DownloadInfo candidate = group.get(i);
            if (isBetterDuplicate(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private boolean isBetterDuplicate(DownloadInfo candidate, DownloadInfo current) {
        float candidateRating = getEffectiveRating(candidate);
        float currentRating = getEffectiveRating(current);
        if (candidateRating != currentRating) {
            return candidateRating > currentRating;
        }
        double candidateComplete = completion(candidate);
        double currentComplete = completion(current);
        if (candidateComplete != currentComplete) {
            return candidateComplete > currentComplete;
        }
        return sizeOf(candidate) > sizeOf(current);
    }

    /**
     * 完成度：已下载页 / 总页。总页未知时用 finished 或 total 兜底。
     */
    private double completion(DownloadInfo info) {
        long total = info.total > 0 ? info.total : (info.pages > 0 ? info.pages : 0);
        if (total <= 0) {
            return info.finished > 0 ? 1d : 0d;
        }
        return (double) Math.min(info.finished, total) / total;
    }

    /**
     * 标题规范化：NFKC、去首部 gid 前缀、去 🔄、去首尾空白、折叠空白、小写。
     */
    @NonNull
    private String buildNormalizedTitleKey(@NonNull DownloadInfo info) {
        String title = info.title;
        if (title == null || title.isEmpty()) {
            title = info.titleJpn;
        }
        if (title == null || title.isEmpty()) {
            return String.valueOf(info.gid);
        }
        String normalized = title.replaceFirst("^\\d+-", "")
                .replace("🔄", "")
                .trim()
                .toLowerCase(Locale.ROOT);
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC);
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized.isEmpty() ? String.valueOf(info.gid) : normalized;
    }

    private List<DownloadInfo> filterByRating(List<DownloadInfo> sourceList, float ratingFrom, float ratingTo) {
        if (sourceList == null) {
            return new ArrayList<>();
        }
        if (ratingFrom <= 0f && ratingTo >= 5f) {
            return sourceList;
        }
        List<DownloadInfo> list = new ArrayList<>();
        for (DownloadInfo info : sourceList) {
            float effectiveRating = getEffectiveRating(info);
            if (effectiveRating >= ratingFrom && effectiveRating <= ratingTo) {
                list.add(info);
            }
        }
        Log.d("DownloadListInfos", "filterByRating: range=" + ratingFrom + "~" + ratingTo + ", 过滤后列表大小=" + list.size());
        return list;
    }

    /**
     * 计算有效评分：优先使用本地评分（本地评分覆盖），
     * 未设置本地评分时回退到 E-Hentai 在线评分。
     */
    private static float getEffectiveRating(DownloadInfo info) {
        if (info == null) {
            return -1f;
        }
        LocalRatingManager localRatingManager = LocalRatingManager.getInstance();
        if (localRatingManager == null) {
            // Not initialized yet, fall back to the transient field (populated at startup)
            return info.localRating > 0f ? info.localRating : info.rating;
        }
        return localRatingManager.getEffectiveRating(info.gid, info.rating);
    }

    private List<DownloadInfo> filterByPageRange(@Nullable List<DownloadInfo> sourceList,
                                                  @Nullable Long pageFrom,
                                                  @Nullable Long pageTo) {
        if (sourceList == null || sourceList.isEmpty()) {
            return sourceList != null ? sourceList : new ArrayList<>();
        }
        if (pageFrom == null && pageTo == null) {
            return sourceList;
        }
        List<DownloadInfo> result = new ArrayList<>();
        for (DownloadInfo info : sourceList) {
            long pages = info.total > 0 ? info.total : 0;
            if (pages == 0) {
                SpiderInfo spiderInfo = SpiderInfo.getSpiderInfo(info);
                if (spiderInfo != null) {
                    pages = spiderInfo.pages;
                }
            }
            if (isInRange(pages, pageFrom, pageTo)) {
                result.add(info);
            }
        }
        return result;
    }

    private List<DownloadInfo> filterDuplicateNamedGalleries(@Nullable List<DownloadInfo> sourceList,
                                                             boolean duplicateOnly) {
        if (!duplicateOnly) {
            return sourceList != null ? sourceList : new ArrayList<>();
        }
        if (sourceList == null || sourceList.isEmpty()) {
            return sourceList != null ? sourceList : new ArrayList<>();
        }

        Map<String, List<DownloadInfo>> grouped = new HashMap<>();
        for (DownloadInfo info : sourceList) {
            String key = buildDuplicateGroupingName(info);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(info);
        }

        Set<Long> duplicatedGids = new HashSet<>();
        for (List<DownloadInfo> group : grouped.values()) {
            if (group.size() >= 2) {
                for (DownloadInfo info : group) {
                    duplicatedGids.add(info.gid);
                }
            }
        }

        List<DownloadInfo> result = new ArrayList<>();
        for (DownloadInfo info : sourceList) {
            if (duplicatedGids.contains(info.gid)) {
                result.add(info);
            }
        }
        return result;
    }

    @NonNull
    private String buildDuplicateGroupingName(@NonNull DownloadInfo info) {
        String sourceName = null;
        UniFile dir = SpiderDen.getGalleryDownloadDir(info);
        if (dir != null) {
            sourceName = dir.getName();
        }
        if (sourceName == null || sourceName.trim().isEmpty()) {
            sourceName = info.title;
        }
        if (sourceName == null) {
            sourceName = String.valueOf(info.gid);
        }

        String normalized = sourceName.replaceFirst("^\\d+-", "")
                .replace("🔄", "")
                .trim()
                .toLowerCase();
        return normalized.isEmpty() ? sourceName : normalized;
    }

    private List<DownloadInfo> filterByTimeRange(@Nullable List<DownloadInfo> sourceList,
                                                 @Nullable Long timeFrom,
                                                 @Nullable Long timeTo) {
        if (sourceList == null || sourceList.isEmpty()) {
            return sourceList != null ? sourceList : new ArrayList<>();
        }
        if (timeFrom == null && timeTo == null) {
            return sourceList;
        }

        List<DownloadInfo> result = new ArrayList<>();
        for (DownloadInfo info : sourceList) {
            long ts = mGalleryTimeCache.computeIfAbsent(info.gid,
                    ignored -> DownloadGalleryMetaHelper.getGalleryDirectoryTimestamp(info));
            if (isInRange(ts, timeFrom, timeTo)) {
                result.add(info);
            }
        }
        return result;
    }

    private List<DownloadInfo> filterBySizeRange(@Nullable List<DownloadInfo> sourceList,
                                                 @Nullable Long sizeFrom,
                                                 @Nullable Long sizeTo) {
        if (sourceList == null || sourceList.isEmpty()) {
            return sourceList != null ? sourceList : new ArrayList<>();
        }
        if (sizeFrom == null && sizeTo == null) {
            return sourceList;
        }

        Map<Long, Long> sizeMap = loadGallerySizeMap(sourceList);
        List<DownloadInfo> result = new ArrayList<>();
        for (DownloadInfo info : sourceList) {
            long size = sizeMap.getOrDefault(info.gid, 0L);
            info.fileSize = size;
            if (isInRange(size, sizeFrom, sizeTo)) {
                result.add(info);
            }
        }
        return result;
    }

    private boolean isInRange(long value, @Nullable Long from, @Nullable Long to) {
        if (from != null && value < from) {
            return false;
        }
        return to == null || value <= to;
    }

    private Map<Long, Long> loadGallerySizeMap(@NonNull List<DownloadInfo> infos) {
        if (infos.isEmpty()) {
            return new HashMap<>();
        }
        // 分批查询，避免 SQLite IN 子句变量数量上限（默认 999）
        final int BATCH_SIZE = 500;
        Map<Long, Long> sizeMap = new HashMap<>(infos.size());
        List<Long> gids = new ArrayList<>(Math.min(BATCH_SIZE, infos.size()));
        for (DownloadInfo info : infos) {
            gids.add(info.gid);
            if (gids.size() >= BATCH_SIZE) {
                sizeMap.putAll(queryGallerySizeMap(gids));
                gids.clear();
            }
        }
        if (!gids.isEmpty()) {
            sizeMap.putAll(queryGallerySizeMap(gids));
        }
        return sizeMap;
    }

    private Map<Long, Long> queryGallerySizeMap(List<Long> gids) {
        Map<Long, Long> result = new HashMap<>();
        try {
            Map<Long, Long> map = DownloadedFileManager.getInstance().getGalleryFilesTotalSizeMap(gids);
            if (map != null) {
                result.putAll(map);
            }
        } catch (IllegalStateException e) {
            Log.w("DownloadListInfos", "DownloadedFileManager not initialized", e);
        } catch (Exception e) {
            Log.w("DownloadListInfos", "loadGallerySizeMap failed", e);
        }
        // 兜底：仍未拿到有效体积的画廊，直接扫描实际下载目录
        for (Long gid : gids) {
            if (gid == null || result.getOrDefault(gid, 0L) > 0L) {
                continue;
            }
            DownloadInfo info = EhDB.getDownloadInfo(gid);
            if (info != null) {
                long size = calculateDownloadDirSize(info);
                if (size > 0L) {
                    result.put(gid, size);
                }
            }
        }
        return result;
    }

    public List<DownloadInfo> sortByType(List<DownloadInfo> sourceList, int type) {
        Log.d("DownloadListInfos", "sortByType: 开始排序, type=" + type);
        if (sourceList == null) {
            Log.w("DownloadListInfos", "sortByType: sourceList为null，返回空列表");
            return new ArrayList<>();
        }
        
        Log.d("DownloadListInfos", "sortByType: 排序前列表大小=" + sourceList.size());
        DownloadInfo[] arr = new DownloadInfo[sourceList.size()];
        sourceList.toArray(arr);

        // 如果是按文件大小排序，先计算所有文件大小
        Map<DownloadInfo, Long> computedSizes = new java.util.HashMap<>();
        if (type == R.id.sort_by_file_size_asc || type == R.id.sort_by_file_size_desc) {
            Map<Long, Long> sizeMap = loadGallerySizeMap(Arrays.asList(arr));
            for (DownloadInfo info : arr) {
                long size = sizeMap.getOrDefault(info.gid, -1L);
                if (size < 0 && info.fileSize >= 0) {
                    size = info.fileSize;
                } else if (size < 0) {
                    size = calculateDownloadDirSize(info);
                }
                computedSizes.put(info, size);
            }
        }

        // 如果是按剩余页数排序，先计算每个画廊的剩余页数
        Map<DownloadInfo, Integer> computedRemaining = new java.util.HashMap<>();
        if (type == R.id.sort_by_remaining_pages_asc || type == R.id.sort_by_remaining_pages_desc) {
            for (DownloadInfo info : arr) {
                computedRemaining.put(info, calculateRemainingPages(info));
            }
        }

        int n = arr.length;
        // 子数组的大小分别为1，2，4，8...
        // 刚开始合并的数组大小是1，接着是2，接着4....
        for (int i = 1; i < n; i += i) {
            //进行数组进行划分
            int left = 0;
            int mid = left + i - 1;
            int right = mid + i;
            //进行合并，对数组大小为 i 的数组进行两两合并
            while (right < n) {
                // 合并函数和递归式的合并函数一样
                merge(arr, left, mid, right, type, computedSizes, computedRemaining);
                left = right + 1;
                mid = left + i - 1;
                right = mid + i;
            }
            // 还有一些被遗漏的数组没合并，千万别忘了
            // 因为不可能每个字数组的大小都刚好为 i
            if (left < n && mid < n) {
                merge(arr, left, mid, n - 1, type, computedSizes, computedRemaining);
            }
        }
        
        Log.d("DownloadListInfos", "sortByType: 排序完成");
        return Arrays.asList(arr);
    }

    // 合并函数，把两个有序的数组合并起来
    // arr[left..mif]表示一个数组，arr[mid+1 .. right]表示一个数组
    @SuppressLint("NonConstantResourceId")
    private static void merge(DownloadInfo[] arr, int left, int mid, int right, int sortType, java.util.Map<DownloadInfo, Long> sizeMap, java.util.Map<DownloadInfo, Integer> remainingMap) {
        //先用一个临时数组把他们合并汇总起来
        DownloadInfo[] a = new DownloadInfo[right - left + 1];
        int i = left;
        int j = mid + 1;
        int k = 0;
        while (i <= mid && j <= right) {
            switch (sortType) {
                case R.id.sort_by_gallery_id_asc:
                    if (arr[i].gid < arr[j].gid) {
                        a[k++] = arr[i++];
                    } else {
                        a[k++] = arr[j++];
                    }
                    break;
                case R.id.sort_by_gallery_id_desc:
                    if (arr[i].gid > arr[j].gid) {
                        a[k++] = arr[i++];
                    } else {
                        a[k++] = arr[j++];
                    }
                    break;
                case R.id.sort_by_create_time_asc:
                    if (arr[i].time < arr[j].time) {
                        a[k++] = arr[i++];
                    } else {
                        a[k++] = arr[j++];
                    }
                    break;
                case R.id.sort_by_create_time_desc:
                    if (arr[i].time > arr[j].time) {
                        a[k++] = arr[i++];
                    } else {
                        a[k++] = arr[j++];
                    }
                    break;
                case R.id.sort_by_rating_asc:
                    if (getEffectiveRating(arr[i]) < getEffectiveRating(arr[j])) {
                        a[k++] = arr[i++];
                    } else {
                        a[k++] = arr[j++];
                    }
                    break;
                case R.id.sort_by_rating_desc:
                    if (getEffectiveRating(arr[i]) > getEffectiveRating(arr[j])) {
                        a[k++] = arr[i++];
                    } else {
                        a[k++] = arr[j++];
                    }
                    break;
                case R.id.sort_by_name_asc: {
                    String titleI = arr[i].title;
                    String titleJ = arr[j].title;
                    // null 值排在最后
                    if (titleI == null && titleJ == null) {
                        a[k++] = arr[i++];
                    } else if (titleI == null) {
                        a[k++] = arr[j++];
                    } else if (titleJ == null) {
                        a[k++] = arr[i++];
                    } else {
                        // 使用 compareToIgnoreCase 进行不区分大小写的比较
                        if (titleI.compareToIgnoreCase(titleJ) < 0) {
                            a[k++] = arr[i++];
                        } else {
                            a[k++] = arr[j++];
                        }
                    }
                    break;
                }
                case R.id.sort_by_name_desc: {
                    String titleI = arr[i].title;
                    String titleJ = arr[j].title;
                    // null 值排在最后
                    if (titleI == null && titleJ == null) {
                        a[k++] = arr[i++];
                    } else if (titleI == null) {
                        a[k++] = arr[j++];
                    } else if (titleJ == null) {
                        a[k++] = arr[i++];
                    } else {
                        // 使用 compareToIgnoreCase 进行不区分大小写的比较
                        if (titleI.compareToIgnoreCase(titleJ) > 0) {
                            a[k++] = arr[i++];
                        } else {
                            a[k++] = arr[j++];
                        }
                    }
                    break;
                }
                case R.id.sort_by_file_size_asc: {
                    long sizeI = sizeMap != null ? sizeMap.getOrDefault(arr[i], -1L) : arr[i].fileSize;
                    long sizeJ = sizeMap != null ? sizeMap.getOrDefault(arr[j], -1L) : arr[j].fileSize;
                    if (sizeI < 0 && sizeJ < 0) {
                        a[k++] = arr[i++];
                    } else if (sizeI < 0) {
                        a[k++] = arr[j++];
                    } else if (sizeJ < 0) {
                        a[k++] = arr[i++];
                    } else if (sizeI < sizeJ) {
                        a[k++] = arr[i++];
                    } else {
                        a[k++] = arr[j++];
                    }
                    break;
                }
                case R.id.sort_by_file_size_desc: {
                    long sizeI = sizeMap != null ? sizeMap.getOrDefault(arr[i], -1L) : arr[i].fileSize;
                    long sizeJ = sizeMap != null ? sizeMap.getOrDefault(arr[j], -1L) : arr[j].fileSize;
                    if (sizeI < 0 && sizeJ < 0) {
                        a[k++] = arr[i++];
                    } else if (sizeI < 0) {
                        a[k++] = arr[j++];
                    } else if (sizeJ < 0) {
                        a[k++] = arr[i++];
                    } else if (sizeI > sizeJ) {
                        a[k++] = arr[i++];
                    } else {
                        a[k++] = arr[j++];
                    }
                    break;
                }
                case R.id.sort_by_state_queue_asc:
                case R.id.sort_by_state_queue_desc: {
                    // 按下载状态队列排序：正在下载 > 等待中 > 已完成
                    // 升序/降序影响同一状态内的排列顺序（按时间）
                    int stateOrderI = getStateQueueOrder(arr[i].state);
                    int stateOrderJ = getStateQueueOrder(arr[j].state);
                    if (stateOrderI != stateOrderJ) {
                        // 状态不同时，按队列顺序排列
                        if (sortType == R.id.sort_by_state_queue_asc) {
                            a[k++] = stateOrderI < stateOrderJ ? arr[i++] : arr[j++];
                        } else {
                            a[k++] = stateOrderI > stateOrderJ ? arr[i++] : arr[j++];
                        }
                    } else {
                        // 状态相同时，按时间排序
                        if (sortType == R.id.sort_by_state_queue_asc) {
                            a[k++] = arr[i].time <= arr[j].time ? arr[i++] : arr[j++];
                        } else {
                            a[k++] = arr[i].time >= arr[j].time ? arr[i++] : arr[j++];
                        }
                    }
                    break;
                }
                case R.id.sort_by_remaining_pages_asc:
                case R.id.sort_by_remaining_pages_desc: {
                    int remI = remainingMap != null ? remainingMap.getOrDefault(arr[i], Integer.MAX_VALUE) : Integer.MAX_VALUE;
                    int remJ = remainingMap != null ? remainingMap.getOrDefault(arr[j], Integer.MAX_VALUE) : Integer.MAX_VALUE;
                    if (sortType == R.id.sort_by_remaining_pages_asc) {
                        a[k++] = remI <= remJ ? arr[i++] : arr[j++];
                    } else {
                        a[k++] = remI >= remJ ? arr[i++] : arr[j++];
                    }
                    break;
                }
            }

        }
        while (i <= mid) a[k++] = arr[i++];
        while (j <= right) a[k++] = arr[j++];
        // 把临时数组复制到原数组
        for (i = 0; i < k; i++) {
            arr[left++] = a[i];
        }
    }

    /**
     * 获取下载状态的队列顺序值，值越小优先级越高。
     * 顺序：正在下载(2) > 等待中(1) > 无状态(0) > 更新中(5) > 失败(4) > 已完成(3)
     */
    private static int getStateQueueOrder(int state) {
        switch (state) {
            case DownloadInfo.STATE_DOWNLOAD: return 0;  // 最高优先
            case DownloadInfo.STATE_WAIT:     return 1;
            case DownloadInfo.STATE_NONE:     return 2;
            case DownloadInfo.STATE_UPDATE:   return 3;
            case DownloadInfo.STATE_FAILED:   return 4;
            case DownloadInfo.STATE_FINISH:   return 5;  // 最低优先
            default: return 6;
        }
    }

    /**
     * 智能计算剩余页数：
     * 1. 已完成的下载返回 0（finished >= total 或状态为 FINISH）
     * 2. 否则优先使用 total - finished（总页数 - 已完成页数）
     * 3. 如果 total 未知，尝试从 SpiderInfo/本地文件数获取
     * 4. 回退使用 pages 字段
     * 5. 未知的排最后（Integer.MAX_VALUE）
     */
    private int calculateRemainingPages(DownloadInfo info) {
        // 已经完成（或全部页面已下载）的，剩余为 0
        if (info.state == DownloadInfo.STATE_FINISH
                || (info.total > 0 && info.finished >= info.total)) {
            return 0;
        }
        // 优先使用 total - finished
        if (info.total > 0 && info.finished >= 0) {
            return Math.max(0, info.total - info.finished);
        }
        // 尝试从 SpiderInfo 获取
        try {
            SpiderInfo si = SpiderInfo.getSpiderInfo(info);
            if (si != null && si.pages > 0) {
                // 本地已下载文件数
                int localCount = countLocalFiles(info);
                return Math.max(0, si.pages - localCount);
            }
        } catch (Exception ignored) {
        }
        // 回退：用 pages 字段
        if (info.pages > 0) {
            return info.pages;
        }
        return Integer.MAX_VALUE; // 未知的排最后
    }

    /**
     * 统计本地下载目录中的文件数（排除 .ehviewer 等元数据文件）
     */
    private int countLocalFiles(DownloadInfo info) {
        UniFile dir = SpiderDen.getGalleryDownloadDir(info);
        if (dir == null || !dir.isDirectory()) return 0;
        int count = 0;
        UniFile[] children = dir.listFiles();
        if (children != null) {
            for (UniFile child : children) {
                if (child.isFile() && !child.getName().startsWith(".")) {
                    count++;
                }
            }
        }
        return count;
    }

    private List<DownloadInfo> filterDownloadState(List<DownloadInfo> sourceList, int state) {
        List<DownloadInfo> list = new ArrayList<>();
        if (sourceList == null) {
            return list;
        }
        for (DownloadInfo info : sourceList) {
            if (info.state == state) {
                list.add(info);
            }
        }
        return list;
    }

    // 新增方法：按分类过滤
    private List<DownloadInfo> filterByCategories(Set<Integer> categoryIds, List<DownloadInfo> sourceList) {
        List<DownloadInfo> list = new ArrayList<>();
        if (sourceList == null || categoryIds == null) {
            return list;
        }
        
        Log.d("DownloadListInfos", "filterByCategories: 输入分类=" + categoryIds + ", 列表大小=" + sourceList.size());
        
        for (DownloadInfo info : sourceList) {
            Log.d("DownloadListInfos", "filterByCategories: 检查项目，分类=" + info.category + ", 标题=" + info.title);
            if (categoryIds.contains(info.category)) {
                list.add(info);
                Log.d("DownloadListInfos", "filterByCategories: 匹配成功，添加到结果");
            }
        }
        
        Log.d("DownloadListInfos", "filterByCategories: 过滤后列表大小=" + list.size());
        return list;
    }

    private List<DownloadInfo> filterByCategory(List<DownloadInfo> sourceList, int categoryId) {
        List<DownloadInfo> list = new ArrayList<>();
        if (sourceList == null) {
            return list;
        }
        for (DownloadInfo info : sourceList) {
            if (info.category == categoryId) {
                list.add(info);
            }
        }
        return list;
    }

    private List<DownloadInfo> filterByStates(Set<Integer> stateIds, List<DownloadInfo> sourceList) {
        List<DownloadInfo> list = new ArrayList<>();
        if (sourceList == null || stateIds == null) {
            return list;
        }
        Log.d("DownloadListInfos", "filterByStates: 输入状态=" + stateIds + ", 列表大小=" + sourceList.size());
        for (DownloadInfo info : sourceList) {
            if (stateIds.contains(info.state)) {
                list.add(info);
            }
        }
        Log.d("DownloadListInfos", "filterByStates: 过滤后列表大小=" + list.size());
        return list;
    }

    // 新增方法：执行高级搜索
    public void executeAdvancedSearch(String keyword, int searchOption, Set<Integer> categories, int sortId) {
        Log.d("DownloadListInfos", "executeAdvancedSearch: 开始, keyword=" + keyword + ", searchOption=" + searchOption + ", categories=" + categories + ", sortId=" + sortId);
        Log.d("DownloadListInfos", "executeAdvancedSearch: 输入列表大小=" + (mList != null ? mList.size() : 0));
        
        service.execute(() -> {
            if (mCancelled) return;
            List<DownloadInfo> safeList = new ArrayList<>(mList != null ? mList : new ArrayList<>());
            // 先应用分类过滤
            List<DownloadInfo> filteredList = safeList;
            if (categories != null && !categories.contains(EhUtils.ALL_CATEGORY)) {
                Log.d("DownloadListInfos", "executeAdvancedSearch: 应用分类过滤, categories=" + categories);
                filteredList = filterByCategories(categories, filteredList);
                Log.d("DownloadListInfos", "executeAdvancedSearch: 分类过滤完成，列表大小=" + filteredList.size());
            }
            
            // 再应用关键词搜索
            if (keyword != null && !keyword.isEmpty()) {
                Log.d("DownloadListInfos", "executeAdvancedSearch: 应用关键词搜索, keyword=" + keyword);
                filteredList = searchByKeyword(keyword, searchOption, filteredList);
                Log.d("DownloadListInfos", "executeAdvancedSearch: 关键词搜索完成，列表大小=" + filteredList.size());
            }
            
            // 最后应用排序
            if (sortId != R.id.sort_by_default) {
                resultList = sortByType(filteredList, sortId);
            } else {
                resultList = filteredList;
            }

            handler.post(() -> {
                if (mDownloadSearchCallback == null) {
                    Log.e("DownloadListInfos", "executeAdvancedSearch: 回调为null");
                    return;
                }
                Log.d("DownloadListInfos", "executeAdvancedSearch: 调用成功回调，结果列表大小=" + resultList.size());
                mDownloadSearchCallback.onDownloadSearchSuccess(resultList);
            });
        });
    }
    private List<DownloadInfo> filterDownloadKind(List<DownloadInfo> sourceList, int menuId) {
        int kind = kindValue(menuId);
        List<DownloadInfo> list = new ArrayList<>();

        if (sourceList == null) {
            return null;
        }
        if (kind == EhUtils.ALL_CATEGORY) {
            return new ArrayList<>(sourceList);
        }
        for (DownloadInfo info : sourceList) {
            if (info.category == kind) {
                list.add(info);
            }
        }
        return list;
    }


    protected List<DownloadInfo> searchingInBackground() {
        if (mDownloadSearchCallback == null) {
            return new ArrayList<>();
        }
        if (mSearchKey == null || mSearchKey.isEmpty()) {
            return mList;
        }
        if (mList == null) {
            return new ArrayList<>();
        }
        List<SearchBranch> branches = parseBooleanQuery(mSearchKey);
        Map<String, List<String>> termAliases = buildTermAliases(branches);
        SearchDebugLog.d("DownloadSearch", "searchingInBackground query='" + mSearchKey
                + "' -> " + branches.size() + " branch(es), "
                + termAliases.size() + " term alias group(s)");

        List<DownloadInfo> safeList = new ArrayList<>(mList);
        List<DownloadInfo> cache = new ArrayList<>();

        for (DownloadInfo info : safeList) {
            boolean matched = matchBranches(branches, info, termAliases);
            if (matched) {
                cache.add(info);
            }
            SearchDebugLog.d("DownloadSearch", "  " + (matched ? "MATCH  " : "NO-MATCH") + info.gid
                    + " title=" + info.title);
        }

        return cache;
    }

    /**
     * 为每个搜索词条预计算别名集（只算一次）：
     * 原词、去命名空间值（parody:arknights -> arknights）、
     * 标签库规范 key（resolveTagTerm）与其中文翻译（getTranslation）。
     * 让“标题 / 标签 / AI 描述”三处都能用中文或英文命中。
     */
    private Map<String, List<String>> buildTermAliases(List<SearchBranch> branches) {
        Map<String, List<String>> map = new HashMap<>();
        if (branches == null) {
            return map;
        }
        Set<String> terms = new HashSet<>();
        for (SearchBranch branch : branches) {
            terms.addAll(branch.positives);
            terms.addAll(branch.negatives);
        }
        if (terms.isEmpty()) {
            return map;
        }
        EhTagDatabase db = mContext != null ? EhTagDatabase.getInstance(mContext) : null;
        for (String term : terms) {
            List<String> aliases = computeAliases(term, db);
            if (!aliases.isEmpty()) {
                map.put(term, aliases);
            }
            SearchDebugLog.d("DownloadSearch", "aliases('" + term + "') -> " + aliases);
        }
        return map;
    }

    private static List<String> computeAliases(String term, @Nullable EhTagDatabase db) {
        List<String> aliases = new ArrayList<>();
        String t = term == null ? "" : term.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1);
        }
        if (t.isEmpty()) {
            return aliases;
        }
        addAlias(aliases, t);
        int idx = t.indexOf(':');
        if (idx >= 0) {
            addAlias(aliases, t.substring(idx + 1));
        }
        if (db != null) {
            String canonical = db.resolveTagTerm(t);
            if (canonical != null && !canonical.isEmpty()) {
                addAlias(aliases, canonical);
                int ci = canonical.indexOf(':');
                if (ci >= 0) {
                    addAlias(aliases, canonical.substring(ci + 1));
                }
                String chinese = db.getTranslation(canonical);
                if (chinese != null && !"null".equals(chinese)) {
                    addAlias(aliases, chinese);
                }
            }
        }
        return aliases;
    }

    /** 别名统一用小写且把 '+' 换成空格，与标签/标题/AI 描述归一化一致。 */
    private static void addAlias(List<String> aliases, String value) {
        String normalized = normalizeTagToken(value);
        if (!normalized.isEmpty() && !aliases.contains(normalized)) {
            aliases.add(normalized);
        }
    }

    /** 兼容旧行为 + 新增布尔语义：任一分支满足即命中。标题统一小写只算一次。 */
    private boolean matchBranches(List<SearchBranch> branches, DownloadInfo info,
                                  Map<String, List<String>> termAliases) {
        if (branches == null || branches.isEmpty()) {
            // 解析失败时退化为旧的整串标题包含
            return EhUtils.judgeSuitableTitle(info, mSearchKey)
                    || matchAiDescription(mSearchKey, info);
        }
        String titleJpn = info.titleJpn != null ? info.titleJpn : "";
        String title = info.title != null ? info.title : "";
        String titleLc = (titleJpn + title).toLowerCase(Locale.ROOT);
        for (SearchBranch branch : branches) {
            if (matchBranch(branch, info, titleLc, termAliases)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 分支命中 = 排除词都不命中 &&（分支原文整串命中标题 || 全部正词命中）。
     * 保留旧 judgeSuitableTitle 的"整串包含"行为，同时支持真正的分组布尔语义。
     */
    private boolean matchBranch(SearchBranch branch, DownloadInfo info, String titleLc,
                                Map<String, List<String>> termAliases) {
        for (String neg : branch.negatives) {
            if (matchTerm(neg, info, titleLc, termAliases)) {
                return false;
            }
        }
        if (branch.positives.isEmpty()) {
            // 纯排除分支：排除词都不命中即满足
            return true;
        }
        if (EhUtils.judgeSuitableTitle(info, branch.raw)) {
            return true;
        }
        for (String pos : branch.positives) {
            if (!matchTerm(pos, info, titleLc, termAliases)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 单词命中判定：标题/日版标题（大小写不敏感，任一别名）contains
     * || 标签（精确/去命名空间精确，任一别名） || AI 描述（任一别名）contains。
     */
    private boolean matchTerm(String term, DownloadInfo info, String titleLc,
                              Map<String, List<String>> termAliases) {
        List<String> aliases = termAliases != null ? termAliases.get(term) : null;
        if (aliases == null || aliases.isEmpty()) {
            // 兜底：解析未覆盖（引号/异常词）时直接用原词
            aliases = computeAliases(term, mContext != null ? EhTagDatabase.getInstance(mContext) : null);
            if (aliases.isEmpty()) {
                return false;
            }
        }
        for (String alias : aliases) {
            if (alias.length() > 0 && titleLc.contains(alias)) {
                return true;
            }
        }
        return tagMatches(aliases, info) || matchAiDescriptionAliases(aliases, info);
    }

    /**
     * 标签匹配：精确 token 或“忽略命名空间前缀”后的值匹配。
     * 例如搜索词 "arknights" 可命中 "parody:arknights"；"ai generated" 可命中 "other:ai+generated"。
     * 只做精确比较，不做子串，避免 "parody:arknights" 误配 "parody:arknights endfield"。
     */
    private boolean tagMatches(List<String> aliases, DownloadInfo info) {
        if (info.tgList == null || info.tgList.isEmpty()) {
            info.tgList = searchTagList(info.gid);
        }
        if (info.tgList == null) {
            return false;
        }
        Set<String> aliasSet = new HashSet<>(aliases);
        for (String tg : info.tgList) {
            if (aliasSet.contains(normalizeTagToken(tg))) {
                return true;
            }
            int idx = tg.indexOf(':');
            if (idx >= 0) {
                if (aliasSet.contains(normalizeTagToken(tg.substring(idx + 1)))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 标签/搜索词归一化：小写，e-hentai 标签中的 '+' 表示空格。 */
    private static String normalizeTagToken(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().toLowerCase(Locale.ROOT).replace('+', ' ');
    }

    /** AI 描述包含任一别名（小写，LOCALE 无关）。 */
    private boolean matchAiDescriptionAliases(List<String> aliases, DownloadInfo info) {
        if (info == null || aliases == null || aliases.isEmpty()) {
            return false;
        }
        try {
            com.hippo.ehviewer.dao.GalleryAiInfo aiInfo = EhDB.queryGalleryAiInfo(info.gid);
            if (aiInfo == null) {
                return false;
            }
            if (containsAny(aliases, aiInfo.getSummary())) {
                return true;
            }
            if (containsAny(aliases, aiInfo.getTags())) {
                return true;
            }
            return containsAny(aliases, aiInfo.getDescriptions());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean containsAny(List<String> aliases, String text) {
        if (text == null || text.isEmpty() || aliases == null || aliases.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String alias : aliases) {
            if (alias.length() > 0 && lower.contains(alias)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 布尔表达式分支：一组以 AND 连接的正词 + 一组排除词。
     * 由 parseBooleanQuery 从 e-hentai f_search 表达式（如 "(a -b) OR (c d)"）解析而来。
     */
    private static class SearchBranch {
        final List<String> positives = new ArrayList<>();
        final List<String> negatives = new ArrayList<>();
        final String raw; // 原始分支文本（不含最外层括号），用于标题整串兼容匹配

        SearchBranch(String raw) {
            this.raw = raw;
        }
    }

    /**
     * 把查询解析为若干 OR 分支。
     * 顶层按 " OR "（深度 0，引号外，大小写不敏感）切分；
     * 分支内按空格（引号内除外）切分为词条，"-" 前缀归入排除词；
     * 最外层括号被剥离。畸形输入防御性处理。
     */
    private static List<SearchBranch> parseBooleanQuery(String query) {
        List<SearchBranch> branches = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) {
            return branches;
        }
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        boolean inQuote = false;
        String s = query;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            }
            if (!inQuote) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth = Math.max(0, depth - 1);
                }
                // 深度 0 处识别 " OR "
                if (depth == 0 && i + 2 < s.length() && Character.isWhitespace(c)
                        && (Character.toLowerCase(s.charAt(i + 1)) == 'o')
                        && (Character.toLowerCase(s.charAt(i + 2)) == 'r')
                        && (i + 3 >= s.length() || Character.isWhitespace(s.charAt(i + 3)))) {
                    if (cur.length() > 0) {
                        parts.add(cur.toString());
                        cur.setLength(0);
                    }
                    i += 3; // 跳过 " OR" 与后面的一个空格
                    continue;
                }
            }
            cur.append(c);
        }
        if (cur.length() > 0) {
            parts.add(cur.toString());
        }
        for (String part : parts) {
            SearchBranch branch = parseBranch(part);
            if (branch != null) {
                branches.add(branch);
            }
        }
        return branches;
    }

    private static SearchBranch parseBranch(String part) {
        String s = part.trim();
        if (s.isEmpty()) {
            return null;
        }
        while (s.startsWith("(") && s.endsWith(")") && isBalancedOuter(s)) {
            s = s.substring(1, s.length() - 1).trim();
        }
        SearchBranch branch = new SearchBranch(s);
        for (String token : tokenizeSpaces(s)) {
            String term = token;
            boolean negative = false;
            while (term.startsWith("-") && !isQuoted(term)) {
                negative = true;
                term = term.substring(1);
            }
            if (term.isEmpty()) {
                continue;
            }
            if (negative) {
                branch.negatives.add(term);
            } else {
                branch.positives.add(term);
            }
        }
        if (branch.positives.isEmpty() && branch.negatives.isEmpty()) {
            return null;
        }
        return branch;
    }

    /** 按空格分词，双引号内的空格不切分。 */
    private static List<String> tokenizeSpaces(String input) {
        List<String> tokens = new ArrayList<>();
        boolean inQuote = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
                sb.append(c);
            } else if (Character.isWhitespace(c) && !inQuote) {
                if (sb.length() > 0) {
                    tokens.add(sb.toString());
                    sb.setLength(0);
                }
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) {
            tokens.add(sb.toString());
        }
        return tokens;
    }

    private static boolean isQuoted(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"");
    }

    private static boolean isBalancedOuter(String s) {
        if (s.length() < 2) {
            return false;
        }
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    /**
     * 匹配 AI 分析描述（summary / tags / 逐页描述）
     */
    private boolean matchAiDescription(String key, DownloadInfo info) {
        if (info == null || key == null || key.isEmpty()) {
            return false;
        }
        try {
            com.hippo.ehviewer.dao.GalleryAiInfo aiInfo = EhDB.queryGalleryAiInfo(info.gid);
            if (aiInfo == null) {
                return false;
            }
            String summary = aiInfo.getSummary();
            if (summary != null && summary.toLowerCase().contains(key)) {
                return true;
            }
            String tags = aiInfo.getTags();
            if (tags != null && tags.toLowerCase().contains(key)) {
                return true;
            }
            String descriptions = aiInfo.getDescriptions();
            return descriptions != null && descriptions.toLowerCase().contains(key);
        } catch (Exception e) {
            return false;
        }
    }

    private ArrayList<String> searchTagList(long gid) {
        GalleryTags tags = EhDB.queryGalleryTags(gid);

        if (tags == null) {
            return null;
        }

        ArrayList<String> tagList = new ArrayList<>();

        tagList.addAll(parserList("artist", tags.artist));
        tagList.addAll(parserList("rows", tags.rows));
        tagList.addAll(parserList("cosplayer", tags.cosplayer));
        tagList.addAll(parserList("character", tags.character));
        tagList.addAll(parserList("female", tags.female));
        tagList.addAll(parserList("group", tags.group));
        tagList.addAll(parserList("language", tags.language));
        tagList.addAll(parserList("male", tags.male));
        tagList.addAll(parserList("misc", tags.misc));
        tagList.addAll(parserList("mixed", tags.mixed));
        tagList.addAll(parserList("other", tags.other));
        tagList.addAll(parserList("parody", tags.parody));
        tagList.addAll(parserList("reclass", tags.reclass));
        tagList.addAll(parserList("location", tags.location));

        return tagList;
    }

    private ArrayList<String> parserList(String name, String content) {
        if (name == null || content == null) {
            return new ArrayList<>();
        }
        ArrayList<String> list = new ArrayList<>();

        String[] tagNames = content.split(",");

        for (String s : tagNames) {
            list.add(name + ":" + s);
        }

        return list;
    }

    // 新增方法：根据关键词和搜索选项进行搜索
    private List<DownloadInfo> searchByKeyword(String keyword, int searchOption, List<DownloadInfo> sourceList) {
        List<DownloadInfo> list = new ArrayList<>();
        if (sourceList == null || keyword == null || keyword.isEmpty()) {
            return sourceList != null ? sourceList : new ArrayList<>();
        }

        String key = keyword.toLowerCase();
        for (DownloadInfo info : sourceList) {
            boolean match = false;
            
            // 根据搜索选项进行搜索
            if ((searchOption & AdvanceSearchTable.SNAME) != 0 && info.title != null) {
                if (info.title.toLowerCase().contains(key)) {
                    match = true;
                }
            }
            
            if (!match && (searchOption & AdvanceSearchTable.STAGS) != 0 && info.simpleTags != null) {
                String tags = String.join(",", info.simpleTags);
                if (tags.toLowerCase().contains(key)) {
                    match = true;
                }
            }
            
            if (!match && (searchOption & AdvanceSearchTable.SDESC) != 0 && info.title != null) {
                // 优先匹配 AI 描述，否则使用标题兜底
                if (matchAiDescription(key, info)) {
                    match = true;
                } else if (info.title.toLowerCase().contains(key)) {
                    match = true;
                }
            }
            
            if (!match && (searchOption & AdvanceSearchTable.STORR) != 0 && info.uploader != null) {
                if (info.uploader.toLowerCase().contains(key)) {
                    match = true;
                }
            }
            
            if (match) {
                list.add(info);
            }
        }
        return list;
    }

    /**
     * 计算下载目录的总大小（只查找已存在的目录，不创建新目录）
     */
    private long calculateDownloadDirSize(DownloadInfo info) {
        try {
            UniFile downloadDir = SpiderDen.getExistingGalleryDownloadDir(info);
            if (downloadDir == null || !downloadDir.isDirectory()) {
                return -1;
            }
            return calculateFolderSize(downloadDir);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 递归计算文件夹大小
     */
    private long calculateFolderSize(UniFile folder) {
        long totalSize = 0;
        UniFile[] files = folder.listFiles();

        if (files == null) {
            return 0;
        }

        for (UniFile file : files) {
            if (file.isFile()) {
                long fileSize = file.length();
                if (fileSize > 0) {
                    totalSize += fileSize;
                }
            } else if (file.isDirectory()) {
                totalSize += calculateFolderSize(file); // 递归计算子文件夹
            }
        }

        return totalSize;
    }


    private int kindValue(int id) {
        return switch (id) {
            case R.id.doujinshi -> EhConfig.DOUJINSHI;
            case R.id.manga -> EhConfig.MANGA;
            case R.id.artist_cg -> EhConfig.ARTIST_CG;
            case R.id.game_cg -> EhConfig.GAME_CG;
            case R.id.western -> EhConfig.WESTERN;
            case R.id.non_h -> EhConfig.NON_H;
            case R.id.image_set -> EhConfig.IMAGE_SET;
            case R.id.cosplay -> EhConfig.COSPLAY;
            case R.id.asian_porn -> EhConfig.ASIAN_PORN;
            case R.id.misc -> EhConfig.MISC;
            default -> EhUtils.ALL_CATEGORY;
        };
    }

}
