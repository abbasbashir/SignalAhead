package com.signalahead.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface SignalDao {
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

@Database(entities=[Journey::class,Observation::class,WeakZone::class], version=1, exportSchema=true)
abstract class AppDatabase: RoomDatabase() {
    abstract fun dao():SignalDao
    companion object { fun create(context:Context)=Room.databaseBuilder(context,AppDatabase::class.java,"signal-ahead.db").fallbackToDestructiveMigration().build() }
}
