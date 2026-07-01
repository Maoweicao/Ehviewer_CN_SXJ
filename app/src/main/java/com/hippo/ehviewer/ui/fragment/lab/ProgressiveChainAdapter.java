package com.hippo.ehviewer.ui.fragment.lab;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.task.ProgressiveScanTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ProgressiveChainAdapter extends RecyclerView.Adapter<ProgressiveChainAdapter.ChainViewHolder> {

    private final List<ProgressiveScanTask.ProgressiveChain> chains = new ArrayList<>();
    private final Map<Integer, Boolean> expandedMap = new HashMap<>();
    private final Map<Integer, Long> selectedTargetMap = new HashMap<>();

    private OnChainActionListener listener;

    public interface OnChainActionListener {
        void onMerge(long targetGid, List<Long> sourceGids);
        void onIgnore(ProgressiveScanTask.ProgressiveChain chain);
        void onView(ProgressiveScanTask.ProgressiveChain chain);
    }

    public void setOnChainActionListener(OnChainActionListener listener) {
        this.listener = listener;
    }

    public void setChains(List<ProgressiveScanTask.ProgressiveChain> chains) {
        this.chains.clear();
        if (chains != null) {
            this.chains.addAll(chains);
        }
        expandedMap.clear();
        selectedTargetMap.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ChainViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_progressive_chain, parent, false);
        return new ChainViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChainViewHolder holder, int position) {
        ProgressiveScanTask.ProgressiveChain chain = chains.get(position);
        int chainId = chain.getId();
        boolean expanded = Boolean.TRUE.equals(expandedMap.get(chainId));

        holder.title.setText(chain.getDisplayName());
        holder.depth.setText(holder.itemView.getContext().getString(
                R.string.progressive_chain_depth, chain.getDepth()));
        holder.summary.setText(holder.itemView.getContext().getString(
                R.string.progressive_chain_summary,
                chain.getCommonHashes(), chain.getTotalUniqueHashes()));
        holder.expandIcon.setText(expanded ? "\u25B2" : "\u25BC");
        holder.detailsPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);

        holder.foldersContainer.removeAllViews();
        if (expanded) {
            List<ProgressiveScanTask.FolderInfo> folders = chain.getFolders();
            for (int i = 0; i < folders.size(); i++) {
                ProgressiveScanTask.FolderInfo folder = folders.get(i);
                View folderView = LayoutInflater.from(holder.itemView.getContext())
                        .inflate(R.layout.item_progressive_folder, holder.foldersContainer, false);

                RadioButton radio = folderView.findViewById(R.id.folder_radio);
                TextView nameText = folderView.findViewById(R.id.folder_name);
                TextView infoText = folderView.findViewById(R.id.folder_info);
                View arrowView = folderView.findViewById(R.id.folder_arrow);
                View rootView = folderView.findViewById(R.id.folder_root);

                long gid = folder.getGid();
                Long selectedGid = selectedTargetMap.get(chainId);
                if (selectedGid == null && i == 0) {
                    selectedGid = gid;
                    selectedTargetMap.put(chainId, gid);
                }
                radio.setChecked(gid == (selectedGid != null ? selectedGid : -1L));

                nameText.setText(folder.getTitle().isEmpty() ? folder.getName() : folder.getTitle());
                infoText.setText(String.format(Locale.getDefault(),
                        "GID: %d  |  %d pages  |  %d files",
                        folder.getGid(), folder.getHashCount(), folder.getFileCount()));

                if (i == folders.size() - 1) {
                    arrowView.setVisibility(View.GONE);
                } else {
                    arrowView.setVisibility(View.VISIBLE);
                }

                radio.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) {
                        selectedTargetMap.put(chainId, gid);
                        for (int j = 0; j < holder.foldersContainer.getChildCount(); j++) {
                            View child = holder.foldersContainer.getChildAt(j);
                            RadioButton otherRadio = child.findViewById(R.id.folder_radio);
                            if (otherRadio != null && otherRadio != buttonView) {
                                otherRadio.setChecked(false);
                            }
                        }
                    }
                });

                rootView.setOnClickListener(v -> radio.setChecked(true));

                holder.foldersContainer.addView(folderView);
            }
        }

        holder.header.setOnClickListener(v -> {
            boolean isExpanded = Boolean.TRUE.equals(expandedMap.get(chainId));
            expandedMap.put(chainId, !isExpanded);
            notifyItemChanged(position);
        });

        holder.btnMerge.setOnClickListener(v -> {
            if (listener == null) return;
            Long targetGid = selectedTargetMap.get(chainId);
            if (targetGid == null) {
                targetGid = chain.getFolders().get(0).getGid();
            }
            List<Long> sourceGids = new ArrayList<>();
            for (ProgressiveScanTask.FolderInfo f : chain.getFolders()) {
                if (f.getGid() != targetGid) {
                    sourceGids.add(f.getGid());
                }
            }
            if (sourceGids.isEmpty()) return;
            listener.onMerge(targetGid, sourceGids);
        });
        holder.btnIgnore.setOnClickListener(v -> {
            if (listener != null) listener.onIgnore(chain);
        });
        holder.btnView.setOnClickListener(v -> {
            if (listener != null) listener.onView(chain);
        });
    }

    @Override
    public int getItemCount() {
        return chains.size();
    }

    static class ChainViewHolder extends RecyclerView.ViewHolder {
        View header;
        TextView title;
        TextView depth;
        TextView expandIcon;
        TextView summary;
        View detailsPanel;
        LinearLayout foldersContainer;
        Button btnMerge;
        Button btnIgnore;
        Button btnView;

        ChainViewHolder(@NonNull View itemView) {
            super(itemView);
            header = itemView.findViewById(R.id.header);
            title = itemView.findViewById(R.id.chain_title);
            depth = itemView.findViewById(R.id.chain_depth);
            expandIcon = itemView.findViewById(R.id.expand_icon);
            summary = itemView.findViewById(R.id.chain_summary);
            detailsPanel = itemView.findViewById(R.id.details_panel);
            foldersContainer = itemView.findViewById(R.id.folders_container);
            btnMerge = itemView.findViewById(R.id.btn_merge);
            btnIgnore = itemView.findViewById(R.id.btn_ignore);
            btnView = itemView.findViewById(R.id.btn_view);
        }
    }
}
