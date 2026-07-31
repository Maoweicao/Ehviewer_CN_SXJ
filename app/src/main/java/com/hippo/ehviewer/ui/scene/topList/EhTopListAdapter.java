package com.hippo.ehviewer.ui.scene.topList;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.topList.TopListInfo;
import com.hippo.ehviewer.client.data.topList.TopListItem;
import com.hippo.ehviewer.client.data.topList.TopListItemArray;

abstract class EhTopListAdapter extends RecyclerView.Adapter<EhTopListAdapter.EhTopListViewHolder> {

    private final Context context;
    private final TopListInfo ehTopListInfo;
    private final int searchType;
    /**
     * 0=yesterday, 1=past month, 2=past year, 3=all-time. The adapter only
     * renders the items belonging to this time bucket; the Scene owns the
     * secondary TabLayout that drives the index.
     */
    private final int timeBucketIndex;

    public EhTopListAdapter(@NonNull Context context, TopListInfo topListInfo, int searchType, int timeBucketIndex) {
        this.context = context;
        this.ehTopListInfo = topListInfo;
        this.searchType = searchType;
        this.timeBucketIndex = timeBucketIndex;
    }

    @NonNull
    @Override
    public EhTopListAdapter.EhTopListViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = View.inflate(context, R.layout.item_top_list_entry, null);
        return new EhTopListViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull EhTopListAdapter.EhTopListViewHolder holder, int position) {
        final TopListItemArray bucket = ehTopListInfo.get(timeBucketIndex);
        if (bucket == null || position >= bucket.length()) {
            return;
        }
        final TopListItem item = bucket.get(position);
        final int rank = position + 1;

        // ---- Rank badge (#1, #2, ... with gold/silver/bronze for top 3) ----
        holder.rankText.setVisibility(View.VISIBLE);
        holder.rankCrown.setVisibility(View.GONE);
        switch (rank) {
            case 1:
                holder.rankContainer.setBackgroundResource(R.drawable.bg_rank_gold);
                holder.rankText.setText(R.string.top_list_rank_prefix);
                holder.rankText.setText("#1");
                holder.rankCrown.setVisibility(View.VISIBLE);
                break;
            case 2:
                holder.rankContainer.setBackgroundResource(R.drawable.bg_rank_silver);
                holder.rankText.setText(context.getString(R.string.top_list_rank_prefix, rank));
                break;
            case 3:
                holder.rankContainer.setBackgroundResource(R.drawable.bg_rank_bronze);
                holder.rankText.setText(context.getString(R.string.top_list_rank_prefix, rank));
                break;
            default:
                holder.rankContainer.setBackgroundResource(R.drawable.bg_rank_normal);
                holder.rankText.setText(context.getString(R.string.top_list_rank_prefix, rank));
                break;
        }

        // ---- Title (full display, no decoration icon to keep it clean) ----
        holder.title.setText(item.value);

        holder.itemView.setOnClickListener(v -> onItemClick(item, searchType));
    }

    /**
     * Resolves a theme attribute (e.g. {@code R.attr.topListPalette1}) to a
     * concrete color using the supplied context. Falls back to the provided
     * default if the attribute is not defined in the active theme.
     */
    protected int resolveThemeColor(@AttrRes int attr, int fallback) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(attr, typedValue, true)) {
            return typedValue.data;
        }
        return fallback;
    }

    abstract void onItemClick(TopListItem topListItem, int searchType);

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

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.isEmpty();
    }

    public static class EhTopListViewHolder extends RecyclerView.ViewHolder {
        public final FrameLayout rankContainer;
        public final TextView rankText;
        public final ImageView rankCrown;
        public final TextView title;

        public EhTopListViewHolder(@NonNull View itemView) {
            super(itemView);
            rankContainer = itemView.findViewById(R.id.top_list_rank_container);
            rankText = itemView.findViewById(R.id.top_list_rank_text);
            rankCrown = itemView.findViewById(R.id.top_list_rank_crown);
            title = itemView.findViewById(R.id.top_list_title);
        }
    }
}