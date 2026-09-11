package com.gptvideo2anime.pipeline

import android.content.Context
import android.net.Uri
import android.util.Log
import com.gptvideo2anime.inference.OnnxAnimeEngine
import com.gptvideo2anime.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ProcessResult(
    val outputFile: File
)

class VideoProcessor(
    private val context: Context
) {
    companion object {
        private const val TAG = "VideoProcessor"
        private const val MAX_TEMP_FILES = 5
    }

    private val modelManager = ModelManager(context)
    private val codecEngine = MediaCodecVideoEngine(context)

    suspend fun processVideo(
        uri: Uri,
        strength: Int,
        isEnhanceEnabled: Boolean,
        onProgress: (Int, Int, String) -> Unit
    ): ProcessResult = withContext(Dispatchers.IO) {
        val videoInfo = codecEngine.inspect(uri)
        Log.i(TAG, "Processing video: ${videoInfo.width}x${videoInfo.height}, FPS: ${videoInfo.frameRate}")

        val modelPath = modelManager.animeModelPath()
            ?: throw IllegalStateException("AnimeGAN model missing from local storage.")

        val modelFile = File(modelPath)
        require(modelFile.exists() && modelFile.length() > 0) {
            "AnimeGAN model file is invalid or empty at $modelPath"
        }

        val outputDir = File(context.filesDir, "output").apply { mkdirs() }
        cleanOldOutputs(outputDir)

        val outputFile = File(
            outputDir,
            "anime_${System.currentTimeMillis()}.mp4"
        )

        val normalizedStrength = (strength.coerceIn(0, 100)) / 100f

        // ✅ Shifted to 256 for maximum mobile speed
        OnnxAnimeEngine(
            modelPath = modelPath,
            modelWidth = 256,
            modelHeight = 256
        ).use { engine ->
            codecEngine.processVideo(
                inputUri = uri,
                outputFile = outputFile,
                animeEngine = engine,
                strength = normalizedStrength,
                isEnhanceEnabled = isEnhanceEnabled,
                onProgress = { current, total, stage ->
                    onProgress(current, total, stage)
                }
            )
        }

        if (!outputFile.exists() || outputFile.length() == 0L) {
            throw IllegalStateException("Failed to generate processed output video.")
        }

        ProcessResult(outputFile)
    }

    private fun cleanOldOutputs(outputDir: File) {
        try {
            val files = outputDir.listFiles() ?: return
            if (files.size >= MAX_TEMP_FILES) {
                files.sortBy { it.lastModified() }
                val filesToDelete = files.size - MAX_TEMP_FILES + 1
                for (i in 0 until filesToDelete) {
                    files[i].delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean old output files: ${e.message}")
        }
    }
}
