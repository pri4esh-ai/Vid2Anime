package com.gptvideo2anime.pipeline

import android.graphics.Bitmap
import android.util.Log
import com.gptvideo2anime.inference.OnnxAnimeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VideoPipeline(
    private val animeEngine: OnnxAnimeEngine,
    private val strength: Float,
    private val isEnhanceEnabled: Boolean,
    // ✅ Changed default from 3 to 4 for smoother parallel processing
    private val bufferSize: Int = 4
) {
    companion object {
        private const val TAG = "VideoPipeline"
        private const val FRAME_TIMEOUT_MS = 100L
    }

    private val decodeQueue = ArrayBlockingQueue<DecodedFrame>(bufferSize)
    private val aiQueue = ArrayBlockingQueue<ProcessedFrame>(bufferSize)
    private val isRunning = AtomicBoolean(true)

    private var lastFrameHash: Int = 0
    private var identicalFrameCount: Int = 0
    private val SKIP_THRESHOLD = 2

    data class DecodedFrame(
        val bitmap: Bitmap,
        val presentationTimeUs: Long,
        val frameIndex: Int
    )

    data class ProcessedFrame(
        val bitmap: Bitmap,
        val presentationTimeUs: Long,
        val frameIndex: Int,
        val wasSkipped: Boolean = false
    )

    fun startAiStage(scope: CoroutineScope): Job {
        return scope.launch(Dispatchers.Default) {
            Log.i(TAG, "AI stage started")

            while (isRunning.get() || decodeQueue.isNotEmpty()) {
                val frame = decodeQueue.poll(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: continue

                try {
                    val currentHash = computeFrameHash(frame.bitmap)
                    val shouldSkip = shouldSkipFrame(currentHash)

                    val processedBitmap = if (shouldSkip) {
                        Log.d(TAG, "Skipping identical frame ${frame.frameIndex}")
                        frame.bitmap
                    } else {
                        val animeBitmap = animeEngine.processFrame(frame.bitmap)
                        blendFrames(frame.bitmap, animeBitmap).also {
                            if (animeBitmap !== frame.bitmap && !animeBitmap.isRecycled) {
                                animeBitmap.recycle()
                            }
                        }
                    }

                    val result = ProcessedFrame(
                        bitmap = processedBitmap,
                        presentationTimeUs = frame.presentationTimeUs,
                        frameIndex = frame.frameIndex,
                        wasSkipped = shouldSkip
                    )

                    while (!aiQueue.offer(result, FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        if (!isRunning.get()) break
                    }

                    lastFrameHash = currentHash

                } catch (e: Exception) {
                    Log.e(TAG, "AI processing error on frame ${frame.frameIndex}", e)
                }
            }

            Log.i(TAG, "AI stage stopped")
        }
    }

    fun offerDecodedFrame(bitmap: Bitmap, pts: Long, frameIndex: Int): Boolean {
        if (!isRunning.get()) return false

        val frame = DecodedFrame(
            bitmap = bitmap,
            presentationTimeUs = pts,
            frameIndex = frameIndex
        )

        while (!decodeQueue.offer(frame, FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            if (!isRunning.get()) return false
        }

        return true
    }

    fun pollProcessedFrame(): ProcessedFrame? {
        return aiQueue.poll(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    // ✅ Added logging to track remaining frames when stopping
    fun stop() {
        isRunning.set(false)
        Log.i(TAG, "Pipeline stop signal sent. Remaining: decode=${decodeQueue.size()}, ai=${aiQueue.size()}")
    }

    // ✅ Optimized: Poll every 5ms instead of 50ms for faster end-of-stream drain
    fun awaitCompletion(timeoutMs: Long = 30_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val decodeEmpty = decodeQueue.isEmpty()
            val aiEmpty = aiQueue.isEmpty()

            if (decodeEmpty && aiEmpty) {
                Log.i(TAG, "Pipeline drained completely.")
                return
            }

            Thread.sleep(5)
        }

        Log.w(TAG, "Pipeline drain timed out. decode=${decodeQueue.size()}, ai=${aiQueue.size()}")
    }

    private fun computeFrameHash(bitmap: Bitmap): Int {
        var hash = 0
        val width = bitmap.width
        val height = bitmap.height
        val step = 16

        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                val pixel = bitmap.getPixel(x, y)
                hash = hash * 31 + pixel
            }
        }

        return hash
    }

    private fun shouldSkipFrame(currentHash: Int): Boolean {
        if (currentHash == lastFrameHash) {
            identicalFrameCount++
            return identicalFrameCount >= SKIP_THRESHOLD
        }

        identicalFrameCount = 0
        return false
    }

    private fun blendFrames(original: Bitmap, anime: Bitmap): Bitmap {
        if (strength >= 0.999f) return anime
        if (strength <= 0.001f) return original

        val width = original.width
        val height = original.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)

        canvas.drawBitmap(original, 0f, 0f, null)

        val paint = android.graphics.Paint().apply {
            alpha = (strength * 255).toInt().coerceIn(0, 255)
        }
        canvas.drawBitmap(anime, 0f, 0f, paint)

        return result
    }
}
