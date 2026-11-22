package io.github.r2r3k.fengmingharness.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * 图片预处理：缩到最长边 1300、按 EXIF 扶正、压 JPEG；
 * 同时生成一张 240 的小缩略图（列表只解码缩略图，避免大图把内存吃爆）。
 */
object ImagePrep {

    private const val MAX_EDGE = 1300
    private const val THUMB_EDGE = 320
    private const val QUALITY = 82

    data class Prepared(val bytes: ByteArray, val path: String, val thumbPath: String)

    fun prepare(ctx: Context, uri: Uri): Result<Prepared> = runCatching {
        val resolver = ctx.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        var opened = false
        resolver.openInputStream(uri)?.use { input ->
            opened = true
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (!opened) {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                opened = true
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
            }
        }
        if (!opened) error("系统没给这个文件的读取权限，请在弹窗点「允许」，或换一张试试")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            error("这张图解不出来（可能是 HEIC 或文件损坏）")
        }

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) > MAX_EDGE) sample *= 2

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
        var source = resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        }
        if (source == null) {
            source = resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, decodeOptions)
            }
        }
        val decoded = source ?: error("图片解码失败，换一张试试")

        val scale = MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }

        val oriented = applyExif(ctx, uri, scaled)

        val dir = File(ctx.filesDir, "images").apply { mkdirs() }
        val base = UUID.randomUUID().toString()

        val full = ByteArrayOutputStream()
        oriented.compress(Bitmap.CompressFormat.JPEG, QUALITY, full)
        val bytes = full.toByteArray()
        if (bytes.isEmpty()) error("压缩后是空文件")
        val file = File(dir, "$base.jpg")
        file.writeBytes(bytes)

        val thumb = saveThumb(dir, base, oriented)

        if (oriented !== decoded) oriented.recycle()
        if (scaled !== decoded && scaled !== oriented) scaled.recycle()

        // 相机拍的原图是临时文件，处理完删掉，避免缓存越积越大
        runCatching { if (uri.toString().contains("/camera/") || uri.path?.contains("camera") == true) ctx.contentResolver.delete(uri, null, null) }

        Prepared(bytes, file.absolutePath, thumb)
    }

    /**
     * 原样存一段图片字节（官方导出里的 webp 直接就存成 webp），只额外生成一张小缩略图。
     *
     * 为什么不像 [prepareBytes] 那样重压：这些是官方历史里的截图，用户可能要点开看字，
     * 重压会糊；而且不重压还省一次编码。
     * 后缀不认识（或解不出来）时退回 [prepareBytes]。
     */
    fun saveOriginal(ctx: Context, bytes: ByteArray, name: String): Prepared? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in ORIGINAL_EXTS) return prepareBytes(ctx, bytes, 1600, 80)

        val dir = File(ctx.filesDir, "images").apply { mkdirs() }
        val base = UUID.randomUUID().toString()
        val file = File(dir, "$base.$ext")
        return runCatching {
            file.writeBytes(bytes)
            val thumb = makeThumbFromBytes(dir, base, bytes)
            if (thumb == null) {
                file.delete()
                return prepareBytes(ctx, bytes, 1600, 80)
            }
            Prepared(bytes, file.absolutePath, thumb)
        }.getOrElse {
            runCatching { file.delete() }
            prepareBytes(ctx, bytes, 1600, 80)
        }
    }

    private fun makeThumbFromBytes(dir: File, base: String, bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) > THUMB_EDGE * 2) sample *= 2

        val bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val thumb = saveThumb(dir, base, bitmap)
        bitmap.recycle()
        return thumb
    }

    private val ORIGINAL_EXTS = setOf("webp", "png", "jpg", "jpeg", "gif")

    /**
     * 从一段现成的图片字节（比如官方导出压缩包里的 webp）生成同样规格的图 + 缩略图。
     * 用来导入历史对话里带的图片：字节没有 Uri，走不了 prepare()。
     */
    fun prepareBytes(
        ctx: Context,
        bytes: ByteArray,
        maxEdge: Int = MAX_EDGE,
        quality: Int = QUALITY,
    ): Prepared? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) > maxEdge) sample *= 2

        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@runCatching null

        val scale = maxEdge.toFloat() / maxOf(decoded.width, decoded.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }

        val dir = File(ctx.filesDir, "images").apply { mkdirs() }
        val base = UUID.randomUUID().toString()
        val full = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, full)
        val out = full.toByteArray()
        if (out.isEmpty()) return@runCatching null
        val file = File(dir, "$base.jpg")
        file.writeBytes(out)
        val thumb = saveThumb(dir, base, scaled)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        Prepared(out, file.absolutePath, thumb)
    }.getOrNull()

    private fun saveThumb(dir: File, base: String, bitmap: Bitmap): String {
        val thumbScale = THUMB_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val thumbBitmap = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * thumbScale).toInt().coerceAtLeast(1),
            (bitmap.height * thumbScale).toInt().coerceAtLeast(1),
            true,
        )
        val thumbFile = File(dir, "${base}_t.jpg")
        thumbFile.outputStream().use { thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 75, it) }
        thumbBitmap.recycle()
        return thumbFile.absolutePath
    }

    fun deleteLocal(path: String) {
        if (path.isBlank()) return
        runCatching { File(path).delete() }
    }

    private fun applyExif(ctx: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val degrees = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                when (
                    ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> -360f
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> -720f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)

        if (degrees == 0f) return bitmap
        val matrix = Matrix()
        when (degrees) {
            -360f -> matrix.postScale(-1f, 1f)
            -720f -> matrix.postScale(1f, -1f)
            else -> matrix.postRotate(degrees)
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }
}
