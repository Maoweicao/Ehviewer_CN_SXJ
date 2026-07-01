package com.hippo.ehviewer.dao;

import org.greenrobot.greendao.annotation.Entity;
import org.greenrobot.greendao.annotation.Id;
import org.greenrobot.greendao.annotation.NotNull;
import org.greenrobot.greendao.annotation.Generated;

@Entity
public class MilestoneInfo {
    @Id
    private Long id;

    @NotNull
    private String key;

    private String value;

    private long updateTime;

    @Generated(hash = 1849103488)
    public MilestoneInfo(Long id, @NotNull String key, String value, long updateTime) {
        this.id = id;
        this.key = key;
        this.value = value;
        this.updateTime = updateTime;
    }

    @Generated(hash = 1453418844)
    public MilestoneInfo() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }
}
