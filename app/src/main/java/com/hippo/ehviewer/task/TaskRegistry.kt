package com.hippo.ehviewer.task

import com.hippo.ehviewer.R

/**
 * 任务注册表
 * 管理所有后台任务的元数据信息
 */
object TaskRegistry {
    
    data class TaskMetadata(
        val taskClassName: String,
        val displayNameResId: Int,           // 汉化后的名字
        val descriptionResId: Int,           // 描述
        val taskType: BackgroundTask.TaskType,
        val requiresParams: Boolean,         // 是否需要参数
        val paramType: ParamType = ParamType.NONE
    )
    
    enum class ParamType {
        NONE,                    // 无参数
        GALLERY_LIST,           // 画廊列表（压缩、删除等）
        FILE_PATH,              // 文件路径（导入等）
        URI,                    // URI（导入等）
        CUSTOM                  // 自定义参数
    }
    
    private val registry = mutableMapOf<String, TaskMetadata>()
    
    init {
        // 注册所有任务
        registerCleanInvalidDownloadTask()
        registerStartAllDownloadTask()
        registerScanDownloadFilesTask()
        registerExportDatabaseTask()
        registerDumpLogcatTask()
        registerResetMediaScanTask()
        registerExportDataTask()
        registerExportLegacyDataTask()
        registerExportDownloadItemsTask()
        registerExportPerformanceLogTask()
        registerCleanDownloadLogsTask()
        registerProgressiveScanTask()
        registerVerifyDownloadIntegrityTask()
        registerCleanRedundancyTask()
        registerScanRecycleBinTask()
        registerScanLocalGalleryTask()
        registerRepairAllDownloadedGalleryTask()
        registerRepairUnknownCategoryGalleryTask()
        registerRebuildDownloadRecordsTask()
        registerScanDownloadTask()
        registerRepairDownloadedThumbnailTask()
        registerCompressSelectedGalleriesTask()
        registerImportLegacyDataTask()
        registerDeleteRangeDownloadTask()
        registerDeleteFilesTask()
        registerImportDataTask()
        registerStartRangeDownloadTask()
        registerImportDownloadItemsTask()
        registerProgressiveMergeTask()
        registerProgressiveMergeAllTask()
        registerProgressiveBackupTask()
        registerMergeDuplicateGalleryTask()
    }
    
    fun getAllTasks(): List<TaskMetadata> = registry.values.toList()
    
    fun getNoParamTasks(): List<TaskMetadata> = registry.values.filter { !it.requiresParams }
    
    fun getTasksByType(type: BackgroundTask.TaskType): List<TaskMetadata> = 
        registry.values.filter { it.taskType == type }
    
    fun getMetadata(className: String): TaskMetadata? = registry[className]
    
    fun getTasksRequiringParam(paramType: ParamType): List<TaskMetadata> =
        registry.values.filter { it.requiresParams && it.paramType == paramType }
    
    private fun register(metadata: TaskMetadata) {
        registry[metadata.taskClassName] = metadata
    }
    
    // ==================== 无参数任务 ====================
    
    private fun registerCleanInvalidDownloadTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.impl.CleanInvalidDownloadTask",
            displayNameResId = R.string.settings_download_clean_invalid_download,
            descriptionResId = R.string.settings_download_clean_invalid_download_summary,
            taskType = BackgroundTask.TaskType.CLEANUP,
            requiresParams = false
        ))
    }
    
    private fun registerStartAllDownloadTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.impl.StartAllDownloadTask",
            displayNameResId = R.string.download_start_all,
            descriptionResId = R.string.download_start_all_summary,
            taskType = BackgroundTask.TaskType.DOWNLOAD,
            requiresParams = false
        ))
    }
    
    private fun registerScanDownloadFilesTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.impl.ScanDownloadFilesTask",
            displayNameResId = R.string.settings_download_scan_download_files,
            descriptionResId = R.string.settings_download_scan_download_files_summary,
            taskType = BackgroundTask.TaskType.SCAN,
            requiresParams = false
        ))
    }
    
    private fun registerExportDatabaseTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.ExportDatabaseTask",
            displayNameResId = R.string.settings_advanced_export_database,
            descriptionResId = R.string.settings_advanced_export_database_summary,
            taskType = BackgroundTask.TaskType.EXPORT,
            requiresParams = false
        ))
    }
    
    private fun registerDumpLogcatTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.DumpLogcatTask",
            displayNameResId = R.string.settings_advanced_dump_logcat,
            descriptionResId = R.string.settings_advanced_dump_logcat_summary,
            taskType = BackgroundTask.TaskType.EXPORT,
            requiresParams = false
        ))
    }
    
    private fun registerResetMediaScanTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.ResetMediaScanTask",
            displayNameResId = R.string.settings_download_reset_media_scan,
            descriptionResId = R.string.settings_download_reset_media_scan_summary,
            taskType = BackgroundTask.TaskType.OTHER,
            requiresParams = false
        ))
    }
    
    private fun registerExportDataTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.ExportDataTask",
            displayNameResId = R.string.settings_advanced_export_data,
            descriptionResId = R.string.settings_advanced_export_data_summary,
            taskType = BackgroundTask.TaskType.EXPORT,
            requiresParams = false
        ))
    }
    
    private fun registerExportLegacyDataTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.ExportLegacyDataTask",
            displayNameResId = R.string.settings_advanced_export_legacy_option,
            descriptionResId = R.string.settings_advanced_export_data_summary,
            taskType = BackgroundTask.TaskType.EXPORT,
            requiresParams = false
        ))
    }
    
    private fun registerExportDownloadItemsTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.ExportDownloadItemsTask",
            displayNameResId = R.string.settings_download_export_download_items,
            descriptionResId = R.string.settings_advanced_export_data_summary,
            taskType = BackgroundTask.TaskType.EXPORT,
            requiresParams = false
        ))
    }
    
    private fun registerExportPerformanceLogTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.ExportPerformanceLogTask",
            displayNameResId = R.string.performance_log_export,
            descriptionResId = R.string.settings_advanced_export_data_summary,
            taskType = BackgroundTask.TaskType.EXPORT,
            requiresParams = false
        ))
    }
    
    private fun registerCleanDownloadLogsTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.CleanDownloadLogsTask",
            displayNameResId = R.string.settings_download_clean_logs,
            descriptionResId = R.string.settings_download_clean_logs_summary,
            taskType = BackgroundTask.TaskType.CLEANUP,
            requiresParams = false
        ))
    }
    
    private fun registerProgressiveScanTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.ProgressiveScanTask",
            displayNameResId = R.string.lab_progressive_manager,
            descriptionResId = R.string.lab_progressive_manager_summary,
            taskType = BackgroundTask.TaskType.SCAN,
            requiresParams = false
        ))
    }
    
    private fun registerVerifyDownloadIntegrityTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.VerifyDownloadIntegrityTask",
            displayNameResId = R.string.settings_download_verify_integrity,
            descriptionResId = R.string.settings_download_verify_integrity_summary,
            taskType = BackgroundTask.TaskType.SCAN,
            requiresParams = false
        ))
    }
    
    private fun registerCleanRedundancyTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.CleanRedundancyTask",
            displayNameResId = R.string.settings_download_clean_redundancy,
            descriptionResId = R.string.settings_download_clean_redundancy_summary,
            taskType = BackgroundTask.TaskType.CLEANUP,
            requiresParams = false
        ))
    }
    
    private fun registerScanRecycleBinTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.ScanRecycleBinTask",
            displayNameResId = R.string.recycle_bin_scan_task_name,
            descriptionResId = R.string.recycle_bin_scan_task_summary,
            taskType = BackgroundTask.TaskType.SCAN,
            requiresParams = false
        ))
    }
    
    private fun registerScanLocalGalleryTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.ScanLocalGalleryTask",
            displayNameResId = R.string.local_gallery_scan_task_name,
            descriptionResId = R.string.local_gallery_scan_task_summary,
            taskType = BackgroundTask.TaskType.SCAN,
            requiresParams = false
        ))
    }
    
    private fun registerRepairAllDownloadedGalleryTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.RepairAllDownloadedGalleryTask",
            displayNameResId = R.string.settings_download_repair_all_downloaded_gallery,
            descriptionResId = R.string.settings_download_repair_all_downloaded_gallery_summary,
            taskType = BackgroundTask.TaskType.UPDATE,
            requiresParams = false
        ))
    }
    
    private fun registerRepairUnknownCategoryGalleryTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.RepairUnknownCategoryGalleryTask",
            displayNameResId = R.string.settings_download_repair_unknown_category_gallery,
            descriptionResId = R.string.settings_download_repair_unknown_category_gallery_summary,
            taskType = BackgroundTask.TaskType.UPDATE,
            requiresParams = false
        ))
    }
    
    private fun registerRebuildDownloadRecordsTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.RebuildDownloadRecordsTask",
            displayNameResId = R.string.settings_download_rebuild_download_records,
            descriptionResId = R.string.settings_download_rebuild_download_records_summary,
            taskType = BackgroundTask.TaskType.SCAN,
            requiresParams = false
        ))
    }
    
    private fun registerScanDownloadTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.ScanDownloadTask",
            displayNameResId = R.string.settings_download_scan_download_files,
            descriptionResId = R.string.settings_download_scan_download_files_summary,
            taskType = BackgroundTask.TaskType.SCAN,
            requiresParams = false
        ))
    }
    
    private fun registerRepairDownloadedThumbnailTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.RepairDownloadedThumbnailTask",
            displayNameResId = R.string.settings_download_repair_thumbnail,
            descriptionResId = R.string.settings_download_repair_thumbnail_summary,
            taskType = BackgroundTask.TaskType.CLEANUP,
            requiresParams = false
        ))
    }
    
    // ==================== 需要参数的任务 ====================
    
    private fun registerCompressSelectedGalleriesTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.impl.CompressSelectedGalleriesTask",
            displayNameResId = R.string.compress_selected_galleries,
            descriptionResId = R.string.compress_selected_galleries,
            taskType = BackgroundTask.TaskType.CLEANUP,
            requiresParams = true,
            paramType = ParamType.GALLERY_LIST
        ))
    }
    
    private fun registerImportLegacyDataTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.impl.ImportLegacyDataTask",
            displayNameResId = R.string.settings_advanced_import_legacy_data,
            descriptionResId = R.string.settings_advanced_import_legacy_data_summary,
            taskType = BackgroundTask.TaskType.IMPORT,
            requiresParams = true,
            paramType = ParamType.FILE_PATH
        ))
    }
    
    private fun registerDeleteRangeDownloadTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.impl.DeleteRangeDownloadTask",
            displayNameResId = R.string.delete_download,
            descriptionResId = R.string.delete_download,
            taskType = BackgroundTask.TaskType.CLEANUP,
            requiresParams = true,
            paramType = ParamType.GALLERY_LIST
        ))
    }
    
    private fun registerDeleteFilesTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.impl.DeleteFilesTask",
            displayNameResId = R.string.delete,
            descriptionResId = R.string.delete,
            taskType = BackgroundTask.TaskType.CLEANUP,
            requiresParams = true,
            paramType = ParamType.CUSTOM
        ))
    }
    
    private fun registerImportDataTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.impl.ImportDataTask",
            displayNameResId = R.string.settings_advanced_import_data,
            descriptionResId = R.string.settings_advanced_import_data_summary,
            taskType = BackgroundTask.TaskType.IMPORT,
            requiresParams = true,
            paramType = ParamType.FILE_PATH
        ))
    }
    
    private fun registerStartRangeDownloadTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.impl.StartRangeDownloadTask",
            displayNameResId = R.string.download_start_range,
            descriptionResId = R.string.download_start_range_summary,
            taskType = BackgroundTask.TaskType.DOWNLOAD,
            requiresParams = true,
            paramType = ParamType.GALLERY_LIST
        ))
    }
    
    private fun registerImportDownloadItemsTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.ImportDownloadItemsTask",
            displayNameResId = R.string.settings_download_import_items,
            descriptionResId = R.string.settings_advanced_import_data_summary,
            taskType = BackgroundTask.TaskType.IMPORT,
            requiresParams = true,
            paramType = ParamType.URI
        ))
    }
    
    private fun registerProgressiveMergeTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.ProgressiveMergeTask",
            displayNameResId = R.string.progressive_merge,
            descriptionResId = R.string.lab_progressive_manager_summary,
            taskType = BackgroundTask.TaskType.MERGE,
            requiresParams = true,
            paramType = ParamType.CUSTOM
        ))
    }
    
    private fun registerProgressiveMergeAllTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.ProgressiveMergeAllTask",
            displayNameResId = R.string.progressive_merge_all,
            descriptionResId = R.string.lab_progressive_manager_summary,
            taskType = BackgroundTask.TaskType.MERGE,
            requiresParams = true,
            paramType = ParamType.CUSTOM
        ))
    }
    
    private fun registerProgressiveBackupTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.ProgressiveBackupTask",
            displayNameResId = R.string.progressive_backup_task_name,
            descriptionResId = R.string.lab_progressive_manager_summary,
            taskType = BackgroundTask.TaskType.BACKUP,
            requiresParams = true,
            paramType = ParamType.CUSTOM
        ))
    }
    
    private fun registerMergeDuplicateGalleryTask() {
        register(TaskMetadata(
            taskClassName = "com.hippo.ehviewer.task.MergeDuplicateGalleryTask",
            displayNameResId = R.string.settings_download_merge_duplicate_gallery,
            descriptionResId = R.string.settings_download_merge_duplicate_gallery_summary,
            taskType = BackgroundTask.TaskType.MERGE,
            requiresParams = false  // 可以无参数执行全量合并
        ))
    }
}
