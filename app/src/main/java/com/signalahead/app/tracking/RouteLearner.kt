package com.signalahead.app.tracking

import com.signalahead.app.data.*
import java.util.UUID

class RouteLearner(private val dao:SignalDao){
    private var previous:RadioPoint?=null
    fun reset(){previous=null}
    suspend fun record(point:RadioPoint,journey:String){
        val segment=PredictionCore.segment(previous,point);previous=point
        if(segment==null)return
        val a=segment.first.fix;val b=segment.last.fix
        val direction=PredictionCore.bearing(a,b)
        val old=dao.networkSpots(point.network).filter{
            PredictionCore.angle(direction,it.direction)<35&&
            PredictionCore.distance(a,Fix(it.startLat,it.startLng,0,0.0,null,null))<200&&
            PredictionCore.distance(b,Fix(it.endLat,it.endLng,0,0.0,null,null))<300
        }.minByOrNull{PredictionCore.distance(a,Fix(it.startLat,it.startLng,0,0.0,null,null))}
        val id=old?.id?:UUID.randomUUID().toString()
        dao.routeVote(RouteVote(id,journey,point.quality,b.at))
        val score=PredictionCore.score(dao.routeVotes(id,b.at-90L*86400000).map{Evidence(it.journeyId,it.quality,it.at)},b.at)
        dao.putSpot(RouteSpot(id,point.network,old?.startLat?:a.lat,old?.startLng?:a.lng,old?.endLat?:b.lat,old?.endLng?:b.lng,
            old?.direction?:direction,score.quality,score.confidence,score.journeys,b.at,old?.name?:"",old?.muted?:false,old?.lastWarned?:0))
    }
}
