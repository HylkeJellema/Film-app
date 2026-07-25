package com.kickercam.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "ClipRepository"

/**
 * Clips live in app-private external storage with a JSON sidecar each, so the on-disk directory is
 * the single source of truth. Nothing to migrate, nothing to corrupt, and a clip that fails to
 * finish writing simply has no sidecar and is ignored.
 */
class ClipRepository(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val appContext = context.applicationContext

    val clipsDir: File = File(
        appContext.getExternalFilesDir(null) ?: appContext.filesDir,
        "clips",
    ).apply { mkdirs() }

    private val thumbsRoot: File = File(
        appContext.getExternalFilesDir(null) ?: appContext.filesDir,
        "thumbs",
    ).apply { mkdirs() }

    private val _clips = MutableStateFlow<List<Clip>>(emptyList())
    val clips: StateFlow<List<Clip>> = _clips.asStateFlow()

    fun thumbnailDirFor(clipId: String): File = File(thumbsRoot, clipId)

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val found = clipsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { sidecar -> readClip(sidecar) }
            ?.sortedByDescending { it.meta.createdAtEpochMs }
            ?: emptyList()
        _clips.value = found
    }

    private fun readClip(sidecar: File): Clip? = try {
        val meta = json.decodeFromString<ClipMeta>(sidecar.readText())
        val video = File(clipsDir, meta.fileName)
        if (!video.exists()) {
            sidecar.delete()
            null
        } else {
            Clip(meta, video, thumbnailDirFor(meta.id))
        }
    } catch (t: Throwable) {
        Log.w(TAG, "unreadable sidecar ${sidecar.name}", t)
        null
    }

    /** Writes the sidecar and renders thumbnails. Call after the muxer has closed the file. */
    suspend fun register(meta: ClipMeta): Clip? = withContext(Dispatchers.IO) {
        val video = File(clipsDir, meta.fileName)
        if (!video.exists()) {
            Log.w(TAG, "register called for missing file ${meta.fileName}")
            return@withContext null
        }

        val thumbDir = thumbnailDirFor(meta.id)
        val frames = ThumbnailGenerator.generate(
            videoFile = video,
            momentOffsetMs = meta.momentOffsetMs,
            durationMs = meta.durationMs,
            outputDir = thumbDir,
        )

        val complete = meta.copy(thumbFrameCount = frames)
        writeSidecar(complete)
        refresh()
        Clip(complete, video, thumbDir)
    }

    private fun writeSidecar(meta: ClipMeta) {
        runCatching {
            File(clipsDir, "${meta.id}.json").writeText(json.encodeToString(ClipMeta.serializer(), meta))
        }.onFailure { Log.w(TAG, "cannot write sidecar for ${meta.id}", it) }
    }

    suspend fun delete(clip: Clip) = withContext(Dispatchers.IO) {
        clip.videoFile.delete()
        File(clipsDir, "${clip.id}.json").delete()
        clip.thumbnailDir.deleteRecursively()
        refresh()
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        clipsDir.listFiles()?.forEach { it.delete() }
        thumbsRoot.listFiles()?.forEach { it.deleteRecursively() }
        refresh()
    }

    /** Copies a clip into the shared Movies/KickerCam album so it shows up in Gallery/Photos. */
    suspend fun exportToMovies(clip: Clip): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = appContext.contentResolver
            val displayName = "KickerCam_${clip.meta.createdAtEpochMs}.mp4"

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/KickerCam")
                put(MediaStore.Video.Media.DATE_TAKEN, clip.meta.createdAtEpochMs)
                put(MediaStore.Video.Media.DURATION, clip.meta.durationMs)
                put(MediaStore.Video.Media.WIDTH, clip.meta.widthPx)
                put(MediaStore.Video.Media.HEIGHT, clip.meta.heightPx)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
                ?: error("MediaStore refused to create an entry")

            try {
                resolver.openOutputStream(uri)?.use { out ->
                    clip.videoFile.inputStream().use { input -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
                } ?: error("Could not open the destination for writing")

                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }

            writeSidecar(clip.meta.copy(exported = true, exportedUri = uri.toString()))
            refresh()
            uri
        }
    }

    /** Free space on the volume holding the clips, in bytes. */
    fun availableBytes(): Long = runCatching { clipsDir.usableSpace }.getOrDefault(0L)

    fun totalClipBytes(): Long = _clips.value.sumOf { it.sizeBytes }
}
