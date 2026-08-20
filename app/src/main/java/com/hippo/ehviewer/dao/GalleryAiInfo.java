package com.hippo.ehviewer.dao;

import org.greenrobot.greendao.annotation.Entity;
import org.greenrobot.greendao.annotation.Generated;
import org.greenrobot.greendao.annotation.Id;

/** AI 图片分析结果（用于下载列表的按描述搜索）。 */
@Entity(nameInDb = "GALLERY_AI_INFO")
public class GalleryAiInfo {

    @Id
    private long gid;
    private String summary;
    private String tags;
    private String descriptions;
    private float aestheticScore;
    private long updatedAt;

    @Generated
    public GalleryAiInfo() {
    }

    @Generated
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
