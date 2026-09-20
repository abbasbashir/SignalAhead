package com.signalahead.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Dao interface SignalDao {
    @Query("SELECT * FROM RouteSpot ORDER BY lastObserved DESC") fun routeSpots():Flow<List<RouteSpot>>
    @Query("SELECT * FROM RouteSpot") suspend fun allRouteSpots():List<RouteSpot>
    @Query("SELECT * FROM RouteSpot WHERE network=:network") suspend fun networkSpots(network:String):List<RouteSpot>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putSpot(spot:RouteSpot)
    @Query("UPDATE RouteSpot SET name=:name WHERE id=:id") suspend fun nameSpot(id:String,name:String)
    @Query("UPDATE RouteSpot SET muted=:muted WHERE id=:id") suspend fun muteSpot(id:String,muted:Boolean)
    @Query("UPDATE RouteSpot SET lastWarned=:at WHERE id=:id") suspend fun warnSpot(id:String,at:Long)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun routeVote(vote:RouteVote)
    @Query("SELECT * FROM RouteVote WHERE spotId=:id AND at>=:cutoff ORDER BY at") suspend fun routeVotes(id:String,cutoff:Long):List<RouteVote>
    @Query("SELECT * FROM RouteVote") suspend fun allRouteVotes():List<RouteVote>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun summary(summary:JourneySummary)
    @Query("SELECT * FROM JourneySummary ORDER BY started DESC") fun summaries():Flow<List<JourneySummary>>
    @Query("SELECT * FROM JourneySummary") suspend fun allSummaries():List<JourneySummary>
    @Query("UPDATE JourneySummary SET status='INTERRUPTED' WHERE status IN ('ACTIVE','PAUSED')") suspend fun markInterrupted()
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun alert(alert:AlertEvent)
    @Query("SELECT * FROM AlertEvent ORDER BY at DESC") fun alerts():Flow<List<AlertEvent>>
    @Query("SELECT * FROM AlertEvent") suspend fun allAlerts():List<AlertEvent>
    @Query("SELECT * FROM AlertEvent WHERE journeyId=:journey") suspend fun journeyAlerts(journey:String):List<AlertEvent>
    @Query("UPDATE AlertEvent SET feedback=:feedback WHERE id=:id") suspend fun feedback(id:String,feedback:String)
    @Query("UPDATE AlertEvent SET outcome=:outcome WHERE id=:id") suspend fun outcome(id:String,outcome:String)
    @Query("DELETE FROM RouteSpot") suspend fun deleteRouteSpots()
    @Query("DELETE FROM RouteVote") suspend fun deleteRouteVotes()
    @Query("DELETE FROM JourneySummary") suspend fun deleteSummaries()
    @Query("DELETE FROM AlertEvent") suspend fun deleteAlerts()
    @Query("SELECT * FROM Journey") suspend fun allJourneys():List<Journey>
    @Query("SELECT * FROM PlaceVote") suspend fun allPlaceVotes():List<PlaceVote>
    @Query("DELETE FROM RouteVote WHERE at<:cutoff") suspend fun pruneRouteVotes(cutoff:Long)
    @Query("DELETE FROM AlertEvent WHERE at<:cutoff") suspend fun pruneAlerts(cutoff:Long)
    @Query("SELECT * FROM WeakZone WHERE cellKey=:key") suspend fun zone(key:String):WeakZone?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun vote(vote:PlaceVote)
    @Query("SELECT * FROM PlaceVote WHERE cellKey=:key AND at>=:cutoff ORDER BY at DESC LIMIT 10") suspend fun votes(key:String,cutoff:Long):List<PlaceVote>
    @Query("DELETE FROM PlaceVote") suspend fun deleteVotes()
    @Query("DELETE FROM PlaceVote WHERE at<:cutoff") suspend fun deleteOldVotes(cutoff:Long)
    @Query("UPDATE WeakZone SET warningSuppressed=:muted WHERE cellKey=:key") suspend fun setMuted(key:String,muted:Boolean)
    @Query("SELECT COUNT(*) FROM Observation") fun observationCount():Flow<Int>
    @Insert suspend fun insertJourney(journey: Journey)
    @Query("UPDATE Journey SET endedAt=:endedAt WHERE id=:id") suspend fun endJourney(id: String, endedAt: Long)
    @Insert suspend fun insertObservation(observation: Observation)
    @Query("SELECT * FROM WeakZone ORDER BY confidence DESC") fun observeZones(): Flow<List<WeakZone>>
    @Query("SELECT * FROM WeakZone WHERE warningSuppressed=0") suspend fun activeZones(): List<WeakZone>
    @Query("SELECT * FROM WeakZone ORDER BY lastObserved DESC") suspend fun exportZones(): List<WeakZone>
    @Query("SELECT * FROM Observation ORDER BY timestamp DESC") suspend fun exportObservations(): List<Observation>
    @Query("SELECT COUNT(DISTINCT journeyId) FROM Observation WHERE kind IN ('VALID_SIGNAL','LEVEL_ONLY') AND signalLevel <= 1 AND latitude BETWEEN :minLat AND :maxLat AND longitude BETWEEN :minLng AND :maxLng") suspend fun weakJourneyCount(minLat:Double,maxLat:Double,minLng:Double,maxLng:Double):Int
    @Query("SELECT COUNT(*) FROM Observation WHERE kind IN ('VALID_SIGNAL','LEVEL_ONLY') AND signalLevel <= 1 AND latitude BETWEEN :minLat AND :maxLat AND longitude BETWEEN :minLng AND :maxLng") suspend fun weakObservationCount(minLat:Double,maxLat:Double,minLng:Double,maxLng:Double):Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertZone(zone: WeakZone)
    @Query("UPDATE WeakZone SET warningSuppressed=1 WHERE cellKey=:key") suspend fun suppressZone(key:String)
    @Query("UPDATE WeakZone SET lastWarnedAt=:at WHERE cellKey=:key") suspend fun markWarned(key:String, at:Long)
    @Query("DELETE FROM Observation WHERE timestamp < :cutoff") suspend fun deleteOldRaw(cutoff:Long)
    @Query("DELETE FROM Observation") suspend fun deleteObservations()
    @Query("DELETE FROM WeakZone") suspend fun deleteZones()
    @Query("DELETE FROM Journey") suspend fun deleteJourneys()
}

@Database(entities=[Journey::class,Observation::class,WeakZone::class,PlaceVote::class,RouteSpot::class,RouteVote::class,JourneySummary::class,AlertEvent::class], version=3, exportSchema=true)
abstract class AppDatabase: RoomDatabase() {
    abstract fun dao():SignalDao
    companion object {
        val MIGRATION_2_3=object:Migration(2,3){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("CREATE TABLE IF NOT EXISTS RouteSpot (id TEXT NOT NULL PRIMARY KEY, network TEXT NOT NULL, startLat REAL NOT NULL, startLng REAL NOT NULL, endLat REAL NOT NULL, endLng REAL NOT NULL, direction REAL NOT NULL, quality TEXT NOT NULL, confidence REAL NOT NULL, journeys INTEGER NOT NULL, lastObserved INTEGER NOT NULL, name TEXT NOT NULL, muted INTEGER NOT NULL, lastWarned INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS RouteVote (spotId TEXT NOT NULL, journeyId TEXT NOT NULL, quality TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(spotId,journeyId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS JourneySummary (id TEXT NOT NULL PRIMARY KEY, started INTEGER NOT NULL, ended INTEGER NOT NULL, samples INTEGER NOT NULL, weakSamples INTEGER NOT NULL, gaps INTEGER NOT NULL, alerts INTEGER NOT NULL, pausedMillis INTEGER NOT NULL, status TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS AlertEvent (id TEXT NOT NULL PRIMARY KEY, journeyId TEXT NOT NULL, spotId TEXT NOT NULL, at INTEGER NOT NULL, distance REAL NOT NULL, leadSeconds REAL NOT NULL, delivered INTEGER NOT NULL, feedback TEXT NOT NULL, outcome TEXT NOT NULL)")
            }
        }
        val MIGRATION_1_2=object:Migration(1,2){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("ALTER TABLE WeakZone ADD COLUMN quality TEXT NOT NULL DEFAULT 'WEAK'")
                db.execSQL("CREATE TABLE IF NOT EXISTS PlaceVote (cellKey TEXT NOT NULL, journeyId TEXT NOT NULL, quality TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(cellKey, journeyId))")
            }
        }
        fun create(context:Context)=Room.databaseBuilder(context,AppDatabase::class.java,"signal-ahead.db").addMigrations(MIGRATION_1_2,MIGRATION_2_3).build()
    }
}
