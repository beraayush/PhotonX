package com.photonx.camera.engine

import com.photonx.camera.data.AppLogger
import org.opencv.android.OpenCVLoader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenCvInitializer @Inject constructor(
    private val logger: AppLogger
) {
    @Volatile
    private var initialized = false

    fun ensureInitialized(): Boolean {
        if (initialized) return true
        return synchronized(this) {
            if (initialized) return true
            try {
                val ok = OpenCVLoader.initLocal()
                if (ok) {
                    initialized = true
                    logger.info("OpenCV", "OpenCV 4.9.0 native library loaded successfully")
                } else {
                    logger.error("OpenCV", "OpenCVLoader.initLocal() returned false")
                }
                ok
            } catch (t: Throwable) {
                logger.error("OpenCV", "Failed to initialize OpenCV native library", t)
                false
            }
        }
    }
}
