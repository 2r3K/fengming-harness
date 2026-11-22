package io.github.r2r3k.fengmingharness.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 备份包（数据库 + 图片）的单元测试，跑在电脑上。
 *
 * 跑法：
 * ```
 * .\gradlew.bat testDebugUnitTest --tests "io.github.r2r3k.fengmingharness.core.store.BackupArchiveTest.*"
 * ```
 */
class BackupArchiveTest {

    private fun tempDir(name: String): File {
        val dir = File.createTempFile("backup-test", name)
        dir.delete()
        dir.mkdirs()
        return dir
    }

    @Test
    fun roundTripKeepsDatabaseAndReferencedImagesOnly() {
        val root = tempDir("root")
        val dbFile = File(root, "harness.db")
        dbFile.writeBytes("这是一份假的 SQLite 内容".toByteArray(Charsets.UTF_8))

        val imagesDir = File(root, "images").apply { mkdirs() }
        File(imagesDir, "a.jpg").writeBytes(ByteArray(1000) { 1 })
        File(imagesDir, "b.jpg").writeBytes(ByteArray(2000) { 2 })
        // 没被任何消息引用：不该进备份
        File(imagesDir, "orphan.jpg").writeBytes(ByteArray(3000) { 3 })

        val referenced = setOf(
            File(imagesDir, "a.jpg").absolutePath,
            File(imagesDir, "b.jpg").absolutePath,
        )

        val packed = ByteArrayOutputStream()
        val written = BackupArchive.write(
            out = packed,
            dbFile = dbFile,
            imagesDir = imagesDir,
            referenced = referenced,
            meta = BackupArchive.Meta(appVersion = "1.3.4", conversations = 3, messages = 9),
        )
        assertEquals("只该打被引用的图片", 2, written.images)
        assertEquals(1000L + 2000L, written.imageBytes)

        // 解开
        val restoreRoot = tempDir("restore")
        val dbTarget = File(restoreRoot, "harness.db")
        val imagesTarget = File(restoreRoot, "images")
        val meta = BackupArchive.read(
            ByteArrayInputStream(packed.toByteArray()),
            dbTarget,
            imagesTarget,
        )

        assertEquals("数据库内容要对得上", "这是一份假的 SQLite 内容", dbTarget.readText(Charsets.UTF_8))
        assertEquals(setOf("a.jpg", "b.jpg"), imagesTarget.listFiles()?.map { it.name }?.toSet())
        assertFalse("孤儿图片不该被备份", File(imagesTarget, "orphan.jpg").exists())
        assertEquals(2000, File(imagesTarget, "b.jpg").length().toInt())
        assertEquals(2, meta?.images)
        assertEquals(3, meta?.conversations)
        assertEquals(9, meta?.messages)
        assertEquals("1.3.4", meta?.appVersion)
    }

    @Test
    fun maliciousEntryNamesNeverEscapeTheFolder() {
        // 手工造一个"坏包"：塞 ../、多层目录、绝对路径
        val packed = ByteArrayOutputStream()
        ZipOutputStream(packed).use { zip ->
            zip.putNextEntry(ZipEntry("harness.db"))
            zip.write("db".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("images/../../evil.txt"))
            zip.write("bad".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("images/sub/dir/ok.jpg"))
            zip.write("ok".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("/etc/passwd"))
            zip.write("bad".toByteArray())
            zip.closeEntry()
        }

        val root = tempDir("evil")
        val dbTarget = File(root, "nested/dir/harness.db")
        val imagesTarget = File(root, "img")
        BackupArchive.read(ByteArrayInputStream(packed.toByteArray()), dbTarget, imagesTarget)

        // 数据库照常恢复
        assertTrue(dbTarget.isFile)
        // 只有 ok.jpg 落地（按单层文件名拍平），带 .. 的条目整条丢掉
        assertEquals(listOf("ok.jpg"), imagesTarget.listFiles()?.map { it.name })
        // 没有任何文件被写到目录外面
        assertFalse(File(root, "evil.txt").exists())
        assertFalse(File(root.parentFile, "evil.txt").exists())
    }

    @Test
    fun packageWithoutDatabaseIsRejected() {
        val packed = ByteArrayOutputStream()
        ZipOutputStream(packed).use { zip ->
            zip.putNextEntry(ZipEntry("images/a.jpg"))
            zip.write(ByteArray(10))
            zip.closeEntry()
        }
        val root = tempDir("nod b")
        val failure = runCatching {
            BackupArchive.read(
                ByteArrayInputStream(packed.toByteArray()),
                File(root, "harness.db"),
                File(root, "images"),
            )
        }.exceptionOrNull()
        assertTrue("没有数据库的包必须报错：${failure?.javaClass?.simpleName}", failure is IllegalArgumentException)
    }

    @Test
    fun zipSniffingWorks() {
        val zipBytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("x"))
                zip.write(1)
                zip.closeEntry()
            }
        }.toByteArray()
        assertTrue(BackupArchive.looksLikeZip(ByteArrayInputStream(zipBytes)))

        val sqliteHead = byteArrayOf(0x53, 0x51, 0x4C, 0x69, 0x74, 0x65) // "SQLite"
        assertFalse(BackupArchive.looksLikeZip(ByteArrayInputStream(sqliteHead)))
    }

    @Test
    fun legacyPlainDatabaseStillHasNoMeta() {
        // 老格式：裸 db（非 zip）——read 不该被用来处理它，这里只确认 meta 解析对空包的反应
        val packed = ByteArrayOutputStream()
        ZipOutputStream(packed).use { zip ->
            zip.putNextEntry(ZipEntry("harness.db"))
            zip.write("db".toByteArray())
            zip.closeEntry()
        }
        val root = tempDir("legacy")
        val meta = BackupArchive.read(
            ByteArrayInputStream(packed.toByteArray()),
            File(root, "harness.db"),
            File(root, "images"),
        )
        assertNull("没有备份清单时返回 null", meta)
    }
}
