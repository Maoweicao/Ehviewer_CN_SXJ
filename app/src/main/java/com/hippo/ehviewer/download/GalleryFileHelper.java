package com.hippo.ehviewer.download;

import android.net.Uri;
import android.util.Log;

import com.hippo.ehviewer.Settings;
import com.hippo.unifile.UniFile;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 下载画廊本地文件的删除与回收站工具。
 * 回收站复用下载目录下的 .recycle_bin（与本地画廊模块共用同一目录）。
 */
public final class GalleryFileHelper {

    private static final String TAG = "GalleryFileHelper";
    private static final String RECYCLE_BIN_DIR_NAME = ".recycle_bin";

    private GalleryFileHelper() {
    }

    /** 获取回收站目录（不存在则创建），失败返回 null。 */
    public static UniFile getRecycleBinDir() {
        UniFile downloadLocation = Settings.getDownloadLocation();
        if (downloadLocation == null) {
            return null;
        }
        UniFile recycleBin = downloadLocation.subFile(RECYCLE_BIN_DIR_NAME);
        if (recycleBin != null && !recycleBin.exists()) {
            recycleBin.ensureDir();
        }
        return recycleBin;
    }

    /** 递归删除文件或目录，成功返回 true。 */
    public static boolean deleteRecursively(UniFile file) {
        if (file == null) {
            return false;
        }
        try {
            if (file.isDirectory()) {
                UniFile[] children = file.listFiles();
                if (children != null) {
                    for (UniFile child : children) {
                        deleteRecursively(child);
                    }
                }
            }
            return file.delete();
        } catch (Exception e) {
            Log.e(TAG, "deleteRecursively failed: " + file.getName(), e);
            return false;
        }
    }

    /**
     * 将画廊目录移动到回收站。
     * 文件系统（file scheme）下优先 File.renameTo 快速移动；
     * SAF 等其他场景回退为递归复制后删除源。
     */
    public static boolean moveToRecycleBin(UniFile galleryDir) {
        if (galleryDir == null || !galleryDir.exists()) {
            return false;
        }
        UniFile recycleBin = getRecycleBinDir();
        if (recycleBin == null) {
            return false;
        }
        String name = galleryDir.getName();
        UniFile target = recycleBin.subFile(name);
        if (target != null && target.exists()) {
            target = recycleBin.subFile(name + "_" + System.currentTimeMillis());
        }
        if (target == null) {
            return false;
        }
        Uri uri = galleryDir.getUri();
        if (uri != null && UniFile.isFileUri(uri)) {
            File src = new File(uri.getPath());
            File dst = new File(target.getUri().getPath());
            return src.renameTo(dst);
        }
        if (copyRecursively(galleryDir, target)) {
            return deleteRecursively(galleryDir);
        }
        return false;
    }

    private static boolean copyRecursively(UniFile src, UniFile dst) {
        try {
            if (src.isDirectory()) {
                if (!dst.ensureDir()) {
                    return false;
                }
                UniFile[] children = src.listFiles();
                if (children != null) {
                    for (UniFile child : children) {
                        UniFile childDst = dst.subFile(child.getName());
                        if (childDst == null || !copyRecursively(child, childDst)) {
                            return false;
                        }
                    }
                }
                return true;
            } else {
                try (InputStream is = src.openInputStream(); OutputStream os = dst.openOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) > 0) {
                        os.write(buffer, 0, len);
                    }
                }
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "copyRecursively failed: " + src.getName(), e);
            return false;
        }
    }
}
