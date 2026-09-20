package com.signalahead.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.google.gson.Gson
import com.signalahead.app.tracking.NetworkContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Backup(
    val format:Int=1,val networkSalt:String,
    val journeys:List<Journey>,val observations:List<Observation>,val legacySpots:List<WeakZone>,
    val legacyVotes:List<PlaceVote>,val spots:List<RouteSpot>,val votes:List<RouteVote>,
    val summaries:List<JourneySummary>,val alerts:List<AlertEvent>
)
object BackupManager {
    suspend fun save(context:Context,db:AppDatabase,uri:Uri)=withContext(Dispatchers.IO){
        val d=db.dao()
        val backup=db.withTransaction{
            Backup(networkSalt=NetworkContext.salt(context),journeys=d.allJourneys(),observations=d.exportObservations(),
                legacySpots=d.exportZones(),legacyVotes=d.allPlaceVotes(),spots=d.allRouteSpots(),votes=d.allRouteVotes(),
                summaries=d.allSummaries(),alerts=d.allAlerts())
        }
        val output=context.contentResolver.openOutputStream(uri)?:error("Cannot open backup destination")
        output.bufferedWriter().use{Gson().toJson(backup,it)}
    }
    suspend fun read(context:Context,uri:Uri):Backup=withContext(Dispatchers.IO){
        val input=context.contentResolver.openInputStream(uri)?:error("Cannot open backup")
        val bytes=input.use{it.readBytesLimited(20*1024*1024)}
        val b=Gson().fromJson(bytes.toString(Charsets.UTF_8),Backup::class.java)?:error("Empty backup")
        validate(b);b
    }
    fun validate(b:Backup){
        require(b.format==1){"Unsupported backup version"}
        require(b.networkSalt.length in 20..80){"Invalid network context"}
        require(b.observations.size<=100000 && b.spots.size<=10000 && b.votes.size<=100000 && b.summaries.size<=20000 && b.alerts.size<=100000){"Backup is too large"}
        fun position(lat:Double,lng:Double){require(lat.isFinite()&&lng.isFinite()&&lat in -90.0..90.0&&lng in -180.0..180.0)}
        b.observations.forEach{position(it.latitude,it.longitude);require(it.timestamp>0&&it.accuracyMetres.isFinite()&&it.accuracyMetres>=0)}
        b.spots.forEach{
            position(it.startLat,it.startLng);position(it.endLat,it.endLng)
            require(it.id.length in 1..100&&it.network.matches(Regex("[a-f0-9]{64}")))
            require(it.name.length<=80&&it.quality in listOf("WEAK","STRONG")&&it.confidence in 0.0..1.0&&it.direction in 0.0..360.0&&it.journeys>=0)
        }
        b.legacySpots.forEach{position(it.centreLat,it.centreLng);require(it.confidence in 0.0..1.0)}
        require(b.spots.map{it.id}.distinct().size==b.spots.size)
        require(b.journeys.map{it.id}.distinct().size==b.journeys.size)
        require(b.observations.map{it.id}.distinct().size==b.observations.size)
        val ids=b.spots.map{it.id}.toSet()
        b.votes.forEach{require(it.spotId in ids&&it.quality in listOf("WEAK","STRONG")&&it.at>0)}
        b.summaries.forEach{require(it.started>0&&it.samples>=0&&it.gaps>=0&&it.alerts>=0)}
        b.alerts.forEach{require(it.spotId in ids&&it.distance.isFinite()&&it.leadSeconds.isFinite())}
    }
    suspend fun restore(context:Context,db:AppDatabase,b:Backup)=withContext(Dispatchers.IO){
        validate(b)
        db.withTransaction{
            val d=db.dao();clear(d)
            b.journeys.forEach{d.insertJourney(it)}
            b.observations.forEach{d.insertObservation(it)}
            b.legacySpots.forEach{d.upsertZone(it)}
            b.legacyVotes.forEach{d.vote(it)}
            b.spots.forEach{d.putSpot(it)}
            b.votes.forEach{d.routeVote(it)}
            b.summaries.forEach{d.summary(if(it.status in listOf("ACTIVE","PAUSED"))it.copy(status="INTERRUPTED") else it)}
            b.alerts.forEach{d.alert(it)}
        }
        NetworkContext.restoreSalt(context,b.networkSalt)
    }
    suspend fun clear(d:SignalDao){
        d.deleteAlerts();d.deleteRouteVotes();d.deleteRouteSpots();d.deleteSummaries()
        d.deleteObservations();d.deleteVotes();d.deleteZones();d.deleteJourneys()
    }
}
private fun java.io.InputStream.readBytesLimited(max:Int):ByteArray{
    val output=java.io.ByteArrayOutputStream()
    val buffer=ByteArray(8192)
    while(true){val n=read(buffer);if(n<0)break;require(output.size()+n<=max){"Backup exceeds 20 MB"};output.write(buffer,0,n)}
    return output.toByteArray()
}
