package com.signalahead.app.tracking
import org.junit.Assert.*
import org.junit.Test

class PredictionCoreTest{
    private val now=10000000000L
    private fun f(lat:Double,lng:Double,t:Long=now,heading:Double?=0.0,speed:Double?=10.0,accuracy:Double=10.0)=Fix(lat,lng,t,accuracy,speed,heading)
    @Test fun movingAcrossCellsCanLearn(){
        val a=RadioPoint(f(0.0,0.0,now-30000),"WEAK","A",1)
        val b=RadioPoint(f(.004,0.0),"WEAK","A",2)
        assertNotNull(PredictionCore.segment(a,b))
    }
    @Test fun staleRadioOrChangedNetworkCannotTeach(){
        val a=RadioPoint(f(0.0,0.0,now-30000),"WEAK","A",1)
        assertNull(PredictionCore.segment(a,RadioPoint(f(.004,0.0),"WEAK","B",2)))
        assertNull(PredictionCore.segment(a,RadioPoint(f(.004,0.0),"WEAK","A",1)))
    }
    @Test fun interruptedOrTurningSegmentsAreRejected(){
        val a=RadioPoint(f(0.0,0.0,now-200000),"WEAK","A",1)
        assertNull(PredictionCore.segment(a,RadioPoint(f(.004,0.0),"WEAK","A",2)))
        assertNull(PredictionCore.segment(a.copy(fix=f(0.0,0.0,now-30000,90.0)),RadioPoint(f(.004,0.0),"WEAK","A",2)))
    }
    @Test fun restartingDoesNotConfirm(){
        val votes=(0..9).map{Evidence("$it","WEAK",now-it*10000L)}
        assertEquals(1,PredictionCore.score(votes,now).journeys)
    }
    @Test fun contradictionsReduceConfidence(){
        val votes=(1..3).map{Evidence("$it","WEAK",now-it*86400000L)}
        assertTrue(PredictionCore.score(votes,now).confidence>.9)
        assertTrue(PredictionCore.score(votes+Evidence("new","STRONG",now),now).confidence<.66)
    }
    @Test fun alongRouteWarnsButParallelRoadDoesNot(){
        val start=f(.01,0.0);val end=f(.013,0.0)
        assertNotNull(PredictionCore.approach(f(0.0,0.0),f(-.001,0.0,now-15000),start,end,.9,now,now))
        assertNull(PredictionCore.approach(f(0.0,.005),f(-.001,.005,now-15000),start,end,.9,now,now))
    }
    @Test fun wrongDirectionOrStaleLocationCannotWarn(){
        val start=f(.01,0.0);val end=f(.013,0.0)
        assertNull(PredictionCore.approach(f(0.0,0.0,heading=180.0),f(.001,0.0,now-15000,180.0),start,end,.9,now,now))
        assertNull(PredictionCore.approach(f(0.0,0.0,now-60000),f(-.001,0.0,now-75000),start,end,.9,now,now))
    }
    @Test fun oldZonesAndPoorAccuracyCannotWarn(){
        val start=f(.01,0.0);val end=f(.013,0.0)
        assertNull(PredictionCore.approach(f(0.0,0.0,accuracy=500.0),f(-.001,0.0,now-15000),start,end,.9,now,now))
        assertNull(PredictionCore.approach(f(0.0,0.0),f(-.001,0.0,now-15000),start,end,.9,now-31L*86400000,now))
    }
}
