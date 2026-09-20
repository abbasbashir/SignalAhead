package com.signalahead.app.tracking

import android.location.Location
import com.signalahead.app.data.*
import kotlin.math.*

object ZoneEngine {
    private const val CELL=0.002
    fun cellKey(lat:Double,lng:Double)="${floor(lat/CELL).toLong()}:${floor(lng/CELL).toLong()}"
    suspend fun update(dao:SignalDao,o:Observation) {
        val quality=SamplingPolicy.category(o.signalLevel)?:return
        if(o.observationConfidence<.6)return
        val key=cellKey(o.latitude,o.longitude)
        val old=dao.zone(key)
        dao.vote(PlaceVote(key,o.journeyId,quality,o.timestamp))
        val votes=dao.votes(key,o.timestamp-90L*86_400_000)
        val count=votes.count{it.quality==quality}
        dao.upsertZone(WeakZone(
            key,(floor(o.latitude/CELL)+.5)*CELL,(floor(o.longitude/CELL)+.5)*CELL,
            180.0,count,(old?.observations?:0)+1,
            (count/3.0).coerceAtMost(1.0)*count/votes.size.coerceAtLeast(1),
            SamplingPolicy.status(count),o.timestamp,old?.warningSuppressed?:false,old?.lastWarnedAt,quality
        ))
    }
    fun distanceAndBearing(from:Location,z:WeakZone):Pair<Float,Float>{
        val result=FloatArray(2)
        Location.distanceBetween(from.latitude,from.longitude,z.centreLat,z.centreLng,result)
        return result[0] to result[1]
    }
    fun angleDelta(a:Float,b:Float)=abs(((a-b+540)%360)-180)
}
