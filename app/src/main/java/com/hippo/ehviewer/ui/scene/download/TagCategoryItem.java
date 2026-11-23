package com.hippo.ehviewer.ui.scene.download;

/**
 * 右滑面板「画廊书签」里的一个分类行。
 *
 * 复用下载标签的行布局（名称 + 右侧数量）；非标签条件显示“—”而不是 0，
 * 避免把“无法判定”伪装成“命中 0 条”。
 */
class TagCategoryItem extends DownloadLabelItem {

    final boolean valid;
    final boolean allRow;
    final String keyword;

    TagCategoryItem(String label, long count, boolean valid, boolean allRow, String keyword) {
        super(label, count);
        this.valid = valid;
        this.allRow = allRow;
        this.keyword = keyword;
    }

    @Override
    public String count() {
        return valid ? super.count() : "—";
    }
}
