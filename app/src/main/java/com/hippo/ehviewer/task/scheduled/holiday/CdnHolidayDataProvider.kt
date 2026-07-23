package com.hippo.ehviewer.task.scheduled.holiday

import android.content.Context
import android.util.Log
import com.hippo.ehviewer.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * CDN 节假日数据提供者
 * 从 https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/ 获取数据
 */
class CdnHolidayDataProvider(
    private val context: Context
) : HolidayDataProvider {
    companion object {
        private const val TAG = "CdnHolidayProvider"
        private const val BASE_URL = "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/"
        private const val CACHE_DIR_NAME = "HolidayCache"
        private const val CACHE_FILE_PREFIX = "holiday_"
        private const val CACHE_FILE_SUFFIX = ".json"
        private const val UPDATE_INTERVAL_MILLIS = 365L * 24 * 60 * 60 * 1000  // 1年
    }

    private val cacheDir = AppConfig.getDirInExternalAppDir(CACHE_DIR_NAME)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val holidayCache = mutableMapOf<Int, List<Holiday>>()
    private var lastUpdateTime: Long? = null

    override suspend fun getHolidays(year: Int): List<Holiday> {
        // 检查内存缓存
        holidayCache[year]?.let { return it }
        
        return withContext(Dispatchers.IO) {
            // 检查文件缓存
            val cached = loadFromCache(year)
            if (cached != null && !isCacheExpired(year)) {
                holidayCache[year] = cached
                return@withContext cached
            }
            
            // 从 CDN 获取
            val fetched = fetchFromCdn(year)
            if (fetched.isNotEmpty()) {
                saveToCache(year, fetched)
                holidayCache[year] = fetched
                return@withContext fetched
            }
            
            // 返回缓存（即使过期）
            if (cached != null) {
                return@withContext cached
            }
            
            emptyList()
        }
    }

    override suspend fun isHoliday(date: LocalDate): Boolean {
        val holidays = getHolidays(date.year)
        return holidays.any { it.date == date && it.isOffDay }
    }

    override suspend fun refreshData() {
        withContext(Dispatchers.IO) {
            val currentYear = LocalDate.now().year
            getHolidays(currentYear)
            try {
                getHolidays(currentYear + 1)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch next year holidays", e)
            }
            lastUpdateTime = System.currentTimeMillis()
        }
    }

    override fun getLastUpdateTime(): Long? {
        if (lastUpdateTime != null) return lastUpdateTime
        
        // 从缓存文件获取
        val cacheFile = getCacheFile(LocalDate.now().year)
        return if (cacheFile.exists()) cacheFile.lastModified() else null
    }

    override fun isDataExpired(): Boolean {
        val lastUpdate = getLastUpdateTime() ?: return true
        return System.currentTimeMillis() - lastUpdate > UPDATE_INTERVAL_MILLIS
    }

    private suspend fun fetchFromCdn(year: Int): List<Holiday> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "${BASE_URL}${year}.json"
                Log.d(TAG, "Fetching holidays from: $url")

                val request = Request.Builder()
                    .url(url)
                    .build()

                val response = httpClient.newCall(request).execute()
                val code = response.code()
                if (code == 200) {
                    val body = response.body()?.string()
                    parseHolidayJson(body, year)
                } else {
                    Log.w(TAG, "Failed to fetch holidays: $code")
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch holidays for $year", e)
                emptyList()
            }
        }
    }

    private fun parseHolidayJson(json: String?, year: Int): List<Holiday> {
        if (json.isNullOrEmpty()) {
            Log.w(TAG, "Empty holiday JSON for year $year")
            return emptyList()
        }

        return try {
            val root = JSONObject(json)
            val dataYear = root.optInt("year", 0)

            // 验证年份
            if (dataYear != year) {
                Log.w(TAG, "Holiday data year mismatch: expected $year, got $dataYear")
                return emptyList()
            }

            val days = root.optJSONArray("days")
            if (days == null || days.length() == 0) {
                Log.w(TAG, "Holiday data is empty for year $year")
                return emptyList()
            }

            val holidays = mutableListOf<Holiday>()
            val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

            for (i in 0 until days.length()) {
                val day = days.getJSONObject(i)
                val name = day.optString("name", "")
                val dateStr = day.optString("date", "")
                val isOffDay = day.optBoolean("isOffDay", false)

                if (name.isNotEmpty() && dateStr.isNotEmpty()) {
                    try {
                        val date = LocalDate.parse(dateStr, dateFormatter)
                        holidays.add(Holiday(name, date, isOffDay))
                    } catch (e: Exception) {
                        Log.w(TAG, "Invalid date format: $dateStr")
                    }
                }
            }

            Log.d(TAG, "Parsed ${holidays.size} holidays for year $year")
            holidays
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse holiday JSON", e)
            emptyList()
        }
    }

    private fun loadFromCache(year: Int): List<Holiday>? {
        val cacheFile = getCacheFile(year)
        if (!cacheFile.exists()) return null

        return try {
            val json = cacheFile.readText()
            parseHolidayJson(json, year)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cache for year $year", e)
            null
        }
    }

    private fun saveToCache(year: Int, holidays: List<Holiday>) {
        if (cacheDir == null) {
            Log.w(TAG, "Cache directory not available")
            return
        }

        try {
            val cacheFile = getCacheFile(year)
            val root = JSONObject().apply {
                put("year", year)
                put("papers", org.json.JSONArray())
                val daysArray = org.json.JSONArray()
                holidays.forEach { holiday ->
                    daysArray.put(JSONObject().apply {
                        put("name", holiday.name)
                        put("date", holiday.date.toString())
                        put("isOffDay", holiday.isOffDay)
                    })
                }
                put("days", daysArray)
            }
            cacheFile.writeText(root.toString(2))
            Log.d(TAG, "Saved ${holidays.size} holidays to cache for year $year")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cache for year $year", e)
        }
    }

    private fun isCacheExpired(year: Int): Boolean {
        val cacheFile = getCacheFile(year)
        if (!cacheFile.exists()) return true
        
        val lastModified = cacheFile.lastModified()
        return System.currentTimeMillis() - lastModified > UPDATE_INTERVAL_MILLIS
    }

    private fun getCacheFile(year: Int): File {
        val dir = cacheDir ?: File(context.filesDir, CACHE_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${CACHE_FILE_PREFIX}${year}${CACHE_FILE_SUFFIX}")
    }
}
