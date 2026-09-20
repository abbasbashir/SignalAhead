package com.signalahead.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ObservationKind { VALID_SIGNAL, LEVEL_ONLY, CONNECTIVITY_ONLY, UNAVAILABLE, STALE_OR_LOW_CONFIDENCE }
enum class ZoneStatus { UNCONFIRMED, POSSIBLE, CONFIRMED, STALE }

@Entity data class Journey(@PrimaryKey val id: String, val startedAt: Long, val endedAt: Long? = null)

@Entity data class Observation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val journeyId: String,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Float,
    val speedMps: Float?,
    val bearingDegrees: Float?,
    val signalDbm: Int?,
    val signalLevel: Int?,
    val networkType: String?,
    val dataValidated: Boolean?,
    val kind: String,
    val observationConfidence: Double,
    val locationSource: String,
    val signalSource: String?,
    val signalTimestamp: Long?,
    val retentionClass: String = "RAW_30_DAYS"
)

@Entity data class WeakZone(
    @PrimaryKey val cellKey: String,
    val centreLat: Double,
    val centreLng: Double,
    val radiusMetres: Double,
    val distinctJourneys: Int,
    val observations: Int,
    val confidence: Double,
    val status: String,
    val lastObserved: Long,
    val warningSuppressed: Boolean = false,
    val lastWarnedAt: Long? = null
)
