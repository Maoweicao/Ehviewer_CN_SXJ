package com.hippo.ehviewer.task.scheduled

import java.util.Calendar

/**
 * Cron 表达式构建器
 * 支持格式: 分 时 日 月 周 年
 */
class CronExpressionBuilder {
    data class CronConfig(
        val minute: Int = 0,          // 0-59
        val hour: Int = 0,            // 0-23
        val dayOfMonth: String = "*", // *, 1-31, */2, 1,15
        val month: String = "*",      // *, 1-12, */3
        val dayOfWeek: String = "*",  // *, 0-6, 1-5
        val year: String = "*"        // *, 2024, 2024-2026
    )

    fun build(config: CronConfig): String {
        return "${config.minute} ${config.hour} ${config.dayOfMonth} ${config.month} ${config.dayOfWeek} ${config.year}"
    }

    fun parse(expression: String): CronConfig {
        val parts = expression.trim().split("\\s+".toRegex())
        if (parts.size < 5) {
            throw IllegalArgumentException("Invalid cron expression: $expression")
        }

        return CronConfig(
            minute = parts[0].toIntOrNull() ?: 0,
            hour = parts[1].toIntOrNull() ?: 0,
            dayOfMonth = parts[2],
            month = parts[3],
            dayOfWeek = parts[4],
            year = if (parts.size > 5) parts[5] else "*"
        )
    }

    fun getDescription(expression: String): String {
        return try {
            val config = parse(expression)
            buildDescription(config)
        } catch (e: Exception) {
            "无效的表达式: $expression"
        }
    }

    @JvmOverloads
    fun getNextExecutionTime(expression: String, from: Long = System.currentTimeMillis()): Long? {
        return try {
            val config = parse(expression)
            calculateNextExecution(config, from)
        } catch (e: Exception) {
            null
        }
    }

    @JvmOverloads
    fun getNextNExecutionTimes(expression: String, n: Int, from: Long = System.currentTimeMillis()): List<Long> {
        val times = mutableListOf<Long>()
        var current = from

        repeat(n) {
            val next = getNextExecutionTime(expression, current)
            if (next != null) {
                times.add(next)
                current = next + 1  // 加1毫秒避免重复
            }
        }

        return times
    }

    // 预设配置
    fun dailyAt(hour: Int, minute: Int): CronConfig {
        return CronConfig(minute = minute, hour = hour)
    }

    fun weeklyAt(dayOfWeek: Int, hour: Int, minute: Int): CronConfig {
        return CronConfig(minute = minute, hour = hour, dayOfWeek = dayOfWeek.toString())
    }

    fun monthlyAt(dayOfMonth: Int, hour: Int, minute: Int): CronConfig {
        return CronConfig(minute = minute, hour = hour, dayOfMonth = dayOfMonth.toString())
    }

    private fun buildDescription(config: CronConfig): String {
        val sb = StringBuilder()

        // 时间部分
        sb.append("在 ")
        sb.append(String.format("%02d:%02d", config.hour, config.minute))

        // 日期部分
        when {
            config.dayOfMonth == "*" && config.dayOfWeek == "*" -> sb.append(" 每天")
            config.dayOfWeek != "*" -> {
                sb.append(" 每")
                val days = parseDayOfWeek(config.dayOfWeek)
                sb.append(days.joinToString("、") { getDayName(it) })
            }
            config.dayOfMonth != "*" -> {
                sb.append(" 每月")
                sb.append(config.dayOfMonth)
                sb.append("日")
            }
        }

        // 月份部分
        if (config.month != "*") {
            sb.append(" 的")
            sb.append(config.month)
            sb.append("月")
        }

        return sb.toString()
    }

    private fun parseDayOfWeek(dayOfWeek: String): List<Int> {
        return when {
            dayOfWeek == "*" -> (0..6).toList()
            dayOfWeek.contains(",") -> dayOfWeek.split(",").map { it.trim().toIntOrNull() ?: 0 }
            dayOfWeek.contains("-") -> {
                val parts = dayOfWeek.split("-")
                val start = parts[0].trim().toIntOrNull() ?: 0
                val end = parts[1].trim().toIntOrNull() ?: 0
                (start..end).toList()
            }
            else -> listOf(dayOfWeek.toIntOrNull() ?: 0)
        }
    }

    private fun getDayName(day: Int): String {
        return when (day) {
            0 -> "周日"
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            else -> "未知"
        }
    }

    private fun calculateNextExecution(config: CronConfig, from: Long): Long? {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = from

        // 设置秒和毫秒为0
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        // 尝试最多366天（一年）
        repeat(366) {
            // 检查日期是否匹配
            if (matchesDate(config, calendar)) {
                // 设置时间
                calendar.set(Calendar.HOUR_OF_DAY, config.hour)
                calendar.set(Calendar.MINUTE, config.minute)

                // 如果时间还没过，返回
                if (calendar.timeInMillis > from) {
                    return calendar.timeInMillis
                }
            }

            // 移到下一天
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
        }

        return null
    }

    private fun matchesDate(config: CronConfig, calendar: Calendar): Boolean {
        // 检查月份
        if (config.month != "*") {
            val month = calendar.get(Calendar.MONTH) + 1  // Calendar.MONTH 从0开始
            if (!matchesValue(config.month, month)) return false
        }

        // 检查日
        if (config.dayOfMonth != "*") {
            val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
            if (!matchesValue(config.dayOfMonth, dayOfMonth)) return false
        }

        // 检查星期
        if (config.dayOfWeek != "*") {
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1  // 转换为0-6（周日=0）
            if (!matchesValue(config.dayOfWeek, dayOfWeek)) return false
        }

        // 检查年
        if (config.year != "*") {
            val year = calendar.get(Calendar.YEAR)
            if (!matchesValue(config.year, year)) return false
        }

        return true
    }

    private fun matchesValue(pattern: String, value: Int): Boolean {
        return when {
            pattern == "*" -> true
            pattern.contains(",") -> pattern.split(",").any { matchesValue(it.trim(), value) }
            pattern.contains("-") -> {
                val parts = pattern.split("-")
                val start = parts[0].trim().toIntOrNull() ?: return false
                val end = parts[1].trim().toIntOrNull() ?: return false
                value in start..end
            }
            pattern.startsWith("*/") -> {
                val step = pattern.substring(2).toIntOrNull() ?: return false
                value % step == 0
            }
            else -> pattern.toIntOrNull() == value
        }
    }

    companion object {
        // 常用预设
        val DAILY_00_00 = CronConfig(minute = 0, hour = 0)
        val DAILY_02_00 = CronConfig(minute = 0, hour = 2)
        val DAILY_06_00 = CronConfig(minute = 0, hour = 6)
        val WEEKLY_MONDAY_00_00 = CronConfig(minute = 0, hour = 0, dayOfWeek = "1")
        val WEEKLY_SUNDAY_00_00 = CronConfig(minute = 0, hour = 0, dayOfWeek = "0")
        val MONTHLY_1ST_00_00 = CronConfig(minute = 0, hour = 0, dayOfMonth = "1")
    }
}
