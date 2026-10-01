package com.hippo.ehviewer.ui.scene.topList;

import android.content.Context;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.data.EhTopListDetail;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.topList.TopListInfo;
import com.hippo.ehviewer.client.data.topList.TopListItem;
import com.hippo.ehviewer.client.data.topList.TopListItemArray;
import com.hippo.ehviewer.widget.SimpleRatingView;
import com.hippo.ehviewer.widget.TileThumb;

/**
 * 排行榜列表适配器。
 *
 * <p>画廊排行榜（{@code gallery_toplists}）是唯一带 gid+token 的分类，能拿到画廊详情，
 * 因此显示缩略图 + 标题 + uploader + 评分 + 页数。
 * 其余 6 个分类是关键词榜单（上传者名/标签名等），服务器不下发 gid，
 * 拉不到画廊信息，只显示标题文字。
 * 两种行都是平铺列表样式 + 底部细分隔线，都带金银铜排名 badge。
 */
abstract class EhTopListAdapter extends RecyclerView.Adapter<EhTopListAdapter.EhTopListViewHolder> {

    /** 画廊行条目（带缩略图） */
    private static final int TYPE_GALLERY = 0;
    /** 关键词文字行条目 */
    private static final int TYPE_TEXT = 1;

    private static final String TAG = EhTopListAdapter.class.getSimpleName();

    private final Context context;
    private final TopListInfo ehTopListInfo;
    private final int searchType;
    /**
     * 0=yesterday, 1=past month, 2=past year, 3=all-time. The adapter only
     * renders the items belonging to this time bucket; the Scene owns the
     * secondary TabLayout that drives the index.
     */
    private final int timeBucketIndex;
    /** gid -> 已拉取的画廊详情，由 Scene 侧填充，adapter 只读 */
    private final SparseArray<GalleryDetail> galleryCache;
    /** 多选模式下已选中的 gid，由 Scene 侧维护 */
    private final java.util.Set<Long> selectedGids;
    private boolean multiSelectMode;

    public EhTopListAdapter(@NonNull Context context, TopListInfo topListInfo, int searchType,
                             int timeBucketIndex, SparseArray<GalleryDetail> galleryCache,
                             java.util.Set<Long> selectedGids, boolean multiSelectMode) {
        this.context = context;
        this.ehTopListInfo = topListInfo;
        this.searchType = searchType;
        this.timeBucketIndex = timeBucketIndex;
        this.galleryCache = galleryCache != null ? galleryCache : new SparseArray<>();
        this.selectedGids = selectedGids != null ? selectedGids : new java.util.HashSet<>();
        this.multiSelectMode = multiSelectMode;
    }

    public void setMultiSelectMode(boolean enabled) {
        if (this.multiSelectMode == enabled) {
            return;
        }
        this.multiSelectMode = enabled;
        notifyDataSetChanged();
    }

    public boolean isMultiSelectMode() {
        return multiSelectMode;
    }

    private boolean isGalleryCategory() {
        return ehTopListInfo.type == EhTopListDetail.ListType.GALLERY;
    }

    /**
     * 该条目是否用带缩略图的画廊行：需要 gid+token。
     * 详情还没到时先按行骨架渲染（占位图），拿到数据后由 Scene 触发刷新。
     */
    private boolean useGalleryRow(TopListItem item) {
        if (!isGalleryCategory()) {
            return false;
        }
        return item.gid != null && !item.gid.isEmpty()
                && item.token != null && !item.token.isEmpty();
    }

    @Override
    public int getItemViewType(int position) {
        final TopListItemArray bucket = ehTopListInfo.get(timeBucketIndex);
        if (bucket == null || position >= bucket.length()) {
            return TYPE_TEXT;
        }
        return useGalleryRow(bucket.get(position)) ? TYPE_GALLERY : TYPE_TEXT;
    }

    @NonNull
    @Override
    public EhTopListAdapter.EhTopListViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_GALLERY) {
            View view = View.inflate(context, R.layout.item_top_list_gallery_entry, null);
            return new EhTopListViewHolder(view, true);
        }
        View view = View.inflate(context, R.layout.item_top_list_entry, null);
        return new EhTopListViewHolder(view, false);
    }

    @Override
    public void onBindViewHolder(@NonNull EhTopListAdapter.EhTopListViewHolder holder, int position) {
        final TopListItemArray bucket = ehTopListInfo.get(timeBucketIndex);
        if (bucket == null || position >= bucket.length()) {
            return;
        }
        final TopListItem item = bucket.get(position);
        final int rank = position + 1;

        if (holder.galleryRow) {
            bindGalleryRow(holder, item, rank);
        } else {
            bindTextRow(holder, item, rank);
        }

        holder.itemView.setOnClickListener(v -> onItemClick(item, searchType));
        // 长按：非多选时进入多选模式（仅画廊排行榜）
        holder.itemView.setOnLongClickListener(v -> {
            if (holder.galleryRow) {
                return onLongClick(item);
            }
            return false;
        });
    }

    /**
     * 画廊排行榜行：金银铜 badge + 缩略图 + 标题 + uploader + 评分 + 页数。
     * 详情未就绪时缩略图用占位图、次要信息隐藏，不留空行。
     */
    private void bindGalleryRow(@NonNull EhTopListViewHolder holder, TopListItem item, int rank) {
        bindRankBadge(holder, rank);
        holder.title.setText(item.value);

        long gid = 0;
        try {
            gid = Long.parseLong(item.gid);
        } catch (NumberFormatException e) {
            Log.w(TAG, "Bad gid in top list: " + item.gid);
        }

        GalleryDetail detail = gid != 0 ? galleryCache.get((int) gid) : null;
        if (detail == null) {
            // 详情还在拉取中：只显示文本，缩略图和次要信息留空
            holder.thumb.setImageResource(R.drawable.placeholder_gallery);
            holder.uploader.setVisibility(View.GONE);
            holder.rating.setVisibility(View.GONE);
            holder.pages.setVisibility(View.GONE);
        } else {
            holder.thumb.load(EhCacheKeyFactory.getThumbKey(detail.gid), detail.thumb);
            holder.uploader.setText(detail.uploader);
            holder.uploader.setVisibility(View.VISIBLE);
            holder.rating.setRating(detail.rating);
            holder.rating.setVisibility(View.VISIBLE);
            if (detail.pages > 0) {
                holder.pages.setText(detail.pages + "P");
                holder.pages.setVisibility(View.VISIBLE);
            } else {
                holder.pages.setVisibility(View.GONE);
            }
        }

        bindSelectionState(holder, gid);
    }

    /** 关键词文字行：表单式平铺，保留金银铜排名 badge，并在其后标注条目类型 */
    private void bindTextRow(@NonNull EhTopListViewHolder holder, TopListItem item, int rank) {
        bindRankBadge(holder, rank);
        bindKindIcon(holder);
        holder.title.setText(item.value);
    }

    /**
     * 文字行的条目类型标记，排在金银铜 badge 之后：
     * 上传者排行榜是人（ic_toplist_user），其余关键词榜单是画廊名（ic_toplist_gallery_name）。
     */
    private void bindKindIcon(@NonNull EhTopListViewHolder holder) {
        if (holder.kindIcon == null) {
            return;
        }
        holder.kindIcon.setImageResource(
                isUserRankedCategory() ? R.drawable.ic_toplist_user : R.drawable.ic_toplist_gallery_name);
        holder.kindIcon.setVisibility(View.VISIBLE);
    }

    /**
     * 这些榜单排的是"人"（上传者 / 被追踪者 / 清理者等），条目是用户名而非画廊，
     * 所以用人物图标而不是画廊图标。
     * 画廊排行榜本身走画廊行（有缩略图），不会走到这里。
     */
    private boolean isUserRankedCategory() {
        switch (ehTopListInfo.type) {
            case UPLOADER:
            case EH_TRACKER:
            case CLEANUP:
            case RATING_AND_REVIEWING:
            case HENTAI_HOME:
            case TAGGING:
                return true;
            case GALLERY:
            default:
                return false;
        }
    }

    private boolean isUploaderCategory() {
        return ehTopListInfo.type == EhTopListDetail.ListType.UPLOADER;
    }

    /** 金银铜排名 badge：#1 金色带皇冠，#2 银，#3 铜，其余普通色。两种行布局共用。 */
    private void bindRankBadge(@NonNull EhTopListViewHolder holder, int rank) {
        holder.rankText.setText(context.getString(R.string.top_list_rank_prefix, rank));
        switch (rank) {
            case 1:
                holder.rankContainer.setBackgroundResource(R.drawable.bg_rank_gold);
                holder.rankCrown.setVisibility(View.VISIBLE);
                break;
            case 2:
                holder.rankContainer.setBackgroundResource(R.drawable.bg_rank_silver);
                holder.rankCrown.setVisibility(View.GONE);
                break;
            case 3:
                holder.rankContainer.setBackgroundResource(R.drawable.bg_rank_bronze);
                holder.rankCrown.setVisibility(View.GONE);
                break;
            default:
                holder.rankContainer.setBackgroundResource(R.drawable.bg_rank_normal);
                holder.rankCrown.setVisibility(View.GONE);
                break;
        }
    }

    /** 多选勾选角标（仅画廊卡片有该控件，文字行走普通点击） */
    private void bindSelectionState(@NonNull EhTopListViewHolder holder, long gid) {
        if (holder.selected == null) {
            holder.itemView.setAlpha(1f);
            return;
        }
        if (!multiSelectMode) {
            holder.selected.setVisibility(View.GONE);
            holder.itemView.setAlpha(1f);
            return;
        }
        boolean checked = gid != 0 && selectedGids.contains(gid);
        holder.selected.setVisibility(checked ? View.VISIBLE : View.GONE);
        holder.itemView.setAlpha(checked ? 0.7f : 1f);
    }

    abstract void onItemClick(TopListItem topListItem, int searchType);

    /**
     * 长按条目。非多选模式下返回 true 表示已消费事件（Scene 会进入多选模式）。
     */
    abstract boolean onLongClick(TopListItem topListItem);

    @Override
    public int getItemCount() {
        TopListItemArray bucket = ehTopListInfo.get(timeBucketIndex);
        return bucket != null ? bucket.length() : 0;
    }

    public TopListItemArray currentBucket() {
        return ehTopListInfo.get(timeBucketIndex);
    }

    public TopListInfo getTopListInfo() {
        return ehTopListInfo;
    }

    public int getSearchType() {
        return searchType;
    }

    public static class EhTopListViewHolder extends RecyclerView.ViewHolder {
        /** true = 画廊排行榜行布局（带缩略图），false = 关键词文字行布局 */
        public final boolean galleryRow;

        // 文字行 / 卡片各自的标题控件（布局不同，id 不同）
        public TextView title;
        // 文字行：金银铜排名 badge
        public FrameLayout rankContainer;
        public TextView rankText;
        public ImageView rankCrown;
        // 文字行：条目类型标记（人 / 画廊），画廊行不需要（已有缩略图）
        public final ImageView kindIcon;
        // 卡片：缩略图与次要信息
        public TileThumb thumb;
        public TextView uploader;
        public SimpleRatingView rating;
        public TextView pages;
        // 多选勾选角标，只有卡片布局有，文字行为 null
        public final ImageView selected;

        public EhTopListViewHolder(@NonNull View itemView, boolean galleryRow) {
            super(itemView);
            this.galleryRow = galleryRow;
            this.kindIcon = itemView.findViewById(R.id.top_list_kind_icon);
            if (galleryRow) {
                this.selected = itemView.findViewById(R.id.gallery_selected);
                this.title = itemView.findViewById(R.id.gallery_title);
                this.thumb = itemView.findViewById(R.id.gallery_thumb);
                this.uploader = itemView.findViewById(R.id.gallery_uploader);
                this.rating = itemView.findViewById(R.id.gallery_rating);
                this.pages = itemView.findViewById(R.id.gallery_pages);
                // 画廊行同样保留金银铜 badge
                this.rankContainer = itemView.findViewById(R.id.top_list_rank_container);
                this.rankText = itemView.findViewById(R.id.top_list_rank_text);
                this.rankCrown = itemView.findViewById(R.id.top_list_rank_crown);
            } else {
                this.selected = null;
                this.rankContainer = itemView.findViewById(R.id.top_list_rank_container);
                this.rankText = itemView.findViewById(R.id.top_list_rank_text);
                this.rankCrown = itemView.findViewById(R.id.top_list_rank_crown);
                this.title = itemView.findViewById(R.id.top_list_title);
            }
        }
    }
}
