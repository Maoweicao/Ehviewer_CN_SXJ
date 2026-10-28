package com.hippo.ehviewer.dao;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** AI 图片分析结果（用于下载列表的按描述搜索）。 */
@Entity(tableName = "GALLERY_AI_INFO")
public class GalleryAiInfo {

    @PrimaryKey
    @ColumnInfo(name = "GID")
    private long gid;
    @ColumnInfo(name = "SUMMARY")
    private String summary;
    @ColumnInfo(name = "TAGS")
    private String tags;
    @ColumnInfo(name = "DESCRIPTIONS")
    private String descriptions;
    @ColumnInfo(name = "AESTHETIC_SCORE")
    private float aestheticScore;
    @ColumnInfo(name = "UPDATED_AT")
    private long updatedAt;

    public GalleryAiInfo() {
    }

    @Ignore
    public GalleryAiInfo(long gid, String summary, String tags, String descriptions,
            float aestheticScore, long updatedAt) {
        this.gid = gid;
        this.summary = summary;
        this.tags = tags;
        this.descriptions = descriptions;
        this.aestheticScore = aestheticScore;
        this.updatedAt = updatedAt;
    }

    public long getGid() { return gid; }
    public void setGid(long gid) { this.gid = gid; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getDescriptions() { return descriptions; }
    public void setDescriptions(String descriptions) { this.descriptions = descriptions; }
    public float getAestheticScore() { return aestheticScore; }
    public void setAestheticScore(float aestheticScore) { this.aestheticScore = aestheticScore; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
