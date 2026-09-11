package com.gptvideo2anime.pipeline

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.util.Log

object YuvConverter {
    private const val TAG = "YuvConverter"
    
    // ✅ Pre-allocated buffers to avoid GC pressure
    private var cachedPixels: IntArray = IntArray(0)
    private var cachedYuv: ByteArray = ByteArray(0)
    
    fun imageToBitmap(image: Image): Bitmap {
        require(image.format == ImageFormat.YUV_420_888) {
            "Unsupported image format: ${image.format}"
        }

        val width = image.width
        val height = image.height
        val pixelCount = width * height

        // ✅ Reuse buffer instead of allocating every frame
        if (cachedPixels.size < pixelCount) {
            cachedPixels = IntArray(pixelCount)
            Log.d(TAG, "Allocated pixel buffer: $pixelCount ints")
        }

        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val pixels = cachedPixels

        // ✅ Optimized: Fixed-point math, reduced branches
        for (j in 0 until height) {
            val yOffset = j * yRowStride
            val uvOffset = (j shr 1) * uvRowStride
            
            for (i in 0 until width) {
                val Y = (yBuffer.get(yOffset + i).toInt() and 0xFF) - 16
                val uvIdx = uvOffset + (i shr 1) * uvPixelStride
                
                val U = (uBuffer.get(uvIdx).toInt() and 0xFF) - 128
                val V = (vBuffer.get(uvIdx).toInt() and 0xFF) - 128

                // ✅ Fixed-point conversion (faster than float on mobile CPU)
                var R = (298 * Y + 409 * V + 128) shr 8
                var G = (298 * Y - 100 * U - 208 * V + 128) shr 8
                var B = (298 * Y + 516 * U + 128) shr 8

                // ✅ Inline clamping (faster than coerceIn)
                if (R < 0) R = 0 else if (R > 255) R = 255
                if (G < 0) G = 0 else if (G > 255) G = 255
                if (B < 0) B = 0 else if (B > 255) B = 255

                pixels[j * width + i] = (0xFF shl 24) or (R shl 16) or (G shl 8) or B
            }
        }

        // ✅ Reuse bitmap if possible (would need bitmap pool for full optimization)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    fun bitmapToNv12(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        
        // Ensure even dimensions
        val alignedWidth = (width + 1) and 1.inv()
        val alignedHeight = (height + 1) and 1.inv()
        
        val frameSize = alignedWidth * alignedHeight
        val yuvSize = frameSize + (frameSize shr 1)

        // ✅ Reuse buffer
        if (cachedYuv.size < yuvSize) {
            cachedYuv = ByteArray(yuvSize)
            Log.d(TAG, "Allocated YUV buffer: $yuvSize bytes")
        }

        val yuv = cachedYuv
        val pixelCount = width * height
        val pixels = IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var yIndex = 0
        var uvIndex = frameSize

        // ✅ Optimized loop with reduced modulo operations
        for (j in 0 until height) {
            val isEvenRow = (j and 1) == 0
            
            for (i in 0 until width) {
                val pixel = pixels[j * width + i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // ✅ Fixed-point RGB to YUV
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = if (y < 16) 16.toByte() else if (y > 235) 235.toByte() else y.toByte()

                // ✅ Only compute UV for even rows/cols
                if (isEvenRow && (i and 1) == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                    yuv[uvIndex++] = if (u < 16) 16.toByte() else if (u > 240) 240.toByte() else u.toByte()
                    yuv[uvIndex++] = if (v < 16) 16.toByte() else if (v > 240) 240.toByte() else v.toByte()
                }
            }
        }

        return yuv
    }
}
