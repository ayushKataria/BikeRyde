package com.ayushkataria.bikeryde.media

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Where a stop's label pill goes. [needsLeader] means it couldn't fit next to its marker and was
 * pushed further out, so a dotted leader line must connect it back to the marker. */
internal class LabelPlacement(val rect: RectF, val needsLeader: Boolean)

/** A label pill's measured size, padding included. */
internal class LabelSize(val width: Float, val height: Float)

/**
 * Places stop-name labels so they don't collide — with each other, with any stop marker, or with
 * fixed areas like the caption bar — and stay inside [bounds]. Run once per render (not per frame),
 * so a label never jumps around while a video plays.
 *
 * A label normally sits centered directly above its marker (or directly below, if above doesn't
 * fit), with no line — but only when that spot is free *and* unambiguous: no other marker may be
 * nearly as close to the label as its own marker is. In a static image every label is drawn at
 * once, so a label that merely avoids collisions while sitting between two nearby stops would
 * still leave the viewer guessing which stop it names.
 *
 * Otherwise — typically stops too close together — the label is pushed outward in widening
 * rings to the nearest free spot (preferring spots off the route, away from other markers, and
 * whose line doesn't cross another label), and flagged for a dotted leader line back to its
 * marker, the way maps label small islands. No label is ever dropped: if a route is so dense that
 * even that fails, the label takes whichever spot overlaps the least.
 */
internal object StopLabelLayout {

    private const val RING_DIRECTIONS = 16
    private const val MAX_ROUTE_SAMPLES = 400
    private const val LEADER_SAMPLES = 12

    /**
     * @param anchors every stop marker's center (labelled or not — all are obstacles).
     * @param sizes each marker's label size, or null for a marker with no label; same order as [anchors].
     * @param order the indices to place, highest priority first — earlier labels get the better spots.
     * @return one placement per index of [anchors]; null exactly where [sizes] is null.
     */
    fun layout(
        anchors: List<PointF>,
        sizes: List<LabelSize?>,
        order: List<Int>,
        markerRadius: Float,
        bounds: RectF,
        obstacles: List<RectF>,
        routePoints: List<PointF>
    ): List<LabelPlacement?> {
        val markerBoxes = anchors.map { p ->
            val r = markerRadius * 1.3f
            RectF(p.x - r, p.y - r, p.x + r, p.y + r)
        }
        val routeStep = (routePoints.size / MAX_ROUTE_SAMPLES).coerceAtLeast(1)
        val routeSamples = routePoints.filterIndexed { i, _ -> i % routeStep == 0 }
        val gap = markerRadius * 0.8f
        val placed = arrayOfNulls<LabelPlacement>(anchors.size)
        val placedRects = mutableListOf<RectF>()

        fun isFree(rect: RectF): Boolean =
            bounds.contains(rect) &&
                placedRects.none { RectF.intersects(it, rect) } &&
                markerBoxes.none { RectF.intersects(it, rect) } &&
                obstacles.none { RectF.intersects(it, rect) }

        fun routeHits(rect: RectF): Int = routeSamples.count { rect.contains(it.x, it.y) }

        /** How close the nearest *other* marker comes to [rect], measured to its nearest edge. */
        fun nearestOtherMarkerDistance(rect: RectF, ownIndex: Int): Float =
            anchors.indices.filter { it != ownIndex }.minOfOrNull { j -> distanceToRect(anchors[j], rect) }
                ?: Float.MAX_VALUE

        for (index in order) {
            val size = sizes[index] ?: continue
            val anchor = anchors[index]
            // A label reads as its marker's only if every other marker is clearly further from it.
            val clearance = size.height * 1.5f

            fun isUnambiguous(rect: RectF): Boolean =
                nearestOtherMarkerDistance(rect, index) > distanceToRect(anchor, rect) + clearance

            fun leaderScore(rect: RectF): Int =
                routeHits(rect) * 10 +
                    leaderCrossings(anchor, rect, placedRects) * 50 +
                    (if (nearestOtherMarkerDistance(rect, index) < clearance) 30 else 0)

            val placement = directCandidates(anchor, size, markerRadius, gap)
                .firstOrNull { isFree(it) && isUnambiguous(it) }
                ?.let { LabelPlacement(it, needsLeader = false) }
                ?: farPlacement(anchor, size, markerRadius, gap, bounds, ::isFree, ::leaderScore)
                ?: LabelPlacement(leastOverlapping(anchor, size, markerRadius, gap, bounds, placedRects), needsLeader = true)

            placed[index] = placement
            placedRects += placement.rect
        }
        return placed.toList()
    }

    /**
     * Drops the label of any stop that repeats an earlier stop's name close enough that their
     * labels would overlap — e.g. a loop ride that starts and ends at the same place — so the
     * place is named once instead of twice (each copy with its own leader line). Both markers stay.
     * The earliest stop keeps the label, so a video still shows the name as soon as the route
     * first reaches that place.
     *
     * @param names each marker's label text (null for none), in chronological order.
     * @return [sizes] with the repeated labels replaced by null.
     */
    fun withoutDuplicateLabels(
        anchors: List<PointF>,
        names: List<String?>,
        sizes: List<LabelSize?>,
        markerRadius: Float
    ): List<LabelSize?> {
        val gap = markerRadius * 0.8f
        val kept = mutableListOf<Int>()
        return sizes.mapIndexed { index, size ->
            val name = names[index]?.trim()
            if (size == null || name.isNullOrEmpty()) return@mapIndexed size
            val ownRect = directCandidates(anchors[index], size, markerRadius, gap).first()
            val isRepeat = kept.any { other ->
                names[other]?.trim().equals(name, ignoreCase = true) &&
                    RectF.intersects(directCandidates(anchors[other], sizes[other]!!, markerRadius, gap).first(), ownRect)
            }
            if (isRepeat) null else size.also { kept += index }
        }
    }

    /** The two line-free spots, centered on the marker: the classic "pin label" above it, then below. */
    private fun directCandidates(anchor: PointF, size: LabelSize, markerRadius: Float, gap: Float): List<RectF> {
        val offset = markerRadius + gap + size.height / 2f
        return listOf(
            rectAt(anchor.x, anchor.y - offset, size),
            rectAt(anchor.x, anchor.y + offset, size)
        )
    }

    /** The nearest free spot further out, searched ring by ring; within a ring, the lowest [score] wins. */
    private fun farPlacement(
        anchor: PointF,
        size: LabelSize,
        markerRadius: Float,
        gap: Float,
        bounds: RectF,
        isFree: (RectF) -> Boolean,
        score: (RectF) -> Int
    ): LabelPlacement? {
        val step = max(size.height * 1.4f, markerRadius * 3f)
        val maxDistance = hypot(bounds.width(), bounds.height())
        var distance = markerRadius + gap + step
        while (distance < maxDistance) {
            val best = ringCandidates(anchor, size, distance)
                .filter(isFree)
                .minByOrNull(score)
            if (best != null) return LabelPlacement(best, needsLeader = true)
            distance += step
        }
        return null
    }

    /** Label rects whose nearest edge sits [distance] from [anchor], in [RING_DIRECTIONS] directions. */
    private fun ringCandidates(anchor: PointF, size: LabelSize, distance: Float): List<RectF> =
        (0 until RING_DIRECTIONS).map { i ->
            val angle = 2.0 * Math.PI * i / RING_DIRECTIONS
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            rectAt(
                anchor.x + dx * (distance + size.width / 2f),
                anchor.y + dy * (distance + size.height / 2f),
                size
            )
        }

    /** Last resort for an extremely crowded route: the in-bounds spot overlapping other labels the least. */
    private fun leastOverlapping(
        anchor: PointF,
        size: LabelSize,
        markerRadius: Float,
        gap: Float,
        bounds: RectF,
        placedRects: List<RectF>
    ): RectF {
        val step = max(size.height * 1.4f, markerRadius * 3f)
        val candidates = directCandidates(anchor, size, markerRadius, gap) +
            (1..6).flatMap { ring -> ringCandidates(anchor, size, markerRadius + gap + step * ring) }
        return candidates
            .map { clampInto(it, bounds) }
            .minBy { rect -> placedRects.sumOf { overlapArea(it, rect).toDouble() } }
    }

    /** How many already-placed labels a leader line from [anchor] to [rect] would run through. */
    private fun leaderCrossings(anchor: PointF, rect: RectF, placedRects: List<RectF>): Int {
        val end = leaderEnd(anchor, rect)
        return placedRects.count { other ->
            (1 until LEADER_SAMPLES).any { s ->
                val t = s.toFloat() / LEADER_SAMPLES
                other.contains(anchor.x + (end.x - anchor.x) * t, anchor.y + (end.y - anchor.y) * t)
            }
        }
    }

    /** The point on [rect]'s border nearest to [anchor] — where a leader line meets its label. */
    fun leaderEnd(anchor: PointF, rect: RectF): PointF =
        PointF(anchor.x.coerceIn(rect.left, rect.right), anchor.y.coerceIn(rect.top, rect.bottom))

    private fun distanceToRect(point: PointF, rect: RectF): Float {
        val nearest = leaderEnd(point, rect)
        return hypot(nearest.x - point.x, nearest.y - point.y)
    }

    private fun rectAt(centerX: Float, centerY: Float, size: LabelSize): RectF = RectF(
        centerX - size.width / 2f,
        centerY - size.height / 2f,
        centerX + size.width / 2f,
        centerY + size.height / 2f
    )

    private fun clampInto(rect: RectF, bounds: RectF): RectF {
        val dx = when {
            rect.left < bounds.left -> bounds.left - rect.left
            rect.right > bounds.right -> bounds.right - rect.right
            else -> 0f
        }
        val dy = when {
            rect.top < bounds.top -> bounds.top - rect.top
            rect.bottom > bounds.bottom -> bounds.bottom - rect.bottom
            else -> 0f
        }
        return RectF(rect).apply { offset(dx, dy) }
    }

    private fun overlapArea(a: RectF, b: RectF): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        return if (w > 0f && h > 0f) w * h else 0f
    }
}
