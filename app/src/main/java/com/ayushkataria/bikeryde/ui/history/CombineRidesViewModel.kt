package com.ayushkataria.bikeryde.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ayushkataria.bikeryde.ride.CombineDay
import com.ayushkataria.bikeryde.ride.Ride
import com.ayushkataria.bikeryde.ride.RideDay
import com.ayushkataria.bikeryde.ride.RideDayType
import com.ayushkataria.bikeryde.ride.RideRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State for [CombineRidesFragment]: which completed single-day rides the rider picked, and how
 * many rest days they inserted after each one. Kept here (not in the Fragment) so a rotation
 * doesn't lose the plan, and so the combine itself — a multi-table copy — can't be cancelled
 * halfway by the view going away.
 */
class CombineRidesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RideRepository(application)

    /** A combinable single-day ride and its sole [RideDay]. */
    data class Candidate(val ride: Ride, val day: RideDay)

    /** One row of the trip preview — a [RideDay] built for display, plus what it maps to. */
    data class PlannedDay(val preview: RideDay, val plan: CombineDay, val afterRideId: Long)

    data class UiState(
        val isLoading: Boolean = true,
        val candidates: List<Candidate> = emptyList(),
        val selectedRideIds: Set<Long> = emptySet(),
        /** Rest days inserted after each selected ride, keyed by ride id. */
        val restDaysAfter: Map<Long, Int> = emptyMap(),
        val isCombining: Boolean = false
    ) {
        val canCombine: Boolean get() = selectedRideIds.size >= MIN_RIDES && !isCombining

        /** The new ride's days in order: selected rides by start time, each followed by its rest days. */
        val plannedDays: List<PlannedDay>
            get() {
                val days = mutableListOf<PlannedDay>()
                candidates.filter { it.ride.id in selectedRideIds }
                    .sortedBy { it.day.startTime }
                    .forEach { candidate ->
                        val source = candidate.day
                        days += PlannedDay(
                            preview = source.copy(dayIndex = days.size),
                            plan = CombineDay.Travel(candidate.ride.id),
                            afterRideId = candidate.ride.id
                        )
                        repeat(restDaysAfter[candidate.ride.id] ?: 0) { n ->
                            val date = source.startTime + (n + 1) * DAY_MS
                            days += PlannedDay(
                                preview = RideDay(
                                    id = 0L,
                                    rideId = 0L,
                                    dayIndex = days.size,
                                    dayType = RideDayType.NOT_TRAVEL,
                                    startTime = date,
                                    endTime = date,
                                    startPlaceName = source.endPlaceName,
                                    endPlaceName = source.endPlaceName,
                                    distanceKm = 0.0,
                                    durationS = 0L
                                ),
                                plan = CombineDay.Rest(date, source.endPlaceName),
                                afterRideId = candidate.ride.id
                            )
                        }
                    }
                return days
            }
    }

    sealed class Event {
        data class Combined(val rideId: Long) : Event()
        object CombineFailed : Event()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val candidates = repository.getCompletedSingleDayRides().mapNotNull { ride ->
                repository.getRideDays(ride.id).firstOrNull()?.let { Candidate(ride, it) }
            }
            _state.update { it.copy(isLoading = false, candidates = candidates) }
        }
    }

    fun toggleSelected(rideId: Long) = _state.update { state ->
        if (rideId in state.selectedRideIds) {
            state.copy(selectedRideIds = state.selectedRideIds - rideId, restDaysAfter = state.restDaysAfter - rideId)
        } else {
            state.copy(selectedRideIds = state.selectedRideIds + rideId)
        }
    }

    fun addRestDay(afterRideId: Long) = _state.update { state ->
        state.copy(restDaysAfter = state.restDaysAfter + (afterRideId to (state.restDaysAfter[afterRideId] ?: 0) + 1))
    }

    fun removeRestDay(afterRideId: Long) = _state.update { state ->
        val remaining = (state.restDaysAfter[afterRideId] ?: 0) - 1
        state.copy(
            restDaysAfter = if (remaining > 0) state.restDaysAfter + (afterRideId to remaining) else state.restDaysAfter - afterRideId
        )
    }

    fun combine(title: String?, deleteOriginals: Boolean) {
        val current = _state.value
        if (!current.canCombine) return
        _state.update { it.copy(isCombining = true) }
        viewModelScope.launch {
            try {
                val rideId = withContext(NonCancellable) {
                    val newRideId = repository.combineRides(current.plannedDays.map { it.plan }, title)
                    if (deleteOriginals) current.selectedRideIds.forEach { repository.deleteRide(it) }
                    newRideId
                }
                _events.trySend(Event.Combined(rideId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isCombining = false) }
                _events.trySend(Event.CombineFailed)
            }
        }
    }

    companion object {
        const val MIN_RIDES = 2
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
