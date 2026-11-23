package com.hippo.ehviewer.ui.scene.download;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import com.hippo.android.resource.AttrResources;
import com.hippo.ehviewer.BackgroundTaskManager;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabel;
import com.hippo.ehviewer.dao.QuickSearch;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.filter.TagCoverage;
import com.hippo.ehviewer.download.tag.TagCategory;
import com.hippo.ehviewer.download.tag.TagCategoryBuilder;
import com.hippo.ehviewer.download.tag.TagStore;
import com.hippo.ehviewer.task.BackfillGalleryTagsTask;
import com.hippo.ehviewer.widget.TagModeStrip;
import com.hippo.scene.Announcer;
import com.hippo.util.IoThreadPoolExecutor;
import com.hippo.lib.yorozuya.ObjectUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 下载管理器右侧抽屉。
 *
 * 默认只有下载标签（文件夹）；开启「下载管理器启用画廊书签分类」后多一层模式条：
 *  - 默认：原来的标签列表；
 *  - 画廊书签：快速搜索条目 + 命中数，点击把整个下载列表按该标签圈定。
 *
 * 计数在 IO 线程完成，只读一次 {@code Gallery_Tags}；下载变化时由
 * [DownloadsScene] 调用 [updateDownloadLabels] 重新统计。
 */
public class DownloadLabelDraw {

    private static final int MODE_DEFAULT = 0;
    private static final int MODE_TAGS = 1;

    private final LayoutInflater inflater;
    private final DownloadsScene scene;
    private final ViewGroup container;
    private final Context context;

    private View view;
    private Toolbar toolbar;
    private ListView listView;
    private ViewGroup modeStripContainer;
    private ProgressBar tagProgress;
    private TextView tagFooter;
    private TextView tip;
    private TagModeStrip modeStrip;

    private DownloadLabelAdapter labelAdapter;
    private DownloadLabelAdapter tagAdapter;
    private final List<DownloadLabelItem> labelItems = new ArrayList<>();
    private final List<DownloadLabelItem> tagItems = new ArrayList<>();
    private List<TagCategory> categories = new ArrayList<>();

    private int mode = MODE_DEFAULT;
    private int tagRequestId = 0;
    private int taggedCount = 0;
    private int totalCount = 0;

    public DownloadLabelDraw(LayoutInflater inflater, @Nullable ViewGroup container, DownloadsScene scene) {
        this.inflater = inflater;
        this.container = container;
        this.scene = scene;
        this.context = scene.getEHContext();
    }

    /**
     * 获取与当前主题兼容的对话框上下文
     */
    private Context getDialogContext() {
        return com.hippo.ehviewer.utils.DialogUtils.getDialogContext(context);
    }

    public View createView() {
        view = inflater.inflate(R.layout.download_drawer, container, false);
        assert context != null;

        toolbar = view.findViewById(R.id.toolbar);
        modeStripContainer = view.findViewById(R.id.mode_strip_container);
        tagProgress = view.findViewById(R.id.tag_progress);
        tagFooter = view.findViewById(R.id.tag_footer);
        tip = view.findViewById(R.id.tip);
        listView = view.findViewById(R.id.list_view);

        toolbar.setTitle(R.string.download_labels);
        toolbar.inflateMenu(R.menu.drawer_download);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            switch (id) {
                case R.id.action_settings:
                    scene.startScene(new Announcer(DownloadLabelsScene.class));
                    return true;
                case R.id.action_default_download_label:
                    DownloadManager dm = scene.getMDownloadManager();
                    if (null == dm) {
                        return true;
                    }

                    List<DownloadLabel> list = dm.getLabelList();
                    final String[] items = new String[list.size() + 2];
                    items[0] = scene.getString(R.string.let_me_select);
                    items[1] = scene.getString(R.string.default_download_label_name);
                    for (int i = 0, n = list.size(); i < n; i++) {
                        items[i + 2] = list.get(i).getLabel();
                    }
                    Context dialogCtx = getDialogContext();
                    if (dialogCtx == null) return true;
                    new AlertDialog.Builder(dialogCtx)
                            .setTitle(R.string.default_download_label)
                            .setItems(items, (dialog, which) -> {
                                if (which == 0) {
                                    Settings.putHasDefaultDownloadLabel(false);
                                } else {
                                    Settings.putHasDefaultDownloadLabel(true);
                                    String label;
                                    if (which == 1) {
                                        label = null;
                                    } else {
                                        label = items[which];
                                    }
                                    Settings.putDefaultDownloadLabel(label);
                                }
                            }).show();
                    return true;
            }
            return false;
        });

        labelAdapter = new DownloadLabelAdapter(Objects.requireNonNull(scene.getEHContext()),
                R.layout.item_download_label_list, labelItems);
        tagAdapter = new DownloadLabelAdapter(Objects.requireNonNull(scene.getEHContext()),
                R.layout.item_download_label_list, tagItems);

        listView.setOnItemClickListener((parent, view1, position, id) -> onRowClick(position));

        setupModeStrip();

        if (Settings.getDownloadTagCategoryEnabled() && !isNullOrEmpty(scene.getTagQuery())) {
            setMode(MODE_TAGS, false);
        } else {
            setMode(MODE_DEFAULT, false);
        }
        return view;
    }

    private void setupModeStrip() {
        if (!Settings.getDownloadTagCategoryEnabled()) {
            modeStripContainer.setVisibility(View.GONE);
            return;
        }
        int themeAccent;
        try {
            themeAccent = AttrResources.getAttrColor(context, androidx.appcompat.R.attr.colorPrimary);
        } catch (Throwable e) {
            themeAccent = context.getResources().getColor(R.color.tag_category_bookmark);
        }
        int bookmarkAccent = context.getResources().getColor(R.color.tag_category_bookmark);
        if (themeAccent == 0) {
            themeAccent = bookmarkAccent;
        }

        modeStrip = new TagModeStrip(context);
        List<TagModeStrip.Mode> modes = new ArrayList<>(2);
        modes.add(new TagModeStrip.Mode(context.getString(R.string.tag_category_mode_default), themeAccent));
        modes.add(new TagModeStrip.Mode(context.getString(R.string.tag_category_mode_bookmarks), bookmarkAccent));
        modeStrip.setModes(modes, 0);
        modeStrip.setOnModeSelected(index -> setMode(index == MODE_TAGS ? MODE_TAGS : MODE_DEFAULT, false));

        modeStripContainer.removeAllViews();
        modeStripContainer.addView(modeStrip, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        modeStripContainer.setVisibility(View.VISIBLE);
    }

    private void setMode(int newMode, boolean keepSelection) {
        mode = newMode;
        if (modeStrip != null) {
            modeStrip.select(newMode, false);
        }
        if (newMode == MODE_TAGS) {
            toolbar.setTitle(R.string.tag_category_mode_bookmarks);
            listView.setAdapter(tagAdapter);
            tagFooter.setVisibility(View.VISIBLE);
            tagFooter.setOnClickListener(v -> confirmBackfill());
            rebuildTagCategories();
        } else {
            toolbar.setTitle(R.string.download_labels);
            listView.setAdapter(labelAdapter);
            tagFooter.setVisibility(View.GONE);
            tagProgress.setVisibility(View.GONE);
            updateLabelList();
            updateTip();
        }
    }

    private void onRowClick(int position) {
        if (scene.searching) {
            Toast.makeText(context, R.string.download_searching, Toast.LENGTH_LONG).show();
            return;
        }
        if (mode == MODE_TAGS) {
            if (position <= 0) {
                scene.clearTagScope();
                scene.closeDrawer(Gravity.RIGHT);
                return;
            }
            int index = position - 1;
            if (index < 0 || index >= categories.size()) {
                return;
            }
            TagCategory category = categories.get(index);
            if (!category.getValid()) {
                Toast.makeText(context, R.string.tag_category_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            scene.applyTagScope(category.getKeyword(), category.getName());
            scene.closeDrawer(Gravity.RIGHT);
            return;
        }

        List<DownloadLabel> list = EhApplication.getDownloadManager(context).getLabelList();
        List<String> labels = new ArrayList<>(list.size() + 1);
        labels.add(scene.getString(R.string.default_download_label_name));
        for (DownloadLabel raw : list) {
            labels.add(raw.getLabel());
        }
        if (position < 0 || position >= labels.size()) {
            return;
        }
        String label = position == 0 ? null : labels.get(position);
        if (!ObjectUtils.equal(label, scene.mLabel) || scene.getTagQuery() != null) {
            scene.applyLabel(label);
            if (scene.searchKey != null && !scene.searchKey.isEmpty()) {
                scene.startSearching();
            } else {
                scene.updateView();
            }
            scene.closeDrawer(Gravity.RIGHT);
        }
    }

    private void updateLabelList() {
        final DownloadManager downloadManager = EhApplication.getDownloadManager(context);
        List<DownloadLabel> list = downloadManager.getLabelList();
        List<DownloadLabelItem> items = new ArrayList<>(list.size() + 1);
        items.add(new DownloadLabelItem(scene.getString(R.string.default_download_label_name),
                downloadManager.getDefaultDownloadInfoList().size()));
        for (DownloadLabel raw : list) {
            items.add(new DownloadLabelItem(raw.getLabel(), downloadManager.getLabelCount(raw.getLabel())));
        }
        labelItems.clear();
        labelItems.addAll(items);
        labelAdapter.updateData(labelItems);
        labelAdapter.clearSelection();
        updateTip();
    }

    private void updateTip() {
        if (tip == null || listView == null) {
            return;
        }
        if (mode == MODE_TAGS) {
            boolean empty = categories.isEmpty();
            if (empty) {
                tip.setText(R.string.tag_category_no_bookmarks);
                tip.setVisibility(View.VISIBLE);
                listView.setVisibility(View.GONE);
            } else {
                tip.setVisibility(View.GONE);
                listView.setVisibility(View.VISIBLE);
            }
        } else {
            tip.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 重新统计书签分类命中数（IO 线程）。
     */
    private void rebuildTagCategories() {
        final int requestId = ++tagRequestId;
        tagProgress.setVisibility(View.VISIBLE);
        tip.setVisibility(View.GONE);
        listView.setVisibility(View.GONE);

        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            List<QuickSearch> searches;
            Map<Long, Set<String>> table;
            List<DownloadInfo> all = new ArrayList<>();
            try {
                searches = EhDB.getAllQuickSearch();
            } catch (Throwable t) {
                searches = new ArrayList<>();
            }
            try {
                table = TagStore.loadAll();
            } catch (Throwable t) {
                table = new HashMap<>();
            }
            DownloadManager dm = scene.getMDownloadManager();
            if (dm != null) {
                all.addAll(dm.getAllDownloadInfoList());
            }

            Set<Long> gids = new HashSet<>(all.size());
            Map<Long, Set<String>> merged = new HashMap<>(table);
            for (DownloadInfo info : all) {
                gids.add(info.gid);
                Set<String> memoryTags = TagStore.tokensOf(info);
                if (!memoryTags.isEmpty()) {
                    Set<String> target = new HashSet<>();
                    Set<String> fromTable = merged.get(info.gid);
                    if (fromTable != null) {
                        target.addAll(fromTable);
                    }
                    target.addAll(memoryTags);
                    merged.put(info.gid, target);
                }
            }

            List<TagCategory> result = TagCategoryBuilder.build(searches, merged, gids);
            int tagged = TagCoverage.countTagged(all, table.keySet());
            int total = all.size();
            final List<TagCategory> finalResult = result;

            if (view != null) {
                view.post(() -> {
                    if (requestId != tagRequestId || view == null) {
                        return;
                    }
                    applyTagCategories(finalResult, tagged, total);
                });
            }
        });
    }

    private void applyTagCategories(List<TagCategory> result, int tagged, int total) {
        categories = result;
        taggedCount = tagged;
        totalCount = total;

        List<DownloadLabelItem> items = new ArrayList<>(result.size() + 1);
        items.add(new TagCategoryItem(context.getString(R.string.tag_category_all), total, true, true, null));
        for (TagCategory category : result) {
            items.add(new TagCategoryItem(category.getName(), category.getCount(),
                    category.getValid(), false, category.getKeyword()));
        }
        tagItems.clear();
        tagItems.addAll(items);
        tagAdapter.updateData(tagItems);

        // 高亮当前生效的标签
        String active = scene.getTagQuery();
        int selected = -1;
        if (active != null) {
            for (int i = 0; i < result.size(); i++) {
                if (active.equals(result.get(i).getKeyword())) {
                    selected = i + 1;
                    break;
                }
            }
        }
        tagAdapter.setSelection(selected, context.getResources().getColor(R.color.tag_category_bookmark));

        tagFooter.setText(context.getString(R.string.tag_category_coverage, taggedCount, totalCount));
        tagFooter.setVisibility(View.VISIBLE);

        tagProgress.setVisibility(View.GONE);
        updateTip();
    }

    private void confirmBackfill() {
        Context dialogCtx = getDialogContext();
        if (dialogCtx == null) {
            return;
        }
        new AlertDialog.Builder(dialogCtx)
                .setTitle(R.string.backfill_gallery_tags_title)
                .setMessage(R.string.backfill_gallery_tags_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    BackgroundTaskManager.getInstance().submitBackgroundTask(
                            new BackfillGalleryTagsTask(context));
                    Toast.makeText(context, R.string.backfill_gallery_tags_started, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static boolean isNullOrEmpty(@Nullable String value) {
        return value == null || value.isEmpty();
    }

    public void updateDownloadLabels() {
        if (mode == MODE_DEFAULT) {
            updateLabelList();
        } else {
            rebuildTagCategories();
        }
    }
}
