package com.hippo.ehviewer.task.automation

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.hippo.ehviewer.network.NetworkStateManager
import java.util.Calendar

/**
 * 条件评估器：根据 AutomationConditions 判断当前环境是否满足执行条件。
 * 全部条件用 AND 连接；为空的 condition 一律视为满足。
 */
class ConditionEvaluator(private val context: Context) {

    companion object {
        private const val TAG = "ConditionEvaluator"
    }

    private val appContext = context.applicationContext

    fun evaluate(conditions: AutomationConditions): ConditionResult {
        if (conditions.isEmpty()) return ConditionResult.Met

        if (conditions.requireWifi && !isWifiConnected()) {
            return ConditionResult.NotMet("requires WiFi")
        }
        if (conditions.requireNoMetered && NetworkStateManager.isMetered()) {
            return ConditionResult.NotMet("metered network blocked")
        }
        if (conditions.requireCharging && !isCharging()) {
            return ConditionResult.NotMet("not charging")
        }
        if (conditions.requireScreenOff && isScreenOn()) {
            return ConditionResult.NotMet("screen on")
        }
        if (conditions.minBatteryPercent > 0) {
            val level = batteryLevelPercent()
            if (level < conditions.minBatteryPercent) {
                return ConditionResult.NotMet("battery too low ($level%)")
            }
        }
        conditions.timeWindow?.let { tw ->
            val cal = Calendar.getInstance()
            val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            if (!tw.contains(nowMin)) {
                return ConditionResult.NotMet("outside time window")
            }
        }
        if (conditions.weekdayMask != 0) {
            val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
            val bit = 1 shl (today - 1)
            if (conditions.weekdayMask and bit == 0) {
                return ConditionResult.NotMet("wrong weekday")
            }
        }
        return ConditionResult.Met
    }

    fun isWifiConnected(): Boolean = NetworkStateManager.currentState == NetworkStateManager.State.ONLINE_WIFI

    fun isCharging(): Boolean {
        return try {
            val intent: Intent? = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query battery status", e)
            false
        }
    }

    fun isScreenOn(): Boolean {
        return try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                pm?.isInteractive == true
            } else {
                @Suppress("DEPRECATION")
                pm?.isScreenOn == true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query screen state", e)
            false
        }
    }

    fun batteryLevelPercent(): Int {
        return try {
            val intent: Intent? = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level < 0 || scale <= 0) 0 else (level * 100 / scale)
        } catch (e: Exception) {
            0
        }
    }
}

sealed class ConditionResult {
    object Met : ConditionResult()
    data class NotMet(val reason: String) : ConditionResult()
}