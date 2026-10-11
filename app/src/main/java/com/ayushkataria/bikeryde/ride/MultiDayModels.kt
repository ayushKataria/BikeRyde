package com.ayushkataria.bikeryde.ride

/** Whether a [Ride] is a single-day outing or one leg of a longer trip. */
enum class RideType {
    SINGLE_DAY,
    MULTI_DAY
}

/**
 * Whether a [RideDay] involved riding or was spent in one place — e.g. a rest day or a day set
 * aside for sightseeing/tourism at the current stop, with no GPS track of its own.
 */
enum class RideDayType {
    TRAVEL,
    NOT_TRAVEL
}

/**
 * One day within a [Ride]. A single-day ride has exactly one of these (dayIndex 0); a multi-day
 * ride gets one per start/pause-or-end day segment, plus one per [RideDayType.NOT_TRAVEL] day
 * spent at a stop. All [Stop]s and [GpsPoint]s hang off a TRAVEL RideDay.
 */
data class RideDay(
    val id: Long,
    val rideId: Long,
    val dayIndex: Int,
    val dayType: RideDayType,
    val startTime: Long,
    val endTime: Long?,
    val startPlaceName: String?,
    val endPlaceName: String?,
    val distanceKm: Double,
    val durationS: Long
)

/** A manual start/pause/end action captured during a [RideDay], reverse-geocoded to a place name. */
data class Stop(
    val id: Long,
    val rideDayId: Long,
    val action: RideEventAction,
    val timestamp: Long,
    val lat: Double,
    val lng: Double,
    val placeName: String?
)

/** A single logged GPS fix belonging to a [RideDay]'s tracked route. */
data class GpsPoint(
    val id: Long,
    val rideDayId: Long,
    val timestamp: Long,
    val lat: Double,
    val lng: Double,
    val elevation: Double?,
    val speed: Float?
)

/**
 * One day in a plan for combining completed single-day rides into a multi-day [Ride] — see
 * [RideRepository.combineRides]. The plan's list order is the new ride's [RideDay.dayIndex] order.
 */
sealed interface CombineDay {
    /** The sole TRAVEL [RideDay] of the single-day ride [sourceRideId], copied into the new ride. */
    data class Travel(val sourceRideId: Long) : CombineDay

    /** A [RideDayType.NOT_TRAVEL] day spent at [placeName] on [date]. */
    data class Rest(val date: Long, val placeName: String?) : CombineDay
}
