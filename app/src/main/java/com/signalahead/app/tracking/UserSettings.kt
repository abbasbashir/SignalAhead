package com.signalahead.app.tracking

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

data class Preferences(
    val mode:String="Balanced",
    val warnings:Boolean=true, val confirmedOnly:Boolean=true,
    val refreshHours:Int=24, val retentionDays:Int=30, val theme:String="System",
    val autoStart:Boolean=false
)
class UserSettings(context:Context){
    private val prefs=context.getSharedPreferences("preferences",Context.MODE_PRIVATE)
    val state=MutableStateFlow(Preferences(
        prefs.getString("mode","Balanced")!!,
        prefs.getBoolean("warnings",true),prefs.getBoolean("confirmed",true),
        prefs.getInt("refresh",24),prefs.getInt("retention",30),prefs.getString("theme","System")!!,
        prefs.getBoolean("auto",false)
    ))
    fun save(p:Preferences){
        prefs.edit().putBoolean("auto",p.autoStart).putString("mode",p.mode)
            .putBoolean("warnings",p.warnings).putBoolean("confirmed",p.confirmedOnly)
            .putInt("refresh",p.refreshHours).putInt("retention",p.retentionDays)
            .putString("theme",p.theme).apply()
        state.value=p
    }
}
data class LiveState(
    val phase:String="Ready", val level:Int?=null, val sampledAt:Long?=null,
    val accuracy:Float?=null, val samples:Int=0, val startedAt:Long?=null,
    val policy:String="Dashboard only • no location recording",
    val next:String?=null, val error:String?=null
)
object Live { val state=MutableStateFlow(LiveState()) }
object SamplingPolicy {
    fun seconds(mode:String,lowBattery:Boolean,stationary:Boolean,known:Boolean,approaching:Boolean):Long =
        when {
            lowBattery -> 90
            stationary -> 120
            approaching -> if(mode=="Eco") 30 else 15
            known -> 60
            mode=="Eco" -> 60
            mode=="Responsive" -> 15
            else -> 30
        }
    fun recheck(last:Long?, now:Long, hours:Int)=last==null || now-last>=hours*3_600_000L
    fun category(level:Int?)=when(level){0,1->"WEAK";3,4->"STRONG";else->null}
    fun status(votes:Int)=when{votes>=3->"CONFIRMED";votes>=2->"POSSIBLE";else->"UNCONFIRMED"}
}
