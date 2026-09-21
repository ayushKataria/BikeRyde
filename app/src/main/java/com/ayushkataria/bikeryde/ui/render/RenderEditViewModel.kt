package com.ayushkataria.bikeryde.ui.render

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ayushkataria.bikeryde.media.MergedStop
import com.ayushkataria.bikeryde.media.RenderImageStorage
import com.ayushkataria.bikeryde.media.RenderRepository
import com.ayushkataria.bikeryde.media.RenderType
import com.ayushkataria.bikeryde.media.RideRenderDataAssembler
import com.ayushkataria.bikeryde.media.StaticImageRenderer
import com.ayushkataria.bikeryde.media.VideoDurationRecommender
import com.ayushkataria.bikeryde.media.mergedStopsForRide
import com.ayushkataria.bikeryde.ride.RideDay
import com.ayushkataria.bikeryde.ride.RideRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Holds everything the customize screen ([RenderEditFragment]) needs to keep across a
 * configuration change (rotation, dark-mode toggle, locale change): the ride data it loaded and
 * every edit the rider has made so far. The Fragment owns only the views, and rebuilds them from
 * this state whenever its view is recreated.
 *
 * Edits are stored as overrides on top of the defaults (recorded stop name, generated day
 * caption), so an untouched field still tracks its default.
 *
 * It also owns the two pieces of work that must not be cancelled by a rotation — copying a picked
 * photo into app storage ([onImagePicked]) and rendering the static image ([generateImage]) — so
 * they run on [viewModelScope] rather than the Fragment's view lifecycle. Their outcomes reach the
 * screen through [isRendering] and [events], which a recreated view simply picks back up.
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
    private val repository = RideRepository(application)

    /** What a pending photo-picker launch is for. */
    sealed class PickTarget {
        object Cover : PickTarget()
        data class Stop(val index: Int) : PickTarget()
        data class Day(val dayIndex: Int) : PickTarget()
    }

    /** One-shot outcomes of background work, delivered to whichever view is currently collecting [events]. */
    sealed class Event {
        /** The photo for [target] was copied and is now recorded in the matching path field/map. */
        data class ImagePicked(val target: PickTarget) : Event()
        object ImagePickFailed : Event()
        data class ImageRenderDone(val uri: Uri) : Event()
        object ImageRenderFailed : Event()
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

    // --- Background work state ---

    private val _isRendering = MutableStateFlow(false)

    /** True while a static image render is in flight — the Generate button stays disabled meanwhile, even across a rotation. */
    val isRendering: StateFlow<Boolean> = _isRendering.asStateFlow()

    // Unlimited buffer + receiveAsFlow: an outcome that lands while no view is collecting (mid-rotation,
    // app backgrounded) waits and is delivered exactly once to the next collector.
    private val _events = Channel<Event>(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

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

    /**
     * Copies the picked photo into app storage and records it for [target]. The copy is verified by
     * decoding a thumbnail from it, not the picker's Uri — a photo picker's content:// grant can fail
     * to re-open by the time it's needed, and a copy that won't decode now would fail identically
     * when the render runs later, so it's treated as a failed pick instead of silently recorded.
     */
    fun onImagePicked(target: PickTarget, uri: Uri) {
        viewModelScope.launch {
            val path = runCatching { RenderImageStorage.copyToAppStorage(getApplication(), uri) }.getOrNull()
            if (path == null) {
                _events.trySend(Event.ImagePickFailed)
                return@launch
            }
            val decodes = runCatching {
                RenderImageStorage.decodeSampledBitmap(path, THUMBNAIL_SIZE_PX, THUMBNAIL_SIZE_PX)
            }.getOrNull() != null
            if (!decodes) {
                runCatching { File(path).delete() }
                _events.trySend(Event.ImagePickFailed)
                return@launch
            }
            when (target) {
                is PickTarget.Cover -> coverImagePath = path
                is PickTarget.Stop -> stopBackgroundPaths[target.index] = path
                is PickTarget.Day -> dayBackgroundPaths[target.dayIndex] = path
            }
            _events.trySend(Event.ImagePicked(target))
        }
    }

    /** Renders the static image from the current edits; the result arrives as [Event.ImageRenderDone] or [Event.ImageRenderFailed]. */
    fun generateImage() {
        if (_isRendering.value) return
        _isRendering.value = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            val renderRepository = RenderRepository(app)
            var renderId: Long? = null
            try {
                // This render replaces any previous preview for this ride — delete it now rather
                // than leaving an orphaned preview file behind in app storage.
                renderRepository.getLatest(rideId, RenderType.IMAGE)?.filePath?.let { path ->
                    runCatching { File(path).delete() }
                }
                renderId = renderRepository.insertQueued(rideId, RenderType.IMAGE)
                renderRepository.markProcessing(renderId)

                val data = RideRenderDataAssembler(repository).assemble(
                    rideId = rideId,
                    stopNameOverrides = stopNameOverrides(),
                    excludedStopIndices = excludedStopIndices,
                    coverImagePath = coverImagePath
                )
                val output = data?.let {
                    try {
                        StaticImageRenderer(app).render(it)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        null
                    }
                }
                if (output == null) {
                    renderRepository.markFailed(renderId)
                    _isRendering.value = false
                    _events.trySend(Event.ImageRenderFailed)
                    return@launch
                }
                renderRepository.markDone(renderId, output.filePath, StaticImageRenderer.RESOLUTION_LABEL, fps = 0)
                _isRendering.value = false
                _events.trySend(Event.ImageRenderDone(output.uri))
            } catch (e: CancellationException) {
                // Only happens when the screen is left for good (the ViewModel is cleared) — a rotation
                // no longer cancels this. Don't leave the row claiming to still be processing.
                renderId?.let { withContext(NonCancellable) { renderRepository.markFailed(it) } }
                throw e
            }
        }
    }

    companion object {
        /** Bounding size for the on-screen photo thumbnails (and for verifying a picked photo decodes). */
        const val THUMBNAIL_SIZE_PX = 400
    }
}
