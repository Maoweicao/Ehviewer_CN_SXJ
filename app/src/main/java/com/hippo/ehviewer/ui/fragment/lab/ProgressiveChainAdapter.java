package com.hippo.ehviewer.ui.fragment.lab;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
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
    private final Map<Integer, Integer> selectedTargetIndex = new HashMap<>();

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
        selectedTargetIndex.clear();
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
        List<ProgressiveScanTask.FolderInfo> folders = chain.getFolders();

        int selIndex = selectedTargetIndex.getOrDefault(chainId, 0);
        ProgressiveScanTask.FolderInfo targetFolder = selIndex < folders.size()
                ? folders.get(selIndex) : folders.get(0);

        holder.title.setText(targetFolder.getTitle().isEmpty()
                ? targetFolder.getName() : targetFolder.getTitle());
        holder.depth.setText(holder.itemView.getContext().getString(
                R.string.progressive_chain_depth, chain.getDepth()));
        holder.expandIcon.setText(expanded ? "\u25B2" : "\u25BC");
        holder.detailsPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);

        if (expanded) {
            holder.summary.setText(holder.itemView.getContext().getString(
                    R.string.progressive_chain_summary,
                    chain.getCommonHashes(), chain.getTotalUniqueHashes()));

            List<String> folderNames = new ArrayList<>();
            for (ProgressiveScanTask.FolderInfo f : folders) {
                String display = f.getTitle().isEmpty() ? f.getName() : f.getTitle();
                if (display.length() > 40) {
                    display = display.substring(0, 37) + "...";
                }
                folderNames.add(display + " (" + f.getHashCount() + "p)");
            }

            ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                    holder.itemView.getContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    folderNames);
            holder.spinner.setAdapter(spinnerAdapter);
            holder.spinner.setSelection(selIndex);
            holder.spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    selectedTargetIndex.put(chainId, position);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });

            holder.foldersContainer.removeAllViews();
            for (int i = 0; i < folders.size(); i++) {
                ProgressiveScanTask.FolderInfo folder = folders.get(i);
                View folderView = LayoutInflater.from(holder.itemView.getContext())
                        .inflate(R.layout.item_progressive_folder, holder.foldersContainer, false);

                TextView nameText = folderView.findViewById(R.id.folder_name);
                TextView infoText = folderView.findViewById(R.id.folder_info);
                View arrowView = folderView.findViewById(R.id.folder_arrow);

                nameText.setText(folder.getTitle().isEmpty() ? folder.getName() : folder.getTitle());
                infoText.setText(String.format(Locale.getDefault(),
                        "GID: %d  |  %d pages  |  %d files",
                        folder.getGid(), folder.getHashCount(), folder.getFileCount()));

                if (i == folders.size() - 1) {
                    arrowView.setVisibility(View.GONE);
                } else {
                    arrowView.setVisibility(View.VISIBLE);
                }

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
            int ti = selectedTargetIndex.getOrDefault(chainId, 0);
            if (ti >= folders.size()) ti = 0;
            long targetGid = folders.get(ti).getGid();
            List<Long> sourceGids = new ArrayList<>();
            for (ProgressiveScanTask.FolderInfo f : folders) {
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

    public long getSelectedTargetGid(int chainId, ProgressiveScanTask.ProgressiveChain chain) {
        int ti = selectedTargetIndex.getOrDefault(chainId, 0);
        List<ProgressiveScanTask.FolderInfo> folders = chain.getFolders();
        if (ti >= folders.size()) ti = 0;
        return folders.get(ti).getGid();
    }

    static class ChainViewHolder extends RecyclerView.ViewHolder {
        View header;
        TextView title;
        TextView depth;
        TextView expandIcon;
        View detailsPanel;
        TextView summary;
        Spinner spinner;
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
            detailsPanel = itemView.findViewById(R.id.details_panel);
            summary = itemView.findViewById(R.id.chain_summary);
            spinner = itemView.findViewById(R.id.spinner_target);
            foldersContainer = itemView.findViewById(R.id.folders_container);
            btnMerge = itemView.findViewById(R.id.btn_merge);
            btnIgnore = itemView.findViewById(R.id.btn_ignore);
            btnView = itemView.findViewById(R.id.btn_view);
        }
    }
}
