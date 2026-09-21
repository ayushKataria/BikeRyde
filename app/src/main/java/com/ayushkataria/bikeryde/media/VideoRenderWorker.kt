package com.ayushkataria.bikeryde.media

import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ayushkataria.bikeryde.ride.RideRepository
import java.io.File
import java.nio.ByteBuffer

/**
 * Renders a ride's animated route video in the background via [MediaCodec] + a Surface input
 * (drawing each frame with [RouteFrameDrawer] straight onto the encoder's Surface, no OpenGL
 * needed), muxes it to MP4, and moves it into app-private storage ([RenderFileStorage]) — a
 * preview only, not saved anywhere visible until the user taps Save. Reports progress via
 * [setProgress] and a persistent notification, per design doc §5.3/§6.
 *
 * Operates on [RideRenderData] — day count is opaque to this worker, so once multi-day tracking
 * exists, rendering a multi-day trip's video is the same code path as a single day's.
 */
class VideoRenderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val rideId = inputData.getLong(KEY_RIDE_ID, -1L)
        if (rideId == -1L) return Result.failure()

        RenderNotifications.ensureChannel(applicationContext)
        setForeground(createForegroundInfo(0))

        val stopNames = inputData.getStringArray(KEY_STOP_NAMES)
        val stopBackgrounds = inputData.getStringArray(KEY_STOP_BACKGROUNDS)
        val stopNameOverrides = stopNames?.mapIndexedNotNull { i, name ->
            if (name.isNullOrBlank()) null else i to name
        }?.toMap() ?: emptyMap()
        val stopBackgroundPaths = stopBackgrounds?.mapIndexedNotNull { i, path ->
            if (path.isNullOrBlank()) null else i to path
        }?.toMap() ?: emptyMap()
        val excludedStopIndices = inputData.getIntArray(KEY_EXCLUDED_STOPS)?.toSet() ?: emptySet()
        val dayBackgrounds = inputData.getStringArray(KEY_DAY_BACKGROUNDS)
        val dayCaptions = inputData.getStringArray(KEY_DAY_CAPTIONS)
        val dayBackgroundPaths = dayBackgrounds?.mapIndexedNotNull { i, path ->
            if (path.isNullOrBlank()) null else i to path
        }?.toMap() ?: emptyMap()
        val dayCaptionOverrides = dayCaptions?.mapIndexedNotNull { i, text ->
            if (text.isNullOrBlank()) null else i to text
        }?.toMap() ?: emptyMap()
        val dayLabelsEnabled = inputData.getBoolean(KEY_DAY_LABELS_ENABLED, false)

        val rideRepository = RideRepository(applicationContext)
        val renderRepository = RenderRepository(applicationContext)
        val data = RideRenderDataAssembler(rideRepository).assemble(
            rideId = rideId,
            stopNameOverrides = stopNameOverrides,
            stopBackgroundPaths = stopBackgroundPaths,
            excludedStopIndices = excludedStopIndices,
            dayBackgroundPaths = dayBackgroundPaths,
            dayCaptionOverrides = dayCaptionOverrides,
            dayLabelsEnabled = dayLabelsEnabled
        )
        if (data == null || data.allPoints.size < 2) return Result.failure()

        val recommendedDurationS = VideoDurationRecommender.recommend(data.allStops.size)
        val durationS = inputData.getInt(KEY_DURATION_SECONDS, recommendedDurationS)

        // This render replaces any previous preview for the same ride/type — delete it now
        // rather than leaving orphaned preview files behind in app storage.
        renderRepository.getLatest(rideId, RenderType.VIDEO)?.filePath?.let { path ->
            runCatching { File(path).delete() }
        }

        val renderId = renderRepository.insertQueued(rideId, RenderType.VIDEO, workId = id.toString())
        renderRepository.markProcessing(renderId)

        val backgroundPaths = listOfNotNull(data.coverImagePath) +
            data.allStops.mapNotNull { it.backgroundImagePath } +
            data.days.mapNotNull { it.backgroundImagePath }
        BackgroundImageCache.preload(backgroundPaths, WIDTH, HEIGHT)

        return try {
            val (outputFile, fps) = renderToFile(data, durationS) { percent ->
                setProgress(workDataOf(KEY_PROGRESS to percent))
                setForeground(createForegroundInfo(percent))
            }
            val output = saveToAppStorage(outputFile)
            renderRepository.markDone(renderId, output.filePath, "${WIDTH}x$HEIGHT", fps)
            RenderNotifications.showReady(applicationContext, rideId)
            Result.success(workDataOf(KEY_RESULT_URI to output.uri.toString()))
        } catch (e: Exception) {
            renderRepository.markFailed(renderId)
            Result.failure()
        } finally {
            BackgroundImageCache.clear()
        }
    }

    private suspend fun createForegroundInfo(percent: Int): ForegroundInfo {
        val notification = RenderNotifications.progressNotification(applicationContext, percent)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                RenderNotifications.PROGRESS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(RenderNotifications.PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    /**
     * Every part of the encoder configuration except the frame rate, which is only known after
     * probing the device ([chooseEncoder]). The probe and the real configure use this same format,
     * so the encoder that gets probed is one that can actually take the real configuration.
     */
    private fun baseVideoFormat(): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

    /** The H.264 encoder to render with ([codecName], null if none matched) and the frame rate it can sustain. */
    private class EncoderChoice(val codecName: String?, val fps: Int)

    /**
     * Picks the H.264 encoder and the highest frame rate it actually supports at [WIDTH]x[HEIGHT] —
     * 60fps target, falling back to 30fps — per the design doc's capability-detection open question,
     * rather than assuming hardware support and failing mid-render. The frame rate is checked against
     * the very encoder returned here, and the render must create that same one by name: asking for
     * "any H.264 encoder" instead could hand back a different codec than the one that was probed.
     */
    private fun chooseEncoder(): EncoderChoice {
        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val codecName = codecList.findEncoderForFormat(baseVideoFormat())
                ?: return EncoderChoice(null, FALLBACK_FPS)
            val codecInfo = codecList.codecInfos.first { it.name == codecName }
            val videoCaps = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
            val supports60 = videoCaps?.areSizeAndRateSupported(WIDTH, HEIGHT, TARGET_FPS.toDouble()) == true
            EncoderChoice(codecName, if (supports60) TARGET_FPS else FALLBACK_FPS)
        } catch (e: Exception) {
            EncoderChoice(null, FALLBACK_FPS)
        }
    }

    private suspend fun renderToFile(data: RideRenderData, durationS: Int, onProgress: suspend (Int) -> Unit): Pair<File, Int> {
        val encoderChoice = chooseEncoder()
        val fps = encoderChoice.fps
        // The animation itself sweeps progress 0..1 across animationFrameCount frames (the
        // duration the rider chose/customized); lingerFrameCount then holds on the completed
        // route — full path, all stops, final stats — at the ride's last stop, so the video
        // doesn't cut away the instant it finishes drawing. A rest day gets its own fixed-length
        // hold spliced in at the right point too (see VideoTimeline) — both are added on top of the
        // chosen animation duration, not carved out of it.
        val animationFrameCount = fps * durationS
        val lingerFrameCount = fps * LINGER_SECONDS
        val timeline = VideoTimeline.build(data, fps, animationFrameCount, lingerFrameCount)
        val frameCount = timeline.size
        // All the per-render setup (point projection, distance prefix sums, Paint objects) happens
        // once here, not on every frame — see RouteFrameDrawer's kdoc for why that matters for both
        // render time and playback smoothness.
        val preparedRender = RouteFrameDrawer.prepare(WIDTH, HEIGHT, data)

        val format = baseVideoFormat().apply { setInteger(MediaFormat.KEY_FRAME_RATE, fps) }
        val encoder = encoderChoice.codecName?.let { MediaCodec.createByCodecName(it) }
            ?: MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val outputFile = File(applicationContext.cacheDir, "ride_render_${System.currentTimeMillis()}.mp4")
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        fun drainEncoder(endOfStream: Boolean) {
            if (endOfStream) encoder.signalEndOfInputStream()
            while (true) {
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(outIndex)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size != 0 && muxerStarted && encodedData != null) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            // Draw and submit frames as fast as the device can manage — no artificial real-time
            // pacing. The encoder's automatic buffer timestamps come from wall-clock submission
            // time (there's no public API to set them explicitly on a lockCanvas-fed input
            // surface), so trying to pace submission to match real time only works when the device
            // can draw+encode a frame faster than its budget; the moment it can't (slow devices,
            // photo backgrounds, long routes), frames fall behind and both the total render time
            // and the video's own internal timing balloon. retimeToEvenPacing() below fixes the
            // *video's* timing deterministically afterward, so there's nothing to gain from trying
            // to hit real time during capture — only render time to lose.
            var lastReportedPercent = -1
            for (frame in timeline.indices) {
                val frameSpec = timeline[frame]
                val canvas = inputSurface.lockCanvas(null)
                try {
                    preparedRender.draw(canvas, frameSpec.progress, frameSpec.dayIndex, frameSpec.daySegmentProgress)
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }
                drainEncoder(false)

                // onProgress() rebuilds and re-posts the foreground notification and writes to
                // WorkManager's own progress store — each a cross-process call expensive enough
                // that firing it on every one of a few hundred frames (instead of only the ~100
                // times the displayed percent actually changes) was itself blowing out the render
                // time far more than the drawing work ever did.
                val percent = (frame + 1) * 100 / frameCount
                if (percent != lastReportedPercent) {
                    onProgress(percent)
                    lastReportedPercent = percent
                }
            }
            drainEncoder(true)
        } finally {
            encoder.stop()
            encoder.release()
            inputSurface.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }

        val retimedFile = retimeToEvenPacing(outputFile, fps)
        return retimedFile to fps
    }

    /**
     * Rewrites the video track's presentation timestamps to be evenly spaced across exactly
     * `sampleCount / fps` seconds, regardless of how long capture actually took — a fast remux
     * (reads and rewrites already-encoded samples via [MediaExtractor]/[MediaMuxer], no
     * re-encoding) that makes the output duration and playback smoothness independent of device
     * speed or how expensive a given ride's content was to draw.
     */
    private fun retimeToEvenPacing(source: File, fps: Int): File {
        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)

        var trackIndex = -1
        var trackFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                trackIndex = i
                trackFormat = format
                break
            }
        }
        if (trackIndex == -1 || trackFormat == null) {
            extractor.release()
            return source
        }
        extractor.selectTrack(trackIndex)

        val retimedFile = File(source.parentFile, "retimed_${source.name}")
        val muxer = MediaMuxer(retimedFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outTrackIndex = muxer.addTrack(trackFormat)
        muxer.start()

        val bufferSize = if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            2 * 1024 * 1024
        }.coerceAtLeast(1024 * 1024)
        val buffer = ByteBuffer.allocate(bufferSize)
        val bufferInfo = MediaCodec.BufferInfo()
        val frameDurationUs = 1_000_000L / fps
        var frameIndex = 0L

        while (true) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = frameIndex * frameDurationUs
            bufferInfo.flags = extractor.sampleFlags
            muxer.writeSampleData(outTrackIndex, buffer, bufferInfo)
            extractor.advance()
            frameIndex++
        }

        muxer.stop()
        muxer.release()
        extractor.release()
        source.delete()
        return retimedFile
    }

    /** Moves the temp working file (written to [Context.getCacheDir] during encoding) into
     * app-private, persistent storage — the actual preview location. */
    private fun saveToAppStorage(file: File): RenderOutput {
        val dest = RenderFileStorage.newVideoFile(applicationContext)
        if (!file.renameTo(dest)) {
            file.copyTo(dest, overwrite = true)
            file.delete()
        }
        return RenderOutput(RenderFileStorage.uriFor(applicationContext, dest), dest.absolutePath)
    }

    companion object {
        const val KEY_RIDE_ID = "rideId"
        const val KEY_PROGRESS = "progress"
        const val KEY_RESULT_URI = "resultUri"
        /** String array aligned by index to [RideRepository.getEvents]'s order — one custom stop name per entry, empty for none. */
        const val KEY_STOP_NAMES = "stopNames"
        /** String array aligned the same way — one background photo path per stop, empty for none. */
        const val KEY_STOP_BACKGROUNDS = "stopBackgrounds"
        /** Merged-stop indices (same keying) the rider unchecked on the customize screen — dropped entirely. */
        const val KEY_EXCLUDED_STOPS = "excludedStops"
        /** Animation length in seconds — defaults to [VideoDurationRecommender.recommend] if omitted. */
        const val KEY_DURATION_SECONDS = "durationSeconds"
        /** String array keyed by [com.ayushkataria.bikeryde.ride.RideDay.dayIndex] — one background
         * photo path per day, multi-day video only. */
        const val KEY_DAY_BACKGROUNDS = "dayBackgrounds"
        /** String array, same keying — one rider-edited caption per day. */
        const val KEY_DAY_CAPTIONS = "dayCaptions"
        /** The customize screen's "Add day labels" checkbox. */
        const val KEY_DAY_LABELS_ENABLED = "dayLabelsEnabled"

        const val WIDTH = 1080
        const val HEIGHT = 1920
        private const val TARGET_FPS = 60
        private const val FALLBACK_FPS = 30
        private const val BIT_RATE = 8_000_000
        /** Extra hold on the completed route (final stop, full stats) at the end of the video, on
         * top of the rider's chosen animation length. */
        private const val LINGER_SECONDS = 3

        fun uniqueWorkName(rideId: Long) = "video_render_$rideId"
    }
}
