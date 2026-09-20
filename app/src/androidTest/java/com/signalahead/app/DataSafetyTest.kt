package com.signalahead.app

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.signalahead.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DataSafetyTest {
    @Test fun migrateV2AndRestoreRoundTrip()=runBlocking<Unit>{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="migration-test.db"
        context.deleteDatabase(name)
        val file=context.getDatabasePath(name);file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file,null).use{db->
            db.execSQL("CREATE TABLE Journey (id TEXT NOT NULL PRIMARY KEY, startedAt INTEGER NOT NULL, endedAt INTEGER)")
            db.execSQL("CREATE TABLE Observation (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, journeyId TEXT NOT NULL, timestamp INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, accuracyMetres REAL NOT NULL, speedMps REAL, bearingDegrees REAL, signalDbm INTEGER, signalLevel INTEGER, networkType TEXT, dataValidated INTEGER, kind TEXT NOT NULL, observationConfidence REAL NOT NULL, locationSource TEXT NOT NULL, signalSource TEXT, signalTimestamp INTEGER, retentionClass TEXT NOT NULL)")
            db.execSQL("CREATE TABLE WeakZone (cellKey TEXT NOT NULL PRIMARY KEY, centreLat REAL NOT NULL, centreLng REAL NOT NULL, radiusMetres REAL NOT NULL, distinctJourneys INTEGER NOT NULL, observations INTEGER NOT NULL, confidence REAL NOT NULL, status TEXT NOT NULL, lastObserved INTEGER NOT NULL, warningSuppressed INTEGER NOT NULL, lastWarnedAt INTEGER, quality TEXT NOT NULL DEFAULT 'WEAK')")
            db.execSQL("CREATE TABLE PlaceVote (cellKey TEXT NOT NULL, journeyId TEXT NOT NULL, quality TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(cellKey,journeyId))")
            db.execSQL("INSERT INTO Journey VALUES ('old-trip',123456789,NULL)")
            db.version=2
        }
        val db=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(AppDatabase.MIGRATION_2_3).build()
        try{
            assertEquals("old-trip",db.dao().allJourneys().single().id)
            val z=RouteSpot("test-spot","a".repeat(64),-33.0,151.0,-32.99,151.0,0.0,"WEAK",.9,3,123456789,"My road")
            db.dao().putSpot(z)
            val backup=File(context.cacheDir,"round-trip.json")
            BackupManager.save(context,db,Uri.fromFile(backup))
            val read=BackupManager.read(context,Uri.fromFile(backup))
            db.dao().deleteRouteSpots()
            BackupManager.restore(context,db,read)
            assertEquals(z,db.dao().allRouteSpots().single())
            assertEquals(1,db.dao().allJourneys().size)
            try{BackupManager.restore(context,db,read.copy(format=99));fail("Invalid backup accepted")}
            catch(_:IllegalArgumentException){}
            assertEquals(z,db.dao().allRouteSpots().single())
            backup.delete()
        }finally{db.close();context.deleteDatabase(name)}
    }
}
