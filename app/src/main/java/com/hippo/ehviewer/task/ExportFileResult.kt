package com.hippo.ehviewer.task

import java.io.File

/**
 * 导出类后台任务的通用结果接口。
 * 任务完成后，外部可通过 [exportedFile] 读取生成的成品文件进行分享。
 */
interface ExportFileResult {
    /** 导出的成品文件；未完成或失败时为 null */
    val exportedFile: File?
}