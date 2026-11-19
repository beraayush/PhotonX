package com.photonx.camera.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

class MediaStoreRepository @Inject constructor(
    private val context: Context,
    private val logger: AppLogger
) {

    suspend fun saveRawJpegToGallery(
        jpegBytes: ByteArray,
        rotationDegrees: Int,
        modeTag: String
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val filename = "PhotonX_${modeTag}_$timestamp.jpg"

            val tempFile = File(context.cacheDir, "raw_$filename")
            tempFile.writeBytes(jpegBytes)

            val exif = ExifInterface(tempFile.absolutePath)
            exif.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                rotationToExifOrientation(rotationDegrees).toString()
            )
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "PhotonX 3.0")
            exif.setAttribute(
                ExifInterface.TAG_DATETIME_ORIGINAL,
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
            )
            exif.saveAttributes()

            val finalBytes = tempFile.readBytes()
            tempFile.delete()

            val uri = insertBytesToMediaStore(finalBytes, filename)
            logger.info("MediaStore", "Saved lossless AUTO JPEG: $filename (${finalBytes.size / 1024} KB)")
            uri
        } catch (e: Exception) {
            logger.error("MediaStore", "Failed saving raw JPEG bytes", e)
            null
        }
    }

    suspend fun saveBitmapToGallery(
        bitmap: Bitmap,
        rotationDegrees: Int,
        modeTag: String,
        sourceExifBytes: ByteArray? = null
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val filename = "PhotonX_${modeTag}_$timestamp.jpg"

            val tempFile = File(context.cacheDir, "proc_$filename")
            tempFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 97, out)
            }

            val exif = ExifInterface(tempFile.absolutePath)
            if (sourceExifBytes != null) {
                try {
                    val srcExif = ExifInterface(ByteArrayInputStream(sourceExifBytes))
                    listOf(
                        ExifInterface.TAG_MAKE,
                        ExifInterface.TAG_MODEL,
                        ExifInterface.TAG_F_NUMBER,
                        ExifInterface.TAG_FOCAL_LENGTH,
                        ExifInterface.TAG_ISO_SPEED_RATINGS,
                        ExifInterface.TAG_EXPOSURE_TIME
                    ).forEach { tag ->
                        srcExif.getAttribute(tag)?.let { valStr ->
                            exif.setAttribute(tag, valStr)
                        }
                    }
                } catch (_: Exception) {}
            }
            exif.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                rotationToExifOrientation(rotationDegrees).toString()
            )
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "PhotonX 3.0")
            exif.setAttribute(
                ExifInterface.TAG_DATETIME_ORIGINAL,
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
            )
            exif.saveAttributes()

            val finalBytes = tempFile.readBytes()
            tempFile.delete()

            val uri = insertBytesToMediaStore(finalBytes, filename)
            logger.info("MediaStore", "Saved processed $modeTag: $filename (${bitmap.width}x${bitmap.height})")
            uri
        } catch (e: Exception) {
            logger.error("MediaStore", "Failed saving processed bitmap", e)
            null
        }
    }

    private fun insertBytesToMediaStore(bytes: ByteArray, filename: String): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/PhotonX")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (imageUri != null) {
            resolver.openOutputStream(imageUri)?.use { os ->
                os.write(bytes)
                os.flush()
            }
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(imageUri, contentValues, null, null)
        }
        return imageUri
    }

    private fun rotationToExifOrientation(rotationDegrees: Int): Int = when ((rotationDegrees % 360 + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }
}
