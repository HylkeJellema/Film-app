package com.kickercam.storage

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.FileOutputStream

private const val TAG = "ThumbnailGenerator"

/**
 * Renders the short looping preview shown in the gallery.
 *
 * A handful of small frames spanning the moment of detection, played back in a loop, makes it
 * obvious at a glance which clip caught the trick — far quicker than opening each one to check.
 */
object ThumbnailGenerator {

    const val FRAME_COUNT = 14
    const val FRAME_WIDTH = 240
    private const val SPAN_BEFORE_MS = 1_200L
    private const val SPAN_AFTER_MS = 1_600L

    /** Extracts frames into [outputDir] and returns how many were written. */
    fun generate(videoFile: File, momentOffsetMs: Long, durationMs: Long, outputDir: File): Int {
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            Log.w(TAG, "cannot create thumbnail dir ${outputDir.absolutePath}")
            return 0
        }

        val retriever = MediaMetadataRetriever()
        var written = 0
        try {
            retriever.setDataSource(videoFile.absolutePath)

            val actualDuration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: durationMs

            val start = (momentOffsetMs - SPAN_BEFORE_MS).coerceIn(0L, actualDuration)
            val end = (momentOffsetMs + SPAN_AFTER_MS).coerceIn(start, actualDuration)
            val step = if (FRAME_COUNT > 1) (end - start) / (FRAME_COUNT - 1) else 0L

            var height = 0
            for (i in 0 until FRAME_COUNT) {
                val timeUs = (start + step * i) * 1000L
                if (height == 0) {
                    // Derive the thumbnail height from the first decoded frame so odd aspects work.
                    val probe = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    height = if (probe != null && probe.width > 0) {
                        (FRAME_WIDTH * probe.height / probe.width).coerceAtLeast(2)
                    } else {
                        FRAME_WIDTH * 9 / 16
                    }
                    probe?.recycle()
                }

                val bitmap = try {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        FRAME_WIDTH,
                        height,
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "frame extraction failed at ${timeUs}us", t)
                    null
                } ?: continue

                val target = File(outputDir, Clip.frameName(written))
                try {
                    FileOutputStream(target).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 72, out)
                    }
                    written++
                } catch (t: Throwable) {
                    Log.w(TAG, "cannot write ${target.name}", t)
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "thumbnail generation failed for ${videoFile.name}", t)
        } finally {
            runCatching { retriever.release() }
        }
        return written
    }
}
