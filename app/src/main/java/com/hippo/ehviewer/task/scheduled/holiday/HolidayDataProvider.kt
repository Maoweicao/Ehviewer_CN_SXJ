package com.hippo.ehviewer.task.scheduled.holiday

import java.time.LocalDate

/**
 * 节假日数据提供者接口
 */
interface HolidayDataProvider {
    /**
     * 获取指定年份的节假日数据
     */
    suspend fun getHolidays(year: Int): List<Holiday>
    
    /**
     * 检查指定日期是否为节假日（休息日）
     */
    suspend fun isHoliday(date: LocalDate): Boolean
    
    /**
     * 刷新节假日数据
     */
    suspend fun refreshData()
    
    /**
     * 获取最后更新时间
     */
    fun getLastUpdateTime(): Long?
    
    /**
     * 检查数据是否需要更新（超过1年）
     */
    fun isDataExpired(): Boolean
}
