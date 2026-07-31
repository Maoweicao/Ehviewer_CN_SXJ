package com.hippo.ehviewer.task.automation

import androidx.annotation.StringRes
import com.hippo.ehviewer.R

enum class EventTriggerType(
    @StringRes val displayNameResId: Int,
    @StringRes val descriptionResId: Int,
    val filterSupport: FilterSupport,
    val category: Category,
) {
    // 下载类
    DOWNLOAD_FINISHED(
        displayNameResId = R.string.automation_event_download_finished,
        descriptionResId = R.string.automation_event_download_finished_desc,
        filterSupport = FilterSupport.OPTIONAL,
        category = Category.DOWNLOAD,
    ),
    ALL_DOWNLOADS_COMPLETED(
        displayNameResId = R.string.automation_event_all_downloads_completed,
        descriptionResId = R.string.automation_event_all_downloads_completed_desc,
        filterSupport = FilterSupport.NONE,
        category = Category.DOWNLOAD,
    ),
    DOWNLOAD_FAILED(
        displayNameResId = R.string.automation_event_download_failed,
        descriptionResId = R.string.automation_event_download_failed_desc,
        filterSupport = FilterSupport.OPTIONAL,
        category = Category.DOWNLOAD,
    ),
    DOWNLOAD_ADDED(
        displayNameResId = R.string.automation_event_download_added,
        descriptionResId = R.string.automation_event_download_added_desc,
        filterSupport = FilterSupport.OPTIONAL,
        category = Category.DOWNLOAD,
    ),

    // 网络类
    NETWORK_AVAILABLE(
        displayNameResId = R.string.automation_event_network_available,
        descriptionResId = R.string.automation_event_network_available_desc,
        filterSupport = FilterSupport.NONE,
        category = Category.NETWORK,
    ),
    NETWORK_LOST(
        displayNameResId = R.string.automation_event_network_lost,
        descriptionResId = R.string.automation_event_network_lost_desc,
        filterSupport = FilterSupport.NONE,
        category = Category.NETWORK,
    ),
    WIFI_CONNECTED(
        displayNameResId = R.string.automation_event_wifi_connected,
        descriptionResId = R.string.automation_event_wifi_connected_desc,
        filterSupport = FilterSupport.NONE,
        category = Category.NETWORK,
    ),

    // 充电
    CHARGING_STARTED(
        displayNameResId = R.string.automation_event_charging_started,
        descriptionResId = R.string.automation_event_charging_started_desc,
        filterSupport = FilterSupport.NONE,
        category = Category.DEVICE,
    ),
    CHARGING_STOPPED(
        displayNameResId = R.string.automation_event_charging_stopped,
        descriptionResId = R.string.automation_event_charging_stopped_desc,
        filterSupport = FilterSupport.NONE,
        category = Category.DEVICE,
    ),

    // 应用生命周期
    APP_FOREGROUND(
        displayNameResId = R.string.automation_event_app_foreground,
        descriptionResId = R.string.automation_event_app_foreground_desc,
        filterSupport = FilterSupport.NONE,
        category = Category.LIFECYCLE,
    ),
    APP_BACKGROUND(
        displayNameResId = R.string.automation_event_app_background,
        descriptionResId = R.string.automation_event_app_background_desc,
        filterSupport = FilterSupport.NONE,
        category = Category.LIFECYCLE,
    ),

    // 数据变化
    FAVORITE_ADDED(
        displayNameResId = R.string.automation_event_favorite_added,
        descriptionResId = R.string.automation_event_favorite_added_desc,
        filterSupport = FilterSupport.NONE,
        category = Category.DATA,
    ),
    FAVORITE_REMOVED(
        displayNameResId = R.string.automation_event_favorite_removed,
        descriptionResId = R.string.automation_event_favorite_removed_desc,
        filterSupport = FilterSupport.NONE,
        category = Category.DATA,
    ),
    HISTORY_ADDED(
        displayNameResId = R.string.automation_event_history_added,
        descriptionResId = R.string.automation_event_history_added_desc,
        filterSupport = FilterSupport.NONE,
        category = Category.DATA,
    ),
    LOCAL_GALLERY_ADDED(
        displayNameResId = R.string.automation_event_local_gallery_added,
        descriptionResId = R.string.automation_event_local_gallery_added_desc,
        filterSupport = FilterSupport.NONE,
        category = Category.DATA,
    ),

    // 文件 / Intent
    FILE_CREATED(
        displayNameResId = R.string.automation_event_file_created,
        descriptionResId = R.string.automation_event_file_created_desc,
        filterSupport = FilterSupport.REQUIRED,
        category = Category.FILE,
    ),
    FILE_DELETED(
        displayNameResId = R.string.automation_event_file_deleted,
        descriptionResId = R.string.automation_event_file_deleted_desc,
        filterSupport = FilterSupport.REQUIRED,
        category = Category.FILE,
    ),
    CUSTOM_INTENT_RECEIVED(
        displayNameResId = R.string.automation_event_custom_intent,
        descriptionResId = R.string.automation_event_custom_intent_desc,
        filterSupport = FilterSupport.REQUIRED,
        category = Category.EXTENSION,
    );

    enum class Category { DOWNLOAD, NETWORK, DEVICE, LIFECYCLE, DATA, FILE, EXTENSION }
    enum class FilterSupport { NONE, OPTIONAL, REQUIRED }
}