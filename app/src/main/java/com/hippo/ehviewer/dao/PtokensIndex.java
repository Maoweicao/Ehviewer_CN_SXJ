package com.hippo.ehviewer.dao;

import org.greenrobot.greendao.annotation.Entity;
import org.greenrobot.greendao.annotation.Id;
import org.greenrobot.greendao.annotation.Generated;

/**
 * 画廊页面 ptoken 索引实体，用于快速检测递进关系
 * 存储每个已下载画廊的所有页面 ptoken，以便增量检测子集/超集关系
 */
@Entity(nameInDb = "PTOKENS_INDEX")
public class PtokensIndex {

    @Id
    private long gid;

    /** 逗号分隔的 ptoken 字符串 */
    private String ptokens;

    /** 画廊总页数 */
    private int pages;

    /** 索引最后更新时间 */
    private long updatedAt;

    @Generated
    public PtokensIndex() {
    }

    @Generated
    public PtokensIndex(long gid, String ptokens, int pages, long updatedAt) {
        this.gid = gid;
        this.ptokens = ptokens;
        this.pages = pages;
        this.updatedAt = updatedAt;
    }

    public long getGid() {
        return gid;
    }

    public void setGid(long gid) {
        this.gid = gid;
    }

    public String getPtokens() {
        return ptokens;
    }

    public void setPtokens(String ptokens) {
        this.ptokens = ptokens;
    }

    public int getPages() {
        return pages;
    }

    public void setPages(int pages) {
        this.pages = pages;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
