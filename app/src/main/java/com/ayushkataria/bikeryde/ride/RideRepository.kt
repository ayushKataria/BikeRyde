package com.ayushkataria.bikeryde.ride

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ids returned once a ride (and its first [RideDay]) have been created. */
data class RideSession(val rideId: Long, val rideDayId: Long)

/** Id and [RideDay.dayIndex] of a newly-started day within an existing multi-day trip. */
data class RideDaySession(val rideDayId: Long, val dayIndex: Int)

/**
 * Owns all reads/writes of ride data. Every method does its SQLite work on [Dispatchers.IO];
 * callers (the tracking service, the activity) stay on the main thread.
 *
 * Storage mirrors the design doc's Ride -> RideDay -> {Stop, GpsPoint} shape. A single-day ride
 * gets exactly one RideDay (dayIndex 0), created alongside the ride itself.
 */
class RideRepository(context: Context) {

    private val dbHelper = RideDbHelper.getInstance(context)

    /** Starts a new single-day ride: creates the [Ride], its sole [RideDay], and a START [Stop]. */
    suspend fun startRide(startTime: Long, lat: Double?, lng: Double?, placeName: String?): RideSession =
        withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            val rideId = db.insert(
                "ride",
                null,
                ContentValues().apply {
                    put("type", RideType.SINGLE_DAY.name)
                    put("start_time", startTime)
                    put("status", RideStatus.TRACKING.name)
                    put("total_distance_m", 0.0)
                    put("total_duration_s", 0L)
                }
            )
            val rideDayId = insertTravelDay(db, rideId, 0, startTime, lat, lng, placeName)
            RideSession(rideId, rideDayId)
        }

    /**
     * Starts a new multi-day trip: creates the [Ride] (type [RideType.MULTI_DAY]), its first
     * [RideDay] (index 0), and a START [Stop] — the "Start Trip" action from design doc §5.2.
     */
    suspend fun startTrip(startTime: Long, lat: Double?, lng: Double?, placeName: String?): RideSession =
        withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            val rideId = db.insert(
                "ride",
                null,
                ContentValues().apply {
                    put("type", RideType.MULTI_DAY.name)
                    put("start_time", startTime)
                    put("status", RideStatus.TRACKING.name)
                    put("total_distance_m", 0.0)
                    put("total_duration_s", 0L)
                }
            )
            val rideDayId = insertTravelDay(db, rideId, nextDayIndex(db, rideId), startTime, lat, lng, placeName)
            RideSession(rideId, rideDayId)
        }

    /**
     * Begins the next day of an already-started trip — a new TRAVEL [RideDay] (the next available
     * [RideDay.dayIndex]) plus a START [Stop]. The trip's [Ride] row already exists, so unlike
     * [startTrip] there's nothing to create there.
     */
    suspend fun startNextDay(rideId: Long, startTime: Long, lat: Double?, lng: Double?, placeName: String?): RideDaySession =
        withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            val dayIndex = nextDayIndex(db, rideId)
            val rideDayId = insertTravelDay(db, rideId, dayIndex, startTime, lat, lng, placeName)
            RideDaySession(rideDayId, dayIndex)
        }

    /**
     * Closes out today's [RideDay] without ending the trip — design doc §5.2's "Pause day (end of
     * riding for that day)". The [Ride] itself stays [RideStatus.TRACKING]; the rider taps
     * [startNextDay] whenever they're ready to continue, which may be the next morning.
     */
    suspend fun finishDay(
        rideDayId: Long,
        timestamp: Long,
        lat: Double?,
        lng: Double?,
        placeName: String?,
        distanceM: Double,
        durationS: Long
    ) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        finalizeDay(db, rideDayId, timestamp, placeName, distanceM, durationS)
        insertStop(db, rideDayId, RideEventAction.END, timestamp, lat, lng, placeName)
    }

    /**
     * Ends the whole multi-day trip: finalizes today's still-open [RideDay] (same as [finishDay])
     * and marks the [Ride] itself [RideStatus.COMPLETED], with totals summed fresh across every
     * [RideDay] — not carried over in memory — since the tracking service may have been recreated
     * between days and so never held a running total spanning the whole trip.
     */
    suspend fun endTrip(
        rideId: Long,
        rideDayId: Long,
        timestamp: Long,
        lat: Double?,
        lng: Double?,
        placeName: String?,
        todayDistanceM: Double,
        todayDurationS: Long
    ) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        finalizeDay(db, rideDayId, timestamp, placeName, todayDistanceM, todayDurationS)
        insertStop(db, rideDayId, RideEventAction.END, timestamp, lat, lng, placeName)

        val (totalDistanceM, totalDurationS) = tripTotals(db, rideId)
        db.update(
            "ride",
            ContentValues().apply {
                put("end_time", timestamp)
                put("status", RideStatus.COMPLETED.name)
                put("total_distance_m", totalDistanceM)
                put("total_duration_s", totalDurationS)
            },
            "id = ?",
            arrayOf(rideId.toString())
        )
    }

    /**
     * Ends a multi-day trip when no day is currently open — the rider taps "End Trip" between
     * days rather than right after finishing one. There's no in-progress day to finalize here
     * (unlike [endTrip]), so this is just a status flip with totals summed across the days already
     * on record.
     */
    suspend fun completeTripWithNoOpenDay(rideId: Long, timestamp: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val (totalDistanceM, totalDurationS) = tripTotals(db, rideId)
        db.update(
            "ride",
            ContentValues().apply {
                put("end_time", timestamp)
                put("status", RideStatus.COMPLETED.name)
                put("total_distance_m", totalDistanceM)
                put("total_duration_s", totalDurationS)
            },
            "id = ?",
            arrayOf(rideId.toString())
        )
    }

    /**
     * Records a [RideDayType.NOT_TRAVEL] day within a multi-day [Ride] — a rest or tourism day
     * spent at the current stop, with no GPS track or [Stop]s of its own.
     */
    suspend fun addNotTravelDay(rideId: Long, date: Long, placeName: String?): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        insertNotTravelDay(db, rideId, nextDayIndex(db, rideId), date, placeName)
    }

    /**
     * Creates a new, already-[RideStatus.COMPLETED] multi-day [Ride] from completed single-day
     * rides plus rest days, laid out in [plan] order. Each [CombineDay.Travel] entry's sole
     * [RideDay] is *copied* — along with its [Stop]s and [GpsPoint]s — so the source rides stay in
     * history untouched (the caller deletes them via [deleteRide] if the rider asked to). Totals
     * are summed fresh across the new ride's days, same as [endTrip].
     *
     * @return the new ride's id.
     */
    suspend fun combineRides(plan: List<CombineDay>, title: String?): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val sourceDays = plan.filterIsInstance<CombineDay.Travel>().associate { travel ->
                travel.sourceRideId to requireNotNull(
                    db.query(
                        "ride_day", null, "ride_id = ?", arrayOf(travel.sourceRideId.toString()),
                        null, null, "day_index ASC", "1"
                    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRideDay() else null }
                ) { "Ride ${travel.sourceRideId} has no day to combine" }
            }
            require(sourceDays.isNotEmpty()) { "A combined ride needs at least one travel day" }
            val startTime = sourceDays.values.minOf { it.startTime }
            val endTime = sourceDays.values.maxOf { it.endTime ?: it.startTime }

            val rideId = db.insert(
                "ride",
                null,
                ContentValues().apply {
                    put("type", RideType.MULTI_DAY.name)
                    put("start_time", startTime)
                    put("end_time", endTime)
                    put("status", RideStatus.COMPLETED.name)
                    put("total_distance_m", 0.0)
                    put("total_duration_s", 0L)
                    put("title", title?.trim()?.takeIf { it.isNotEmpty() })
                }
            )

            plan.forEachIndexed { dayIndex, day ->
                when (day) {
                    is CombineDay.Rest -> insertNotTravelDay(db, rideId, dayIndex, day.date, day.placeName)
                    is CombineDay.Travel -> copyTravelDay(db, sourceDays.getValue(day.sourceRideId), rideId, dayIndex)
                }
            }

            val (totalDistanceM, totalDurationS) = tripTotals(db, rideId)
            db.update(
                "ride",
                ContentValues().apply {
                    put("total_distance_m", totalDistanceM)
                    put("total_duration_s", totalDurationS)
                },
                "id = ?",
                arrayOf(rideId.toString())
            )
            db.setTransactionSuccessful()
            rideId
        } finally {
            db.endTransaction()
        }
    }

    /** Completed single-day rides, oldest first — the rides that can be combined via [combineRides]. */
    suspend fun getCompletedSingleDayRides(): List<Ride> = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            "ride",
            null,
            "type = ? AND status = ?",
            arrayOf(RideType.SINGLE_DAY.name, RideStatus.COMPLETED.name),
            null,
            null,
            "start_time ASC"
        ).use { cursor ->
            val rides = mutableListOf<Ride>()
            while (cursor.moveToNext()) rides += cursor.toRide()
            rides
        }
    }

    /** The trip's distance/duration so far, summed across every already-finalized [RideDay] — a
     * day still open (in progress) contributes 0 here until it's closed via [finishDay]/[endTrip],
     * so a caller showing a live "trip so far" figure adds the current day's live counters on top. */
    suspend fun getTripTotalsSoFar(rideId: Long): Pair<Double, Long> = withContext(Dispatchers.IO) {
        tripTotals(dbHelper.readableDatabase, rideId)
    }

    /** The most recent multi-day trip still in TRACKING or PAUSED state, if any — lets the multi-day
     * screen re-derive where a trip stands from the database alone, since a trip can easily span an
     * app restart (or several) between days. */
    suspend fun getActiveTrip(): Ride? = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            "ride",
            null,
            "type = ? AND status IN (?, ?)",
            arrayOf(RideType.MULTI_DAY.name, RideStatus.TRACKING.name, RideStatus.PAUSED.name),
            null,
            null,
            "id DESC",
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toRide() else null }
    }

    suspend fun pauseRide(
        rideId: Long,
        rideDayId: Long,
        timestamp: Long,
        lat: Double?,
        lng: Double?,
        placeName: String?
    ) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        setStatus(db, rideId, RideStatus.PAUSED)
        insertStop(db, rideDayId, RideEventAction.PAUSE, timestamp, lat, lng, placeName)
    }

    suspend fun resumeRide(
        rideId: Long,
        rideDayId: Long,
        timestamp: Long,
        lat: Double?,
        lng: Double?,
        placeName: String?
    ) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        setStatus(db, rideId, RideStatus.TRACKING)
        insertStop(db, rideDayId, RideEventAction.RESUME, timestamp, lat, lng, placeName)
    }

    suspend fun endRide(
        rideId: Long,
        rideDayId: Long,
        timestamp: Long,
        lat: Double?,
        lng: Double?,
        placeName: String?,
        totalDistanceM: Double,
        totalDurationS: Long
    ) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.update(
            "ride",
            ContentValues().apply {
                put("end_time", timestamp)
                put("status", RideStatus.COMPLETED.name)
                put("total_distance_m", totalDistanceM)
                put("total_duration_s", totalDurationS)
            },
            "id = ?",
            arrayOf(rideId.toString())
        )
        finalizeDay(db, rideDayId, timestamp, placeName, totalDistanceM, totalDurationS)
        insertStop(db, rideDayId, RideEventAction.END, timestamp, lat, lng, placeName)
    }

    suspend fun addGpsPoint(
        rideDayId: Long,
        timestamp: Long,
        lat: Double,
        lng: Double,
        elevation: Double?,
        speed: Float?
    ) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.insert(
            "gps_point",
            null,
            ContentValues().apply {
                put("ride_day_id", rideDayId)
                put("timestamp", timestamp)
                put("lat", lat)
                put("lng", lng)
                putNullableDouble("elevation", elevation)
                if (speed == null) putNull("speed") else put("speed", speed)
            }
        )
    }

    /** All logged GPS fixes for a ride, in recording order — the route to render on [RouteMapView]. */
    suspend fun getRoutePoints(rideId: Long): List<RidePoint> = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.rawQuery(
            """
            SELECT gps_point.lat, gps_point.lng, gps_point.speed
            FROM gps_point
            JOIN ride_day ON gps_point.ride_day_id = ride_day.id
            WHERE ride_day.ride_id = ?
            ORDER BY ride_day.day_index ASC, gps_point.id ASC
            """.trimIndent(),
            arrayOf(rideId.toString())
        ).use { cursor ->
            val points = mutableListOf<RidePoint>()
            while (cursor.moveToNext()) {
                points += RidePoint(
                    lat = cursor.getDouble(0),
                    lng = cursor.getDouble(1),
                    speedMps = if (cursor.isNull(2)) null else cursor.getFloat(2)
                )
            }
            points
        }
    }

    /** The start/pause/resume/end control actions for a ride, in chronological order — the route's stop markers. */
    suspend fun getEvents(rideId: Long): List<RideEvent> = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.rawQuery(
            """
            SELECT stop.id, stop."action", stop.timestamp, stop.lat, stop.lng, stop.place_name
            FROM stop
            JOIN ride_day ON stop.ride_day_id = ride_day.id
            WHERE ride_day.ride_id = ?
            ORDER BY ride_day.day_index ASC, stop.id ASC
            """.trimIndent(),
            arrayOf(rideId.toString())
        ).use { cursor ->
            val events = mutableListOf<RideEvent>()
            while (cursor.moveToNext()) {
                events += RideEvent(
                    id = cursor.getLong(0),
                    action = RideEventAction.valueOf(cursor.getString(1)),
                    timestamp = cursor.getLong(2),
                    lat = if (cursor.isNull(3)) null else cursor.getDouble(3),
                    lng = if (cursor.isNull(4)) null else cursor.getDouble(4),
                    placeName = cursor.getString(5)
                )
            }
            events
        }
    }

    /** A single day's logged GPS fixes, in recording order — used to render a multi-day ride's
     * video/image one [com.ayushkataria.bikeryde.media.RenderDay] per actual [RideDay]. */
    suspend fun getRoutePointsForDay(rideDayId: Long): List<RidePoint> = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            "gps_point",
            arrayOf("lat", "lng", "speed"),
            "ride_day_id = ?",
            arrayOf(rideDayId.toString()),
            null,
            null,
            "id ASC"
        ).use { cursor ->
            val points = mutableListOf<RidePoint>()
            while (cursor.moveToNext()) {
                points += RidePoint(
                    lat = cursor.getDouble(0),
                    lng = cursor.getDouble(1),
                    speedMps = if (cursor.isNull(2)) null else cursor.getFloat(2)
                )
            }
            points
        }
    }

    /** A single day's start/pause/resume/end control actions, in chronological order. */
    suspend fun getEventsForDay(rideDayId: Long): List<RideEvent> = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.rawQuery(
            """
            SELECT stop.id, stop."action", stop.timestamp, stop.lat, stop.lng, stop.place_name
            FROM stop
            WHERE stop.ride_day_id = ?
            ORDER BY stop.id ASC
            """.trimIndent(),
            arrayOf(rideDayId.toString())
        ).use { cursor ->
            val events = mutableListOf<RideEvent>()
            while (cursor.moveToNext()) {
                events += RideEvent(
                    id = cursor.getLong(0),
                    action = RideEventAction.valueOf(cursor.getString(1)),
                    timestamp = cursor.getLong(2),
                    lat = if (cursor.isNull(3)) null else cursor.getDouble(3),
                    lng = if (cursor.isNull(4)) null else cursor.getDouble(4),
                    placeName = cursor.getString(5)
                )
            }
            events
        }
    }

    /** The fastest single GPS fix recorded for a ride, in m/s — null if no fix reported a speed. */
    suspend fun getMaxSpeedMps(rideId: Long): Float? = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.rawQuery(
            """
            SELECT MAX(gps_point.speed)
            FROM gps_point
            JOIN ride_day ON gps_point.ride_day_id = ride_day.id
            WHERE ride_day.ride_id = ?
            """.trimIndent(),
            arrayOf(rideId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getFloat(0) else null
        }
    }

    /** The most recent ride still in TRACKING or PAUSED state, if any — used to recover UI state. */
    suspend fun getActiveRide(): Ride? = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            "ride",
            null,
            "status IN (?, ?)",
            arrayOf(RideStatus.TRACKING.name, RideStatus.PAUSED.name),
            null,
            null,
            "id DESC",
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toRide() else null
        }
    }

    /** Whether any ride has ever been recorded — used to skip onboarding on subsequent launches. */
    suspend fun hasAnyRides(): Boolean = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            "ride", arrayOf("id"), null, null, null, null, null, "1"
        ).use { it.moveToFirst() }
    }

    /** Completed rides, most recent first — the ride history list. */
    suspend fun getCompletedRides(): List<Ride> = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            "ride",
            null,
            "status = ?",
            arrayOf(RideStatus.COMPLETED.name),
            null,
            null,
            "start_time DESC"
        ).use { cursor ->
            val rides = mutableListOf<Ride>()
            while (cursor.moveToNext()) rides += cursor.toRide()
            rides
        }
    }

    suspend fun getRide(rideId: Long): Ride? = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            "ride", null, "id = ?", arrayOf(rideId.toString()), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toRide() else null
        }
    }

    /** All days (travel and not-travel) for a ride, in day order. */
    suspend fun getRideDays(rideId: Long): List<RideDay> = withContext(Dispatchers.IO) {
        dbHelper.readableDatabase.query(
            "ride_day",
            null,
            "ride_id = ?",
            arrayOf(rideId.toString()),
            null,
            null,
            "day_index ASC"
        ).use { cursor ->
            val days = mutableListOf<RideDay>()
            while (cursor.moveToNext()) {
                days += cursor.toRideDay()
            }
            days
        }
    }

    /** Sets or clears (pass null or blank) a ride's user-given display name. */
    suspend fun renameRide(rideId: Long, title: String?) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.update(
            "ride",
            ContentValues().apply { put("title", title?.trim()?.takeIf { it.isNotEmpty() }) },
            "id = ?",
            arrayOf(rideId.toString())
        )
    }

    /**
     * Permanently changes a [Stop]'s recorded place name — unlike the render customize screen's
     * per-stop name override (which only relabels that one rendered image/video), this rewrites
     * the actual `stop` row, so the new name shows up everywhere the stop is displayed from now on.
     * If the stop is a START or END, its [RideDay]'s denormalized start/end place name is kept in
     * sync too, since the multi-day day list and render captions read from there, not from `stop`.
     */
    suspend fun renameStop(stopId: Long, placeName: String?) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val trimmed = placeName?.trim()?.takeIf { it.isNotEmpty() }
        db.update(
            "stop",
            ContentValues().apply { put("place_name", trimmed) },
            "id = ?",
            arrayOf(stopId.toString())
        )
        db.rawQuery(
            "SELECT ride_day_id, \"action\" FROM stop WHERE id = ?",
            arrayOf(stopId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val rideDayId = cursor.getLong(0)
                val column = when (RideEventAction.valueOf(cursor.getString(1))) {
                    RideEventAction.START -> "start_place_name"
                    RideEventAction.END -> "end_place_name"
                    RideEventAction.PAUSE, RideEventAction.RESUME -> null
                }
                if (column != null) {
                    db.update(
                        "ride_day",
                        ContentValues().apply { put(column, trimmed) },
                        "id = ?",
                        arrayOf(rideDayId.toString())
                    )
                }
            }
        }
    }

    /**
     * Permanently deletes a ride and everything that hangs off it — its days, stops, GPS points,
     * and render records (plus their output files on disk, best-effort). There's no foreign-key
     * cascade in this SQLite schema, so each child table is cleared explicitly in one transaction.
     */
    suspend fun deleteRide(rideId: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val renderPaths = db.query(
            "render", arrayOf("file_path"), "ride_id = ?", arrayOf(rideId.toString()), null, null, null
        ).use { cursor ->
            val paths = mutableListOf<String>()
            while (cursor.moveToNext()) {
                cursor.getString(0)?.let { paths += it }
            }
            paths
        }

        db.beginTransaction()
        try {
            db.execSQL(
                "DELETE FROM gps_point WHERE ride_day_id IN (SELECT id FROM ride_day WHERE ride_id = ?)",
                arrayOf(rideId)
            )
            db.execSQL(
                "DELETE FROM stop WHERE ride_day_id IN (SELECT id FROM ride_day WHERE ride_id = ?)",
                arrayOf(rideId)
            )
            db.delete("ride_day", "ride_id = ?", arrayOf(rideId.toString()))
            db.delete("render", "ride_id = ?", arrayOf(rideId.toString()))
            db.delete("ride", "id = ?", arrayOf(rideId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        renderPaths.forEach { path -> runCatching { java.io.File(path).delete() } }
    }

    /** One past the highest [RideDay.dayIndex] recorded for a ride so far, or 0 for its first day. */
    private fun nextDayIndex(db: SQLiteDatabase, rideId: Long): Int = db.rawQuery(
        "SELECT MAX(day_index) FROM ride_day WHERE ride_id = ?", arrayOf(rideId.toString())
    ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) + 1 else 0 }

    private fun insertTravelDay(
        db: SQLiteDatabase,
        rideId: Long,
        dayIndex: Int,
        startTime: Long,
        lat: Double?,
        lng: Double?,
        placeName: String?
    ): Long {
        val rideDayId = db.insert(
            "ride_day",
            null,
            ContentValues().apply {
                put("ride_id", rideId)
                put("day_index", dayIndex)
                put("day_type", RideDayType.TRAVEL.name)
                put("start_time", startTime)
                put("start_place_name", placeName)
                put("distance_km", 0.0)
                put("duration_s", 0L)
            }
        )
        insertStop(db, rideDayId, RideEventAction.START, startTime, lat, lng, placeName)
        return rideDayId
    }

    private fun insertNotTravelDay(
        db: SQLiteDatabase,
        rideId: Long,
        dayIndex: Int,
        date: Long,
        placeName: String?
    ): Long = db.insert(
        "ride_day",
        null,
        ContentValues().apply {
            put("ride_id", rideId)
            put("day_index", dayIndex)
            put("day_type", RideDayType.NOT_TRAVEL.name)
            put("start_time", date)
            put("end_time", date)
            put("start_place_name", placeName)
            put("end_place_name", placeName)
            put("distance_km", 0.0)
            put("duration_s", 0L)
        }
    )

    /** Copies [source] — and every [Stop] and [GpsPoint] hanging off it, in recording order — into
     * [rideId] as its day [dayIndex]. Used by [combineRides]. */
    private fun copyTravelDay(db: SQLiteDatabase, source: RideDay, rideId: Long, dayIndex: Int) {
        val newDayId = db.insert(
            "ride_day",
            null,
            ContentValues().apply {
                put("ride_id", rideId)
                put("day_index", dayIndex)
                put("day_type", source.dayType.name)
                put("start_time", source.startTime)
                if (source.endTime == null) putNull("end_time") else put("end_time", source.endTime)
                put("start_place_name", source.startPlaceName)
                put("end_place_name", source.endPlaceName)
                put("distance_km", source.distanceKm)
                put("duration_s", source.durationS)
            }
        )
        db.execSQL(
            """
            INSERT INTO stop (ride_day_id, "action", timestamp, lat, lng, place_name)
            SELECT ?, "action", timestamp, lat, lng, place_name FROM stop WHERE ride_day_id = ? ORDER BY id ASC
            """.trimIndent(),
            arrayOf(newDayId, source.id)
        )
        db.execSQL(
            """
            INSERT INTO gps_point (ride_day_id, timestamp, lat, lng, elevation, speed)
            SELECT ?, timestamp, lat, lng, elevation, speed FROM gps_point WHERE ride_day_id = ? ORDER BY id ASC
            """.trimIndent(),
            arrayOf(newDayId, source.id)
        )
    }

    private fun finalizeDay(
        db: SQLiteDatabase,
        rideDayId: Long,
        timestamp: Long,
        placeName: String?,
        distanceM: Double,
        durationS: Long
    ) {
        db.update(
            "ride_day",
            ContentValues().apply {
                put("end_time", timestamp)
                put("end_place_name", placeName)
                put("distance_km", distanceM / 1000.0)
                put("duration_s", durationS)
            },
            "id = ?",
            arrayOf(rideDayId.toString())
        )
    }

    private fun tripTotals(db: SQLiteDatabase, rideId: Long): Pair<Double, Long> = db.rawQuery(
        "SELECT COALESCE(SUM(distance_km), 0), COALESCE(SUM(duration_s), 0) FROM ride_day WHERE ride_id = ?",
        arrayOf(rideId.toString())
    ).use { cursor ->
        cursor.moveToFirst()
        (cursor.getDouble(0) * 1000.0) to cursor.getLong(1)
    }

    private fun setStatus(db: SQLiteDatabase, rideId: Long, status: RideStatus) {
        db.update(
            "ride",
            ContentValues().apply { put("status", status.name) },
            "id = ?",
            arrayOf(rideId.toString())
        )
    }

    private fun insertStop(
        db: SQLiteDatabase,
        rideDayId: Long,
        action: RideEventAction,
        timestamp: Long,
        lat: Double?,
        lng: Double?,
        placeName: String?
    ) {
        db.insert(
            "stop",
            null,
            ContentValues().apply {
                put("ride_day_id", rideDayId)
                put("action", action.name)
                put("timestamp", timestamp)
                putNullableDouble("lat", lat)
                putNullableDouble("lng", lng)
                put("place_name", placeName)
            }
        )
    }

    private fun Cursor.toRide(): Ride = Ride(
        id = getLong(getColumnIndexOrThrow("id")),
        type = RideType.valueOf(getString(getColumnIndexOrThrow("type"))),
        startTime = getLong(getColumnIndexOrThrow("start_time")),
        endTime = if (isNull(getColumnIndexOrThrow("end_time"))) null else getLong(getColumnIndexOrThrow("end_time")),
        status = RideStatus.valueOf(getString(getColumnIndexOrThrow("status"))),
        totalDistanceM = getDouble(getColumnIndexOrThrow("total_distance_m")),
        totalDurationS = getLong(getColumnIndexOrThrow("total_duration_s")),
        title = getString(getColumnIndexOrThrow("title"))
    )

    private fun Cursor.toRideDay(): RideDay = RideDay(
        id = getLong(getColumnIndexOrThrow("id")),
        rideId = getLong(getColumnIndexOrThrow("ride_id")),
        dayIndex = getInt(getColumnIndexOrThrow("day_index")),
        dayType = RideDayType.valueOf(getString(getColumnIndexOrThrow("day_type"))),
        startTime = getLong(getColumnIndexOrThrow("start_time")),
        endTime = if (isNull(getColumnIndexOrThrow("end_time"))) null else getLong(getColumnIndexOrThrow("end_time")),
        startPlaceName = getString(getColumnIndexOrThrow("start_place_name")),
        endPlaceName = getString(getColumnIndexOrThrow("end_place_name")),
        distanceKm = getDouble(getColumnIndexOrThrow("distance_km")),
        durationS = getLong(getColumnIndexOrThrow("duration_s"))
    )
}
