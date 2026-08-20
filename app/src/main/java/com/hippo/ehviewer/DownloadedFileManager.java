/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer;

import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.dao.DaoSession;
import com.hippo.ehviewer.dao.DownloadedFile;
import com.hippo.ehviewer.dao.DownloadedFilesDao;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.unifile.UniFile;
import com.hippo.lib.yorozuya.FileUtils;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.lib.yorozuya.MathUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class DownloadedFileManager {

    private static final String TAG = DownloadedFileManager.class.getSimpleName();
    private static DownloadedFileManager sInstance;

    // 物化视图表：串连 DOWNLOAD_DIRNAME 与 DOWNLOADED_FILES，按画廊聚合总大小（字节）
    private static final String GALLERY_SIZE_TABLE = "DOWNLOADED_GALLERY_SIZE";

    private final Context mContext;
    private final DownloadedFilesDao mDownloadedFilesDao;

    public static final int SCAN_STATUS_IDLE = 0;
    public static final int SCAN_STATUS_SCANNING = 1;
    public static final int SCAN_STATUS_COMPLETED = 2;
    public static final int SCAN_STATUS_ERROR = 3;

    private volatile int mScanStatus = SCAN_STATUS_IDLE;
    private final Object mScanLock = new Object();
    private final AtomicInteger mScanProgress = new AtomicInteger(0);
    private final AtomicInteger mScanTotal = new AtomicInteger(0);
    private String mScanError;

    public static void initialize(Context context) {
        if (sInstance == null) {
            sInstance = new DownloadedFileManager(context.getApplicationContext());
        }
    }

    public static DownloadedFileManager getInstance() {
        if (sInstance == null) {
            throw new IllegalStateException("DownloadedFileManager not initialized");
        }
        return sInstance;
    }

    private DownloadedFileManager(Context context) {
        mContext = context;
        DaoSession daoSession = EhDB.getDaoSession();
        mDownloadedFilesDao = daoSession.getDownloadedFilesDao();
        ensureFileTokenColumn();
        ensureGallerySizeView();
    }

    /**
     * 确保物化视图表存在：串连 DOWNLOAD_DIRNAME（下载目录表）与 DOWNLOADED_FILES（下载文件表），
     * 按画廊 gid 聚合出总大小。若 DOWNLOADED_FILES 未记录（未执行过扫描），
     * 该表仍可依赖下载目录名定位实际目录并回退扫描。
     */
    private void ensureGallerySizeView() {
        try {
            mDownloadedFilesDao.getDatabase().execSQL(
                    "CREATE TABLE IF NOT EXISTS \"" + GALLERY_SIZE_TABLE + "\" (" +
                            "\"GID\" INTEGER PRIMARY KEY NOT NULL ," +
                            "\"TOTAL_SIZE\" INTEGER NOT NULL DEFAULT 0);");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create DOWNLOADED_GALLERY_SIZE view", e);
        }
    }

    /**
     * 刷新物化视图：把 DOWNLOAD_DIRNAME 与 DOWNLOADED_FILES 聚合后的总大小写入物化视图表。
     */
    public void refreshGallerySizeView() {
        try {
            mDownloadedFilesDao.getDatabase().execSQL("DELETE FROM \"" + GALLERY_SIZE_TABLE + "\"");
            mDownloadedFilesDao.getDatabase().execSQL(
                    "INSERT OR REPLACE INTO \"" + GALLERY_SIZE_TABLE + "\" (GID, TOTAL_SIZE) " +
                            "SELECT d.GID, COALESCE(SUM(f.SIZE), 0) " +
                            "FROM DOWNLOAD_DIRNAME d " +
                            "LEFT JOIN DOWNLOADED_FILES f ON f.GID = d.GID AND f.STATUS = " + DownloadedFile.STATUS_NORMAL + " " +
                            "GROUP BY d.GID");
        } catch (Exception e) {
            Log.e(TAG, "Failed to refresh DOWNLOADED_GALLERY_SIZE view", e);
        }
    }

    /**
     * 从物化视图批量查询指定画廊的总大小（单位：字节）。未命中返回 0。
     */
    private Map<Long, Long> queryGallerySizeView(@NonNull List<Long> gids) {
        Map<Long, Long> result = new HashMap<>();
        List<Long> queryGids = new ArrayList<>(gids.size());
        for (Long gid : gids) {
            if (gid != null) {
                queryGids.add(gid);
            }
        }
        if (queryGids.isEmpty()) {
            return result;
        }
        StringBuilder placeholders = new StringBuilder();
        String[] args = new String[queryGids.size()];
        for (int i = 0; i < queryGids.size(); i++) {
            if (i > 0) {
                placeholders.append(',');
            }
            placeholders.append('?');
            args[i] = String.valueOf(queryGids.get(i));
        }
        Cursor cursor = null;
        try {
            cursor = mDownloadedFilesDao.getDatabase().rawQuery(
                    "SELECT GID, TOTAL_SIZE FROM \"" + GALLERY_SIZE_TABLE + "\" WHERE GID IN (" + placeholders + ")",
                    args);
            while (cursor != null && cursor.moveToNext()) {
                long gid = cursor.getLong(0);
                long size = cursor.isNull(1) ? 0L : cursor.getLong(1);
                result.put(gid, Math.max(size, 0L));
            }
        } catch (Exception e) {
            Log.e(TAG, "queryGallerySizeView failed", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    /**
     * 回退方案：直接扫描实际下载目录计算总大小（单位：字节）。
     * 目录不存在或不可读时返回 -1。
     */
    private long calculateGalleryDirSize(long gid) {
        try {
            GalleryInfo gi = new GalleryInfo();
            gi.gid = gid;
            UniFile dir = SpiderDen.getExistingGalleryDownloadDir(gi);
            if (dir == null || !dir.isDirectory()) {
                return -1;
            }
            return calculateFolderSize(dir);
        } catch (Exception e) {
            return -1;
        }
    }

    private long calculateFolderSize(UniFile folder) {
        long totalSize = 0;
        UniFile[] files = folder.listFiles();
        if (files == null) {
            return 0;
        }
        for (UniFile file : files) {
            if (file.isFile()) {
                long fileSize = file.length();
                if (fileSize > 0) {
                    totalSize += fileSize;
                }
            } else if (file.isDirectory()) {
                totalSize += calculateFolderSize(file);
            }
        }
        return totalSize;
    }

    private void ensureFileTokenColumn() {
        try {
            boolean hasFileToken = false;
            Cursor cursor = mDownloadedFilesDao.getDatabase().rawQuery("PRAGMA table_info('DOWNLOADED_FILES')", null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    int columnIndex = cursor.getColumnIndex("name");
                    if (columnIndex == -1) {
                        continue;
                    }
                    String columnName = cursor.getString(columnIndex);
                    if ("FILE_TOKEN".equalsIgnoreCase(columnName)) {
                        hasFileToken = true;
                        break;
                    }
                }
                cursor.close();
            }
            if (!hasFileToken) {
                mDownloadedFilesDao.getDatabase().execSQL("ALTER TABLE DOWNLOADED_FILES ADD COLUMN FILE_TOKEN TEXT;");
                Log.i(TAG, "Added missing FILE_TOKEN column to DOWNLOADED_FILES");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to ensure FILE_TOKEN column", e);
        }
    }

    @Nullable
    private File toFile(@Nullable UniFile uniFile) {
        if (uniFile == null || uniFile.getUri() == null) {
            return null;
        }
        String path = uniFile.getUri().getPath();
        return path != null ? new File(path) : null;
    }

    /** shell 输出条目数上限，防止异常时无限累积 */
    private static final int MAX_SHELL_OUTPUT_ENTRIES = 200000;
    /** 单条路径长度上限（超出视为异常并丢弃该条目） */
    private static final int MAX_SHELL_PATH_LENGTH = 4096;
    /** 错误流读取上限（字节），防止 stderr 巨大导致 OOM */
    private static final int MAX_SHELL_ERROR_BYTES = 64 * 1024;

    /**
     * 单次 find 一次性列出下载目录下所有画廊文件（深度 1 为画廊目录、深度 2 为画廊内文件），
     * 取代原先每目录两次 shell 进程（cat .ehviewer + find 文件）的做法。
     * 使用 -print0 以 '\0' 分隔，避免文件名含空格/换行时被拆散。
     *
     * 流式读取进程输出并按 '\0' 增量分隔，避免把整段输出载入内存导致 OOM。
     *
     * @return 绝对路径列表；shell 不可用或失败时返回 null
     */
    @Nullable
    private List<String> listAllGalleryFilesShell(@NonNull File downloadDir) {
        String command = "find " + escapeShellArg(downloadDir.getAbsolutePath())
                + " -maxdepth 2 -mindepth 1 -type f -print0 2>/dev/null";
        return executeShellCommandList("/system/bin/sh", "-c", command);
    }

    /**
     * 执行 shell 命令并流式读取 stdout，按 '\0' 分隔返回字符串列表。
     * 不会一次性载入整个输出，适用于输出可能很大的场景。
     */
    @Nullable
    private List<String> executeShellCommandList(@NonNull String... command) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(command);

            List<String> result = new ArrayList<>();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8), 8 * 1024);
            StringBuilder sb = new StringBuilder(256);
            boolean tooLong = false;
            int c;
            try {
                while ((c = reader.read()) != -1) {
                    if (c == '\0') {
                        if (sb.length() > 0) {
                            if (!tooLong) {
                                result.add(sb.toString());
                            }
                            sb.setLength(0);
                            tooLong = false;
                            if (result.size() >= MAX_SHELL_OUTPUT_ENTRIES) {
                                Log.w(TAG, "Shell output exceeds " + MAX_SHELL_OUTPUT_ENTRIES + " entries, aborting");
                                return null;
                            }
                        }
                    } else {
                        if (sb.length() >= MAX_SHELL_PATH_LENGTH) {
                            tooLong = true;
                        } else {
                            sb.append((char) c);
                        }
                    }
                }
            } finally {
                IOUtils.closeQuietly(reader);
            }
            if (sb.length() > 0 && !tooLong) {
                result.add(sb.toString());
            }

            // 读取错误流（有上限，防止 stderr 巨大导致 OOM）
            String error = readShellErrorCapped(process.getErrorStream());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                Log.w(TAG, "Shell command failed: " + Arrays.toString(command) + " exit=" + exitCode + " err=" + error);
                return null;
            }
            return result;
        } catch (Exception e) {
            Log.w(TAG, "Failed to execute shell command: " + Arrays.toString(command), e);
            return null;
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 有上限地读取进程错误流，避免 stderr 输出巨大时把内存耗尽。
     */
    @Nullable
    private String readShellErrorCapped(@Nullable InputStream errorStream) {
        if (errorStream == null) {
            return null;
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int total = 0;
            int len;
            while ((len = errorStream.read(buffer)) != -1) {
                total += len;
                if (total > MAX_SHELL_ERROR_BYTES) {
                    Log.w(TAG, "Shell stderr exceeds " + MAX_SHELL_ERROR_BYTES + " bytes, truncating");
                    break;
                }
                baos.write(buffer, 0, len);
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            Log.w(TAG, "Failed to read shell error stream", e);
            return null;
        } finally {
            IOUtils.closeQuietly(errorStream);
        }
    }

    /**
     * 直接读取小文件内容（如 .ehviewer），避免为每个画廊启动 cat 进程。
     */
    @Nullable
    private String readFileContent(@Nullable File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return null;
        }
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            return IOUtils.readString(fis, StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            Log.w(TAG, "Failed to read file: " + file.getAbsolutePath(), e);
            return null;
        } finally {
            IOUtils.closeQuietly(fis);
        }
    }

    /**
     * 从旧格式 .ehviewer 内容解析画廊标题（第 3 行为 title）。
     * VERSION2 格式或无法解析时返回 null。
     */
    @Nullable
    private String parseOldFormatTitle(@Nullable String content) {
        if (content == null) {
            return null;
        }
        String[] lines = content.split("\n", 4);
        if (lines.length < 3) {
            return null;
        }
        if ("VERSION2".equals(lines[0].trim())) {
            return null;
        }
        String title = lines[2].trim();
        return title.isEmpty() ? null : title;
    }

    /**
     * 提取文件扩展名（不含点号），无扩展名返回空串。
     */
    private String extensionOf(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex > 0 ? filename.substring(dotIndex + 1) : "";
    }

    private String escapeShellArg(@NonNull String arg) {
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    /**
     * 检查文件是否已存在
     *
     * @param token 文件token（短码）
     * @return 文件信息，如果不存在则返回null
     */
    @Nullable
    public DownloadedFile getFileByToken(String token) {
        if (TextUtils.isEmpty(token)) {
            Log.d(TAG, "getFileByToken called with empty token");
            return null;
        }

        Log.d(TAG, "getFileByToken called for token: " + token);

        try {
            // 优先查找正常状态记录
            DownloadedFile file = mDownloadedFilesDao.queryBuilder()
                    .where(DownloadedFilesDao.Properties.Token.eq(token))
                    .where(DownloadedFilesDao.Properties.Status.eq(DownloadedFile.STATUS_NORMAL))
                    .unique();

            if (file != null) {
                Log.d(TAG, "Found normal status file for token: " + token + ", filename: " + file.getFilename());
                return file;
            }

            // 未找到，尝试使用 fileToken 进行查找
            file = getFileByFileToken(token);
            if (file != null) {
                Log.d(TAG, "Found normal status file by fileToken: " + token + ", filename: " + file.getFilename());
                return file;
            }

            Log.d(TAG, "No file found for token: " + token);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error querying file by token: " + token, e);
            return null;
        }
    }

    @Nullable
    public DownloadedFile getFileByFileToken(String fileToken) {
        if (TextUtils.isEmpty(fileToken)) {
            return null;
        }

        try {
            DownloadedFile file = mDownloadedFilesDao.queryBuilder()
                    .where(DownloadedFilesDao.Properties.FileToken.eq(fileToken))
                    .where(DownloadedFilesDao.Properties.Status.eq(DownloadedFile.STATUS_NORMAL))
                    .unique();
            if (file != null) {
                return file;
            }

            List<DownloadedFile> candidates = mDownloadedFilesDao.queryBuilder()
                    .where(DownloadedFilesDao.Properties.FileToken.eq(fileToken))
                    .list();
            if (candidates != null && !candidates.isEmpty()) {
                for (DownloadedFile candidate : candidates) {
                    File candidateFile = new File(candidate.getPath());
                    if (candidateFile.exists()) {
                        if (candidate.getStatus() != DownloadedFile.STATUS_NORMAL) {
                            candidate.setStatus(DownloadedFile.STATUS_NORMAL);
                            candidate.setLast_accessed(System.currentTimeMillis());
                            mDownloadedFilesDao.update(candidate);
                        }
                        return candidate;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error querying file by fileToken: " + fileToken, e);
            return null;
        }
    }

    /**
     * 添加或更新文件信息
     *
     * @param token    文件token
     * @param gid      画廊GID
     * @param filename 文件名
     * @param path     文件路径
     * @param size     文件大小
     * @return 是否成功
     */
    public boolean addOrUpdateFile(String token, long gid, String filename, String path, long size) {
        return addOrUpdateFile(token, token, gid, filename, path, size);
    }

    public boolean addOrUpdateFile(String token, String fileToken, long gid, String filename, String path, long size) {
        if (TextUtils.isEmpty(token) || TextUtils.isEmpty(filename) || TextUtils.isEmpty(path)) {
            Log.w(TAG, "Invalid parameters for addOrUpdateFile - token: " + token + ", filename: " + filename + ", path: " + path);
            return false;
        }

        Log.d(TAG, "addOrUpdateFile called - token: " + token + ", fileToken: " + fileToken + ", gid: " + gid + ", filename: " + filename + ", size: " + size);

        try {
            DownloadedFile file = getFileByToken(token);
            if (file == null && !TextUtils.isEmpty(fileToken) && !TextUtils.equals(token, fileToken)) {
                file = getFileByFileToken(fileToken);
            }
            if (file == null) {
                Log.d(TAG, "Creating new file record for token: " + token + ", fileToken: " + fileToken);
                // 新建文件记录
                file = new DownloadedFile();
                file.setToken(token);
                file.setFileToken(fileToken);
                file.setGid(gid);
                file.setFilename(filename);
                file.setPath(path);
                file.setSize(size);
                file.setDownload_time(System.currentTimeMillis());
                file.setLast_accessed(System.currentTimeMillis());
                file.setStatus(DownloadedFile.STATUS_NORMAL);

                // 计算MD5
                Log.d(TAG, "Calculating MD5 for file: " + path);
                String md5 = calculateMD5(path);
                if (md5 != null) {
                    file.setMd5(md5);
                    Log.d(TAG, "MD5 calculated: " + md5);
                } else {
                    Log.w(TAG, "Failed to calculate MD5 for file: " + path);
                }

                Log.d(TAG, "Inserting new file record into database");
                mDownloadedFilesDao.insert(file);
                Log.d(TAG, "Successfully inserted file record for token: " + token + ", fileToken: " + fileToken);
            } else {
                Log.d(TAG, "Updating existing file record for token: " + token + ", fileToken: " + fileToken);
                // 更新现有记录
                if (!TextUtils.isEmpty(fileToken) && !TextUtils.equals(fileToken, file.getFileToken())) {
                    file.setFileToken(fileToken);
                }
                file.setLast_accessed(System.currentTimeMillis());
                if (size > 0) {
                    file.setSize(size);
                }
                mDownloadedFilesDao.update(file);
                Log.d(TAG, "Successfully updated file record for token: " + token + ", fileToken: " + fileToken);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error adding/updating file: " + filename, e);
            // 记录数据库相关信息
            try {
                Log.e(TAG, "Database error details", e);
            } catch (Exception dbEx) {
                Log.e(TAG, "Failed to get database info", dbEx);
            }
            return false;
        }
    }

    /**
     * 标记文件为已删除
     *
     * @param token 文件token
     */
    public void markFileDeleted(String token) {
        if (TextUtils.isEmpty(token)) {
            return;
        }

        try {
            DownloadedFile file = getFileByToken(token);
            if (file != null) {
                file.setStatus(DownloadedFile.STATUS_DELETED);
                mDownloadedFilesDao.update(file);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error marking file as deleted: " + token, e);
        }
    }

    /**
     * 标记画廊的所有文件为已移除
     *
     * @param gid 画廊GID
     */
    public void markGalleryRemoved(long gid) {
        try {
            List<DownloadedFile> files = mDownloadedFilesDao.queryBuilder()
                    .where(DownloadedFilesDao.Properties.Gid.eq(gid))
                    .where(DownloadedFilesDao.Properties.Status.eq(DownloadedFile.STATUS_NORMAL))
                    .list();

            for (DownloadedFile file : files) {
                file.setStatus(DownloadedFile.STATUS_GALLERY_REMOVED);
                mDownloadedFilesDao.update(file);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error marking gallery as removed: " + gid, e);
        }
    }

    /**
     * 获取画廊的所有文件
     *
     * @param gid 画廊GID
     * @return 文件列表
     */
    public List<DownloadedFile> getGalleryFiles(long gid) {
        try {
            return mDownloadedFilesDao.queryBuilder()
                    .where(DownloadedFilesDao.Properties.Gid.eq(gid))
                    .where(DownloadedFilesDao.Properties.Status.eq(DownloadedFile.STATUS_NORMAL))
                    .orderAsc(DownloadedFilesDao.Properties.Filename)
                    .list();
        } catch (Exception e) {
            Log.e(TAG, "Error getting gallery files: " + gid, e);
            return new ArrayList<>();
        }
    }

    /**
     * 批量检查画廊文件是否存在
     *
     * @param gid 画廊GID
     * @return 文件存在性检查结果
     */
    public GalleryFileCheckResult checkGalleryFilesExist(long gid) {
        Log.d(TAG, "Checking gallery files existence for GID: " + gid);

        GalleryFileCheckResult result = new GalleryFileCheckResult(gid);
        List<DownloadedFile> files = getGalleryFiles(gid);

        result.totalFiles = files.size();

        for (DownloadedFile file : files) {
            String filePath = file.getPath();
            java.io.File physicalFile = new java.io.File(filePath);

            if (physicalFile.exists() && physicalFile.canRead()) {
                result.existingFiles++;
                result.existingFileList.add(file);

                // 检查文件大小
                long expectedSize = file.getSize() != null ? file.getSize() : 0;
                long actualSize = physicalFile.length();

                if (expectedSize > 0 && actualSize == expectedSize) {
                    result.validFiles++;
                } else {
                    result.invalidFiles++;
                    result.invalidFileList.add(file);
                    Log.w(TAG, "File size mismatch: " + file.getFilename() +
                            " - expected: " + expectedSize + ", actual: " + actualSize);
                }
            } else {
                result.missingFiles++;
                result.missingFileList.add(file);
                Log.w(TAG, "Missing file: " + file.getFilename() + " at " + filePath);
            }
        }

        Log.d(TAG, "Gallery file check completed for GID " + gid +
                " - Total: " + result.totalFiles +
                ", Existing: " + result.existingFiles +
                ", Valid: " + result.validFiles +
                ", Missing: " + result.missingFiles +
                ", Invalid: " + result.invalidFiles);

        return result;
    }

    /**
     * 画廊文件检查结果
     */
    public static class GalleryFileCheckResult {
        public final long gid;
        public int totalFiles = 0;
        public int existingFiles = 0;
        public int validFiles = 0;
        public int missingFiles = 0;
        public int invalidFiles = 0;

        public final List<DownloadedFile> existingFileList = new ArrayList<>();
        public final List<DownloadedFile> missingFileList = new ArrayList<>();
        public final List<DownloadedFile> invalidFileList = new ArrayList<>();

        public GalleryFileCheckResult(long gid) {
            this.gid = gid;
        }

        public boolean isComplete() {
            return totalFiles > 0 && validFiles == totalFiles;
        }

        public boolean hasMissingFiles() {
            return missingFiles > 0;
        }

        public boolean hasInvalidFiles() {
            return invalidFiles > 0;
        }

        public double getCompletionRate() {
            return totalFiles > 0 ? (double) validFiles / totalFiles : 0.0;
        }
    }

    /**
     * 获取总文件数量
     */
    public int getTotalFilesCount() {
        try {
            return (int) mDownloadedFilesDao.queryBuilder()
                    .where(DownloadedFilesDao.Properties.Status.eq(DownloadedFile.STATUS_NORMAL))
                    .count();
        } catch (Exception e) {
            Log.e(TAG, "Error getting total files count", e);
            return 0;
        }
    }

    /**
     * 获取指定画廊的文件总大小（单位：字节）
     */
    public long getGalleryFilesTotalSize(long gid) {
        List<Long> gids = new ArrayList<>(1);
        gids.add(gid);
        return getGalleryFilesTotalSizeMap(gids).getOrDefault(gid, 0L);
    }

    /**
     * 批量获取多个画廊文件总大小（单位：字节）。
     * 查询顺序：物化视图（DOWNLOADED_GALLERY_SIZE）-> DOWNLOADED_FILES 聚合 -> 实际目录扫描。
     * 目录扫描结果回写物化视图，后续查询秒开。
     */
    @NonNull
    public Map<Long, Long> getGalleryFilesTotalSizeMap(@NonNull List<Long> gids) {
        Map<Long, Long> result = new HashMap<>();
        if (gids.isEmpty()) {
            return result;
        }

        for (Long gid : gids) {
            if (gid != null) {
                result.put(gid, 0L);
            }
        }

        // 1) 先查物化视图（缓存）
        Set<Long> needQuery = new HashSet<>();
        Map<Long, Long> viewSizes = queryGallerySizeView(gids);
        for (Long gid : gids) {
            if (gid == null) {
                continue;
            }
            long size = viewSizes.getOrDefault(gid, 0L);
            if (size > 0L) {
                result.put(gid, size);
            } else {
                needQuery.add(gid);
            }
        }
        if (needQuery.isEmpty()) {
            return result;
        }

        // 2) 再查 DOWNLOADED_FILES 聚合
        List<Long> queryGids = new ArrayList<>(needQuery);
        StringBuilder placeholders = new StringBuilder();
        String[] args = new String[queryGids.size() + 1];
        args[0] = String.valueOf(DownloadedFile.STATUS_NORMAL);
        for (int i = 0; i < queryGids.size(); i++) {
            if (i > 0) {
                placeholders.append(',');
            }
            placeholders.append('?');
            args[i + 1] = String.valueOf(queryGids.get(i));
        }

        String sql = "SELECT GID, SUM(COALESCE(SIZE, 0)) FROM DOWNLOADED_FILES "
                + "WHERE STATUS = ? AND GID IN (" + placeholders + ") GROUP BY GID";

        Set<Long> dirFallback = new HashSet<>();
        Cursor cursor = null;
        try {
            cursor = mDownloadedFilesDao.getDatabase().rawQuery(sql, args);
            while (cursor != null && cursor.moveToNext()) {
                long gid = cursor.getLong(0);
                long size = cursor.isNull(1) ? 0L : cursor.getLong(1);
                if (size > 0L) {
                    result.put(gid, size);
                    upsertGallerySizeView(gid, size);
                } else {
                    dirFallback.add(gid);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting gallery files total size map", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        // 未在 DOWNLOADED_FILES 中出现过的 gid 也走目录扫描
        for (Long gid : queryGids) {
            if (result.getOrDefault(gid, 0L) <= 0L) {
                dirFallback.add(gid);
            }
        }

        // 3) 最后扫描实际下载目录，并回写物化视图缓存
        if (!dirFallback.isEmpty()) {
            for (Long gid : dirFallback) {
                long size = calculateGalleryDirSize(gid);
                if (size > 0L) {
                    result.put(gid, size);
                    upsertGallerySizeView(gid, size);
                }
            }
        }

        return result;
    }

    /**
     * 写入/更新物化视图缓存。
     */
    private void upsertGallerySizeView(long gid, long size) {
        try {
            mDownloadedFilesDao.getDatabase().execSQL(
                    "INSERT OR REPLACE INTO \"" + GALLERY_SIZE_TABLE + "\" (GID, TOTAL_SIZE) VALUES (?, ?)",
                    new Object[]{gid, Math.max(size, 0L)});
        } catch (Exception e) {
            Log.e(TAG, "upsertGallerySizeView failed, gid=" + gid, e);
        }
    }

    /**
     * 清理无效文件记录
     *
     * @return 清理的文件数量
     */
    public int cleanupInvalidFiles() {
        Log.d(TAG, "开始清理无效文件记录");

        List<DownloadedFile> allFiles = mDownloadedFilesDao.queryBuilder()
                .where(DownloadedFilesDao.Properties.Status.eq(DownloadedFile.STATUS_NORMAL))
                .list();

        int cleanedCount = 0;

        for (DownloadedFile file : allFiles) {
            java.io.File physicalFile = new java.io.File(file.getPath());

            if (!physicalFile.exists() || !physicalFile.canRead()) {
                Log.w(TAG, "发现无效文件记录: " + file.getFilename() + " (路径: " + file.getPath() + ")");

                // 标记为已删除而不是直接删除，保留历史记录
                file.setStatus(DownloadedFile.STATUS_DELETED);
                mDownloadedFilesDao.update(file);
                cleanedCount++;
            }
        }

        Log.i(TAG, "清理完成，删除了 " + cleanedCount + " 个无效文件记录");
        return cleanedCount;
    }

    /**
     * 计算文件MD5
     *
     * @param filePath 文件路径
     * @return MD5字符串，失败返回null
     */
    @Nullable
    private String calculateMD5(String filePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(filePath);
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }

                byte[] md5Bytes = digest.digest();
                StringBuilder sb = new StringBuilder();
                for (byte b : md5Bytes) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } finally {
                IOUtils.closeQuietly(fis);
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            Log.e(TAG, "Error calculating MD5 for: " + filePath, e);
            return null;
        }
    }

    /**
     * 扫描下载目录重建文件信息表
     *
     * @param progressListener 进度监听器
     */
    public void scanDownloadDirectories(@Nullable DownloadedFileManagerScanListener progressListener) {
        synchronized (mScanLock) {
            if (mScanStatus != SCAN_STATUS_IDLE) {
                String msg = "已有扫描在进行中，请稍后再试";
                Log.w(TAG, msg);
                if (progressListener != null) {
                    progressListener.onError(new IllegalStateException(msg));
                }
                return;
            }
            mScanStatus = SCAN_STATUS_SCANNING;
        }

        new Thread(() -> {
            mScanProgress.set(0);
            mScanTotal.set(0);
            mScanError = null;

            try {
                // 检查数据库表是否存在，缺失时创建
                Log.i(TAG, "Starting scan, checking database connection");
                try {
                    long count = mDownloadedFilesDao.count();
                    Log.i(TAG, "DownloadedFiles table exists, current record count: " + count);
                } catch (Exception e) {
                    Log.e(TAG, "DownloadedFiles table missing, recreating: " + e.getMessage(), e);
                    createDownloadedFilesTable();
                }

                // 载入现有记录，供增量对账使用（不再清空表，保留文件级历史）
                Map<String, DownloadedFile> existingByToken = loadAllFilesByToken();
                Map<Long, Set<String>> existingTokensByGid = new HashMap<>();
                for (DownloadedFile f : existingByToken.values()) {
                    existingTokensByGid.computeIfAbsent(f.getGid(), k -> new HashSet<>()).add(f.getToken());
                }

                // 获取下载目录
                UniFile downloadDir = Settings.getDownloadLocation();
                if (downloadDir == null) {
                    throw new RuntimeException("Download location not set");
                }
                Log.i(TAG, "Download location: " + downloadDir.getUri());

                // 单次 find 枚举全部画廊文件（file URI 场景），失败则回退 UniFile 枚举
                Map<String, List<String>> filesByDir = null;
                File downloadDirFile = toFile(downloadDir);
                if (downloadDirFile != null) {
                    List<String> allFiles = listAllGalleryFilesShell(downloadDirFile);
                    if (allFiles != null) {
                        filesByDir = new HashMap<>();
                        for (String path : allFiles) {
                            if (TextUtils.isEmpty(path)) {
                                continue;
                            }
                            File f = new File(path);
                            File parent = f.getParentFile();
                            if (parent == null) {
                                continue;
                            }
                            filesByDir.computeIfAbsent(parent.getAbsolutePath(), k -> new ArrayList<>()).add(path);
                        }
                    }
                }

                // 由文件父目录推导画廊目录
                List<UniFile> galleryDirs = new ArrayList<>();
                if (filesByDir != null) {
                    for (String dirPath : filesByDir.keySet()) {
                        File dirFile = new File(dirPath);
                        if (!dirFile.isDirectory()) {
                            continue;
                        }
                        UniFile uniDir = UniFile.fromFile(dirFile);
                        if (uniDir != null && uniDir.isDirectory()) {
                            galleryDirs.add(uniDir);
                        }
                    }
                }
                if (galleryDirs.isEmpty()) {
                    // SAF 或 find 失败/无文件时回退 UniFile 枚举
                    UniFile[] allEntries = downloadDir.listFiles();
                    if (allEntries != null) {
                        for (UniFile entry : allEntries) {
                            if (entry != null && entry.isDirectory()) {
                                galleryDirs.add(entry);
                            }
                        }
                    }
                }

                int totalDirs = galleryDirs.size();
                mScanTotal.set(totalDirs);
                Log.i(TAG, "Found " + totalDirs + " gallery directories to scan");

                if (totalDirs == 0) {
                    Log.w(TAG, "No gallery directories found, nothing to scan");
                }

                Set<Long> seenGids = new HashSet<>();
                ScanAccumulator acc = new ScanAccumulator();
                int processed = 0;

                for (UniFile galleryDir : galleryDirs) {
                    if (galleryDir == null) {
                        continue;
                    }
                    String dirName = galleryDir.getName();
                    try {
                        List<String> dirFiles = null;
                        File galleryDirFile = toFile(galleryDir);
                        if (galleryDirFile != null && filesByDir != null) {
                            dirFiles = filesByDir.get(galleryDirFile.getAbsolutePath());
                        }
                        scanGalleryDirectory(galleryDir, dirFiles, existingByToken, existingTokensByGid, seenGids, acc);
                    } catch (Exception e) {
                        Log.e(TAG, "Error scanning gallery directory: " + dirName, e);
                    }

                    processed++;
                    mScanProgress.set(processed);
                    if (progressListener != null) {
                        progressListener.onProgress(processed, totalDirs);
                    }
                }

                // 磁盘上已不存在的画廊/文件标记为 STATUS_DELETED，保留文件级历史
                acc.markedDeleted += markMissingFilesDeleted(existingByToken, existingTokensByGid, seenGids);

                // 刷新物化视图，保证下载列表大小展示与对账结果一致
                refreshGallerySizeView();

                Log.i(TAG, "Scan completed: dirs=" + totalDirs
                        + ", galleries=" + acc.galleryCount
                        + ", files=" + acc.scannedFiles
                        + ", new=" + acc.newFiles
                        + ", updated=" + acc.updatedFiles
                        + ", markedDeleted=" + acc.markedDeleted
                        + ", historyRebuilt=" + acc.historyRebuilt);

                mScanStatus = SCAN_STATUS_COMPLETED;
                if (progressListener != null) {
                    progressListener.onCompleted();
                }
            } catch (Exception e) {
                mScanStatus = SCAN_STATUS_ERROR;
                mScanError = e.getMessage();
                Log.e(TAG, "Error during scan", e);

                if (progressListener != null) {
                    progressListener.onError(e);
                }
            } finally {
                mScanStatus = SCAN_STATUS_IDLE;
            }
        }).start();
    }

    /**
     * 扫描单个画廊目录
     */
    private void scanGalleryDirectory(
            UniFile galleryDir,
            @Nullable List<String> shellFiles,
            Map<String, DownloadedFile> existingByToken,
            Map<Long, Set<String>> existingTokensByGid,
            Set<Long> seenGids,
            ScanAccumulator acc) throws Exception {
        String dirName = galleryDir.getName();

        // 检查 .ehviewer 文件
        UniFile ehviewerFile = galleryDir.findFile(".ehviewer");
        if (ehviewerFile == null) {
            Log.d(TAG, "No .ehviewer file found in directory: " + dirName);
            return;
        }

        // 读取 SpiderInfo
        SpiderInfo spiderInfo;
        String oldFormatTitle = null;
        File galleryDirFile = toFile(galleryDir);
        if (galleryDirFile != null) {
            String ehviewerContent = readFileContent(new File(galleryDirFile, ".ehviewer"));
            if (ehviewerContent != null) {
                spiderInfo = SpiderInfo.read(new ByteArrayInputStream(ehviewerContent.getBytes(StandardCharsets.UTF_8)));
                oldFormatTitle = parseOldFormatTitle(ehviewerContent);
            } else {
                spiderInfo = SpiderInfo.read(ehviewerFile);
            }
        } else {
            spiderInfo = SpiderInfo.read(ehviewerFile);
        }
        if (spiderInfo == null) {
            Log.w(TAG, "Failed to read SpiderInfo from .ehviewer file: " + dirName);
            return;
        }

        long gid = spiderInfo.gid;
        seenGids.add(gid);
        acc.galleryCount++;

        // 补建下载历史：磁盘上存在但下载历史/下载列表均缺失的画廊
        if (!EhDB.hasDownloadHistory(gid) && EhDB.getDownloadInfo(gid) == null) {
            try {
                EhDB.recordDownloadHistoryFromScan(gid, spiderInfo.token, oldFormatTitle, dirName);
                acc.historyRebuilt++;
                Log.i(TAG, "Rebuilt download history for gid=" + gid + ", dir=" + dirName);
            } catch (Exception e) {
                Log.w(TAG, "Failed to record download history for gid=" + gid, e);
            }
        }

        // 本画廊在 DB 中的 token 集合（用于标记磁盘上已缺失的文件）
        Set<String> gidTokens = existingTokensByGid.computeIfAbsent(gid, k -> new HashSet<>());
        Set<String> seenTokens = new HashSet<>();

        List<DownloadedFile> toInsert = new ArrayList<>();
        List<DownloadedFile> toUpdate = new ArrayList<>();

        int fileCount = 0;
        if (shellFiles != null) {
            for (String filePath : shellFiles) {
                if (TextUtils.isEmpty(filePath)) {
                    continue;
                }
                File fileObj = new File(filePath);
                if (!fileObj.exists() || fileObj.isDirectory()) {
                    continue;
                }
                if (reconcileImageFile(gid, fileObj.getName(), fileObj.getAbsolutePath(), fileObj.length(),
                        spiderInfo, existingByToken, seenTokens, toInsert, toUpdate, acc)) {
                    fileCount++;
                }
            }
        } else {
            UniFile[] imageFiles = galleryDir.listFiles();
            if (imageFiles == null) {
                Log.d(TAG, "No files found in directory: " + dirName);
                return;
            }
            for (UniFile file : imageFiles) {
                if (file.isDirectory()) {
                    continue;
                }
                String filename = file.getName();
                String path = file.getUri() != null ? file.getUri().getPath() : null;
                if (filename == null || path == null) {
                    continue;
                }
                if (reconcileImageFile(gid, filename, path, file.length(),
                        spiderInfo, existingByToken, seenTokens, toInsert, toUpdate, acc)) {
                    fileCount++;
                }
            }
        }

        // 批量写库（不计算 MD5：MD5 在应用中无消费者，全量读图算 MD5 是扫描慢的主因）
        if (!toInsert.isEmpty()) {
            mDownloadedFilesDao.insertInTx(toInsert);
        }
        if (!toUpdate.isEmpty()) {
            mDownloadedFilesDao.updateInTx(toUpdate);
        }

        // 标记本画廊在 DB 中但磁盘已缺失的文件为已删除，保留文件级历史
        gidTokens.removeAll(seenTokens);
        if (!gidTokens.isEmpty()) {
            List<DownloadedFile> stale = new ArrayList<>(gidTokens.size());
            for (String token : gidTokens) {
                DownloadedFile f = existingByToken.get(token);
                if (f != null && f.getStatus() == DownloadedFile.STATUS_NORMAL) {
                    f.setStatus(DownloadedFile.STATUS_DELETED);
                    stale.add(f);
                }
            }
            if (!stale.isEmpty()) {
                mDownloadedFilesDao.updateInTx(stale);
                acc.markedDeleted += stale.size();
            }
            gidTokens.clear();
        }

        acc.scannedFiles += fileCount;
        Log.i(TAG, "Scanned gallery directory: " + dirName + ", files=" + fileCount
                + ", new=" + toInsert.size() + ", updated=" + toUpdate.size());
    }

    /**
     * 对账单个图片文件：命中现有记录则更新（保留 download_time/历史），否则新建。
     * 返回该文件是否为可记录的图片文件。
     */
    private boolean reconcileImageFile(
            long gid,
            String filename,
            String path,
            long size,
            SpiderInfo spiderInfo,
            Map<String, DownloadedFile> existingByToken,
            Set<String> seenTokens,
            List<DownloadedFile> toInsert,
            List<DownloadedFile> toUpdate,
            ScanAccumulator acc) {
        if (filename == null || filename.startsWith(".")) {
            return false;
        }
        if (!isImageExtension(extensionOf(filename))) {
            return false;
        }

        String rawToken = extractTokenFromFilename(filename);
        if (rawToken == null) {
            return false;
        }

        String fileToken = null;
        String dbToken;
        if (rawToken.matches("\\d+")) {
            // 纯数字文件名，尝试从 .ehviewer 的 pTokenMap 恢复真实 fileToken
            int index;
            try {
                index = Integer.parseInt(rawToken);
            } catch (NumberFormatException e) {
                return false;
            }
            String resolvedToken = spiderInfo.pTokenMap != null ? spiderInfo.pTokenMap.get(index) : null;
            if (resolvedToken != null && !SpiderInfo.TOKEN_FAILED.equals(resolvedToken)) {
                fileToken = resolvedToken;
                dbToken = resolvedToken;
            } else {
                dbToken = gid + "_" + rawToken;
            }
        } else {
            dbToken = rawToken;
            fileToken = rawToken;
        }

        DownloadedFile existing = existingByToken.get(dbToken);
        boolean isNew;
        if (existing == null) {
            existing = new DownloadedFile();
            existing.setToken(dbToken);
            existing.setFileToken(fileToken);
            existing.setGid(gid);
            existing.setFilename(filename);
            existing.setDownload_time(System.currentTimeMillis());
            existing.setStatus(DownloadedFile.STATUS_NORMAL);
            existingByToken.put(dbToken, existing);
            isNew = true;
            acc.newFiles++;
        } else {
            isNew = false;
            if (existing.getStatus() != DownloadedFile.STATUS_NORMAL) {
                existing.setStatus(DownloadedFile.STATUS_NORMAL);
            }
            acc.updatedFiles++;
        }
        existing.setPath(path);
        existing.setLast_accessed(System.currentTimeMillis());
        if (size > 0) {
            existing.setSize(size);
        }
        seenTokens.add(dbToken);
        if (isNew) {
            toInsert.add(existing);
        } else {
            toUpdate.add(existing);
        }
        return true;
    }

    /**
     * 创建 DOWNLOADED_FILES 表（含 FILE_TOKEN 列，与 DAO schema 一致）。
     */
    private void createDownloadedFilesTable() throws Exception {
        mDownloadedFilesDao.getDatabase().execSQL("CREATE TABLE IF NOT EXISTS \"DOWNLOADED_FILES\" (" +
                "\"TOKEN\" TEXT PRIMARY KEY NOT NULL ," +
                "\"FILE_TOKEN\" TEXT," +
                "\"GID\" INTEGER NOT NULL ," +
                "\"FILENAME\" TEXT NOT NULL ," +
                "\"MD5\" TEXT," +
                "\"PATH\" TEXT NOT NULL ," +
                "\"SIZE\" INTEGER," +
                "\"DOWNLOAD_TIME\" INTEGER NOT NULL ," +
                "\"LAST_ACCESSED\" INTEGER," +
                "\"STATUS\" INTEGER NOT NULL );");
    }

    /**
     * 载入 DOWNLOADED_FILES 全部记录到内存，供增量对账使用。
     */
    @NonNull
    private Map<String, DownloadedFile> loadAllFilesByToken() {
        Map<String, DownloadedFile> map = new HashMap<>();
        try {
            for (DownloadedFile f : mDownloadedFilesDao.loadAll()) {
                if (f != null && f.getToken() != null) {
                    map.put(f.getToken(), f);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load existing DOWNLOADED_FILES records", e);
        }
        return map;
    }

    /**
     * 标记磁盘上已不存在的画廊的全部文件为 STATUS_DELETED，保留文件级历史。
     *
     * @return 标记删除的记录数
     */
    private int markMissingFilesDeleted(
            Map<String, DownloadedFile> existingByToken,
            Map<Long, Set<String>> existingTokensByGid,
            Set<Long> seenGids) {
        List<DownloadedFile> stale = new ArrayList<>();
        for (Map.Entry<Long, Set<String>> entry : existingTokensByGid.entrySet()) {
            long gid = entry.getKey();
            if (seenGids.contains(gid)) {
                // 已按画廊在 scanGalleryDirectory 内处理过
                continue;
            }
            for (String token : entry.getValue()) {
                DownloadedFile f = existingByToken.get(token);
                if (f != null && f.getStatus() == DownloadedFile.STATUS_NORMAL) {
                    f.setStatus(DownloadedFile.STATUS_DELETED);
                    stale.add(f);
                }
            }
        }
        if (!stale.isEmpty()) {
            mDownloadedFilesDao.updateInTx(stale);
        }
        return stale.size();
    }

    /**
     * 扫描过程中的累计统计信息
     */
    private static class ScanAccumulator {
        int galleryCount;
        int scannedFiles;
        int newFiles;
        int updatedFiles;
        int markedDeleted;
        int historyRebuilt;
    }

        /**
         * 检查是否是图片扩展名
         */
        private boolean isImageExtension (String extension){
            return "jpg".equalsIgnoreCase(extension) ||
                    "jpeg".equalsIgnoreCase(extension) ||
                    "png".equalsIgnoreCase(extension) ||
                    "gif".equalsIgnoreCase(extension) ||
                    "webp".equalsIgnoreCase(extension) ||
                    "bmp".equalsIgnoreCase(extension);
        }

        /**
         * 从文件名提取token
         * 支持两种文件名格式:
         * 1. index-token.ext (例如: 0001-abc123.jpg)
         * 2. index.ext (例如: 00000008.jpg)
         */
        @Nullable
        private String extractTokenFromFilename (String filename){
            if (TextUtils.isEmpty(filename)) {
                return null;
            }

            String trimmedFilename = filename.trim();

            // 使用修剪后的文件名
            // 查找最后一个点号（支持半角点号 '.' 和全角点号 '．'）
            int lastDot = trimmedFilename.lastIndexOf('.');
            if (lastDot < 0) {
                // 尝试全角点号
                lastDot = trimmedFilename.lastIndexOf('．');
            }

            if (lastDot <= 0) {
                return null;
            }

            int lastDash = trimmedFilename.lastIndexOf('-', lastDot);

            if (lastDash > 0) {
                // 格式1: index-token.ext
                return trimmedFilename.substring(lastDash + 1, lastDot);
            } else {
                // 格式2: index.ext - 使用不带扩展名的文件名作为token
                return trimmedFilename.substring(0, lastDot);
            }
        }

        /**
         * 获取扫描状态
         */
        public int getScanStatus () {
            return mScanStatus;
        }

        /**
         * 获取扫描进度
         */
        public int getScanProgress () {
            return mScanProgress.get();
        }

        /**
         * 获取扫描总数
         */
        public int getScanTotal () {
            return mScanTotal.get();
        }

        /**
         * 获取扫描错误信息
         */
        public String getScanError() {
            return mScanError;
        }
    }