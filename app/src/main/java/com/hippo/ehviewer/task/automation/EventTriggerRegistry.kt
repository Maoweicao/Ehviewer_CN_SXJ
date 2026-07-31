package com.hippo.ehviewer.task.automation

import com.hippo.ehviewer.task.BackgroundTask

/**
 * 事件触发器注册表。
 * 集中存储每个事件触发器的显示/分类元数据，UI 与事件分发都从这里读取。
 */
object EventTriggerRegistry {

    data class EventTriggerMetadata(
        val type: EventTriggerType,
        val taskType: BackgroundTask.TaskType?,
        val supportsFilter: Boolean,
    )

    private val registry = LinkedHashMap<EventTriggerType, EventTriggerMetadata>()

    init {
        registerDownloads()
        registerNetwork()
        registerDevice()
        registerLifecycle()
        registerData()
        registerFileAndIntent()
    }

    fun register(metadata: EventTriggerMetadata) {
        registry[metadata.type] = metadata
    }

    fun get(type: EventTriggerType): EventTriggerMetadata? = registry[type]

    fun getAll(): List<EventTriggerMetadata> = registry.values.toList()

    fun getByCategory(category: EventTriggerType.Category): List<EventTriggerMetadata> =
        registry.values.filter { it.type.category == category }

    private fun registerDownloads() {
        register(EventTriggerMetadata(EventTriggerType.DOWNLOAD_FINISHED, BackgroundTask.TaskType.DOWNLOAD, true))
        register(EventTriggerMetadata(EventTriggerType.ALL_DOWNLOADS_COMPLETED, BackgroundTask.TaskType.DOWNLOAD, false))
        register(EventTriggerMetadata(EventTriggerType.DOWNLOAD_FAILED, BackgroundTask.TaskType.DOWNLOAD, true))
        register(EventTriggerMetadata(EventTriggerType.DOWNLOAD_ADDED, BackgroundTask.TaskType.DOWNLOAD, true))
    }

    private fun registerNetwork() {
        register(EventTriggerMetadata(EventTriggerType.NETWORK_AVAILABLE, null, false))
        register(EventTriggerMetadata(EventTriggerType.NETWORK_LOST, null, false))
        register(EventTriggerMetadata(EventTriggerType.WIFI_CONNECTED, null, false))
    }

    private fun registerDevice() {
        register(EventTriggerMetadata(EventTriggerType.CHARGING_STARTED, null, false))
        register(EventTriggerMetadata(EventTriggerType.CHARGING_STOPPED, null, false))
    }

    private fun registerLifecycle() {
        register(EventTriggerMetadata(EventTriggerType.APP_FOREGROUND, null, false))
        register(EventTriggerMetadata(EventTriggerType.APP_BACKGROUND, null, false))
    }

    private fun registerData() {
        register(EventTriggerMetadata(EventTriggerType.FAVORITE_ADDED, null, false))
        register(EventTriggerMetadata(EventTriggerType.FAVORITE_REMOVED, null, false))
        register(EventTriggerMetadata(EventTriggerType.HISTORY_ADDED, null, false))
        register(EventTriggerMetadata(EventTriggerType.LOCAL_GALLERY_ADDED, null, false))
    }

    private fun registerFileAndIntent() {
        register(EventTriggerMetadata(EventTriggerType.FILE_CREATED, null, true))
        register(EventTriggerMetadata(EventTriggerType.FILE_DELETED, null, true))
        register(EventTriggerMetadata(EventTriggerType.CUSTOM_INTENT_RECEIVED, null, true))
    }
}