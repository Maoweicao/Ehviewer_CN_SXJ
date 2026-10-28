package com.hippo.ehviewer.db;

import androidx.room.TypeConverter;

import java.util.Date;

/**
 * Room 类型转换器。
 *
 * <p>历史库（greenDAO 时代）把 {@link Date} 存成 INTEGER 毫秒时间戳，
 * 这里保持同样的物理存储，避免迁移时改列类型。
 */
public class Converters {

    @TypeConverter
    public static Date fromTimestamp(Long value) {
        return value == null ? null : new Date(value);
    }

    @TypeConverter
    public static Long toTimestamp(Date date) {
        return date == null ? null : date.getTime();
    }
}
