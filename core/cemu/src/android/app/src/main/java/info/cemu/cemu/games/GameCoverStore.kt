package info.cemu.cemu.games

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import info.cemu.cemu.nativeinterface.NativeActiveSettings
import java.io.File
import java.util.Collections

// Custom per-game cover images.
//
// Covers are stored as JPEG files in <userDataPath>/covers/<titleId>.jpg and
// can be set by the user via long-press on a game in the list ("custom cover").
// A small in-memory cache keeps the decoded bitmaps around so scrolling the
// game grid stays smooth.
object GameCoverStore {
    private const val MAX_DECODE_DIMENSION = 640

    private val lock = Any()
    private val cache = Collections.synchronizedMap(HashMap<Long, ImageBitmap?>())

    fun coversFolder(): File =
        File(NativeActiveSettings.getUserDataPath()).resolve("covers")

    fun coverFile(titleId: Long): File =
        coversFolder().resolve("$titleId.jpg")

    fun hasCover(titleId: Long): Boolean = coverFile(titleId).isFile

    fun loadCover(titleId: Long): ImageBitmap? {
        synchronized(lock) {
            if (cache.containsKey(titleId)) {
                return cache[titleId]
            }
        }
        val bitmap = decodeCoverFile(coverFile(titleId))
        val imageBitmap = bitmap?.asImageBitmap()
        synchronized(lock) {
            cache[titleId] = imageBitmap
        }
        return imageBitmap
    }

    fun invalidate(titleId: Long) {
        synchronized(lock) {
            cache.remove(titleId)
        }
    }

    fun invalidateAll() {
        synchronized(lock) {
            cache.clear()
        }
    }

    // Copies the picked image into the covers folder (decoded and downscaled
    // to keep the storage footprint small) and refreshes the cache.
    fun importCover(context: Context, uri: Uri, titleId: Long): Boolean {
        return try {
            val bitmap = decodeFromUri(context, uri) ?: return false
            val target = coverFile(titleId)
            val folder = target.parentFile
            if (folder != null && !folder.exists()) folder.mkdirs()
            target.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
            invalidate(titleId)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun removeCover(titleId: Long): Boolean {
        val removed = coverFile(titleId).delete()
        invalidate(titleId)
        return removed
    }

    private fun decodeCoverFile(file: File): Bitmap? {
        if (!file.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds)
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val maxDim = maxOf(info.size.width, info.size.height)
                    if (maxDim > MAX_DECODE_DIMENSION) {
                        val scale = MAX_DECODE_DIMENSION.toFloat() / maxDim
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1),
                        )
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(bounds: BitmapFactory.Options): Int {
        var inSampleSize = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        if (maxDim > MAX_DECODE_DIMENSION) {
            while (maxDim / (inSampleSize * 2) >= MAX_DECODE_DIMENSION) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
