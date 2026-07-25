package com.kickercam.storage

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class ClipMeta(
    val id: String,
    val fileName: String,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    /** Offset inside the clip where the detection fired — the middle of the action. */
    val momentOffsetMs: Long,
    val widthPx: Int,
    val heightPx: Int,
    val fps: Int,
    val triggerReason: String,
    val detectorMode: String,
    val lensLabel: String,
    val hasAudio: Boolean,
    val thumbFrameCount: Int = 0,
    val exported: Boolean = false,
    val exportedUri: String? = null,
)

/** A clip on disk together with its metadata and pre-rendered thumbnail frames. */
data class Clip(
    val meta: ClipMeta,
    val videoFile: File,
    val thumbnailDir: File,
) {
    val id: String get() = meta.id

    val thumbnailFrames: List<File>
        get() = (0 until meta.thumbFrameCount).map { File(thumbnailDir, frameName(it)) }
            .filter { it.exists() }

    val posterFrame: File? get() = thumbnailFrames.firstOrNull()

    val sizeBytes: Long get() = videoFile.length()

    companion object {
        fun frameName(index: Int): String = "f%02d.jpg".format(index)
    }
}
