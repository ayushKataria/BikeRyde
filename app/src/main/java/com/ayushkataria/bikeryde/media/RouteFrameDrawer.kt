package com.ayushkataria.bikeryde.media

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.location.Location
import com.ayushkataria.bikeryde.ride.RideEventAction
import com.ayushkataria.bikeryde.ride.RidePoint
import com.ayushkataria.bikeryde.ride.RouteProjection
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min

/**
 * Draws a ride's route + stats overlay. [prepare] does all the *per-render* work once — geometry,
 * point projection, distance prefix sums, and every [Paint] — and returns a [PreparedRender] whose
 * [PreparedRender.draw] is called once per output frame (a static image is just one call at
 * `progress = 1f`; a video calls it once per frame, sweeping 0..1).
 *
 * This split exists because [PreparedRender.draw] used to redo all of that setup — including a full
 * scan of every GPS point for distance (via the relatively expensive [Location.distanceBetween])
 * and re-projecting every visible point — on *every single frame*. At a few hundred frames per
 * video, that repeated O(points) work was slow enough to blow well past the target render duration,
 * and since the encoder stamps each frame's timestamp from when it actually arrives (not a fixed
 * clock), slow frames don't just make the render take longer — they make the *video's own playback*
 * stutter, because frames physically arrive at uneven intervals. Precomputing once fixes both.
 */
object RouteFrameDrawer {

    private val backgroundColor = Color.parseColor("#FFF8F5")
    private val cardColor = Color.parseColor("#F6E4DB")
    private val routeColor = Color.parseColor("#E8622B")
    private val darkTextColor = Color.parseColor("#221A15")
    private val darkLabelColor = Color.parseColor("#53443B")
    private val lightTextColor = Color.WHITE
    private val lightLabelColor = Color.parseColor("#EAEAEA")
    private val scrimColor = Color.parseColor("#73000000")
    private val startColor = Color.parseColor("#39662E")
    private val pauseResumeColor = Color.parseColor("#8A5A00")
    private val endColor = Color.parseColor("#BA1A1A")

    /** The route line is colored by elapsed progress (early = [routeGradientStartColor], later =
     * [routeGradientEndColor]) rather than one flat color, so an out-and-back ride's return leg
     * reads as a visibly different pass over the same roads instead of invisibly retracing an
     * identical line. */
    private val routeGradientStartColor = routeColor
    private val routeGradientEndColor = Color.parseColor("#2B62E8")

    /** How many colored chunks the route line is split into for the gradient — coarse enough to
     * keep per-frame draw calls cheap, fine enough that the color change reads as a smooth fade. */
    private const val GRADIENT_SEGMENT_COUNT = 40

    /** How many trailing points get the bright "current position" comet trail. */
    private const val TRAIL_POINT_COUNT = 14

    /** A multi-day day's photo/caption reaches full opacity this far into its own segment's
     * [RenderFrame.daySegmentProgress] — short enough that even a 3s rest-day hold gets a visible
     * fade rather than one slower than the hold itself. */
    private const val DAY_CROSSFADE_FRACTION = 0.15f

    private fun colorForFraction(fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        val r = lerpChannel(Color.red(routeGradientStartColor), Color.red(routeGradientEndColor), t)
        val g = lerpChannel(Color.green(routeGradientStartColor), Color.green(routeGradientEndColor), t)
        val b = lerpChannel(Color.blue(routeGradientStartColor), Color.blue(routeGradientEndColor), t)
        return Color.rgb(r, g, b)
    }

    private fun lerpChannel(from: Int, to: Int, t: Float): Int = (from + (to - from) * t).toInt().coerceIn(0, 255)

    fun prepare(width: Int, height: Int, data: RideRenderData): PreparedRender =
        PreparedRender(width, height, data)

    /** How far along the animated route (in GPS-point units) a given stop's marker/photo appears —
     * stops are recorded in chronological order, same as the GPS trail, so a stop's ordinal
     * position among all stops approximates its position along the route. */
    private fun stopRevealPointIndex(stopIndex: Int, totalStops: Int, totalPoints: Int): Float =
        (stopIndex.toFloat() / (totalStops - 1).coerceAtLeast(1)) * (totalPoints - 1)

    /** [isEndpoint] marks a ride's START/END stop — its label gets first pick of placement spots. */
    private class StopEntry(
        val revealIndex: Float,
        val point: PointF,
        val color: Int,
        val label: String?,
        val isEndpoint: Boolean
    )

    /** A pre-built, pre-colored chunk of the route line — [path] spans point indices
     * [startIndex]..[endIndex] inclusive and is reused every frame once fully revealed; only the
     * one chunk currently being drawn mid-reveal needs a fresh, shorter path each frame. */
    private class RouteSegment(val startIndex: Int, val endIndex: Int, val color: Int, val path: Path)

    class PreparedRender internal constructor(
        private val width: Int,
        private val height: Int,
        private val data: RideRenderData
    ) {
        private val usesPhoto = data.coverImagePath != null ||
            data.allStops.any { it.backgroundImagePath != null } ||
            data.days.any { it.backgroundImagePath != null }
        private val textColor = if (usesPhoto) lightTextColor else darkTextColor
        private val labelColor = if (usesPhoto) lightLabelColor else darkLabelColor

        private val margin = width * 0.06f
        private val headerBottom = height * 0.1f
        private val footerTop = height * 0.78f
        private val fullCanvasRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        private val routeBounds = RectF(margin, headerBottom + margin * 0.5f, width - margin, footerTop - margin * 0.5f)
        private val inset = RectF(
            routeBounds.left + routeBounds.width() * 0.08f,
            routeBounds.top + routeBounds.height() * 0.08f,
            routeBounds.right - routeBounds.width() * 0.08f,
            routeBounds.bottom - routeBounds.height() * 0.08f
        )

        private val allPoints = data.allPoints
        private val hasRoute = allPoints.size >= 2

        private val viewport = if (hasRoute) RouteProjection.viewportFor(allPoints, inset) else null
        private val projectedPoints: List<PointF> = viewport?.let { vp -> allPoints.map { vp.project(it) } } ?: emptyList()

        /** distancePrefixM[i] = cumulative distance from point 0 to point i — an O(1) lookup per
         * frame instead of re-walking every point (with a geodesic distance calc) each time. */
        private val distancePrefixM: DoubleArray = run {
            val prefix = DoubleArray(allPoints.size)
            val results = FloatArray(1)
            for (i in 1 until allPoints.size) {
                Location.distanceBetween(allPoints[i - 1].lat, allPoints[i - 1].lng, allPoints[i].lat, allPoints[i].lng, results)
                prefix[i] = prefix[i - 1] + results[0]
            }
            prefix
        }

        private val routeSegments: List<RouteSegment> = if (hasRoute) buildRouteSegments() else emptyList()

        private fun buildRouteSegments(): List<RouteSegment> {
            val n = allPoints.size
            val chunkSize = ceil((n - 1).toDouble() / min(GRADIENT_SEGMENT_COUNT, n - 1).coerceAtLeast(1)).toInt().coerceAtLeast(1)
            val segments = mutableListOf<RouteSegment>()
            var start = 0
            while (start < n - 1) {
                val end = min(start + chunkSize, n - 1)
                val midFraction = (start + end) / 2f / (n - 1)
                segments += RouteSegment(
                    startIndex = start,
                    endIndex = end,
                    color = colorForFraction(midFraction),
                    path = RouteProjection.smoothedPath(projectedPoints.subList(start, end + 1))
                )
                start = end
            }
            return segments
        }

        private val markerRadius = routeBounds.width() * 0.018f
        private val currentPositionRadius = routeBounds.width() * 0.024f
        private val stopEntries: List<StopEntry> = if (hasRoute) {
            val stops = data.allStops
            val vp = viewport!!
            stops.mapIndexedNotNull { index, stop ->
                val lat = stop.lat
                val lng = stop.lng
                if (lat == null || lng == null) return@mapIndexedNotNull null
                StopEntry(
                    revealIndex = stopRevealPointIndex(index, stops.size, allPoints.size),
                    point = vp.project(RidePoint(lat, lng, null)),
                    color = when (stop.action) {
                        RideEventAction.START -> startColor
                        RideEventAction.END -> endColor
                        RideEventAction.PAUSE, RideEventAction.RESUME -> pauseResumeColor
                    },
                    label = stop.displayName,
                    isEndpoint = stop.action == RideEventAction.START || stop.action == RideEventAction.END
                )
            }
        } else emptyList()

        /** (revealIndex, photo path) for stops with a background photo — single-day video crossfade. */
        private val imagedStops: List<Pair<Float, String>> = if (hasRoute && data.coverImagePath == null && !data.isMultiDay) {
            val stops = data.allStops
            stops.mapIndexedNotNull { index, stop ->
                stop.backgroundImagePath?.let { path -> stopRevealPointIndex(index, stops.size, allPoints.size) to path }
            }
        } else emptyList()

        // Precomputed final stat strings that don't change frame to frame (only distance/time run).
        private val avgSpeedText = String.format(Locale.US, "%.1f km/h", data.avgSpeedKmh)
        private val maxSpeedText = data.maxSpeedKmh?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "–"

        // All Paint objects are built once and reused every frame; only color/alpha mutate.
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = width * 0.052f
            typeface = Typeface.DEFAULT_BOLD
        }
        private val wordmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = routeColor
            textSize = width * 0.032f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.DEFAULT_BOLD
        }
        private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cardColor }
        private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = routeBounds.width() * 0.045f
            textAlign = Paint.Align.CENTER
        }
        private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = routeBounds.width() * 0.012f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = routeColor
        }
        private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = routeBounds.width() * 0.006f
            color = Color.WHITE
        }
        private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = routeBounds.width() * 0.017f
        }
        private val currentPositionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val currentPositionRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = routeBounds.width() * 0.009f
        }
        private val stopLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = darkTextColor
            textSize = routeBounds.width() * 0.028f
            textAlign = Paint.Align.CENTER
        }
        private val stopLabelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = backgroundColor
            alpha = 235
        }
        private val statsLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = width * 0.03f
        }
        private val statsValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = width * 0.052f
            typeface = Typeface.DEFAULT_BOLD
        }
        private val scrimPaint = Paint().apply { color = scrimColor }
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // The "Instagram style" per-day caption, bottom-center over the route graphics.
        private val captionBaseTextSize = routeBounds.width() * 0.042f
        private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = captionBaseTextSize
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val captionBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#99000000") }

        /** Dotted line tying a label that had to be pushed away from a crowded spot back to its marker. */
        private val leaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = routeBounds.width() * 0.005f
            strokeCap = Paint.Cap.ROUND
            pathEffect = DashPathEffect(floatArrayOf(0.1f, strokeWidth * 2.6f), 0f)
            color = if (usesPhoto) Color.WHITE else darkLabelColor
        }
        private val stopLabelPad = markerRadius * 0.5f

        /**
         * Each stop's label position, laid out once for the whole render (see [StopLabelLayout]) so
         * labels never collide and never move between video frames — parallel to [stopEntries].
         * Labels stay out of the header/stats bands and, when day captions are on, the caption bar.
         * A place repeated at the same spot (a loop ride's start and end) gets just one label.
         */
        private val labelPlacements: List<LabelPlacement?> = run {
            val sizes = stopEntries.map { entry ->
                entry.label?.let { name ->
                    LabelSize(
                        width = stopLabelPaint.measureText(name) + stopLabelPad * 2f,
                        height = stopLabelPaint.descent() - stopLabelPaint.ascent() + stopLabelPad * 2f
                    )
                }
            }
            val captionObstacles = if (data.days.any { !it.caption.isNullOrBlank() }) {
                val barBottom = routeBounds.bottom - routeBounds.height() * 0.05f
                listOf(RectF(routeBounds.left, barBottom - captionBaseTextSize * 2.3f, routeBounds.right, barBottom))
            } else emptyList()
            val anchors = stopEntries.map { it.point }
            StopLabelLayout.layout(
                anchors = anchors,
                sizes = StopLabelLayout.withoutDuplicateLabels(anchors, stopEntries.map { it.label }, sizes, markerRadius),
                order = stopEntries.indices.sortedBy { if (stopEntries[it].isEndpoint) 0 else 1 },
                markerRadius = markerRadius,
                bounds = RectF(margin * 0.5f, headerBottom, width - margin * 0.5f, footerTop),
                obstacles = captionObstacles,
                routePoints = projectedPoints
            )
        }

        /**
         * @param dayIndex which day's segment this frame belongs to (see [VideoTimeline]) — a
         *   multi-day video's per-day photo/caption come from this, not from [progress] (which, in
         *   point-index space, has no width at all for a rest day).
         * @param daySegmentProgress 0..1 through that day's own segment — a travel day's reveal
         *   fraction, or a rest day's hold fraction — used to fade its photo/caption in smoothly
         *   rather than cutting to it.
         */
        fun draw(canvas: Canvas, progress: Float, dayIndex: Int = 0, daySegmentProgress: Float = 1f) {
            canvas.drawColor(if (usesPhoto) Color.BLACK else backgroundColor)
            if (usesPhoto) drawPhotoBackground(canvas, progress, dayIndex, daySegmentProgress)

            canvas.drawText(data.title, margin, headerBottom * 0.65f, titlePaint)
            canvas.drawText("BikeRyde", width - margin, headerBottom * 0.65f, wordmarkPaint)

            // A background photo is the whole point of adding one — don't cover most of it with an
            // opaque card. The route/markers/labels draw with enough contrast (scrim + white text +
            // label pills) to read directly over the photo instead.
            if (!usesPhoto) {
                canvas.drawRoundRect(routeBounds, routeBounds.width() * 0.04f, routeBounds.width() * 0.04f, cardPaint)
            }

            if (!hasRoute) {
                canvas.drawText("No route data", routeBounds.centerX(), routeBounds.centerY(), emptyPaint)
                drawStats(canvas, fraction = 1f)
                return
            }

            val visibleCount = min(allPoints.size, ceil(progress * allPoints.size).toInt().coerceAtLeast(2))
            drawRoute(canvas, visibleCount)
            drawStops(canvas, visibleCount)
            drawCurrentPosition(canvas, visibleCount)
            drawCaption(canvas, data.days.firstOrNull { it.dayIndex == dayIndex }?.caption, dayFadeAlpha(daySegmentProgress))
            drawStats(canvas, visibleFraction(allPoints.size, visibleCount))
        }

        /** How far into a day's own segment its photo/caption has faded in — full opacity once
         * [daySegmentProgress] passes [DAY_CROSSFADE_FRACTION], not the whole segment, so a short
         * rest-day hold still gets a visible fade rather than a fade slower than the hold itself. */
        private fun dayFadeAlpha(daySegmentProgress: Float): Float =
            (daySegmentProgress / DAY_CROSSFADE_FRACTION).coerceIn(0f, 1f)

        /** The "Instagram style" bottom-center caption, layered above the route/photo graphics but
         * below the stats footer — a dark bar behind bold white text, shrunk to fit if it's long,
         * fading in at [alpha] alongside its day's photo. */
        private fun drawCaption(canvas: Canvas, caption: String?, alpha: Float) {
            if (caption.isNullOrBlank() || alpha <= 0f) return
            val maxWidth = routeBounds.width() * 0.84f
            captionPaint.textSize = captionBaseTextSize
            while (captionPaint.measureText(caption) > maxWidth && captionPaint.textSize > captionBaseTextSize * 0.55f) {
                captionPaint.textSize -= 2f
            }
            val barHeight = captionPaint.textSize * 2.3f
            val barBottom = routeBounds.bottom - routeBounds.height() * 0.05f
            val barTop = barBottom - barHeight
            captionBarPaint.alpha = (0x99 * alpha).toInt()
            canvas.drawRoundRect(
                routeBounds.left + routeBounds.width() * 0.06f,
                barTop,
                routeBounds.right - routeBounds.width() * 0.06f,
                barBottom,
                barHeight * 0.3f,
                barHeight * 0.3f,
                captionBarPaint
            )
            captionPaint.alpha = (255 * alpha).toInt()
            val textY = barBottom - barHeight / 2f - (captionPaint.ascent() + captionPaint.descent()) / 2f
            canvas.drawText(caption, routeBounds.centerX(), textY, captionPaint)
        }

        /** Draws every route chunk revealed so far — precomputed chunks are reused as-is; the one
         * chunk currently mid-reveal is redrawn each frame with just its visible prefix. */
        private fun drawRoute(canvas: Canvas, visibleCount: Int) {
            val lastRevealedIndex = visibleCount - 1
            for (segment in routeSegments) {
                if (segment.startIndex > lastRevealedIndex) break
                routePaint.color = segment.color
                if (segment.endIndex <= lastRevealedIndex) {
                    canvas.drawPath(segment.path, routePaint)
                } else if (lastRevealedIndex > segment.startIndex) {
                    canvas.drawPath(
                        RouteProjection.smoothedPath(projectedPoints.subList(segment.startIndex, lastRevealedIndex + 1)),
                        routePaint
                    )
                }
            }
        }

        /** A bright "you are here" marker with a short fading comet trail at the current animation
         * playhead — so a leg of the route that retraces an earlier one (an out-and-back ride) still
         * visibly shows motion instead of the line just silently overlapping itself. Hidden once the
         * route finishes drawing (the end-of-video hold), where it would only duplicate the END stop
         * marker already at that same point. */
        private fun drawCurrentPosition(canvas: Canvas, visibleCount: Int) {
            if (!hasRoute || visibleCount < 2 || visibleCount >= allPoints.size) return
            val headIndex = visibleCount - 1
            val headFraction = headIndex.toFloat() / (allPoints.size - 1)
            val headColor = colorForFraction(headFraction)
            val head = projectedPoints[headIndex]

            val trailStart = (headIndex - TRAIL_POINT_COUNT).coerceAtLeast(0)
            if (trailStart < headIndex) {
                val trailPoints = projectedPoints.subList(trailStart, headIndex + 1)
                val tail = trailPoints.first()
                trailPaint.shader = LinearGradient(
                    tail.x, tail.y, head.x, head.y,
                    Color.TRANSPARENT, headColor,
                    Shader.TileMode.CLAMP
                )
                canvas.drawPath(RouteProjection.smoothedPath(trailPoints), trailPaint)
                trailPaint.shader = null
            }

            currentPositionRingPaint.color = headColor
            canvas.drawCircle(head.x, head.y, currentPositionRadius, currentPositionFillPaint)
            canvas.drawCircle(head.x, head.y, currentPositionRadius, currentPositionRingPaint)
        }

        /** Leader lines first, then markers, then labels — so a label is never covered by a later
         * marker or another label's leader line. */
        private fun drawStops(canvas: Canvas, visibleCount: Int) {
            val visible = stopEntries.indices.filter { stopEntries[it].revealIndex <= visibleCount - 1 }
            for (i in visible) {
                val placement = labelPlacements[i]?.takeIf { it.needsLeader } ?: continue
                val anchor = stopEntries[i].point
                val end = StopLabelLayout.leaderEnd(anchor, placement.rect)
                val length = hypot(end.x - anchor.x, end.y - anchor.y)
                if (length <= markerRadius) continue
                val startX = anchor.x + (end.x - anchor.x) / length * markerRadius
                val startY = anchor.y + (end.y - anchor.y) / length * markerRadius
                canvas.drawLine(startX, startY, end.x, end.y, leaderPaint)
            }
            for (i in visible) {
                val entry = stopEntries[i]
                markerPaint.color = entry.color
                canvas.drawCircle(entry.point.x, entry.point.y, markerRadius, markerPaint)
                canvas.drawCircle(entry.point.x, entry.point.y, markerRadius, markerStrokePaint)
            }
            for (i in visible) {
                val name = stopEntries[i].label ?: continue
                val rect = labelPlacements[i]?.rect ?: continue
                canvas.drawRoundRect(rect, stopLabelPad, stopLabelPad, stopLabelBackgroundPaint)
                canvas.drawText(name, rect.centerX(), rect.top + stopLabelPad - stopLabelPaint.ascent(), stopLabelPaint)
            }
        }

        /**
         * Draws the active background photo: the single [RideRenderData.coverImagePath] for a
         * static image; for a single-day video, a crossfade between each stop's photo as the route
         * animates past it; for a multi-day video, a crossfade between each day's photo (travel or
         * rest alike) as its segment begins — see [drawMultiDayPhoto]. A dark scrim under all of
         * these keeps the route/stats/caption legible.
         */
        private fun drawPhotoBackground(canvas: Canvas, progress: Float, dayIndex: Int, daySegmentProgress: Float) {
            val coverPath = data.coverImagePath
            if (coverPath != null) {
                BackgroundImageCache.get(coverPath)?.let { bitmap ->
                    bitmapPaint.alpha = 255
                    canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
                }
                canvas.drawRect(fullCanvasRect, scrimPaint)
                return
            }

            if (data.isMultiDay) {
                drawMultiDayPhoto(canvas, dayIndex, daySegmentProgress)
                return
            }

            if (imagedStops.isEmpty()) return
            val totalPoints = allPoints.size
            val playhead = progress * (totalPoints - 1).coerceAtLeast(1)
            val crossfadeWindow = (totalPoints * 0.08f).coerceAtLeast(3f)
            val prev = imagedStops.lastOrNull { it.first <= playhead }
            val next = imagedStops.firstOrNull { it.first > playhead }

            if (prev != null) {
                BackgroundImageCache.get(prev.second)?.let { bitmap ->
                    bitmapPaint.alpha = 255
                    canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
                }
            }
            if (next != null) {
                val fadeSpan = if (prev != null) (next.first - prev.first) else crossfadeWindow
                val fadeStart = next.first - min(crossfadeWindow, fadeSpan)
                val alpha = ((playhead - fadeStart) / (next.first - fadeStart).coerceAtLeast(1f)).coerceIn(0f, 1f)
                if (alpha > 0f) {
                    BackgroundImageCache.get(next.second)?.let { bitmap ->
                        bitmapPaint.alpha = (alpha * 255).toInt()
                        canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
                    }
                }
            }
            canvas.drawRect(fullCanvasRect, scrimPaint)
        }

        /** Every day (travel or rest) is a segment with its own photo and its own 0..1
         * [daySegmentProgress] — the previous day-with-a-photo stays at full opacity underneath
         * while the current day's photo fades in over [DAY_CROSSFADE_FRACTION] of its segment, so a
         * rest day gets exactly the same smooth transition a travel day does, instead of the hard
         * cut a point-index-based crossfade gives it (it has no points, so no width in that space). */
        private fun drawMultiDayPhoto(canvas: Canvas, dayIndex: Int, daySegmentProgress: Float) {
            val currentPhoto = data.days.firstOrNull { it.dayIndex == dayIndex }?.backgroundImagePath
            val previousPhoto = data.days
                .filter { it.dayIndex < dayIndex && it.backgroundImagePath != null }
                .maxByOrNull { it.dayIndex }
                ?.backgroundImagePath
            if (currentPhoto == null && previousPhoto == null) return

            previousPhoto?.let { path ->
                BackgroundImageCache.get(path)?.let { bitmap ->
                    bitmapPaint.alpha = 255
                    canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
                }
            }
            val fadeAlpha = dayFadeAlpha(daySegmentProgress)
            if (currentPhoto != null && fadeAlpha > 0f) {
                BackgroundImageCache.get(currentPhoto)?.let { bitmap ->
                    bitmapPaint.alpha = (fadeAlpha * 255).toInt()
                    canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
                }
            }
            canvas.drawRect(fullCanvasRect, scrimPaint)
        }

        private fun visibleFraction(totalPoints: Int, visibleCount: Int): Float =
            if (totalPoints < 2) 1f else visibleCount.toFloat() / totalPoints

        private fun drawStats(canvas: Canvas, fraction: Float) {
            val runningDistanceM = if (allPoints.size < 2) {
                data.totalDistanceM
            } else {
                val idx = (ceil(fraction * allPoints.size).toInt().coerceAtLeast(2) - 1).coerceIn(0, allPoints.size - 1)
                distancePrefixM[idx]
            }
            val runningDurationS = (data.totalDurationS * fraction).toLong()

            val columnWidth = (width - width * 0.12f) / 2f
            val col1 = width * 0.06f
            val col2 = col1 + columnWidth

            // The stats block is vertically centered within the footer band (with room to breathe
            // on both sides), rather than packed against its top with the bottom row crowding the edge.
            val footerHeight = height - footerTop
            val topRowLabelY = footerTop + footerHeight * 0.22f
            val topRowValueY = footerTop + footerHeight * 0.38f
            val bottomRowLabelY = footerTop + footerHeight * 0.64f
            val bottomRowValueY = footerTop + footerHeight * 0.80f

            canvas.drawText("DISTANCE", col1, topRowLabelY, statsLabelPaint)
            canvas.drawText(String.format(Locale.US, "%.2f km", runningDistanceM / 1000.0), col1, topRowValueY, statsValuePaint)

            canvas.drawText("TIME ON ROAD", col2, topRowLabelY, statsLabelPaint)
            canvas.drawText(formatDuration(runningDurationS), col2, topRowValueY, statsValuePaint)

            canvas.drawText("AVG SPEED", col1, bottomRowLabelY, statsLabelPaint)
            canvas.drawText(avgSpeedText, col1, bottomRowValueY, statsValuePaint)

            canvas.drawText("MAX SPEED", col2, bottomRowLabelY, statsLabelPaint)
            canvas.drawText(maxSpeedText, col2, bottomRowValueY, statsValuePaint)
        }

        private fun formatDuration(totalSeconds: Long): String {
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            return String.format(Locale.US, "%02d:%02d hrs", hours, minutes)
        }
    }
}
