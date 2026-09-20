package com.signalahead.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Dao interface SignalDao {
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

@Database(entities=[Journey::class,Observation::class,WeakZone::class,PlaceVote::class], version=2, exportSchema=true)
abstract class AppDatabase: RoomDatabase() {
    abstract fun dao():SignalDao
    companion object {
        val MIGRATION_1_2=object:Migration(1,2){
            override fun migrate(db:SupportSQLiteDatabase){
                db.execSQL("ALTER TABLE WeakZone ADD COLUMN quality TEXT NOT NULL DEFAULT 'WEAK'")
                db.execSQL("CREATE TABLE IF NOT EXISTS PlaceVote (cellKey TEXT NOT NULL, journeyId TEXT NOT NULL, quality TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(cellKey, journeyId))")
            }
        }
        fun create(context:Context)=Room.databaseBuilder(context,AppDatabase::class.java,"signal-ahead.db").addMigrations(MIGRATION_1_2).build()
    }
}
