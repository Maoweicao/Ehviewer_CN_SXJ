package com.hippo.ehviewer.task.scheduled

/**
 * 延时条件类型
 */
enum class DelayCondition {
    IMMEDIATE,              // 立即执行
    DOWNLOAD_COMPLETE,      // 所有下载任务完成时
    OTHER_TASKS_COMPLETE,   // 除自己以外后台任务完成时
    SPECIFIC_TIME           // 指定时间执行
}

/**
 * 重复模式
 */
enum class RepeatMode {
    ONCE,                   // 单次
    DAILY,                  // 每天
    WEEKDAYS,               // 工作日（用户自定义周一至周日）
    WEEKENDS,               // 周末（自动计算）
    HOLIDAYS,               // 节假日（CDN 数据）
    CUSTOM_CRON             // 自定义 Cron 表达式
}

/**
 * 定时任务状态
 */
enum class ScheduledTaskState {
    PENDING,                // 等待中
    WAITING_CONDITION,      // 等待条件满足
    WAITING_TIME,           // 等待执行时间
    QUEUED,                 // 已加入队列
    RUNNING,                // 运行中
    COMPLETED,              // 已完成
    FAILED,                 // 失败
    CANCELLED,              // 已取消
    PAUSED                  // 暂停
}

/**
 * 重试模式
 */
enum class RetryMode {
    NO_RETRY,               // 不重试
    IMMEDIATE,              // 立即重试
    DELAYED,                // 延迟重试
    NEXT_SCHEDULE           // 下次执行时重试
}

/**
 * 任务组执行模式
 */
enum class ExecutionMode {
    SEQUENTIAL,    // 串行执行（默认）
    PARALLEL       // 并行执行
}
