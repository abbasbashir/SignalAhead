package com.signalahead.app.tracking

import android.location.Location
import com.signalahead.app.data.*
import kotlin.math.*

object ZoneEngine {
    private const val CELL=0.0015 // roughly 165m latitude; intentionally coarse
    fun cellKey(lat:Double,lng:Double)="${floor(lat/CELL).toLong()}:${floor(lng/CELL).toLong()}"
    suspend fun update(dao:SignalDao,o:Observation) {
        if((o.signalLevel?:4)>1 || o.observationConfidence<0.55) return
        val d=CELL/2
        val journeys=dao.weakJourneyCount(o.latitude-d,o.latitude+d,o.longitude-d,o.longitude+d)
        val count=dao.weakObservationCount(o.latitude-d,o.latitude+d,o.longitude-d,o.longitude+d)
        val status=when { journeys>=3->ZoneStatus.CONFIRMED; journeys>=2->ZoneStatus.POSSIBLE; else->ZoneStatus.UNCONFIRMED }
        dao.upsertZone(WeakZone(cellKey(o.latitude,o.longitude),o.latitude,o.longitude,220.0,journeys,count,(journeys/3.0).coerceAtMost(1.0),status.name,o.timestamp))
    }
    fun distanceAndBearing(from:Location,z:WeakZone):Pair<Float,Float>{ val out=FloatArray(2); Location.distanceBetween(from.latitude,from.longitude,z.centreLat,z.centreLng,out); return out[0] to out[1] }
    fun angleDelta(a:Float,b:Float)=abs(((a-b+540)%360)-180)
}
