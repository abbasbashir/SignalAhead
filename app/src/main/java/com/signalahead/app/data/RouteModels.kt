package com.signalahead.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity data class RouteSpot(
    @PrimaryKey val id:String, val network:String,
    val startLat:Double,val startLng:Double,val endLat:Double,val endLng:Double,
    val direction:Double,val quality:String,val confidence:Double,val journeys:Int,
    val lastObserved:Long,val name:String="",val muted:Boolean=false,val lastWarned:Long=0
)
@Entity(primaryKeys=["spotId","journeyId"])
data class RouteVote(val spotId:String,val journeyId:String,val quality:String,val at:Long)
@Entity data class JourneySummary(
    @PrimaryKey val id:String,val started:Long,val ended:Long=0,
    val samples:Int=0,val weakSamples:Int=0,val gaps:Int=0,val alerts:Int=0,
    val pausedMillis:Long=0,val status:String="ACTIVE"
)
@Entity data class AlertEvent(
    @PrimaryKey val id:String,val journeyId:String,val spotId:String,val at:Long,
    val distance:Double,val leadSeconds:Double,val delivered:Boolean,
    val feedback:String="",val outcome:String="UNVERIFIED"
)
