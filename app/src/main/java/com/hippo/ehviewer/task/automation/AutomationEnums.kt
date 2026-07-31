package com.hippo.ehviewer.task.automation

/**
 * 重复模式（用于时间触发器）
 */
enum class RepeatMode {
    ONCE,
    DAILY,
    WEEKDAYS,
    WEEKENDS,
    HOLIDAYS,
    CUSTOM_CRON,
}

/**
 * 多个动作的执行模式
 */
enum class ExecutionMode {
    SEQUENTIAL,
    PARALLEL,
}

/**
 * 失败重试模式
 */
enum class RetryMode {
    NO_RETRY,
    IMMEDIATE,
    DELAYED,
    NEXT_SCHEDULE,
}

/**
 * 自动化任务自身的状态
 */
enum class AutomationState {
    PENDING,
    WAITING_CONDITION,
    WAITING_TIME,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    PAUSED,
    DISABLED,
}