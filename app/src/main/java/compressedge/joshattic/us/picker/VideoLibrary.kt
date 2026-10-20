package compressedge.joshattic.us.picker

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size

/** One video row from MediaStore, as shown in the built-in video picker. */
data class VideoLibraryItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateAddedSec: Long,
    val width: Int,
    val height: Int
)

enum class VideoSort(val sortOrder: String) {
    NEWEST(MediaStore.Video.Media.DATE_ADDED + " DESC"),
    OLDEST(MediaStore.Video.Media.DATE_ADDED + " ASC"),
    LARGEST(MediaStore.Video.Media.SIZE + " DESC"),
    SMALLEST(MediaStore.Video.Media.SIZE + " ASC"),
    LONGEST(MediaStore.Video.Media.DURATION + " DESC"),
    NAME(MediaStore.Video.Media.DISPLAY_NAME + " ASC")
}

/**
 * Loads the device video library straight from MediaStore.
 *
 * Videos already produced by this app are excluded: the default gallery
 * output lives in a folder named "Compressor Edge" (see saveToGallery),
 * so anything whose relative path contains that folder name is skipped.
 * On pre-Android 10 devices RELATIVE_PATH does not exist, so the legacy
 * DATA column is filtered instead.
 */
object VideoLibrary {

    private const val OUTPUT_FOLDER_NAME = "Compressor Edge"

    fun loadVideos(context: Context, sort: VideoSort): List<VideoLibraryItem> {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val projection = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )
        val selection: String
        val selectionArgs: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Video.Media.RELATIVE_PATH)
            selection = MediaStore.Video.Media.RELATIVE_PATH + " NOT LIKE ?"
            selectionArgs = arrayOf("%$OUTPUT_FOLDER_NAME%")
        } else {
            @Suppress("DEPRECATION")
            selection = MediaStore.MediaColumns.DATA + " NOT LIKE ?"
            selectionArgs = arrayOf("%/$OUTPUT_FOLDER_NAME/%")
        }

        val items = mutableListOf<VideoLibraryItem>()
        resolver.query(collection, projection.toTypedArray(), selection, selectionArgs, sort.sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                items.add(
                    VideoLibraryItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = cursor.getString(nameCol) ?: "Video",
                        sizeBytes = cursor.getLong(sizeCol),
                        durationMs = cursor.getLong(durationCol),
                        dateAddedSec = cursor.getLong(dateCol),
                        width = cursor.getInt(widthCol),
                        height = cursor.getInt(heightCol)
                    )
                )
            }
        }
        return items
    }

    fun loadThumbnail(context: Context, item: VideoLibraryItem): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(item.uri, Size(360, 360), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver,
                    item.id,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** Small in-memory thumbnail cache so grid scrolling does not re-decode. */
object VideoThumbnailCache {
    private val cache = LruCache<Long, Bitmap>(64)

    fun get(id: Long): Bitmap? = cache.get(id)

    fun put(id: Long, bitmap: Bitmap) {
        cache.put(id, bitmap)
    }
}
