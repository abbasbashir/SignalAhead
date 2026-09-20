package com.signalahead.app.tracking

import kotlin.math.*

data class Fix(val lat:Double,val lng:Double,val at:Long,val accuracy:Double,val speed:Double?,val bearing:Double?)
data class RadioPoint(val fix:Fix,val quality:String,val network:String,val radioTime:Long)
data class Segment(val first:RadioPoint,val last:RadioPoint)
data class Evidence(val journey:String,val quality:String,val at:Long)
data class Score(val quality:String,val confidence:Double,val journeys:Int)
data class Approach(val metres:Double,val seconds:Double)

object PredictionCore {
    private const val R=6371000.0
    fun distance(a:Fix,b:Fix):Double {
        val p1=Math.toRadians(a.lat);val p2=Math.toRadians(b.lat)
        val dp=p2-p1;val dl=Math.toRadians(b.lng-a.lng)
        val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2)
        return 2*R*asin(sqrt(h.coerceIn(0.0,1.0)))
    }
    fun bearing(a:Fix,b:Fix):Double{
        val p1=Math.toRadians(a.lat);val p2=Math.toRadians(b.lat);val dl=Math.toRadians(b.lng-a.lng)
        return (Math.toDegrees(atan2(sin(dl)*cos(p2),cos(p1)*sin(p2)-sin(p1)*cos(p2)*cos(dl)))+360)%360
    }
    fun angle(a:Double,b:Double)=abs(((a-b+540)%360)-180)
    fun segment(a:RadioPoint?,b:RadioPoint):Segment?{
        if(a==null||a.network!=b.network||a.quality!=b.quality||a.radioTime==b.radioTime)return null
        val dt=(b.fix.at-a.fix.at)/1000.0
        if(dt !in 3.0..100.0||a.fix.accuracy>100||b.fix.accuracy>100)return null
        val d=distance(a.fix,b.fix)
        if(d !in 25.0..1500.0||d/dt>60)return null
        val direction=bearing(a.fix,b.fix)
        if(a.fix.bearing!=null&&angle(direction,a.fix.bearing)>45)return null
        if(b.fix.bearing!=null&&angle(direction,b.fix.bearing)>45)return null
        return Segment(a,b)
    }
    fun score(votes:List<Evidence>,now:Long):Score{
        // Restarting a journey must not inflate confidence. Separate supporting visits by 30 min.
        var last=Long.MIN_VALUE/2
        val independent=votes.filter{it.at<=now&&now-it.at<=90L*86400000}
            .groupBy{it.journey}.map{it.value.maxBy{v->v.at}}.sortedBy{it.at}
            .filter{if(it.at-last>=1800000){last=it.at;true}else false}
        var weak=0.0;var strong=0.0
        independent.forEach{val w=exp(-(now-it.at)/ (14.0*86400000));if(it.quality=="WEAK")weak+=w else strong+=w}
        val quality=if(weak>=strong)"WEAK" else "STRONG"
        val support=independent.count{it.quality==quality}
        val total=weak+strong
        // Fresh opposing evidence immediately downgrades a formerly confirmed zone.
        val opposition=independent.lastOrNull()?.quality?.let{it!=quality}==true
        val confidence=if(total==0.0)0.0 else max(weak,strong)/total*min(1.0,support/3.0)*(if(opposition).6 else 1.0)
        return Score(quality,confidence,support)
    }
    fun approach(current:Fix,previous:Fix?,start:Fix,end:Fix,confidence:Double,observed:Long,now:Long,leadTarget:Double=120.0):Approach?{
        val speed=current.speed?:return null;val heading=current.bearing?:return null
        if(speed<1||current.accuracy>100||now-current.at !in 0..30000||now-observed !in 0..30L*86400000||confidence<.66)return null
        if(previous==null||now-previous.at>90000||previous.bearing==null||angle(previous.bearing,heading)>25)return null
        if(angle(bearing(start,end),heading)>35)return null
        val d=distance(current,start)
        val relative=Math.toRadians(angle(bearing(current,start),heading))
        val along=d*cos(relative);val cross=abs(d*sin(relative))
        if(along<=max(80.0,current.accuracy)||cross>max(65.0,current.accuracy*1.5))return null
        val range=(speed*leadTarget).coerceIn(300.0,3500.0)
        if(along>range||distance(previous,start)<d-15)return null
        return Approach(along,along/speed)
    }
}
