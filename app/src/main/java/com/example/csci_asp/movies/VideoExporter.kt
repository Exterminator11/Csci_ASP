package com.example.csci_asp.movies

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class VideoExporter(
    private val context: Context
) {
    private val videoMimeType = "video/avc"
    private val videoWidth = 1920
    private val videoHeight = 1080
    private val frameRate = 30
    private val bitrate = 8_000_000
    private val iFrameInterval = 1
    private val photoDurationSeconds = 2
    private val framesPerPhoto = frameRate * photoDurationSeconds

    suspend fun createVideo(
        photoUris: List<Uri>,
        audioUri: Uri?,
        outputFile: File
    ) = withContext(Dispatchers.IO) {
        require(photoUris.isNotEmpty()) { "Photo list cannot be empty" }

        // Create video from images
        val tempVideo = File(context.cacheDir, "video_temp_${System.currentTimeMillis()}.mp4")
        try {
            createVideoFromImages(photoUris, tempVideo)

            // If audio is provided, combine with video and write to outputFile (overwrite)
            if (audioUri != null) {
                combineVideoAndAudio(tempVideo, audioUri, outputFile)
            } else {
                // Move tempVideo to outputFile
                if (outputFile.exists()) outputFile.delete()
                tempVideo.copyTo(outputFile)
            }
        } finally {
            try { tempVideo.delete() } catch (_: Exception) {}
        }
    }

    private fun createVideoFromImages(photoUris: List<Uri>, outputFile: File) {
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var encoder: MediaCodec? = null
        var surface: Surface? = null
        var muxerStarted = false
        var videoTrackIndex = -1

        try {
            val format = MediaFormat.createVideoFormat(videoMimeType, videoWidth, videoHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            }

            encoder = MediaCodec.createEncoderByType(videoMimeType)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = encoder.createInputSurface()
            encoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var globalFrameIndex = 0

            // Process each photo sequentially
            for (photoIndex in photoUris.indices) {
                val bitmap = decodeBitmap(photoUris[photoIndex])
                if (bitmap == null) {
                    // Skip this photo, but advance frame index to maintain timing
                    globalFrameIndex += framesPerPhoto
                    continue
                }

                // Draw this photo for all its frames (2 seconds = 60 frames at 30fps)
                for (frameInPhoto in 0 until framesPerPhoto) {
                    // Draw the bitmap to encoder surface
                    drawBitmapToSurfaceSafe(bitmap, surface!!)

                    // Calculate presentation time for this frame (microseconds)
                    val presentationTimeUs = (globalFrameIndex * 1_000_000L) / frameRate

                    // Drain encoder output for this frame
                    drainEncoderOutput(
                        encoder,
                        bufferInfo,
                        muxer,
                        { !muxerStarted },
                        { newFormat ->
                            videoTrackIndex = muxer.addTrack(newFormat)
                            muxer.start()
                            muxerStarted = true
                        },
                        { encodedBuf ->
                            if (encodedBuf != null && bufferInfo.size > 0 && muxerStarted) {
                                bufferInfo.presentationTimeUs = presentationTimeUs
                                encodedBuf.position(bufferInfo.offset)
                                encodedBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(videoTrackIndex, encodedBuf, bufferInfo)
                            }
                        }
                    )

                    globalFrameIndex++
                }

                // Recycle bitmap after all frames for this photo are processed
                try {
                    if (!bitmap.isRecycled) bitmap.recycle()
                } catch (_: Exception) {}
            }

            // Signal end of stream and drain remaining encoder output
            try {
                encoder.signalEndOfInputStream()
            } catch (_: Exception) {}

            drainEncoderOutput(
                encoder,
                bufferInfo,
                muxer,
                { !muxerStarted },
                { newFormat ->
                    videoTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                },
                { encodedBuf ->
                    if (encodedBuf != null && bufferInfo.size > 0 && muxerStarted) {
                        encodedBuf.position(bufferInfo.offset)
                        encodedBuf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(videoTrackIndex, encodedBuf, bufferInfo)
                    }
                },
                timeoutUs = 10_000
            )
        } finally {
            try { surface?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try {
                if (muxerStarted) muxer.stop()
            } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    /**
     * Helper function to drain encoder output buffers.
     * Handles format changes, writes encoded data to muxer, and checks for end of stream.
     */
    private fun drainEncoderOutput(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        shouldStartMuxer: () -> Boolean,
        onFormatChanged: (MediaFormat) -> Unit,
        onOutputBuffer: (ByteBuffer?) -> Unit,
        timeoutUs: Long = 0
    ) {
        while (true) {
            val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    break
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (shouldStartMuxer()) {
                        val newFormat = encoder.outputFormat
                        onFormatChanged(newFormat)
                    }
                }
                outputBufferIndex >= 0 -> {
                    val encodedBuf = encoder.getOutputBuffer(outputBufferIndex)
                    onOutputBuffer(encodedBuf)
                    encoder.releaseOutputBuffer(outputBufferIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
                else -> break
            }
        }
    }

    /**
     * Safe canvas draw that uses lockCanvas(null) (or fallback) and unlocks safely.
     * lockHardwareCanvas() can throw on some devices — we use lockCanvas(null) unless you
     * specifically require hardware canvas.
     */
    private fun drawBitmapToSurfaceSafe(bitmap: Bitmap, surface: Surface) {
        val canvas = try {
            surface.lockCanvas(null)
        } catch (e: Exception) {
            // If locking fails, abort drawing (encoder will produce no frame for this iteration)
            return
        }
        try {
            canvas.drawColor(android.graphics.Color.BLACK)
            val scale = minOf(
                canvas.width.toFloat() / bitmap.width,
                canvas.height.toFloat() / bitmap.height
            )
            val scaledWidth = bitmap.width * scale
            val scaledHeight = bitmap.height * scale
            val left = (canvas.width - scaledWidth) / 2f
            val top = (canvas.height - scaledHeight) / 2f
            canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + scaledWidth, top + scaledHeight), null)
        } finally {
            try { surface.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
        }
    }

    /**
     * Combine prepared video file and audio Uri into outputFile.
     * This function is robust to missing audio or audio that cannot be parsed.
     */
    private fun combineVideoAndAudio(videoFile: File, audioUri: Uri, outputFile: File) {
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var muxerStarted = false

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var audioExtractorHasData = false
        var audioDurationUs = 0L

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            val vTrack = findTrack(videoExtractor, "video/")
            if (vTrack < 0) {
                // no video track — bail out
                return
            }
            videoExtractor.selectTrack(vTrack)
            val videoFormat = videoExtractor.getTrackFormat(vTrack)
            videoTrackIndex = muxer.addTrack(videoFormat)
            
            // Get video duration to limit audio
            val videoDurationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                videoFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            // Attempt to prepare audio extractor
            // For raw resources (android.resource://), we can use setDataSource directly
            // For content:// URIs, we copy to temp file first
            var tempAudioFile: File? = null
            try {
                val uriString = audioUri.toString()
                if (uriString.startsWith("android.resource://")) {
                    context.contentResolver.openAssetFileDescriptor(audioUri, "r")?.fileDescriptor?.let { fd ->
                        audioExtractor.setDataSource(fd)
                    }
                } else {
                    // Content URI - copy to temp file
                    context.contentResolver.openInputStream(audioUri)?.use { input ->
                        tempAudioFile = File(context.cacheDir, "tmp_audio_${System.currentTimeMillis()}.tmp")
                        FileOutputStream(tempAudioFile!!).use { out ->
                            input.copyTo(out)
                        }
                    }
                    if (tempAudioFile != null && tempAudioFile!!.exists()) {
                        audioExtractor.setDataSource(tempAudioFile!!.absolutePath)
                    } else {
                        throw IllegalStateException("Could not create temp audio file")
                    }
                }

                val aTrack = findTrack(audioExtractor, "audio/")
                if (aTrack >= 0) {
                    audioExtractor.selectTrack(aTrack)
                    val audioFormat = audioExtractor.getTrackFormat(aTrack)
                    audioTrackIndex = muxer.addTrack(audioFormat)
                    audioExtractorHasData = true
                    audioDurationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                        audioFormat.getLong(MediaFormat.KEY_DURATION)
                    } else {
                        0L
                    }
                }
            } catch (e: Exception) {
                // ignore audio preparation errors and proceed without audio
                audioExtractorHasData = false
                audioTrackIndex = -1
            } finally {
                try { tempAudioFile?.delete() } catch (_: Exception) {}
            }

            // Start muxer only after adding tracks
            muxer.start()
            muxerStarted = true

            val bufferInfo = MediaCodec.BufferInfo()
            val buffer = ByteBuffer.allocate(1024 * 1024)

            var videoDone = false
            var audioDone = !audioExtractorHasData
            var audioTimeOffset = 0L

            while (!videoDone || !audioDone) {
                if (!videoDone) {
                    val sampleSize = videoExtractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        videoDone = true
                        // Stop audio when video ends
                        audioDone = true
                    } else {
                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize

                        // Translate extractor flags to muxer/safe flags
                        bufferInfo.flags = translateExtractorFlags(videoExtractor.sampleFlags)

                        bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                        muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                        videoExtractor.advance()
                    }
                }

                if (!audioDone && audioExtractorHasData && !videoDone) {
                    val sampleSize = audioExtractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        // Only loop audio if video is still playing
                        if (!videoDone && audioDurationUs > 0) {
                            audioExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            audioTimeOffset += audioDurationUs
                        } else {
                            audioDone = true
                        }
                    } else {
                        val sampleTime = audioExtractor.sampleTime
                        val totalAudioTime = if (sampleTime >= 0) sampleTime + audioTimeOffset else audioTimeOffset
                        
                        // Stop audio if it exceeds video duration
                        if (videoDurationUs > 0 && totalAudioTime >= videoDurationUs) {
                            audioDone = true
                        } else {
                            bufferInfo.offset = 0
                            bufferInfo.size = sampleSize

                            bufferInfo.flags = translateExtractorFlags(audioExtractor.sampleFlags)
                            bufferInfo.presentationTimeUs = totalAudioTime
                            muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                            audioExtractor.advance()
                        }
                    }
                }
            }
        } finally {
            try { videoExtractor.release() } catch (_: Exception) {}
            try { audioExtractor.release() } catch (_: Exception) {}
            try {
                if (muxerStarted) muxer.stop()
            } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    /**
     * Translate MediaExtractor sampleFlags to flags safe for BufferInfo used with MediaMuxer.
     *
     * Important notes:
     * - MediaExtractor sample flags (SAMPLE_FLAG_SYNC, SAMPLE_FLAG_ENCRYPTED, SAMPLE_FLAG_PARTIAL_FRAME)
     *   are not directly valid for BufferInfo.flags; muxer expects codec buffer flags (KEY_FRAME, PARTIAL_FRAME, etc).
     * - We ignore ENCRYPTED flag here because MediaCodec/MediaMuxer do not expose a direct BUFFER_FLAG_ENCRYPTED constant
     *   on all API levels; encrypted content usually needs a different handling path (MediaDrm/MediaCodec with secure decoders).
     */
    private fun translateExtractorFlags(extractorFlags: Int): Int {
        var out = 0
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            // Mark as key frame — muxer accepts KEY_FRAME
            out = out or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            out = out or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        // Do not map SAMPLE_FLAG_ENCRYPTED here — encrypted content requires a secure path.
        return out
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        return try {
            val resolver: ContentResolver = context.contentResolver
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            options.inSampleSize = calculateInSampleSize(options, videoWidth, videoHeight)
            options.inJustDecodeBounds = false

            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height, width) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}

//package com.example.csci_asp.movies
//
//import android.content.ContentResolver
//import android.content.Context
//import android.graphics.Bitmap
//import android.graphics.BitmapFactory
//import android.media.MediaCodec
//import android.media.MediaCodecInfo
//import android.media.MediaExtractor
//import android.media.MediaFormat
//import android.media.MediaMuxer
//import android.net.Uri
//import android.view.Surface
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.withContext
//import java.io.File
//import java.io.FileOutputStream
//import java.nio.ByteBuffer
//
//class VideoExporter(
//    private val context: Context
//) {
//    private val videoMimeType = "video/avc"
//    private val videoWidth = 1920
//    private val videoHeight = 1080
//    private val frameRate = 30
//    private val bitrate = 8_000_000
//    private val iFrameInterval = 1
//    private val photoDurationSeconds = 2
//    private val framesPerPhoto = frameRate * photoDurationSeconds
//
//    suspend fun createVideo(
//        photoUris: List<Uri>,
//        audioUri: Uri?,
//        outputFile: File
//    ) = withContext(Dispatchers.IO) {
//        require(photoUris.isNotEmpty()) { "Photo list cannot be empty" }
//
//        android.util.Log.d("VideoExporter", "Starting video creation with ${photoUris.size} photos")
//
//        // Create video from images
//        val tempVideo = File(context.cacheDir, "video_temp_${System.currentTimeMillis()}.mp4")
//        try {
//            android.util.Log.d("VideoExporter", "Creating video from images...")
//            createVideoFromImages(photoUris, tempVideo)
//
//            if (!tempVideo.exists() || tempVideo.length() == 0L) {
//                throw IllegalStateException("Video file was not created or is empty")
//            }
//
//            android.util.Log.d("VideoExporter", "Video created successfully, size: ${tempVideo.length()} bytes")
//
//            // If audio is provided, combine with video and write to outputFile (overwrite)
//            if (audioUri != null) {
//                android.util.Log.d("VideoExporter", "Combining video with audio...")
//                combineVideoAndAudio(tempVideo, audioUri, outputFile)
//            } else {
//                android.util.Log.d("VideoExporter", "No audio, copying video file...")
//                // Move tempVideo to outputFile
//                if (outputFile.exists()) outputFile.delete()
//                tempVideo.copyTo(outputFile, overwrite = true)
//            }
//
//            if (!outputFile.exists() || outputFile.length() == 0L) {
//                throw IllegalStateException("Output file was not created or is empty")
//            }
//
//            android.util.Log.d("VideoExporter", "Movie creation complete!")
//        } catch (e: Exception) {
//            android.util.Log.e("VideoExporter", "Error creating video", e)
//            throw e
//        } finally {
//            try { tempVideo.delete() } catch (_: Exception) {}
//        }
//    }
//
//    private fun createVideoFromImages(photoUris: List<Uri>, outputFile: File) {
//        android.util.Log.d("VideoExporter", "createVideoFromImages started")
//
//        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
//        var encoder: MediaCodec? = null
//        var surface: Surface? = null
//        var muxerStarted = false
//        var videoTrackIndex = -1
//
//        try {
//            val format = MediaFormat.createVideoFormat(videoMimeType, videoWidth, videoHeight).apply {
//                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
//                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
//                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
//                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
//            }
//
//            encoder = MediaCodec.createEncoderByType(videoMimeType)
//            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
//            surface = encoder.createInputSurface()
//            encoder.start()
//
//            android.util.Log.d("VideoExporter", "Encoder started successfully")
//
//            val bufferInfo = MediaCodec.BufferInfo()
//            var frameIndex = 0
//            val totalFrames = photoUris.size * framesPerPhoto
//
//            android.util.Log.d("VideoExporter", "Total frames to encode: $totalFrames")
//
//            // Loop through frames
//            for (photoIndex in photoUris.indices) {
//                val bitmap = decodeBitmap(photoUris[photoIndex])
//                if (bitmap == null) {
//                    android.util.Log.w("VideoExporter", "Failed to decode photo at index $photoIndex")
//                    continue
//                }
//
//                android.util.Log.d("VideoExporter", "Processing photo $photoIndex/${photoUris.size}")
//
//                for (i in 0 until framesPerPhoto) {
//                    drawBitmapToSurfaceSafe(bitmap, surface!!)
//
//                    // Drain encoder output after drawing
//                    var outputAvailable = true
//                    while (outputAvailable) {
//                        val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
//                        when {
//                            outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
//                                outputAvailable = false
//                            }
//                            outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
//                                if (!muxerStarted) {
//                                    val newFormat = encoder.outputFormat
//                                    videoTrackIndex = muxer.addTrack(newFormat)
//                                    muxer.start()
//                                    muxerStarted = true
//                                    android.util.Log.d("VideoExporter", "Muxer started with track index: $videoTrackIndex")
//                                }
//                            }
//                            outputBufferIndex >= 0 -> {
//                                val encodedBuf = encoder.getOutputBuffer(outputBufferIndex)
//                                if (encodedBuf != null && bufferInfo.size > 0 && muxerStarted) {
//                                    bufferInfo.presentationTimeUs = (frameIndex * 1_000_000L) / frameRate
//                                    encodedBuf.position(bufferInfo.offset)
//                                    encodedBuf.limit(bufferInfo.offset + bufferInfo.size)
//                                    muxer.writeSampleData(videoTrackIndex, encodedBuf, bufferInfo)
//                                }
//                                encoder.releaseOutputBuffer(outputBufferIndex, false)
//                            }
//                        }
//                    }
//                    frameIndex++
//                }
//
//                try { if (!bitmap.isRecycled) bitmap.recycle() } catch (_: Exception) {}
//            }
//
//            android.util.Log.d("VideoExporter", "All frames drawn, signaling end of stream")
//
//            // Signal end of stream
//            try {
//                encoder.signalEndOfInputStream()
//            } catch (_: Exception) {
//                android.util.Log.e("VideoExporter", "Error signaling end of input stream")
//            }
//
//            // Drain remaining output
//            var sawEOS = false
//            while (!sawEOS) {
//                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
//                when {
//                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
//                        // Continue waiting
//                    }
//                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
//                        if (!muxerStarted) {
//                            val newFormat = encoder.outputFormat
//                            videoTrackIndex = muxer.addTrack(newFormat)
//                            muxer.start()
//                            muxerStarted = true
//                            android.util.Log.d("VideoExporter", "Muxer started (late) with track index: $videoTrackIndex")
//                        }
//                    }
//                    outputBufferIndex >= 0 -> {
//                        val encodedBuf = encoder.getOutputBuffer(outputBufferIndex)
//                        if (encodedBuf != null && bufferInfo.size > 0 && muxerStarted) {
//                            encodedBuf.position(bufferInfo.offset)
//                            encodedBuf.limit(bufferInfo.offset + bufferInfo.size)
//                            muxer.writeSampleData(videoTrackIndex, encodedBuf, bufferInfo)
//                        }
//                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
//                            sawEOS = true
//                            android.util.Log.d("VideoExporter", "End of stream reached")
//                        }
//                        encoder.releaseOutputBuffer(outputBufferIndex, false)
//                    }
//                }
//            }
//
//            android.util.Log.d("VideoExporter", "Video encoding completed")
//        } catch (e: Exception) {
//            android.util.Log.e("VideoExporter", "Error in createVideoFromImages", e)
//            throw e
//        } finally {
//            try { surface?.release() } catch (_: Exception) {}
//            try { encoder?.stop() } catch (_: Exception) {}
//            try { encoder?.release() } catch (_: Exception) {}
//            try {
//                if (muxerStarted) muxer.stop()
//            } catch (_: Exception) {}
//            try { muxer.release() } catch (_: Exception) {}
//        }
//    }
//
//    private fun drawBitmapToSurfaceSafe(bitmap: Bitmap, surface: Surface) {
//        val canvas = try {
//            surface.lockCanvas(null)
//        } catch (e: Exception) {
//            return
//        }
//        try {
//            canvas.drawColor(android.graphics.Color.BLACK)
//            val scale = minOf(
//                canvas.width.toFloat() / bitmap.width,
//                canvas.height.toFloat() / bitmap.height
//            )
//            val scaledWidth = bitmap.width * scale
//            val scaledHeight = bitmap.height * scale
//            val left = (canvas.width - scaledWidth) / 2f
//            val top = (canvas.height - scaledHeight) / 2f
//            canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + scaledWidth, top + scaledHeight), null)
//        } finally {
//            try { surface.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
//        }
//    }
//
//    private fun combineVideoAndAudio(videoFile: File, audioUri: Uri, outputFile: File) {
//        if (outputFile.exists()) outputFile.delete()
//
//        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
//        var videoTrackIndex = -1
//        var audioTrackIndex = -1
//        var muxerStarted = false
//
//        val videoExtractor = MediaExtractor()
//        val audioExtractor = MediaExtractor()
//        var audioExtractorHasData = false
//        var tempAudioFile: File? = null
//
//        try {
//            // Setup video extractor
//            videoExtractor.setDataSource(videoFile.absolutePath)
//            val vTrack = findTrack(videoExtractor, "video/")
//            if (vTrack < 0) {
//                throw IllegalStateException("No video track found")
//            }
//            videoExtractor.selectTrack(vTrack)
//            val videoFormat = videoExtractor.getTrackFormat(vTrack)
//            videoTrackIndex = muxer.addTrack(videoFormat)
//
//            // Get video duration
//            val videoDurationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
//                videoFormat.getLong(MediaFormat.KEY_DURATION)
//            } else {
//                0L
//            }
//
//            // Setup audio extractor
//            try {
//                val uriString = audioUri.toString()
//                if (uriString.startsWith("android.resource://")) {
//                    context.contentResolver.openAssetFileDescriptor(audioUri, "r")?.use { afd ->
//                        audioExtractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
//                    }
//                } else {
//                    // Content URI - copy to temp file
//                    tempAudioFile = File(context.cacheDir, "tmp_audio_${System.currentTimeMillis()}.tmp")
//                    context.contentResolver.openInputStream(audioUri)?.use { input ->
//                        FileOutputStream(tempAudioFile!!).use { output ->
//                            input.copyTo(output)
//                        }
//                    }
//                    audioExtractor.setDataSource(tempAudioFile!!.absolutePath)
//                }
//
//                val aTrack = findTrack(audioExtractor, "audio/")
//                if (aTrack >= 0) {
//                    audioExtractor.selectTrack(aTrack)
//                    val audioFormat = audioExtractor.getTrackFormat(aTrack)
//                    audioTrackIndex = muxer.addTrack(audioFormat)
//                    audioExtractorHasData = true
//                }
//            } catch (e: Exception) {
//                // Audio setup failed, continue with video only
//                audioExtractorHasData = false
//            }
//
//            // Start muxer
//            muxer.start()
//            muxerStarted = true
//
//            val bufferInfo = MediaCodec.BufferInfo()
//            val buffer = ByteBuffer.allocate(1024 * 1024)
//
//            // Write video track
//            var videoDone = false
//            while (!videoDone) {
//                val sampleSize = videoExtractor.readSampleData(buffer, 0)
//                if (sampleSize < 0) {
//                    videoDone = true
//                } else {
//                    bufferInfo.offset = 0
//                    bufferInfo.size = sampleSize
//                    bufferInfo.flags = translateExtractorFlags(videoExtractor.sampleFlags)
//                    bufferInfo.presentationTimeUs = videoExtractor.sampleTime
//
//                    muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
//                    videoExtractor.advance()
//                }
//            }
//
//            // Write audio track (trim to video duration)
//            if (audioExtractorHasData && videoDurationUs > 0) {
//                var audioDone = false
//                while (!audioDone) {
//                    val sampleSize = audioExtractor.readSampleData(buffer, 0)
//                    if (sampleSize < 0) {
//                        audioDone = true
//                    } else {
//                        val sampleTime = audioExtractor.sampleTime
//
//                        // Stop audio when it exceeds video duration
//                        if (sampleTime >= videoDurationUs) {
//                            audioDone = true
//                            continue
//                        }
//
//                        bufferInfo.offset = 0
//                        bufferInfo.size = sampleSize
//                        bufferInfo.flags = translateExtractorFlags(audioExtractor.sampleFlags)
//                        bufferInfo.presentationTimeUs = sampleTime
//
//                        muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
//                        audioExtractor.advance()
//                    }
//                }
//            }
//        } finally {
//            try { videoExtractor.release() } catch (_: Exception) {}
//            try { audioExtractor.release() } catch (_: Exception) {}
//            try {
//                if (muxerStarted) muxer.stop()
//            } catch (_: Exception) {}
//            try { muxer.release() } catch (_: Exception) {}
//            try { tempAudioFile?.delete() } catch (_: Exception) {}
//        }
//    }
//
//    private fun translateExtractorFlags(extractorFlags: Int): Int {
//        var out = 0
//        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
//            out = out or MediaCodec.BUFFER_FLAG_KEY_FRAME
//        }
//        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
//            out = out or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
//        }
//        return out
//    }
//
//    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
//        for (i in 0 until extractor.trackCount) {
//            val format = extractor.getTrackFormat(i)
//            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
//            if (mime.startsWith(mimePrefix)) return i
//        }
//        return -1
//    }
//
//    private fun decodeBitmap(uri: Uri): Bitmap? {
//        return try {
//            val resolver: ContentResolver = context.contentResolver
//            val options = BitmapFactory.Options().apply {
//                inJustDecodeBounds = true
//            }
//            resolver.openInputStream(uri)?.use { stream ->
//                BitmapFactory.decodeStream(stream, null, options)
//            }
//
//            options.inSampleSize = calculateInSampleSize(options, videoWidth, videoHeight)
//            options.inJustDecodeBounds = false
//
//            resolver.openInputStream(uri)?.use { stream ->
//                BitmapFactory.decodeStream(stream, null, options)
//            }
//        } catch (e: Exception) {
//            null
//        }
//    }
//
//    private fun calculateInSampleSize(
//        options: BitmapFactory.Options,
//        reqWidth: Int,
//        reqHeight: Int
//    ): Int {
//        val (height, width) = options.run { outHeight to outWidth }
//        var inSampleSize = 1
//
//        if (height > reqHeight || width > reqWidth) {
//            var halfHeight = height / 2
//            var halfWidth = width / 2
//
//            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
//                inSampleSize *= 2
//            }
//        }
//        return inSampleSize
//    }
//}