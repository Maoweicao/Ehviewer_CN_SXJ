package com.hippo.ehviewer.task.scheduled.holiday

import java.time.LocalDate

/**
 * 节假日数据
 */
data class Holiday(
    val name: String,           // 节日名称
    val date: LocalDate,        // 日期
    val isOffDay: Boolean       // 是否休息日
)
