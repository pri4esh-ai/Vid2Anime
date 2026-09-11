package com.gptvideo2anime.pipeline

import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import com.gptvideo2anime.inference.OnnxAnimeEngine
import kotlinx.coroutines.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pipelined video processor that runs decode, AI, and encode stages concurrently.
 * 
 * Architecture:
 *   Decoder Thread → [Frame Buffer] → AI Thread → [Result Buffer] → Encoder Thread
 */
class VideoPipeline(
    private val animeEngine: OnnxAnimeEngine,
    private val strength: Float,
    private val isEnhanceEnabled: Boolean,
    private val bufferSize: Int = 3  // Decode 3 frames ahead
) {
    companion object {
        private const val TAG = "VideoPipeline"
        private const val FRAME_TIMEOUT_MS = 100L
    }

    // Frame buffers between stages
    private val decodeQueue = ArrayBlockingQueue<DecodedFrame>(bufferSize)
    private val aiQueue = ArrayBlockingQueue<ProcessedFrame>(bufferSize)
    
    private val isRunning = AtomicBoolean(true)
    
    // Frame skip detection
    private var lastFrameHash: Int = 0
    private var identicalFrameCount: Int = 0
    private val SKIP_THRESHOLD = 2  // Skip after 2 identical frames

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

    /**
     * Start the AI processing stage.
     * This runs on a separate coroutine and processes frames from decodeQueue.
     */
    fun startAiStage(scope: CoroutineScope): Job {
        return scope.launch(Dispatchers.Default) {
            Log.i(TAG, "AI stage started")
            
            while (isRunning.get() || decodeQueue.isNotEmpty()) {
                val frame = decodeQueue.poll(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    ?: continue

                try {
                    // Frame skip detection
                    val currentHash = computeFrameHash(frame.bitmap)
                    val shouldSkip = shouldSkipFrame(currentHash)

                    val processedBitmap = if (shouldSkip) {
                        Log.d(TAG, "Skipping identical frame ${frame.frameIndex}")
                        frame.bitmap  // Reuse last result
                    } else {
                        // Run AI inference
                        val animeBitmap = animeEngine.processFrame(frame.bitmap)
                        
                        // Blend with original
                        blendFrames(frame.bitmap, animeBitmap)
                    }

                    val result = ProcessedFrame(
                        bitmap = processedBitmap,
                        presentationTimeUs = frame.presentationTimeUs,
                        frameIndex = frame.frameIndex,
                        wasSkipped = shouldSkip
                    )

                    // Put result in AI queue (blocks if queue is full)
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

    /**
     * Offer a decoded frame to the pipeline.
     * Called by the decoder thread.
     * Returns false if pipeline is shutting down.
     */
    fun offerDecodedFrame(bitmap: Bitmap, pts: Long, frameIndex: Int): Boolean {
        if (!isRunning.get()) return false
        
        val frame = DecodedFrame(
            bitmap = bitmap,
            presentationTimeUs = pts,
            frameIndex = frameIndex
        )
        
        // Block until space is available (backpressure)
        while (!decodeQueue.offer(frame, FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            if (!isRunning.get()) return false
        }
        
        return true
    }

    /**
     * Poll a processed frame from the AI stage.
     * Called by the encoder thread.
     */
    fun pollProcessedFrame(): ProcessedFrame? {
        return aiQueue.poll(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Signal the pipeline to stop after processing remaining frames.
     */
    fun stop() {
        isRunning.set(false)
    }

    /**
     * Wait for all queues to drain.
     */
    fun awaitCompletion(timeoutMs: Long = 30_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while ((decodeQueue.isNotEmpty() || aiQueue.isNotEmpty()) 
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
    }

    // --- Frame Skip Detection ---

    private fun computeFrameHash(bitmap: Bitmap): Int {
        // Simple perceptual hash: sample every 16th pixel
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

    // --- Blending ---

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

    fun getQueueSizes(): String {
        return "decode=${decodeQueue.size()}, ai=${aiQueue.size()}"
    }
}
