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
    private val bufferSize: Int = 4
) {
    companion object {
        private const val TAG = "VideoPipeline"
        private const val FRAME_TIMEOUT_MS = 10L
    }

    private val decodeQueue = ArrayBlockingQueue<DecodedFrame>(bufferSize)
    private val aiQueue = ArrayBlockingQueue<ProcessedFrame>(bufferSize)
    private val isRunning = AtomicBoolean(true)

    private var lastFrameHash: Int = 0
    private var identicalFrameCount: Int = 0
    private val SKIP_THRESHOLD = 2

    private var hashPixels: IntArray = IntArray(0)

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

                    val processedBitmap: Bitmap
                    if (shouldSkip) {
                        Log.d(TAG, "Skipping identical frame ${frame.frameIndex}")
                        processedBitmap = frame.bitmap
                    } else {
                        val animeBitmap = animeEngine.processFrame(frame.bitmap)
                        processedBitmap = blendFrames(frame.bitmap, animeBitmap)
                        
                        // Recycle animeBitmap ONLY if it wasn't passed as the final result
                        if (animeBitmap !== processedBitmap && animeBitmap !== frame.bitmap && !animeBitmap.isRecycled) {
                            animeBitmap.recycle()
                        }
                    }

                    // Recycle original decoded frame ONLY if it wasn't passed as the final result
                    if (processedBitmap !== frame.bitmap && !frame.bitmap.isRecycled) {
                        frame.bitmap.recycle()
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
                    if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
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

    fun stop() {
        isRunning.set(false)
        Log.i(TAG, "Pipeline stop signal sent. Remaining: decode=${decodeQueue.size}, ai=${aiQueue.size}")
    }

    fun awaitCompletion(timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            if (decodeQueue.isEmpty() && aiQueue.isEmpty()) {
                Log.i(TAG, "Pipeline drained completely.")
                return
            }
            Thread.sleep(2)
        }

        Log.w(TAG, "Pipeline drain timed out. decode=${decodeQueue.size}, ai=${aiQueue.size}")
    }

    private fun computeFrameHash(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        val step = 16
        val sampleWidth = (width + step - 1) / step
        val sampleHeight = (height + step - 1) / step
        val sampleCount = sampleWidth * sampleHeight

        if (hashPixels.size < sampleCount) {
            hashPixels = IntArray(sampleCount)
        }

        bitmap.getPixels(hashPixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)

        var hash = 0
        for (i in 0 until sampleCount) {
            hash = hash * 31 + hashPixels[i]
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

        val paint = android.graphics.Paint()
        paint.alpha = (strength * 255).toInt().coerceIn(0, 255)
        canvas.drawBitmap(anime, 0f, 0f, paint)

        return result
    }
}
