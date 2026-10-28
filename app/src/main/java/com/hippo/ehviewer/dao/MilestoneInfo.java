package com.hippo.ehviewer.dao;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "MILESTONE")
public class MilestoneInfo {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    private Long id;

    @NonNull
    @ColumnInfo(name = "KEY")
    private String key;

    @ColumnInfo(name = "VALUE")
    private String value;

    @ColumnInfo(name = "UPDATE_TIME")
    private long updateTime;

    @Ignore
    public MilestoneInfo(Long id, String key, String value, long updateTime) {
        this.id = id;
        this.key = key;
        this.value = value;
        this.updateTime = updateTime;
    }

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
