package com.ayushkataria.bikeryde.ui.render

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import com.ayushkataria.bikeryde.media.MergedStop
import com.ayushkataria.bikeryde.media.RenderType
import com.ayushkataria.bikeryde.media.RideRenderDataAssembler
import com.ayushkataria.bikeryde.media.VideoDurationRecommender
import com.ayushkataria.bikeryde.media.mergedStopsForRide
import com.ayushkataria.bikeryde.ride.RideDay
import com.ayushkataria.bikeryde.ride.RideRepository

/**
 * Holds everything the customize screen ([RenderEditFragment]) needs to keep across a
 * configuration change (rotation, dark-mode toggle, locale change): the ride data it loaded and
 * every edit the rider has made so far. The Fragment owns only the views, and rebuilds them from
 * this state whenever its view is recreated.
 *
 * Edits are stored as overrides on top of the defaults (recorded stop name, generated day
 * caption), so an untouched field still tracks its default.
 *
 * The ride id and render type come from the Fragment's arguments via [SavedStateHandle].
 */
class RenderEditViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    val rideId: Long = requireNotNull(savedStateHandle.get<Long>(RenderEditFragment.ARG_RIDE_ID))
    val renderType: RenderType =
        RenderType.valueOf(requireNotNull(savedStateHandle.get<String>(RenderEditFragment.ARG_RENDER_TYPE)))
    val repository = RideRepository(application)

    /** What a pending photo-picker launch is for. */
    sealed class PickTarget {
        object Cover : PickTarget()
        data class Stop(val index: Int) : PickTarget()
        data class Day(val dayIndex: Int) : PickTarget()
    }

    // --- Loaded ride data (read-only to the UI) ---

    var stops: List<MergedStop> = emptyList()
        private set
    var rideDays: List<RideDay> = emptyList()
        private set

    /** True only for a multi-day *video* — a multi-day static image still uses [coverImagePath] like a single-day one. */
    var useDayRows = false
        private set
    var recommendedDurationS = VideoDurationRecommender.MIN_SECONDS
        private set

    private var defaultDayCaptions: Map<Int, String> = emptyMap()
    private var loaded = false

    // --- User edits ---

    var coverImagePath: String? = null
    val stopBackgroundPaths = mutableMapOf<Int, String>()
    val dayBackgroundPaths = mutableMapOf<Int, String>()
    val excludedStopIndices = mutableSetOf<Int>()
    var dayLabelsEnabled = false
    var durationMultiplier = VideoDurationRecommender.DEFAULT_MULTIPLIER
    var pendingPickTarget: PickTarget? = null

    private val stopNameEdits = mutableMapOf<Int, String>()
    private val dayCaptionEdits = mutableMapOf<Int, String>()

    /** Loads the ride's stops and days once; later calls (e.g. after a rotation) return immediately. */
    suspend fun load() {
        if (loaded) return
        val days = repository.getRideDays(rideId)
        val mergedStops = mergedStopsForRide(repository, rideId)

        // Assigned only after every read finished, so a load cancelled midway leaves nothing half-set.
        rideDays = days
        stops = mergedStops
        useDayRows = renderType == RenderType.VIDEO && days.size > 1
        if (renderType == RenderType.VIDEO) {
            recommendedDurationS = VideoDurationRecommender.recommend(mergedStops.size)
        }
        if (useDayRows) {
            val assembler = RideRenderDataAssembler(repository)
            defaultDayCaptions = days.associate { it.dayIndex to assembler.defaultDayCaption(it) }
        }
        loaded = true
    }

    fun stopName(index: Int): String = stopNameEdits[index] ?: stops[index].placeName.orEmpty()

    fun setStopName(index: Int, name: String) {
        stopNameEdits[index] = name
    }

    /** The name for every stop as it should render, edited or not. */
    fun stopNameOverrides(): Map<Int, String> = stops.indices.associateWith { stopName(it) }

    fun dayCaption(dayIndex: Int): String = dayCaptionEdits[dayIndex] ?: defaultDayCaptions[dayIndex].orEmpty()

    fun setDayCaption(dayIndex: Int, caption: String) {
        dayCaptionEdits[dayIndex] = caption
    }

    val videoDurationSeconds: Int
        get() = VideoDurationRecommender.apply(recommendedDurationS, durationMultiplier)
}
