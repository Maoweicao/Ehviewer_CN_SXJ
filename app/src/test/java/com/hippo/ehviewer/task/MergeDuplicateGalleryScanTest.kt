package com.hippo.ehviewer.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MergeDuplicateGalleryScanTest {

    @Test
    fun parseShellScanOutput_parsesDirMtimeAndCounts() {
        val raw = "D\tgallery1\t1712345678.123\n" +
            "D\tgallery2\t1712345680\n" +
            "D\tgallery three\t1712345690.5\n" +
            "     1234 ./gallery1\t\n" +
            "        7 ./gallery2\t\n" +
            "        3 ./gallery three\t\n"

        val result = MergeDuplicateGalleryTask.parseShellScanOutput(raw)

        assertEquals(3, result.size)

        val g1 = result["gallery1"]
        assertEquals("gallery1", g1?.dirname)
        assertEquals(1234, g1?.fileCount)
        assertEquals(1712345678123L, g1?.mtime)

        val g2 = result["gallery2"]
        assertEquals(7, g2?.fileCount)
        assertEquals(1712345680000L, g2?.mtime)

        val g3 = result["gallery three"]
        assertEquals(3, g3?.fileCount)
        assertEquals(1712345690500L, g3?.mtime)
    }

    @Test
    fun parseShellScanOutput_ignoresCountLinesWithoutDirEntry() {
        val raw = "D\tonlydir\t100\n" +
            "  7 ./onlydir\t\n" +
            "  2 ./\t\n" +
            "  5 ./other\t\n"

        val result = MergeDuplicateGalleryTask.parseShellScanOutput(raw)

        assertEquals(1, result.size)
        assertEquals(7, result["onlydir"]?.fileCount)
    }

    @Test
    fun scanNativeDirectory_countsFilesRecursively() {
        val root = Files.createTempDirectory("mgd_scan_").toFile()
        try {
            val a = File(root, "12345-galleryA").apply { mkdirs() }
            File(a, "000001.jpg").createNewFile()
            File(a, ".ehviewer").createNewFile()
            val b = File(root, "99999-galleryB").apply { mkdirs() }
            File(b, "sub").mkdirs()
            File(File(b, "sub"), "page.png").createNewFile()

            val result = MergeDuplicateGalleryTask.scanNativeDirectory(root)

            assertEquals(2, result?.size)

            val entryA = result?.get("12345-galleryA")
            assertEquals(2, entryA?.fileCount)
            assertTrue((entryA?.mtime ?: 0L) > 0L)

            val entryB = result?.get("99999-galleryB")
            assertEquals(1, entryB?.fileCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun scanNativeDirectory_skipsPlainFilesInRoot() {
        val root = Files.createTempDirectory("mgd_rootfile_").toFile()
        try {
            File(root, "a.txt").createNewFile()
            val sub = File(root, "subdir").apply { mkdirs() }
            File(sub, "1.jpg").createNewFile()

            val result = MergeDuplicateGalleryTask.scanNativeDirectory(root)

            assertEquals(1, result?.size)
            assertEquals(1, result?.get("subdir")?.fileCount)
        } finally {
            root.deleteRecursively()
        }
    }
}